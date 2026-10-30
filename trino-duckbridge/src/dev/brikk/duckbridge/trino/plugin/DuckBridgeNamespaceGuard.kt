package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import java.sql.Connection

/** Standard JDBC metadata loses parent-schema identity; never advertise its leaves as flat schemas. */
internal object DuckBridgeNamespaceGuard {
    fun requireFlat(connection: Connection): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT c.catalog_name,s.schema_name,s.parent_schema FROM (SELECT current_database() catalog_name) c " +
                    "LEFT JOIN duckdb_schemas() s ON s.database_name=c.catalog_name AND s.parent_schema_oid IS NOT NULL ORDER BY s.oid LIMIT 1",
            ).use { rows ->
                check(rows.next()) { "DuckDB did not report its current catalog" }
                if (rows.getString(2) != null) {
                    throw TrinoException(NOT_SUPPORTED,
                        "Nested DuckDB schema '${rows.getString(2)}' under '${rows.getString(3)}' in catalog '${rows.getString(1)}' " +
                            "cannot be exposed through flat JDBC schema metadata: ancestry would be lost. " +
                            "Use a database with flat schemas or explicit native SQL; literal dots in flat schema names remain supported.")
                }
                rows.getString(1)
            }
        }
}
