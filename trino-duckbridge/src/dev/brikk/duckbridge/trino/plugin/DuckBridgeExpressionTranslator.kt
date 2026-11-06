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
import io.trino.spi.type.CharType
import io.trino.spi.type.DateType
import io.trino.spi.type.DoubleType
import io.trino.spi.type.IntegerType
import io.trino.spi.type.SmallintType
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.TinyintType
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType
import java.time.LocalDate

/**
 * Translates a Trino [ConnectorExpression] predicate into DuckDB SQL fragments for the remote WHERE
 * clause. Anything unrecognised (unknown function, unsupported type or constant) fails translation
 * for that conjunct, leaving it for Trino to evaluate above the scan; the translator never throws.
 */
object DuckBridgeExpressionTranslator {
    val PUSHABLE_FUNCTIONS: Set<NameArity> get() = EMISSION_STRATEGIES.keys

    /** The entries that require the `trino_parity` extension. */
    val ALIAS_FUNCTIONS: Set<NameArity> get() = EMISSION_STRATEGIES.filterValues { it is Emission.Alias }.keys

    val EMISSION_STRATEGIES: Map<NameArity, Emission> get() = DuckBridgeEmissionCatalog.EMISSION_STRATEGIES

    private val TYPE_GATES: Map<NameArity, ArgTypeGate> get() = DuckBridgeEmissionCatalog.TYPE_GATES

    /**
     * Translates each top-level AND-conjunct independently and returns the fragments that translated.
     * [aliasAvailable] is whether the `trino_parity` extension is loaded on the target connection.
     * [stringComparisonAllowed] is false in NULL_ONLY/GUARDED, where a diverging string prefilter
     * would under-return and no retained filter can repair it.
     */
    fun translateConjuncts(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession? = null,
        aliasAvailable: Boolean = true,
        stringComparisonAllowed: Boolean = true,
    ): List<String> =
        conjuncts(expression)
            .filterNot(::isTautologyTrue)
            .mapNotNull { translate(it, assignments, session, aliasAvailable, stringComparisonAllowed) }

    private fun isTautologyTrue(expression: ConnectorExpression): Boolean =
        expression is Constant && expression.type is BooleanType && expression.value == true

    private fun conjuncts(expression: ConnectorExpression): List<ConnectorExpression> =
        if (expression is Call && expression.functionName == StandardFunctions.AND_FUNCTION_NAME) {
            expression.arguments.flatMap(::conjuncts)
        } else {
            listOf(expression)
        }

