package dev.brikk.duckbridge.trino.plugin

import io.trino.plugin.jdbc.LongWriteFunction
import io.trino.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE
import io.trino.spi.TrinoException
import java.sql.PreparedStatement
import java.time.LocalDate

/** Preserve the DATE type without going through JDBC's lossy java.sql.Date carrier. */
internal object DuckBridgeDateWriteFunction : LongWriteFunction {
    override fun getBindExpression(): String = "CAST(? AS DATE)"

    override fun set(statement: PreparedStatement, index: Int, value: Long) {
        // DuckDB reserves the adjacent int32 values for infinity; Trino DATE has no such sentinels.
        if (value !in -2147483646L..2147483646L) {
            throw TrinoException(NUMERIC_VALUE_OUT_OF_RANGE, "DATE epoch day $value is outside DuckDB's finite DATE range")
        }
        // ISO extended positive years carry '+', which DuckDB's DATE parser rejects.
        statement.setString(index, LocalDate.ofEpochDay(value).toString().removePrefix("+"))
    }
}
