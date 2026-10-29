package dev.brikk.duckbridge.trino.plugin

import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.LongWriteFunction
import io.trino.plugin.jdbc.ObjectReadFunction
import io.trino.plugin.jdbc.ObjectWriteFunction
import io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN
import io.trino.plugin.jdbc.WriteMapping
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.type.LongTimestamp
import io.trino.spi.type.LongTimestampWithTimeZone
import io.trino.spi.type.TimeType
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.Type
import io.trino.spi.type.TimeZoneKey
import java.sql.PreparedStatement
import java.time.Instant
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal object DuckBridgeNanosColumnMappings {
    private val TIMESTAMP = TimestampType.createTimestampType(9)
    private val TIMESTAMP_TZ = TimestampWithTimeZoneType.createTimestampWithTimeZoneType(9)
    private val TIME = TimeType.createTimeType(9)

    fun byTypeName(name: String?, zone: TimeZoneKey): ColumnMapping? = when (name?.uppercase()) {
        "TIMESTAMP_NS" -> timestamp()
        "TIMESTAMPTZ_NS" -> zonedTimestamp(zone)
        "TIME_NS" -> time()
        else -> null
    }

    fun timestamp(): ColumnMapping = ColumnMapping.objectMapping(TIMESTAMP,
        ObjectReadFunction.of(LongTimestamp::class.java) { rows, index ->
            // Legacy java.sql.Timestamp normalizes DST-gap wall clocks in the JVM default zone.
            DuckBridgeNanos.timestamp(rows.getObject(index, LocalDateTime::class.java))
        }, timestampWriter(), DISABLE_PUSHDOWN)

    fun zonedTimestamp(zone: TimeZoneKey): ColumnMapping = ColumnMapping.objectMapping(TIMESTAMP_TZ,
        ObjectReadFunction.of(LongTimestampWithTimeZone::class.java) { rows, index ->
            val value = rows.getObject(index, OffsetDateTime::class.java)
            LongTimestampWithTimeZone.fromEpochMillisAndFraction(value.toInstant().toEpochMilli(), (value.nano % 1_000_000) * 1000, zone)
        },
        zonedWriter(), DISABLE_PUSHDOWN)

    fun time(): ColumnMapping = ColumnMapping.longMapping(TIME,
        { rows, index -> DuckBridgeNanos.time(rows.getObject(index) as LocalTime) }, timeWriter(), DISABLE_PUSHDOWN)

    fun write(type: Type): WriteMapping? = when {
        type is TimestampType && type.precision in 7..9 -> WriteMapping.objectMapping("TIMESTAMP_NS", timestampWriter())
        type is TimestampWithTimeZoneType && type.precision in 7..9 -> WriteMapping.objectMapping("TIMESTAMPTZ_NS", zonedWriter())
        type is TimeType && type.precision in 7..9 -> WriteMapping.longMapping("TIME_NS", timeWriter())
        else -> null
    }

    private fun timestampWriter(): ObjectWriteFunction = object : ObjectWriteFunction {
        override fun getJavaType(): Class<*> = LongTimestamp::class.java
        override fun getBindExpression(): String = "CAST(? AS TIMESTAMP_NS)"
        override fun set(statement: PreparedStatement, index: Int, value: Any) {
            statement.setString(index, DuckBridgeNanos.localTimestamp(value as LongTimestamp).toString())
        }
    }

    private fun zonedWriter(): ObjectWriteFunction = object : ObjectWriteFunction {
        override fun getJavaType(): Class<*> = LongTimestampWithTimeZone::class.java
        override fun getBindExpression(): String = "CAST(? AS TIMESTAMPTZ_NS)"
        override fun set(statement: PreparedStatement, index: Int, value: Any) {
            val timestamp = value as LongTimestampWithTimeZone
            if (timestamp.picosOfMilli % 1000 != 0) throw TrinoException(NOT_SUPPORTED, "Sub-nanosecond zoned timestamp cannot be stored in DuckDB")
            val instant = Instant.ofEpochMilli(timestamp.epochMillis).plusNanos(timestamp.picosOfMilli / 1000L)
            statement.setString(index, instant.atOffset(ZoneOffset.UTC).toString())
        }
    }

    private fun timeWriter(): LongWriteFunction = object : LongWriteFunction {
        override fun getBindExpression(): String = "CAST(? AS TIME_NS)"
        override fun set(statement: PreparedStatement, index: Int, value: Long) {
            statement.setString(index, DuckBridgeNanos.localTime(value).toString())
        }
    }
}
