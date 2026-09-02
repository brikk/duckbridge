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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Properties

/**
 * Cross-engine differential harness for [SemanticFixtures] (EV-B1 in
 * dev-docs/TODO-rectify-from-eval.md).
 *
 * For each [SemanticFixtures.Fixture] the SAME Trino expression is evaluated on **Trino** (the
 * authoritative expected value — never hand-written) and on **DuckDB** via the SQL the PRODUCTION
 * translator emits. Outcomes must agree: identical canonical value, or both engines error. A
 * value-vs-error split is a failure too (a pushed predicate would fail a query Trino runs, or run one
 * Trino fails). [SemanticFixtures.NotPushed] cases assert the translator declines the shape.
 *
 * Both engines run in [SemanticFixtures.ZONE] so session-zone-sensitive emissions are exercised.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestPushdownSemanticFixtures : AbstractTestQueryFramework() {
    private val trinoSession: Session =
        testSessionBuilder()
            .setCatalog(DuckBridgeQueryRunner.CATALOG)
            .setSchema(DuckBridgeQueryRunner.SCHEMA)
            .setTimeZoneKey(SemanticFixtures.TIME_ZONE_KEY)
            .build()

    private val duck: Connection by lazy { openDuck() }

    override fun createQueryRunner(): QueryRunner = DuckBridgeQueryRunner.create(DuckBridgeQueryRunner.freshDatabaseUrl())

    @AfterAll
    fun closeDuck() {
        duck.close()
    }

    @TestFactory
    fun fixtures(): List<DynamicTest> =
        SemanticFixtures.all().map { case ->
            DynamicTest.dynamicTest("${case.name}/${case.arity} :: ${case.label}") {
                when (case) {
                    is SemanticFixtures.NotPushed -> assertNotPushed(case)
                    is SemanticFixtures.Fixture -> assertParity(case)
                }
            }
        }

    private fun assertNotPushed(case: SemanticFixtures.NotPushed) {
        assertThat(case.emittedDuckSql())
            .`as`("%s/%d [%s] must NOT be pushed (gate)", case.name, case.arity, case.label)
            .isNull()
    }

    private fun assertParity(fx: SemanticFixtures.Fixture) {
        val duckQuery = fx.duckQuery()
        assertThat(duckQuery)
            .`as`("%s/%d [%s] must be pushable (translator returned SQL)", fx.name, fx.arity, fx.label)
            .isNotNull()
        val trinoQuery = fx.trinoQuery()
        val expected = trinoOutcome(trinoQuery)
        val actual = duckOutcome(duckQuery!!)
        assertThat(actual)
            .`as`("%s/%d [%s]\n  trino : %s\n  duckdb: %s", fx.name, fx.arity, fx.label, trinoQuery, duckQuery)
            .isEqualTo(expected)
    }

    /** Canonical outcome of one engine: a normalised value string, or `ERROR` (message kept for the report). */
    private sealed interface Outcome {
        data class Value(val canonical: String) : Outcome

        data class Error(val message: String) : Outcome {
            // Any error equals any other error: both engines refusing is parity.
            override fun equals(other: Any?): Boolean = other is Error

            override fun hashCode(): Int = 0

            override fun toString(): String = "ERROR(${message.lineSequence().first()})"
        }
    }

    private fun trinoOutcome(sql: String): Outcome =
        try {
            val rows = computeActual(trinoSession, sql).materializedRows
            Outcome.Value(canon(rows.single().getField(0)))
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Outcome.Error(e.message ?: e.toString())
        }

    private fun duckOutcome(sql: String): Outcome =
        try {
            duck.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    check(rs.next()) { "no row" }
                    Outcome.Value(canon(rs.getObject(1)))
                }
            }
        } catch (e: SQLException) {
            Outcome.Error(e.message ?: e.toString())
        }

    /**
     * Engine-neutral rendering: integral numbers as plain digits; floating values via exact
     * `BigDecimal(double)` (so 3.0 == 3 but 0.1+0.2 != 0.3), NaN/±Infinity by name; dates ISO;
     * bytes hex; strings verbatim.
     */
    private fun canon(v: Any?): String =
        when (v) {
            null -> "NULL"
            is Boolean -> v.toString()
            is Float -> canonDouble(v.toDouble())
            is Double -> canonDouble(v)
            is BigDecimal -> v.stripTrailingZeros().toPlainString()
            is BigInteger -> v.toString()
            is Number -> v.toLong().toString()
            is LocalDate -> v.toString()
            is java.sql.Date -> v.toLocalDate().toString()
            is LocalDateTime -> v.toString()
            is java.sql.Timestamp -> v.toLocalDateTime().toString()
            is ByteArray -> v.joinToString("") { "%02X".format(it) }
            else -> v.toString()
        }

    private fun canonDouble(d: Double): String =
        when {
            d.isNaN() -> "NaN"
            d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
            d == 0.0 -> "0" // fold -0.0 and 0.0 (both engines compare them equal)
            else -> BigDecimal(d).stripTrailingZeros().toPlainString()
        }

    private fun openDuck(): Connection {
        val props = Properties()
        props.setProperty("allow_unsigned_extensions", "true")
        val conn = DriverManager.getConnection("jdbc:duckdb:", props)
        conn.createStatement().use { it.execute("SET TimeZone = '${SemanticFixtures.ZONE}'") }
        val path =
            TrinoParityExtensionResolver.resolveBundledExtensionPath()
                ?: throw AssertionError("trino_parity extension not bundled for this platform — build it first: `(cd duckdb-trino-parity-extension && make)`.")
        TrinoFunctionAliases.loadInProcess(conn, path)
        return conn
    }
}
