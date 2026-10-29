package dev.brikk.duckbridge.trino.plugin

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Locale
import kotlin.io.path.writeText

/**
 * Discovery, not a parity gate: execute the published generator's frozen SQL on both real engines,
 * retain value/type/error differences, and prove none of these unqualified calls becomes pushable.
 * The opt-in metadata audit separately verifies this module-local corpus against the actual library.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestPublishedSqlIntake : AbstractTestQueryFramework() {
    private val duckDelegate = lazy { DriverManager.getConnection("jdbc:duckdb:") }
    private val duck by duckDelegate

    override fun createQueryRunner(): QueryRunner = DuckBridgeQueryRunner.create(DuckBridgeQueryRunner.freshDatabaseUrl())

    @AfterAll
    fun closeDuck() {
        if (duckDelegate.isInitialized()) duck.close()
    }

    private data class Value(val type: String, val value: String?)
    private data class RemoteOutcome(val value: Value?, val error: String = "")

    @Test
    fun recordsPublishedRegexLoweringsWithoutAuthorizingGenericPatterns() {
        // Availability is now bounded by the qualified positive-atom gate; generic published SQL
        // still does not authorize lookarounds or the other unqualified pattern shapes.
        assertThat(RegexpCountSafety.isSafe("(?<=a)b")).isFalse()
        assertThat(RegexpCountSafety.isSafe("(?=a)")).isFalse()
        val lines = checkNotNull(javaClass.getResourceAsStream("/function-intake/brikk-sql-regexp-count.tsv"))
            .bufferedReader().use { it.readLines() }
        check(lines.first() == "source\tcase\tinput_sql\tduckdb_sql\tstatus")
        val observations = lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            check(fields.size == 5 && fields[0] == "trino" && fields[4] == "EMITTED") { "Invalid intake fixture: $line" }
            val host = hostOutcome(fields[2])
            val remote = remoteOutcome(fields[3])
            val status = when {
                remote.value == null -> "DUCKDB_ERROR_NEEDS_REVIEW"
                host == remote.value -> "MATCHING_FIXTURE_NOT_QUALIFIED"
                else -> "DIFFERENT_VALUE_OR_TYPE"
            }
            listOf(fields[1], host.type, host.value ?: "<SQL_NULL>", remote.value?.type.orEmpty(),
                remote.value?.value ?: "<SQL_NULL>", status, remote.error)
        }
        check(observations.isNotEmpty()) { "No intake fixtures were evaluated" }
        // A remote query error must not hide a dead/poisoned engine or a broken test setup.
        assertThat(computeActual("SELECT 1").materializedRows.single().getField(0)).isEqualTo(1)
        duck.createStatement().use { statement ->
            statement.executeQuery("SELECT 1").use { result ->
                assertThat(result.next()).isTrue()
                assertThat(result.getInt(1)).isEqualTo(1)
            }
        }
        val output = Files.createDirectories(Path.of("build/function-audit"))
        output.resolve("published-trino-observations.tsv").writeText(
            "case\thost_type\thost_value\tduckdb_type\tduckdb_value\tobservation\tdiagnostic\n" +
                observations.joinToString("\n") { tsv(it) } + "\n",
        )
    }

    private fun hostOutcome(sql: String): Value {
        // All source probes are valid Trino calls. Unexpected host failures fail the test, not parity.
        val result = computeActual(sql)
        return Value(result.types.single().displayName.lowercase(Locale.ROOT),
            result.materializedRows.single().getField(0)?.toString())
    }

    private fun remoteOutcome(sql: String): RemoteOutcome =
        try {
            duck.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next()) { "DuckDB probe returned no row" }
                    val value = Value(result.metaData.getColumnTypeName(1).lowercase(Locale.ROOT), result.getObject(1)?.toString())
                    check(!result.next()) { "DuckDB probe returned more than one row" }
                    RemoteOutcome(value)
                }
            }
        } catch (error: SQLException) {
            RemoteOutcome(null, "${error.sqlState}: ${error.message}")
        }

    private fun tsv(fields: List<String>): String = fields.joinToString("\t") { value ->
        if (value.any { it in "\t\r\n\"" }) "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
