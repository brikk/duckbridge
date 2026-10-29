package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.type.TimeZoneKey.UTC_KEY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TestDuckDb2TemporalGuards {
    @Test
    fun testNewNanosNamesCannotUseGenericMicrosecondMappings() {
        for (type in listOf("TIMESTAMP_NS", "TIMESTAMPTZ_NS", "TIME_NS", "timestamp_ns", "time_ns")) {
            assertThat(DuckBridgeScalarColumnMappings.isUnqualifiedTemporal(type)).describedAs(type).isTrue()
            assertThat(DuckBridgeScalarColumnMappings.byTypeName(type, UTC_KEY)).describedAs(type).isNull()
        }
    }

    @Test
    fun testQualifiedSecondMillisecondAndMicrosecondTypesAreNotBlocked() {
        for (type in listOf("TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP", "TIMESTAMPTZ", "TIME", "TIMETZ")) {
            assertThat(DuckBridgeScalarColumnMappings.isUnqualifiedTemporal(type)).describedAs(type).isFalse()
        }
    }
}
