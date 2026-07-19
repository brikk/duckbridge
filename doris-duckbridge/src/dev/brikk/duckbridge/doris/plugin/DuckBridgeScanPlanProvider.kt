package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.ConnectorSession
import org.apache.doris.connector.api.handle.ConnectorColumnHandle
import org.apache.doris.connector.api.handle.ConnectorTableHandle
import org.apache.doris.connector.api.pushdown.ConnectorExpression
import org.apache.doris.connector.api.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.api.scan.ConnectorScanRange
import org.apache.doris.connector.api.scan.ConnectorScanRangeType
import java.util.Optional

/**
 * The scan-plan seam for duckbridge. This is where the pushdown core (ported from
 * `trino-duckbridge`'s translator architecture — new FE, ported discipline) will compose the
 * projected columns + pushed-down filter into a DuckDB `SELECT` and emit a `JDBC_SCAN` range
 * carrying `{driver_class, driver_url, jdbc_url, query}` for the BE's `JdbcJniScanner` to run via
 * quack-jdbc.
 *
 * WIP scaffold: [planScan] is intentionally NOT implemented. Returning an empty scan would be a
 * silent under-return (the exact anti-pattern the repo bans), so it throws with a pointer to the
 * open probes that gate a real implementation. [getScanRangeType] already declares `JDBC_SCAN`
 * (plan: Route J rides the BE's shared `jdbc` reader).
 */
internal class DuckBridgeScanPlanProvider(
    @Suppress("unused") private val config: DuckBridgeConnectorConfig,
) : ConnectorScanPlanProvider {

    override fun getScanRangeType(): ConnectorScanRangeType = ConnectorScanRangeType.JDBC_SCAN

    override fun planScan(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        columns: List<ConnectorColumnHandle>,
        filter: Optional<ConnectorExpression>,
    ): List<ConnectorScanRange> = throw UnsupportedOperationException(
        "duckbridge planScan is not implemented yet. A real implementation must settle the plan's " +
            "open probes before emitting a JDBC_SCAN range: P1 (Doris↔DuckDB divergence audit — " +
            "what is safe to push), P2 (JDBC_SCAN range count vs the Quack connection pool), P3 " +
            "(session-init / zone-explicit SQL for tz correctness), P4 (quack-jdbc metadata " +
            "fidelity), P5 (what the SPI hands planScan beyond conjuncts — LIMIT/TopN), P6 (Doris " +
            "session time_zone visibility). Failing loud instead of returning an empty scan " +
            "(which would silently under-return). See dev-docs/PLAN-doris-duckbridge.md §Open probes.",
    )
}
