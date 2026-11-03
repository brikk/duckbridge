package dev.brikk.duckbridge.trino.plugin

import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.timestampWriteFunction
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.type.TimestampType.TIMESTAMP_MICROS
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC

/** Native JDBC spells timestamp infinities as reserved epoch-microsecond carriers. */
internal object DuckBridgeTimestampColumnMapping {
    private const val MICROS_PER_SECOND = 1_000_000L
    private val minimum = dateTime(Long.MIN_VALUE)
    private val maximum = dateTime(Long.MAX_VALUE - 1)

    fun mapping(): ColumnMapping = ColumnMapping.longMapping(
        TIMESTAMP_MICROS,
        { rows, index -> micros(rows.getObject(index, LocalDateTime::class.java)) },
        timestampWriteFunction(TIMESTAMP_MICROS),
    )

    internal fun micros(value: LocalDateTime): Long {
        if (value < minimum || value > maximum || value.nano % 1000 != 0) throw unsupported()
        val seconds = value.toEpochSecond(UTC)
        val fraction = value.nano / 1000L
        // At Long.MIN_VALUE the floor-second product alone underflows; the complete value fits.
        val micros = if (seconds < 0) (seconds + 1) * MICROS_PER_SECOND + (fraction - MICROS_PER_SECOND)
            else seconds * MICROS_PER_SECOND + fraction
        if (micros == -Long.MAX_VALUE) throw unsupported()
        return micros
    }

    private fun dateTime(micros: Long): LocalDateTime = LocalDateTime.ofEpochSecond(
        Math.floorDiv(micros, MICROS_PER_SECOND), (Math.floorMod(micros, MICROS_PER_SECOND) * 1000).toInt(), UTC,
    )

    private fun unsupported(): TrinoException = TrinoException(NOT_SUPPORTED,
        "DuckBridge TIMESTAMP reader requires finite representable microsecond source values; infinity/invalid carriers are unsupported")
}
