package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types

/** Real two-connection external DDL and repeated native/Quack prepared execution in autocommit. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestPreparedSchemaLifecycle {
    private val serverDelegate = lazy { TestingQuackServer() }
    private val server by serverDelegate
    private data class Rows(val types: List<String>, val values: List<List<String?>>)

    @AfterAll
    fun tearDown() { if (serverDelegate.isInitialized()) server.close() }

    private fun forEachTransport(action: (Connection, Connection) -> Unit) {
        val url = DuckBridgeQueryRunner.freshDatabaseUrl()
        DriverManager.getConnection(url).use { first -> DriverManager.getConnection(url).use { second -> action(first, second) } }
        lifecycleConnection(server).use { first -> lifecycleConnection(server).use { second -> action(first, second) } }
    }

    private fun execute(connection: Connection, sql: String) { connection.createStatement().use { it.execute(sql) } }

    private fun read(statement: PreparedStatement): Rows = statement.executeQuery().use { result ->
        val types = (1..result.metaData.columnCount).map { result.metaData.getColumnTypeName(it).uppercase() }
        val values = buildList {
            while (result.next()) add((1..types.size).map { result.getString(it) })
        }
        Rows(types, values)
    }

    @Test
    fun testStableProjectionSeesCommittedRowsAfterExternalAddColumn() = forEachTransport { reader, ddl ->
        execute(ddl, "CREATE TABLE prepared_stable(id INTEGER,name VARCHAR)")
        try {
            execute(ddl, "INSERT INTO prepared_stable VALUES (1,'a'),(2,'b')")
            reader.prepareStatement("SELECT id,name FROM prepared_stable WHERE id>=? ORDER BY id").use { statement ->
                statement.setInt(1, 1)
                assertThat(read(statement)).isEqualTo(Rows(listOf("INTEGER", "VARCHAR"), listOf(listOf("1", "a"), listOf("2", "b"))))
                execute(ddl, "ALTER TABLE prepared_stable ADD COLUMN extra INTEGER DEFAULT 7")
                execute(ddl, "INSERT INTO prepared_stable VALUES (3,'c',9)")
                assertThat(read(statement)).isEqualTo(Rows(listOf("INTEGER", "VARCHAR"),
                    listOf(listOf("1", "a"), listOf("2", "b"), listOf("3", "c"))))
            }
        } finally { execute(ddl, "DROP TABLE prepared_stable") }
    }

    @Test
    fun testStarProjectionRefreshesResultMetadataAfterExternalDdl() = forEachTransport { reader, ddl ->
        execute(ddl, "CREATE TABLE prepared_star(id INTEGER,name VARCHAR)")
        try {
            execute(ddl, "INSERT INTO prepared_star VALUES (1,'a')")
            reader.prepareStatement("SELECT * FROM prepared_star WHERE id=?").use { statement ->
                statement.setInt(1, 1)
                assertThat(read(statement).types).containsExactly("INTEGER", "VARCHAR")
                execute(ddl, "ALTER TABLE prepared_star ADD COLUMN extra INTEGER DEFAULT 7")
                assertThat(read(statement)).isEqualTo(Rows(listOf("INTEGER", "VARCHAR", "INTEGER"), listOf(listOf("1", "a", "7"))))
            }
        } finally { execute(ddl, "DROP TABLE prepared_star") }
    }

    @Test
    fun testDropRecreateDoesNotKeepOldTypesOrRows() = forEachTransport { reader, ddl ->
        execute(ddl, "CREATE TABLE prepared_recreate(id INTEGER,name VARCHAR)")
        try {
            execute(ddl, "INSERT INTO prepared_recreate VALUES (1,'old')")
            reader.prepareStatement("SELECT * FROM prepared_recreate WHERE id=?").use { statement ->
                statement.setInt(1, 1)
                assertThat(read(statement).values).containsExactly(listOf("1", "old"))
                execute(ddl, "DROP TABLE prepared_recreate")
                execute(ddl, "CREATE TABLE prepared_recreate(id BIGINT,name VARCHAR)")
                execute(ddl, "INSERT INTO prepared_recreate VALUES (9000000000,'new')")
                statement.setLong(1, 9_000_000_000L)
                assertThat(read(statement)).isEqualTo(Rows(listOf("BIGINT", "VARCHAR"), listOf(listOf("9000000000", "new"))))
            }
        } finally { execute(ddl, "DROP TABLE prepared_recreate") }
    }

    @Test
    fun testParameterChangesAndFailedStatementRecoveryKeepTheConnectionUsable() = forEachTransport { reader, ddl ->
        execute(ddl, "CREATE TABLE prepared_parameters(id INTEGER)")
        try {
            execute(ddl, "INSERT INTO prepared_parameters VALUES (1),(2),(3)")
            reader.prepareStatement("SELECT id FROM prepared_parameters WHERE id>=? ORDER BY id").use { statement ->
                statement.setInt(1, 2)
                assertThat(read(statement).values).containsExactly(listOf("2"), listOf("3"))
                statement.setNull(1, Types.INTEGER)
                assertThat(read(statement).values).isEmpty()
                statement.setString(1, "not-an-integer")
                assertThatThrownBy { read(statement) }.isInstanceOf(SQLException::class.java)
                assertRecoveryContract(reader, statement)
            }
        } finally { execute(ddl, "DROP TABLE prepared_parameters") }
    }

    private fun assertRecoveryContract(reader: Connection, statement: PreparedStatement) {
        if (reader is org.duckdb.DuckDBConnection) {
            // JDBC 2.0.0.0 executeDirect() explicitly closes its statement in queryFailed finally.
            // Do not mistake that valid failure state for a poisoned connection or reuse it blindly.
            assertThat(statement.isClosed).isTrue()
            assertThatThrownBy { statement.setLong(1, 3L) }.isInstanceOf(SQLException::class.java).hasMessageContaining("closed")
            reader.prepareStatement("SELECT id FROM prepared_parameters WHERE id>=? ORDER BY id").use { fresh ->
                fresh.setLong(1, 3L)
                assertThat(read(fresh).values).containsExactly(listOf("3"))
            }
        } else {
            assertThat(statement.isClosed).isFalse()
            statement.setLong(1, 3L)
            assertThat(read(statement).values).containsExactly(listOf("3"))
        }
        lifecycleControl(reader)
        assertThat(reader.autoCommit).isTrue()
    }
}
