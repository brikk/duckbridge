package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.handle.ConnectorColumnHandle
import org.apache.doris.connector.api.pushdown.ConnectorAnd
import org.apache.doris.connector.api.pushdown.ConnectorColumnRef
import org.apache.doris.connector.api.pushdown.ConnectorComparison
import org.apache.doris.connector.api.pushdown.ConnectorExpression
import org.apache.doris.connector.api.pushdown.ConnectorFunctionCall
import org.apache.doris.connector.api.pushdown.ConnectorIn
import org.apache.doris.connector.api.pushdown.ConnectorIsNull
import org.apache.doris.connector.api.pushdown.ConnectorLiteral
import org.apache.doris.connector.api.pushdown.ConnectorNot
import org.apache.doris.connector.api.pushdown.ConnectorOr
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Composes the DuckDB `SELECT` that the BE's `JdbcJniScanner` runs over quack-jdbc.
 *
 * Scope = the **domain floor** (comparisons `= <> < <= > >=`, `IN`/`NOT IN`, `IS [NOT] NULL`, boolean
 * `AND`/`OR`/`NOT`) **plus the P1-audited IDENTICAL scalar functions** — an explicit allowlist
 * ([FUNCTION_ALLOWLIST]) of `ConnectorFunctionCall` shapes the Doris↔DuckDB divergence audit proved
 * byte-exact (`dev-docs/REPORT-doris-duckdb-function-divergence.md`). Everything else
 * (`LIKE`/`REGEXP`/`BETWEEN`/arithmetic, and every UN-audited or DIVERGENT function) is DROPPED and
 * re-evaluated by Doris above the scan.
 *
 * The allowlist admits **nothing** the audit didn't prove, and bakes in the audit's cross-name
 * mappings (Doris `character_length`→DuckDB `length`; Doris `length`(bytes)→DuckDB `strlen`; Doris
 * `locate(needle,hay)`→DuckDB `strpos(hay,needle)`) and guards (`substring` only with a constant
 * start ≠ 0). Float-**returning** functions are excluded even when audited-identical: a float result
 * inside `WHERE f(x)=<literal>` is print-precision-fragile across the wire.
 *
 * Correctness contract (see NOTES-p5-p2-scan.md): the FE keeps every conjunct for BE-side
 * re-evaluation (we do not implement `applyFilter`), so a DROPPED conjunct is safe — Doris
 * re-filters. But a PUSHED conjunct must be exactly equivalent: if our WHERE wrongly excludes a
 * matching row, the BE re-filter cannot resurrect it. So we only render predicates we can prove
 * faithful, and we **refuse** anything we cannot escape safely — notably a string/identifier
 * containing U+0000 (NUL), which DuckDB's protocol cannot round-trip; such a conjunct is dropped
 * (evaluated above), never silently mangled.
 *
 * Identifiers are double-quoted with `"` → `""` escaping (DuckDB); string literals single-quoted
 * with `'` → `''` escaping. Only pushed onto SCALAR columns (never ARRAY/STRUCT/MAP/nested types).
 *
 * **Timezone safety (probe P3/P6, `dev-docs/REPORT-doris-timezone-probe.md`).** A datetime literal
 * compared against a column is rendered by the column's DuckDB type: a naive `TIMESTAMP`/`DATE`
 * column gets a naive literal (wall-clock, zone-independent — proven), a `TIMESTAMPTZ` column gets
 * an explicit-UTC literal (`TIMESTAMPTZ '…+00'` — proven zone-independent; P4 reads the UTC instant
 * as the wall-clock, so the Doris literal IS a UTC instant). A temporal comparison whose column's
 * DuckDB type is UNKNOWN **drops** — we never emit the zone-dependent naive-literal-vs-TIMESTAMPTZ
 * form (which compares in the DuckDB session zone we don't control). This needs the caller to pass
 * `columnDuckdbTypes` for every filter-referenced column.
 */
internal object DuckBridgeQueryBuilder {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // Datetime with optional fractional seconds up to micros (DuckDB TIMESTAMP resolution).
    private val DATETIME_FMT: DateTimeFormatter =
        DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true)
            .optionalEnd()
            .toFormatter()

    /** Doris type names (ConnectorType.getTypeName) that are NOT scalar — never push a predicate on these. */
    private val NON_SCALAR_TYPE_NAMES = setOf("ARRAY", "MAP", "STRUCT")

    // DuckDB timezone-aware timestamp type names (as quack-jdbc getColumns().TYPE_NAME reports them).
    // A comparison on such a column MUST render its literal explicit-UTC (probe P3/P6) — a naive
    // literal is compared in the DuckDB session zone, which we don't control (silently wrong).
    private val DUCKDB_TZ_TIMESTAMP_TYPES = setOf("TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ")

    // DuckDB naive-temporal type names: comparison is wall-clock-to-wall-clock, zone-independent
    // (probe P3/P6), so a naive literal is safe.
    private val DUCKDB_NAIVE_TEMPORAL_TYPES = setOf(
        "TIMESTAMP", "DATETIME", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS", "DATE",
    )

    /**
     * Build the DuckDB SELECT.
     *
     * @param catalog DuckDB catalog (main catalog from P4).
     * @param database DuckDB schema (Doris database).
     * @param table table name.
     * @param columns projected columns (empty ⇒ SELECT *), in output order.
     * @param filter optional pushed-down predicate tree.
     * @param limit row limit, or < 0 for none.
     * @param columnDuckdbTypes columnName → raw DuckDB `TYPE_NAME` for EVERY resolvable column (not
     *   just projected ones), so a temporal predicate can be rendered zone-safely: a naive
     *   TIMESTAMP/DATE column pushes with a naive literal (zone-independent), a TIMESTAMPTZ column
     *   pushes with an explicit-UTC literal (option 2, proven zone-independent in the P3 probe), and
     *   a temporal column whose DuckDB type is UNKNOWN drops (fail-safe — never the zone-dependent
     *   naive-literal-vs-TIMESTAMPTZ form). Empty map ⇒ every temporal comparison drops.
     * @return the SQL string for `query_sql`.
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
            sb.append('*')
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

        // Collect the pushable conjuncts (top-level AND flattened). A conjunct that can't be
        // rendered faithfully is dropped (FE re-filters); we track whether ANY was dropped so
        // LIMIT is only pushed when the whole filter went down.
        val ctx = RenderContext(columnDuckdbTypes)
        val clauses = ArrayList<String>()
        val allPushed = if (filter == null) {
            true
        } else {
            collectConjuncts(filter, clauses, ctx)
        }
        if (clauses.isNotEmpty()) {
            sb.append(" WHERE ").append(clauses.joinToString(" AND ") { "($it)" })
        }

        // LIMIT only when nothing was dropped — else a remote LIMIT truncates before the FE's
        // above-scan re-filter runs, under-returning (mirrors JdbcQueryBuilder.shouldPushDownLimit).
        if (limit >= 0 && allPushed) {
            sb.append(" LIMIT ").append(limit)
        }

        return sb.toString()
    }

    /**
     * Per-`buildQuery` render state: the column→DuckDB-type map for zone-safe temporal rendering.
     * Passed down the render tree so a comparison against a TIMESTAMPTZ column can render its
     * literal explicit-UTC.
     */
    private class RenderContext(val columnDuckdbTypes: Map<String, String>)

    /**
     * Flatten top-level AND and render each conjunct; a conjunct that can't be rendered is dropped.
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

    /** Render a domain-floor expression to DuckDB SQL, or null if not pushable/renderable. */
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
        // Everything else (ConnectorLike, ConnectorBetween, …) is out of scope — drop it
        // (Doris evaluates above the scan).
        else -> null
    }

    /**
     * Render an audited-IDENTICAL scalar function per [FUNCTION_ALLOWLIST], or null (drop) for any
     * function not in the allowlist or whose arity/guards don't match. The allowlist is the P1
     * audit's authority — it admits nothing the audit didn't prove byte-exact.
     */
    private fun renderFunctionCall(fn: ConnectorFunctionCall, ctx: RenderContext): String? {
        val entry = FUNCTION_ALLOWLIST[fn.functionName.lowercase()] ?: return null
        val args = fn.arguments
        if (args.size !in entry.arities) {
            return null
        }
        val rendered = ArrayList<String>(args.size)
        for (arg in args) {
            val sql = render(arg, ctx) ?: return null // any un-renderable argument drops the whole call
            rendered.add(sql)
        }
        return entry.render(rendered, args)
    }

    private fun renderComparison(comp: ConnectorComparison, ctx: RenderContext): String? {
        val symbol = comparisonSymbol(comp.operator) ?: return null
        // Guard: exactly one side is a literal and the other is a renderable non-literal (a scalar
        // column ref, or an allowlisted function call over one). This is the pushable shape:
        // `<expr> <op> <literal>`. Column-column / literal-literal comparisons are dropped.
        val leftLit = comp.left is ConnectorLiteral
        val rightLit = comp.right is ConnectorLiteral
        if (leftLit == rightLit) {
            return null // both literals or neither literal → drop
        }
        // Temporal zone-safety (probe P3/P6): the non-literal side may be a column whose DuckDB type
        // is TIMESTAMPTZ — then the literal MUST render explicit-UTC (a naive literal would be
        // compared in the DuckDB session zone → zone-dependent → silently wrong). Determine the
        // temporal-safety class from the non-literal COLUMN operand (a function over a column is not
        // pushed for temporal literals — the audited fns are int/string-returning, so a datetime
        // literal never pairs with one; still, we key strictly off a direct column ref).
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
        // Every element must be a renderable literal; else drop the whole IN (partial is unsafe).
        // Temporal safety applies per-element against the IN column.
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

    /**
     * Render a literal in the zone-correct form for the column it's compared against (probe P3/P6).
     * For a NON-temporal literal, or a comparison not against a resolvable column, defers to
     * [renderLiteral] with no temporal class. For a temporal literal:
     *  - column is naive DuckDB TIMESTAMP/DATE → naive literal (wall-clock, zone-independent).
     *  - column is DuckDB TIMESTAMPTZ → explicit-UTC literal (`TIMESTAMPTZ '…+00'`, proven safe).
     *  - column's DuckDB type is UNKNOWN (not in the map) → **drop** (return null) — never emit the
     *    zone-dependent naive-literal-vs-TIMESTAMPTZ form. Fail-safe; the FE re-filters.
     */
    private fun renderLiteralForColumn(
        lit: ConnectorLiteral,
        colRef: ConnectorColumnRef?,
        ctx: RenderContext,
    ): String? {
        val isTemporalLit = !lit.isNull && (lit.value is LocalDate || lit.value is LocalDateTime)
        if (!isTemporalLit) {
            return renderLiteral(lit, null) // non-temporal literals are zone-agnostic
        }
        // A temporal literal must be paired with a known temporal column to render zone-safely.
        val duckdbType = colRef?.let { ctx.columnDuckdbTypes[it.columnName] }?.trim()?.uppercase()
            ?: return null // no resolvable column type → can't prove zone-safe → drop
        return when {
            duckdbType in DUCKDB_TZ_TIMESTAMP_TYPES -> renderLiteral(lit, TemporalClass.TZ)
            duckdbType in DUCKDB_NAIVE_TEMPORAL_TYPES -> renderLiteral(lit, TemporalClass.NAIVE)
            else -> null // an unrecognized temporal DuckDB type → drop (fail-safe)
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
            // For a boolean combinator, ALL children must render — a dropped child would change
            // the predicate's meaning (an OR with a missing disjunct under-returns). Drop the whole.
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

    /** Zone-safety class of a temporal literal, decided by the column it's compared against. */
    private enum class TemporalClass { NAIVE, TZ }

    /**
     * DuckDB literal rendering from the typed Java value; null if not safely renderable (e.g. NUL).
     * [temporalClass] governs a date/datetime literal (probe P3/P6): NAIVE → `TIMESTAMP`/`DATE`
     * (wall-clock, for a naive DuckDB column); TZ → `TIMESTAMPTZ '…+00'` (explicit UTC instant, for
     * a DuckDB TIMESTAMPTZ column — P4 reads its UTC instant as the wall-clock, so the Doris literal
     * IS a UTC instant). `null` [temporalClass] on a temporal literal is a programming error (callers
     * route temporal literals through [renderLiteralForColumn]) → drops.
     */
    @Suppress("ReturnCount")
    private fun renderLiteral(lit: ConnectorLiteral, temporalClass: TemporalClass?): String? {
        if (lit.isNull) {
            return "NULL"
        }
        return when (val v = lit.value) {
            is String -> quoteString(v) // may return null on NUL
            is Boolean -> if (v) "TRUE" else "FALSE"
            is Int, is Long, is BigDecimal -> v.toString()
            is Double -> if (v.isFinite()) v.toString() else null // ±Inf/NaN: not a safe literal
            is Float -> if (v.isFinite()) v.toString() else null
            is LocalDate -> renderDate(v, temporalClass)
            is LocalDateTime -> renderDatetime(v, temporalClass)
            else -> null // unknown value type — drop (evaluated above)
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
        // EQ_FOR_NULL is null-safe equality (<=>) — not a plain '='; drop until we render it faithfully.
        else -> null
    }

    private fun isScalar(col: ConnectorColumnRef): Boolean =
        col.type.typeName.uppercase() !in NON_SCALAR_TYPE_NAMES

    // ---- P1 function allowlist ----

    /**
     * One audited-IDENTICAL function: the accepted arities and how to render it in DuckDB, given the
     * already-rendered argument SQL (`r`) and the raw argument expressions (`a`, for guard checks
     * like "constant start ≠ 0"). Return null to drop (guard failed).
     */
    private class AllowedFunction(
        val arities: Set<Int>,
        val render: (r: List<String>, a: List<ConnectorExpression>) -> String?,
    )

    /**
     * Doris function-name (lowercased, as the FE emits it — verified via `EXPLAIN VERBOSE`
     * `PREDICATES:`) → DuckDB rendering. Every entry cites its verdict in
     * `dev-docs/REPORT-doris-duckdb-function-divergence.md`. IDENTICAL, integer/string/boolean
     * returns only (float-returning functions excluded — print-precision-fragile in `=`).
     */
    private val FUNCTION_ALLOWLIST: Map<String, AllowedFunction> = buildMap {
        // Doris character_length (code points; FE normalizes char_length → character_length) ≡
        // DuckDB length. IDENTICAL.
        put("character_length", AllowedFunction(setOf(1)) { r, _ -> "length(${r[0]})" })
        // Doris length (BYTES) ≡ DuckDB strlen — NOT DuckDB length (which is code points). IDENTICAL.
        put("length", AllowedFunction(setOf(1)) { r, _ -> "strlen(${r[0]})" })
        // substring: IDENTICAL for a constant start ≠ 0 (Doris returns NULL at 0, DuckDB clamps 0→1).
        put(
            "substring",
            AllowedFunction(setOf(2, 3)) { r, a ->
                if (constantStartIsNonZero(a)) r.joinToString(", ", "substring(", ")") else null
            },
        )
        // Doris locate(needle, hay) ≡ DuckDB strpos(hay, needle) — ARG SWAP. IDENTICAL.
        put("locate", AllowedFunction(setOf(2)) { r, _ -> "strpos(${r[1]}, ${r[0]})" })
        // Doris instr(hay, needle) ≡ DuckDB instr(hay, needle). IDENTICAL.
        put("instr", AllowedFunction(setOf(2)) { r, _ -> "instr(${r[0]}, ${r[1]})" })
        // starts_with(x, prefix). IDENTICAL.
        put("starts_with", AllowedFunction(setOf(2)) { r, _ -> "starts_with(${r[0]}, ${r[1]})" })
        // abs — integer-exact for INT inputs. IDENTICAL.
        put("abs", AllowedFunction(setOf(1)) { r, _ -> "abs(${r[0]})" })
        // Date extraction (INT return). IDENTICAL.
        for (dateFn in setOf("year", "month", "day", "hour", "minute", "second")) {
            put(dateFn, AllowedFunction(setOf(1)) { r, _ -> "$dateFn(${r[0]})" })
        }
    }

    /**
     * Whether a `substring`'s start argument is a constant literal ≠ 0. DuckDB clamps a 0 start to 1
     * while Doris returns NULL, so pushing `substring(x, 0, …)` would over-return remotely and (since
     * the FE re-filters, and a remote extra row that Doris would NULL-out is then dropped) is unsafe
     * only if the start could be 0 — a non-constant start can't be proven ≠ 0, so we require a
     * constant. (Negative and ≥1 constants are audited-aligned.)
     */
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

    /**
     * Quote a DuckDB identifier: wrap in `"`, escaping embedded `"` as `""`. Unicode-safe (DuckDB
     * identifiers are UTF-8). A NUL in an identifier is impossible in practice (DuckDB rejects it at
     * DDL time) but we still guard: callers only pass names that came back from `getColumns`, so
     * this is defensive.
     */
    private fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * Quote a DuckDB string literal: wrap in `'`, escaping embedded `'` as `''`. Unicode-safe.
     * **Refuses (returns null) a string containing U+0000** — DuckDB's wire protocol cannot
     * round-trip an embedded NUL, so pushing it risks a truncated/mismatched literal. The caller
     * drops the conjunct and Doris evaluates it above the scan (never silently wrong).
     */
    private fun quoteString(value: String): String? {
        if (value.indexOf('\u0000') >= 0) {
            return null
        }
        return "'" + value.replace("'", "''") + "'"
    }
}
