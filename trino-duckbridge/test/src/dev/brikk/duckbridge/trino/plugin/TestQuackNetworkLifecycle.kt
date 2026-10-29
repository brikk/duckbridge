package dev.brikk.duckbridge.trino.plugin

import com.gizmodata.quack.jdbc.sql.QuackDriver
import com.gizmodata.quack.jdbc.sql.QuackConnection
import com.gizmodata.quack.jdbc.sql.QuackResultSet
import eu.rekawek.toxiproxy.model.ToxicDirection
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.rnorth.ducttape.unreliables.Unreliables
import org.testcontainers.containers.Network
import org.testcontainers.toxiproxy.ToxiproxyContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

private fun control(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT 42").use { rows ->
            assertThat(rows.next()).isTrue()
            assertThat(rows.getInt(1)).isEqualTo(42)
            assertThat(rows.next()).isFalse()
        }
    }
}

/** Actual TCP faults. No fake ResultSet, transparent SQL retry or shared/user service interruption. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestQuackNetworkLifecycle {
    private lateinit var network: Network
    private lateinit var server: TestingQuackServer
    private lateinit var proxy: ToxiproxyContainer
    private lateinit var endpoint: Proxy

    @BeforeAll
    fun setUp() {
        network = Network.newNetwork()
        server = TestingQuackServer(network)
        proxy = ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")).withNetwork(network)
        proxy.start()
        endpoint = ToxiproxyClient(proxy.host, proxy.controlPort).createProxy("quack", "0.0.0.0:8666", server.proxyTarget())
    }

    @AfterAll
    fun tearDown() {
        if (::proxy.isInitialized) proxy.close()
        if (::server.isInitialized) server.close()
        if (::network.isInitialized) network.close()
    }

    private fun connection(timeout: String = "PT1S"): Connection = QuackDriver().connect(
        "jdbc:quack://${proxy.host}:${proxy.getMappedPort(8666)}", Properties().apply {
            setProperty("token", server.token)
            setProperty("connectTimeout", "PT1S")
            setProperty("requestTimeout", timeout)
        },
    )

    @Test
    fun testConnectionCutFailsQueryAndFreshSessionRecoversAfterRestore() {
        connection().use { connection ->
            control(connection)
            endpoint.disable()
            try {
                assertThatThrownBy { control(connection) }.isInstanceOf(SQLException::class.java)
            } finally { endpoint.enable() }
        }
        connection().use { control(it) }
    }

    @Test
    fun testBlackholedResponseHasABoundedRequestTimeoutAndClose() {
        val connection = connection()
        control(connection)
        val toxic = endpoint.toxics().timeout("response-blackhole", ToxicDirection.DOWNSTREAM, 0)
        try {
            val start = System.nanoTime()
            assertThatThrownBy { control(connection) }.isInstanceOf(SQLException::class.java)
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5))
            val closeStart = System.nanoTime()
            connection.close()
            assertThat(Duration.ofNanos(System.nanoTime() - closeStart)).isLessThan(Duration.ofSeconds(5))
        } finally {
            toxic.remove()
            connection.close()
        }
        connection().use { control(it) }
    }

    /** Strict acceptance gate; run :qualifyQuackStreaming. Published 0.10 failed this contract. */
    fun qualifyPartialResultNeverBecomesSuccessfulEofAfterConnectionLoss() {
        connection("PT5S").use { connection ->
            connection.createStatement().use { statement ->
                // PREPARE drains whole batches. A compressed sequence can fit millions of rows
                // in one native batch yet exceed the client's expanded-allocation budget.
                // Flat hash values force multiple default-sized batches without raising limits.
                val cursor = (connection as QuackConnection).session().cursor(
                    "SELECT CAST(hash(range) & 9223372036854775807 AS BIGINT) AS value FROM range(8000000)",
                )
                assertThat(cursor.materializedRowCount()).describedAs("Initial inline prefix must require another wire fetch")
                    .isLessThan(8_000_000)
                // This is the same public JDBC wrapper used by QuackStatement.execute(). The
                // cursor counter proves that the network fault cannot hit a fully buffered result.
                QuackResultSet(statement, cursor).use { rows ->
                    assertThat(rows.next()).isTrue()
                    endpoint.disable()
                    try {
                        val failure = org.assertj.core.api.Assertions.catchThrowable { while (rows.next()) rows.getLong(1) }
                        assertThat(failure).withFailMessage("A partial stream must fail, not return EOF at row ${rows.row}")
                            .isInstanceOf(SQLException::class.java)
                    } finally { endpoint.enable() }
                }
            }
        }
        connection().use { control(it) }
    }

    @Test
    fun testServerRestartCannotReuseAnOldSessionAndNewSessionRecovers() {
        val old = connection()
        control(old)
        try {
            server.restart()
            Unreliables.retryUntilSuccess(30, TimeUnit.SECONDS) { connection().use { control(it) } }
            assertThatThrownBy { control(old) }.isInstanceOf(SQLException::class.java)
        } finally { old.close() }
        connection().use { control(it) }
    }

    /** No timeout-as-cancel false positive: server fallback is 10s, request timeout 30s. */
    fun qualifyStatementCancellation() {
        val worker = Executors.newSingleThreadExecutor()
        connection("PT30S").use { connection ->
            connection.createStatement().use { it.execute("SET max_execution_time=10000") }
            connection.createStatement().use { statement ->
                val marker = "quack_cancellation_qualification"
                val query = worker.submit { statement.executeQuery("/* $marker */ SELECT sum(range) FROM range(1000000000000)").close() }
                try {
                    Unreliables.retryUntilSuccess(3, TimeUnit.SECONDS) {
                        assertThat(query.isDone).isFalse()
                        assertThat(activeQueryContains(marker)).isTrue()
                    }
                    statement.cancel()
                    val failure = org.assertj.core.api.Assertions.catchThrowable { query.get(2, TimeUnit.SECONDS) }
                    assertThat(failure).describedAs("cancel must interrupt the active query, not wait for its 10s deadline")
                        .isInstanceOf(java.util.concurrent.ExecutionException::class.java)
                    assertThat(failure.cause?.message.orEmpty()).containsIgnoringCase("interrupt")
                    assertThat((failure.cause as SQLException).sqlState).isEqualTo("57014")
                } finally {
                    query.cancel(true)
                    worker.shutdownNow()
                    worker.awaitTermination(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    fun qualifyDisconnectDeadline() {
        val connection = connection("PT30S")
        control(connection)
        val worker = Executors.newSingleThreadExecutor()
        val toxic = endpoint.toxics().timeout("disconnect-blackhole", ToxicDirection.DOWNSTREAM, 0)
        val closing = worker.submit { connection.close() }
        var exceeded = false
        try {
            try { closing.get(3, TimeUnit.SECONDS) } catch (_: TimeoutException) { exceeded = true }
        } finally {
            toxic.remove()
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            connection.close()
        }
        assertThat(exceeded).describedAs("DISCONNECT must have its own <=2s request budget, not the general 30s timeout").isFalse()
    }

    private fun activeQueryContains(marker: String): Boolean = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM quack_active_connections()").use { rows ->
                rowsContainMarker(rows, marker)
            }
        }
    }

    private fun rowsContainMarker(rows: java.sql.ResultSet, marker: String): Boolean {
        while (rows.next()) {
            if ((1..rows.metaData.columnCount).any { rows.getString(it)?.contains(marker) == true }) return true
        }
        return false
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val fixture = TestQuackNetworkLifecycle()
            try {
                fixture.setUp()
                when (args.single()) {
                    "streaming" -> fixture.qualifyPartialResultNeverBecomesSuccessfulEofAfterConnectionLoss()
                    "cancel" -> fixture.qualifyStatementCancellation()
                    "disconnect" -> fixture.qualifyDisconnectDeadline()
                    else -> error("Unknown qualification gate")
                }
            } finally { fixture.tearDown() }
        }
    }
}
