package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.type.LongTimestamp
import io.trino.spi.type.TimeType
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.Type
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** Exact conversions; neither DuckDB infinities nor Trino picoseconds may become finite nanos. */
internal object DuckBridgeNanos {
    fun timestampCarrier(raw: Any?): LongTimestamp = timestamp(when (raw) {
        is LocalDateTime -> raw
        else -> throw unsupported("TIMESTAMP_NS array needs a LocalDateTime carrier, not legacy Timestamp: ${raw?.javaClass?.name}")
    })

    fun timestamp(value: LocalDateTime): LongTimestamp {
        val seconds = value.toEpochSecond(ZoneOffset.UTC)
        val nanos = if (seconds < 0) Math.addExact(Math.multiplyExact(seconds + 1, 1_000_000_000L), value.nano - 1_000_000_000L)
            else Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), value.nano.toLong())
        requireFinite(nanos)
        return LongTimestamp(Math.floorDiv(nanos, 1000), Math.floorMod(nanos, 1000) * 1000)
    }

    fun localTimestamp(value: LongTimestamp): LocalDateTime {
        if (value.picosOfMicro % 1000 != 0) throw unsupported("Sub-nanosecond timestamp cannot be stored in DuckDB")
        val nanos = if (value.epochMicros < 0) Math.addExact(Math.multiplyExact(value.epochMicros + 1, 1000), value.picosOfMicro / 1000L - 1000)
            else Math.addExact(Math.multiplyExact(value.epochMicros, 1000), value.picosOfMicro / 1000L)
        requireFinite(nanos)
        return LocalDateTime.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000), Math.floorMod(nanos, 1_000_000_000), ZoneOffset.UTC)
    }

    fun time(value: LocalTime): Long = Math.multiplyExact(value.toNanoOfDay(), 1000)

    fun localTime(picos: Long): LocalTime {
        if (picos % 1000 != 0L) throw unsupported("Sub-nanosecond time cannot be stored in DuckDB")
        return LocalTime.ofNanoOfDay(picos / 1000)
    }

    fun requireFinite(nanos: Long) {
        // DuckDB timestamp_t reserves MAX for +infinity, MIN+1 for -infinity, MIN as invalid.
        if (nanos == Long.MAX_VALUE || nanos <= Long.MIN_VALUE + 1) throw unsupported("Infinite/invalid nanosecond timestamp is not representable in Trino")
    }

    fun isHighPrecision(type: Type): Boolean = when (type) {
        is TimestampType -> type.precision > 6
        is TimestampWithTimeZoneType -> type.precision > 6
        is TimeType -> type.precision > 6
        else -> false
    }

    private fun unsupported(message: String) = TrinoException(NOT_SUPPORTED, message)
}
