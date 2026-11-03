package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slices.utf8Slice
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampType.TIMESTAMP_MICROS
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Types
import java.util.Optional

class TestWithTimezoneRendering {
    private val wall = Variable("wall", TIMESTAMP_MICROS)
    private val z = Variable("z", TIMESTAMP_TZ_MICROS)
    private val zone = Variable("zone", VARCHAR)
    private val assignments = listOf(wall, z, zone).associate { variable ->
        variable.name to JdbcColumnHandle(variable.name, JdbcTypeHandle(Types.OTHER, Optional.of(variable.type.displayName),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), variable.type)
    }

    @Test
    fun testFixedOffsetsOnlyThroughTypedConsumersWithoutTrustDependencies() {
        for (id in listOf("UTC", "Z", "Etc/UTC", "GMT+5:45", "+05:45", "-14:00", "Etc/GMT+5")) {
            val value = attached(id)
            assertThat(DuckBridgeExpressionTranslator.translate(value, assignments, session())).isNull()
            for (aliases in listOf(false, true)) for (strings in listOf(false, true)) {
                val expression = Call(BOOLEAN, StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME, listOf(value, z))
                val sql = DuckBridgeExpressionTranslator.translate(expression, assignments, session(), aliases, strings)
                assertThat(sql).contains("HUGEINT", "epoch_us(\"wall\")", "error(").doesNotContain("make_timestamptz", "timezone(")
                val year = Call(BIGINT, FunctionName("year"), listOf(value))
                assertThat(DuckBridgeExpressionTranslator.translate(year, assignments, session(), aliases, strings))
                    .contains("DATE '1970-01-01'", "HUGEINT").doesNotContain("AS TIMESTAMP")
            }
        }
    }

    @Test
    fun testNamedDynamicInvalidAndUnqualifiedConsumersStayLocal() {
        for (id in listOf("America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe", "Not/AZone", "", "+14:01")) {
            val expression = Call(BIGINT, FunctionName("hour"), listOf(attached(id)))
            assertThat(DuckBridgeExpressionTranslator.translate(expression, assignments, session())).isNull()
        }
        for (value in listOf(Call(TIMESTAMP_TZ_MICROS, FunctionName("with_timezone"), listOf(wall, zone)), attached("UTC"))) {
            val trunc = Call(TIMESTAMP_TZ_MICROS, FunctionName("date_trunc"), listOf(text("hour"), value))
            assertThat(DuckBridgeExpressionTranslator.translate(trunc, assignments, session())).isNull()
            val cast = Call(io.trino.spi.type.DateType.DATE, StandardFunctions.CAST_FUNCTION_NAME, listOf(value))
            assertThat(DuckBridgeExpressionTranslator.translate(cast, assignments, session())).isNull()
        }
    }

    @Test
    fun testPrecisionSessionAndMixedProvenanceBoundaries() {
        for (precision in listOf(3, 9)) {
            val source = Variable("wall", TimestampType.createTimestampType(precision))
            val value = Call(TimestampWithTimeZoneType.createTimestampWithTimeZoneType(precision), FunctionName("with_timezone"), listOf(source, text("UTC")))
            assertThat(DuckBridgeExpressionTranslator.translate(Call(BIGINT, FunctionName("hour"), listOf(value)), assignments, session())).isNull()
        }
        val hour = Call(BIGINT, FunctionName("hour"), listOf(attached("UTC")))
        assertThat(DuckBridgeExpressionTranslator.translate(hour, assignments, null)).isNull()
        assertThat(DuckBridgeExpressionTranslator.translate(hour, assignments, session(false))).isNull()
        val mixed = Call(TIMESTAMP_TZ_MICROS, StandardFunctions.COALESCE_FUNCTION_NAME, listOf(attached("UTC"), attached("+01:00")))
        assertThat(DuckBridgeExpressionTranslator.translate(Call(BIGINT, FunctionName("hour"), listOf(mixed)), assignments, session())).isNull()
        val comparison = Call(BOOLEAN, StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME, listOf(mixed, z))
        assertThat(DuckBridgeExpressionTranslator.translate(comparison, assignments, session())).contains("coalesce(", "HUGEINT")
        val nullif = Call(TIMESTAMP_TZ_MICROS, StandardFunctions.NULLIF_FUNCTION_NAME, listOf(attached("UTC"), z))
        assertThat(DuckBridgeExpressionTranslator.translate(Call(BIGINT, FunctionName("hour"), listOf(nullif)), assignments, session())).contains("nullif(")
    }

    private fun attached(id: String) = Call(TIMESTAMP_TZ_MICROS, FunctionName("with_timezone"), listOf(wall, text(id)))
    private fun text(value: String) = Constant(utf8Slice(value), VARCHAR)
    private fun session(enabled: Boolean = true) = TestingConnectorSession.builder()
        .setPropertyMetadata(DuckBridgeSessionProperties(DuckBridgeConfig()).getSessionProperties())
        .setPropertyValues(mapOf(DuckBridgeSessionProperties.PUSHDOWN_TIMESTAMP_WITH_TIME_ZONE to enabled)).build()
}
