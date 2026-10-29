package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.type.BigintType.BIGINT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.rnorth.ducttape.unreliables.Unreliables
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real QUACK executor, native Arrow reader and server-side session diagnostics. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestQuackArrowSourceLifecycle {
    private lateinit var server: TestingQuackServer
    private lateinit var executor: QuackDuckBridgeExecutor

    @BeforeAll
    fun setUp() {
        server = TestingQuackServer()
        executor = QuackDuckBridgeExecutor(server.host, server.mappedPort, server.token, DuckDbTuning.defaults(), null)
    }

    @AfterAll
    fun tearDown() { if (::server.isInitialized) server.close() }

    private fun source(sql: String = "SELECT * FROM range(2503)"): DuckBridgeExecutorPageSource {
        val context = executor.execute(DuckBridgeExecutor.ExecutionRequest(sql, "UTC"))
        return DuckBridgeExecutorPageSource(context, DuckBridgeArrowToPageConverter(listOf(BIGINT)), 1)
    }

    private fun assertTerminal(source: DuckBridgeExecutorPageSource) {
        assertThat(source.isFinished()).isTrue()
        assertThat(source.getNextSourcePage()).isNull()
        assertThat(source.getMemoryUsage()).isZero()
        source.close()
        assertThat(source.getNextSourcePage()).isNull()
    }

    private fun assertNoSessions() {
        Unreliables.retryUntilSuccess(10, TimeUnit.SECONDS) {
            // Only the independent observer may remain. Old query text/cache cannot hide in an
            // idle pooled session because this executor owns a fresh native connection per query.
            assertThat(sessionSnapshots(server)).hasSize(1)
        }
    }

    @Test
    fun testEofReleasesRemoteSessionAndPagesOutliveTheReader() {
        val source = source()
        try {
            val pages = buildList { while (!source.isFinished()) source.getNextSourcePage()?.let { add(it) } }
            assertTerminal(source)
            var count = 0L
            for (page in pages) {
                for (index in 0 until page.positionCount) assertThat(BIGINT.getLong(page.getBlock(0), index)).isEqualTo(count++)
            }
            assertThat(count).isEqualTo(2503)
            assertNoSessions()
        } finally { source.close() }
    }

    @Test
    fun testCloseBeforeReadAndConversionFailureReleaseRemoteSessions() {
        val closed = source()
        closed.close()
        assertTerminal(closed)
        assertNoSessions()
        val failing = source("SELECT 'wrong-declared-vector-type'")
        try {
            assertThatThrownBy { failing.getNextSourcePage() }.isInstanceOf(ClassCastException::class.java)
            assertTerminal(failing)
            assertNoSessions()
        } finally { failing.close() }
    }

    @Test
    fun testFailedRemoteExecutionDoesNotLeaveAnOrphanSession() {
        assertThatThrownBy { source("SELECT * FROM lifecycle_missing_relation") }.isInstanceOf(SQLException::class.java)
            .hasMessageContaining("lifecycle_missing_relation")
        assertNoSessions()
        lifecycleConnection(server).use { lifecycleControl(it) }
    }

    @Test
    fun testConcurrentReadsAndClosesReleaseEveryOwnedRemoteSession() {
        val workers = Executors.newFixedThreadPool(3)
        try {
            repeat(8) {
                val source = source("SELECT * FROM range(100000)")
                val firstPage = CountDownLatch(1)
                try {
                    val reading = workers.submit<Long> { readPrefix(source, firstPage) }
                    assertThat(firstPage.await(5, TimeUnit.SECONDS)).isTrue()
                    val closing = (1..2).map { workers.submit { source.close() } }
                    closing.forEach { it.get(5, TimeUnit.SECONDS) }
                    assertThat(reading.get(5, TimeUnit.SECONDS)).isBetween(1L, 100000L)
                    assertTerminal(source)
                    assertNoSessions()
                } finally { source.close() }
            }
        } finally {
            workers.shutdownNow()
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun readPrefix(source: DuckBridgeExecutorPageSource, firstPage: CountDownLatch): Long {
        var count = 0L
        while (true) {
            val page = source.getNextSourcePage() ?: return count
            for (index in 0 until page.positionCount) assertThat(BIGINT.getLong(page.getBlock(0), index)).isEqualTo(count++)
            firstPage.countDown()
            Thread.yield()
        }
    }
}
