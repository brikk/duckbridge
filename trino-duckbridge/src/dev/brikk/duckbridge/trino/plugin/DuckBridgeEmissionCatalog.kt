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

import dev.brikk.duckbridge.trino.plugin.DuckBridgeExpressionTranslator.ArgTypeGate
import dev.brikk.duckbridge.trino.plugin.DuckBridgeExpressionTranslator.Emission
import dev.brikk.duckbridge.trino.plugin.DuckBridgeExpressionTranslator.NameArity
import io.airlift.slice.Slice
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.type.BigintType
import io.trino.spi.type.DateType
import io.trino.spi.type.DoubleType
import io.trino.spi.type.RealType
import io.trino.spi.type.IntegerType
import io.trino.spi.type.SmallintType
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.TinyintType
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType

/**
 * Which Trino `(name, arity)` pairs push, how each is emitted ([Emission]), and the argument gates
 * ([ArgTypeGate]) that restrict a push to the shapes where DuckDB matches Trino. Only
 * [Emission.Alias] entries need the `trino_parity` extension; the rest stay pushable without it.
 */
internal object DuckBridgeEmissionCatalog {
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
            // url_encode / url_decode are NOT pushed: Trino uses application/x-www-form-urlencoded
            // rules (space→'+', '*' kept, '~' encoded) while DuckDB is RFC 3986 percent-encoding —
            // url_encode('a b*~') is 'a+b*%7E' in Trino, 'a%20b%2A~' in DuckDB; url_decode('a+b') is 'a b'
            // vs 'a+b'.
            put(NameArity("to_base64", 1), Emission.Bare)
            // from_base64 is NOT pushed: Trino accepts unpadded input ('YWI'), DuckDB errors.
            // Numeric / math
            put(NameArity("abs", 1), Emission.Bare)
            put(NameArity("ceil", 1), Emission.Bare)
            put(NameArity("floor", 1), Emission.Bare)
            // mod/2 is Bare but gated (TYPE_GATES) to a non-zero constant divisor: Trino throws
            // DIVISION_BY_ZERO where DuckDB returns NULL, which would silently drop the row.
            put(NameArity("mod", 2), Emission.Bare)
            // power is NOT pushed: native pow differs from Java Math.pow on special values and
            // finite rounding, and a shared NaN result still changes DuckDB/Trino WHERE comparisons.
            // sqrt / ln / log2 / log10 / asin / acos are NOT pushed: Trino returns IEEE
            // NaN/-Infinity outside the domain where DuckDB throws, but wrapping in CASE is still
            // unsafe in a predicate because DuckDB and Trino's runtime filter path order NaN
            // differently. No context-free scalar rewrite preserves every comparison.
            put(NameArity("exp", 1), Emission.Bare)
            put(NameArity("sin", 1), Emission.Bare)
            put(NameArity("cos", 1), Emission.Bare)
            put(NameArity("tan", 1), Emission.Bare)
            // atan / atan2 / cosh stay local: finite native rounding changes exact predicates.
            put(NameArity("sinh", 1), Emission.Bare)
            put(NameArity("tanh", 1), Emission.Bare)
            put(NameArity("degrees", 1), Emission.Bare)
            put(NameArity("radians", 1), Emission.Bare)
            // cbrt is NOT pushed: Java's Math.cbrt is exact for perfect cubes (cbrt(-27) = -3)
            // where DuckDB's yields -3.0000000000000004 — an equality predicate would miss the row.
            put(NameArity("sign", 1), Emission.Contextual { rendered, args, _ ->
                val value = rendered[0]
                when (args[0].type) {
                    is DoubleType -> floatingSign(value, "DOUBLE")
                    is RealType -> floatingSign(value, "FLOAT")
                    else -> "sign($value)"
                }
            })
            put(NameArity("pi", 0), Emission.Bare)
            // Regex. All regex entries are gated (TYPE_GATES) to a constant pattern that passes the
            // RE2-safe allowlist: Trino compiles with Joni (Java syntax), DuckDB with RE2, and
            // beyond the shared core the engines silently disagree (e.g. `$` matching before a
            // trailing newline) or RE2 rejects the pattern outright (lookaround, backreferences).
            // regexp_extract: Trino returns NULL on no match, DuckDB returns '' — wrap in a
            // match guard so a genuine empty match still yields '' and a non-match yields NULL.
            put(NameArity("regexp_extract", 2), Emission.Inline { a -> guardedRegexpExtract(a[0], a[1], group = null) })
            put(NameArity("regexp_extract", 3), Emission.Inline { a -> guardedRegexpExtract(a[0], a[1], group = a[2]) })
            // Count only the qualified, non-nullable ASCII-atom grammar. The broader RE2-safe
            // match gate is not a proof of repeated/zero-width match iteration semantics.
            put(NameArity("regexp_count", 2), Emission.Inline { a -> "length(regexp_extract_all(${a[0]}, ${a[1]}))" })
            // Date / time
            put(NameArity("year", 1), Emission.Contextual { rendered, args, _ ->
                DuckBridgeTimestampTruncation.year(rendered[0], args[0])
            })
            put(NameArity("month", 1), Emission.Bare)
            put(NameArity("day", 1), Emission.Bare)
            put(NameArity("quarter", 1), Emission.Bare)
            // Zoned time fields preserve the input offset; DATE/calendar-field parity is separate.
            put(NameArity("date_trunc", 2), Emission.Contextual { rendered, args, _ ->
                DuckBridgeTimestampTruncation.render(rendered, args)
            })
            // date_diff: Trino counts COMPLETE units elapsed (Joda getDifference); DuckDB's date_diff
            // counts partition BOUNDARIES crossed (date_diff('month', 2020-01-31, 2020-02-01) = 1 vs
            // Trino 0). DuckDB's date_sub is the complete-units form. Unit list gated to the
            // names both engines accept (TYPE_GATES).
            put(NameArity("date_diff", 3), Emission.Rename("date_sub"))
            put(NameArity("week", 1), Emission.Bare)
            put(NameArity("hour", 1), Emission.Bare)
            put(NameArity("minute", 1), Emission.Bare)
            put(NameArity("second", 1), Emission.Bare)

