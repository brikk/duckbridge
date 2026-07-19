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
 * As of probe P4 (quack-jdbc metadata fidelity), the **metadata plane is real**:
 * [DuckBridgeDorisMetadata] resolves schemas/tables/columns over quack-jdbc with the probe-decided
 * type map. `planScan` remains a fail-loud stub — P1 (pushdown divergence audit) and P5 (what the
 * SPI hands planScan) are still open. Fail-loud over silently-wrong throughout.
 */
class DuckBridgeConnector internal constructor(
    private val config: DuckBridgeConnectorConfig,
    @Suppress("unused") private val context: ConnectorContext,
) : Connector {

    private val connections = DuckBridgeQuackConnections(config)
    private val metadata =
        DuckBridgeDorisMetadata(config, connections, config.enableTimestampTz)
    private val scanPlanProvider = DuckBridgeScanPlanProvider(config)

    override fun getMetadata(session: ConnectorSession?): ConnectorMetadata = metadata

    override fun getScanPlanProvider(): ConnectorScanPlanProvider = scanPlanProvider

    override fun close() {
        // Metadata connections are per-call and self-closing; no long-lived FE-side pool to release.
    }

    internal fun config(): DuckBridgeConnectorConfig = config
}
