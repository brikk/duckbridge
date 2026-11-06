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
import io.trino.plugin.jdbc.PreparedQuery
import io.trino.plugin.jdbc.QueryParameter
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.CharType
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.DecimalType
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.Int128
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.RealType.REAL
import io.trino.spi.type.SmallintType.SMALLINT
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TinyintType.TINYINT
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Inlines base-jdbc's [PreparedQuery] parameters as DuckDB literals, because `quack_query_by_name`
 * takes a literal SQL string with no parameter binding. Only types with an unambiguous DuckDB
 * literal form are rendered; anything else throws rather than risk a wrong literal.
 */
object QuackParameterInliner {
    fun inline(prepared: PreparedQuery): String {
        val params = prepared.parameters()
        if (params.isEmpty()) {
            return prepared.query()
        }
        val literals = params.map { renderLiteral(it) }
        return substitutePlaceholders(prepared.query(), literals)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun renderLiteral(param: QueryParameter): String {
        val value = param.value.orElse(null) ?: return "NULL"
        val type = param.type
        return when {
            type == BIGINT || type == INTEGER || type == SMALLINT || type == TINYINT -> (value as Long).toString()
            type == BOOLEAN -> if (value as Boolean) "TRUE" else "FALSE"
            type == DOUBLE -> renderDouble(value as Double)
            type == REAL -> renderReal(value as Long)
            type == DATE -> "DATE '" + LocalDate.ofEpochDay(value as Long).toString() + "'"
            type is DecimalType -> renderDecimal(type, value)
            type is TimestampType -> renderTimestamp(type, value)
            type is VarcharType || type is CharType -> renderString(value)
            else -> throw unsupported(type)
        }
    }

    /** REAL native value is the float's int bits packed in a long (Trino's REAL javaType). */
    private fun renderReal(intBits: Long): String {
        val f = Float.fromBits(intBits.toInt())
        if (f.isNaN()) {
            throw TrinoException(NOT_SUPPORTED, "QUACK execution engine: cannot inline a NaN REAL parameter")
        }
        return "CAST('$f' AS REAL)"
    }

    /** Short decimals arrive as an unscaled long; long decimals as an [Int128]. */
    private fun renderDecimal(type: DecimalType, value: Any): String {
        val decimal =
            if (type.isShort) {
                BigDecimal.valueOf(value as Long, type.scale)
            } else {
                BigDecimal((value as Int128).toBigInteger(), type.scale)
            }
        return "CAST('${decimal.toPlainString()}' AS DECIMAL(${type.precision}, ${type.scale}))"
    }

    /**
     * Short TIMESTAMP (precision ≤ 6) arrives as epoch-microseconds of the session-independent wall
     * clock; render it as a zone-free DuckDB `TIMESTAMP` literal. Sub-microsecond precision (> 6)
     * arrives as a `LongTimestamp` object and is not inlined (fail loud).
     */
    private fun renderTimestamp(type: TimestampType, value: Any): String {
        if (!type.isShort) {
            throw TrinoException(
                NOT_SUPPORTED,
                "QUACK execution engine: cannot inline a TIMESTAMP($type) parameter with sub-microsecond precision",
            )
        }
        val micros = value as Long
        val seconds = Math.floorDiv(micros, MICROS_PER_SECOND)
        val microOfSecond = Math.floorMod(micros, MICROS_PER_SECOND)
        val wallClock = LocalDateTime.ofEpochSecond(seconds, (microOfSecond * NANOS_PER_MICRO).toInt(), ZoneOffset.UTC)
        val base = wallClock.format(TIMESTAMP_FORMAT)
        // Explicit seconds always (LocalDateTime.toString drops :00); micros only when present.
        val text = if (microOfSecond == 0L) base else "$base." + microOfSecond.toString().padStart(6, '0').trimEnd('0')
        return "TIMESTAMP '$text'"
    }

    private fun renderString(value: Any): String {
        val text =
            when (value) {
                is Slice -> value.toStringUtf8()
                is String -> value
                else -> throw TrinoException(
                    NOT_SUPPORTED,
                    "QUACK execution engine: cannot inline a string parameter of runtime type ${value.javaClass.name}",
                )
            }
        return "'" + text.replace("'", "''") + "'"
    }

    private fun renderDouble(d: Double): String {
        if (d.isNaN()) {
            throw TrinoException(NOT_SUPPORTED, "QUACK execution engine: cannot inline a NaN DOUBLE parameter")
        }
        if (d.isInfinite()) return "CAST('$d' AS DOUBLE)"
        return d.toString()
    }

    private fun unsupported(type: Type) =
        TrinoException(
            NOT_SUPPORTED,
            "The QUACK execution engine cannot yet inline a pushed-down predicate parameter of type '$type' " +
                "into the quack_query_by_name literal. Run this query with execution-engine=JDBC (the default), or " +
                "add a proven rendering for this type in QuackParameterInliner.",
        )

    /**
     * Replace each `?` outside a single-quoted string literal with the next rendered literal. Tracks
     * single-quote state (DuckDB escapes an embedded quote as `''`, which this toggle handles) so a
     * `?` inside a string constant the translator emitted is never mistaken for a placeholder.
     */
    private fun substitutePlaceholders(query: String, literals: List<String>): String {
        val sb = StringBuilder(query.length + literals.sumOf { it.length })
        var inQuote = false
        var next = 0
        for (c in query) {
            when {
                c == '\'' -> {
                    sb.append(c)
                    inQuote = !inQuote
                }
                c == '?' && !inQuote -> {
                    if (next >= literals.size) {
                        throw TrinoException(NOT_SUPPORTED, "QUACK execution engine: more '?' placeholders than parameters while inlining")
                    }
                    sb.append(literals[next])
                    next++
                }
                else -> sb.append(c)
            }
        }
        if (next != literals.size) {
            throw TrinoException(
                NOT_SUPPORTED,
                "QUACK execution engine: rendered query consumed $next of ${literals.size} parameters — placeholder/parameter mismatch",
            )
        }
        return sb.toString()
    }

    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000L
    private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
