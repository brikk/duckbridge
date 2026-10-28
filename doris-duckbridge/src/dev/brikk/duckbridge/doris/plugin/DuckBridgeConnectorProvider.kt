package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.Connector
import org.apache.doris.connector.spi.ConnectorContext
import org.apache.doris.connector.spi.ConnectorProvider
import org.apache.doris.connector.spi.ConnectorSecuritySurface
import org.apache.doris.foundation.security.JdbcDriverUrlSecurity

/**
 * `ServiceLoader` entry point for the duckbridge Doris connector. Registered via
 * `resources/META-INF/services/org.apache.doris.connector.spi.ConnectorProvider`, discovered at
 * FE startup, and accepted by upstream Doris's provider-first connector routing. The manifest API
 * major is checked before registration; [create] then wires the production metadata and scan path.
 */
class DuckBridgeConnectorProvider : ConnectorProvider {

    override fun getType(): String = TYPE

    override fun securitySurface(): ConnectorSecuritySurface =
        ConnectorSecuritySurface.ofJdbcDriverUrls(null, DuckBridgeConnectorConfig.PROP_DRIVER_URL)

    override fun validateProperties(properties: Map<String, String>) {
        // Statement-time only: reconstruction/replay must retain historical catalog compatibility.
        val config = DuckBridgeConnectorConfig.from(properties)
        JdbcDriverUrlSecurity.check(config.driverUrl)
    }

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
