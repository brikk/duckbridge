package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.DorisConnectorException
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Opens short-lived quack-jdbc connections for metadata resolution.
 * Credentials stay out of the URL to avoid leaking through logs and EXPLAIN.
 */
internal class DuckBridgeQuackConnections(
    private val config: DuckBridgeConnectorConfig,
) {
    init {
        // DriverManager's system-classloader discovery cannot see the plugin's child classloader.
        try {
            Class.forName(config.driverClass)
        } catch (e: ClassNotFoundException) {
            throw DorisConnectorException(
                "duckbridge: quack-jdbc driver '${config.driverClass}' not found on the plugin " +
                    "classpath. It must be bundled in the plugin zip (plugins/connector/duckbridge/lib).",
                e,
            )
        }
    }

    /** Open an authenticated connection. Caller MUST close it (use `use { }`). */
    fun open(): Connection {
        val props = Properties()
        config.user?.let { props.setProperty("user", it) }
        config.password?.let { props.setProperty("token", it) }
        return try {
            DriverManager.getConnection(config.jdbcUrl, props)
        } catch (e: java.sql.SQLException) {
            throw DorisConnectorException(
                "duckbridge: could not connect to the DuckDB/Quack server at '${config.jdbcUrl}' " +
                    "(driver '${config.driverClass}'). ${e.message}",
                e,
            )
        }
    }

    fun <T> withConnection(block: (Connection) -> T): T = open().use(block)
}
