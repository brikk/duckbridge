package dev.brikk.duckbridge.trino.plugin

import io.trino.Session
import io.trino.plugin.jdbc.JdbcPlugin
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.airlift.slice.Slices.utf8Slice
import io.trino.spi.expression.Call
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.TimestampType.TIMESTAMP_MICROS
import io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.spi.type.TimeZoneKey
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.DistributedQueryRunner
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.util.Optional
import java.util.Properties

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestWithTimezoneParity : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private val oracles = linkedMapOf<String, Connection>()
    private val output = Path.of("build/with-timezone-parity")
    private val base = testSessionBuilder().setCatalog("attach_native").setSchema("audit").setTimeZoneKey(TimeZoneKey.UTC_KEY).build()

    override fun createQueryRunner(): QueryRunner {
        Files.createDirectories(output)
        val url = DuckBridgeQueryRunner.freshDatabaseUrl()
        oracles["native"] = DriverManager.getConnection(url, DuckBridgeQueryRunner.duckJdbcProperties())
        WithTimezoneCases.seed(oracles.getValue("native"))
        server = TestingQuackServer()
        oracles["remote"] = DriverManager.getConnection(server.connectionUrl(), Properties().apply { setProperty("token", server.token) })
        WithTimezoneCases.seed(oracles.getValue("remote"))
        oracles.values.forEach(WithTimezoneCases::seedWide)
        val runner = DistributedQueryRunner.builder(base).build()
        try {
            runner.installPlugin(DuckBridgePlugin())
            runner.installPlugin(JdbcPlugin("attach_no_expr") { DuckBridgeClientModule(expressionPushdownEnabled = false) })
            for (profile in oracles.keys) {
                val properties = buildMap {
                    put("connection-url", if (profile == "native") url else server.connectionUrl())
                    put("duckbridge.string-pushdown.mode", "BINARY")
                    if (profile == "remote") put("duckbridge.quack.token", server.token)
                }
                runner.createCatalog("attach_$profile", "duckbridge", properties)
                runner.createCatalog("attach_${profile}_local", "attach_no_expr", properties)
            }
            return runner
        } catch (failure: Throwable) { runner.close(); throw failure }
    }

    @AfterAll
    fun closeOracles() {
        oracles.values.forEach(Connection::close)
        if (::server.isInitialized) server.close()
    }

    @Test
    fun testPreferredOriginalOffsetCandidate() {
        Files.createDirectories(output)
        val summary = mutableListOf("profile\tsession_zone\ttarget_zone\trows\traw_differences")
        for (connection in oracles.values) connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version(),source_id FROM pragma_version()").use { rows ->
                check(rows.next()); assertThat(rows.getString(1)).isEqualTo("v2.0.0")
                assertThat(rows.getString(2)).isEqualTo("d54cbbe3df")
            }
        }
        for ((profile, connection) in oracles) for (sessionZone in WithTimezoneCases.zones) {
            connection.createStatement().use { it.execute("SET TimeZone='$sessionZone'") }
            val session = session(profile, sessionZone)
            val expectedSource = computeActual(session, WithTimezoneCases.input(sessionZone) +
                " SELECT id,wall,fraction,z AT TIME ZONE 'UTC' FROM input ORDER BY id")
            val source = computeActual(session, "SELECT id,wall,fraction,z AT TIME ZONE 'UTC' FROM attach_$profile.audit.attach ORDER BY id")
            assertThat(source.types).isEqualTo(expectedSource.types)
            assertThat(source.materializedRows).isEqualTo(expectedSource.materializedRows)
            for (zone in WithTimezoneCases.zones) {
                val attached = "with_timezone(wall,'$zone')"
                val measured = "date_diff('millisecond',TIMESTAMP '1970-01-01 00:00:00.000000 UTC',$attached)*BIGINT '1000'+fraction"
                val expected = computeActual(session, "${WithTimezoneCases.input()} SELECT id,$measured FROM input ORDER BY id")
                    .materializedRows.map { (it.getField(0) as Long) to (it.getField(1) as Long?) }
                val actual = nativeLongs(connection, "epoch_us(${candidate(zone)})")
                val raw = nativeLongs(connection, "epoch_us(timezone('$zone',wall))")
                assertThat(actual).describedAs("%s %s %s", profile, sessionZone, zone).isEqualTo(expected)
                val differences = expected.indices.count { expected[it] != raw[it] }
                summary.add(listOf(profile, sessionZone, zone, expected.size, differences).joinToString("\t"))
                Files.write(output.resolve("${key(profile, sessionZone, zone)}.tsv"),
                    listOf("id\ttrino_micros\traw_micros\tcandidate_micros") + expected.indices.map {
                        listOf(expected[it].first, expected[it].second, raw[it].second, actual[it].second).joinToString("\t")
                    })
            }
        }
        Files.write(output.resolve("candidate-summary.tsv"), summary)
    }

    @Test
    fun testFiniteNativeRangeBoundaryDiagnostic() {
        Files.createDirectories(output)
        val sql = "SELECT year(with_timezone(wall,'America/New_York')),hour(with_timezone(wall,'America/New_York')) " +
            "FROM UNNEST(ARRAY[TIMESTAMP '294247-01-10 04:00:54.775000']) input(wall)"
        val host = computeActual(session("native", "UTC"), sql)
        assertThat(host.rowCount).isEqualTo(1)
        assertThat(host.materializedRows.single().getField(0)).isEqualTo(294247L)
        assertThat(host.materializedRows.single().getField(1)).isEqualTo(4L)
        Files.writeString(output.resolve("finite-native-boundary.txt"), "Trino SQL=$sql\nTrino=$host\n")
        for ((profile, connection) in oracles) {
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE audit.native_edge AS SELECT make_timestamp(9223372036854775000) wall")
                try {
                    val failure = assertThrows(SQLException::class.java) {
                        statement.executeQuery("SELECT epoch_us(${candidate("America/New_York")}) FROM audit.native_edge").use { it.next() }
                    }
                    Files.writeString(output.resolve("$profile-finite-native-boundary.txt"), failure.stackTraceToString())
                    assertThat(failure.message).containsIgnoringCase("overflow")
                } finally { connection.createStatement().use { it.execute("DROP TABLE audit.native_edge") } }
            }
        }
    }

    @Test
    fun testProductionAttachmentAndNestedExtraction() {
        Files.createDirectories(output)
        for (profile in oracles.keys) for (sessionZone in WithTimezoneCases.zones) {
            val session = session(profile, sessionZone)
            for (zone in WithTimezoneCases.fixedZones) {
                val attached = "with_timezone(wall,'$zone')"
                val predicates = listOf("$attached=z", "$attached<z", "$attached<=z", "NOT ($attached<=z)",
                    "$attached IS NOT DISTINCT FROM z", "hour($attached)=hour(wall)", "minute($attached)=minute(wall)",
                    "year($attached)<1", "to_unixtime($attached)<0", "$attached IS NULL")
                for ((index, predicate) in predicates.withIndex()) {
                    val expected = computeActual(session, "${WithTimezoneCases.input(sessionZone)} SELECT id FROM input WHERE $predicate ORDER BY id")
                    val sql = "SELECT id FROM attach_$profile.audit.attach WHERE $predicate ORDER BY id"
                    val actual = computeActual(session, sql)
                    val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                    Files.writeString(output.resolve("${key(profile, sessionZone, zone)}-$index.plan"), plan)
                    assertThat(actual.materializedRows).describedAs("%s %s %s", profile, sessionZone, predicate).isEqualTo(expected.materializedRows)
                    assertThat(plan).doesNotContain("filterPredicate")
                    assertThat(plan).contains("HUGEINT")
                }
            }
        }
    }

    @Test
    fun testExactFixedOffsetScalarConsumers() {
        Files.createDirectories(output)
        val fields = listOf("year", "month", "day", "quarter", "hour", "minute", "second", "millisecond", "to_unixtime")
        for ((profile, connection) in oracles) for (sessionZone in WithTimezoneCases.zones) {
            val session = session(profile, sessionZone)
            connection.createStatement().use { it.execute("SET TimeZone='$sessionZone'") }
            for (zone in WithTimezoneCases.fixedZones) {
                val expressions = fields.joinToString(",") { "$it(with_timezone(wall,'$zone'))" }
                val expected = computeActual(session, "${WithTimezoneCases.input(sessionZone)} SELECT id,$expressions FROM input ORDER BY id")
                    .materializedRows.map { row -> (0 until row.fieldCount).map { canonical(row.getField(it)) } }
                val emitted = fields.joinToString(",") { emitted(it, zone, sessionZone) }
                val actual = nativeRows(connection, "SELECT id,$emitted FROM audit.attach ORDER BY id")
                Files.write(output.resolve("${key(profile, sessionZone, zone)}-fixed-fields.tsv"),
                    listOf("id\ttrino_fields_exact_double_bits\tnative_fields_exact_double_bits") + expected.indices.map {
                        listOf(expected[it][0], expected[it].drop(1), actual[it].drop(1)).joinToString("\t")
                    })
                assertThat(actual)
                    .describedAs("%s %s %s exact fields/DOUBLE bits", profile, sessionZone, zone).isEqualTo(expected)
            }
        }
        Files.writeString(output.resolve("fixed-scalar-profile.txt"), "rows=${WithTimezoneCases.micros.size}\n" +
            "zones=${WithTimezoneCases.fixedZones}\nJVM=${java.time.ZoneId.systemDefault()}\nExact DOUBLE bits, no tolerance\n")
    }

    @Test
    fun testFullFiniteSourceEndpointsWithoutNarrowingGuard() {
        val fields = listOf("year", "month", "day", "quarter", "hour", "minute", "second", "millisecond", "to_unixtime")
        for ((profile, connection) in oracles) for (zone in WithTimezoneCases.fixedZones) {
            val expressions = fields.joinToString(",") { "$it(with_timezone(wall,'$zone'))" }
            val input = "WITH input AS (${WithTimezoneCases.wideInput()})"
            val measured = "CAST(date_diff('millisecond',TIMESTAMP '1970-01-01 00:00:00.000000',wall) AS DECIMAL(38,0))" +
                "*DECIMAL '1000'+CAST(fraction AS DECIMAL(38,0))"
            val measuredSource = computeActual(session(profile, "UTC"), "$input SELECT $measured FROM input ORDER BY id")
                .materializedRows.map { (it.getField(0) as java.math.BigDecimal).longValueExact() }
            assertThat(measuredSource).isEqualTo(WithTimezoneCases.wideEpochs())
            val expected = computeActual(session(profile, "UTC"), "$input SELECT id,$expressions FROM input ORDER BY id")
                .materializedRows.map { row -> (0 until row.fieldCount).map { canonical(row.getField(it)) } }
            val emitted = fields.joinToString(",") { emitted(it, zone, "UTC") }
            val actual = nativeRows(connection, "SELECT id,$emitted FROM audit.wide ORDER BY id")
            Files.write(output.resolve("$profile-wide-${zone.replace(':', '_')}-fields.tsv"),
                listOf("id\ttrino_fields_exact_double_bits\tnative_fields_exact_double_bits") + expected.indices.map {
                    listOf(expected[it][0], expected[it].drop(1), actual[it].drop(1)).joinToString("\t")
                })
            assertThat(actual).isEqualTo(expected)
            val predicate = "hour(with_timezone(wall,'$zone'))=hour(wall) AND " +
                "with_timezone(wall,'$zone')<=with_timezone(wall,'UTC')"
            val oracle = computeActual(session(profile, "UTC"), "$input SELECT id FROM input WHERE $predicate ORDER BY id")
            val sql = "SELECT id FROM attach_$profile.audit.wide WHERE $predicate ORDER BY id"
            assertThat(computeActual(session(profile, "UTC"), sql).materializedRows).isEqualTo(oracle.materializedRows)
            val plan = computeActual(session(profile, "UTC"), "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
            assertThat(plan).doesNotContain("filterPredicate").contains("HUGEINT")
            Files.createDirectories(output)
            Files.writeString(output.resolve("$profile-wide-${zone.replace(':', '_')}.plan"), plan)
        }
    }

    @Test
    fun testNamedZonesAndDisabledPropertyRemainLocal() {
        for (profile in oracles.keys) for (sessionZone in WithTimezoneCases.zones) {
            for (zone in listOf("America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe", "UTC")) {
                val session = if (zone == "UTC") Session.builder(session(profile, sessionZone))
                    .setCatalogSessionProperty("attach_$profile", "pushdown_timestamp_with_timezone", "false").build() else session(profile, sessionZone)
                val predicate = "hour(with_timezone(wall,'$zone'))=hour(wall)"
                val sql = "SELECT id FROM attach_$profile.audit.attach WHERE $predicate ORDER BY id"
                val expected = computeActual(session, "${WithTimezoneCases.input(sessionZone)} SELECT id FROM input WHERE $predicate ORDER BY id")
                assertThat(computeActual(session, sql).materializedRows).isEqualTo(expected.materializedRows)
                val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                Files.writeString(output.resolve("${key(profile, sessionZone, zone)}-local.plan"), plan)
                assertThat(plan).contains("filterPredicate")
            }
        }
    }

    @Test
    fun testCompositionsKeepZoneProvenanceOrRetainTheConsumer() {
        for (profile in oracles.keys) for (sessionZone in WithTimezoneCases.zones) {
            val session = session(profile, sessionZone)
            val value = "with_timezone(wall,'UTC')"
            val predicates = listOf("coalesce($value,z)=$value", "hour(nullif($value,z))=1",
                "hour(coalesce($value,z))=hour(wall)", "hour(coalesce($value,with_timezone(wall,'+01:00')))=hour(wall)")
            for ((index, predicate) in predicates.withIndex()) {
                val expected = computeActual(session, "${WithTimezoneCases.input(sessionZone)} SELECT id FROM input WHERE $predicate ORDER BY id")
                val sql = "SELECT id FROM attach_$profile.audit.attach WHERE $predicate ORDER BY id"
                assertThat(computeActual(session, sql).materializedRows).isEqualTo(expected.materializedRows)
                val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                Files.writeString(output.resolve("$profile-${sessionZone.replace('/', '_')}-composition-$index.plan"), plan)
                val local = index == 3 || (index == 2 && sessionZone != "UTC")
                if (local) assertThat(plan).contains("filterPredicate")
                else assertThat(plan).doesNotContain("filterPredicate").contains("HUGEINT")
            }
        }
    }

    @Test
    fun testInvalidSourcesFailLoudWithoutRejectingWideFiniteInputs() {
        for ((profile, connection) in oracles) {
            connection.createStatement().use {
                it.execute("CREATE TABLE audit.bad(id BIGINT,wall TIMESTAMP,z TIMESTAMPTZ)")
                it.execute("INSERT INTO audit.bad VALUES (1,make_timestamp(-1),make_timestamptz(-1)),(2,NULL,NULL)," +
                    "(3,TIMESTAMP 'infinity',make_timestamptz(0)),(4,TIMESTAMP '-infinity',make_timestamptz(0))," +
                    "(5,make_timestamp(0),TIMESTAMPTZ 'infinity'),(6,make_timestamp(0),make_timestamptz(2251799813685248000))," +
                    "(7,make_timestamp(9223372036854775806),make_timestamptz(0))")
            }
            try {
                val session = session(profile, "UTC")
                for (id in 3..6) {
                    val expression = if (id <= 4) "hour(with_timezone(wall,'UTC'))>=0" else
                        "coalesce(with_timezone(wall,'UTC'),z)=with_timezone(wall,'UTC')"
                    val sql = "SELECT id FROM attach_$profile.audit.bad WHERE id=$id AND $expression"
                    val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                    Files.writeString(output.resolve("$profile-source-guard-$id.plan"), plan)
                    assertThat(plan).contains("error(").doesNotContain("filterPredicate")
                    assertThatThrownBy { computeActual(session, sql) }.hasMessageContaining("finite representable source values")
                }
                for (id in listOf(1, 7)) {
                    assertThat(computeActual(session, "SELECT id FROM attach_$profile.audit.bad " +
                        "WHERE id=$id AND hour(with_timezone(wall,'-14:00'))>=0").rowCount).isEqualTo(1)
                }
                assertThat(computeActual(session, "SELECT id FROM attach_$profile.audit.bad " +
                    "WHERE id=2 AND with_timezone(wall,'UTC') IS NULL").rowCount).isEqualTo(1)
            } finally { connection.createStatement().use { it.execute("DROP TABLE audit.bad") } }
        }
    }

    private fun emitted(field: String, zone: String, sessionZone: String): String {
        val session = TestingConnectorSession.builder().setTimeZoneKey(TimeZoneKey.getTimeZoneKey(sessionZone))
            .setPropertyMetadata(DuckBridgeSessionProperties(DuckBridgeConfig()).getSessionProperties()).build()
        val wall = Variable("wall", TIMESTAMP_MICROS)
        val column = JdbcColumnHandle("wall", JdbcTypeHandle(Types.TIMESTAMP, Optional.of("TIMESTAMP"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), TIMESTAMP_MICROS)
        val value = Call(TIMESTAMP_TZ_MICROS, FunctionName("with_timezone"), listOf(wall, Constant(utf8Slice(zone), VARCHAR)))
        return checkNotNull(DuckBridgeExpressionTranslator.translate(Call(if (field == "to_unixtime") DOUBLE else BIGINT,
            FunctionName(field), listOf(value)), mapOf("wall" to column), session, aliasAvailable = false, stringComparisonAllowed = false))
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is Double -> "D:${value.toRawBits()}"
        is Number -> value.toLong()
        else -> value
    }

    private fun nativeRows(connection: Connection, sql: String): List<List<Any?>> = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            for (index in 1..rows.metaData.columnCount) {
                assertThat(rows.metaData.getColumnType(index)).isEqualTo(if (index == rows.metaData.columnCount) Types.DOUBLE else Types.BIGINT)
            }
            buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { canonical(rows.getObject(it)) }) }
        }
    }

    private fun candidate(zone: String): String {
        val t = "epoch_us(wall)"
        fun offset(epoch: String): String = "(epoch_us(timezone('$zone',make_timestamptz($epoch)))-($epoch))"
        val original = offset(t)
        val guess = "($t-$original)"
        return "(CASE WHEN $original=${offset(guess)} THEN make_timestamptz($guess) ELSE timezone('$zone',wall) END)"
    }

    private fun nativeLongs(connection: Connection, expression: String): List<Pair<Long, Long?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id,$expression FROM audit.attach ORDER BY id").use { rows ->
                buildList { while (rows.next()) {
                    val id = rows.getLong(1)
                    val value = rows.getLong(2)
                    add(id to if (rows.wasNull()) null else value)
                } }
            }
        }

    private fun session(profile: String, zone: String): Session = Session.builder(base).setCatalog("attach_$profile")
        .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(zone)).build()
    private fun key(profile: String, sessionZone: String, zone: String): String =
        "$profile-${sessionZone.replace('/', '_')}-${zone.replace('/', '_')}"
}
