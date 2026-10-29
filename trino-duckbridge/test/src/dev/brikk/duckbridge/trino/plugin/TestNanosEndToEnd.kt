package dev.brikk.duckbridge.trino.plugin

import com.gizmodata.quack.jdbc.sql.QuackDriver
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.DistributedQueryRunner
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import io.trino.spi.type.TimeZoneKey.UTC_KEY
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/** Four real scan paths, one independent native-Trino value oracle; no cast-through-micros fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestNanosEndToEnd : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private val catalogs = listOf("native_jdbc", "native_arrow", "remote_jdbc", "remote_arrow")

    override fun createQueryRunner(): QueryRunner {
        server = TestingQuackServer()
        checkNotNull(server.installParityExtension()) { "Qualification needs the matching signed parity binary" }
        val url = DuckBridgeQueryRunner.freshDatabaseUrl()
        DriverManager.getConnection(url).use { seed(it) }
        QuackDriver().connect(server.connectionUrl(), Properties().apply { setProperty("token", server.token) }).use { seed(it) }
        val runner = DistributedQueryRunner.builder(testSessionBuilder().setCatalog("native_jdbc").setSchema("nanos")
            .setTimeZoneKey(UTC_KEY).build()).build()
        try {
            runner.installPlugin(DuckBridgePlugin())
            for (catalog in catalogs) runner.createCatalog(catalog, "duckbridge", buildMap {
                put("connection-url", if (catalog.startsWith("remote")) server.connectionUrl() else url)
                if (catalog.startsWith("remote")) put("duckbridge.quack.token", server.token)
                if (catalog.endsWith("arrow")) put("duckbridge.execution-engine", if (catalog.startsWith("remote")) "QUACK" else "DUCKDB_LOCAL")
                if (DuckBridgeQueryRunner.bundledParityIsUnsigned()) put("duckbridge.allow-unsigned-extensions", "true")
            })
            return runner
        } catch (error: Throwable) { runner.close(); server.close(); throw error }
    }

    private fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA nanos")
            statement.execute("CREATE TABLE nanos.samples AS WITH base(id,ts,clock,z) AS (VALUES ${NanosFixtures.values(true)}) " +
                "SELECT r.range*10+b.id AS id,ts,clock,z,CASE WHEN b.id=9 THEN NULL ELSE [ts,NULL] END AS timestamps," +
                "CASE WHEN b.id=9 THEN NULL ELSE [clock,NULL] END AS clocks FROM base b CROSS JOIN range(300) r")
            statement.execute("CREATE TABLE nanos.infinite AS SELECT CAST('infinity' AS TIMESTAMP_NS) AS ts")
            statement.execute("CREATE TABLE nanos.outside_day AS SELECT CAST('24:00:00' AS TIME_NS) AS clock")
        }
    }

    @AfterAll
    fun closeServer() { if (::server.isInitialized) server.close() }

    private fun expected(projection: String) = computeActual(
        "WITH base(id,ts,clock,z) AS (VALUES ${NanosFixtures.values(false)}) " +
            "SELECT $projection FROM (SELECT rep*10+b.id AS id,ts,clock,z," +
            "CASE WHEN b.id=9 THEN NULL ELSE ARRAY[ts,NULL] END AS timestamps," +
            "CASE WHEN b.id=9 THEN NULL ELSE ARRAY[clock,NULL] END AS clocks " +
            "FROM base b CROSS JOIN UNNEST(sequence(0,299)) AS r(rep)) ORDER BY id",
    )

    @Test
    fun testExactScalarsArraysNullsAndBatchBoundariesOnAllPaths() {
        for (catalog in catalogs) {
            val columns = if (catalog == "native_jdbc") "id,ts,clock,clocks" else "id,ts,clock,timestamps,clocks"
            val expected = expected(columns)
            assertThat(expected.rowCount).isEqualTo(3000)
            val actual = computeActual("SELECT $columns FROM $catalog.nanos.samples ORDER BY id")
            assertThat(actual.types).describedAs(catalog).isEqualTo(expected.types)
            assertThat(actual.materializedRows).describedAs(catalog).isEqualTo(expected.materializedRows)
        }
        val nativeColumns = computeActual("SHOW COLUMNS FROM native_jdbc.nanos.samples").materializedRows.map { it.getField(0) }
        assertThat(nativeColumns).doesNotContain("timestamps")
    }

    @Test
    fun testZonedNanosOnArrowAndQuackAndNativeJdbcRefusal() {
        val expected = expected("id,z")
        for (catalog in catalogs.filter { it != "native_jdbc" }) {
            assertThat(computeActual("SELECT id,z FROM $catalog.nanos.samples ORDER BY id").materializedRows)
                .describedAs(catalog).isEqualTo(expected.materializedRows)
        }
        for (catalog in listOf("native_jdbc")) {
            val columns = computeActual("SHOW COLUMNS FROM $catalog.nanos.samples").materializedRows.map { it.getField(0) }
            assertThat(columns).doesNotContain("z")
        }
    }

    @Test
    fun testWritesBindNineDigitsRatherThanMicrosecondTimestampLiterals() {
        for (catalog in catalogs) {
            val columns = if (catalog != "native_jdbc") "id,ts,clock,z" else "id,ts,clock"
            val source = "SELECT $columns FROM (VALUES ${NanosFixtures.values(false)}) AS base(id,ts,clock,z)"
            val expected = computeActual("$source ORDER BY id")
            computeActual("CREATE TABLE $catalog.nanos.written AS $source")
            try {
                val actual = computeActual("SELECT $columns FROM $catalog.nanos.written ORDER BY id")
                assertThat(actual.types).isEqualTo(expected.types)
                assertThat(actual.materializedRows).describedAs(catalog).isEqualTo(expected.materializedRows)
            } finally { computeActual("DROP TABLE $catalog.nanos.written") }
        }
    }

    @Test
    fun testSessionZoneChangesPreserveNanosInstants() {
        val session = io.trino.testing.TestingSession.testSessionBuilder()
            .setTimeZoneKey(io.trino.spi.type.TimeZoneKey.getTimeZoneKey("America/New_York")).build()
        val source = "SELECT CAST(id AS bigint),CAST(z AT TIME ZONE 'America/New_York' AS varchar) " +
            "FROM (VALUES ${NanosFixtures.values(false)}) base(id,ts,clock,z) ORDER BY id"
        val expected = computeActual(session, source).materializedRows
        for (catalog in catalogs.filter { it != "native_jdbc" }) {
            val actual = computeActual(session, "SELECT id%10,CAST(z AS varchar) FROM $catalog.nanos.samples WHERE id<10 ORDER BY id").materializedRows
            assertThat(actual).describedAs(catalog).isEqualTo(expected)
        }
    }

    @Test
    fun testSevenAndEightDigitWritesUseNineDigitStorageWithoutValueLoss() {
        for (catalog in catalogs) {
            for (precision in 7..8) {
                val zoned = if (catalog != "native_jdbc") ",CAST(z AS timestamp($precision) with time zone) AS z" else ""
                val source = "SELECT CAST(ts AS timestamp($precision)) AS ts,CAST(clock AS time($precision)) AS clock$zoned " +
                    "FROM (VALUES ${NanosFixtures.values(false)}) base(id,ts,clock,z) WHERE id<7 OR id=9"
                val target = "CAST(ts AS timestamp(9)),CAST(clock AS time(9))" +
                    if (catalog != "native_jdbc") ",CAST(z AS timestamp(9) with time zone)" else ""
                val expected = computeActual("SELECT $target FROM ($source)").materializedRows
                computeActual("CREATE TABLE $catalog.nanos.rounded AS $source")
                try {
                    val actual = computeActual("SELECT * FROM $catalog.nanos.rounded").materializedRows
                    assertThat(actual).describedAs("$catalog precision=$precision").containsExactlyInAnyOrderElementsOf(expected)
                } finally { computeActual("DROP TABLE $catalog.nanos.rounded") }
            }
        }
    }

    @Test
    fun testNanosPredicatesRemainLocalAndKeepExactTruthSets() {
        for (catalog in catalogs) {
            val sql = "SELECT id FROM $catalog.nanos.samples WHERE ts=TIMESTAMP '1969-12-31 23:59:59.999999999' ORDER BY id"
            val plan = computeActual("EXPLAIN (TYPE DISTRIBUTED) $sql").onlyValue.toString()
            assertThat(plan).contains("filterPredicate")
            assertThat(computeActual(sql).materializedRows.map { it.getField(0) })
                .isEqualTo((0L until 300L).map { it * 10 })
        }
    }

    @Test
    fun testInfinityAndUnrepresentableTimeFailRatherThanBecomeFiniteValues() {
        for (catalog in catalogs) {
            assertThatThrownBy { computeActual("SELECT ts FROM $catalog.nanos.infinite") }
                .hasMessageMatching("(?s).*[Ii]nfinite.*")
            assertThatThrownBy { computeActual("SELECT clock FROM $catalog.nanos.outside_day") }
                .hasMessageMatching("(?s).*(NanoOfDay|TIME_NS|24-hour|LocalTime).*")
        }
    }
}
