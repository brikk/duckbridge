package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.Connector
import org.apache.doris.connector.api.ConnectorMetadata
import org.apache.doris.connector.api.ConnectorSession
import org.apache.doris.connector.api.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.spi.ConnectorContext

/**
 * Read-only duckbridge [Connector]. Composes the SPI object graph — metadata resolution and the
 * scan-plan seam — so the plugin loads and wires end-to-end.
 *
 * WIP scaffold: the object graph is real, but metadata and `planScan` are stubbed to fail loud
 * (see [DuckBridgeDorisMetadata] / [DuckBridgeScanPlanProvider]) until the plan's P1–P6 probes
 * are settled. Fail-loud over silently-wrong: we never return an empty listing or an empty scan
 * to fake progress.
 */
class DuckBridgeConnector internal constructor(
    private val config: DuckBridgeConnectorConfig,
    @Suppress("unused") private val context: ConnectorContext,
) : Connector {

    private val metadata = DuckBridgeDorisMetadata(config)
    private val scanPlanProvider = DuckBridgeScanPlanProvider(config)

    override fun getMetadata(session: ConnectorSession?): ConnectorMetadata = metadata

    override fun getScanPlanProvider(): ConnectorScanPlanProvider = scanPlanProvider

    override fun close() {
        // No live resources held yet (no connection pool in the scaffold). Once the FE opens a
        // quack-jdbc connection for metadata resolution (probe P4), close it here.
    }

    internal fun config(): DuckBridgeConnectorConfig = config
}
