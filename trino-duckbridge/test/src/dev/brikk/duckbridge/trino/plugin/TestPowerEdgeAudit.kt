package dev.brikk.duckbridge.trino.plugin

import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcPlugin
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.Variable
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.DistributedQueryRunner
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.Optional

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestPowerEdgeAudit : AbstractTestQueryFramework() {
    private lateinit var duck: Connection
    private val output = Path.of("build/power-edge-audit")

    override fun createQueryRunner(): QueryRunner {
        val url = DuckBridgeQueryRunner.freshDatabaseUrl()
        duck = DriverManager.getConnection(url, DuckBridgeQueryRunner.duckJdbcProperties())
        seed()
        val runner = DistributedQueryRunner.builder(testSessionBuilder().setCatalog("power_pushed").setSchema("audit").build()).build()
        try {
            runner.installPlugin(DuckBridgePlugin())
            runner.installPlugin(JdbcPlugin("power_no_push") { DuckBridgeClientModule(expressionPushdownEnabled = false) })
            val properties = buildMap {
                put("connection-url", url)
                if (DuckBridgeQueryRunner.bundledParityIsUnsigned()) put("duckbridge.allow-unsigned-extensions", "true")
            }
            runner.createCatalog("power_pushed", "duckbridge", properties)
            runner.createCatalog("power_local", "power_no_push", properties)
            return runner
        } catch (error: Throwable) { runner.close(); duck.close(); throw error }
    }

    private fun seed() {
        duck.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA audit")
            statement.execute("CREATE TABLE audit.cases(id BIGINT, b DOUBLE, e DOUBLE)")
        }
        duck.prepareStatement("INSERT INTO audit.cases VALUES(?,?,?)").use { statement ->
            for (case in PowerEdgeCases.cases) {
                statement.setLong(1, case.id)
                if (case.base == null) statement.setNull(2, Types.DOUBLE) else statement.setDouble(2, case.base)
                if (case.exponent == null) statement.setNull(3, Types.DOUBLE) else statement.setDouble(3, case.exponent)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    @AfterAll
    fun closeOracle() { if (::duck.isInitialized) duck.close() }

    @Test
    fun testNativeDifferencesAndExactProductionFallback() {
        Files.createDirectories(output)
        controls()
        verifyInputs()
        // Native differences are diagnostics, not qualified emissions. Do not prescribe platform-specific libm rounding.
        assertThat(scalarAudit()).describedAs("Native special-value counterexamples must remain visible")
            .contains(156L, 184L, 435L, 463L, 464L)
        assertThat(predicateAudit()).describedAs("Native NaN comparison counterexamples must remain visible")
            .contains("power(b,e) > 0", "power(b,e) >= 0", "NOT (power(b,e) > 0)")
        val alias = "SELECT id FROM power_pushed.audit.cases WHERE pow(b,e) > 0 ORDER BY id"
        assertThat(computeActual("EXPLAIN (TYPE DISTRIBUTED) $alias").onlyValue.toString()).contains("filterPredicate")
        assertThat(ids(alias)).containsExactlyElementsOf(ids("SELECT id FROM power_local.audit.cases WHERE power(b,e) > 0 ORDER BY id"))
        controls()
    }

    private fun controls() {
        assertThat(computeActual("SELECT CAST(42 AS BIGINT)").onlyValue).isEqualTo(42L)
        val trino = Path.of(io.trino.operator.scalar.MathFunctions::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(trino.fileName.toString()).isEqualTo("trino-main-${System.getProperty("duckbridge.test.trinoVersion", "483")}.jar")
        duck.createStatement().use { statement ->
            statement.executeQuery("SELECT version(),CAST(42 AS BIGINT)").use { rows ->
                check(rows.next())
                assertThat(rows.getString(1)).isEqualTo("v2.0.0")
                assertThat(rows.getLong(2)).isEqualTo(42L)
            }
            statement.executeQuery("SELECT source_id,current_setting('ieee_floating_point_ops') FROM pragma_version()").use { rows ->
                check(rows.next())
                assertThat(rows.getString(1)).isEqualTo("d54cbbe3df")
                assertThat(rows.getBoolean(2)).isTrue()
            }
        }
        val driver = Path.of(org.duckdb.DuckDBDriver::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(driver.fileName.toString()).isEqualTo("duckdb_jdbc-2.0.0.0.jar")
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(driver).use { input -> DigestInputStream(input, digest).use { it.transferTo(OutputStream.nullOutputStream()) } }
        val hash = java.util.HexFormat.of().formatHex(digest.digest())
        assertThat(hash).isEqualTo("5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186")
        Files.writeString(output.resolve("versions.txt"), "Trino=$trino\nDuckDB=v2.0.0\nsource_id=d54cbbe3df\n" +
            "ieee_floating_point_ops=true\nnative_jdbc=$driver\nsha256=$hash\n" +
            "jdbc_metadata_driver_version=${duck.metaData.driverVersion}\nJDK=${Runtime.version()}\n")
    }

    private fun verifyInputs() {
        val host = computeActual("SELECT id,b,e FROM power_local.audit.cases ORDER BY id")
        assertThat(host.rowCount).isEqualTo(PowerEdgeCases.cases.size)
        duck.createStatement().use { statement ->
            statement.executeQuery("SELECT id,b,e FROM audit.cases ORDER BY id").use { rows ->
                for ((index, case) in PowerEdgeCases.cases.withIndex()) {
                    check(rows.next())
                    assertThat(rows.getLong(1)).isEqualTo(case.id)
                    val expected = host.materializedRows[index]
                    assertThat(expected.getField(0)).isEqualTo(case.id)
                    assertThat(PowerEdgeCases.same(case.base, expected.getField(1) as Double?)).isTrue()
                    assertThat(PowerEdgeCases.same(case.exponent, expected.getField(2) as Double?)).isTrue()
                    assertThat(PowerEdgeCases.same(case.base, rows.getObject(2) as Double?)).isTrue()
                    assertThat(PowerEdgeCases.same(case.exponent, rows.getObject(3) as Double?)).isTrue()
                }
                assertThat(rows.next()).isFalse()
            }
        }
    }

    @Test
    fun testWithdrawnMathCannotEmitWithOrWithoutParityOrStringComparisonTrust() {
        val assignments: Map<String, ColumnHandle> = listOf("b", "e").associateWith { name ->
            JdbcColumnHandle(name, JdbcTypeHandle(Types.DOUBLE, Optional.of("DOUBLE"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()), DOUBLE)
        }
        for ((name, arity) in listOf("power" to 2, "pow" to 2, "atan" to 1, "atan2" to 2, "cosh" to 1)) {
            val args = if (arity == 2) listOf(Variable("b", DOUBLE), Variable("e", DOUBLE)) else listOf(Variable("b", DOUBLE))
            val expression = Call(DOUBLE, FunctionName(name), args)
            for (alias in listOf(false, true)) {
                for (strings in listOf(false, true)) {
                    assertThat(DuckBridgeExpressionTranslator.translate(
                        expression,
                        assignments, null, aliasAvailable = alias, stringComparisonAllowed = strings,
                    )).describedAs("%s must not emit with alias=%s strings=%s", name, alias, strings).isNull()
                    assertThat(DuckBridgeExpressionTranslator.translate(
                        Call(DOUBLE, FunctionName("abs"), listOf(expression)),
                        assignments, null, aliasAvailable = alias, stringComparisonAllowed = strings,
                    )).describedAs("Nested %s must remain local", name).isNull()
                }
            }
        }
    }

    @Test
    fun testAtanAtan2AndCoshPreserveExactLocalValuesAndPredicates() {
        Files.createDirectories(output)
        controls()
        verifyInputs()
        val input = mathOracleInput()
        val diagnostics = mutableListOf("function\tid\ttrino\tduckdb\tproduction\tnative_match\tproduction_match")
        val predicates = mutableListOf("predicate\ttrino_ids\tproduction_ids\tfilter_retained")
        for ((expression, finiteValue) in listOf("atan(b)" to "atan(pi())", "atan2(b,e)" to "atan2(pi(),1.0E0)", "cosh(b)" to "cosh(1.0E0)")) {
            localMathScalars(input, expression, diagnostics)
            for ((index, predicate) in listOf("$expression>0", "NOT ($expression>0)", "$expression=0", "$expression=$finiteValue").withIndex()) {
                val sql = "SELECT id FROM power_pushed.audit.cases WHERE $predicate ORDER BY id"
                val expected = ids("$input SELECT id FROM input WHERE $predicate ORDER BY id")
                val actual = ids(sql)
                val plan = computeActual("EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                Files.writeString(output.resolve("local-${expression.substringBefore('(')}-$index.plan"), plan)
                assertThat(plan).describedAs("%s must remain local", predicate).contains("filterPredicate")
                assertThat(actual).describedAs(predicate).containsExactlyElementsOf(expected)
                predicates.add(listOf(predicate, expected.toString(), actual.toString(), "true").joinToString("\t"))
            }
        }
        Files.write(output.resolve("local-math-scalars.tsv"), diagnostics)
        Files.write(output.resolve("local-math-predicates.tsv"), predicates)
        controls()
    }

    private fun localMathScalars(input: String, expression: String, diagnostics: MutableList<String>) {
        val host = computeActual("$input SELECT id,$expression FROM input ORDER BY id")
        val production = computeActual("SELECT id,$expression FROM power_pushed.audit.cases ORDER BY id")
        assertThat(host.types[1]).isEqualTo(DOUBLE)
        assertThat(production.types).isEqualTo(host.types)
        assertThat(production.rowCount).isEqualTo(host.rowCount)
        duck.createStatement().use { statement ->
            statement.executeQuery("SELECT id,$expression FROM audit.cases ORDER BY id").use { rows ->
                for ((index, expected) in host.materializedRows.withIndex()) {
                    check(rows.next())
                    val actual = production.materializedRows[index]
                    assertThat(actual.getField(0)).isEqualTo(expected.getField(0))
                    assertThat(rows.getLong(1)).isEqualTo(expected.getField(0) as Long)
                    val value = expected.getField(1) as Double?
                    val local = actual.getField(1) as Double?
                    val native = rows.getObject(2) as Double?
                    assertThat(PowerEdgeCases.same(value, local)).describedAs("%s id=%s", expression, expected.getField(0)).isTrue()
                    diagnostics.add(listOf(expression, expected.getField(0).toString(), PowerEdgeCases.describe(value),
                        PowerEdgeCases.describe(native), PowerEdgeCases.describe(local),
                        PowerEdgeCases.same(value, native).toString(), "true").joinToString("\t"))
                }
                assertThat(rows.next()).isFalse()
            }
        }
    }

    private fun mathOracleInput(): String = PowerEdgeCases.cases.joinToString(",", "WITH input(id,b,e) AS (VALUES ", ")") { row ->
        fun literal(value: Double?): String = "CAST(${value?.let { "'$it'" } ?: "NULL"} AS DOUBLE)"
        "(CAST(${row.id} AS BIGINT),${literal(row.base)},${literal(row.exponent)})"
    }

    private fun scalarAudit(): List<Long> {
        // Diagnostic native SQL only: production must not emit it.
        val expression = "power(\"b\",\"e\")"
        val host = computeActual("SELECT id,power(b,e) FROM power_local.audit.cases ORDER BY id")
        val production = computeActual("SELECT id,power(b,e) FROM power_pushed.audit.cases ORDER BY id")
        assertThat(host.types[1]).isEqualTo(DOUBLE)
        assertThat(production.types[1]).isEqualTo(DOUBLE)
        assertThat(host.rowCount).isEqualTo(PowerEdgeCases.cases.size)
        assertThat(production.rowCount).isEqualTo(host.rowCount)
        val lines = mutableListOf("id\tbase\texponent\ttrino\tduckdb\tproduction\tnative_match\tproduction_match")
        val differences = mutableListOf<Long>()
        duck.createStatement().use { statement ->
            statement.executeQuery("SELECT id,$expression FROM audit.cases ORDER BY id").use { rows ->
                assertThat(rows.metaData.getColumnTypeName(2)).isEqualToIgnoringCase("DOUBLE")
                for ((index, case) in PowerEdgeCases.cases.withIndex()) {
                    check(rows.next())
                    assertThat(rows.getLong(1)).isEqualTo(case.id)
                    assertThat(host.materializedRows[index].getField(0)).isEqualTo(case.id)
                    val expected = host.materializedRows[index].getField(1) as Double?
                    val actual = rows.getObject(2) as Double?
                    val local = production.materializedRows[index].getField(1) as Double?
                    assertThat(production.materializedRows[index].getField(0)).isEqualTo(case.id)
                    assertThat(PowerEdgeCases.same(expected, local)).describedAs("Production power scalar id=%s", case.id).isTrue()
                    val match = PowerEdgeCases.same(expected, actual)
                    val detail = listOf(case.id.toString(), PowerEdgeCases.describe(case.base), PowerEdgeCases.describe(case.exponent),
                        PowerEdgeCases.describe(expected), PowerEdgeCases.describe(actual), PowerEdgeCases.describe(local),
                        match.toString(), PowerEdgeCases.same(expected, local).toString()).joinToString("\t")
                    lines.add(detail)
                    if (!match) differences.add(case.id)
                }
                assertThat(rows.next()).isFalse()
            }
        }
        Files.write(output.resolve("scalars.tsv"), lines)
        return differences
    }

    private fun predicateAudit(): List<String> {
        val predicates = listOf("power(b,e) > 0", "power(b,e) >= 0", "power(b,e) < 0", "power(b,e) <= 0",
            "power(b,e) = 0", "power(b,e) <> 0", "power(b,e) = 1", "power(b,e) > 1", "power(b,e) <= 1",
            "power(b,e) IS NULL", "power(b,e) IS NOT NULL", "NOT (power(b,e) > 0)",
            "power(b,e) > 0 OR b IS NULL", "power(b,e) >= 0 AND e <> 0")
        val lines = mutableListOf("predicate\ttrino_ids\tproduction_ids\tduckdb_ids\tnative_match\tproduction_match")
        val differences = mutableListOf<String>()
        for ((index, predicate) in predicates.withIndex()) {
            val localSql = "SELECT id FROM power_local.audit.cases WHERE $predicate ORDER BY id"
            val pushedSql = "SELECT id FROM power_pushed.audit.cases WHERE $predicate ORDER BY id"
            val localPlan = computeActual("EXPLAIN (TYPE DISTRIBUTED) $localSql").onlyValue.toString()
            val pushedPlan = computeActual("EXPLAIN (TYPE DISTRIBUTED) $pushedSql").onlyValue.toString()
            Files.writeString(output.resolve("predicate-$index-local.plan"), localPlan)
            Files.writeString(output.resolve("predicate-$index-production.plan"), pushedPlan)
            assertThat(localPlan).describedAs(predicate).contains("filterPredicate")
            assertThat(pushedPlan).describedAs("Production power must remain local: %s", predicate).contains("filterPredicate")
            val expected = ids(localSql)
            val actual = ids(pushedSql)
            val native = nativeIds(predicate)
            assertThat(actual).describedAs("Production power truth set: %s", predicate).containsExactlyElementsOf(expected)
            lines.add(listOf(predicate, expected.toString(), actual.toString(), native.toString(),
                (expected == native).toString(), (expected == actual).toString()).joinToString("\t"))
            if (expected != native) differences.add(predicate)
        }
        Files.write(output.resolve("predicates.tsv"), lines)
        return differences
    }

    private fun ids(sql: String): List<Long> = computeActual(sql).materializedRows.map { it.getField(0) as Long }

    private fun nativeIds(predicate: String): List<Long> = duck.createStatement().use { statement ->
        statement.executeQuery("SELECT id FROM audit.cases WHERE $predicate ORDER BY id").use { rows ->
            buildList { while (rows.next()) add(rows.getLong(1)) }
        }
    }
}
