package dev.brikk.duckbridge.trino.plugin

import io.airlift.slice.Slices
import io.trino.spi.Page
import io.trino.spi.block.BlockBuilder
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType.VARCHAR
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.DuckDBChunkedResult
import org.duckdb.DuckDBConnection
import org.duckdb.DuckDBPreparedStatement
import org.duckdb.DuckDBResultSet
import java.sql.DriverManager
import java.sql.Connection
import java.sql.ResultSet
import java.util.Properties
import kotlin.math.min

/** Test-only scalar Page materialization. No registration in a connector or transport default changes. */
internal object ScanBenchmarkReader {
    const val PAGE_ROWS = 2048
    enum class Mode(val streamed: Boolean, val arrow: Boolean = false) {
        JDBC(false), JDBC_STREAMING(true), CHUNKED(true), ARROW(false, true), ARROW_STREAMING(true, true),
    }
    data class Result(val rows: Long, val hash: Long, val pages: Int, val arrowManagedPeak: Long, val statementClosed: Boolean)

    /** Sample through an independent connection, never re-enter an owner with an active streamed result. */
    fun nativeMemory(observer: Connection): Long = observer.createStatement().use { statement ->
        statement.executeQuery("SELECT CAST(sum(memory_usage_bytes) AS BIGINT) FROM duckdb_memory()").use { rows ->
            check(rows.next()) { "No native-memory sample" }
            rows.getLong(1)
        }
    }

    fun open(url: String, mode: Mode): DuckDBConnection = (DriverManager.getConnection(url, Properties().apply {
        setProperty("jdbc_stream_results", mode.streamed.toString())
    }) as DuckDBConnection).also { connection ->
        connection.createStatement().use { it.execute("SET threads=1"); it.execute("SET memory_limit='512MB'") }
    }

    fun scan(connection: DuckDBConnection, mode: Mode, workload: ScanBenchmarkData.Workload, observe: (() -> Unit)? = null): Result {
        val types = ScanBenchmarkData.types(workload.shape)
        val checksum = ScanBenchmarkData.Digest()
        var pages = 0
        val sink: (Page) -> Unit = { page -> checksum.page(page, types); pages++; observe?.invoke() }
        val statement = connection.prepareStatement(ScanBenchmarkData.sql(workload)) as DuckDBPreparedStatement
        var arrowPeak = -1L
        statement.use {
            it.setLong(1, workload.lower)
            it.setLong(2, workload.upper)
            when {
                mode == Mode.CHUNKED -> it.query().use { result -> readChunks(result, types, workload.consume, sink) }
                mode.arrow -> arrowPeak = readArrowStatement(it, types, workload.consume, sink)
                else -> it.executeQuery().use { result -> readRows(result, types, workload.consume, sink) }
            }
        }
        check(checksum.rows == workload.consume) { "Unexpected early EOF: ${checksum.rows} / ${workload.consume}" }
        check(statement.isClosed) { "Benchmark leaked its statement" }
        return Result(checksum.rows, checksum.hash, pages, arrowPeak, statement.isClosed)
    }

    private fun builders(types: List<Type>, rows: Int): List<BlockBuilder> = types.map { it.createBlockBuilder(null, rows) }
    private fun page(builders: List<BlockBuilder>): Page = Page(*builders.map { it.build() }.toTypedArray())

    private fun rowAppender(type: Type, column: Int): (ResultSet, BlockBuilder) -> Unit = when (type) {
        BIGINT -> { rows, block -> val value = rows.getLong(column); if (rows.wasNull()) block.appendNull() else BIGINT.writeLong(block, value) }
        DOUBLE -> { rows, block -> val value = rows.getDouble(column); if (rows.wasNull()) block.appendNull() else DOUBLE.writeDouble(block, value) }
        VARCHAR -> { rows, block ->
            val value = rows.getString(column)
            if (value == null) block.appendNull() else VARCHAR.writeSlice(block, Slices.utf8Slice(value))
        }
        else -> error("Unqualified benchmark type: $type")
    }

    private fun readArrowStatement(statement: DuckDBPreparedStatement, types: List<Type>, limit: Long, sink: (Page) -> Unit): Long =
        (statement.executeQuery() as DuckDBResultSet).use { result ->
            RootAllocator().use { allocator ->
                (result.arrowExportStream(allocator, PAGE_ROWS.toLong()) as ArrowReader).use { reader ->
                    readArrow(reader, types, limit, sink)
                }
                check(allocator.allocatedMemory == 0L) { "Arrow allocator retained owned buffers after close" }
                allocator.peakMemoryAllocation
            }
        }

    private fun readRows(rows: ResultSet, types: List<Type>, limit: Long, sink: (Page) -> Unit) {
        val appenders = types.mapIndexed { column, type -> rowAppender(type, column + 1) }
        var total = 0L
        while (total < limit) {
            val blocks = builders(types, PAGE_ROWS)
            var count = 0
            while (count < PAGE_ROWS && total < limit && rows.next()) {
                appenders.forEachIndexed { column, append -> append(rows, blocks[column]) }
                count++; total++
            }
            if (count == 0) break
            sink(page(blocks))
        }
    }

    private fun readChunks(result: DuckDBChunkedResult, types: List<Type>, limit: Long, sink: (Page) -> Unit) {
        var total = 0L
        while (total < limit && result.nextChunk()) {
            val chunk = result.chunk()
            check(chunk.columnCount() == types.size.toLong()) { "Unexpected chunk column count" }
            val count = min(chunk.rowCount(), limit - total).toInt()
            val blocks = builders(types, count)
            types.forEachIndexed { column, type ->
                val vector = chunk.vector(column.toLong())
                for (row in 0 until count) {
                    val index = row.toLong()
                    if (vector.isNull(index)) blocks[column].appendNull()
                    else when (type) {
                        BIGINT -> BIGINT.writeLong(blocks[column], vector.getLong(index))
                        DOUBLE -> DOUBLE.writeDouble(blocks[column], vector.getDouble(index))
                        VARCHAR -> VARCHAR.writeSlice(blocks[column], Slices.utf8Slice(vector.getString(index)))
                        else -> error("Unqualified benchmark type: $type")
                    }
                }
            }
            sink(page(blocks))
            total += count
        }
    }

    private fun readArrow(reader: ArrowReader, types: List<Type>, limit: Long, sink: (Page) -> Unit) {
        val converter = DuckBridgeArrowToPageConverter(types)
        var total = 0L
        while (total < limit && reader.loadNextBatch()) {
            val converted = converter.convert(reader.vectorSchemaRoot)
            val count = min(converted.positionCount.toLong(), limit - total).toInt()
            if (count > 0) sink(if (count == converted.positionCount) converted else converted.getRegion(0, count))
            total += count
        }
    }
}
