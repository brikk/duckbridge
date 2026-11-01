package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slices.utf8Slice
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE
import io.trino.spi.TrinoException
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.TimestampType.TIMESTAMP_MICROS
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType.VARCHAR
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Types
import java.util.Optional

class TestDateWritesAndTemporalUnits {
    @Test
    fun testFiniteDateBindsKeepNativeTypeAndExactDays() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.prepareStatement("SELECT typeof(d),date_diff('day',DATE '1970-01-01',d) FROM " +
                "(SELECT ${DuckBridgeDateWriteFunction.bindExpression} AS d)").use { statement ->
                for (day in listOf(-2147483646L, -719893L, -719528L, -719162L, 0L, 2932896L, 2932897L, 2147483646L)) {
                    DuckBridgeDateWriteFunction.set(statement, 1, day)
                    statement.executeQuery().use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo("DATE")
                        assertThat(rows.getLong(2)).describedAs("epoch day %s", day).isEqualTo(day)
                        assertThat(rows.next()).isFalse()
                    }
                }
                DuckBridgeDateWriteFunction.setNull(statement, 1)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("DATE")
                    assertThat(rows.getObject(2)).isNull()
                }
            }
        }
    }

    @Test
    fun testDateSentinelsAndOutOfRangeValuesFailBeforeBinding() {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.prepareStatement("SELECT ${DuckBridgeDateWriteFunction.bindExpression}").use { statement ->
                for (day in listOf(Long.MIN_VALUE, -2147483648L, -2147483647L, 2147483647L, 2147483648L, Long.MAX_VALUE)) {
                    assertThatThrownBy { DuckBridgeDateWriteFunction.set(statement, 1, day) }
                        .isInstanceOfSatisfying(TrinoException::class.java) { failure ->
                            assertThat(failure.errorCode).isEqualTo(NUMERIC_VALUE_OUT_OF_RANGE.toErrorCode())
                        }
                        .hasMessageContaining("outside DuckDB's finite DATE range")
                }
            }
        }
    }

    @Test
    fun testUnitAdmissionIsSpecificToTheTemporalType() {
        val dateUnits = listOf("day", "week", "month", "quarter", "year", "MONTH")
        val timestampUnits = dateUnits + listOf("millisecond", "second", "minute", "hour")
        for (type in listOf(DATE, TIMESTAMP_MICROS)) {
            val valid = if (type == DATE) dateUnits else timestampUnits
            for (unit in timestampUnits + listOf("century", "days", "fortnight", "", " hour")) {
                for (name in listOf("date_trunc", "date_diff")) {
                    val sql = translate(name, type, Constant(utf8Slice(unit), VARCHAR))
                    assertThat(sql != null).describedAs("%s(%s, %s)", name, unit, type).isEqualTo(unit in valid)
                }
            }
            for (unit in listOf(Constant(null, VARCHAR), Variable("unit", VARCHAR))) {
                assertThat(translate("date_trunc", type, unit)).isNull()
                assertThat(translate("date_diff", type, unit)).isNull()
            }
        }
    }

    private fun translate(name: String, type: Type, unit: ConnectorExpression): String? {
        val value = Variable("value", type)
        val args = if (name == "date_diff") listOf(unit, value, value) else listOf(unit, value)
        val result = if (name == "date_diff") BIGINT else type
        val jdbcType = if (type == DATE) Types.DATE else Types.TIMESTAMP
        val column = JdbcColumnHandle("value", JdbcTypeHandle(jdbcType, Optional.of(type.displayName), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()), type)
        val unitColumn = JdbcColumnHandle("unit", JdbcTypeHandle(Types.VARCHAR, Optional.of("VARCHAR"), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()), VARCHAR)
        return DuckBridgeExpressionTranslator.translate(Call(result, FunctionName(name), args), mapOf("value" to column, "unit" to unitColumn))
    }
}
