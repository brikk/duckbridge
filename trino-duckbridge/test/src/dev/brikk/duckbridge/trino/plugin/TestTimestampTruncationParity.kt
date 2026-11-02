package dev.brikk.duckbridge.trino.plugin

import io.trino.Session
import io.trino.plugin.jdbc.JdbcPlugin
import io.trino.spi.expression.Constant
import io.trino.spi.expression.Variable
import io.trino.spi.type.SqlTimestampWithTimeZone
import io.trino.spi.type.TimeZoneKey
import io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.DistributedQueryRunner
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import io.airlift.slice.Slices.utf8Slice
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Properties

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestTimestampTruncationParity : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private val oracles = linkedMapOf<String, Connection>()
    private val output = Path.of("build/timestamp-truncation-parity")
    private val base = testSessionBuilder().setCatalog("ts_native").setSchema("audit").setTimeZoneKey(TimeZoneKey.UTC_KEY).build()

    override fun createQueryRunner(): QueryRunner {
        val url = DuckBridgeQueryRunner.freshDatabaseUrl()
        oracles["native"] = DriverManager.getConnection(url, DuckBridgeQueryRunner.duckJdbcProperties())
        TimestampTruncationCases.seed(oracles.getValue("native"))
        server = TestingQuackServer()
        oracles["remote"] = DriverManager.getConnection(server.connectionUrl(), Properties().apply { setProperty("token", server.token) })
        TimestampTruncationCases.seed(oracles.getValue("remote"))
        val runner = DistributedQueryRunner.builder(base).build()
        try {
            runner.installPlugin(DuckBridgePlugin())
            runner.installPlugin(JdbcPlugin("ts_no_expr") { DuckBridgeClientModule(expressionPushdownEnabled = false) })
            for (profile in listOf("native", "remote")) {
                val properties = buildMap {
                    put("connection-url", if (profile == "native") url else server.connectionUrl())
                    put("duckbridge.string-pushdown.mode", "BINARY")
                    if (profile == "remote") put("duckbridge.quack.token", server.token)
                }
                runner.createCatalog("ts_$profile", "duckbridge", properties)
                runner.createCatalog("ts_${profile}_local", "ts_no_expr", properties)
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
    fun testExactSubdayCandidatesAndProductionPredicates() {
        Files.createDirectories(output)
        val scalarEvidence = mutableListOf("profile\tzone\tunit\trows\traw_native_differences")
        for ((profile, connection) in oracles) {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version(),source_id FROM pragma_version()").use { rows ->
                    check(rows.next()); assertThat(rows.getString(1)).isEqualTo("v2.0.0")
                    assertThat(rows.getString(2)).isEqualTo("d54cbbe3df")
                }
            }
            for (zone in TimestampTruncationCases.zones) {
                connection.createStatement().use { it.execute("SET TimeZone='$zone'") }
                val session = session(profile, zone)
                val input = TimestampTruncationCases.input(zone)
                // Only the measurement is exported in UTC; inner functions still use the original query/value zone.
                val expectedSource = computeActual(session, "$input SELECT id,at_timezone(z,'UTC'),wall,at_timezone(other,'UTC') FROM input ORDER BY id")
                val source = computeActual(session, "SELECT id,at_timezone(z,'UTC'),wall,at_timezone(other,'UTC') FROM ts_$profile.audit.ts ORDER BY id")
                assertThat(source.types).isEqualTo(expectedSource.types)
                assertThat(source.materializedRows).isEqualTo(expectedSource.materializedRows)
                for (unit in TimestampTruncationCases.units) {
                    scalarEvidence.add(validateScalars(connection, profile, zone, unit, session, input))
                    validatePredicates(profile, zone, unit, session, input)
                }
            }
        }
        Files.write(output.resolve("scalars.tsv"), scalarEvidence)
        Files.writeString(output.resolve("profile.txt"), "JDK=${Runtime.version()}\nJVM.zone=${java.time.ZoneId.systemDefault()}\n" +
            "Joda=${org.joda.time.DateTimeZone::class.java.protectionDomain.codeSource.location}\n")
    }

    private fun validateScalars(connection: Connection, profile: String, zone: String, unit: String, session: Session, input: String): String {
        val measured = epochMeasurement("date_trunc('$unit',z)")
        val host = computeActual(session, "$input SELECT id,$measured FROM input ORDER BY id")
        val expected = host.materializedRows.map { row -> (row.getField(0) as Long) to (row.getField(1) as Long?) }
        val native = nativeEpochs(connection, "date_trunc('$unit',z)")
        val candidate = candidate(unit)
        val actual = nativeEpochs(connection, candidate)
        assertThat(actual).describedAs("%s %s %s candidate", profile, zone, unit).isEqualTo(expected)
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT DISTINCT typeof($candidate) FROM audit.ts").use { rows ->
                check(rows.next()); assertThat(rows.getString(1)).isEqualTo("TIMESTAMP WITH TIME ZONE")
            }
        }
        assertThat(computeActual(session, "SELECT date_trunc('$unit',z) FROM ts_$profile.audit.ts WHERE id=0").types)
            .containsExactly(TIMESTAMP_TZ_MICROS)
        val production = computeActual(session, "SELECT id,$measured FROM ts_$profile.audit.ts ORDER BY id")
        assertThat(production.types).isEqualTo(host.types)
        assertThat(production.materializedRows).isEqualTo(host.materializedRows)
        val differences = expected.indices.count { expected[it] != native[it] }
        val lines = expected.indices.map { index ->
            listOf(expected[index].first, expected[index].second, native[index].second, actual[index].second).joinToString("\t")
        }
        Files.write(output.resolve("${key(profile, zone, unit)}-scalars.tsv"), listOf("id\ttrino_micros\traw_micros\tcandidate_micros") + lines)
        return listOf(profile, zone, unit, actual.size, differences).joinToString("\t")
    }

    private fun epochMeasurement(expression: String): String =
        "(date_diff('millisecond',TIMESTAMP '1970-01-01 00:00:00.000000 UTC',$expression)*BIGINT '1000')"

    private fun validatePredicates(profile: String, zone: String, unit: String, session: Session, input: String) {
        val expression = "date_trunc('$unit',z)"
        val predicates = listOf("$expression<=z", "$expression=z", "NOT ($expression<=z)",
            "minute($expression)=0", "year($expression)<1970")
        for ((index, predicate) in predicates.withIndex()) {
            val expected = computeActual(session, "$input SELECT id FROM input WHERE $predicate ORDER BY id")
            val sql = "SELECT id FROM ts_$profile.audit.ts WHERE $predicate ORDER BY id"
            val actual = computeActual(session, sql)
            val local = computeActual(session, "SELECT id FROM ts_${profile}_local.audit.ts WHERE $predicate ORDER BY id")
            val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
            Files.writeString(output.resolve("${key(profile, zone, unit)}-$index.plan"), plan)
            assertThat(actual.materializedRows).describedAs("%s %s %s", profile, zone, predicate).isEqualTo(expected.materializedRows)
            assertThat(local.materializedRows).isEqualTo(expected.materializedRows)
            assertThat(plan).describedAs("Must be a real native predicate: %s", predicate).doesNotContain("filterPredicate")
            assertThat(plan).contains("make_timestamptz")
        }
    }

    @Test
    fun testAstronomicalZonedYear() {
        for ((profile, connection) in oracles) {
            for (zone in TimestampTruncationCases.zones) {
                connection.createStatement().use { it.execute("SET TimeZone='$zone'") }
                val session = session(profile, zone)
                val input = TimestampTruncationCases.input(zone)
                val expected = computeActual(session, "$input SELECT id,year(z) FROM input ORDER BY id").materializedRows
                    .map { (it.getField(0) as Long) to (it.getField(1) as Long?) }
                val argument = Variable("z", TIMESTAMP_TZ_MICROS)
                val candidate = checkNotNull(DuckBridgeTimestampTruncation.year("z", argument))
                assertThat(nativeLongs(connection, candidate)).isEqualTo(expected)
                for (predicate in listOf("year(z)<0", "year(z)=0", "year(z)<1970")) {
                    val sql = "SELECT id FROM ts_$profile.audit.ts WHERE $predicate ORDER BY id"
                    val actual = computeActual(session, sql)
                    val host = computeActual(session, "$input SELECT id FROM input WHERE $predicate ORDER BY id")
                    assertThat(actual.materializedRows).isEqualTo(host.materializedRows)
                    val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                    assertThat(plan).contains("era(").doesNotContain("filterPredicate")
                }
            }
        }
    }

    @Test
    fun testYearConsumesFiniteTruncationOutsidePackedStorage() {
        val minimum = -2_251_799_813_685_248_000L
        val maximum = 2_251_799_813_685_247_999L
        for ((profile, connection) in oracles) {
            connection.createStatement().use {
                it.execute("CREATE TABLE audit.packed_edge AS SELECT * FROM (VALUES " +
                    "(1::BIGINT,make_timestamptz($minimum)),(2::BIGINT,make_timestamptz($maximum))) t(id,z)")
            }
            try {
                for (zone in TimestampTruncationCases.zones) {
                    val session = session(profile, zone)
                    val input = "WITH input(id,z) AS (VALUES (BIGINT '1',${TimestampTruncationCases.trinoZoned(minimum, zone)})," +
                        "(BIGINT '2',${TimestampTruncationCases.trinoZoned(maximum, zone)}))"
                    val predicate = "year(date_trunc('hour',z))<1970"
                    val expected = computeActual(session, "$input SELECT id FROM input WHERE $predicate ORDER BY id")
                    val sql = "SELECT id FROM ts_$profile.audit.packed_edge WHERE $predicate ORDER BY id"
                    assertThat(computeActual(session, sql).materializedRows).isEqualTo(expected.materializedRows)
                    val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                    assertThat(plan).contains("isfinite(", "make_timestamptz").doesNotContain("filterPredicate")
                }
                connection.createStatement().use { statement ->
                    statement.execute("SET TimeZone='UTC'")
                    statement.executeQuery("SELECT epoch_us(${candidate("hour")}) FROM audit.packed_edge WHERE id=1").use { rows ->
                        check(rows.next()); assertThat(rows.getLong(1)).isLessThan(minimum)
                    }
                }
            } finally {
                connection.createStatement().use { it.execute("DROP TABLE audit.packed_edge") }
            }
        }
    }

    @Test
    fun testUnsupportedZonedInputsFailInsteadOfFilteringRows() {
        for ((profile, connection) in oracles) {
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE audit.bad(id BIGINT,z TIMESTAMPTZ)")
                statement.execute("INSERT INTO audit.bad VALUES (1,make_timestamptz(-1))," +
                    "(2,TIMESTAMPTZ 'infinity'),(3,TIMESTAMPTZ '-infinity')," +
                    "(4,make_timestamptz(-2251799813685248001)),(5,make_timestamptz(2251799813685248000)),(6,NULL)")
            }
            try {
                for (expression in listOf("date_trunc('hour',z)<=z", "year(z)<1970")) {
                    for (id in 2..5) {
                        val sql = "SELECT id FROM ts_$profile.audit.bad WHERE id=$id AND $expression"
                        val plan = computeActual("EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                        assertThat(plan).doesNotContain("filterPredicate").contains("error(")
                        assertThatThrownBy { computeActual(sql) }.hasMessageContaining("finite Trino-representable input")
                    }
                    assertThat(computeActual("SELECT id FROM ts_$profile.audit.bad WHERE id=1 AND $expression").rowCount).isEqualTo(1)
                    assertThat(computeActual("SELECT id FROM ts_$profile.audit.bad WHERE id=6 AND $expression").rowCount).isEqualTo(0)
                }
            } finally {
                connection.createStatement().use { it.execute("DROP TABLE audit.bad") }
            }
        }
    }

    @Test
    fun testZonedFunctionSwitchAndUnknownZoneOriginsStayLocal() {
        for (profile in oracles.keys) {
            val session = session(profile, "America/New_York")
            val predicates = listOf("date_trunc('hour',with_timezone(wall,'UTC'))<=z", "date_trunc('hour',z)<=z")
            for ((index, predicate) in predicates.withIndex()) {
                val configured = if (index == 1) Session.builder(session)
                    .setCatalogSessionProperty("ts_$profile", "pushdown_timestamp_with_timezone", "false").build() else session
                val sql = "SELECT id FROM ts_$profile.audit.ts WHERE $predicate ORDER BY id"
                val plan = computeActual(configured, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                assertThat(plan).contains("filterPredicate")
                val expected = computeActual(configured, "${TimestampTruncationCases.input("America/New_York")} " +
                    "SELECT id FROM input WHERE $predicate ORDER BY id")
                assertThat(computeActual(configured, sql).materializedRows).isEqualTo(expected.materializedRows)
            }
        }
    }

    @Test
    fun testKnownZoneCompositionsAndNestedTruncationPlans() {
        Files.createDirectories(output)
        for (profile in oracles.keys) {
            val session = session(profile, "America/New_York")
            val input = TimestampTruncationCases.input("America/New_York")
            for ((index, value) in listOf("coalesce(z,other)", "nullif(z,other)", "if(id>=10,z,other)").withIndex()) {
                val predicate = "date_trunc('hour',$value)<=z"
                val sql = "SELECT id FROM ts_$profile.audit.ts WHERE $predicate ORDER BY id"
                val expected = computeActual(session, "$input SELECT id FROM input WHERE $predicate ORDER BY id")
                assertThat(computeActual(session, sql).materializedRows).isEqualTo(expected.materializedRows)
                val plan = computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
                Files.writeString(output.resolve("$profile-composition-$index.plan"), plan)
                if (index == 2) {
                    // Trino 483 ConnectorExpressionTranslator.visitCase only translates NULLIF, not generic IF/CASE.
                    assertThat(plan).contains("filterPredicate").doesNotContain("make_timestamptz")
                } else {
                    assertThat(plan).doesNotContain("filterPredicate").contains("make_timestamptz")
                    assertThat(plan).contains(if (index == 0) "coalesce(" else "nullif(")
                }
            }
            val nested = "date_trunc('hour',date_trunc('minute',z))<=z"
            val sql = "SELECT id FROM ts_$profile.audit.ts WHERE $nested ORDER BY id"
            assertThat(computeActual(session, "EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()).contains("filterPredicate")
            val expected = computeActual(session, "$input SELECT id FROM input WHERE $nested ORDER BY id")
            assertThat(computeActual(session, sql).materializedRows).isEqualTo(expected.materializedRows)
        }
    }

    @Test
    fun testLateOverlapResultCarrierDiagnostic() {
        val micros = TimestampTruncationCases.epochMicros(java.time.Instant.parse("2024-11-03T06:30:00Z"))
        val id = TimestampTruncationCases.micros.indexOf(micros)
        check(id >= 0)
        val session = session("native", "America/New_York")
        val sql = "${TimestampTruncationCases.input("America/New_York")} SELECT " +
            "date_diff('millisecond',TIMESTAMP '1970-01-01 00:00:00.000000 UTC',z)," +
            "date_diff('millisecond',TIMESTAMP '1970-01-01 00:00:00.000000 UTC',date_trunc('millisecond',z))," +
            "date_trunc('millisecond',z),at_timezone(date_trunc('millisecond',z),'UTC') FROM input WHERE id=$id"
        val row = computeActual(session, sql).materializedRows.single()
        Files.createDirectories(output)
        Files.writeString(output.resolve("late-overlap-result-carrier.txt"), row.toString())
        assertThat(row.getField(0)).isEqualTo(micros / 1000)
        assertThat(row.getField(1)).isEqualTo(micros / 1000)
        assertThat(epoch(row.getField(3))).isEqualTo(micros)
        assertThat(epoch(row.getField(2))).isNotEqualTo(micros)
    }

    private fun candidate(unit: String): String {
        val args = listOf(Constant(utf8Slice(unit), VARCHAR), Variable("z", TIMESTAMP_TZ_MICROS))
        return checkNotNull(DuckBridgeTimestampTruncation.render(listOf("'$unit'", "z"), args))
    }

    private fun nativeEpochs(connection: Connection, expression: String): List<Pair<Long, Long?>> = nativeLongs(connection, "epoch_us($expression)")

    private fun nativeLongs(connection: Connection, expression: String): List<Pair<Long, Long?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id,$expression FROM audit.ts ORDER BY id").use { rows ->
                buildList { while (rows.next()) {
                    val id = rows.getLong(1)
                    val value = rows.getLong(2)
                    add(id to if (rows.wasNull()) null else value)
                } }
            }
        }

    private fun epoch(value: Any?): Long? = when (value) {
        null -> null
        is SqlTimestampWithTimeZone -> value.epochMillis * 1000 + value.picosOfMilli / 1_000_000
        is ZonedDateTime -> TimestampTruncationCases.epochMicros(value.toInstant())
        is OffsetDateTime -> TimestampTruncationCases.epochMicros(value.toInstant())
        else -> error("Unexpected exact timestamp carrier: ${value.javaClass.name}")
    }

    private fun session(profile: String, zone: String): Session = Session.builder(base).setCatalog("ts_$profile")
        .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(zone)).build()
    private fun key(profile: String, zone: String, unit: String): String = "$profile-${zone.replace('/', '_')}-$unit"
}
