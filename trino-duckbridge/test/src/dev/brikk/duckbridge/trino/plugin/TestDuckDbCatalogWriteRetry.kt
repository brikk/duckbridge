package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual held catalog transactions, including the native quack_query_by_name path used by LOAD retry. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckDbCatalogWriteRetry {
    private enum class Mode { EMBEDDED, QUACK_JDBC, QUACK_ARROW_WRAPPER }
    private val serverDelegate = lazy { TestingQuackServer() }
    private val server by serverDelegate

    private class Target(val connection: Connection, private val wrapped: Boolean) {
        private fun sql(sql: String): String = if (!wrapped) sql else
            "SELECT * FROM system.main.quack_query_by_name('retry_remote','${sql.replace("'", "''")}')"

        fun execute(sql: String) {
            connection.createStatement().use { statement ->
                if (wrapped) statement.executeQuery(sql(sql)).use { rows -> while (rows.next()) { /* drain */ } }
                else statement.execute(sql)
            }
        }

        fun count(sql: String): Int = connection.createStatement().use { statement ->
            statement.executeQuery(sql(sql)).use { rows -> check(rows.next()); rows.getInt(1) }
        }
    }

    @AfterAll
    fun tearDown() { if (serverDelegate.isInitialized()) server.close() }

    private fun runModes(action: (Connection, Target, String) -> Unit) {
        for (mode in Mode.entries) {
            val url = DuckBridgeQueryRunner.freshDatabaseUrl()
            val first = if (mode == Mode.EMBEDDED) DriverManager.getConnection(url) else lifecycleConnection(server)
            first.use { holder ->
                val second = when (mode) {
                    Mode.EMBEDDED -> DriverManager.getConnection(url)
                    Mode.QUACK_JDBC -> lifecycleConnection(server)
                    Mode.QUACK_ARROW_WRAPPER -> openNativeWrapper()
                }
                second.use { action(holder, Target(it, mode == Mode.QUACK_ARROW_WRAPPER), mode.name.lowercase()) }
            }
        }
    }

    private fun openNativeWrapper(): Connection {
        val connection = DriverManager.getConnection("jdbc:duckdb:")
        try {
            connection.createStatement().use { statement ->
                statement.execute("INSTALL quack")
                statement.execute("LOAD quack")
                statement.execute("CREATE SECRET (TYPE quack,TOKEN '${server.token}')")
                statement.execute("ATTACH 'quack:${server.host}:${server.mappedPort}' AS retry_remote (disable_ssl true)")
            }
            return connection
        } catch (error: SQLException) { connection.close(); throw error }
    }

    private fun holdSchema(holder: Connection, name: String) {
        holder.autoCommit = false
        holder.createStatement().use { it.execute("CREATE SCHEMA $name") }
    }

    @Test
    fun testRealAutocommitConflictConvergesAfterCompetingCommit() = runModes { holder, target, mode ->
        val schema = "lifecycle_retry_once_$mode"
        holdSchema(holder, schema)
        var attempts = 0
        assertThat(target.connection.autoCommit).isTrue()
        DuckDbCatalogWriteRetry.retryConflicts("schema $schema") {
            attempts++
            try { target.execute("CREATE SCHEMA IF NOT EXISTS $schema") }
            catch (error: SQLException) {
                assertThat(DuckDbCatalogWriteRetry.isWriteWriteConflict(error)).isTrue()
                if (attempts == 1) holder.commit()
                throw error
            }
        }
        assertThat(attempts).isEqualTo(2)
        assertThat(target.connection.autoCommit).isTrue()
        assertThat(target.count("SELECT count(*) FROM duckdb_schemas() WHERE schema_name='$schema'")).isEqualTo(1)
    }

    @Test
    fun testPersistentRealConflictExhaustsBoundedAttemptsAndKeepsOriginalError() = runModes { holder, target, mode ->
        val schema = "lifecycle_retry_exhaust_$mode"
        holdSchema(holder, schema)
        var attempts = 0
        var last: SQLException? = null
        val failure = org.assertj.core.api.Assertions.catchThrowable {
            DuckDbCatalogWriteRetry.retryConflicts("schema $schema") {
                attempts++
                try { target.execute("CREATE SCHEMA IF NOT EXISTS $schema") }
                catch (error: SQLException) { last = error; throw error }
            }
        }
        assertThat(attempts).isEqualTo(DuckDbCatalogWriteRetry.MAX_ATTEMPTS)
        assertThat(failure).isSameAs(last)
        assertThat(DuckDbCatalogWriteRetry.isWriteWriteConflict(checkNotNull(last))).isTrue()
        holder.rollback()
        assertThat(target.count("SELECT 42")).isEqualTo(42)
    }

    @Test
    fun testParserBinderAndExecutionErrorsAreNeverCatalogRetries() = runModes { _, target, mode ->
        for (sql in listOf("SELECT (", "SELECT lifecycle_absent_column", "SELECT CAST('bad' AS INTEGER)")) {
            var attempts = 0
            assertThatThrownBy {
                DuckDbCatalogWriteRetry.retryConflicts("$mode non-conflict") { attempts++; target.execute(sql) }
            }.isInstanceOf(SQLException::class.java)
            assertThat(attempts).describedAs("$mode: $sql").isEqualTo(1)
            assertThat(target.connection.autoCommit).isTrue()
            assertThat(target.count("SELECT 42")).isEqualTo(42)
        }
    }

    @Test
    fun testInterruptionStopsBackoffWithoutSwallowingTheConflict() = runModes { holder, target, mode ->
        val schema = "lifecycle_retry_interrupt_$mode"
        holdSchema(holder, schema)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val checked = worker.submit<Boolean> {
                interruptedAttempt(target, schema)
            }
            assertThat(checked.get(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            worker.shutdownNow()
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun interruptedAttempt(target: Target, schema: String): Boolean {
        var attempts = 0
        var conflict: SQLException? = null
        val failure = org.assertj.core.api.Assertions.catchThrowable {
            DuckDbCatalogWriteRetry.retryConflicts("schema $schema") {
                attempts++
                try { target.execute("CREATE SCHEMA IF NOT EXISTS $schema") }
                catch (error: SQLException) { conflict = error; Thread.currentThread().interrupt(); throw error }
            }
        }
        assertThat(attempts).isEqualTo(1)
        assertThat(failure).isSameAs(conflict)
        return Thread.currentThread().isInterrupted
    }
}
