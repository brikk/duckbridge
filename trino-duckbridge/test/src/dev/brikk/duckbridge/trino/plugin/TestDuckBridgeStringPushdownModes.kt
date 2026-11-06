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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeStringPushdownModes : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
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

    private fun pushedNameConstraint(plan: String): Boolean = plan.contains("constraint on [name]")

    private fun retainedFilter(plan: String): Boolean = plan.contains("filterPredicate =")


    @Test
    fun nullOnlyDoesNotPushVarcharEquality() {
        val plan = explain("NULL_ONLY", "SELECT id FROM people WHERE name = 'bob'")
        assertThat(pushedNameConstraint(plan)).`as`("NULL_ONLY must not push a string domain").isFalse()
        assertThat(retainedFilter(plan)).`as`("filter stays in Trino").isTrue()
    }

    @Test
    fun nullOnlyPushesIsNull() {
        val plan = explain("NULL_ONLY", "SELECT id FROM people WHERE name IS NULL")
        assertThat(pushedNameConstraint(plan)).`as`("IS NULL pushes even in NULL_ONLY").isTrue()
        assertThat(retainedFilter(plan)).`as`("no retained filter for pushed IS NULL").isFalse()
    }

    @Test
    fun nullOnlyStillPushesNonStringFunction() {
        // length(name) returns bigint, so this comparison is not subject to string collation hazards.
        val plan = explain("NULL_ONLY", "SELECT id FROM people WHERE length(name) = 5")
        assertThat(plan).contains("""length("name") = 5""")
        assertThat(retainedFilter(plan)).isFalse()
    }


    @Test
    fun guardedPushesVarcharEqualityAsRetainedPrefilter() {
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
        val plan = explain("GUARDED", "SELECT id FROM people WHERE upper(name) = 'BOB'")
        assertThat(plan).doesNotContain("trino_upper")
        assertThat(retainedFilter(plan)).isTrue()
    }


    @Test
    fun binaryFullyPushesVarcharEquality() {
        val plan = explain("BINARY", "SELECT id FROM people WHERE name = 'bob'")
        assertThat(pushedNameConstraint(plan)).isTrue()
        assertThat(retainedFilter(plan)).`as`("BINARY drops the retained filter").isFalse()
    }

    @Test
    fun binaryPushesStringReturningBareFunctionComparison() {
        val plan = explain("BINARY", "SELECT id FROM people WHERE replace(name, 'o', '0') = 'b0b'")
        assertThat(plan).contains("""replace("name", 'o', '0') = 'b0b'""")
        assertThat(retainedFilter(plan)).isFalse()
    }

    @Test
    fun binaryDoesNotPushAliasFunction() {
        val plan = explain("BINARY", "SELECT id FROM people WHERE lower(name) = 'bob'")
        assertThat(plan).doesNotContain("trino_lower")
        assertThat(retainedFilter(plan)).isTrue()
    }

    @Test
    fun binaryReturnsCorrectRows() {
        assertThat(rows("BINARY", "SELECT id FROM people WHERE name = 'bob' ORDER BY id")).containsExactly(2L)
    }


    @Test
    fun parityPushesAliasFunction() {
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
