package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.ConnectorType
import org.apache.doris.connector.api.handle.ConnectorColumnHandle

/**
 * Opaque duckbridge column handle. [ordinalPosition] is the column's index in the table schema
 * (used by the planner for projection ordering); [columnType] is the already-mapped Doris type
 * (see [DuckDbToDorisTypeMapper]); [duckdbType] is the raw DuckDB `TYPE_NAME` kept for the future
 * scan-SQL builder.
 */
@JvmRecord
internal data class DuckBridgeColumnHandle(
    val columnName: String,
    val columnType: ConnectorType,
    val duckdbType: String,
    val nullable: Boolean,
    val ordinalPosition: Int,
) : ConnectorColumnHandle
