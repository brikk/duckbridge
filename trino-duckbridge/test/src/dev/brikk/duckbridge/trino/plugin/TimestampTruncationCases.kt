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

internal object TimestampTruncationCases {
    val zones = listOf("UTC", "America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe")
    val units = listOf("millisecond", "second", "minute", "hour")
    private val format = DateTimeFormatterBuilder().appendValue(ChronoField.YEAR, 4, 10, SignStyle.NORMAL)
        .appendPattern("-MM-dd HH:mm:ss.").appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, false)
        .toFormatter(Locale.ROOT)
    val micros: List<Long?> = buildList {
        add(null)
        addAll(listOf(-3_600_000_001L, -3_600_000_000L, -1_001L, -1_000L, -999L, -1L, 0L, 1L, 999L, 1_000L, 1_000_001L))
        for (text in listOf("-0001-01-01T00:00:00.000001Z", "0000-01-01T00:00:00.000001Z",
            "0001-01-01T00:00:00.000001Z", "1582-10-04T23:59:59.999999Z",
            "1582-10-15T00:00:00.000001Z", "1677-09-21T00:12:43.145224Z", "2000-02-29T12:34:56.123456Z",
            "9999-12-31T23:59:59.999999Z")) add(epochMicros(Instant.parse(text)))
        // Within the packed Trino limits, leaving room to materialize every truncated scalar.
        add(-2_251_799_813_685_248_000L + 172_800_000_000L)
        add(2_251_799_813_685_247_999L - 172_800_000_000L)
        for (text in listOf("2024-03-10T07:00:00Z", "2024-11-03T06:00:00Z",
            "2024-04-06T15:00:00Z", "2024-10-05T15:30:00Z", "1985-12-31T18:30:00Z")) {
            val center = epochMicros(Instant.parse(text))
            for (minute in -90..90 step 15) for (fraction in listOf(-1L, 0L, 1L, 999_999L)) {
                add(center + minute * 60_000_000L + fraction)
            }
        }
    }.distinct()

    fun instant(micros: Long): Instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000L)
    fun epochMicros(instant: Instant): Long = Math.addExact(Math.multiplyExact(instant.epochSecond, 1_000_000), instant.nano / 1000L)

    fun input(zone: String): String = micros.mapIndexed { id, value ->
        val timestamp = value?.let { format.format(LocalDateTime.ofInstant(instant(it), ZoneOffset.UTC)) }
        val wall = timestamp?.let { "TIMESTAMP '$it'" } ?: "CAST(NULL AS TIMESTAMP(6))"
        "(CAST($id AS BIGINT),${trinoZoned(value, zone)},$wall,${trinoZoned(otherMicros(id), zone)})"
    }.joinToString(",", "WITH input(id,z,wall,other) AS (VALUES ", ")")

    private fun otherMicros(id: Int): Long? = micros[(id + 1) % micros.size]
    fun trinoZoned(value: Long?, zone: String): String = value?.let {
        "(TIMESTAMP '${format.format(LocalDateTime.ofInstant(instant(it), ZoneOffset.UTC))} UTC' AT TIME ZONE '$zone')"
    } ?: "CAST(NULL AS TIMESTAMP(6) WITH TIME ZONE)"

    fun seed(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA audit")
            statement.execute("CREATE TABLE audit.ts(id BIGINT,z TIMESTAMPTZ,wall TIMESTAMP,other TIMESTAMPTZ)")
        }
        connection.prepareStatement("INSERT INTO audit.ts VALUES(?,make_timestamptz(?),make_timestamp(?),make_timestamptz(?))").use { statement ->
            micros.forEachIndexed { id, value ->
                statement.setLong(1, id.toLong())
                if (value == null) {
                    statement.setNull(2, Types.BIGINT)
                    statement.setNull(3, Types.BIGINT)
                } else {
                    // Exact native typed constructors avoid transport-specific ISO year spelling.
                    statement.setLong(2, value)
                    statement.setLong(3, value)
                }
                val other = otherMicros(id)
                if (other == null) statement.setNull(4, Types.BIGINT) else statement.setLong(4, other)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
