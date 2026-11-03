package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slices.utf8Slice
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcPlugin
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.Variable
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.TimeZoneKey
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.DistributedQueryRunner
import io.trino.testing.QueryFailedException
import io.trino.testing.QueryRunner
import io.trino.testing.TestingConnectorSession
import io.trino.testing.TestingSession.testSessionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.Optional
import java.util.Properties

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDateTruncationLocal : AbstractTestQueryFramework() {
    private val units = listOf("day", "week", "month", "quarter", "year")
    private val sources = linkedMapOf<String, Connection>()
    private lateinit var server: TestingQuackServer
    private val output = Path.of("build/release-closeout/date-truncation")
    private val base = testSessionBuilder().setCatalog("date_native").setSchema("audit").setTimeZoneKey(TimeZoneKey.UTC_KEY).build()

    override fun createQueryRunner(): QueryRunner {
        Files.createDirectories(output)
        val url = DuckBridgeQueryRunner.freshDatabaseUrl()
        sources["native"] = DriverManager.getConnection(url, DuckBridgeQueryRunner.duckJdbcProperties())
        server = TestingQuackServer()
        sources["remote"] = DriverManager.getConnection(server.connectionUrl(), Properties().apply { setProperty("token", server.token) })
        for (connection in sources.values) {
            connection.createStatement().use {
                it.execute("CREATE SCHEMA audit")
                it.execute("CREATE TABLE audit.dates(id BIGINT,d DATE)")
            }
            connection.prepareStatement("INSERT INTO audit.dates VALUES(?,DATE '1970-01-01'+CAST(? AS INTEGER))").use { statement ->
                PushdownEdgeFixtures.dateDays.forEachIndexed { id, day ->
                    statement.setLong(1, id.toLong())
                    if (day == null) statement.setNull(2, Types.INTEGER) else statement.setInt(2, Math.toIntExact(day))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        val runner = DistributedQueryRunner.builder(base).build()
        try {
            runner.installPlugin(DuckBridgePlugin())
            for (profile in sources.keys) runner.createCatalog("date_$profile", "duckbridge", buildMap {
                put("connection-url", if (profile == "native") url else server.connectionUrl())
                put("duckbridge.string-pushdown.mode", "BINARY")
                if (profile == "remote") put("duckbridge.quack.token", server.token)
            })
            return runner
        } catch (failure: Throwable) { runner.close(); throw failure }
    }

    @AfterAll
    fun closeSources() {
        sources.values.forEach(Connection::close)
        if (::server.isInitialized) server.close()
    }

    @Test
    fun testAllDateUnitsDeclineRegardlessOfTrustOrZonedProperty() {
        val d = Variable("d", DATE)
        val column = JdbcColumnHandle("d", JdbcTypeHandle(Types.DATE, Optional.of("DATE"), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()), DATE)
        for (unit in units) for (enabled in listOf(false, true)) for (aliases in listOf(false, true)) for (strings in listOf(false, true)) {
            val session = TestingConnectorSession.builder().setPropertyMetadata(DuckBridgeSessionProperties(DuckBridgeConfig()).getSessionProperties())
                .setPropertyValues(mapOf(DuckBridgeSessionProperties.PUSHDOWN_TIMESTAMP_WITH_TIME_ZONE to enabled)).build()
            val call = Call(DATE, FunctionName("date_trunc"), listOf(Constant(utf8Slice(unit), VARCHAR), d))
            assertThat(DuckBridgeExpressionTranslator.translate(call, mapOf("d" to column), session, aliases, strings)).isNull()
        }
    }

    @Test
    fun testTypesValuesRowSetsAndWideDateErrorsStayTrinoLocal() {
        val observations = mutableListOf("profile\tunit\tid\tnative_trino\tproduction")
        var errors = 0
        for (profile in sources.keys) {
            val source = outcome("SELECT id,d FROM date_$profile.audit.dates ORDER BY id")
            val expectedSource = outcome("${PushdownEdgeFixtures.trinoInput("date_boundaries")} SELECT * FROM input ORDER BY id")
            assertThat(source.sameContract(expectedSource)).isTrue()
            for (unit in units) {
                for ((id, day) in PushdownEdgeFixtures.dateDays.withIndex()) {
                    val value = day?.let { "date_add('day',BIGINT '$it',DATE '1970-01-01')" } ?: "CAST(NULL AS DATE)"
                    val expected = outcome("SELECT date_trunc('$unit',d) FROM UNNEST(ARRAY[$value]) input(d)")
                    val actual = outcome("SELECT date_trunc('$unit',d) FROM date_$profile.audit.dates WHERE id=$id")
                    observations.add(listOf(profile, unit, id, expected.describe(), actual.describe()).joinToString("\t"))
                    assertThat(actual.sameContract(expected)).describedAs("%s %s id=%s", profile, unit, id).isTrue()
                    if (expected.errorCode != null) errors++ else assertThat(actual.types).containsExactly("date")
                }
                val predicate = "date_trunc('$unit',d)=d"
                val oracle = "WITH input(id,d) AS (VALUES " + PushdownEdgeFixtures.dateDays.take(6).mapIndexed { id, day ->
                    "(BIGINT '$id'," + (day?.let { "date_add('day',BIGINT '$it',DATE '1970-01-01')" } ?: "CAST(NULL AS DATE)") + ")"
                }.joinToString(",") + ") SELECT id FROM input WHERE $predicate ORDER BY id"
                val sql = "SELECT id FROM date_$profile.audit.dates WHERE id<6 AND $predicate ORDER BY id"
                assertThat(outcome(sql).sameContract(outcome(oracle))).isTrue()
                val plan = computeActual("EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                Files.writeString(output.resolve("$profile-$unit.plan"), plan)
                // Trino may erase the identity day truncation before the connector sees a call.
                if (unit != "day") assertThat(plan).contains("filterPredicate")
            }
        }
        assertThat(errors).isGreaterThan(0)
        Files.write(output.resolve("contracts.tsv"), observations)
    }

    private fun outcome(sql: String): EdgeAuditResult = try {
        val result = computeActual(sql)
        EdgeAuditResult(result.types.map { it.toString() }, result.materializedRows.map { row ->
            (0 until row.fieldCount).map { EdgeAuditValue.canonical(row.getField(it)) }
        })
    } catch (failure: QueryFailedException) {
        val info = (queryRunner as DistributedQueryRunner).coordinator.queryManager.getFullQueryInfo(failure.queryId)
        val code = checkNotNull(info.errorCode).name
        check(code !in setOf("SYNTAX_ERROR", "FUNCTION_NOT_FOUND", "COLUMN_NOT_FOUND", "TYPE_MISMATCH")) { "Invalid DATE oracle/query: $sql" }
        EdgeAuditResult(errorCode = code, errorType = info.failureInfo?.type, message = info.failureInfo?.message)
    }
}
