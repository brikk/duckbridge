package dev.brikk.duckbridge.trino.plugin

import java.sql.Connection
import java.sql.Types
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.Locale

internal object WithTimezoneCases {
    val zones = listOf("UTC", "America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe")
    val fixedZones = listOf("UTC", "+05:45", "-05:00", "+14:00", "-14:00")
    private val format = DateTimeFormatterBuilder().appendValue(ChronoField.YEAR, 4, 10, SignStyle.NORMAL)
        .appendPattern("-MM-dd HH:mm:ss.").appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, false).toFormatter(Locale.ROOT)
    val micros: List<Long?> = buildList {
        add(null)
        addAll(listOf(-1_001L, -1_000L, -999L, -1L, 0L, 1L, 999L, 1_000L, 1_001L))
        for (text in listOf("-0001-01-01T00:00:00.000001Z", "0000-01-01T00:00:00.000001Z",
            "0001-01-01T00:00:00.000001Z", "1582-10-04T23:59:59.999999Z", "2000-02-29T12:34:56.123456Z",
            "9999-12-31T23:59:59.999999Z")) add(micros(Instant.parse(text)))
        add(-2_251_799_813_685_248_000L + 172_800_000_000L)
        add(2_251_799_813_685_247_999L - 172_800_000_000L)
        for (text in listOf("2024-03-10T02:00:00Z", "2024-11-03T01:00:00Z",
            "2024-04-07T01:30:00Z", "2024-10-06T02:00:00Z", "1986-01-01T00:00:00Z")) {
            val center = micros(Instant.parse(text))
            for (minute in -90..90 step 15) for (fraction in listOf(-1L, 0L, 1L, 999_999L)) add(center + minute * 60_000_000L + fraction)
        }
    }.distinct()

    private fun micros(instant: Instant): Long = Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000), instant.nano / 1000L)
    private fun text(micros: Long): String = format.format(LocalDateTime.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000, ZoneOffset.UTC))

    fun input(zone: String = "UTC"): String = micros.mapIndexed { id, value ->
        val wall = value?.let { "TIMESTAMP '${text(it)}'" } ?: "CAST(NULL AS TIMESTAMP(6))"
        val z = value?.let { "(TIMESTAMP '${text(it)} UTC' AT TIME ZONE '$zone')" } ?: "CAST(NULL AS TIMESTAMP(6) WITH TIME ZONE)"
        // Fraction supplied independently: zone offsets are whole seconds, so attachment retains these microseconds.
        val fraction = value?.let { Math.floorMod(it, 1000) } ?: 0
        "(BIGINT '$id',$wall,BIGINT '$fraction',$z)"
    }.joinToString(",", "WITH input(id,wall,fraction,z) AS (VALUES ", ")")

    // DuckDB reserves +/-Long.MAX_VALUE for infinities; Long.MIN_VALUE itself is finite.
    private val wideMicros = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 2, -9_223_372_036_854_775_000L,
        9_223_372_036_854_775_000L, Long.MAX_VALUE - 1)

    fun wideInput(onlyId: Int? = null): String = (onlyId?.let { listOf(wideMicros[it - 1]) } ?: wideMicros).joinToString(",",
        "SELECT id,wall,fraction FROM UNNEST(ARRAY[", "]) WITH ORDINALITY input(wall,fraction,id)") { value ->
        // Trino's literal parser overflows floorSeconds*1e6 at the very bottom, even when the final value fits.
        val wall = if (value < Long.MIN_VALUE + 1_000_000) "(TIMESTAMP '${text(value + 1_000_000)}' - INTERVAL '1' SECOND)"
            else "TIMESTAMP '${text(value)}'"
        "ROW($wall,BIGINT '${Math.floorMod(value, 1000)}')"
    }

    fun wideEpochs(): List<Long> = wideMicros

    fun seedWide(connection: Connection) {
        connection.createStatement().use {
            it.execute("CREATE TABLE audit.wide(id BIGINT,wall TIMESTAMP,fraction BIGINT)")
        }
        connection.prepareStatement("INSERT INTO audit.wide VALUES(?,make_timestamp(?),?)").use { statement ->
            wideMicros.forEachIndexed { id, value ->
                statement.setLong(1, id + 1L)
                statement.setLong(2, value)
                statement.setLong(3, Math.floorMod(value, 1000).toLong())
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    fun seed(connection: Connection) {
        connection.createStatement().use {
            it.execute("CREATE SCHEMA audit")
            it.execute("CREATE TABLE audit.attach(id BIGINT,wall TIMESTAMP,fraction BIGINT,z TIMESTAMPTZ)")
        }
        connection.prepareStatement("INSERT INTO audit.attach VALUES(?,make_timestamp(?),?,make_timestamptz(?))").use { statement ->
            micros.forEachIndexed { id, value ->
                statement.setLong(1, id.toLong())
                if (value == null) statement.setNull(2, Types.BIGINT) else statement.setLong(2, value)
                statement.setLong(3, value?.let { Math.floorMod(it, 1000).toLong() } ?: 0)
                if (value == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
