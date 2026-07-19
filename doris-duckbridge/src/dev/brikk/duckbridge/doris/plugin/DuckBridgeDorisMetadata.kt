package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.ConnectorColumn
import org.apache.doris.connector.api.ConnectorMetadata
import org.apache.doris.connector.api.ConnectorSession
import org.apache.doris.connector.api.ConnectorTableSchema
import org.apache.doris.connector.api.DorisConnectorException
import org.apache.doris.connector.api.handle.ConnectorColumnHandle
import org.apache.doris.connector.api.handle.ConnectorTableHandle
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.util.Optional

/**
 * Read-side metadata for duckbridge — the REAL implementation, built on what probe P4 proved about
 * quack-jdbc `DatabaseMetaData` fidelity (`dev-docs/REPORT-quack-jdbc-metadata-probe.md`).
 *
 * Namespace flattening (P4): DuckDB is 3-level (`catalog.schema.table`), Doris catalogs are 2-level
 * (`database.table`). We map **DuckDB schema → Doris database**, scoped to the connection's **main
 * catalog only** (discovered from the connection, not hard-coded — an in-memory server's is
 * `memory`, a file/attached DB's is its name). DuckDB-internal catalogs (`system`, `temp`) and
 * schemas (`information_schema`, `pg_catalog`) are excluded, and only `TABLE`/`VIEW` table types are
 * listed (not `SYSTEM VIEW`). Multi-catalog (`ATTACH`) is deferred (report §Open question).
 *
 * Types map via [DuckDbToDorisTypeMapper] keyed off `getColumns().TYPE_NAME` (the faithful field;
 * `DATA_TYPE` is lossy). Unmappable types fail loud naming the column.
 *
 * Connections are short-lived: one per resolution call, closed immediately (doris-ducklake's
 * plan-time round-trip pattern; P2 note in the report).
 */
