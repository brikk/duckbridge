package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slice
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.StandardFunctions
import io.trino.spi.expression.Variable
import io.trino.spi.type.TimestampWithTimeZoneType
import java.util.Locale

/** Joda time-field rounding keeps the input instant's offset, including across a transition. */
internal object DuckBridgeTimestampTruncation {
    private val SUBDAY_MICROS = mapOf("millisecond" to 1_000L, "second" to 1_000_000L,
        "minute" to 60_000_000L, "hour" to 3_600_000_000L)
    // Both Trino zoned timestamp representations pack milliseconds alongside a 12-bit zone key.
    private const val MIN_MICROS = -2_251_799_813_685_248_000L
    private const val MAX_MICROS = 2_251_799_813_685_247_999L

    fun render(rendered: List<String>, args: List<ConnectorExpression>): String? {
        val value = args[1]
        val unit = ((args[0] as? Constant)?.value as? Slice)?.toStringUtf8()?.lowercase(Locale.ROOT)
        val width = SUBDAY_MICROS[unit]
        if (value.type !is TimestampWithTimeZoneType || width == null) {
            return "date_trunc(${rendered[0]}, ${rendered[1]})"
        }
        if ((value.type as TimestampWithTimeZoneType).precision > 6 || !hasSessionZone(value, allowTruncation = false)) return null
        return guarded(rendered[1], subday(rendered[1], width), packedInput = true)
    }

    fun year(value: String, argument: ConnectorExpression): String? {
        if (argument.type !is TimestampWithTimeZoneType) return "year($value)"
        if (!hasSessionZone(argument, allowTruncation = true)) return null
        val result = "(CASE WHEN era($value)=0 THEN 1-year($value) ELSE year($value) END)"
        // An already-truncated intermediate may be outside packed storage bounds while YEAR is representable.
        return guarded(value, result, packedInput = hasSessionZone(argument, allowTruncation = false))
    }

    /** All zoned values must originate in mapped columns, whose Trino zone is the query session zone. */
    private fun hasSessionZone(expression: ConnectorExpression, allowTruncation: Boolean): Boolean = when (expression) {
        is Variable -> true
        is Constant -> expression.type !is TimestampWithTimeZoneType || expression.value == null
        is Call -> {
            val knownOrigin = expression.type !is TimestampWithTimeZoneType || preservesZone(expression, allowTruncation)
            knownOrigin && expression.arguments.all { hasSessionZone(it, allowTruncation) }
        }
        else -> false
    }

    private fun preservesZone(call: Call, allowTruncation: Boolean): Boolean = when (call.functionName) {
        StandardFunctions.COALESCE_FUNCTION_NAME, StandardFunctions.NULLIF_FUNCTION_NAME -> true
        else -> call.functionName.catalogSchema.isEmpty &&
            (call.functionName.name == "if" || (allowTruncation && call.functionName.name == "date_trunc"))
    }

    private fun subday(value: String, width: Long): String {
        val epoch = "epoch_us($value)"
        val offset = "(date_part('timezone', $value) * 1000000)"
        // Reduce before adding so neither epoch+offset nor floor division can overflow or lose bits.
        val remainder = "(((($epoch % $width + $offset % $width) % $width) + $width) % $width)"
        return "make_timestamptz($epoch - $remainder)"
    }

    private fun guarded(value: String, result: String, packedInput: Boolean): String {
        val condition = if (packedInput) "epoch_us($value) BETWEEN $MIN_MICROS AND $MAX_MICROS" else "isfinite($value)"
        return "(CASE WHEN $condition THEN $result WHEN $value IS NULL THEN NULL " +
            "ELSE error('DuckBridge zoned timestamp requires a finite Trino-representable input') END)"
    }
}
