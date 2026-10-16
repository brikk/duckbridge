package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.Connector
import org.apache.doris.connector.spi.ConnectorContext
import org.apache.doris.connector.spi.ConnectorProvider

/**
 * `ServiceLoader` entry point for the duckbridge Doris connector. Registered via
 * `resources/META-INF/services/org.apache.doris.connector.spi.ConnectorProvider`, discovered at
 * FE startup, and accepted by upstream Doris's provider-first connector routing. The manifest API
 * major is checked before registration; [create] then wires the production metadata and scan path.
 */
class DuckBridgeConnectorProvider : ConnectorProvider {

    override fun getType(): String = TYPE

    override fun create(properties: Map<String, String>, context: ConnectorContext): Connector =
        DuckBridgeConnector(DuckBridgeConnectorConfig.from(properties), context)

    companion object {
        /**
         * Catalog type for `CREATE CATALOG ... type="duckbridge"`. A distinct type (not `jdbc`,
         * which routes to the in-tree fe-connector-jdbc).
         */
        const val TYPE: String = "duckbridge"
    }
}
