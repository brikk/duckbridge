package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle

@JvmRecord
internal data class DuckBridgeColumnHandle(
    val columnName: String,
    val columnType: ConnectorType,
    val duckdbType: String,
    val nullable: Boolean,
    val ordinalPosition: Int,
) : ConnectorColumnHandle
