package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.TrinoException
import io.trino.spi.type.BigintType.BIGINT
import org.assertj.core.api.Assertions.assertThat
import org.duckdb.DuckDBConnection
import org.duckdb.DuckDBPreparedStatement
import org.junit.jupiter.api.Test
import org.rnorth.ducttape.unreliables.Unreliables
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Cancellation must beat the 8s engine fallback, including the lazy-execute startup window. */
class TestArrowQueryCancellation {
    private fun connection(): DuckDBConnection = (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).also {
        it.createStatement().use { statement ->
            statement.execute("SET max_execution_time=8000")
            statement.execute("SET enable_progress_bar=true")
            statement.execute("SET enable_progress_bar_print=false")
        }
    }

    private fun source(connection: DuckDBConnection, statement: PreparedStatement) =
        DuckBridgeArrowPageSource(connection, statement, DuckBridgeArrowToPageConverter(listOf(BIGINT)), 1)

    @Test
    fun testCloseInterruptsAnExecutingNativeQuery() {
        val connection = connection()
        val statement = connection.prepareStatement("SELECT CAST(sum(range) AS BIGINT) FROM range(1000000000000)") as DuckDBPreparedStatement
        val source = source(connection, statement)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit { source.getNextSourcePage() }
            Unreliables.retryUntilSuccess(3, TimeUnit.SECONDS) {
                assertThat(reading.isDone).isFalse()
                assertThat(statement.queryProgress.percentage).isGreaterThanOrEqualTo(0.0)
            }
            workers.submit { source.close() }.get(2, TimeUnit.SECONDS)
            assertInterrupted(reading)
            assertThat(source.isFinished()).isTrue()
            assertThat(connection.isClosed).isTrue()
            assertThat(source.getMemoryUsage()).isZero()
        } finally {
            runCatching { statement.cancel() }
            source.close()
            workers.shutdownNow()
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun testCloseCannotMissTheLazyExecuteStartupWindow() {
        val connection = connection()
        val statement = connection.prepareStatement("SELECT CAST(sum(range) AS BIGINT) FROM range(1000000000000)")
        val beforeExecute = CountDownLatch(1)
        val releaseExecute = CountDownLatch(1)
        val cancellation = CountDownLatch(1)
        val proxy = beforeExecuteStatement(statement, beforeExecute, releaseExecute, cancellation)
        val source = source(connection, proxy)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit { source.getNextSourcePage() }
            assertThat(beforeExecute.await(5, TimeUnit.SECONDS)).isTrue()
            val closing = workers.submit { source.close() }
            assertThat(cancellation.await(5, TimeUnit.SECONDS)).isTrue()
            releaseExecute.countDown()
            closing.get(2, TimeUnit.SECONDS)
            assertInterrupted(reading)
            assertThat(source.isFinished()).isTrue()
            assertThat(connection.isClosed).isTrue()
        } finally {
            releaseExecute.countDown()
            runCatching { statement.cancel() }
            source.close()
            workers.shutdownNow()
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun assertInterrupted(reading: java.util.concurrent.Future<*>) {
        val error = org.assertj.core.api.Assertions.catchThrowable { reading.get(2, TimeUnit.SECONDS) }
        assertThat(error).isInstanceOf(ExecutionException::class.java)
        assertThat(error.cause).isInstanceOf(TrinoException::class.java)
        assertThat(error.cause?.cause?.message.orEmpty()).containsIgnoringCase("interrupt")
    }

    private fun beforeExecuteStatement(delegate: PreparedStatement, before: CountDownLatch, release: CountDownLatch,
                                       cancelled: CountDownLatch): PreparedStatement = Proxy.newProxyInstance(
        PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java),
    ) { _, method, arguments ->
        if (method.name == "executeQuery") {
            before.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val result = try { method.invoke(delegate, *(arguments ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        if (method.name == "cancel") cancelled.countDown()
        result
    } as PreparedStatement
}
