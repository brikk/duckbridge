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

import com.google.inject.Inject
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import java.sql.Connection
import java.sql.SQLException

/**
 * INSTALLs and LOADs the DuckDB `lance` / `vortex` extensions on each connection when enabled. The
 * install floats to latest because extensions.duckdb.org serves only the latest build per DuckDB
 * version and platform, so a version pin is not possible.
 */
class DuckBridgeScanExtensions
    @Inject
    constructor(private val config: DuckBridgeConfig) {
        fun ensureLoaded(connection: Connection) {
            if (config.isLanceEnabled) {
                install(connection, LANCE)
            }
            if (config.isVortexEnabled) {
                install(connection, VORTEX)
            }
        }

        /** Gives a clear analyze-time error instead of DuckDB's "function not found". */
        fun requireEnabled(extension: String) {
            val enabled =
                when (extension) {
                    LANCE -> config.isLanceEnabled
                    VORTEX -> config.isVortexEnabled
                    else -> false
                }
            if (!enabled) {
                throw TrinoException(
                    NOT_SUPPORTED,
                    "The DuckDB '$extension' extension is not enabled. Set duckbridge.$extension.enabled=true " +
                        "on the catalog to use its scan/search table functions.",
                )
            }
        }

        private fun install(connection: Connection, extension: String) {
            try {
                connection.createStatement().use { stmt ->
                    stmt.execute("INSTALL $extension")
                    stmt.execute("LOAD $extension")
                }
            } catch (e: SQLException) {
                throw TrinoException(
                    NOT_SUPPORTED,
                    "Failed to INSTALL/LOAD the DuckDB '$extension' extension: ${e.message}. " +
                        "The connector installs it from DuckDB's extension repository (floating latest) — the " +
                        "executing DuckDB must have network access to extensions.duckdb.org, and the extension must " +
                        "be published for this DuckDB version + platform. For a remote Quack server, install it " +
                        "server-side.",
                    e,
                )
            }
        }

        companion object {
            const val LANCE: String = "lance"
            const val VORTEX: String = "vortex"
        }
    }
