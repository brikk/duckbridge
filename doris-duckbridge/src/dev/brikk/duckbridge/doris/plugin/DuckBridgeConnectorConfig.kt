package dev.brikk.duckbridge.doris.plugin

/**
 * Catalog properties for the read-only DuckDB connector.
 * With [enableTimestampTz] disabled, timezone-aware values use a DATETIMEV2 UTC carrier.
 */
data class DuckBridgeConnectorConfig(
    val jdbcUrl: String,
    val driverClass: String = DEFAULT_DRIVER_CLASS,
    val driverUrl: String? = null,
    val user: String? = null,
    val password: String? = null,
    val enableTimestampTz: Boolean = false,
) {
    /**
     * The data-class default would expose the Quack auth token in logs.
     */
    override fun toString(): String =
        "DuckBridgeConnectorConfig(jdbcUrl=$jdbcUrl, driverClass=$driverClass, " +
            "driverUrl=$driverUrl, user=$user, password=${redact(password)}, " +
            "enableTimestampTz=$enableTimestampTz)"

    companion object {
        private fun redact(secret: String?): String = if (secret == null) "null" else "***"

        const val DEFAULT_DRIVER_CLASS: String = "com.gizmodata.quack.jdbc.sql.QuackDriver"

        const val PROP_JDBC_URL: String = "jdbc_url"
        const val PROP_DRIVER_CLASS: String = "driver_class"
        const val PROP_DRIVER_URL: String = "driver_url"
        const val PROP_USER: String = "user"
        const val PROP_PASSWORD: String = "password"
        const val PROP_ENABLE_TIMESTAMP_TZ: String = "enable.mapping.timestamp_tz"

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
                enableTimestampTz = properties[PROP_ENABLE_TIMESTAMP_TZ].toBoolean(),
            )
        }
    }
}
