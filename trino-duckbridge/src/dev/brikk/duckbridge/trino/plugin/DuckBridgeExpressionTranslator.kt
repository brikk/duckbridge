/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slice
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType
import io.trino.spi.type.BooleanType
import io.trino.spi.type.DateType
import io.trino.spi.type.DoubleType
import io.trino.spi.type.IntegerType
import io.trino.spi.type.SmallintType
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.TinyintType
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType
import java.time.LocalDate
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Method

/**
 * Translates a Trino [ConnectorExpression] predicate into DuckDB SQL fragments that the connector
 * pushes into the remote DuckDB WHERE clause via the base-jdbc `convertPredicate` seam.
 *
 * This is the "brain" of function-shape pushdown. It consults [EMISSION_STRATEGIES] and, for a
 * recognised `(name, arity)`, emits SQL per that entry's [Emission] class: a bare DuckDB built-in
 * ([Emission.Bare]), a rename ([Emission.Rename]), a parenthesized operator ([Emission.Operator]),
 * an inline SQL transform ([Emission.Inline]), or the `trino_parity` extension's `trino_<name>(...)`
 * alias ([Emission.Alias]). "Alias only what diverges": only the [Emission.Alias] entries depend on
 * the extension; the rest evaluate with Trino-identical semantics on a bare DuckDB and stay pushable
 * even when parity is disabled. Anything unrecognized — unknown function, NULL constant, unsupported
 * type — fails the translation for that conjunct so the caller leaves it in the remaining expression
 * for Trino to evaluate above the scan. The translator never throws.
 *
 * Top-level conjuncts (the children of a top-level `$and`) are translated independently so partial
 * pushdown is possible. (base-jdbc additionally splits conjuncts before calling `convertPredicate`,
 * so this decomposition is belt-and-suspenders.)
 *
 * Difference from the DuckLake port: variables resolve against [JdbcColumnHandle] (base-jdbc's
 * remote column handle) instead of DuckLake's own handle, and there is no row-id column concept.
 */
object DuckBridgeExpressionTranslator {
    /**
     * The set of `(name, arg_count)` pairs the translator can push (across all emission classes).
     * Backed by [EMISSION_STRATEGIES]. Only the [Emission.Alias] subset needs the extension; that
     * subset is asserted ⊆ `trino_meta()` by `TestTrinoFunctionAliases.testAliasSetIsSubsetOfMeta`.
     */
    val PUSHABLE_FUNCTIONS: Set<NameArity> get() = EMISSION_STRATEGIES.keys

    /** The [Emission.Alias] subset — the entries that require the `trino_parity` extension. */
    val ALIAS_FUNCTIONS: Set<NameArity> get() = EMISSION_STRATEGIES.filterValues { it is Emission.Alias }.keys

