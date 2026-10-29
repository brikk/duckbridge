package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.TrinoException
import io.trino.spi.type.BigintType.BIGINT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.duckdb.DuckDBConnection
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real native statements/C-data readers. Barriers instrument call ordering, never fabricate rows. */
class TestArrowSourceLifecycle {
    private data class Owned(val connection: DuckDBConnection, val statement: PreparedStatement, val source: DuckBridgeArrowPageSource)

    private fun open(sql: String): Owned {
        val connection = DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection
        val statement = connection.prepareStatement(sql)
        return Owned(connection, statement, DuckBridgeArrowPageSource(connection, statement, DuckBridgeArrowToPageConverter(listOf(BIGINT)), 1))
    }

    private fun assertTerminal(owned: Owned) {
        assertThat(owned.source.isFinished()).isTrue()
        assertThat(owned.source.getNextSourcePage()).isNull()
        assertThat(owned.source.getMemoryUsage()).isZero()
        assertThat(owned.statement.isClosed).isTrue()
        assertThat(owned.connection.isClosed).isTrue()
        owned.source.close()
        assertThat(owned.source.getNextSourcePage()).isNull()
    }

    @Test
    fun testCloseBeforeFirstReadNeverStartsTheStatement() {
        val owned = open("SELECT * FROM range(2503)")
        owned.source.close()
        assertTerminal(owned)
    }

    @Test
    fun testInterruptedCloserStillCleansUpAndPreservesItsInterrupt() {
        val owned = open("SELECT * FROM range(2503)")
        val worker = Executors.newSingleThreadExecutor()
        try {
            val closing = worker.submit<Boolean> {
                Thread.currentThread().interrupt()
                owned.source.close()
                Thread.currentThread().isInterrupted
            }
            assertThat(closing.get(5, TimeUnit.SECONDS)).isTrue()
            assertTerminal(owned)
        } finally {
            owned.source.close()
            worker.shutdownNow()
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun testEofClosesAllResourcesAndReturnedPagesStayValid() {
        val owned = open("SELECT * FROM range(2503)")
        try {
            val pages = buildList {
                while (!owned.source.isFinished()) owned.source.getNextSourcePage()?.let { add(it) }
            }
            assertTerminal(owned)
            var count = 0L
            for (page in pages) {
                for (index in 0 until page.positionCount) assertThat(BIGINT.getLong(page.getBlock(0), index)).isEqualTo(count++)
            }
            assertThat(count).isEqualTo(2503)
        } finally { owned.source.close() }
    }

    @Test
    fun testExecutionFailureIsNotEofAndClosesAllResources() {
        val owned = open("SELECT CAST(? AS BIGINT)")
        owned.statement.setString(1, "not-a-number")
        try {
            assertThatThrownBy { owned.source.getNextSourcePage() }.isInstanceOf(TrinoException::class.java)
                .hasMessageContaining("Arrow query failed")
            assertTerminal(owned)
        } finally { owned.source.close() }
    }

    @Test
    fun testConversionFailurePreservesTheFailureAndClosesResources() {
        val owned = open("SELECT 'wrong-declared-vector-type'")
        try {
            assertThatThrownBy { owned.source.getNextSourcePage() }.isInstanceOf(ClassCastException::class.java)
            assertTerminal(owned)
        } finally { owned.source.close() }
    }

    @Test
    fun testCloseWaitsForARealReadWithoutFreeingItsConnection() {
        val connection = DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection
        val statement = connection.prepareStatement("SELECT * FROM range(2503)")
        val readReady = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val cancelRequested = CountDownLatch(1)
        val proxy = gatedStatement(statement, readReady, releaseRead, cancelRequested)
        val owned = Owned(connection, statement, DuckBridgeArrowPageSource(connection, proxy, DuckBridgeArrowToPageConverter(listOf(BIGINT)), 1))
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit { owned.source.getNextSourcePage() }
            assertThat(readReady.await(5, TimeUnit.SECONDS)).isTrue()
            val closing = workers.submit { owned.source.close() }
            assertThat(cancelRequested.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(connection.isClosed).isFalse()
            assertThat(closing.isDone).isFalse()
            releaseRead.countDown()
            closing.get(5, TimeUnit.SECONDS)
            verifyReadOutcome(reading)
            assertTerminal(owned)
        } finally {
            releaseRead.countDown()
            owned.source.close()
            workers.shutdownNow()
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun gatedStatement(delegate: PreparedStatement, ready: CountDownLatch, release: CountDownLatch,
                               cancelled: CountDownLatch): PreparedStatement = Proxy.newProxyInstance(
        PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java),
    ) { _, method, arguments ->
        if (method.name == "cancel") cancelled.countDown()
        val result = try { method.invoke(delegate, *(arguments ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        if (method.name == "executeQuery") {
            ready.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Read barrier was never released" }
        }
        result
    } as PreparedStatement

    private fun verifyReadOutcome(reading: java.util.concurrent.Future<*>) {
        try { reading.get(5, TimeUnit.SECONDS) }
        catch (error: ExecutionException) {
            assertThat(error.cause).isInstanceOf(TrinoException::class.java)
            assertThat(error.cause?.cause?.message.orEmpty()).containsIgnoringCase("interrupt")
        }
    }

    @Test
    fun testConcurrentBatchReadsAndRepeatedClosesKeepAnExactPrefix() {
        val workers = Executors.newFixedThreadPool(3)
        try {
            repeat(20) {
                val owned = open("SELECT * FROM range(100000)")
                val firstPage = CountDownLatch(1)
                try {
                    val reading = workers.submit<Long> { readPrefix(owned.source, firstPage) }
                    assertThat(firstPage.await(5, TimeUnit.SECONDS)).isTrue()
                    val closes = (1..2).map { workers.submit { owned.source.close() } }
                    closes.forEach { it.get(5, TimeUnit.SECONDS) }
                    assertThat(reading.get(5, TimeUnit.SECONDS)).isBetween(1L, 100000L)
                    assertTerminal(owned)
                } finally { owned.source.close() }
            }
        } finally {
            workers.shutdownNow()
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun readPrefix(source: DuckBridgeArrowPageSource, firstPage: CountDownLatch): Long {
        var count = 0L
        while (true) {
            val page = source.getNextSourcePage() ?: return count
            for (index in 0 until page.positionCount) assertThat(BIGINT.getLong(page.getBlock(0), index)).isEqualTo(count++)
            firstPage.countDown()
            Thread.yield()
        }
    }
}
