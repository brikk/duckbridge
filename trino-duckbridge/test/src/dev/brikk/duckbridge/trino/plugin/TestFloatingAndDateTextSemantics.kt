package dev.brikk.duckbridge.trino.plugin

import io.trino.plugin.jdbc.JdbcMetadataConfig
import io.trino.plugin.jdbc.JdbcMetadataSessionProperties
import io.trino.spi.predicate.Domain
import io.trino.spi.predicate.Range
import io.trino.spi.predicate.ValueSet
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.RealType.REAL
import io.trino.testing.TestingConnectorSession
import io.trino.util.DateTimeUtils.printDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types
import java.util.Optional
import java.util.Random

class TestFloatingAndDateTextSemantics {
    @Test
    fun testFloatingDomainTransformationPreservesMembershipAndResidual() {
        val session = TestingConnectorSession.builder().setPropertyMetadata(
            JdbcMetadataSessionProperties(JdbcMetadataConfig().setDomainCompactionThreshold(1), Optional.empty()).sessionProperties).build()
        for (type in listOf(DOUBLE, REAL)) {
            fun value(number: Double): Any = if (type == DOUBLE) number else java.lang.Float.floatToRawIntBits(number.toFloat()).toLong()
            val zero = value(0.0)
            val domains = listOf(Domain.none(type), Domain.all(type), Domain.onlyNull(type), Domain.notNull(type),
                Domain.create(ValueSet.ofRanges(Range.greaterThan(type, zero)), false),
                Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(type, zero)), true),
                Domain.create(ValueSet.ofRanges(Range.lessThan(type, zero)), false),
                Domain.singleValue(type, zero).complement(),
                Domain.create(ValueSet.copyOf(type, listOf(zero, value(1.0), value(5.0))), false))
            val samples = listOf(null, value(Double.NaN), value(Double.NEGATIVE_INFINITY), value(-1.0), value(-0.0),
                zero, value(1.0), value(2.0), value(5.0), value(Double.POSITIVE_INFINITY))
            for (domain in domains) {
                val result = DuckBridgeFloatingPredicatePushdown.PUSHDOWN.apply(session, domain)
                for (sample in samples) {
                    assertThat(result.pushedDown.includesNullableValue(sample) && result.remainingFilter.includesNullableValue(sample))
                        .describedAs("%s domain=%s sample=%s", type, domain, sample).isEqualTo(domain.includesNullableValue(sample))
                }
            }
        }
    }

    @Test
    fun testNativeFloatingComparisonHasIeeeAndSqlNullSemantics() {
        val values = listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, -1.0, -0.0, 0.0, 1.0, Double.POSITIVE_INFINITY)
        val pairs = values.flatMap { left -> values.map { right -> left to right } }
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            for (op in listOf("=", "<>", "<", "<=", ">", ">=")) {
                val expression = DuckBridgeFloatingExpressions.comparison("l", op, "r")
                connection.prepareStatement("SELECT $expression FROM (SELECT CAST(? AS DOUBLE) l,CAST(? AS DOUBLE) r)").use { statement ->
                    for ((left, right) in pairs) {
                        assertComparison(statement, left, op, right)
                    }
                }
            }
        }
    }

    private fun assertComparison(statement: PreparedStatement, left: Double?, op: String, right: Double?) {
        listOf(left, right).forEachIndexed { index, value ->
            if (value == null) statement.setNull(index + 1, Types.DOUBLE) else statement.setDouble(index + 1, value)
        }
        statement.executeQuery().use { rows ->
            check(rows.next())
            assertThat(rows.getObject(1)).describedAs("%s %s %s", left, op, right).isEqualTo(ieee(left, op, right))
        }
    }

    @Test
    fun testDateTextMatchesTrinoAcrossFiniteRange() {
        val random = Random(483)
        val days = listOf(-2147483646L, -719893L, -719528L, -719162L, 0L, 2932896L, 2932897L, 2147483646L) +
            List(1024) { random.nextInt().toLong().coerceIn(-2147483646L, 2147483646L) }
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.prepareStatement("SELECT ${DuckBridgeDateExpressions.toVarchar("d")} FROM " +
                "(SELECT ${DuckBridgeDateWriteFunction.bindExpression} AS d)").use { statement ->
                for (day in days) {
                    DuckBridgeDateWriteFunction.set(statement, 1, day)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        assertThat(rows.getString(1)).describedAs("epoch day %s", day).isEqualTo(printDate(day.toInt()))
                    }
                }
            }
        }
    }

    private fun ieee(left: Double?, op: String, right: Double?): Boolean? {
        if (left == null || right == null) return null
        return when (op) {
            "=" -> left == right
            "<>" -> left != right
            "<" -> left < right
            "<=" -> left <= right
            ">" -> left > right
            ">=" -> left >= right
            else -> error("Unexpected operator")
        }
    }
}
