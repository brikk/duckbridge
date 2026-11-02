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
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.TimestampType.TIMESTAMP_MICROS
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Types
import java.util.Optional

class TestTimestampTruncationRendering {
    private val z = Variable("z", TIMESTAMP_TZ_MICROS)
    private val other = Variable("other", TIMESTAMP_TZ_MICROS)
    private val wall = Variable("wall", TIMESTAMP_MICROS)
    private val flag = Variable("flag", BOOLEAN)
    private val assignments = listOf(z, other, wall, flag).associate { variable ->
        variable.name to JdbcColumnHandle(variable.name, JdbcTypeHandle(Types.OTHER, Optional.of(variable.type.displayName),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), variable.type)
    }

    @Test
    fun testKnownSessionZoneCompositionsRemainPushableWithoutParityOrStringTrust() {
        val expressions = listOf(z,
            Call(TIMESTAMP_TZ_MICROS, StandardFunctions.COALESCE_FUNCTION_NAME, listOf(z, other)),
            Call(TIMESTAMP_TZ_MICROS, StandardFunctions.NULLIF_FUNCTION_NAME, listOf(z, other)),
            Call(TIMESTAMP_TZ_MICROS, FunctionName("if"), listOf(flag, z, other)))
        for (value in expressions) for (unit in TimestampTruncationCases.units) {
            for (aliases in listOf(false, true)) for (strings in listOf(false, true)) {
                val sql = DuckBridgeExpressionTranslator.translate(trunc(unit, value), assignments, session(true), aliases, strings)
                assertThat(sql).contains("make_timestamptz", "date_part('timezone'", "epoch_us", "error(")
            }
        }
    }

    @Test
    fun testUnknownZoneConstructorsAndNestedTruncationsRemainLocal() {
        val attached = Call(TIMESTAMP_TZ_MICROS, FunctionName("with_timezone"), listOf(wall, text("UTC")))
        val fromEpoch = Call(TIMESTAMP_TZ_MICROS, FunctionName("from_unixtime"), listOf(Constant(0.0, io.trino.spi.type.DoubleType.DOUBLE)))
        for (value in listOf(attached, fromEpoch, trunc("minute", z))) {
            assertThat(DuckBridgeExpressionTranslator.translate(trunc("hour", value), assignments, session(true))).isNull()
        }
        val year = Call(io.trino.spi.type.BigintType.BIGINT, FunctionName("year"), listOf(trunc("hour", z)))
        assertThat(DuckBridgeExpressionTranslator.translate(year, assignments, session(true))).contains("era(", "make_timestamptz")
        val unknownYear = Call(io.trino.spi.type.BigintType.BIGINT, FunctionName("year"), listOf(attached))
        assertThat(DuckBridgeExpressionTranslator.translate(unknownYear, assignments, session(true))).isNull()
    }

    @Test
    fun testExistingPrecisionUnitAndSessionGatesStillApply() {
        assertThat(DuckBridgeExpressionTranslator.translate(trunc("hour", z), assignments, session(false))).isNull()
        assertThat(DuckBridgeExpressionTranslator.translate(trunc("hour", z), assignments, null)).isNull()
        for (unit in listOf("century", "hours", "", "microsecond")) {
            assertThat(DuckBridgeExpressionTranslator.translate(trunc(unit, z), assignments, session(true))).isNull()
        }
        val high = Variable("z", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(9))
        assertThat(DuckBridgeExpressionTranslator.translate(trunc("hour", high), assignments, session(true))).isNull()
        assertThat(DuckBridgeExpressionTranslator.translate(trunc("HOUR", z), assignments, session(true))).contains("3600000000")
        assertThat(DuckBridgeExpressionTranslator.translate(trunc("day", z), assignments, session(true))).isEqualTo("date_trunc('day', \"z\")")
        assertThat(DuckBridgeExpressionTranslator.translate(trunc("hour", wall), assignments, session(true))).isEqualTo("date_trunc('hour', \"wall\")")
    }

    private fun trunc(unit: String, value: ConnectorExpression): Call = Call(value.type, FunctionName("date_trunc"), listOf(text(unit), value))
    private fun text(value: String): Constant = Constant(utf8Slice(value), VARCHAR)
    private fun session(enabled: Boolean) = TestingConnectorSession.builder()
        .setPropertyMetadata(DuckBridgeSessionProperties(DuckBridgeConfig()).getSessionProperties())
        .setPropertyValues(mapOf(DuckBridgeSessionProperties.PUSHDOWN_TIMESTAMP_WITH_TIME_ZONE to enabled)).build()
}
