package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle
import org.apache.doris.connector.spi.handle.ConnectorTableHandle
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.spi.scan.ConnectorScanRange
import org.apache.doris.connector.spi.scan.ConnectorScanRequest
import java.util.Optional

/**
 * Plans one JDBC scan range per DuckDB query.
 * Pushed predicates must be exact: re-filtering cannot recover rows excluded remotely.
 */
internal class DuckBridgeScanPlanProvider(
    private val config: DuckBridgeConnectorConfig,
    private val catalogId: Long,
) : ConnectorScanPlanProvider {

    /**
     * Partitions and metadata count pushdown are unsupported: JDBC queries cannot be partitioned,
     * and row counts require reading the source.
     */
    override fun planScan(
        session: ConnectorSession?,
        request: ConnectorScanRequest,
    ): List<ConnectorScanRange> {
        val handle = request.tableHandle
        val columns = request.columns
        val filter = request.filter
        val limit = request.limit
        val tableHandle = handle as? DuckBridgeTableHandle
            ?: throw DorisConnectorException(
                "duckbridge planScan received a foreign table handle: ${handle::class.java.name}",
            )

        val querySql = DuckBridgeQueryBuilder.buildQuery(
            tableHandle.catalog,
            tableHandle.database,
            tableHandle.table,
            columns,
            filter.orElse(null),
            limit,
            columnDuckdbTypes(columns),
        )

        val range = DuckBridgeJdbcScanRange.Builder()
            .querySql(querySql)
            .jdbcUrl(config.jdbcUrl)
            .jdbcUser(config.user ?: "")
            // quack-jdbc accepts the BE's HikariCP password as an auth-token alias.
            .jdbcPassword(config.password ?: "")
            .driverClass(config.driverClass)
            // The BE resolves driver_url: a "file://…"/scheme URL is used as-is (no checksum gate
            // on the JNI path), a bare name resolves under DORIS_HOME/plugins/jdbc_drivers.
            .driverUrl(config.driverUrl ?: "")
            .tableType(DuckBridgeJdbcScanRange.TABLE_TYPE_DUCKDB)
            .catalogId(catalogId)
            .build()

        return listOf(range)
    }

    override fun getScanNodeProperties(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        columns: List<ConnectorColumnHandle>,
        filter: Optional<ConnectorExpression>,
    ): Map<String, String> {
        val tableHandle = handle as? DuckBridgeTableHandle ?: return emptyMap()
        val querySql = DuckBridgeQueryBuilder.buildQuery(
            tableHandle.catalog,
            tableHandle.database,
            tableHandle.table,
            columns,
            filter.orElse(null),
            NO_LIMIT,
            columnDuckdbTypes(columns),
        )
        return mapOf("query" to querySql)
    }

    private fun columnDuckdbTypes(columns: List<ConnectorColumnHandle>): Map<String, String> =
        columns.filterIsInstance<DuckBridgeColumnHandle>()
            .associate { it.columnName to it.duckdbType }

    private companion object {
        const val NO_LIMIT: Long = -1L
    }
}
