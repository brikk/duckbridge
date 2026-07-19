package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.Connector
import org.apache.doris.connector.spi.ConnectorContext
import org.apache.doris.connector.spi.ConnectorProvider

/**
 * `ServiceLoader` entry point for the duckbridge Doris connector. Registered via
 * `resources/META-INF/services/org.apache.doris.connector.spi.ConnectorProvider`, discovered at
 * FE startup, and gated by the FE `SPI_READY_TYPES` whitelist patch (see
 * `doris-patches/fe/0001-spi-ready-types-duckbridge.patch`) — a provider whose [getType] isn't
 * whitelisted is silently ignored.
 *
 * WIP scaffold: [create] wires the SPI object graph so the plugin *loads*; behavior beyond that
 * is stubbed to fail loud until the plan's P1–P6 probes are settled (see
 * `dev-docs/NOTES-scaffold.md`).
 */
class DuckBridgeConnectorProvider : ConnectorProvider {

    override fun getType(): String = TYPE

    override fun create(properties: Map<String, String>, context: ConnectorContext): Connector =
        DuckBridgeConnector(DuckBridgeConnectorConfig.from(properties), context)

    companion object {
        /**
         * Catalog type for `CREATE CATALOG ... type="duckbridge"`. A distinct type (not `jdbc`,
         * which routes to the in-tree fe-connector-jdbc) — see the plan's Deploy shape.
         */
        const val TYPE: String = "duckbridge"
    }
}
