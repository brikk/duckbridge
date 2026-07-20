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

import io.airlift.slice.Slices
import io.trino.plugin.jdbc.PreparedQuery
import io.trino.plugin.jdbc.QueryParameter
import io.trino.spi.TrinoException
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.TimestampType.TIMESTAMP_MICROS
import io.trino.spi.type.VarcharType.VARCHAR
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional

class TestQuackParameterInliner {
    private fun param(type: io.trino.spi.type.Type, value: Any?): QueryParameter =
        QueryParameter(type, Optional.ofNullable(value))

    @Test
    fun noParametersReturnsQueryUnchanged() {
        val q = PreparedQuery("SELECT * FROM \"memory\".\"s\".\"t\"", emptyList())
        assertThat(QuackParameterInliner.inline(q)).isEqualTo("SELECT * FROM \"memory\".\"s\".\"t\"")
    }

    @Test
    fun inlinesIntegerAndBooleanAndDouble() {
        val q =
            PreparedQuery(
                "SELECT * FROM t WHERE a >= ? AND b = ? AND c < ?",
                listOf(param(BIGINT, 3L), param(BOOLEAN, true), param(io.trino.spi.type.DoubleType.DOUBLE, 2.5)),
            )
        assertThat(QuackParameterInliner.inline(q))
            .isEqualTo("SELECT * FROM t WHERE a >= 3 AND b = TRUE AND c < 2.5")
    }

    @Test
    fun inlinesVarcharWithQuoteEscaping() {
        val q = PreparedQuery("SELECT * FROM t WHERE name = ?", listOf(param(VARCHAR, Slices.utf8Slice("it's straße"))))
        assertThat(QuackParameterInliner.inline(q)).isEqualTo("SELECT * FROM t WHERE name = 'it''s straße'")
    }

    @Test
    fun inlinesDateAsDuckDbLiteral() {
        val epochDay = LocalDate.of(1990, 5, 1).toEpochDay()
        val q = PreparedQuery("SELECT * FROM t WHERE d = ?", listOf(param(DATE, epochDay)))
        assertThat(QuackParameterInliner.inline(q)).isEqualTo("SELECT * FROM t WHERE d = DATE '1990-05-01'")
    }

    @Test
    fun questionMarkInsideStringLiteralIsNotAPlaceholder() {
        // The parity translator can emit a literal string containing '?'; only the real placeholder
        // (outside the quotes) must be substituted.
        val q = PreparedQuery("SELECT * FROM t WHERE label = 'a?b' AND id = ?", listOf(param(BIGINT, 7L)))
        assertThat(QuackParameterInliner.inline(q)).isEqualTo("SELECT * FROM t WHERE label = 'a?b' AND id = 7")
    }

    @Test
    fun nullValueRendersAsNull() {
        val q = PreparedQuery("SELECT * FROM t WHERE a = ?", listOf(param(BIGINT, null)))
        assertThat(QuackParameterInliner.inline(q)).isEqualTo("SELECT * FROM t WHERE a = NULL")
    }

    @Test
    fun unsupportedTypeFailsLoud() {
        val q = PreparedQuery("SELECT * FROM t WHERE ts = ?", listOf(param(TIMESTAMP_MICROS, 0L)))
        assertThatThrownBy { QuackParameterInliner.inline(q) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("cannot yet inline")
    }
}
