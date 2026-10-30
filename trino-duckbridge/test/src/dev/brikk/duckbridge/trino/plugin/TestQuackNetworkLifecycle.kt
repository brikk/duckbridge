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
import java.sql.Statement
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

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

    private fun connection(timeout: String = "PT1S", bridgeControl: Boolean = false): Connection = QuackDriver().connect(
        if (bridgeControl) "jdbc:quack://${proxy.currentContainerInfo.networkSettings.networks.values.single().ipAddress}:8666"
        else "jdbc:quack://${proxy.host}:${proxy.getMappedPort(8666)}", Properties().apply {
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
    fun qualifyPartialResultNeverBecomesSuccessfulEofAfterConnectionLoss(bridgeControl: Boolean = false) {
        val started = System.nanoTime()
        println("STREAM route=${if (bridgeControl) "direct-bridge-diagnostic-control" else "published-port-acceptance"}")
        connection("PT5S", bridgeControl).use { connection ->
            connection.createStatement().use { statement ->
                // PREPARE drains whole batches. A compressed sequence can fit millions of rows
                // in one native batch yet exceed the client's expanded-allocation budget.
                // Flat hash values force multiple default-sized batches without raising limits.
                println("STREAM initial-start session=${(connection as QuackConnection).session().connectionId()}")
                val cursor = connection.session().cursor(
                    "SELECT CAST(hash(range) & 9223372036854775807 AS BIGINT) AS value FROM range(8000000)",
                )
                println("STREAM initial-ready rows=${cursor.materializedRowCount()} elapsed=${Duration.ofNanos(System.nanoTime() - started)}")
                assertThat(cursor.materializedRowCount()).describedAs("Initial inline prefix must require another wire fetch")
                    .isLessThan(8_000_000)
                // This is the same public JDBC wrapper used by QuackStatement.execute(). The
                // cursor counter proves that the network fault cannot hit a fully buffered result.
                QuackResultSet(statement, cursor).use { rows ->
                    assertThat(rows.next()).isTrue()
                    endpoint.disable()
                    println("STREAM deliberate-cut elapsed=${Duration.ofNanos(System.nanoTime() - started)}")
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
            lifecycleConnection(server).use { lifecycleControl(it) } // direct observer must not retain a cached host port
            assertThatThrownBy { control(old) }.isInstanceOf(SQLException::class.java)
        } finally { old.close() }
        connection().use { control(it) }
    }

    /** No timeout-as-cancel false positive: server fallback is 15s, request timeout 30s. */
    fun qualifyStatementCancellation() {
        withActiveQuery("quack_cancellation_qualification") { connection, statement, query, _ ->
            statement.cancel()
            assertCancelled(query)
            assertThat(connection.isClosed).isFalse()
            control(connection)
        }
    }

    @Test
    fun testCancellationAfterResponseLossFailsTheQueryAndReleasesTheSession() {
        var id = ""
        withActiveQuery("quack_response_loss_cancellation") { connection, statement, query, workers ->
            id = (connection as QuackConnection).session().connectionId()
            val toxic = endpoint.toxics().timeout("cancel-response-loss", ToxicDirection.DOWNSTREAM, 0)
            val cancelling = workers.submit { statement.cancel() }
            try {
                // Observe the real server-side cancel via a direct independent control connection,
                // not the faulted proxy. Then restore ACK delivery; never replay the target SQL.
                Unreliables.retryUntilSuccess(5, TimeUnit.SECONDS) {
                    assertThat(sessionSnapshots(server).find { it.id == id }?.state).isEqualTo("cancelled")
                }
                assertThat(cancelling.isDone).isFalse()
            } finally { toxic.remove() }
            val cancelFailure = org.assertj.core.api.Assertions.catchThrowable { cancelling.get(5, TimeUnit.SECONDS) }
            assertCancelled(query)
            if (cancelFailure == null) {
                assertThat(connection.isClosed).isFalse()
                control(connection)
            } else {
                // Removing a timeout toxic can sever its held TCP response. A lost ACK may
                // therefore fail closed; it must never turn into successful SQL or blind reuse.
                assertThat(cancelFailure).isInstanceOf(java.util.concurrent.ExecutionException::class.java)
                assertThat(cancelFailure.cause).isInstanceOf(SQLException::class.java)
                assertThat(cancelFailure.cause?.message.orEmpty()).contains("Cancellation could not be confirmed")
                assertThat(connection.isClosed).isTrue()
                assertThatThrownBy { control(connection) }.isInstanceOf(SQLException::class.java).hasMessageContaining("closed")
            }
        }
        Unreliables.retryUntilSuccess(5, TimeUnit.SECONDS) {
            assertThat(sessionSnapshots(server).map { it.id }).doesNotContain(id)
        }
    }

    /** Deferred peer acceptance gate, assertions unchanged; run :qualifyQuackLeaseReaping. */
    fun qualifyHardPartitionClosesTheCallerAndLeaseExpiryReapsTheOrphan() {
        var id = ""
        withActiveQuery("quack_hard_partition_lease_cleanup") { connection, _, query, workers ->
            id = (connection as QuackConnection).session().connectionId()
            endpoint.disable()
            try {
                val failure = org.assertj.core.api.Assertions.catchThrowable { query.get(3, TimeUnit.SECONDS) }
                assertThat(failure).isInstanceOf(java.util.concurrent.ExecutionException::class.java)
                assertThat(failure.cause).isInstanceOf(SQLException::class.java)
                workers.submit { connection.close() }.get(3, TimeUnit.SECONDS)
                assertThat(connection.isClosed).isTrue()
                assertThatThrownBy { control(connection) }.isInstanceOf(SQLException::class.java).hasMessageContaining("closed")
                // DISCONNECT cannot be delivered through the cut. Prove a real abandoned logical
                // session exists before testing server lease cleanup, rather than counting close().
                assertThat(sessionSnapshots(server).map { it.id }).contains(id)
            } finally { endpoint.enable() }
        }
        // No retry/reconnect or target heartbeat is possible after physical close. The default
        // 60s lease, not an observer query or a forced server restart, must remove this orphan.
        Thread.sleep(65_000)
        assertThat(sessionSnapshots(server).map { it.id }).doesNotContain(id)
        assertThat(sessionSnapshots(server)).hasSize(1)
        connection().use { control(it) }
    }

    /** Strict independent CANCEL+fail-closed budget, even when the general timeout is 30s. */
    fun qualifyCancellationUnderPartition() {
        withActiveQuery("quack_partition_cancellation") { connection, statement, _, workers ->
            val toxic = endpoint.toxics().timeout("cancel-partition", ToxicDirection.DOWNSTREAM, 0)
            val cancelling = workers.submit { statement.cancel() }
            var failure: Throwable? = null
            try {
                failure = org.assertj.core.api.Assertions.catchThrowable { cancelling.get(5, TimeUnit.SECONDS) }
            } finally {
                toxic.remove()
                if (failure is TimeoutException) runCatching { cancelling.get(5, TimeUnit.SECONDS) }
            }
            assertThat(failure).describedAs("CANCEL plus fail-closed teardown must finish within 5s, not the general 30s request timeout")
                .isInstanceOf(java.util.concurrent.ExecutionException::class.java)
            assertThat(failure?.cause).isInstanceOf(SQLException::class.java)
            assertThat(connection.isClosed).describedAs("Unconfirmed cancellation must not return a usable ambiguous session").isTrue()
        }
    }

    private fun withActiveQuery(marker: String, action: (Connection, Statement, Future<*>, ExecutorService) -> Unit) {
        val workers = Executors.newFixedThreadPool(2)
        connection("PT30S").use { connection ->
            connection.createStatement().use { it.execute("SET max_execution_time=15000") }
            connection.createStatement().use { statement ->
                val query = workers.submit { statement.executeQuery("/* $marker */ SELECT sum(range) FROM range(1000000000000)").close() }
                try {
                    Unreliables.retryUntilSuccess(3, TimeUnit.SECONDS) {
                        assertThat(query.isDone).isFalse()
                        assertThat(activeQueryContains(marker)).isTrue()
                    }
                    action(connection, statement, query, workers)
                } finally {
                    query.cancel(true)
                    workers.shutdownNow()
                    assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
                }
            }
        }
    }

    private fun assertCancelled(query: Future<*>) {
        val failure = org.assertj.core.api.Assertions.catchThrowable { query.get(2, TimeUnit.SECONDS) }
        assertThat(failure).describedAs("cancel must interrupt the active query, not wait for its 15s deadline")
            .isInstanceOf(java.util.concurrent.ExecutionException::class.java)
        assertThat(failure.cause?.message.orEmpty()).containsIgnoringCase("interrupt")
        assertThat((failure.cause as SQLException).sqlState).isEqualTo("57014")
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
                    "streaming", "streaming-bridge-control" ->
                        repeat(Integer.getInteger("duckbridge.qualification.repetitions", 1).also { require(it > 0) }) { index ->
                            println("STREAM iteration=${index + 1}")
                            fixture.qualifyPartialResultNeverBecomesSuccessfulEofAfterConnectionLoss(
                                bridgeControl = args.single() == "streaming-bridge-control",
                            )
                        }
                    "cancel" -> fixture.qualifyStatementCancellation()
                    "cancel-partition" -> fixture.qualifyCancellationUnderPartition()
                    "lease-reaping" -> fixture.qualifyHardPartitionClosesTheCallerAndLeaseExpiryReapsTheOrphan()
                    "disconnect" -> fixture.qualifyDisconnectDeadline()
                    else -> error("Unknown qualification gate")
                }
            } catch (error: Throwable) {
                if (fixture::server.isInitialized) runCatching { System.err.println("Quack server failure evidence:\n${fixture.server.diagnostics()}") }
                if (fixture::proxy.isInitialized) runCatching { System.err.println("Toxiproxy failure evidence:\n${fixture.proxy.logs}") }
                throw error
            } finally { fixture.tearDown() }
        }
    }
}