    /**
     * How each pushable `(name, arity)` is emitted into remote DuckDB SQL. "Alias only what
     * diverges" (user-approved rework): most pushed functions emit a bare DuckDB built-in name (or a
     * rename / operator / SQL-expressible transform) that DuckDB evaluates with Trino-identical
     * semantics natively, and only the entries DuckDB genuinely cannot match without the C++ layer
     * route through the `trino_<name>(...)` [ALIAS] macros/functions of the `trino_parity` extension.
     *
     * Classification authority: the extension maintainers' passthrough audit and, for the
     * [INLINE] bodies, the verified macro bodies in the extension's `macro_definitions.cpp`
     * (as of the pre-shrink revision that still carried them).
     *
     *  - [Emission.Bare]     — same bare built-in name (`length(s)`, `abs(x)`, `year(x)`).
     *  - [Emission.Rename]   — a different bare DuckDB built-in name (`to_hex→hex`).
     *  - [Emission.Operator] — a parenthesized infix/prefix operator (`bitwise_and→(a & b)`).
     *  - [Emission.Inline]   — a fixed SQL transform template (`regexp_replace/2→regexp_replace(s,p,'','g')`).
     *  - [Emission.Alias]    — the extension's `trino_<name>(...)` (native C++ divergence-fixers only).
     *
     * Only [Emission.Alias] entries depend on the extension. When it is unavailable (parity disabled
     * or the binary missing) the Bare/Rename/Operator/Inline classes REMAIN pushable — their
     * correctness is fixture-proven against embedded DuckDB, not the extension.
     */
    val EMISSION_STRATEGIES: Map<NameArity, Emission> =
        buildMap {
            // ---- ALIAS: native C++ divergence-fixers (extension required) ----------------------
            put(NameArity("lower", 1), Emission.Alias)
            put(NameArity("upper", 1), Emission.Alias)
            put(NameArity("reverse", 1), Emission.Alias)
            put(NameArity("trim", 1), Emission.Alias)
            put(NameArity("ltrim", 1), Emission.Alias)
            put(NameArity("rtrim", 1), Emission.Alias)
            put(NameArity("normalize", 1), Emission.Alias)
            put(NameArity("xxhash64", 1), Emission.Alias)
            put(NameArity("sha512", 1), Emission.Alias)
            put(NameArity("hmac_sha256", 2), Emission.Alias)

            // ---- BARE: pure passthroughs — same bare built-in, semantics aligned ---------------
            // String
            put(NameArity("length", 1), Emission.Bare)
            put(NameArity("substring", 2), Emission.Bare)
            put(NameArity("substring", 3), Emission.Bare)
            put(NameArity("replace", 3), Emission.Bare)
            put(NameArity("strpos", 2), Emission.Bare)
            put(NameArity("starts_with", 2), Emission.Bare)
            put(NameArity("lpad", 3), Emission.Bare)
            put(NameArity("rpad", 3), Emission.Bare)
            put(NameArity("concat_ws", 2), Emission.Bare)
            put(NameArity("concat_ws", 3), Emission.Bare)
            put(NameArity("concat_ws", 4), Emission.Bare)
            put(NameArity("concat_ws", 5), Emission.Bare)
            put(NameArity("translate", 3), Emission.Bare)
            put(NameArity("chr", 1), Emission.Bare)
            put(NameArity("bit_length", 1), Emission.Bare)
            put(NameArity("url_encode", 1), Emission.Bare)
            put(NameArity("url_decode", 1), Emission.Bare)
            put(NameArity("to_base64", 1), Emission.Bare)
            put(NameArity("from_base64", 1), Emission.Bare)
            // Numeric / math
            put(NameArity("abs", 1), Emission.Bare)
            put(NameArity("ceil", 1), Emission.Bare)
            put(NameArity("floor", 1), Emission.Bare)
            put(NameArity("mod", 2), Emission.Bare)
            put(NameArity("power", 2), Emission.Bare)
            put(NameArity("sqrt", 1), Emission.Bare)
            put(NameArity("exp", 1), Emission.Bare)
            put(NameArity("ln", 1), Emission.Bare)
            put(NameArity("log2", 1), Emission.Bare)
            put(NameArity("log10", 1), Emission.Bare)
            put(NameArity("sin", 1), Emission.Bare)
            put(NameArity("cos", 1), Emission.Bare)
            put(NameArity("tan", 1), Emission.Bare)
            put(NameArity("asin", 1), Emission.Bare)
            put(NameArity("acos", 1), Emission.Bare)
            put(NameArity("atan", 1), Emission.Bare)
            put(NameArity("atan2", 2), Emission.Bare)
            put(NameArity("sinh", 1), Emission.Bare)
            put(NameArity("cosh", 1), Emission.Bare)
            put(NameArity("tanh", 1), Emission.Bare)
            put(NameArity("degrees", 1), Emission.Bare)
            put(NameArity("radians", 1), Emission.Bare)
            put(NameArity("cbrt", 1), Emission.Bare)
            put(NameArity("sign", 1), Emission.Bare)
            put(NameArity("pi", 0), Emission.Bare)
            // Regex
            put(NameArity("regexp_extract", 2), Emission.Bare)
            put(NameArity("regexp_extract", 3), Emission.Bare)
            // Date / time
            put(NameArity("year", 1), Emission.Bare)
            put(NameArity("month", 1), Emission.Bare)
            put(NameArity("day", 1), Emission.Bare)
            put(NameArity("quarter", 1), Emission.Bare)
            // date_trunc: DuckDB returns TIMESTAMP even for DATE input where Trino preserves DATE.
            // The type differs but RESULTS do NOT in any pushed (comparison) context: DuckDB
            // auto-casts DATE→TIMESTAMP at midnight, so `date_trunc('month', d) </>/= <date>` yields
            // the same boolean as Trino's DATE comparison. Verified against embedded DuckDB and pinned
            // by the date_trunc fixture; BARE is result-safe, no DATE gate needed.
            put(NameArity("date_trunc", 2), Emission.Bare)
            put(NameArity("date_diff", 3), Emission.Bare)
            put(NameArity("week", 1), Emission.Bare)
            put(NameArity("hour", 1), Emission.Bare)
            put(NameArity("minute", 1), Emission.Bare)
            put(NameArity("second", 1), Emission.Bare)

            // ---- RENAME: a different bare DuckDB built-in name ---------------------------------
            put(NameArity("to_hex", 1), Emission.Rename("hex"))
            put(NameArity("from_hex", 1), Emission.Rename("unhex"))
            put(NameArity("levenshtein_distance", 2), Emission.Rename("levenshtein"))
            put(NameArity("hamming_distance", 2), Emission.Rename("hamming"))
            put(NameArity("truncate", 1), Emission.Rename("trunc"))
            put(NameArity("regexp_like", 2), Emission.Rename("regexp_matches"))
            put(NameArity("day_of_year", 1), Emission.Rename("dayofyear"))
            put(NameArity("last_day_of_month", 1), Emission.Rename("last_day"))
            put(NameArity("week_of_year", 1), Emission.Rename("week"))
            put(NameArity("from_unixtime", 1), Emission.Rename("to_timestamp"))
            // bitwise_xor: Trino name; DuckDB scalar is xor(x, y). Pure rename (the macro body is
            // xor(x, y), NOT an operator — DuckDB has no infix XOR operator).
            put(NameArity("bitwise_xor", 2), Emission.Rename("xor"))

            // ---- OPERATOR: parenthesized infix / prefix operator -------------------------------
            put(NameArity("bitwise_and", 2), Emission.Operator.infix("&"))
            put(NameArity("bitwise_or", 2), Emission.Operator.infix("|"))
            put(NameArity("bitwise_left_shift", 2), Emission.Operator.infix("<<"))
            put(NameArity("bitwise_right_shift", 2), Emission.Operator.infix(">>"))
            put(NameArity("bitwise_not", 1), Emission.Operator.prefix("~"))

            // ---- INLINE: fixed SQL transform templates (verified vs macro_definitions.cpp) -----
            // regexp_replace: force the 'g' flag to match Trino's global default. 2-arg removes
            // matches ('' replacement). Macro bodies:
            //   trino_regexp_replace/2 -> regexp_replace(s, pattern, '', 'g')
            //   trino_regexp_replace/3 -> regexp_replace(s, pattern, replacement, 'g')
            put(NameArity("regexp_replace", 2), Emission.Inline { a -> "regexp_replace(${a[0]}, ${a[1]}, '', 'g')" })
            put(NameArity("regexp_replace", 3), Emission.Inline { a -> "regexp_replace(${a[0]}, ${a[1]}, ${a[2]}, 'g')" })
            // Crypto hashes: DuckDB md5/sha1/sha256 return hex VARCHAR; Trino returns VARBINARY.
            // unhex() the hex string to the BLOB shape Trino expects.
            put(NameArity("md5", 1), Emission.Inline { a -> "unhex(md5(${a[0]}))" })
            put(NameArity("sha1", 1), Emission.Inline { a -> "unhex(sha1(${a[0]}))" })
            put(NameArity("sha256", 1), Emission.Inline { a -> "unhex(sha256(${a[0]}))" })
            // if/2 returns NULL on the false branch; if/3 is a pure passthrough (bare `if`).
            put(NameArity("if", 2), Emission.Inline { a -> "if(${a[0]}, ${a[1]}, NULL)" })
            put(NameArity("if", 3), Emission.Bare)
            // day_of_week -> ISO isodow (Mon=1..Sun=7). year_of_week/yow -> ISO-week-numbering year.
            put(NameArity("day_of_week", 1), Emission.Inline { a -> "isodow(${a[0]})" })
            put(NameArity("year_of_week", 1), Emission.Inline { a -> "CAST(extract('isoyear' FROM ${a[0]}) AS BIGINT)" })
            put(NameArity("yow", 1), Emission.Inline { a -> "CAST(extract('isoyear' FROM ${a[0]}) AS BIGINT)" })
            // millisecond -> millis-OF-SECOND (0..999), NOT epoch millis.
            put(NameArity("millisecond", 1), Emission.Inline { a -> "CAST(extract('millisecond' FROM ${a[0]}) AS BIGINT)" })
            // to_unixtime -> seconds since epoch (UTC) as DOUBLE.
            put(NameArity("to_unixtime", 1), Emission.Inline { a -> "CAST(epoch(${a[0]}) AS DOUBLE)" })
            // with_timezone(ts, zone) -> DuckDB timezone(zone, ts) ARG-ORDER FLIP.
            put(NameArity("with_timezone", 2), Emission.Inline { a -> "timezone(${a[1]}, ${a[0]})" })
        }

