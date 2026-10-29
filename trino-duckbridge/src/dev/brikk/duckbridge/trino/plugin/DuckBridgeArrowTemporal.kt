package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.block.BlockBuilder
import io.trino.spi.type.TimeType
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.TimeMicroVector
import org.apache.arrow.vector.TimeMilliVector
import org.apache.arrow.vector.TimeNanoVector
import org.apache.arrow.vector.TimeSecVector

internal object DuckBridgeArrowTemporal {
    private val POWERS = longArrayOf(1, 10, 100, 1000, 10000, 100000, 1000000, 10000000,
        100000000, 1000000000, 10000000000, 100000000000, 1000000000000)

    fun requirePrecision(precision: Int, fractionPicos: Long) {
        if (fractionPicos % POWERS[12 - precision] != 0L) {
            throw TrinoException(NOT_SUPPORTED, "Arrow temporal value would lose precision at precision $precision")
        }
    }

    fun appendTime(type: TimeType, vector: FieldVector, index: Int, builder: BlockBuilder) {
        val nanos = when (vector) {
            is TimeNanoVector -> vector.get(index)
            is TimeMicroVector -> Math.multiplyExact(vector.get(index), 1000)
            is TimeMilliVector -> Math.multiplyExact(vector.get(index).toLong(), 1_000_000)
            is TimeSecVector -> Math.multiplyExact(vector.get(index).toLong(), 1_000_000_000)
            else -> throw TrinoException(NOT_SUPPORTED, "Unsupported Arrow TIME vector: ${vector.javaClass.simpleName}")
        }
        if (nanos !in 0 until 86_400_000_000_000L) throw TrinoException(NOT_SUPPORTED, "Arrow TIME is outside Trino's 24-hour range")
        val picos = Math.multiplyExact(nanos, 1000)
        requirePrecision(type.precision, picos)
        type.writeLong(builder, picos)
    }
}
