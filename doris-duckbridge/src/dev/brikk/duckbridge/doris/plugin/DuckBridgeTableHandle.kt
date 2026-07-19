package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.handle.ConnectorTableHandle

/**
 * Opaque duckbridge table handle. The FE passes it back for every per-table op
 * (getTableSchema, getColumnHandles, planScan). Carries the resolved DuckDB namespace so those ops
 * need no extra round-trip to re-identify the table.
 *
 * [database] is the Doris database = DuckDB **schema** (P4 flattening: schema→database, main
 * catalog only). [catalog] is the DuckDB catalog the schema lives in (the connection's main
 * catalog; recorded so the scan SQL can qualify `catalog.schema.table`).
 */
@JvmRecord
internal data class DuckBridgeTableHandle(
    val catalog: String,
    val database: String,
    val table: String,
) : ConnectorTableHandle
