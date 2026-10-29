package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.type.LongTimestamp
import io.trino.spi.type.LongTimestampWithTimeZone
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.TimeType
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.TimeStampNanoVector
import org.apache.arrow.vector.TimeStampNanoTZVector
import org.apache.arrow.vector.TimeNanoVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TestArrowNanosPrecision {
    @Test
    fun testNegativeNanosUseFloorDivisionAndPreserveNulls() {
        RootAllocator().use { allocator ->
            val vector = TimeStampNanoVector("ts", allocator)
            VectorSchemaRoot.of(vector).use { root ->
                root.allocateNew()
                vector.setSafe(0, -1)
                vector.setSafe(1, -1001)
                vector.setNull(2)
                root.rowCount = 3
                val type = TimestampType.createTimestampType(9)
                val block = DuckBridgeArrowToPageConverter(listOf(type)).convert(root).getBlock(0)
                assertThat(type.getObject(block, 0)).isEqualTo(LongTimestamp(-1, 999000))
                assertThat(type.getObject(block, 1)).isEqualTo(LongTimestamp(-2, 999000))
                assertThat(block.isNull(2)).isTrue()
                assertThatThrownBy { DuckBridgeArrowToPageConverter(listOf(TimestampType.createTimestampType(6))).convert(root) }
                    .hasMessageContaining("lose precision")
                vector.setSafe(0, Long.MAX_VALUE)
                assertThatThrownBy { DuckBridgeArrowToPageConverter(listOf(type)).convert(root) }.hasMessageContaining("Infinite")
            }
            assertThat(allocator.allocatedMemory).isZero()
        }
    }

    @Test
    fun testZonedNanosAndPrecisionGuards() {
        RootAllocator().use { allocator ->
            val vector = TimeStampNanoTZVector("z", allocator, "UTC")
            VectorSchemaRoot.of(vector).use { root ->
                root.allocateNew()
                vector.setSafe(0, -1)
                root.rowCount = 1
                val type = TimestampWithTimeZoneType.createTimestampWithTimeZoneType(9)
                val block = DuckBridgeArrowToPageConverter(listOf(type)).convert(root).getBlock(0)
                val value = type.getObject(block, 0) as LongTimestampWithTimeZone
                assertThat(value.epochMillis).isEqualTo(-1)
                assertThat(value.picosOfMilli).isEqualTo(999999000)
                assertThatThrownBy { DuckBridgeArrowToPageConverter(listOf(TimestampWithTimeZoneType.createTimestampWithTimeZoneType(6))).convert(root) }
                    .hasMessageContaining("lose precision")
            }
        }
    }

    @Test
    fun testTimeNanosCannotWrapAtMidnightOrLoseDigits() {
        RootAllocator().use { allocator ->
            val vector = TimeNanoVector("clock", allocator)
            VectorSchemaRoot.of(vector).use { root ->
                root.allocateNew()
                vector.setSafe(0, 86_399_999_999_999L)
                root.rowCount = 1
                val type = TimeType.createTimeType(9)
                val block = DuckBridgeArrowToPageConverter(listOf(type)).convert(root).getBlock(0)
                assertThat(type.getLong(block, 0)).isEqualTo(86_399_999_999_999_000L)
                assertThatThrownBy { DuckBridgeArrowToPageConverter(listOf(TimeType.createTimeType(6))).convert(root) }
                    .hasMessageContaining("lose precision")
                vector.setSafe(0, 86_400_000_000_000L)
                assertThatThrownBy { DuckBridgeArrowToPageConverter(listOf(type)).convert(root) }.hasMessageContaining("24-hour")
            }
        }
    }
}
