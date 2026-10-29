package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.rnorth.ducttape.unreliables.Unreliables
import java.util.concurrent.TimeUnit

/** Real elapsed lease time, server snapshots, and the driver's actual heartbeat worker threads. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestQuackHeartbeatAndSessions {
    private lateinit var server: TestingQuackServer

    @BeforeAll
    fun setUp() { server = TestingQuackServer() }

    @AfterAll
    fun tearDown() { if (::server.isInitialized) server.close() }

    private fun heartbeatThreads(): Set<Thread> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name == "quack-heartbeat" }.toSet()

    private fun assertGone(id: String, workers: Set<Thread>) {
        Unreliables.retryUntilSuccess(5, TimeUnit.SECONDS) {
            assertThat(sessionSnapshots(server).map { it.id }).doesNotContain(id)
            assertThat(workers.filter { it.isAlive }).isEmpty()
        }
    }

    @Test
    @Timeout(110)
    fun testHeartbeatsPreserveTheSameIdleSessionBeyondItsLease() {
        val before = heartbeatThreads()
        val connection = lifecycleConnection(server)
        val id = connection.session().connectionId()
        val ownedWorkers = heartbeatThreads() - before
        try {
            assertThat(ownedWorkers).hasSize(1)
            connection.createStatement().use { statement ->
                statement.execute("CREATE TEMP TABLE lifecycle_heartbeat_marker(v INTEGER)")
                statement.execute("INSERT INTO lifecycle_heartbeat_marker VALUES (42)")
            }
            assertThat(sessionSnapshots(server).map { it.id }).contains(id)
            // Published 0.11 requests a 60s v3 lease and renews it every 20s. Do not send
            // target-session SQL while idle: the heartbeat alone must preserve the lease.
            Thread.sleep(65_000)
            assertThat(connection.session().connectionId()).isEqualTo(id)
            assertThat(sessionSnapshots(server).map { it.id }).contains(id)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT v FROM lifecycle_heartbeat_marker").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(42)
                    assertThat(rows.next()).isFalse()
                }
            }
        } finally { connection.close() }
        assertGone(id, ownedWorkers)
    }

    @Test
    fun testPhysicalCloseRemovesSessionQueryStateAndHeartbeatWorker() {
        repeat(20) {
            val before = heartbeatThreads()
            val connection = lifecycleConnection(server)
            val id = connection.session().connectionId()
            val workers = heartbeatThreads() - before
            try {
                assertThat(workers).hasSize(1)
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM range(100000)").use { rows -> assertThat(rows.next()).isTrue() }
                }
            } finally { connection.close() }
            connection.close() // idempotent physical close must not resurrect work
            assertGone(id, workers)
        }
        assertThat(sessionSnapshots(server)).hasSize(1) // only the observer, no orphan idle/cache rows
    }
}