    /** Translates a single expression to DuckDB SQL, or null when any subterm is unrecognised. */
    fun translate(
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession? = null,
        aliasAvailable: Boolean = true,
        stringComparisonAllowed: Boolean = true,
    ): String? =
        try {
            translateOrNull(expression, assignments, session, Ctx(aliasAvailable, stringComparisonAllowed))
        } catch (@Suppress("TooGenericExceptionCaught") ignored: RuntimeException) {
            null
        }

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
        // Function semantics proven over microseconds do not carry over to nanosecond values.
        if (DuckBridgeNanos.isHighPrecision(variable.type)) return null
        val column = assignments[variable.name] as? JdbcColumnHandle ?: return null
        val escaped = column.columnName.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    @Suppress("CyclomaticComplexMethod")
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
            // DuckDB's parser stops at a NUL byte inside a quoted literal; leave the conjunct in Trino.
            return varcharConstant(constant)?.let(::quoteLiteral)
        }
        if (type is DateType) {
            val days = value as Long
            val date = LocalDate.ofEpochDay(days)
            // DuckDB's DATE literal parser rejects the signed/extended forms LocalDate emits for
            // years <1 or >9999.
            if (date.year !in 1..9999) {
                return null
            }
            return "DATE '$date'"
        }
        return null
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun translateCall(
        call: Call,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        val name: FunctionName = call.functionName
        val args: List<ConnectorExpression> = call.arguments

        // Zoned intermediates may exceed native TIMESTAMPTZ and carry a non-session zone.
        // A typed consumer must lower them; generic recursion must never masquerade them as integers.
        if (args.any { it.type is TimestampWithTimeZoneType && DuckBridgeWithTimezone.contains(it) }) {
            return DuckBridgeWithTimezone.render(call, session) { translateVariable(it, assignments) }
        }

        when {
            name == StandardFunctions.AND_FUNCTION_NAME -> return joinBinary(args, " AND ", assignments, session, ctx)
            name == StandardFunctions.OR_FUNCTION_NAME -> return joinBinary(args, " OR ", assignments, session, ctx)
            name == StandardFunctions.NOT_FUNCTION_NAME && args.size == 1 ->
                return translateOrNull(args[0], assignments, session, ctx)?.let { "(NOT $it)" }
            name == StandardFunctions.IS_NULL_FUNCTION_NAME && args.size == 1 ->
                return translateOrNull(args[0], assignments, session, ctx)?.let { "($it IS NULL)" }
            name == StandardFunctions.LIKE_FUNCTION_NAME && args.size in 2..3 -> {
                if (!ctx.stringComparisonAllowed) {
                    return null
                }
                return translateLike(args[0], args[1], args.getOrNull(2), assignments, session, ctx)
            }
        }

        comparisonOperator(name)?.let { operator ->
            if (args.size == 2) {
                if (comparesStringOperand(args) && !ctx.stringComparisonAllowed) {
                    return null
                }
                val left = translateOrNull(args[0], assignments, session, ctx)
                val right = translateOrNull(args[1], assignments, session, ctx)
                return if (left == null || right == null) null
                else if (args.any { DuckBridgeFloatingExpressions.isFloating(it.type) })
                    DuckBridgeFloatingExpressions.comparison(left, operator, right)
                else "($left $operator $right)"
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
                return if (left == null || right == null) null
                else if (args.any { DuckBridgeFloatingExpressions.isFloating(it.type) })
                    "(CASE WHEN ${DuckBridgeFloatingExpressions.comparison(left, "=", right)} THEN NULL ELSE $left END)"
                else "nullif($left, $right)"
            }
            name == StandardFunctions.NEGATE_FUNCTION_NAME && args.size == 1 ->
                return translateOrNull(args[0], assignments, session, ctx)?.let { "(-$it)" }
            name == StandardFunctions.CAST_FUNCTION_NAME && args.size == 1 ->
                return translateCast(call, args[0], "CAST", assignments, session, ctx)
            name == StandardFunctions.TRY_CAST_FUNCTION_NAME && args.size == 1 ->
                return translateCast(call, args[0], "TRY_CAST", assignments, session, ctx)
        }

        // Trino's concat NULL-propagates but DuckDB's concat skips NULLs; `||` NULL-propagates in
        // both. Gated on a VARCHAR result to exclude Trino's array overload.
        if (isVarcharConcat(name, args, call)) {
            return translateStringConcat(args, assignments, session, ctx)
        }

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

    private fun emitFunction(
        emission: Emission,
        trinoName: String,
        args: List<ConnectorExpression>,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        val rendered = translateAll(args, assignments, session, ctx) ?: return null
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

    /** Translates every argument in order, or returns null at the first one that fails. */
    private fun translateAll(
        args: List<ConnectorExpression>,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): List<String>? {
        val rendered = ArrayList<String>(args.size)
        for (arg in args) {
            rendered.add(translateOrNull(arg, assignments, session, ctx) ?: return null)
        }
        return rendered
    }

    private fun comparesStringOperand(args: List<ConnectorExpression>): Boolean =
        args.any { it.type is VarcharType || it.type is CharType }

    private fun isIntegerFamily(type: Type): Boolean = DuckBridgeEmissionCatalog.isIntegerFamily(type)

    private fun isVarcharConcat(name: FunctionName, args: List<ConnectorExpression>, call: Call): Boolean =
        name.catalogSchema.isEmpty && "concat" == name.name && args.size >= 2 && call.type is VarcharType

    private fun translateVariadic(
        sqlName: String,
        args: List<ConnectorExpression>,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? = translateAll(args, assignments, session, ctx)?.let { call(sqlName, it) }

    private fun translateStringConcat(
        args: List<ConnectorExpression>,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? = translateAll(args, assignments, session, ctx)?.joinToString(" || ", "(", ")")

    /**
     * Trino's planner unpacks a compiled LikePattern before it reaches the connector, so the pattern
     * and optional single-character escape arrive as VARCHAR [Constant]s. `NOT LIKE` arrives as
     * `$not($like(...))`.
     */
    private fun translateLike(
        value: ConnectorExpression,
        patternArg: ConnectorExpression,
        escapeArg: ConnectorExpression?,
        assignments: Map<String, ColumnHandle>,
        session: ConnectorSession?,
        ctx: Ctx,
    ): String? {
        val pattern = varcharConstant(patternArg) ?: return null
        val escape = escapeArg?.let { varcharConstant(it) ?: return null }
        if (escape != null && escape.codePointCount(0, escape.length) != 1) {
            return null
        }
        val translatedValue = translateOrNull(value, assignments, session, ctx) ?: return null
        val escapeClause = escape?.let { " ESCAPE ${quoteLiteral(it)}" }.orEmpty()
        return "($translatedValue LIKE ${quoteLiteral(pattern)}$escapeClause)"
    }

    private fun varcharConstant(expression: ConnectorExpression): String? {
        if (expression !is Constant || expression.type !is VarcharType) return null
        val value = expression.value as? Slice ?: return null
        return value.toStringUtf8().takeIf { it.indexOf('\u0000') < 0 }
    }

    private fun quoteLiteral(text: String): String = "'" + text.replace("'", "''") + "'"

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
        val inner = translateOrNull(operand, assignments, session, ctx) ?: return null
        if (operand.type is DateType && call.type is VarcharType && (call.type as VarcharType).isUnbounded) {
            return DuckBridgeDateExpressions.toVarchar(inner)
        }
        return "$castKeyword($inner AS $targetType)"
    }

    /**
     * Source/target pairs whose CAST / TRY_CAST results are identical in both engines. Excluded:
     *  - string → anything else: DuckDB's parsers are more lenient ('1.0'→INTEGER 1,
     *    '2020/01/01'→DATE, 'yes'→BOOLEAN) where Trino rejects.
     *  - DOUBLE/REAL → VARCHAR: Java `Double.toString` vs DuckDB formatting (1.0E7 vs 10000000.0).
     *  - TIMESTAMP/TIME/DECIMAL-family → VARCHAR: precision digits and trailing zeros differ.
     *    Unbounded DATE text uses an explicit astronomical-year formatter rather than DuckDB's BC spelling.
     */
    private fun castSemanticsAlign(source: Type, target: Type): Boolean {
        val sourceIsString = source is VarcharType || source is CharType
        if (sourceIsString) {
            return target is VarcharType
        }
        if (target is VarcharType) {
            return isIntegerFamily(source) || source is BooleanType || source is DateType
        }
        return true
    }

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
            StandardFunctions.ADD_FUNCTION_NAME -> "+"
            StandardFunctions.SUBTRACT_FUNCTION_NAME -> "-"
            StandardFunctions.MULTIPLY_FUNCTION_NAME -> "*"
            // $divide and $modulo are not pushed: Trino integer `/` truncates (5/2=2) where DuckDB
            // does true division (2.5), and division by zero throws in Trino but yields
            // Infinity/NULL in DuckDB, silently dropping the row.
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
        return translateAll(args, assignments, session, ctx)?.joinToString(separator, "(", ")")
    }

    fun interface ArgTypeGate {
        fun accepts(args: List<ConnectorExpression>, session: ConnectorSession?): Boolean
    }

    data class NameArity(val name: String, val arity: Int)

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
}