    /**
     * Sparse map of per-entry argument-type gates. [PUSHABLE_FUNCTIONS] remains the binary "is this
     * (name, arity) pushable at all" set; this registry adds finer-grained "and only when the
     * argument types are these" conditions. Entries without a row here accept any argument types.
     */
    private val TYPE_GATES: Map<NameArity, ArgTypeGate> = buildTypeGates()

    private fun buildTypeGates(): Map<NameArity, ArgTypeGate> {
        val gates: MutableMap<NameArity, ArgTypeGate> = mutableMapOf()
        // Tier B always accepted (DATE or TIMESTAMP no-TZ); Tier C (TIMESTAMP WITH TIME ZONE)
        // conditionally accepted when the session sets pushdown_timestamp_with_timezone = true.
        val arg0Tier = argTier(0)
        for (name in listOf("year", "month", "day", "quarter", "hour", "minute", "second", "millisecond", "to_unixtime")) {
            gates[NameArity(name, 1)] = arg0Tier
        }
        // date_trunc(unit, x): gate the second arg.
        gates[NameArity("date_trunc", 2)] = argTier(1)
        // date_diff(unit, t1, t2): both date-shape args must clear the same gate.
        gates[NameArity("date_diff", 3)] =
            ArgTypeGate { args, session ->
                val inner = argTier(0)
                inner.accepts(listOf(args[1]), session) && inner.accepts(listOf(args[2]), session)
            }
        // Tier A — DATE-only.
        val arg0DateStrict = arg(0, DateType::class.java)
        for (name in listOf("day_of_week", "day_of_year", "last_day_of_month", "week", "week_of_year", "year_of_week", "yow")) {
            gates[NameArity(name, 1)] = arg0DateStrict
        }
        // with_timezone(TIMESTAMP no-TZ, varchar) → WTZ. Gate strictly to TIMESTAMP.
        gates[NameArity("with_timezone", 2)] = arg(0, TimestampType::class.java)

        // lpad/rpad: push ONLY when the pad argument (arg 2) is a constant, non-empty varchar.
        // Trino raises on an empty pad string; DuckDB does not. A non-constant pad could be empty at
        // runtime, so we can only push when we can PROVE the pad is non-empty — i.e. a literal.
        val constNonEmptyPad = constNonEmptyVarcharArg(2)
        gates[NameArity("lpad", 3)] = constNonEmptyPad
        gates[NameArity("rpad", 3)] = constNonEmptyPad

        // substring/{2,3}: DuckDB treats start=0 as start=1, Trino differs. Push ONLY when the start
        // argument (arg 1) is a constant integer ≥ 1, which is the range both engines align on.
        val constStartAtLeastOne = constIntArgAtLeast(1, 1)
        gates[NameArity("substring", 2)] = constStartAtLeastOne
        gates[NameArity("substring", 3)] = constStartAtLeastOne
        return gates.toMap()
    }

