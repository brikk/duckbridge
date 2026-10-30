package dev.brikk.duckbridge.trino.plugin

import io.trino.Session
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/** Temporary guards plus opt-in, explicitly typed source expressions on four actual scan paths. */
class TestBoundaryTypePolicy : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private lateinit var url: String
    private val cases = linkedMapOf("untyped" to "NULL", "null_list" to "[NULL,NULL]", "tuple_value" to "row(1,'x')",
        "tuple_list" to "[row(1,'x'),NULL]", "variant_value" to "CAST({'a':1} AS VARIANT)",
        "struct_value" to "{'a':[1,NULL]}", "nested_list" to "[[1,NULL],[2]]")

    override fun createQueryRunner(): QueryRunner {
        url = DuckBridgeQueryRunner.freshDatabaseUrl()
        server = TestingQuackServer()
        checkNotNull(server.installParityExtension())
        DriverManager.getConnection(url).use { seed(it) }
        lifecycleConnection(server).use { seed(it) }
        val runner = DuckBridgeQueryRunner.create(url)
        for (catalog in catalogs.filter { it != "duckbridge" }) runner.createCatalog(catalog, "duckbridge", buildMap {
            put("connection-url", if (catalog.startsWith("remote")) server.connectionUrl() else url)
            if (catalog.startsWith("remote")) put("duckbridge.quack.token", server.token)
            if (catalog.endsWith("arrow")) put("duckbridge.execution-engine", if (catalog.startsWith("remote")) "QUACK" else "DUCKDB_LOCAL")
        })
        return runner
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA boundaries")
            for ((name, expression) in cases) {
                statement.execute("CREATE TABLE boundaries.$name AS SELECT 1 id,$expression v")
                statement.execute("CREATE VIEW boundaries.cast_$name AS SELECT id,CAST(v AS VARCHAR) v FROM boundaries.$name")
            }
            statement.execute("CREATE TABLE boundaries.json_values AS SELECT * FROM (VALUES " +
                "(1,JSON '{\"x\":1,\"x\":2,\"wide\":9007199254740993}'),(2,JSON 'null'),(3,CAST(NULL AS JSON))) t(id,v)")
            statement.execute("CREATE TABLE boundaries.arrays AS SELECT range::INTEGER id,[1,NULL,3] v FROM range(3000)")
        }
    }

    @AfterAll
    fun closeServer() { if (::server.isInitialized) server.close() }

    private fun textSession(catalog: String): Session = testSessionBuilder().setCatalog(catalog).setSchema("boundaries")
        .setCatalogSessionProperty(catalog, "unsupported_type_handling", "CONVERT_TO_VARCHAR").build()

    @Test
    fun testUnsafeGenericTextConversionsFailBeforeReading() {
        for (catalog in catalogs) for (name in cases.keys) {
            assertThatThrownBy { computeActual(textSession(catalog), "SELECT v FROM $catalog.boundaries.$name") }
                .hasMessageContaining("temporarily rejects").hasMessageContaining("explicit source CAST")
        }
    }

    @Test
    fun testExplicitSourceCastsHaveTheChosenTextContract() {
        DriverManager.getConnection(url).use { reference ->
            for ((name, _) in cases) {
                val expected = reference.createStatement().use { statement ->
                    statement.executeQuery("SELECT v FROM boundaries.cast_$name").use { rows -> check(rows.next()); rows.getString(1) }
                }
                for (catalog in catalogs) {
                    val actual = computeActual("SELECT v FROM $catalog.boundaries.cast_$name")
                    assertThat(actual.types.single().displayName).isEqualTo("varchar")
                    assertThat(actual.onlyValue).describedAs("%s / %s", catalog, name).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun testJsonTextOptInKeepsDuplicateKeysWideNumbersAndNullKinds() {
        for (catalog in catalogs) {
            val session = textSession(catalog)
            val result = computeActual(session, "SELECT v FROM $catalog.boundaries.json_values ORDER BY id")
            assertThat(result.materializedRows.map { it.getField(0) })
                .containsExactly("{\"x\":1,\"x\":2,\"wide\":9007199254740993}", "null", null)
            val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) SELECT id FROM $catalog.boundaries.json_values WHERE v='null'").onlyValue.toString()
            assertThat(plan).describedAs("Opaque JSON text is not a JSON predicate mapping").contains("filterPredicate")
        }
    }

    @Test
    fun testQualifiedArraysRemainTypedWithTextFallbackEnabled() {
        for (catalog in catalogs) {
            val result = computeActual(textSession(catalog), "SELECT v FROM $catalog.boundaries.arrays ORDER BY id")
            assertThat(result.types.single().displayName).isEqualTo("array(integer)")
            assertThat(result.rowCount).isEqualTo(3000)
            assertThat(result.materializedRows.map { it.getField(0) }).allMatch { it == listOf(1, null, 3) }
        }
    }

    private companion object {
        val catalogs = listOf("duckbridge", "native_arrow", "remote_jdbc", "remote_arrow")
    }
}
