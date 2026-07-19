package dev.brikk.duckbridge.doris.plugin

/**
 * Immutable holder for the catalog properties duckbridge reads off `CREATE CATALOG`.
 *
 * WIP scaffold: only the DuckDB/Quack JDBC connection coordinates are modeled so far — the
 * pushdown/parity and type-mapping properties land as the P1–P6 probes settle (see
 * `dev-docs/NOTES-scaffold.md`). Kept a plain, tested value object so the SPI wiring has
 * something honest to hold without pretending at behavior.
 *
 * @property jdbcUrl the quack-jdbc URL for the user's DuckDB/Quack server (`jdbc:quack://host:port`).
 * @property driverClass the JDBC driver class name (defaults to quack-jdbc's driver).
 * @property driverUrl optional checksum-gated driver jar URL (Doris `jdbc_drivers_dir` flow); null
 *   when the driver is already on the BE classpath.
 * @property user optional DuckDB/Quack user.
 * @property password optional DuckDB/Quack credential / Quack token.
 */
data class DuckBridgeConnectorConfig(
    val jdbcUrl: String,
    val driverClass: String = DEFAULT_DRIVER_CLASS,
    val driverUrl: String? = null,
    val user: String? = null,
    val password: String? = null,
) {
    companion object {
        /** quack-jdbc's driver class (`jdbc:quack://...`). */
        const val DEFAULT_DRIVER_CLASS: String = "com.gizmodata.quackjdbc.QuackDriver"

        const val PROP_JDBC_URL: String = "jdbc_url"
        const val PROP_DRIVER_CLASS: String = "driver_class"
        const val PROP_DRIVER_URL: String = "driver_url"
        const val PROP_USER: String = "user"
        const val PROP_PASSWORD: String = "password"

        /**
         * Build a config from raw catalog properties. Requires [PROP_JDBC_URL]; everything else is
         * optional. Fails loud (never silently defaults a missing URL) per the repo's fail-loud rule.
         */
        fun from(properties: Map<String, String>): DuckBridgeConnectorConfig {
            val jdbcUrl = properties[PROP_JDBC_URL]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "duckbridge catalog property '$PROP_JDBC_URL' is required " +
                        "(e.g. 'jdbc:quack://host:port')",
                )
            return DuckBridgeConnectorConfig(
                jdbcUrl = jdbcUrl,
                driverClass = properties[PROP_DRIVER_CLASS]?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_DRIVER_CLASS,
                driverUrl = properties[PROP_DRIVER_URL]?.takeIf { it.isNotBlank() },
                user = properties[PROP_USER]?.takeIf { it.isNotBlank() },
                password = properties[PROP_PASSWORD]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
