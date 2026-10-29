package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorFunctionCall
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisOverlayOverQuack {
    private lateinit var server: TestingQuackServer
    private val string = ConnectorType.of("STRING")

    @BeforeAll
    fun setUp() {
        server = TestingQuackServer()
        server.exec(
            "CREATE SCHEMA sales",
            "CREATE TABLE sales.overlay_inputs(id INTEGER, source VARCHAR, replacement VARCHAR)",
            "INSERT INTO sales.overlay_inputs VALUES " +
                "(1,'abcd','X'),(2,'a😀bc','界'),(3,'éxy','👨‍👩‍👧'),(4,'','X')," +
                "(5,NULL,'X'),(6,'abcd',NULL),(7,'abcd',''),(8,'a','long'),(9,'',NULL)," +
                "(10,'ab''cd','x''y'),(11,'👨‍👩‍👧z','q')",
        )
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun query(start: Int, count: Int, expected: String, operator: String): List<Int> {
        val call = ConnectorFunctionCall(
            "overlay", string, listOf(
                ConnectorColumnRef("source", string),
                ConnectorLiteral.ofInt(start), ConnectorLiteral.ofInt(count),
                ConnectorColumnRef("replacement", string),
            ),
        )
        val expression = checkNotNull(DorisOverlayCandidate.render(call, mapOf("source" to "VARCHAR", "replacement" to "VARCHAR")) { argument ->
            when (argument) {
                is ConnectorColumnRef -> "\"${argument.columnName}\""
                is ConnectorLiteral -> argument.value.toString()
                else -> null
            }
        })
        val sql = "SELECT id FROM sales.overlay_inputs WHERE ($expression $operator '${expected.replace("'", "''")}')"
        assertThat(sql).contains("WHERE", "CASE WHEN", "PLACING")
        return server.openConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("$sql ORDER BY id").use { rows ->
                    buildList { while (rows.next()) add(rows.getInt(1)) }
                }
            }
        }
    }

    private fun ids(start: Int, count: Int, expected: String) = query(start, count, expected, "=")

    @Test
    fun replacesCodepointsIncludingAstralCombiningAndJoiners() {
        assertThat(ids(2, 1, "aXcd")).containsExactly(1)
        assertThat(ids(2, 1, "a界bc")).containsExactly(2)
        assertThat(ids(2, 1, "e👨‍👩‍👧xy")).containsExactly(3)
        assertThat(ids(2, 1, "👨q👩‍👧z")).containsExactly(11)
        assertThat(ids(2, 1, "ax'y'cd")).containsExactly(10)
        assertThat(ids(2, 1, "acd")).containsExactly(7)
    }

    @Test
    fun emptyAndOutOfRangeStayUnchangedAndNullReplacementNeverMatches() {
        assertThat(ids(1, 0, "")).containsExactly(4)
        assertThat(ids(2, 1, "a")).containsExactly(8)
        assertThat(ids(100, 1, "abcd")).containsExactly(1, 7)
        assertThat(ids(Int.MAX_VALUE, 1, "")).containsExactly(4)
        assertThat(query(100, 1, "abcd", "<>"))
            .containsExactly(2, 3, 4, 8, 10, 11) // NULL source/replacement must not become true under <>.
    }

    @Test
    fun zeroCountAndLargeTruncationBoundsAreExact() {
        assertThat(ids(2, 0, "aXbcd")).containsExactly(1)
        assertThat(ids(1, Int.MAX_VALUE, "X")).containsExactly(1)
        assertThat(ids(2, Int.MAX_VALUE - 1, "aX")).containsExactly(1)
    }
}
