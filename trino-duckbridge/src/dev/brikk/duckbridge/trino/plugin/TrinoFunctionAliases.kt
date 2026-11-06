/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.duckbridge.trino.plugin

import java.sql.Connection
import java.sql.SQLException

/**
 * Loads the `trino_parity` DuckDB extension, which registers the `trino_<name>(...)` functions the
 * pushdown translator emits. A locally built unsigned binary needs `allow_unsigned_extensions=true`
 * when the connection is created.
 */
object TrinoFunctionAliases {
    /**
     * LOAD the trino_parity extension into an in-process JDBC DuckDB connection. Uses a scratch
     * [java.sql.Statement] so a failure propagates without closing the caller's main statement
     * (DuckDB JDBC auto-closes a Statement on any SQLException).
     */
    @Throws(SQLException::class)
    fun loadInProcess(connection: Connection, path: String) {
        connection.createStatement().use { scratch ->
            scratch.execute("LOAD '" + path.replace("'", "''") + "'")
        }
    }

    /** Load a signed community extension from its extracted DuckDB installation root. */
    @Throws(SQLException::class)
    fun loadInstalled(connection: Connection, extensionDirectory: String) {
        val escaped = extensionDirectory.replace("'", "''")
        connection.createStatement().use { scratch ->
            scratch.execute("SET GLOBAL extension_directories=['$escaped']")
            scratch.execute("LOAD trino_parity")
        }
    }

    @Throws(SQLException::class)
    fun loadBundled(
        connection: Connection,
        extension: TrinoParityExtensionResolver.BundledExtension,
    ) {
        if (extension.extensionDirectory != null) {
            loadInstalled(connection, extension.extensionDirectory)
        } else {
            loadInProcess(connection, extension.path)
        }
    }
}
