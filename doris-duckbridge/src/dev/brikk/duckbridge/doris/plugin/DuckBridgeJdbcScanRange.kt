package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.scan.ConnectorScanRange
import org.apache.doris.connector.api.scan.ConnectorScanRangeType

/**
 * One JDBC scan range = one remote DuckDB query execution. A JDBC scan is un-partitionable, so
 * there is exactly one of these per SELECT (probe P2).
 *
 * Mirrors the in-tree `JdbcScanRange` at our pin (verified): `getRangeType() == FILE_SCAN` (NOT
 * `JDBC_SCAN` — that enum exists but the JDBC path doesn't use it), `getTableFormatType() ==
 * "jdbc"` (routes the BE to its JDBC JNI reader), and `getFileFormat()` defaults to `"jni"`. The
 * default `ConnectorScanRange.populateRangeParams` copies [getProperties] straight into the thrift
 * `jdbc_params` map the BE's `JdbcJniScanner` reads. `table_type=DUCKDB` selects our patched
 * `DuckDbTypeHandler`; `required_fields`/`columns_types` are built by the BE from the scan slots
 * (not set here).
 */
internal class DuckBridgeJdbcScanRange private constructor(
    private val properties: Map<String, String>,
) : ConnectorScanRange {

    override fun getRangeType(): ConnectorScanRangeType = ConnectorScanRangeType.FILE_SCAN

    override fun getPath(): java.util.Optional<String> = java.util.Optional.of("jdbc://virtual")

    override fun getTableFormatType(): String = TABLE_FORMAT_TYPE

    override fun getProperties(): Map<String, String> = properties

    /**
     * REDACTED toString (Item 2): the properties map carries `jdbc_password` (the Quack token). The
     * default class toString is object-identity (no leak), but any explicit range-rendering /
     * debug dump would want the properties — so we provide a safe one that masks the secret key.
     * `getProperties()` itself is unchanged (the BE needs the real value); only this rendering masks.
     */
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

        /** BE `JdbcTypeHandlerFactory.create(tableType)` → our patched `DuckDbTypeHandler`. */
        fun tableType(type: String) = apply { props["table_type"] = type }

        fun build(): DuckBridgeJdbcScanRange = DuckBridgeJdbcScanRange(LinkedHashMap(props))
    }

    companion object {
        const val TABLE_FORMAT_TYPE: String = "jdbc"

        /** `table_type` value that selects the patched `DuckDbTypeHandler` (doris-patches/be). */
        const val TABLE_TYPE_DUCKDB: String = "DUCKDB"

        /** Property keys whose values are secrets and must be masked in [toString]. */
        private val SECRET_KEYS = setOf("jdbc_password")
    }
}
