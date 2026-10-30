package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.ConnectorColumn
import org.apache.doris.connector.spi.ConnectorMetadata
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.ConnectorTableSchema
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle
import org.apache.doris.connector.spi.handle.ConnectorTableHandle
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
            conn.metaData.getTables(catalog, literalPattern(conn, database), "%", USER_TABLE_TYPES).use { rs ->
                while (rs.next()) {
                    if (rs.getString("TABLE_CAT") == catalog && rs.getString("TABLE_SCHEM") == database) out.add(rs.getString("TABLE_NAME"))
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
            val exists = conn.metaData.getTables(catalog, literalPattern(conn, database), literalPattern(conn, table), USER_TABLE_TYPES).use { rows ->
                var exact = false
                while (rows.next()) {
                    if (rows.getString("TABLE_CAT") == catalog && rows.getString("TABLE_SCHEM") == database &&
                        rows.getString("TABLE_NAME") == table) exact = true
                }
                exact
            }
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

    /** JDBC Quack currently returns null for getCatalog; ask the engine, never guess from ATTACH listings. */
    private fun mainCatalog(conn: Connection): String {
        val catalog = conn.createStatement().use { statement ->
            statement.executeQuery("SELECT current_database()").use { rows ->
                check(rows.next()) { "DuckDB did not report its current catalog" }
                rows.getString(1)
            }
        }
        if (catalog.isNullOrBlank() || catalog in SYSTEM_CATALOGS) {
            throw DorisConnectorException(
                "duckbridge: '${config.jdbcUrl}' has no current user catalog. Select a user database; internal catalogs are not exposed.",
            )
        }
        requireFlatNamespace(conn, catalog)
        return catalog
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
        requireFlatNamespace(conn, handle.catalog)
        val out = ArrayList<DuckBridgeColumnHandle>()
        conn.metaData.getColumns(handle.catalog, literalPattern(conn, handle.database), literalPattern(conn, handle.table), "%").use { rs ->
            var ordinal = 0
            while (rs.next()) {
                if (rs.getString("TABLE_CAT") != handle.catalog || rs.getString("TABLE_SCHEM") != handle.database ||
                    rs.getString("TABLE_NAME") != handle.table) continue
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

    private fun literalPattern(conn: Connection, name: String): String {
        val escape = conn.metaData.searchStringEscape
        check(escape.isNotEmpty()) { "DuckDB metadata must provide its literal pattern escape" }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%")
    }

    private fun requireFlatNamespace(conn: Connection, catalog: String) {
        conn.prepareStatement(
            "SELECT schema_name,parent_schema FROM duckdb_schemas() WHERE database_name=? AND parent_schema_oid IS NOT NULL ORDER BY oid LIMIT 1",
        ).use { statement ->
            statement.setString(1, catalog)
            statement.executeQuery().use { rows ->
                if (rows.next()) throw DorisConnectorException(
                    "Nested DuckDB schema '${rows.getString(1)}' under '${rows.getString(2)}' in catalog '$catalog' cannot be exposed " +
                        "through flat JDBC schema metadata: ancestry would be lost. Use a database with flat schemas or explicit native SQL; " +
                        "literal dots in flat schema names remain supported.")
            }
        }
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