internal class DuckBridgeDorisMetadata(
    private val config: DuckBridgeConnectorConfig,
    private val connections: DuckBridgeQuackConnections,
    private val enableTimestampTz: Boolean,
) : ConnectorMetadata {

    override fun listDatabaseNames(session: ConnectorSession?): List<String> =
        connections.withConnection { conn -> userSchemas(conn, mainCatalog(conn)) }

    override fun databaseExists(session: ConnectorSession?, database: String): Boolean =
        connections.withConnection { conn -> userSchemas(conn, mainCatalog(conn)).contains(database) }

    override fun listTableNames(session: ConnectorSession?, database: String): List<String> =
        connections.withConnection { conn ->
            val catalog = mainCatalog(conn)
            val out = ArrayList<String>()
            conn.metaData.getTables(catalog, database, "%", USER_TABLE_TYPES).use { rs ->
                while (rs.next()) {
                    out.add(rs.getString("TABLE_NAME"))
                }
            }
            out
        }

    override fun getTableHandle(
        session: ConnectorSession?,
        database: String,
        table: String,
    ): Optional<ConnectorTableHandle> =
        connections.withConnection { conn ->
            val catalog = mainCatalog(conn)
            val exists = conn.metaData.getTables(catalog, database, table, USER_TABLE_TYPES).use { it.next() }
            if (exists) {
                Optional.of(DuckBridgeTableHandle(catalog, database, table))
            } else {
                Optional.empty()
            }
        }

    override fun getTableSchema(
        session: ConnectorSession?,
        tableHandle: ConnectorTableHandle,
    ): ConnectorTableSchema {
        val handle = tableHandle.asDuckBridgeHandle()
        val columns = connections.withConnection { conn -> resolveColumns(conn, handle) }
        val connectorColumns = ArrayList<ConnectorColumn>(columns.size)
        for (col in columns) {
            connectorColumns.add(
                ConnectorColumn(
                    col.columnName,
                    col.columnType,
                    "", // DuckDB column comments not surfaced in v1
                    col.nullable,
                    null, // no default-value backfill for a live JDBC source
                ),
            )
        }
        return ConnectorTableSchema(
            handle.table,
            connectorColumns,
            DuckBridgeConnectorProvider.TYPE,
            emptyMap(),
        )
    }

    override fun getColumnHandles(
        session: ConnectorSession?,
        tableHandle: ConnectorTableHandle,
    ): Map<String, ConnectorColumnHandle> {
        val handle = tableHandle.asDuckBridgeHandle()
        val columns = connections.withConnection { conn -> resolveColumns(conn, handle) }
        // LinkedHashMap preserves DuckDB's column order so DESC / SELECT * line up.
        val out = LinkedHashMap<String, ConnectorColumnHandle>(columns.size)
        for (col in columns) {
            out[col.columnName] = col
        }
        return out
    }

    override fun close() {
        // Connections are per-call and self-closing; nothing long-lived to release here.
    }

    // ---- resolution helpers ----

    /**
     * The connection's main catalog: the single non-system, non-temp catalog quack reports. DuckDB
     * exposes `system` + `temp` internally plus the user's DB (`memory` in-memory, or the attached
     * name). v1 uses exactly one user catalog (ATTACH/multi-catalog deferred — report §Open
     * question), so more than one is a fail-loud (we won't guess which the user meant).
     */
    private fun mainCatalog(conn: Connection): String {
        conn.catalog?.takeIf { it.isNotBlank() && it !in SYSTEM_CATALOGS }?.let { return it }
        val user = ArrayList<String>()
        conn.metaData.catalogs.use { rs ->
            while (rs.next()) {
                val cat = rs.getString("TABLE_CAT")
                if (cat != null && cat !in SYSTEM_CATALOGS) {
                    user.add(cat)
                }
            }
        }
        return when (user.size) {
            1 -> user[0]
            0 -> throw DorisConnectorException(
                "duckbridge: the DuckDB/Quack server at '${config.jdbcUrl}' exposes no user catalog " +
                    "(only ${SYSTEM_CATALOGS.joinToString()}). Attach or create a database.",
            )
            else -> throw DorisConnectorException(
                "duckbridge: the DuckDB/Quack server exposes multiple user catalogs " +
                    "(${user.joinToString()}). v1 supports a single (main) catalog only — multi-catalog " +
                    "ATTACH mapping is not implemented yet (see the P4 report §Open question).",
            )
        }
    }

    /** User schemas (Doris databases) under [catalog]: non-system schemas only. */
    private fun userSchemas(conn: Connection, catalog: String): List<String> {
        val out = ArrayList<String>()
        conn.metaData.getSchemas(catalog, "%").use { rs ->
            while (rs.next()) {
                val schema = rs.getString("TABLE_SCHEM")
                if (schema != null && schema !in SYSTEM_SCHEMAS) {
                    out.add(schema)
                }
            }
        }
        return out
    }

    /** Resolve a table's columns to duckbridge handles, mapping each type off the faithful TYPE_NAME. */
    private fun resolveColumns(conn: Connection, handle: DuckBridgeTableHandle): List<DuckBridgeColumnHandle> {
        val out = ArrayList<DuckBridgeColumnHandle>()
        conn.metaData.getColumns(handle.catalog, handle.database, handle.table, "%").use { rs ->
            var ordinal = 0
            while (rs.next()) {
                val name = rs.getString("COLUMN_NAME")
                val duckdbType = rs.getString("TYPE_NAME")
                val nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls
                val dorisType = DuckDbToDorisTypeMapper.toDorisType(duckdbType, name, enableTimestampTz)
                out.add(DuckBridgeColumnHandle(name, dorisType, duckdbType, nullable, ordinal++))
            }
        }
        if (out.isEmpty()) {
            throw DorisConnectorException(
                "duckbridge: table '${handle.database}.${handle.table}' has no columns (or was " +
                    "dropped between resolution calls).",
            )
        }
        return out
    }

    private fun ConnectorTableHandle.asDuckBridgeHandle(): DuckBridgeTableHandle =
        this as? DuckBridgeTableHandle
            ?: throw DorisConnectorException(
                "duckbridge received a foreign table handle: ${this::class.java.name}",
            )

    private companion object {
        // DuckDB-internal catalogs / schemas excluded from user-facing listing (probe P4).
        val SYSTEM_CATALOGS = setOf("system", "temp")
        val SYSTEM_SCHEMAS = setOf("information_schema", "pg_catalog")

        // getTables type filter: base tables report "TABLE", views "VIEW"; "SYSTEM VIEW" /
        // "LOCAL TEMPORARY" are DuckDB internals we don't surface (probe P4).
        val USER_TABLE_TYPES = arrayOf("TABLE", "VIEW")
    }
}
