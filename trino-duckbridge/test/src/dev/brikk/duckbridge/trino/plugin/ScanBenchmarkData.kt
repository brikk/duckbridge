package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slices
import io.airlift.slice.XxHash64
import io.trino.spi.Page
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType.VARCHAR
import java.sql.Connection

/** Controlled scalar workloads only; this is not a new production type/transport contract. */
internal object ScanBenchmarkData {
    enum class Shape { NUMERIC, TEXT, WIDE }
    data class Workload(val name: String, val shape: Shape, val lower: Long, val upper: Long,
                        val consume: Long = upper - lower, val ordered: Boolean = true)

    val workloads = listOf(
        Workload("small_numeric", Shape.NUMERIC, 0, 1000),
        Workload("large_numeric", Shape.NUMERIC, 0, 1_000_000),
        Workload("selective_numeric", Shape.NUMERIC, 800_000, 801_000),
        Workload("unicode_text", Shape.TEXT, 0, 250_000),
        Workload("wide_nullable", Shape.WIDE, 0, 100_000),
        Workload("early_close", Shape.NUMERIC, 0, 1_000_000, 2048, ordered = false),
    )

    fun types(shape: Shape): List<Type> = when (shape) {
        Shape.NUMERIC -> listOf(BIGINT, BIGINT, DOUBLE)
        Shape.TEXT -> listOf(BIGINT, VARCHAR)
        Shape.WIDE -> List(33) { BIGINT }
    }

    fun sql(workload: Workload): String {
        val table = when (workload.shape) { Shape.NUMERIC -> "numeric_data"; Shape.TEXT -> "text_data"; Shape.WIDE -> "wide_data" }
        return "SELECT * FROM $table WHERE id>=? AND id<?" + if (workload.ordered) " ORDER BY id" else ""
    }

    fun seed(connection: Connection, numericRows: Long = 1_000_000, textRows: Long = 250_000, wideRows: Long = 100_000) {
        connection.createStatement().use { statement ->
            statement.execute("SET threads=1")
            statement.execute("CREATE TABLE numeric_data AS SELECT r::BIGINT id," +
                "CASE WHEN r%11=0 THEN NULL ELSE r*3 END n,CASE WHEN r%13=0 THEN NULL ELSE (r*0.125)::DOUBLE END d FROM range($numericRows) t(r)")
            statement.execute("CREATE TABLE text_data AS SELECT r::BIGINT id,CASE WHEN r%17=0 THEN NULL " +
                "ELSE repeat('row-'||CAST(r%2048 AS VARCHAR)||'-straße-😀',8) END txt FROM range($textRows) t(r)")
            val columns = (0 until 32).joinToString(",") { "CASE WHEN (r+$it)%19=0 THEN NULL ELSE r*7+$it END c$it" }
            statement.execute("CREATE TABLE wide_data AS SELECT r::BIGINT id,$columns FROM range($wideRows) t(r)")
            statement.execute("CHECKPOINT")
        }
    }

    /** Expected values are generated in the JDK, independently of the SQL/driver used by the readers. */
    fun expected(workload: Workload): Digest {
        val result = Digest()
        for (id in workload.lower until workload.lower + workload.consume) {
            result.field(id)
            when (workload.shape) {
                Shape.NUMERIC -> {
                    result.nullableField(if (id % 11 == 0L) null else id * 3)
                    result.nullableField(if (id % 13 == 0L) null else java.lang.Double.doubleToRawLongBits(id * 0.125))
                }
                Shape.TEXT -> result.nullableField(if (id % 17 == 0L) null else XxHash64.hash(Slices.utf8Slice("row-${id % 2048}-straße-😀".repeat(8))))
                Shape.WIDE -> for (column in 0 until 32) result.nullableField(if ((id + column) % 19 == 0L) null else id * 7 + column)
            }
            result.rows++
        }
        return result
    }

    class Digest {
        var rows = 0L
        var hash = -3750763034362895579L
            private set
        fun field(value: Long) {
            hash = (hash xor 1L) * 1099511628211L
            hash = (hash xor value) * 1099511628211L
        }
        fun nullField() { hash *= 1099511628211L }
        fun nullableField(value: Long?) { if (value == null) nullField() else field(value) }
        fun page(page: Page, types: List<Type>) {
            for (row in 0 until page.positionCount) {
                types.forEachIndexed { column, type ->
                    val block = page.getBlock(column)
                    if (block.isNull(row)) nullField()
                    else field(when (type) {
                        BIGINT -> BIGINT.getLong(block, row)
                        DOUBLE -> java.lang.Double.doubleToRawLongBits(DOUBLE.getDouble(block, row))
                        VARCHAR -> XxHash64.hash(VARCHAR.getSlice(block, row))
                        else -> error("Unqualified benchmark type: $type")
                    })
                }
                rows++
            }
        }
    }
}
