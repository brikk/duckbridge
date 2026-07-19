package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.Connector
import org.apache.doris.connector.api.ConnectorMetadata
import org.apache.doris.connector.api.ConnectorSession
import org.apache.doris.connector.api.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.spi.ConnectorContext

/**
 * Read-only duckbridge [Connector]. Composes the SPI object graph — metadata resolution and the
 * scan-plan seam.
 *
 * As of probes P4 (metadata) + P5/P2 (scan), both planes are **real**: [DuckBridgeDorisMetadata]
 * resolves schemas/tables/columns over quack-jdbc, and [DuckBridgeScanPlanProvider] emits a JDBC
 * scan range so rows flow through the BE `JdbcJniScanner` + our `DuckDbTypeHandler`. Predicate
 * pushdown is the DOMAIN FLOOR only (comparisons/IN/IS NULL); function-shape pushdown waits on P1.
 */
class DuckBridgeConnector internal constructor(
    private val config: DuckBridgeConnectorConfig,
    private val context: ConnectorContext,
) : Connector {

    private val connections = DuckBridgeQuackConnections(config)
    private val metadata =
        DuckBridgeDorisMetadata(config, connections, config.enableTimestampTz)
    private val scanPlanProvider = DuckBridgeScanPlanProvider(config, context.catalogId)

    override fun getMetadata(session: ConnectorSession?): ConnectorMetadata = metadata

    override fun getScanPlanProvider(): ConnectorScanPlanProvider = scanPlanProvider

    override fun close() {
        // Metadata connections are per-call and self-closing; no long-lived FE-side pool to release.
    }

    internal fun config(): DuckBridgeConnectorConfig = config
}
