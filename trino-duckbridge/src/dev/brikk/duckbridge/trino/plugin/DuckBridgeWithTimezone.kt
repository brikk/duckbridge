package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slice
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions
import io.trino.spi.expression.Variable
import io.trino.spi.type.TimeZoneKey
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampWithTimeZoneType

/** Typed, internal instant lowering. No integer expression is exposed as a TIMESTAMPTZ value. */
internal object DuckBridgeWithTimezone {
    private const val DAY = 86_400_000_000L
    private val comparisons = mapOf(
        StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME to "=",
        StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME to "<>",
        StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME to "<",
        StandardFunctions.LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to "<=",
        StandardFunctions.GREATER_THAN_OPERATOR_FUNCTION_NAME to ">",
        StandardFunctions.GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to ">=",
        StandardFunctions.IDENTICAL_OPERATOR_FUNCTION_NAME to "IS NOT DISTINCT FROM",
    )

    private data class InstantSql(
        val micros: String,
        val offset: Long?,
        val sources: Map<String, String>,
        val nullOnly: Boolean = false,
    )

    fun contains(expression: ConnectorExpression): Boolean = expression is Call &&
        (isAttachment(expression) || expression.arguments.any(::contains))

    fun render(call: Call, session: ConnectorSession?, variable: (Variable) -> String?): String? {
        if (!DuckBridgeSessionProperties.isPushdownTimestampWithTimeZone(session)) return null
        if (call.type is TimestampWithTimeZoneType) return null // Only a typed consumer may expose the lowered value.
        val arguments = call.arguments.map { lower(it, session, variable) ?: return null }
        val operator = comparisons[call.functionName]
        val result = when {
            operator != null && arguments.size == 2 -> "(${arguments[0].micros} $operator ${arguments[1].micros})"
            call.functionName == StandardFunctions.IS_NULL_FUNCTION_NAME && arguments.size == 1 -> "(${arguments[0].micros} IS NULL)"
            call.functionName.catalogSchema.isEmpty && arguments.size == 1 -> field(call.functionName.name, arguments[0])
            else -> null
        } ?: return null
        val sources = arguments.flatMap { it.sources.entries }.associate { it.key to it.value }
        return if (sources.isEmpty()) result else "(CASE WHEN ${sources.values.joinToString(" AND ")} THEN $result " +
            "ELSE error('DuckBridge zoned expression requires finite representable source values') END)"
    }

    private fun lower(expression: ConnectorExpression, session: ConnectorSession?, variable: (Variable) -> String?): InstantSql? {
        val type = expression.type as? TimestampWithTimeZoneType ?: return null
        if (type.precision > 6) return null
        return when (expression) {
            is Constant -> if (expression.value == null) InstantSql("CAST(NULL AS HUGEINT)", 0L, emptyMap(), nullOnly = true) else null
            is Variable -> variable(expression)?.let { sql ->
                InstantSql("CAST(epoch_us($sql) AS HUGEINT)", fixedOffset(session?.timeZoneKey?.id),
                    mapOf(sql to "($sql IS NULL OR epoch_us($sql) BETWEEN -2251799813685248000 AND 2251799813685247999)"))
            }
            is Call -> lowerCall(expression, session, variable)
            else -> null
        }
    }

    private fun lowerCall(expression: Call, session: ConnectorSession?, variable: (Variable) -> String?): InstantSql? {
        return when {
            isAttachment(expression) -> attach(expression, variable)
            expression.functionName == StandardFunctions.COALESCE_FUNCTION_NAME && expression.arguments.isNotEmpty() -> {
                val children = expression.arguments.map { lower(it, session, variable) ?: return null }
                val offsets = children.filterNot { it.nullOnly }.map { it.offset }.distinct()
                InstantSql(children.joinToString(", ", "coalesce(", ")") { it.micros },
                    if (offsets.isEmpty()) 0L else offsets.singleOrNull(),
                    children.flatMap { it.sources.entries }.associate { it.key to it.value }, children.all { it.nullOnly })
            }
            expression.functionName == StandardFunctions.NULLIF_FUNCTION_NAME && expression.arguments.size == 2 -> {
                val left = lower(expression.arguments[0], session, variable) ?: return null
                val right = lower(expression.arguments[1], session, variable) ?: return null
                left.copy(micros = "nullif(${left.micros}, ${right.micros})", sources = left.sources + right.sources)
            }
            else -> null
        }
    }

    private fun isAttachment(call: Call): Boolean = call.functionName == FunctionName("with_timezone") && call.arguments.size == 2

    private fun attach(call: Call, variable: (Variable) -> String?): InstantSql? {
        val source = call.arguments[0]
        val type = source.type as? TimestampType ?: return null
        // Short zoned constructors pack immediately and have a different range/error contract.
        if (type.precision !in 4..6 || (call.type as TimestampWithTimeZoneType).precision != type.precision) return null
        val zone = ((call.arguments[1] as? Constant)?.value as? Slice)?.toStringUtf8() ?: return null
        val offset = fixedOffset(zone) ?: return null
        if (source is Constant && source.value == null) return InstantSql("CAST(NULL AS HUGEINT)", offset, emptyMap(), nullOnly = true)
        val sql = (source as? Variable)?.let(variable) ?: return null
        return InstantSql("(CAST(epoch_us($sql) AS HUGEINT) - ($offset))", offset,
            mapOf(sql to "($sql IS NULL OR isfinite($sql))"))
    }

    private fun fixedOffset(id: String?): Long? {
        if (id == null) return null
        val canonical = try { TimeZoneKey.getTimeZoneKey(id).id } catch (@Suppress("TooGenericExceptionCaught") ignored: RuntimeException) { return null }
        if (canonical == "UTC") return 0L
        if (!canonical.matches(Regex("[+-][0-9]{2}:[0-9]{2}"))) return null
        val minutes = canonical.substring(1, 3).toLong() * 60 + canonical.substring(4, 6).toLong()
        return (if (canonical[0] == '-') -minutes else minutes) * 60_000_000L
    }

    private fun field(name: String, instant: InstantSql): String? {
        if (name == "to_unixtime") {
            val millis = floorDivide(instant.micros, 1000)
            val picos = "(${floorMod(instant.micros, 1000)} * 1000000)"
            // Match LongTimestampWithTimeZone's DOUBLE conversion/division/addition order exactly.
            return "(CAST(CAST($millis AS BIGINT) AS DOUBLE) / 1000.0 + CAST(CAST($picos AS BIGINT) AS DOUBLE) / 1000000000000.0)"
        }
        val offset = instant.offset ?: return null
        val local = "(${instant.micros} + ($offset))"
        return when (name) {
            "year", "month", "day", "quarter" -> "$name((DATE '1970-01-01' + CAST(${floorDivide(local, DAY)} AS INTEGER)))"
            "hour" -> integerField(floorMod(local, DAY), 3_600_000_000)
            "minute" -> integerField(floorMod(local, 3_600_000_000), 60_000_000)
            "second" -> integerField(floorMod(local, 60_000_000), 1_000_000)
            "millisecond" -> integerField(floorMod(local, 1_000_000), 1000)
            else -> null
        }
    }

    private fun integerField(value: String, width: Long): String = "CAST(($value // $width) AS BIGINT)"
    private fun floorMod(value: String, width: Long): String = "((($value % $width) + $width) % $width)"
    private fun floorDivide(value: String, width: Long): String = "(($value - ${floorMod(value, width)}) // $width)"
}
