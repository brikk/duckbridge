package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.handle.ConnectorColumnHandle
import org.apache.doris.connector.spi.pushdown.ConnectorAnd
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.pushdown.ConnectorFunctionCall
import org.apache.doris.connector.spi.pushdown.ConnectorIn
import org.apache.doris.connector.spi.pushdown.ConnectorIsNull
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.apache.doris.connector.spi.pushdown.ConnectorNot
import org.apache.doris.connector.spi.pushdown.ConnectorOr
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Composes the DuckDB SELECT executed by the BE's JDBC scanner.
 * Doris re-filters above the scan, but cannot recover rows incorrectly excluded by a pushed predicate.
 */
internal object DuckBridgeQueryBuilder {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val DATETIME_FMT: DateTimeFormatter =
        DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true)
            .optionalEnd()
            .toFormatter()

    private val NON_SCALAR_TYPE_NAMES = setOf("ARRAY", "MAP", "STRUCT")

    // Naive literals against TIMESTAMPTZ depend on the remote session zone, which we do not control.
    private val DUCKDB_TZ_TIMESTAMP_TYPES = setOf("TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ")

    private val DUCKDB_NAIVE_TEMPORAL_TYPES = setOf(
        "TIMESTAMP", "DATETIME", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS", "DATE",
    )

    /**
     * [columnDuckdbTypes] must include filter columns, not just projections, to establish temporal
     * literal safety. Unknown temporal types stay unpushed to avoid session-zone-dependent comparisons.
     */
    fun buildQuery(
        catalog: String,
        database: String,
        table: String,
        columns: List<ConnectorColumnHandle>,
        filter: ConnectorExpression?,
        limit: Long,
        columnDuckdbTypes: Map<String, String> = emptyMap(),
    ): String {
        val sb = StringBuilder("SELECT ")

        if (columns.isEmpty()) {
            // Preserve row cardinality without materializing unused columns for a count-only scan.
            sb.append('1')
        } else {
            sb.append(
                columns.joinToString(", ") { col ->
                    quoteIdentifier((col as DuckBridgeColumnHandle).columnName)
                },
            )
        }

        sb.append(" FROM ")
            .append(quoteIdentifier(catalog)).append('.')
            .append(quoteIdentifier(database)).append('.')
            .append(quoteIdentifier(table))

        val ctx = RenderContext(columnDuckdbTypes)
        val clauses = ArrayList<String>()
        val allPushed = filter?.let { collectConjuncts(it, clauses, ctx) } ?: true
        if (clauses.isNotEmpty()) {
            sb.append(" WHERE ").append(clauses.joinToString(" AND ") { "($it)" })
        }

        // A remote LIMIT before residual filtering can exclude matching rows.
        if (limit >= 0 && allPushed) {
            sb.append(" LIMIT ").append(limit)
        }

        return sb.toString()
    }

    private class RenderContext(val columnDuckdbTypes: Map<String, String>)

    /**
     * @return true iff every conjunct was pushed (nothing dropped).
     */
    private fun collectConjuncts(
        expr: ConnectorExpression,
        clauses: MutableList<String>,
        ctx: RenderContext,
    ): Boolean {
        if (expr is ConnectorAnd) {
            var all = true
            for (child in expr.conjuncts) {
                if (!collectConjuncts(child, clauses, ctx)) {
                    all = false
                }
            }
            return all
        }
        val sql = render(expr, ctx)
        return if (sql != null) {
            clauses.add(sql)
            true
        } else {
            false
        }
    }

    @Suppress("ReturnCount")
    private fun render(expr: ConnectorExpression, ctx: RenderContext): String? = when (expr) {
        is ConnectorComparison -> renderComparison(expr, ctx)
        is ConnectorIn -> renderIn(expr, ctx)
        is ConnectorIsNull -> renderIsNull(expr)
        is ConnectorAnd -> renderBoolean(expr.conjuncts, "AND", ctx)
        is ConnectorOr -> renderBoolean(expr.disjuncts, "OR", ctx)
        is ConnectorNot -> render(expr.operand, ctx)?.let { "NOT ($it)" }
        is ConnectorColumnRef -> renderColumnRef(expr)
        is ConnectorLiteral -> renderLiteral(expr, null)
        is ConnectorFunctionCall -> renderFunctionCall(expr, ctx)
        else -> null
    }

    private fun renderFunctionCall(fn: ConnectorFunctionCall, ctx: RenderContext): String? {
        val entry = FUNCTION_ALLOWLIST[fn.functionName.lowercase()] ?: return null
        val args = fn.arguments
        if (args.size !in entry.arities) {
            return null
        }
        val rendered = ArrayList<String>(args.size)
        for (arg in args) {
            val sql = render(arg, ctx) ?: return null
            rendered.add(sql)
        }
        return entry.render(rendered, args)
    }

    private fun renderComparison(comp: ConnectorComparison, ctx: RenderContext): String? {
        val symbol = comparisonSymbol(comp.operator) ?: return null
        val leftLit = comp.left is ConnectorLiteral
        val rightLit = comp.right is ConnectorLiteral
        if (leftLit == rightLit) {
            return null
        }
        val colRef = (if (leftLit) comp.right else comp.left) as? ConnectorColumnRef
        val litExpr = (if (leftLit) comp.left else comp.right) as ConnectorLiteral
        val litSql = renderLiteralForColumn(litExpr, colRef, ctx) ?: return null
        val other = render(if (leftLit) comp.right else comp.left, ctx) ?: return null
        return if (leftLit) "$litSql $symbol $other" else "$other $symbol $litSql"
    }

    private fun renderIn(inExpr: ConnectorIn, ctx: RenderContext): String? {
        val value = inExpr.value
        if (value !is ConnectorColumnRef || !isScalar(value)) {
            return null
        }
        val valueSql = renderColumnRef(value) ?: return null
        // A partial IN list could exclude matching rows.
        val items = ArrayList<String>(inExpr.inList.size)
        for (item in inExpr.inList) {
            if (item !is ConnectorLiteral) {
                return null
            }
            val sql = renderLiteralForColumn(item, value, ctx) ?: return null
            items.add(sql)
        }
        if (items.isEmpty()) {
            return null
        }
        val op = if (inExpr.isNegated) "NOT IN" else "IN"
        return "$valueSql $op (${items.joinToString(", ")})"
    }

    private fun renderLiteralForColumn(
        lit: ConnectorLiteral,
        colRef: ConnectorColumnRef?,
        ctx: RenderContext,
    ): String? {
        val isTemporalLit = !lit.isNull && (lit.value is LocalDate || lit.value is LocalDateTime)
        if (!isTemporalLit) {
            return renderLiteral(lit, null)
        }
        val duckdbType = colRef?.let { ctx.columnDuckdbTypes[it.columnName] }?.trim()?.uppercase()
            ?: return null
        return when {
            duckdbType in DUCKDB_TZ_TIMESTAMP_TYPES -> renderLiteral(lit, TemporalClass.TZ)
            duckdbType in DUCKDB_NAIVE_TEMPORAL_TYPES -> renderLiteral(lit, TemporalClass.NAIVE)
            else -> null
        }
    }

    private fun renderIsNull(isNull: ConnectorIsNull): String? {
        val operand = isNull.operand
        if (operand !is ConnectorColumnRef || !isScalar(operand)) {
            return null
        }
        val sql = renderColumnRef(operand) ?: return null
        return sql + if (isNull.isNegated) " IS NOT NULL" else " IS NULL"
    }

    private fun renderBoolean(
        children: List<ConnectorExpression>,
        joiner: String,
        ctx: RenderContext,
    ): String? {
        val parts = ArrayList<String>(children.size)
        for (child in children) {
            // Dropping an OR disjunct would exclude matching rows.
            val sql = render(child, ctx) ?: return null
            parts.add("($sql)")
        }
        return parts.joinToString(" $joiner ")
    }

    private fun renderColumnRef(col: ConnectorColumnRef): String? {
        if (!isScalar(col)) {
            return null
        }
        return quoteIdentifier(col.columnName)
    }

    private enum class TemporalClass { NAIVE, TZ }

    @Suppress("ReturnCount")
    private fun renderLiteral(lit: ConnectorLiteral, temporalClass: TemporalClass?): String? {
        if (lit.isNull) {
            return "NULL"
        }
        return when (val v = lit.value) {
            is String -> quoteString(v)
            is Boolean -> if (v) "TRUE" else "FALSE"
            is Int, is Long, is BigDecimal -> v.toString()
            is Double -> if (v.isFinite()) v.toString() else null // ±Inf/NaN: not a safe literal
            is Float -> if (v.isFinite()) v.toString() else null
            is LocalDate -> renderDate(v, temporalClass)
            is LocalDateTime -> renderDatetime(v, temporalClass)
            else -> null
        }
    }

    private fun renderDate(v: LocalDate, temporalClass: TemporalClass?): String? = when (temporalClass) {
        TemporalClass.NAIVE -> "DATE '" + v.format(DATE_FMT) + "'"
        // A DATE literal against a TIMESTAMPTZ column: DuckDB casts DATE→TIMESTAMPTZ at midnight in
        // the SESSION zone (zone-dependent) → render explicit-UTC midnight.
        TemporalClass.TZ -> "TIMESTAMPTZ '" + v.format(DATE_FMT) + " 00:00:00+00'"
        null -> null
    }

    private fun renderDatetime(v: LocalDateTime, temporalClass: TemporalClass?): String? = when (temporalClass) {
        TemporalClass.NAIVE -> "TIMESTAMP '" + v.format(DATETIME_FMT) + "'"
        TemporalClass.TZ -> "TIMESTAMPTZ '" + v.format(DATETIME_FMT) + "+00'"
        null -> null
    }

    private fun comparisonSymbol(op: ConnectorComparison.Operator): String? = when (op) {
        ConnectorComparison.Operator.EQ -> "="
        ConnectorComparison.Operator.NE -> "<>"
        ConnectorComparison.Operator.LT -> "<"
        ConnectorComparison.Operator.LE -> "<="
        ConnectorComparison.Operator.GT -> ">"
        ConnectorComparison.Operator.GE -> ">="
        // EQ_FOR_NULL is null-safe equality (<=>), not a plain '='.
        else -> null
    }

    private fun isScalar(col: ConnectorColumnRef): Boolean =
        col.type.typeName.uppercase() !in NON_SCALAR_TYPE_NAMES

    private class AllowedFunction(
        val arities: Set<Int>,
        val render: (r: List<String>, a: List<ConnectorExpression>) -> String?,
    )

    /**
     * Float-returning functions are excluded because wire print precision can change equality results.
     */
    private val FUNCTION_ALLOWLIST: Map<String, AllowedFunction> = buildMap {
        put("character_length", AllowedFunction(setOf(1)) { r, _ -> "length(${r[0]})" })
        // Doris length counts bytes; DuckDB length counts code points.
        put("length", AllowedFunction(setOf(1)) { r, _ -> "strlen(${r[0]})" })
        // Doris returns NULL at start 0; DuckDB clamps it to 1.
        put(
            "substring",
            AllowedFunction(setOf(2, 3)) { r, a ->
                if (constantStartIsNonZero(a)) r.joinToString(", ", "substring(", ")") else null
            },
        )
        put("locate", AllowedFunction(setOf(2)) { r, _ -> "strpos(${r[1]}, ${r[0]})" })
        put("instr", AllowedFunction(setOf(2)) { r, _ -> "instr(${r[0]}, ${r[1]})" })
        put("starts_with", AllowedFunction(setOf(2)) { r, _ -> "starts_with(${r[0]}, ${r[1]})" })
        put("abs", AllowedFunction(setOf(1)) { r, _ -> "abs(${r[0]})" })
        for (dateFn in setOf("year", "month", "day", "hour", "minute", "second")) {
            put(dateFn, AllowedFunction(setOf(1)) { r, _ -> "$dateFn(${r[0]})" })
        }
    }

    private fun constantStartIsNonZero(args: List<ConnectorExpression>): Boolean {
        val start = args.getOrNull(1) as? ConnectorLiteral ?: return false
        val v = start.value
        return when (v) {
            is Int -> v != 0
            is Long -> v != 0L
            is BigDecimal -> v.signum() != 0
            else -> false
        }
    }

    private fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * DuckDB's wire protocol cannot round-trip embedded NUL; pushing it risks a truncated literal.
     */
    private fun quoteString(value: String): String? {
        if (value.indexOf('\u0000') >= 0) {
            return null
        }
        return "'" + value.replace("'", "''") + "'"
    }
}
