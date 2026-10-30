package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/** Actual metadata contract, including nested paths versus a literal dotted schema name. */
class TestNamespaceContracts {
    @Test
    fun testRawDriverAncestryAndNameContracts() {
        DriverManager.getConnection(DuckBridgeQueryRunner.freshDatabaseUrl()).use { verifyRawMetadata(it) }
        TestingQuackServer().use { server -> lifecycleConnection(server).use { verifyRawMetadata(it) } }
    }

    private fun verifyRawMetadata(connection: Connection) {
        connection.createStatement().use { statement ->
            for (sql in listOf("CREATE SCHEMA finance", "CREATE SCHEMA finance.reports", "CREATE SCHEMA finance.reports.q3",
                "CREATE SCHEMA \"finance.reports\"", "CREATE SCHEMA reports",
                "CREATE TABLE finance.reports.t AS SELECT 11 id", "CREATE TABLE \"finance.reports\".t AS SELECT 22 id",
                "CREATE TABLE reports.t AS SELECT 33 id")) statement.execute(sql)
            for (sql in listOf("SELECT database_name,schema_name,parent_schema,parent_schema_oid FROM duckdb_schemas()",
                "SELECT * FROM information_schema.tables WHERE table_name='t'")) {
                statement.executeQuery(sql).use { rows ->
                    while (rows.next()) println("NAMESPACE ${connection.javaClass.simpleName} $sql: " +
                        (1..rows.metaData.columnCount).joinToString { rows.getString(it).orEmpty() })
                }
            }
        }
        val catalog = connection.catalog
        println("NAMESPACE catalog=$catalog escape=${connection.metaData.searchStringEscape}")
        connection.metaData.getSchemas(catalog, "%").use { rows ->
            val names = buildList { while (rows.next()) add(rows.getString("TABLE_SCHEM")) }
            println("NAMESPACE schemas=$names")
            assertThat(names).contains("finance.reports", "reports", "q3")
        }
        connection.metaData.getTables(catalog, "reports", "t", arrayOf("TABLE")).use { rows ->
            var count = 0
            while (rows.next()) {
                println("NAMESPACE table=${rows.getString("TABLE_CAT")}.${rows.getString("TABLE_SCHEM")}.${rows.getString("TABLE_NAME")}")
                count++
            }
            assertThat(count).describedAs("Nested and flat leaf names are indistinguishable in standard metadata").isEqualTo(2)
        }
    }
}
