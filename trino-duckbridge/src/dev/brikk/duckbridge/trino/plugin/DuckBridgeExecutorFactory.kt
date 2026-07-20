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
import io.trino.plugin.jdbc.BaseJdbcConfig
import io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID
import io.trino.spi.TrinoException

/**
 * Builds the T2 [DuckBridgeExecutor] for the configured [DuckBridgeExecutionEngine]. Ported from the
 * DuckLake connector's `DucklakeDuckDbExecutorFactory`.
 *
 * Only consulted when the execution engine is a T2 Arrow engine (DUCKDB_LOCAL / QUACK); the default
 * JDBC path never touches this. Fails loud (CONFIGURATION_INVALID) when a required knob is missing.
 */
class DuckBridgeExecutorFactory
    @Inject
    constructor(
        private val config: DuckBridgeConfig,
        private val baseJdbcConfig: BaseJdbcConfig,
        private val parity: DuckBridgeParity,
    ) {
        fun create(): DuckBridgeExecutor {
            val tuning = config.toDuckDbTuning()
            return when (config.executionEngine) {
                DuckBridgeExecutionEngine.JDBC ->
                    throw TrinoException(
                        CONFIGURATION_INVALID,
                        "DuckBridgeExecutorFactory must not be used for the JDBC engine (no Arrow executor).",
                    )
                DuckBridgeExecutionEngine.DUCKDB_LOCAL ->
                    InProcessDuckBridgeExecutor(baseJdbcConfig.connectionUrl, tuning, localParityPathOrNull())
                DuckBridgeExecutionEngine.QUACK -> createQuackExecutor(tuning)
            }
        }

        private fun createQuackExecutor(tuning: DuckDbTuning): QuackDuckBridgeExecutor {
            val token = requiredConfig(config.quackToken, "duckbridge.quack.token")
            // Prefer explicit duckbridge.quack.host/port; otherwise reuse the jdbc:quack://host:port
            // connection-url base-jdbc already uses for metadata, so the address isn't configured twice.
            val explicitHost = config.quackHost
            val (host, port) =
                if (explicitHost != null) {
                    explicitHost to config.quackPort
                } else {
                    parseQuackHostPort(baseJdbcConfig.connectionUrl)
                        ?: throw TrinoException(
                            CONFIGURATION_INVALID,
                            "The QUACK execution engine needs a server address: set duckbridge.quack.host " +
                                "(and duckbridge.quack.port), or use a jdbc:quack://host:port connection-url.",
                        )
                }
            return QuackDuckBridgeExecutor(host, port, token, tuning, serverSideParityPathOrNull())
        }

        /** Extract host/port from a `jdbc:quack://host:port[/...]` connection-url; null if not a quack URL. */
        private fun parseQuackHostPort(connectionUrl: String): Pair<String, Int>? {
            val prefix = "jdbc:quack://"
            if (!connectionUrl.startsWith(prefix)) {
                return null
            }
            val authority = connectionUrl.removePrefix(prefix).substringBefore('/')
            val host = authority.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
            val port = authority.substringAfter(':', "").toIntOrNull() ?: config.quackPort
            return host to port
        }

        private fun requiredConfig(value: String?, key: String): String =
            value ?: throw TrinoException(CONFIGURATION_INVALID, "$key is required for the QUACK execution engine.")

        /** Local extension path for the in-process T2 engine — null disables parity for that engine. */
        private fun localParityPathOrNull(): String? {
            if (!config.stringPushdownMode.requiresParityExtension) {
                return null
            }
            return config.parityExtensionPath
                ?: TrinoParityExtensionResolver.resolveBundledExtensionPath()
                ?: throw TrinoException(
                    CONFIGURATION_INVALID,
                    "trino_parity extension required for the DUCKDB_LOCAL engine but not bundled for this platform; " +
                        "set duckbridge.parity-extension-path, or select a non-PARITY duckbridge.string-pushdown.mode " +
                        "(e.g. GUARDED) to run without the extension.",
                )
        }

        /**
         * Server-side extension path for the QUACK T2 engine. The worker's bundled binary can't be
         * read by the remote server, so this MUST be an explicit server-side path (or a non-PARITY mode).
         */
        private fun serverSideParityPathOrNull(): String? {
            if (!config.stringPushdownMode.requiresParityExtension) {
                return null
            }
            return config.parityExtensionPath
                ?: throw TrinoException(
                    CONFIGURATION_INVALID,
                    "trino_parity extension required for the QUACK engine in PARITY mode: set duckbridge.parity-extension-path " +
                        "to the SERVER-SIDE path of trino_parity.duckdb_extension inside the Quack DuckDB process, or select " +
                        "a non-PARITY duckbridge.string-pushdown.mode (e.g. GUARDED).",
                )
        }
    }