    /** Gate: argument [index] must be a [Constant] non-empty [VarcharType] [Slice]. */
    private fun constNonEmptyVarcharArg(index: Int): ArgTypeGate =
        ArgTypeGate { args, _ ->
            val a = args.getOrNull(index)
            a is Constant && a.type is VarcharType && (a.value as? Slice)?.length()?.let { it > 0 } == true
        }

    /** Gate: argument [index] must be a [Constant] integer-family value ≥ [minimum]. */
    private fun constIntArgAtLeast(index: Int, minimum: Long): ArgTypeGate =
        ArgTypeGate { args, _ ->
            val a = args.getOrNull(index)
            a is Constant && isIntegerFamily(a.type) && (a.value as? Long)?.let { it >= minimum } == true
        }

    private fun arg(index: Int, vararg allowed: Class<*>): ArgTypeGate =
        ArgTypeGate { args, _ ->
            if (index >= args.size) {
                false
            } else {
                val t: Type = args[index].type
                allowed.any { it.isInstance(t) }
            }
        }

    private fun argTier(index: Int): ArgTypeGate =
        ArgTypeGate { args, session ->
            if (index >= args.size) {
                false
            } else {
                val t: Type = args[index].type
                when {
                    t is DateType || t is TimestampType -> true
                    t is TimestampWithTimeZoneType &&
                        DuckBridgeSessionProperties.isPushdownTimestampWithTimeZone(session) -> true
                    else -> false
                }
            }
        }

    /**
     * Decompose `expression` into top-level AND-conjuncts and translate each independently.
     * Returns the SQL fragments for conjuncts the translator could handle. The session-less overload
     * reads as "no session properties available" — Tier C and any other session-property-gated entry
     * stays unpushed.
     */
    fun translateConjuncts(expression: ConnectorExpression, assignments: Map<String, ColumnHandle>): List<String> =
        translateConjuncts(expression, assignments, null)

    fun translateConjuncts(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
    ): List<String> = translateConjuncts(expression, assignments, session, aliasAvailable = true)

    /**
     * [aliasAvailable] tells the translator whether the `trino_parity` extension's `trino_<name>(...)`
     * layer is loaded on the target connection. When false, [Emission.Alias] entries are NOT pushed
     * (they'd resolve to a missing function on the remote DuckDB); the Bare/Rename/Operator/Inline
     * classes push regardless — they never touch the extension. This overload keeps string-comparison
     * pushdown ON (used by rendering unit tests); production passes both trust axes via the mode.
     */
    fun translateConjuncts(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        aliasAvailable: Boolean,
    ): List<String> = translateConjuncts(expression, assignments, session, aliasAvailable, stringComparisonAllowed = true)

