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

import dev.brikk.duckbridge.trino.plugin.DuckBridgeExpressionTranslator.NameArity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement

class TestTrinoFunctionAliases {

    @Test
    @Throws(Exception::class)
    fun testAliasSetEqualsMeta() {
        openConnectionWithExtension().use { conn ->
            conn.createStatement().use { stmt ->
                val meta = readMeta(stmt)
                val aliasSet =
                    DuckBridgeExpressionTranslator.ALIAS_FUNCTIONS
                        .map { MetaEntry(it.name, it.arity) }
                        .toHashSet()
                assertThat(meta)
                    .`as`("trino_meta() must catalog exactly the translator's ALIAS set (10 natives)")
                    .isEqualTo(aliasSet)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAliasSetIsExactlyTheTenNatives() {
        val expected =
            setOf(
                NameArity("lower", 1),
                NameArity("upper", 1),
                NameArity("reverse", 1),
                NameArity("trim", 1),
                NameArity("ltrim", 1),
                NameArity("rtrim", 1),
                NameArity("normalize", 1),
                NameArity("xxhash64", 1),
                NameArity("sha512", 1),
                NameArity("hmac_sha256", 2),
            )
        assertThat(DuckBridgeExpressionTranslator.ALIAS_FUNCTIONS).isEqualTo(expected)
    }

    @Test
    @Throws(Exception::class)
    fun testEveryNonAliasEntryHasAFixture() {
        val nonAlias =
            DuckBridgeExpressionTranslator.EMISSION_STRATEGIES
                .filterValues { it !is DuckBridgeExpressionTranslator.Emission.Alias }
                .keys
        val covered = SemanticFixtures.all().filterIsInstance<SemanticFixtures.Fixture>().map { NameArity(it.name, it.arity) }.toSet()
        assertThat(covered)
            .`as`("every Bare/Rename/Operator/Inline entry must carry a semantic fixture")
            .containsAll(nonAlias)
    }


    @Test
    fun newDuckDbJsonFunctionsAreNotAutomaticallyPredicateAliases() {
        val names = setOf(
            "json_set", "json_insert", "json_replace", "json_remove",
            "json_merge_patch_diff", "json_deep_merge", "json_normalize", "json_strip_nulls",
        )
        assertThat(DuckBridgeExpressionTranslator.PUSHABLE_FUNCTIONS)
            .noneMatch { it.name in names }
    }

    @Test
    @Throws(Exception::class)
    fun testRepresentativeAliasSemantics() {
        openConnectionWithExtension().use { conn ->
            conn.createStatement().use { stmt ->
                // Trino uses simple per-code-point case mapping through SliceUtf8 / Character.toUpperCase(int),
                // so upper('ß') stays 'ß' and lower('İ') is a bare 'i'.
                assertThat(scalar(stmt, "SELECT trino_lower('HeLLo')")).isEqualTo("hello")
                assertThat(scalar(stmt, "SELECT trino_upper('ß')")).isEqualTo("ß")
                assertThat(scalar(stmt, "SELECT trino_upper('straße')")).isEqualTo("STRAßE")
                assertThat(scalar(stmt, "SELECT trino_lower(chr(304) || 'stanbul')")).isEqualTo("istanbul")
                assertThat(scalar(stmt, "SELECT trino_lower('ΟΔΥΣΣΕΥΣ')")).isEqualTo("οδυσσευσ")
                assertThat(scalar(stmt, "SELECT trino_upper(chr(64257))")).isEqualTo("\uFB01")
                assertThat(scalar(stmt, "SELECT trino_reverse('abc')")).isEqualTo("cba")
                assertThat(scalar(stmt, "SELECT trino_lower(NULL)")).isNull()
            }
        }
    }

    private data class MetaEntry(val name: String, val arity: Int)

    companion object {
        @Throws(SQLException::class)
        private fun openConnectionWithExtension(): Connection {
            val extension =
                TrinoParityExtensionResolver.resolveBundledExtension()
                    ?: throw AssertionError(
                        "trino_parity extension not bundled in plugin jar on this platform — build it first: " +
                            "`(cd duckdb-trino-parity-extension && make)`.",
                    )
            val props = DuckBridgeQueryRunner.duckJdbcProperties()
            val conn = DriverManager.getConnection("jdbc:duckdb:", props)
            TrinoFunctionAliases.loadBundled(conn, extension)
            return conn
        }

        @Throws(SQLException::class)
        private fun readMeta(stmt: Statement): Set<MetaEntry> {
            val meta = HashSet<MetaEntry>()
            stmt.executeQuery("SELECT trino_name, arg_count FROM trino_meta() ORDER BY trino_name, arg_count").use { rs ->
                while (rs.next()) {
                    meta.add(MetaEntry(rs.getString(1), rs.getInt(2)))
                }
            }
            return meta
        }

        @Throws(SQLException::class)
        private fun scalar(stmt: Statement, sql: String): Any? {
            stmt.executeQuery(sql).use { rs ->
                assertThat(rs.next()).`as`("query produced no rows: %s", sql).isTrue()
                return rs.getObject(1)
            }
        }
    }
}
