package dev.brikk.duckbridge.trino.plugin

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

class TestNamespaceRouting : AbstractTestQueryFramework() {
    private lateinit var url: String
    private lateinit var server: TestingQuackServer

    override fun createQueryRunner(): QueryRunner {
        url = DuckBridgeQueryRunner.freshDatabaseUrl()
        server = TestingQuackServer()
        server.installParityExtension()
        val runner = DuckBridgeQueryRunner.create(url)
        runner.createCatalog("remote_names", "duckbridge", mapOf("connection-url" to server.connectionUrl(), "duckbridge.quack.token" to server.token))
        runner.createCatalog("local_names_arrow", "duckbridge", mapOf("connection-url" to url, "duckbridge.execution-engine" to "DUCKDB_LOCAL"))
        runner.createCatalog("remote_names_arrow", "duckbridge", mapOf("connection-url" to server.connectionUrl(),
            "duckbridge.quack.token" to server.token, "duckbridge.execution-engine" to "QUACK"))
        forEachConnection { _, connection ->
            connection.createStatement().use { statement ->
                for (sql in listOf("CREATE SCHEMA \"finance.reports\"", "CREATE TABLE \"finance.reports\".\"select\" AS SELECT 22 id",
                    "CREATE SCHEMA names_a", "CREATE SCHEMA namesxa", "CREATE TABLE names_a.t AS SELECT 31 id",
                    "CREATE TABLE namesxa.t AS SELECT 41 id", "CREATE TABLE names_a.\"t_%\" AS SELECT 51 id",
                    "CREATE TABLE names_a.txy AS SELECT 61 id")) statement.execute(sql)
            }
        }
        return runner
    }

    @AfterAll
    fun closeServer() { if (::server.isInitialized) server.close() }

    @Test
    fun testLiteralDotsKeywordsAndMetadataWildcardsStayExact() {
        for (catalog in catalogs) {
            assertQuery("SELECT id FROM $catalog.\"finance.reports\".\"select\"", "VALUES 22")
            assertQuery("SELECT id FROM $catalog.names_a.t", "VALUES 31")
            assertQuery("SELECT id FROM $catalog.names_a.\"t_%\"", "VALUES 51")
            val tables = computeActual("SHOW TABLES FROM $catalog.names_a").materializedRows.map { it.getField(0) }
            assertThat(tables).containsExactlyInAnyOrder("t", "t_%", "txy")
        }
    }

    @Test
    fun testQuotedCreateWriteRenameAndDropKeepLiteralComponents() {
        for (catalog in catalogs) {
            val schema = "$catalog.\"finance.reports\""
            computeActual("CREATE TABLE $schema.\"from_%\" (\"quo\"\"ted\" BIGINT)")
            try {
                computeActual("INSERT INTO $schema.\"from_%\" VALUES (91)")
                computeActual("ALTER TABLE $schema.\"from_%\" RENAME TO \"next.%\"")
                assertQuery("SELECT \"quo\"\"ted\" FROM $schema.\"next.%\"", "VALUES CAST(91 AS BIGINT)")
            } finally {
                computeActual("DROP TABLE IF EXISTS $schema.\"from_%\"")
                computeActual("DROP TABLE IF EXISTS $schema.\"next.%\"")
            }
        }
    }

    @Test
    fun testAnotherCatalogCannotInjectAMatchingTableIntoTheCurrentNamespace() = forEachConnection { catalog, connection ->
        connection.createStatement().use { statement ->
            statement.execute("ATTACH ':memory:' AS sibling")
            try {
                statement.execute("CREATE SCHEMA sibling.names_a")
                statement.execute("CREATE TABLE sibling.names_a.t AS SELECT 99 id")
                assertQuery("SELECT id FROM $catalog.names_a.t", "VALUES 31")
            } finally { statement.execute("DETACH sibling") }
        }
    }

    @Test
    fun testNestedSchemasAreRejectedBeforeLeafNamesCanResolveAnotherObject() = forEachConnection { catalog, connection ->
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA finance")
            statement.execute("CREATE SCHEMA finance.reports")
            statement.execute("CREATE TABLE finance.reports.\"select\" AS SELECT 11 id")
            try {
                assertThatThrownBy { computeActual("SHOW SCHEMAS FROM $catalog") }
                    .hasMessageContaining("Nested DuckDB schema")
                assertThatThrownBy { computeActual("SELECT id FROM $catalog.reports.\"select\"") }
                    .hasMessageContaining("Nested DuckDB schema")
            } finally { statement.execute("DROP SCHEMA finance CASCADE") }
        }
    }

    private fun forEachConnection(action: (String, Connection) -> Unit) {
        DriverManager.getConnection(url).use { action("duckbridge", it) }
        lifecycleConnection(server).use { action("remote_names", it) }
    }

    private companion object {
        val catalogs = listOf("duckbridge", "remote_names", "local_names_arrow", "remote_names_arrow")
    }
}
