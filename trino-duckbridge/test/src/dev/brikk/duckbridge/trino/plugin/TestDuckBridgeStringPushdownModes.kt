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

import io.trino.Session
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Per-mode plan-shape tests for the `duckbridge.string-pushdown.mode` dial. The catalog default is
 * PARITY; each test overrides via the `string_pushdown_mode` catalog session property, proving the
 * override works in both directions.
 *
 * Assertions read the `EXPLAIN (TYPE DISTRIBUTED)` plan text (via `computeActual(session, ...)`)
 * because it exposes exactly the split we care about:
 *  - `constraint on [name]` on the scan  → the string domain was PUSHED remotely.
 *  - `ScanFilterProject[... filterPredicate = ...]` (vs a bare `TableScan`) → a filter is RETAINED.
 *  - `constraints=[ParameterizedExpression[...]]` → a function-shape conjunct was pushed.
 *
 * The behavior matrix (see [DuckBridgeStringPushdownMode] and the probe report):
 *  - NULL_ONLY: varchar equality NOT pushed (filter retained, no constraint); IS NULL pushed.
 *  - GUARDED: varchar equality PUSHED and RETAINED (prefilter + keep); `length(name)=5` fully
 *    pushed; `upper(name)='BOB'` (ALIAS) not pushed; NUL-bearing domain skipped (not pushed).
 *  - BINARY: varchar equality FULLY pushed (bare TableScan, no retained filter); string-returning
 *    BARE function comparison pushed; `lower(name)='x'` (ALIAS) not pushed.
 *  - PARITY (default): ALIAS functions push; varchar equality fully pushes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeStringPushdownModes : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        // Catalog default PARITY (extension bundled + loaded); the session property flips the mode.
        val runner = DuckBridgeQueryRunner.create(DuckBridgeQueryRunner.freshDatabaseUrl())
        runner.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.CATALOG}.${DuckBridgeQueryRunner.SCHEMA}")
        return runner
    }

    @BeforeAll
    fun setUpData() {
        computeActual("CREATE TABLE people (id bigint, name varchar)")
        computeActual(
            """
            INSERT INTO people VALUES
                (1, 'Alice'),
                (2, 'bob'),
                (3, 'CAROL'),
                (4, 'straße'),
                (5, NULL)
            """.trimIndent(),
        )
    }

    private fun modeSession(mode: String): Session =
        testSessionBuilder()
            .setCatalog(DuckBridgeQueryRunner.CATALOG)
            .setSchema(DuckBridgeQueryRunner.SCHEMA)
            .setCatalogSessionProperty(DuckBridgeQueryRunner.CATALOG, "string_pushdown_mode", mode)
            .build()

    private fun explain(mode: String, sql: String): String =
        computeActual(modeSession(mode), "EXPLAIN (TYPE DISTRIBUTED) $sql")
            .materializedRows
            .joinToString("\n") { it.getField(0).toString() }

    private fun rows(mode: String, sql: String): List<Long> =
        computeActual(modeSession(mode), sql).materializedRows.map { it.getField(0) as Long }

    /** The scan pushed a domain on the string column. */
    private fun pushedNameConstraint(plan: String): Boolean = plan.contains("constraint on [name]")

    /** A predicate filter is retained above/at the scan (ScanFilterProject carries a filterPredicate). */
    private fun retainedFilter(plan: String): Boolean = plan.contains("filterPredicate =")

    // ---- NULL_ONLY ----------------------------------------------------------

    @Test
    fun nullOnlyDoesNotPushVarcharEquality() {
        val plan = explain("NULL_ONLY", "SELECT id FROM people WHERE name = 'bob'")
        assertThat(pushedNameConstraint(plan)).`as`("NULL_ONLY must not push a string domain").isFalse()
        assertThat(retainedFilter(plan)).`as`("filter stays in Trino").isTrue()
    }

    @Test
    fun nullOnlyPushesIsNull() {
        val plan = explain("NULL_ONLY", "SELECT id FROM people WHERE name IS NULL")
        // IS NULL is exact (no collation hazard) → pushed as a domain, no retained filter.
        assertThat(pushedNameConstraint(plan)).`as`("IS NULL pushes even in NULL_ONLY").isTrue()
        assertThat(retainedFilter(plan)).`as`("no retained filter for pushed IS NULL").isFalse()
    }

    @Test
    fun nullOnlyStillPushesNonStringFunction() {
        // length(name)=5 returns bigint compared to bigint — not a string comparison, pushes always.
        val plan = explain("NULL_ONLY", "SELECT id FROM people WHERE length(name) = 5")
        assertThat(plan).contains("""length("name") = 5""")
        assertThat(retainedFilter(plan)).isFalse()
    }

    // ---- GUARDED ------------------------------------------------------------

    @Test
    fun guardedPushesVarcharEqualityAsRetainedPrefilter() {
        // The whole point of GUARDED: domain pushed remotely AND the exact filter retained locally.
        val plan = explain("GUARDED", "SELECT id FROM people WHERE name = 'bob'")
        assertThat(pushedNameConstraint(plan)).`as`("GUARDED pushes the string domain (pre-filter)").isTrue()
        assertThat(retainedFilter(plan)).`as`("GUARDED retains the exact filter (keep)").isTrue()
    }

    @Test
    fun guardedReturnsCorrectRowsForVarcharEquality() {
        assertThat(rows("GUARDED", "SELECT id FROM people WHERE name = 'bob' ORDER BY id")).containsExactly(2L)
    }

    @Test
    fun guardedSkipsNulBearingDomain() {
        // A NUL-bearing literal domain is skipped entirely (defense-in-depth): no pushed constraint.
        val plan = explain("GUARDED", "SELECT id FROM people WHERE name = U&'a\\0000b'")
        assertThat(pushedNameConstraint(plan)).`as`("GUARDED must skip 0x00-bearing domains").isFalse()
        assertThat(retainedFilter(plan)).isTrue()
    }

    @Test
    fun guardedStillPushesNonStringFunction() {
        val plan = explain("GUARDED", "SELECT id FROM people WHERE length(name) = 5")
        assertThat(plan).contains("""length("name") = 5""")
        assertThat(retainedFilter(plan)).isFalse()
    }

    @Test
    fun guardedDoesNotPushAliasFunction() {
        // upper() is ALIAS (needs PARITY) and string-compares (needs >= BINARY) → not pushed.
        val plan = explain("GUARDED", "SELECT id FROM people WHERE upper(name) = 'BOB'")
        assertThat(plan).doesNotContain("trino_upper")
        assertThat(retainedFilter(plan)).isTrue()
    }

    // ---- BINARY -------------------------------------------------------------

    @Test
    fun binaryFullyPushesVarcharEquality() {
        // No retained filter: byte semantics are probe-verified → bare TableScan with the constraint.
        val plan = explain("BINARY", "SELECT id FROM people WHERE name = 'bob'")
        assertThat(pushedNameConstraint(plan)).isTrue()
        assertThat(retainedFilter(plan)).`as`("BINARY drops the retained filter").isFalse()
    }

    @Test
    fun binaryPushesStringReturningBareFunctionComparison() {
        // replace() is a BARE (extension-free) emission; comparing its VARCHAR result to a literal is a
        // string comparison, allowed at >= BINARY.
        val plan = explain("BINARY", "SELECT id FROM people WHERE replace(name, 'o', '0') = 'b0b'")
        assertThat(plan).contains("""replace("name", 'o', '0') = 'b0b'""")
        assertThat(retainedFilter(plan)).isFalse()
    }

    @Test
    fun binaryDoesNotPushAliasFunction() {
        // lower() is ALIAS → needs PARITY even though BINARY allows string comparison.
        val plan = explain("BINARY", "SELECT id FROM people WHERE lower(name) = 'bob'")
        assertThat(plan).doesNotContain("trino_lower")
        assertThat(retainedFilter(plan)).isTrue()
    }

    @Test
    fun binaryReturnsCorrectRows() {
        assertThat(rows("BINARY", "SELECT id FROM people WHERE name = 'bob' ORDER BY id")).containsExactly(2L)
    }

    // ---- PARITY (default) ---------------------------------------------------

    @Test
    fun parityPushesAliasFunction() {
        // Default catalog mode is PARITY → upper() (ALIAS) fully pushes as trino_upper, no retained filter.
        val plan = explain("PARITY", "SELECT id FROM people WHERE upper(name) = 'BOB'")
        assertThat(plan).contains("trino_upper")
        assertThat(retainedFilter(plan)).isFalse()
    }

    @Test
    fun parityFullyPushesVarcharEquality() {
        val plan = explain("PARITY", "SELECT id FROM people WHERE name = 'bob'")
        assertThat(pushedNameConstraint(plan)).isTrue()
        assertThat(retainedFilter(plan)).isFalse()
    }

    @Test
    fun parityReturnsCorrectAliasRows() {
        assertThat(rows("PARITY", "SELECT id FROM people WHERE upper(name) = 'BOB' ORDER BY id")).containsExactly(2L)
    }
}
