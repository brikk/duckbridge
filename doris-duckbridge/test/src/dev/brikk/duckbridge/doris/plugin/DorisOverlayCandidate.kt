package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.pushdown.ConnectorFunctionCall
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral

/** Test-only prototype; native JNI qualification is blocked. Never a production allowlist entry. */
internal object DorisOverlayCandidate {
    private val STRING_TYPES = setOf("STRING", "VARCHAR")
    private val INTEGER_TYPES = setOf("TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT")

    fun render(
        call: ConnectorFunctionCall,
        columnDuckdbTypes: Map<String, String>,
        renderArgument: (ConnectorExpression) -> String?,
    ): String? {
        val args = call.arguments
        if (args.size != 4 || call.returnType.typeName.uppercase() !in STRING_TYPES) return null
        if (!isVarchar(args[0], columnDuckdbTypes) || !isVarchar(args[3], columnDuckdbTypes)) return null
        val start = integerConstant(args[1]) ?: return null
        val count = integerConstant(args[2]) ?: return null
        // Doris evaluates (start - 1 + count) in int32. Never depend on overflow behavior.
        if (start < 1 || count < 0 || start - 1 + count > Int.MAX_VALUE) return null
        val sql = args.map { renderArgument(it) ?: return null }
        val source = sql[0]
        val replacement = sql[3]
        // Doris is NULL-strict even when the position is outside the source, where it returns the
        // unchanged source. DuckDB instead appends. Both engines index valid UTF-8 by code point.
        return "CASE WHEN $source IS NULL OR $replacement IS NULL THEN NULL " +
            "WHEN length($source) < ${sql[1]} THEN $source " +
            "ELSE overlay($source PLACING $replacement FROM ${sql[1]} FOR ${sql[2]}) END"
    }

    private fun isVarchar(expression: ConnectorExpression, types: Map<String, String>): Boolean =
        when (expression) {
            is ConnectorColumnRef -> expression.type.typeName.uppercase() in STRING_TYPES &&
                types[expression.columnName]?.trim()?.uppercase() == "VARCHAR"
            is ConnectorLiteral -> expression.type.typeName.uppercase() in STRING_TYPES &&
                (expression.isNull || expression.value is String)
            else -> false // no casts, nested calls, JSON/nested-value text, CHAR padding or unknown types
        }

    private fun integerConstant(expression: ConnectorExpression): Long? {
        val literal = expression as? ConnectorLiteral ?: return null
        if (literal.isNull || literal.type.typeName.uppercase() !in INTEGER_TYPES) return null
        val value = when (val raw = literal.value) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> return null
        }
        return value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
    }
}
