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

    /** The catalog of pushable entries and how each is emitted; lives in [DuckBridgeEmissionCatalog]. */
    val EMISSION_STRATEGIES: Map<NameArity, Emission> get() = DuckBridgeEmissionCatalog.EMISSION_STRATEGIES

    private val TYPE_GATES: Map<NameArity, ArgTypeGate> get() = DuckBridgeEmissionCatalog.TYPE_GATES

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
            // A literal containing U+0000 cannot be spelled as a plain quoted string (DuckDB's parser
            // stops at the NUL byte: "unterminated quoted string"); leave the conjunct in Trino.
            if (s.indexOf('\u0000') >= 0) {
                return null
            }
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
            is Emission.Contextual -> emission.template(rendered, args, session)
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

    private fun isIntegerFamily(type: Type): Boolean = DuckBridgeEmissionCatalog.isIntegerFamily(type)

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
        if (!castSemanticsAlign(operand.type, call.type)) {
            return null
        }
        val inner = translateOrNull(operand, assignments, session, ctx)
        return if (inner == null) null else "$castKeyword($inner AS $targetType)"
    }

    /**
     * Source/target pairs whose CAST / TRY_CAST results are identical in both engines. Excluded:
     *  - string → anything else (EV-A9): DuckDB's parsers are more lenient — '1.0'→INTEGER 1,
     *    '2020/01/01'→DATE, 'yes'→BOOLEAN — where Trino rejects (TRY_CAST NULL / CAST error).
     *  - DOUBLE/REAL → VARCHAR (EV-A10): Java `Double.toString` vs DuckDB formatting
     *    (1.0E7 vs 10000000.0, 1.0E20 vs 1e+20).
     *  - TIMESTAMP/TIME/DECIMAL-family and anything not in [duckdbTypeName] → VARCHAR: rendering
     *    (precision digits, trailing zeros) is engine-specific; only exact-integer, boolean and DATE
     *    render identically.
     */
    private fun castSemanticsAlign(source: Type, target: Type): Boolean {
        val sourceIsString = source is VarcharType || source is io.trino.spi.type.CharType
        if (sourceIsString) {
            return target is VarcharType
        }
        if (target is VarcharType) {
            return isIntegerFamily(source) || source is BooleanType || source is DateType
        }
        return true
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

        /**
         * Like [Inline] but also sees the original argument expressions (for their types) and the
         * session; may return null to decline the push (e.g. no usable session zone).
         */
        class Contextual(val template: (List<String>, List<ConnectorExpression>, ConnectorSession?) -> String?) : Emission
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
