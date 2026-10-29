package dev.brikk.duckbridge.trino.plugin

import com.gizmodata.quack.jdbc.sql.QuackConnection
import com.gizmodata.quack.jdbc.sql.QuackDriver
import java.sql.Connection
import java.util.Properties

internal data class QuackSessionSnapshot(val id: String, val query: String?, val state: String, val cachedRows: Long?)

internal fun lifecycleConnection(server: TestingQuackServer): QuackConnection = QuackDriver().connect(
    server.connectionUrl(), Properties().apply {
        setProperty("token", server.token)
        setProperty("connectTimeout", "PT2S")
        setProperty("requestTimeout", "PT5S")
    },
) as QuackConnection

/** Includes this short-lived observer session, which is disconnected before the method returns. */
internal fun sessionSnapshots(server: TestingQuackServer): List<QuackSessionSnapshot> = lifecycleConnection(server).use { connection ->
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT connection_id,query,state,cached_rows FROM quack_active_connections()").use { rows ->
            buildList {
                while (rows.next()) {
                    val cached = rows.getLong("cached_rows").let { if (rows.wasNull()) null else it }
                    add(QuackSessionSnapshot(rows.getString("connection_id"), rows.getString("query"), rows.getString("state"), cached))
                }
            }
        }
    }
}

internal fun lifecycleControl(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT 42").use { rows ->
            check(rows.next() && rows.getInt(1) == 42 && !rows.next()) { "Independent session control failed" }
        }
    }
}
