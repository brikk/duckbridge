package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.DorisConnectorException
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Opens short-lived quack-jdbc connections to the user's DuckDB/Quack server from the catalog
 * config. The FE metadata plane opens one per resolution call and closes it (doris-ducklake's
 * plan-time round-trip pattern) — no long-lived FE-side pool.
 *
 * P2 note (see the probe report): FE metadata connections are separate from BE scan connections and
 * are low-frequency/one-at-a-time, so they don't stress Quack's fixed server-side pool. The pool
 * question that gated trino-duckbridge is BE per-split scan churn (P2's scope), not this path.
 *
 * Credentials never touch the URL (they'd leak into logs/EXPLAIN): the token rides quack-jdbc's
 * `token` connection property, matching the trino module.
 */
internal class DuckBridgeQuackConnections(
    private val config: DuckBridgeConnectorConfig,
) {
    init {
        // Force-register the driver on the plugin classloader: DriverManager's ServiceLoader
        // registration runs against the SYSTEM classloader at JVM start, but the plugin jar is on a
        // child classloader, so its META-INF/services/java.sql.Driver isn't discovered without an
        // explicit Class.forName from inside the plugin (same reason doris-ducklake force-loads its
        // Postgres driver). Registration persists for the (shared, long-lived) plugin classloader.
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
        // quack-jdbc auth token property (kept out of the URL so it can't leak into logs/EXPLAIN).
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

    /** Open a connection, run [block], and close it — the metadata plane's per-call pattern. */
    fun <T> withConnection(block: (Connection) -> T): T = open().use(block)
}
