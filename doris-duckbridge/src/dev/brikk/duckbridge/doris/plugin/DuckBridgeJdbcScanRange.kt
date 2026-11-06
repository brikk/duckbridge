package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.scan.ConnectorScanRange

/**
 * One remote query executed by the BE's JDBC JNI scanner.
 * A JDBC scan cannot be partitioned, so each SELECT has one range.
 */
internal class DuckBridgeJdbcScanRange private constructor(
    private val properties: Map<String, String>,
) : ConnectorScanRange {

    override fun getPath(): java.util.Optional<String> = java.util.Optional.of("jdbc://virtual")

    override fun getTableFormatType(): String = TABLE_FORMAT_TYPE

    override fun getProperties(): Map<String, String> = properties

    override fun toString(): String {
        val masked = properties.entries.joinToString(", ") { (k, v) ->
            "$k=${if (k in SECRET_KEYS) "***" else v}"
        }
        return "DuckBridgeJdbcScanRange{$masked}"
    }

    class Builder {
        private val props = LinkedHashMap<String, String>()

        fun querySql(sql: String) = apply { props["query_sql"] = sql }
        fun jdbcUrl(url: String) = apply { props["jdbc_url"] = url }
        fun jdbcUser(user: String) = apply { props["jdbc_user"] = user }
        fun jdbcPassword(password: String) = apply { props["jdbc_password"] = password }
        fun driverClass(cls: String) = apply { props["jdbc_driver_class"] = cls }
        fun driverUrl(url: String) = apply { props["jdbc_driver_url"] = url }
        fun catalogId(id: Long) = apply { props["catalog_id"] = id.toString() }

        fun tableType(type: String) = apply { props["table_type"] = type }

        fun build(): DuckBridgeJdbcScanRange = DuckBridgeJdbcScanRange(LinkedHashMap(props))
    }

    companion object {
        const val TABLE_FORMAT_TYPE: String = "jdbc"

        /** `table_type` value that selects the patched `DuckDbTypeHandler` (doris-patches/be). */
        const val TABLE_TYPE_DUCKDB: String = "DUCKDB"

        private val SECRET_KEYS = setOf("jdbc_password")
    }
}
