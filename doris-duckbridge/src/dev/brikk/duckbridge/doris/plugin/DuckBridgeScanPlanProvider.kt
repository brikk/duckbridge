package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.ConnectorSession
import org.apache.doris.connector.api.DorisConnectorException
import org.apache.doris.connector.api.handle.ConnectorColumnHandle
import org.apache.doris.connector.api.handle.ConnectorTableHandle
import org.apache.doris.connector.api.pushdown.ConnectorExpression
import org.apache.doris.connector.api.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.api.scan.ConnectorScanRange
import org.apache.doris.connector.api.scan.ConnectorScanRangeType
import java.util.Optional

/**
 * The scan-plan seam for duckbridge: composes the projected columns + domain-floor predicate into a
 * DuckDB `SELECT` ([DuckBridgeQueryBuilder]) and emits ONE JDBC scan range ([DuckBridgeJdbcScanRange])
 * carrying `{query_sql, jdbc_url, jdbc_user, jdbc_password, jdbc_driver_class, jdbc_driver_url,
 * table_type=DUCKDB, catalog_id}` for the BE's `JdbcJniScanner` to run via quack-jdbc.
 *
 * Probe P5 (see NOTES-p5-p2-scan.md): the FE re-evaluates every conjunct above the scan (we do NOT
 * implement `applyFilter`), so the WHERE we render is a pure optimization — dropped conjuncts are
 * safe, but a PUSHED conjunct must be exactly faithful (the builder enforces the domain floor +
 * NUL refusal). Probe P2: exactly one range per query (a JDBC scan is un-partitionable).
 *
 * Scope: the DOMAIN FLOOR only (no function-shape pushdown — that's P1).
 */
internal class DuckBridgeScanPlanProvider(
    private val config: DuckBridgeConnectorConfig,
    private val catalogId: Long,
) : ConnectorScanPlanProvider {

    // FILE_SCAN (+ tableFormatType "jdbc") is the JDBC path at our pin, NOT the JDBC_SCAN enum
    // value (which the in-tree JDBC connector does not use). See NOTES-p5-p2-scan.md.
    override fun getScanRangeType(): ConnectorScanRangeType = ConnectorScanRangeType.FILE_SCAN

    override fun estimateScanRangeCount(session: ConnectorSession?, handle: ConnectorTableHandle): Long = 1

    override fun planScan(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        columns: List<ConnectorColumnHandle>,
        filter: Optional<ConnectorExpression>,
    ): List<ConnectorScanRange> = planScan(session, handle, columns, filter, NO_LIMIT)

    override fun planScan(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        columns: List<ConnectorColumnHandle>,
        filter: Optional<ConnectorExpression>,
        limit: Long,
    ): List<ConnectorScanRange> {
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
        )

        val range = DuckBridgeJdbcScanRange.Builder()
            .querySql(querySql)
            .jdbcUrl(config.jdbcUrl)
            .jdbcUser(config.user ?: "")
            // quack-jdbc reads `password` as the auth-token alias (verified in its
            // getPropertyInfo). The BE maps jdbc_password → HikariCP password → the token.
            .jdbcPassword(config.password ?: "")
            .driverClass(config.driverClass)
            // The BE resolves driver_url: a "file://…"/scheme URL is used as-is (no checksum gate
            // on the JNI path), a bare name resolves under DORIS_HOME/plugins/jdbc_drivers.
            .driverUrl(config.driverUrl ?: "")
            .tableType(DuckBridgeJdbcScanRange.TABLE_TYPE_DUCKDB)
            .catalogId(catalogId)
            .build()

        // Exactly one range: a JDBC scan is un-partitionable (probe P2).
        return listOf(range)
    }

    /**
     * EXPLAIN surfaces the composed query so a "stopped pushing" regression is visible in the plan
     * (the Doris analogue of asserting on the generated query_sql). Mirrors the in-tree JDBC
     * connector's `getScanNodeProperties` "query" key.
     */
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
        )
        return mapOf("query" to querySql)
    }

    private companion object {
        const val NO_LIMIT: Long = -1L
    }
}
