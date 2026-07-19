package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.ConnectorMetadata
import org.apache.doris.connector.api.ConnectorSession
import org.apache.doris.connector.api.ConnectorTableSchema
import org.apache.doris.connector.api.handle.ConnectorColumnHandle
import org.apache.doris.connector.api.handle.ConnectorTableHandle
import java.util.Optional

/**
 * Read-side metadata for duckbridge. The FE resolves schemas/tables/columns by querying the
 * user's DuckDB/Quack server through quack-jdbc `DatabaseMetaData` at plan time.
 *
 * WIP scaffold: metadata fidelity through quack-jdbc for the *Doris* type map is unproven (plan
 * probe P4 — the Trino-side validation does NOT transfer), so every resolution method fails loud
 * rather than returning a plausible-but-unverified answer. Listing methods return "nothing"
 * honestly (an empty catalog is a true statement about a not-yet-implemented resolver), while
 * anything that would assert a *shape* (handles, schema, columns) throws with a pointer to P4.
 * Per the repo rule: fail loud over silently wrong — never hand back a fabricated column type.
 */
internal class DuckBridgeDorisMetadata(
    @Suppress("unused") private val config: DuckBridgeConnectorConfig,
) : ConnectorMetadata {

    override fun listDatabaseNames(session: ConnectorSession?): List<String> = emptyList()

    override fun databaseExists(session: ConnectorSession?, database: String): Boolean = false

    override fun listTableNames(session: ConnectorSession?, database: String): List<String> = emptyList()

    override fun getTableHandle(
        session: ConnectorSession?,
        database: String,
        table: String,
    ): Optional<ConnectorTableHandle> = throw unimplemented("getTableHandle($database.$table)")

    override fun getTableSchema(
        session: ConnectorSession?,
        tableHandle: ConnectorTableHandle,
    ): ConnectorTableSchema = throw unimplemented("getTableSchema")

    override fun getColumnHandles(
        session: ConnectorSession?,
        tableHandle: ConnectorTableHandle,
    ): Map<String, ConnectorColumnHandle> = throw unimplemented("getColumnHandles")

    override fun close() {
        // No resources held in the scaffold.
    }

    private fun unimplemented(what: String): UnsupportedOperationException =
        UnsupportedOperationException(
            "duckbridge metadata: $what is not implemented yet — probe P4 " +
                "(quack-jdbc DatabaseMetaData fidelity for the Doris type map) must be settled " +
                "before resolving real schema. See dev-docs/NOTES-scaffold.md and " +
                "dev-docs/PLAN-doris-duckbridge.md §Open probes.",
        )
}