            // ---- RENAME: a different bare DuckDB built-in name ---------------------------------
            put(NameArity("to_hex", 1), Emission.Rename("hex"))
            // from_hex is NOT pushed: Trino errors on odd-length input ('abc'), DuckDB
            // left-pads a nibble ('0ABC').
            // levenshtein_distance / hamming_distance are NOT pushed: DuckDB's levenshtein /
            // hamming operate on BYTES (levenshtein('äö','ab') = 4, hamming errors on the byte-length
            // mismatch) where Trino counts code points (2).
            put(NameArity("truncate", 1), Emission.Rename("trunc"))
            put(NameArity("regexp_like", 2), Emission.Rename("regexp_matches"))
            put(NameArity("day_of_year", 1), Emission.Rename("dayofyear"))
            put(NameArity("last_day_of_month", 1), Emission.Rename("last_day"))
            put(NameArity("week_of_year", 1), Emission.Rename("week"))
            put(NameArity("from_unixtime", 1), Emission.Rename("to_timestamp"))
            // A rename, not an operator: DuckDB has no infix XOR operator.
            put(NameArity("bitwise_xor", 2), Emission.Rename("xor"))

            // ---- OPERATOR: parenthesized infix / prefix operator -------------------------------
            put(NameArity("bitwise_and", 2), Emission.Operator.infix("&"))
            put(NameArity("bitwise_or", 2), Emission.Operator.infix("|"))
            // bitwise_left_shift / bitwise_right_shift are NOT pushed: Trino's
            // bitwise_right_shift is a LOGICAL zero-fill shift (bitwise_right_shift(-8, 1) =
            // 9223372036854775804) while DuckDB's `>>` is arithmetic (-4); Trino's left shift wraps
            // to the operand width and yields 0 for shift >= width where DuckDB throws.
            put(NameArity("bitwise_not", 1), Emission.Operator.prefix("~"))

