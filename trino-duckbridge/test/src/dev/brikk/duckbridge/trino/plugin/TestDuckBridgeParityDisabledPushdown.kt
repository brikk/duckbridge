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

import io.trino.sql.planner.plan.FilterNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Plan-shape tests with `duckbridge.parity.enabled=false`. Post "alias only what diverges",
 * disabling parity no longer turns off ALL function pushdown: the Bare/Rename/Operator/Inline
 * emission classes still push (they emit plain DuckDB SQL and never touch the extension), and only
 * the extension-backed ALIAS entries drop out.
 *
 * The split canary:
 *  - `length(name) = 5`  → BARE → still FULLY pushed (no FilterNode above the scan).
 *  - `upper(name) = 'BOB'` → ALIAS → NOT pushed (FilterNode remains).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeParityDisabledPushdown : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        val runner =
            DuckBridgeQueryRunner.create(
                DuckBridgeQueryRunner.freshDatabaseUrl(),
                mapOf("duckbridge.parity.enabled" to "false"),
            )
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
                (3, 'CAROL')
            """.trimIndent(),
        )
    }

    @Test
    fun bareLengthStillFullyPushedWithParityDisabled() {
        assertThat(query("SELECT id FROM people WHERE length(name) = 5"))
            .isFullyPushedDown()
    }

    @Test
    fun aliasUpperNotPushedWithParityDisabled() {
        assertThat(query("SELECT id FROM people WHERE upper(name) = 'BOB'"))
            .isNotFullyPushedDown(FilterNode::class.java)
    }

    @Test
    fun bareLengthReturnsCorrectRowsWithParityDisabled() {
        val ids =
            computeActual("SELECT id FROM people WHERE length(name) = 5 ORDER BY id")
                .materializedRows
                .map { it.getField(0) as Long }
        assertThat(ids).containsExactly(1L, 3L)
    }
}
