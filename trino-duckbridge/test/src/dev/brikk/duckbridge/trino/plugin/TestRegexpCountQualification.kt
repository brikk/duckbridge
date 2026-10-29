package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slices
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Types
import java.util.Optional
import kotlin.io.path.writeText

/** Every admitted probe must succeed with the same BIGINT/NULL result; errors cannot equal parity. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestRegexpCountQualification : AbstractTestQueryFramework() {
    private val duck by lazy { DriverManager.getConnection("jdbc:duckdb:") }
    private val assignments: Map<String, ColumnHandle> = mapOf(
        "s" to JdbcColumnHandle("s", JdbcTypeHandle(Types.VARCHAR, Optional.of("VARCHAR"), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()), VARCHAR),
    )
    private val patterns = listOf(
        "a", "a+", "aa", "aba", "a+a", "a+b", "aaa+", "a b", "_+", "/+", ":", ",", "-+", "=",
        "[0-9]", "[0-9]+", "[a-z]+", "[A-Z]+", "[A-Za-z]+", "[a-zA-Z]+", "[A-Za-z0-9]+",
        "[a-zA-Z0-9]+", "[a-z0-9]+", "[A-Z0-9]+", "[a-z][0-9]+", "[A-Z]+-[0-9]+",
    )
    private val sources = listOf(
        null, "", "a", "aa", "aaa", "aaaa", "ababa", "aaaab", "a12b3", "A19-Z000", "123", "xyz",
        "a b a", "A_Z", "::,,--==///", "éaéa", "😀a😀a", "éáa", "a\nb", "a\rb", "a\tb",
        "\n", "\r\n", "中文a测试a", "δοκιμή", "NULL", "a\u0000a", "a".repeat(4096),
    )

    override fun createQueryRunner(): QueryRunner = DuckBridgeQueryRunner.create(DuckBridgeQueryRunner.freshDatabaseUrl())

    @AfterAll
    fun closeDuck() { duck.close() }

    private fun count(pattern: io.trino.spi.expression.ConnectorExpression) =
        Call(BIGINT, FunctionName("regexp_count"), listOf(Variable("s", VARCHAR), pattern))

    private fun emitted(pattern: String): String? = DuckBridgeExpressionTranslator.translate(
        count(Constant(Slices.utf8Slice(pattern), VARCHAR)), assignments, null, aliasAvailable = false,
    )

    @Test
    fun testNativeMatrixMatchesExactTypedCountsAndNulls() {
        val input = "WITH input(id,s) AS (VALUES " + sources.mapIndexed { index, value ->
            "($index,${sourceSql(value)})"
        }.joinToString(",") + ") "
        for (pattern in patterns) {
            assertThat(RegexpCountSafety.isSafe(pattern)).describedAs(pattern).isTrue()
            val expression = checkNotNull(emitted(pattern)) { "Qualified pattern did not emit: $pattern" }
            val host = computeActual(input + "SELECT id,regexp_count(s,'$pattern') FROM input ORDER BY id")
            assertThat(host.types[1]).isEqualTo(BIGINT)
            duck.createStatement().use { statement ->
                statement.executeQuery(input + "SELECT id,$expression FROM input ORDER BY id").use { rows ->
                    assertThat(rows.metaData.getColumnTypeName(2)).isEqualToIgnoringCase("BIGINT")
                    for (expected in host.materializedRows) {
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getLong(1)).isEqualTo((expected.getField(0) as Number).toLong())
                        assertThat(rows.getObject(2)).describedAs("pattern=$pattern id=${rows.getInt(1)}")
                            .isEqualTo(expected.getField(1))
                    }
                    assertThat(rows.next()).isFalse()
                }
            }
        }
        val output = Files.createDirectories(Path.of("build/function-audit"))
        output.resolve("regexp-count-qualified.tsv").writeText(
            "patterns\tinputs\ttyped_observations\tresult\n${patterns.size}\t${sources.size}\t${patterns.size * sources.size}\tMATCH\n",
        )
    }

    @Test
    fun testUnqualifiedPatternsAndDynamicOrNullArgumentsStayLocal() {
        val rejected = listOf("", "a*", "a?", ".", "^a", "a$", "a|b", "(a)", "(?:a)", "(?=a)", "(?<=a)b",
            "\\d+", "\\b", "\\p{L}", "[a-z&&[^b]]", "[^a]", "[a-z", "a{2}", "a++", "😀", "a\u0000b")
        for (pattern in rejected) {
            assertThat(RegexpCountSafety.isSafe(pattern)).describedAs(pattern).isFalse()
            assertThat(emitted(pattern)).describedAs(pattern).isNull()
        }
        assertThat(DuckBridgeExpressionTranslator.translate(count(Variable("s", VARCHAR)), assignments)).isNull()
        assertThat(DuckBridgeExpressionTranslator.translate(count(Constant(null, VARCHAR)), assignments)).isNull()
    }

    private fun sourceSql(value: String?): String = when {
        value == null -> "CAST(NULL AS VARCHAR)"
        '\u0000' in value -> value.split('\u0000').joinToString(" || chr(0) || ") { "'${it.replace("'", "''")}'" }
        else -> "'${value.replace("'", "''")}'"
    }
}
