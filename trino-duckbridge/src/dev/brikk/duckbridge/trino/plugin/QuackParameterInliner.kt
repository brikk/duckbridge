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
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.SmallintType.SMALLINT
import io.trino.spi.type.TinyintType.TINYINT
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType
import java.time.LocalDate

/**
 * Inlines base-jdbc's [PreparedQuery] parameters into a literal SQL string for the T2 QUACK engine.
 *
 * `quack_query_by_name(catalog, sql)` takes a literal SQL string with no parameter binding, so the
 * `?` placeholders base-jdbc emits for pushed-down predicates must be rendered as DuckDB literals
 * before the query is shipped server-side.
 *
 * **Fail loud, never silently wrong.** Only types with an unambiguous DuckDB literal form are
 * rendered; anything else throws (the caller can fall back to `execution-engine=JDBC`). This keeps a
 * wrong-literal from ever producing wrong rows. The type set intentionally starts small and grows as
 * we prove each rendering against the parity suite.
 */
object QuackParameterInliner {
    /** Render [prepared] to a literal SQL string. No-op fast path when there are no parameters. */
    fun inline(prepared: PreparedQuery): String {
        val params = prepared.parameters()
        if (params.isEmpty()) {
            return prepared.query()
        }
        val literals = params.map { renderLiteral(it) }
        return substitutePlaceholders(prepared.query(), literals)
    }

    private fun renderLiteral(param: QueryParameter): String {
        val value = param.value.orElse(null) ?: return "NULL"
        val type = param.type
        return when {
            type == BIGINT || type == INTEGER || type == SMALLINT || type == TINYINT -> (value as Long).toString()
            type == BOOLEAN -> if (value as Boolean) "TRUE" else "FALSE"
            type == DOUBLE -> renderDouble(value as Double)
            type == DATE -> "DATE '" + LocalDate.ofEpochDay(value as Long).toString() + "'"
            type is VarcharType || type is CharType -> renderString(value)
            else -> throw unsupported(type)
        }
    }

    private fun renderString(value: Any): String {
        val text =
            when (value) {
                is Slice -> value.toStringUtf8()
                is String -> value
                else -> throw TrinoException(
                    NOT_SUPPORTED,
                    "QUACK T2: cannot inline a string parameter of runtime type ${value.javaClass.name}",
                )
            }
        return "'" + text.replace("'", "''") + "'"
    }

    private fun renderDouble(d: Double): String {
        if (d.isNaN() || d.isInfinite()) {
            throw TrinoException(NOT_SUPPORTED, "QUACK T2: cannot inline a non-finite DOUBLE parameter ($d)")
        }
        return d.toString()
    }

    private fun unsupported(type: Type) =
        TrinoException(
            NOT_SUPPORTED,
            "QUACK T2 (execution-engine=QUACK) cannot yet inline a pushed-down predicate parameter of type '$type' " +
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
                        throw TrinoException(NOT_SUPPORTED, "QUACK T2: more '?' placeholders than parameters while inlining")
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
                "QUACK T2: rendered query consumed $next of ${literals.size} parameters — placeholder/parameter mismatch",
            )
        }
        return sb.toString()
    }
}
