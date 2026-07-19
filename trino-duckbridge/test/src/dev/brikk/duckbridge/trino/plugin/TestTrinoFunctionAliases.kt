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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties

/**
 * SQL-level acceptance + parity tests for the emission-class rework, run against the REAL built
 * `trino_parity.duckdb_extension` binary (resolved and extracted by [TrinoParityExtensionResolver],
 * the same path the production client uses).
 *
 * Two obligations, post "alias only what diverges":
 *
 *  (a) [testAliasSetIsSubsetOfMeta] — ALIAS LOCKSTEP: the translator's [Emission.Alias] subset (the
 *      10 native-C++ divergence-fixers that must route through the extension) must be a SUBSET of the
 *      loaded binary's `trino_meta()` catalog. It is `⊆`, not `==`, on purpose: the extension still
 *      ships all ~95 `trino_<name>` macros/functions; a follow-up will shrink it to just the 10 and
 *      re-pin equality (see the TODO on that test).
 *
 *  (b) [nonAliasSemanticFixtures] — SEMANTIC FIXTURES: every non-ALIAS entry
 *      (Bare/Rename/Operator/Inline) has at least one fixture that TRANSLATES a Trino
 *      [io.trino.spi.expression.Call] through the production [DuckBridgeExpressionTranslator], then
 *      evaluates the emitted DuckDB SQL against embedded DuckDB and compares it to the Trino-side
 *      expected value. These are the canary that re-proves native alignment on DuckDB pin bumps —
 *      if a bare/rename/inline built-in ever drifts from Trino, its fixture goes red.
 */
class TestTrinoFunctionAliases {
    // ---- (a) ALIAS lockstep -------------------------------------------------

    @Test
    @Throws(Exception::class)
    fun testAliasSetIsSubsetOfMeta() {
        openConnectionWithExtension().use { conn ->
            conn.createStatement().use { stmt ->
                val meta = readMeta(stmt)
                val aliasSet =
                    DuckBridgeExpressionTranslator.ALIAS_FUNCTIONS
                        .map { MetaEntry(it.name, it.arity) }
                        .toHashSet()
                // TODO(parity-extension shrink): the extension still ships all ~95 trino_<name>
                // entries. When it drops the 85 non-ALIAS macros (version bump), re-pin this to
                // `isEqualTo(meta)` so a stale extra macro becomes a hard failure again.
                assertThat(meta)
                    .`as`("every ALIAS-class entry must be backed by a trino_<name> in trino_meta()")
                    .containsAll(aliasSet)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAliasSetIsExactlyTheTenNatives() {
        // The keep-list handed to the parity-extension maintainers: exactly these 10 native C++
        // entries route through the extension. If this drifts, the ANSWER doc + README are stale.
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
        val covered = SemanticFixtures.all().map { NameArity(it.name, it.arity) }.toSet()
        assertThat(covered)
            .`as`("every Bare/Rename/Operator/Inline entry must carry a semantic fixture")
            .containsAll(nonAlias)
    }

    // ---- (b) semantic fixtures ---------------------------------------------

    @TestFactory
    @Throws(Exception::class)
    fun nonAliasSemanticFixtures(): List<DynamicTest> {
        val conn = openConnectionWithExtension()
        val stmt = conn.createStatement()
        return SemanticFixtures.all().map { fx ->
            DynamicTest.dynamicTest("${fx.name}/${fx.arity} :: ${fx.label}") {
                val sql = fx.emittedSql()
                assertThat(sql)
                    .`as`("fixture %s/%d [%s] must be pushable (translator returned SQL)", fx.name, fx.arity, fx.label)
                    .isNotNull()
                val actual = scalar(stmt, fx.query(sql!!))
                fx.assertMatches(actual)
            }
        }
    }

    // ---- representative extension-macro semantics (loaded binary sanity) ----

    @Test
    @Throws(Exception::class)
    fun testRepresentativeAliasSemantics() {
        openConnectionWithExtension().use { conn ->
            conn.createStatement().use { stmt ->
                // The ALIAS class is where DuckDB's built-in diverges; prove the loaded binary
                // actually evaluates the Trino-aligned semantics (ICU full case folding, code-point
                // reverse, NULL propagation).
                assertThat(scalar(stmt, "SELECT trino_lower('HeLLo')")).isEqualTo("hello")
                assertThat(scalar(stmt, "SELECT trino_upper('ß')")).isEqualTo("SS")
                assertThat(scalar(stmt, "SELECT trino_reverse('abc')")).isEqualTo("cba")
                assertThat(scalar(stmt, "SELECT trino_lower(NULL)")).isNull()
            }
        }
    }

    private data class MetaEntry(val name: String, val arity: Int)

    companion object {
        @Throws(SQLException::class)
        private fun openConnectionWithExtension(): Connection {
            val path =
                TrinoParityExtensionResolver.resolveBundledExtensionPath()
                    ?: throw AssertionError(
                        "trino_parity extension not bundled in plugin jar on this platform — build it first: " +
                            "`(cd duckdb-trino-parity-extension && make)`.",
                    )
            val props = Properties()
            props.setProperty("allow_unsigned_extensions", "true")
            val conn = DriverManager.getConnection("jdbc:duckdb:", props)
            TrinoFunctionAliases.loadInProcess(conn, path)
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