    /**
     * Full-trust-axis entry point. [aliasAvailable] gates the extension-backed [Emission.Alias]
     * functions; [stringComparisonAllowed] gates conjuncts that COMPARE a string operand
     * (`upper(x)='B'`, `x LIKE 'a%'`, string `=`/`</`/range) — false in NULL_ONLY/GUARDED, where such
     * comparisons stay in Trino because a diverging string prefilter under-returns and no retained
     * filter can repair it. Non-string-comparing conjuncts (`length(s)=5`, `abs(id)=3`,
     * `year(d)=2000`) push in every mode.
     */
    fun translateConjuncts(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        aliasAvailable: Boolean,
        stringComparisonAllowed: Boolean,
    ): List<String> {
        val out: MutableList<String> = mutableListOf()
        for (conjunct in conjuncts(expression)) {
            if (isTautologyTrue(conjunct)) {
                continue
            }
            translate(conjunct, assignments, session, aliasAvailable, stringComparisonAllowed)?.let(out::add)
        }
        return out.toList()
    }

    private fun isTautologyTrue(expression: ConnectorExpression): Boolean =
        expression is Constant && expression.type is BooleanType && expression.value == true

    private fun conjuncts(expression: ConnectorExpression): List<ConnectorExpression> {
        if (expression is Call && expression.functionName == StandardFunctions.AND_FUNCTION_NAME) {
            val out: MutableList<ConnectorExpression> = mutableListOf()
            for (child in expression.arguments) {
                out.addAll(conjuncts(child))
            }
            return out
        }
        return listOf(expression)
    }

    /** Translate a single expression to DuckDB SQL. Returns null when any subterm is unrecognised. Never throws. */
    fun translate(expression: ConnectorExpression, assignments: Map<String, ColumnHandle>): String? =
        translate(expression, assignments, null)

    fun translate(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
    ): String? = translate(expression, assignments, session, aliasAvailable = true)

    fun translate(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        aliasAvailable: Boolean,
    ): String? = translate(expression, assignments, session, aliasAvailable, stringComparisonAllowed = true)

    fun translate(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        aliasAvailable: Boolean,
        stringComparisonAllowed: Boolean,
    ): String? =
        try {
            translateOrNull(expression, assignments, session, Ctx(aliasAvailable, stringComparisonAllowed))
        } catch (@Suppress("TooGenericExceptionCaught") ignored: RuntimeException) {
            // Defensive: any unexpected RuntimeException from a sub-translator => fail safe.
            null
        }

    /** The two per-query trust axes threaded through the recursive translator. */
    private data class Ctx(val aliasAvailable: Boolean, val stringComparisonAllowed: Boolean)