            // ---- INLINE: fixed SQL transform templates ----------------------------------------
            // regexp_replace: force the 'g' flag to match Trino's global default.
            put(NameArity("regexp_replace", 2), Emission.Inline { a -> "regexp_replace(${a[0]}, ${a[1]}, '', 'g')" })
            put(NameArity("regexp_replace", 3), Emission.Inline { a -> "regexp_replace(${a[0]}, ${a[1]}, ${a[2]}, 'g')" })
            // DuckDB md5/sha1/sha256 return hex VARCHAR; Trino returns VARBINARY.
            put(NameArity("md5", 1), Emission.Inline { a -> "unhex(md5(${a[0]}))" })
            put(NameArity("sha1", 1), Emission.Inline { a -> "unhex(sha1(${a[0]}))" })
            put(NameArity("sha256", 1), Emission.Inline { a -> "unhex(sha256(${a[0]}))" })
            put(NameArity("if", 2), Emission.Inline { a -> "if(${a[0]}, ${a[1]}, NULL)" })
            put(NameArity("if", 3), Emission.Bare)
            // Trino's day_of_week is ISO (Mon=1..Sun=7); year_of_week/yow is the ISO-week-numbering year.
            put(NameArity("day_of_week", 1), Emission.Inline { a -> "isodow(${a[0]})" })
            put(NameArity("year_of_week", 1), Emission.Inline { a -> "CAST(extract('isoyear' FROM ${a[0]}) AS BIGINT)" })
            put(NameArity("yow", 1), Emission.Inline { a -> "CAST(extract('isoyear' FROM ${a[0]}) AS BIGINT)" })
            // Trino's millisecond is millis-of-second; DuckDB's 'millisecond' part is
            // millis-of-minute (seconds*1000 + ms), so reduce mod 1000.
            put(NameArity("millisecond", 1), Emission.Inline { a -> "CAST(extract('millisecond' FROM ${a[0]}) % 1000 AS BIGINT)" })
            // For a naive TIMESTAMP Trino interprets the value in the session zone; DuckDB's
            // epoch(TIMESTAMP) treats it as UTC and ignores SET TimeZone. Re-anchor with
            // timezone(<session zone>, ts) first. TIMESTAMP WITH TIME ZONE is already an instant.
            put(
                NameArity("to_unixtime", 1),
                Emission.Contextual { rendered, args, session ->
                    when (args[0].type) {
                        is TimestampWithTimeZoneType -> "CAST(epoch(${rendered[0]}) AS DOUBLE)"
                        else -> sessionZoneLiteral(session)?.let { zone -> "CAST(epoch(timezone($zone, ${rendered[0]})) AS DOUBLE)" }
                    }
                },
            )
            // WITH_TIMEZONE is an internal typed constructor, not a context-free native value.
            // DuckBridgeWithTimezone lowers qualified fixed-offset consumers; other uses stay local.
            if (DuckBridgeTrinoTarget.VERSION >= 484) remove(NameArity("to_unixtime", 1))
        }

    /** Per-entry argument gates; entries without a gate accept any arguments. */
    val TYPE_GATES: Map<NameArity, ArgTypeGate> = buildTypeGates()

    private fun buildTypeGates(): Map<NameArity, ArgTypeGate> {
        val gates: MutableMap<NameArity, ArgTypeGate> = mutableMapOf()
        // DATE and TIMESTAMP always; TIMESTAMP WITH TIME ZONE only when the session sets
        // pushdown_timestamp_with_timezone = true.
        val arg0Tier = argTier(0)
        for (name in listOf("year", "month", "day", "quarter", "hour", "minute", "second", "millisecond")) {
            gates[NameArity(name, 1)] = arg0Tier
        }
        gates.putAll(epochGates(arg0Tier))
        // Native DuckDB accepts units Trino rejects. Preserve local validation for invalid/dynamic units.
        gates[NameArity("date_trunc", 2)] = dateTruncGate()
        // DATE only.
        val arg0DateStrict = arg(0, DateType::class.java)
        for (name in listOf("day_of_week", "day_of_year", "last_day_of_month", "week", "week_of_year", "year_of_week", "yow")) {
            gates[NameArity(name, 1)] = arg0DateStrict
        }

        // lpad/rpad: push ONLY when the pad argument (arg 2) is a constant, non-empty varchar AND the
        // size (arg 1) is a constant ≥ 0. Trino raises on an empty pad string and on a negative size;
        // DuckDB pads with nothing / returns ''. Non-constant arguments could hit either at runtime.
        val constNonEmptyPad = constNonEmptyVarcharArg(2)
        val constSizeAtLeastZero = constIntArgAtLeast(1, 0)
        val padGate = ArgTypeGate { args, session -> constNonEmptyPad.accepts(args, session) && constSizeAtLeastZero.accepts(args, session) }
        gates[NameArity("lpad", 3)] = padGate
        gates[NameArity("rpad", 3)] = padGate

        // substring/{2,3}: DuckDB treats start=0 as start=1, Trino differs. Push ONLY when the start
        // argument (arg 1) is a constant integer ≥ 1, which is the range both engines align on; for
        // substring/3 the length (arg 2) must also be a constant ≥ 0 — Trino returns '' for a negative
        // length where DuckDB returns a slice ('hello', 2, -1 → 'h').
        val constStartAtLeastOne = constIntArgAtLeast(1, 1)
        val constLengthAtLeastZero = constIntArgAtLeast(2, 0)
        gates[NameArity("substring", 2)] = constStartAtLeastOne
        gates[NameArity("substring", 3)] =
            ArgTypeGate { args, session -> constStartAtLeastOne.accepts(args, session) && constLengthAtLeastZero.accepts(args, session) }

        // replace/3: the search string (arg 1) must be a constant, non-empty varchar. Trino's
        // replace('abc', '', 'x') inserts between every character ('xaxbxcx'); DuckDB is a no-op.
        gates[NameArity("replace", 3)] = constNonEmptyVarcharArg(1)

        // mod/2: divisor must be a non-zero integer constant — Trino throws on a zero divisor,
        // DuckDB yields NULL and the row would silently vanish. A column divisor could be 0 at runtime.
        gates[NameArity("mod", 2)] =
            ArgTypeGate { args, _ ->
                val d = args.getOrNull(1)
                d is Constant && isIntegerFamily(d.type) && (d.value as? Long)?.let { it != 0L } == true
            }

        // concat_ws: every argument must be VARCHAR. Trino's concat_ws(sep, ARRAY[...]) overload
        // joins the elements ('a,b'); DuckDB would stringify the LIST ('[a, b]').
        val allVarchar = ArgTypeGate { args, _ -> args.all { it.type is VarcharType } }
        for (arity in 2..5) {
            gates[NameArity("concat_ws", arity)] = allVarchar
        }

        // Regex: the pattern (arg 1) must be a constant that passes the RE2-safe
        // allowlist; regexp_replace/3's replacement (arg 2) must be a constant with no `$` or `\`
        // (Trino group refs are `$1`, RE2's are `\1` — a `$` would be emitted literally by DuckDB).
        val re2SafePattern = ArgTypeGate { args, _ -> constVarchar(args, 1)?.let(Re2Safety::isSafe) == true }
        gates[NameArity("regexp_like", 2)] = re2SafePattern
        gates[NameArity("regexp_extract", 2)] = re2SafePattern
        gates[NameArity("regexp_extract", 3)] = re2SafePattern
        gates[NameArity("regexp_count", 2)] = regexpCountGate()
        gates[NameArity("regexp_replace", 2)] = re2SafePattern
        gates[NameArity("regexp_replace", 3)] =
            ArgTypeGate { args, session ->
                re2SafePattern.accepts(args, session) &&
                    constVarchar(args, 2)?.let { r -> r.indexOf('$') < 0 && r.indexOf('\\') < 0 } == true
            }

        // date_diff: unit must be a constant both engines spell identically with complete-unit
        // semantics under DuckDB's date_sub; Trino's long forms ('days') and DuckDB-only parts stay out.
        gates[NameArity("date_diff", 3)] =
            ArgTypeGate { args, session ->
                val inner = argTier(0)
                validTemporalUnit(args, listOf(1, 2)) &&
                    inner.accepts(listOf(args[1]), session) && inner.accepts(listOf(args[2]), session)
            }
        return gates.toMap()
    }

    private val DATE_UNITS: Set<String> = setOf("day", "week", "month", "quarter", "year")
    private fun epochGates(tier: ArgTypeGate): Map<NameArity, ArgTypeGate> =
        if (DuckBridgeTrinoTarget.VERSION < 484) mapOf(NameArity("to_unixtime", 1) to tier) else emptyMap()
    private val TIMESTAMP_UNITS: Set<String> = DATE_UNITS + setOf("millisecond", "second", "minute", "hour")

    private fun dateTruncGate(): ArgTypeGate = ArgTypeGate { args, session ->
        // Native DATE truncation returns TIMESTAMP and cannot preserve the full DATE range/error contract.
        args[1].type !is DateType && argTier(1).accepts(args, session) && validTemporalUnit(args, listOf(1))
    }

    private fun floatingSign(value: String, type: String): String =
        "(CASE WHEN isnan($value) OR $value=0 THEN $value ELSE CAST(sign($value) AS $type) END)"

    private fun validTemporalUnit(args: List<ConnectorExpression>, operands: List<Int>): Boolean {
        val units = if (operands.any { args[it].type is DateType }) DATE_UNITS else TIMESTAMP_UNITS
        return constVarchar(args, 0)?.lowercase(java.util.Locale.ROOT) in units
    }

    private fun regexpCountGate(): ArgTypeGate = ArgTypeGate { args, _ ->
        args[0].type is VarcharType && constVarchar(args, 1)?.let(RegexpCountSafety::isSafe) == true
    }

    /** The UTF-8 string of a constant VARCHAR argument at [index], or null if it isn't one. */
    private fun constVarchar(args: List<ConnectorExpression>, index: Int): String? {
        val a = args.getOrNull(index)
        return if (a is Constant && a.type is VarcharType) (a.value as? Slice)?.toStringUtf8() else null
    }

    /** `regexp_extract` that yields NULL (not '') when the pattern does not match — Trino's contract. */
    private fun guardedRegexpExtract(s: String, pattern: String, group: String?): String {
        val extract = if (group == null) "regexp_extract($s, $pattern)" else "regexp_extract($s, $pattern, $group)"
        return "(CASE WHEN regexp_matches($s, $pattern) THEN $extract END)"
    }

    /**
     * The session zone as a quoted DuckDB literal — ONLY for fixed-offset zones (UTC, `+05:00`, ...).
     * Zones with DST are declined (null → the conjunct stays in Trino): in the autumn overlap hour
     * Trino resolves an ambiguous local time to the EARLIER offset (Java `ZoneRules`) while DuckDB's
     * ICU picks the LATER one (2024-11-03 01:30 America/New_York: 1730611800 vs 1730615400),
     * so no zone-name rewrite can be made exact for a DST zone. Also null with no session or when the
     * zone has no DuckDB spelling.
     */
    private fun sessionZoneLiteral(session: ConnectorSession?): String? {
        val id = session?.timeZoneKey?.id ?: return null
        val fixed =
            try {
                java.time.ZoneId.of(id).rules.isFixedOffset
            } catch (@Suppress("SwallowedException") e: java.time.DateTimeException) {
                false
            }
        if (!fixed) {
            return null
        }
        val zone = TrinoTimeZoneNormaliser.normalise(id) ?: return null
        return "'" + zone.replace("'", "''") + "'"
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

    internal fun isIntegerFamily(type: Type): Boolean =
        type is BigintType || type is IntegerType || type is SmallintType || type is TinyintType
}
