package dev.brikk.duckbridge.trino.plugin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC

class TestTimestampColumnMapping {
    @Test
    fun testExactFiniteMicroseconds() {
        for (micros in WithTimezoneCases.wideEpochs() + listOf(-1L, 0L, 1L, -1_000_001L, 1_000_001L)) {
            assertThat(DuckBridgeTimestampColumnMapping.micros(dateTime(micros))).isEqualTo(micros)
        }
    }

    @Test
    fun testRejectReservedAndLossyCarriers() {
        for (value in listOf(dateTime(Long.MAX_VALUE), dateTime(-Long.MAX_VALUE), LocalDateTime.MIN,
            LocalDateTime.MAX, dateTime(0).plusNanos(1))) {
            assertThatThrownBy { DuckBridgeTimestampColumnMapping.micros(value) }.hasMessageContaining("finite representable")
        }
    }

    private fun dateTime(micros: Long): LocalDateTime = LocalDateTime.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000, UTC,
    )
}
