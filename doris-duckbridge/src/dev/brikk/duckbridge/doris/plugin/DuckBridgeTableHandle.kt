package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.handle.ConnectorTableHandle

/**
 * [database] is a DuckDB schema, not a DuckDB catalog.
 */
@JvmRecord
internal data class DuckBridgeTableHandle(
    val catalog: String,
    val database: String,
    val table: String,
) : ConnectorTableHandle