    private fun translateOrNull(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? =
        when (expression) {
            is Variable -> translateVariable(expression, assignments)
            is Constant -> translateConstant(expression)
            is Call -> translateCall(expression, assignments, session, ctx)
            else -> null
        }

    private fun translateVariable(variable: Variable, assignments: Map<String, ColumnHandle>): String? {
        val column = assignments[variable.name]
        if (column !is JdbcColumnHandle) {
            return null
        }
        val escaped = column.columnName.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    @Suppress("CyclomaticComplexMethod") // Faithful port: one branch per SPI constant type; splitting it would obscure the type dispatch.
    private fun translateConstant(constant: Constant): String? {
        val value: Any? = constant.value
        val type: Type = constant.type
        if (value == null) {
            return "NULL"
        }
        if (type is BooleanType) {
            return if (value as Boolean) "TRUE" else "FALSE"
        }
        if (isIntegerFamily(type)) {
            return (value as Long).toString()
        }
        if (type is DoubleType) {
            val d: Double = value as Double
            if (d.isNaN() || d.isInfinite()) {
                return null
            }
            return d.toString()
        }
        if (type is VarcharType) {
            if (value !is Slice) {
                return null
            }
            val s = value.toStringUtf8()
            return "'" + s.replace("'", "''") + "'"
        }
        if (type is DateType) {
            val days = value as Long
            val date = LocalDate.ofEpochDay(days)
            // DuckDB's DATE literal parser rejects the signed/extended forms LocalDate emits for
            // years <1 (BC, '-') or >9999 ('+'); leave such constants unpushed for Trino-side eval.
            if (date.year !in 1..9999) {
                return null
            }
            return "DATE '$date'"
        }
        return null
    }

    // Faithful port of the operator/function dispatch table; each branch encodes a verified semantic
    // edge case (see class doc). Intentionally kept as one dispatch rather than re-derived.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun translateCall(
        call: Call,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        val name: FunctionName = call.functionName
        val args: List<ConnectorExpression> = call.arguments

        when {
            name == StandardFunctions.AND_FUNCTION_NAME -> return joinBinary(args, " AND ", assignments, session, ctx)
            name == StandardFunctions.OR_FUNCTION_NAME -> return joinBinary(args, " OR ", assignments, session, ctx)
            name == StandardFunctions.NOT_FUNCTION_NAME && args.size == 1 -> {
                val inner = translateOrNull(args[0], assignments, session, ctx)
                return if (inner == null) null else "(NOT $inner)"
            }
            name == StandardFunctions.IS_NULL_FUNCTION_NAME && args.size == 1 -> {
                val inner = translateOrNull(args[0], assignments, session, ctx)
                return if (inner == null) null else "($inner IS NULL)"
            }
            name == StandardFunctions.LIKE_FUNCTION_NAME && args.size == 2 -> {
                // LIKE is inherently a string comparison — requires >= BINARY string-comparison trust.
                if (!ctx.stringComparisonAllowed) {
                    return null
                }
                return translateLike(args[0], args[1], assignments, session, ctx)
            }
        }

        comparisonOperator(name)?.let { operator ->
            if (args.size == 2) {
                // A comparison whose operands are string-typed needs >= BINARY string-comparison trust:
                // in NULL_ONLY/GUARDED a diverging string prefilter under-returns, unfixable by a
                // retained filter, so the whole conjunct stays in Trino. Non-string comparisons
                // (length(s)=5, abs(id)=3, year(d)=2000) push in every mode.
                if (comparesStringOperand(args) && !ctx.stringComparisonAllowed) {
                    return null
                }
                val left = translateOrNull(args[0], assignments, session, ctx)
                val right = translateOrNull(args[1], assignments, session, ctx)
                return if (left == null || right == null) null else "($left $operator $right)"
            }
        }
        arithmeticOperator(name)?.let { arithmetic ->
            if (args.size == 2) {
                val left = translateOrNull(args[0], assignments, session, ctx)
                val right = translateOrNull(args[1], assignments, session, ctx)
                return if (left == null || right == null) null else "($left $arithmetic $right)"
            }
        }

        when {
            name == StandardFunctions.IDENTICAL_OPERATOR_FUNCTION_NAME && args.size == 2 -> {
                // IS NOT DISTINCT FROM over strings is a string comparison — same gate as `=`.
                if (comparesStringOperand(args) && !ctx.stringComparisonAllowed) {
                    return null
                }
                val left = translateOrNull(args[0], assignments, session, ctx)
                val right = translateOrNull(args[1], assignments, session, ctx)
                return if (left == null || right == null) null else "($left IS NOT DISTINCT FROM $right)"
            }
            name == StandardFunctions.COALESCE_FUNCTION_NAME && args.isNotEmpty() ->
                return translateVariadic("coalesce", args, assignments, session, ctx)
            name == StandardFunctions.NULLIF_FUNCTION_NAME && args.size == 2 -> {
                val left = translateOrNull(args[0], assignments, session, ctx)
                val right = translateOrNull(args[1], assignments, session, ctx)
                return if (left == null || right == null) null else "nullif($left, $right)"
            }
            name == StandardFunctions.NEGATE_FUNCTION_NAME && args.size == 1 -> {
                val inner = translateOrNull(args[0], assignments, session, ctx)
                return if (inner == null) null else "(-$inner)"
            }
            name == StandardFunctions.CAST_FUNCTION_NAME && args.size == 1 ->
                return translateCast(call, args[0], "CAST", assignments, session, ctx)
            name == StandardFunctions.TRY_CAST_FUNCTION_NAME && args.size == 1 ->
                return translateCast(call, args[0], "TRY_CAST", assignments, session, ctx)
        }

        // String concat is a translator rewrite (NOT a macro): Trino's concat(a,b,c) NULL-propagates,
        // DuckDB's built-in concat silently skips NULLs. The `||` operator NULL-propagates in BOTH
        // engines, so rewrite to (a || b || c). Gated on VARCHAR return type to avoid Trino's array
        // overload (different NULL semantics).
        if (isVarcharConcat(name, args, call)) {
            return translateStringConcat(args, assignments, session, ctx)
        }

        // Trino built-in functions: only push if (name, arity) is in our brain AND the optional
        // argument-type gate accepts the actual call's argument types. The emission strategy decides
        // whether we render a bare built-in, a rename, an operator, an inline transform, or the
        // extension's trino_<name>(...) alias — and ALIAS entries only push when the extension is
        // available on the target connection.
        if (name.catalogSchema.isEmpty) {
            val key = NameArity(name.name, args.size)
            val emission = EMISSION_STRATEGIES[key] ?: return null
            if (emission is Emission.Alias && !ctx.aliasAvailable) {
                return null
            }
            val gate = TYPE_GATES[key]
            if (gate != null && !gate.accepts(args, session)) {
                return null
            }
            return emitFunction(emission, name.name, args, assignments, session, ctx)
        }
        return null
    }

    /** Render a pushable function call per its [Emission] strategy. Returns null if any arg fails. */
    private fun emitFunction(
        emission: Emission,
        trinoName: String,
        args: List<ConnectorExpression>,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        val rendered = ArrayList<String>(args.size)
        for (arg in args) {
            rendered.add(translateOrNull(arg, assignments, session, ctx) ?: return null)
        }
        return when (emission) {
            is Emission.Bare -> call(trinoName, rendered)
            is Emission.Rename -> call(emission.duckName, rendered)
            is Emission.Alias -> call("trino_$trinoName", rendered)
            is Emission.Operator -> emission.render(rendered)
            is Emission.Inline -> emission.template(rendered)
        }
    }

    private fun call(name: String, args: List<String>): String = args.joinToString(", ", "$name(", ")")

    /**
     * True iff either operand of a 2-arg comparison is a string type (VARCHAR or CHAR). Such
     * comparisons carry the collation/byte-ordering trust question the mode dial gates; everything
     * else (numeric, date, boolean) is byte-exact across engines and pushes in every mode.
     */
    private fun comparesStringOperand(args: List<ConnectorExpression>): Boolean =
        args.any { it.type is VarcharType || it.type is io.trino.spi.type.CharType }

    private fun isIntegerFamily(type: Type): Boolean =
        type is BigintType || type is IntegerType || type is SmallintType || type is TinyintType

    private fun isVarcharConcat(name: FunctionName, args: List<ConnectorExpression>, call: Call): Boolean =
        name.catalogSchema.isEmpty && "concat" == name.name && args.size >= 2 && call.type is VarcharType

    private fun translateVariadic(
        sqlName: String,
        args: List<ConnectorExpression>,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        val sql = StringBuilder(sqlName).append('(')
        for (i in args.indices) {
            if (i > 0) {
                sql.append(", ")
            }
            val arg = translateOrNull(args[i], assignments, session, ctx) ?: return null
            sql.append(arg)
        }
        sql.append(')')
        return sql.toString()
    }

    private fun translateStringConcat(
        args: List<ConnectorExpression>,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        val out = StringBuilder("(")
        for (i in args.indices) {
            if (i > 0) {
                out.append(" || ")
            }
            val inner = translateOrNull(args[i], assignments, session, ctx) ?: return null
            out.append(inner)
        }
        out.append(')')
        return out.toString()
    }

    /**
     * Trino delivers LIKE as `Call($like, [value, Constant(LikePattern)])`. `io.trino.type.LikePattern`
     * lives in `trino-main`, not `trino-spi`, so it isn't on the production classpath — accessed
     * reflectively via [LikePatternAccessor]. NOT LIKE arrives as `Call($not, [Call($like, ...)])` and
     * is handled by the `$not` branch recursing into us. Returns null when value/pattern is not
     * translatable (including NULL pattern, dynamic pattern expression, etc.).
     */
    private fun translateLike(
        value: ConnectorExpression,
        patternArg: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        if (patternArg !is Constant) {
            return null
        }
        val patternValue: Any = patternArg.value ?: return null
        val extracted = LikePatternAccessor.extract(patternValue) ?: return null
        val translatedValue = translateOrNull(value, assignments, session, ctx) ?: return null
        val out =
            StringBuilder("(")
                .append(translatedValue)
                .append(" LIKE '")
                .append(extracted.pattern.replace("'", "''"))
                .append('\'')
        if (extracted.escape != null) {
            val escape: Char = extracted.escape
            out.append(" ESCAPE '")
            if (escape == '\'') {
                out.append("''")
            } else {
                out.append(escape)
            }
            out.append('\'')
        }
        out.append(')')
        return out.toString()
    }

    private fun translateCast(
        call: Call,
        operand: ConnectorExpression,
        castKeyword: String,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        val targetType = duckdbTypeName(call.type) ?: return null
        val inner = translateOrNull(operand, assignments, session, ctx)
        return if (inner == null) null else "$castKeyword($inner AS $targetType)"
    }

    /**
     * Map a Trino [Type] to the DuckDB type name to use inside a CAST. Conservative: only primitive
     * numeric / boolean / varchar / date are handled; timestamp precision + decimal scale + nested
     * types are unsupported so the translator fails the cast cleanly and stays unpushed.
     */
    private fun duckdbTypeName(type: Type): String? =
        when (type) {
            is BooleanType -> "BOOLEAN"
            is TinyintType -> "TINYINT"
            is SmallintType -> "SMALLINT"
            is IntegerType -> "INTEGER"
            is BigintType -> "BIGINT"
            is DoubleType -> "DOUBLE"
            is VarcharType -> "VARCHAR"
            is DateType -> "DATE"
            else -> null
        }

    private fun comparisonOperator(name: FunctionName): String? =
        when (name) {
            StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME -> "="
            StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME -> "<>"
            StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME -> "<"
            StandardFunctions.LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME -> "<="
            StandardFunctions.GREATER_THAN_OPERATOR_FUNCTION_NAME -> ">"
            StandardFunctions.GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME -> ">="
            else -> null
        }

    private fun arithmeticOperator(name: FunctionName): String? =
        when (name) {
            // Trino's $add/$subtract/$multiply map to identical SQL operators in DuckDB; both engines
            // align on integer overflow throws and float NaN/Inf propagation.
            StandardFunctions.ADD_FUNCTION_NAME -> "+"
            StandardFunctions.SUBTRACT_FUNCTION_NAME -> "-"
            StandardFunctions.MULTIPLY_FUNCTION_NAME -> "*"
            // $divide and $modulo are INTENTIONALLY not pushed: Trino integer `/` truncates toward
            // zero (5/2=2) but DuckDB does true division (5/2=2.5), and divide/modulo-by-zero throws
            // in Trino but silently yields Infinity/NULL in DuckDB (stripping the row before Trino's
            // above-scan re-eval can throw). A future trino_divide/trino_modulo parity function would
            // let these push safely. See TestDuckBridgeArithmeticPushdownParity.
            else -> null
        }

    private fun joinBinary(
        args: List<ConnectorExpression>,
        separator: String,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        if (args.isEmpty()) {
            return null
        }
        val out = StringBuilder("(")
        for (i in args.indices) {
            if (i > 0) {
                out.append(separator)
            }
            val inner = translateOrNull(args[i], assignments, session, ctx) ?: return null
            out.append(inner)
        }
        out.append(')')
        return out.toString()
    }

    fun interface ArgTypeGate {
        fun accepts(args: List<ConnectorExpression>, session: ConnectorSession?): Boolean
    }

    data class NameArity(val name: String, val arity: Int)

    /**
     * Emission strategy for a pushable `(name, arity)`. See [EMISSION_STRATEGIES].
     */
    sealed interface Emission {
        /** Emit the same bare built-in name (`length(s)`, `abs(x)`). */
        data object Bare : Emission

        /** Emit a different bare DuckDB built-in name (`to_hex→hex`). */
        data class Rename(val duckName: String) : Emission

        /** Emit the extension's `trino_<name>(...)` — the only class that needs the extension. */
        data object Alias : Emission

        /** Emit a parenthesized infix/prefix operator (`bitwise_and→(a & b)`, `bitwise_not→(~a)`). */
        class Operator private constructor(private val render: (List<String>) -> String) : Emission {
            fun render(args: List<String>): String = render.invoke(args)

            companion object {
                fun infix(op: String): Operator = Operator { a -> "(${a[0]} $op ${a[1]})" }

                fun prefix(op: String): Operator = Operator { a -> "($op${a[0]})" }
            }
        }

        /** Emit a fixed SQL transform template over the (already-rendered) argument SQL fragments. */
        class Inline(val template: (List<String>) -> String) : Emission
    }

    /**
     * Reflective bridge to `io.trino.type.LikePattern` (which lives in `trino-main`, not `trino-spi`,
     * so it isn't importable on the plugin's compile classpath). The runtime instance arrives via the
     * SPI as a `Constant` of `LikePatternType`; we reflect on the instance's own class to read its
     * pattern string and optional escape character. If the upstream class shape changes, [extract]
     * returns null and the LIKE conjunct stays unpushed.
     */
    private object LikePatternAccessor {
        private val CACHE: ConcurrentHashMap<Class<*>, MethodPair> = ConcurrentHashMap()
        private val MISSING: MethodPair = MethodPair(null, null)

        fun extract(likePattern: Any): Extracted? {
            val methods = CACHE.computeIfAbsent(likePattern.javaClass) { resolve(it) }
            if (methods.getPattern == null || methods.getEscape == null) {
                return null
            }
            return try {
                val pattern = methods.getPattern.invoke(likePattern) as String? ?: return null
                val escapeOpt = methods.getEscape.invoke(likePattern)
                var escape: Char? = null
                if (escapeOpt is Optional<*> && escapeOpt.isPresent) {
                    val inner = escapeOpt.get()
                    if (inner is Char) {
                        escape = inner
                    } else {
                        return null
                    }
                }
                Extracted(pattern, escape)
            } catch (@Suppress("SwallowedException") ignored: ReflectiveOperationException) {
                null
            }
        }

        private fun resolve(clazz: Class<*>): MethodPair {
            if ("io.trino.type.LikePattern" != clazz.name) {
                return MISSING
            }
            return try {
                MethodPair(clazz.getMethod("getPattern"), clazz.getMethod("getEscape"))
            } catch (@Suppress("SwallowedException") ignored: NoSuchMethodException) {
                MISSING
            }
        }

        private data class MethodPair(val getPattern: Method?, val getEscape: Method?)

        data class Extracted(val pattern: String, val escape: Char?)
    }
}
