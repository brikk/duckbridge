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
import io.airlift.log.Logger
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.connector.ConnectorSession
import java.sql.Connection
import java.sql.SQLException

/**
 * Owns parity-extension lifecycle for the connector: resolves the extension binary path once, and
 * on each session connection LOADs + probes the extension, failing loud if parity is enabled but the
 * extension can't be made available.
 *
 * "Fail loud over silently wrong": if the operator enabled parity (default) but the extension is
 * missing or won't load/probe, every query on that catalog throws a clear [TrinoException] with
 * install instructions rather than silently degrading to no function pushdown (which would look like
 * a mysterious perf cliff, not a misconfiguration).
 *
 * Transport-aware (P3):
 *  - EMBEDDED (T1): resolve a worker-local binary (bundled extraction or the configured path) and
 *    `LOAD '<local-path>'` over the in-process DuckDB connection.
 *  - QUACK (T3): the worker cannot extract a binary for the *remote* server. If
 *    `duckbridge.parity-extension-path` is set it is treated as a SERVER-SIDE path and LOADed over
 *    the pass-through connection; otherwise we assume the server pre-loaded the extension and just
 *    probe `trino_meta()`. Either way, failure throws with server-side install instructions.
 */
class DuckBridgeParity
    @Inject
    constructor(
        private val config: DuckBridgeConfig,
        private val transport: DuckBridgeTransport,
    ) {
        val isEnabled: Boolean get() = config.stringPushdownMode.requiresParityExtension

        /**
         * Resolved LOCAL extension path for the embedded transport (explicit override or bundled
         * extraction). Computed lazily and memoised. Never consulted for the Quack transport — a
         * worker-local binary can't be LOADed into a remote server.
         */
        private val resolvedLocalPath: String? by lazy {
            config.parityExtensionPath ?: TrinoParityExtensionResolver.resolveBundledExtensionPath()
        }

        /**
         * Resolve the effective string-pushdown mode for this connection: the session override when
         * present, else the catalog default. base-jdbc's internal metadata sessions may not carry the
         * property, in which case we fall back to the configured default.
         */
        private fun effectiveMode(session: ConnectorSession): DuckBridgeStringPushdownMode =
            try {
                DuckBridgeSessionProperties.getStringPushdownMode(session)
            } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") ignored: RuntimeException) {
                config.stringPushdownMode
            }

        /**
         * Per-connection string-pushdown init, keyed off the effective mode:
         *  - PARITY: LOAD (where applicable), then ONE consolidated query probes `trino_meta()`,
         *    `default_collation`, and all byte-comparison canaries. Fail loud if the extension is missing.
         *  - BINARY: run the same one query without `trino_meta()` (no extension). Fail loud if DuckDB's string
         *    comparison/ordering or `default_collation` diverges from Trino.
         *  - FULL/GUARDED/NULL_ONLY: no probe (FULL is caller-asserted; GUARDED/NULL_ONLY don't need
         *    byte alignment).
         *
         * Idempotent: LOAD of an already-loaded extension is a no-op. Validation deliberately remains
         * per-connection (not URL-cached) so a restarted remote instance is never trusted stale.
         *
         * @throws TrinoException if a required extension or comparison guarantee is unavailable.
         */
        fun ensureInitialised(connection: Connection, session: ConnectorSession) {
            val mode = effectiveMode(session)
            if (mode.requiresParityExtension) {
                when (transport) {
                    DuckBridgeTransport.EMBEDDED -> loadEmbedded(connection)
                    DuckBridgeTransport.QUACK -> loadQuackWhenConfigured(connection)
                }
            }
            if (mode.requiresComparisonProbe) {
                val remote = transport == DuckBridgeTransport.QUACK
                val result = runProbe(connection, mode, remote)
                requireParityRows(result, mode, remote)
                DuckBridgeStringComparisonProbe.verifyOrThrow(result, mode)
            }
        }

        @Throws(SQLException::class)
        private fun runProbe(
            connection: Connection,
            mode: DuckBridgeStringPushdownMode,
            remote: Boolean,
        ): DuckBridgeStringComparisonProbe.ProbeResult =
            try {
                DuckBridgeStringComparisonProbe.probe(connection, mode.requiresParityExtension)
            } catch (e: SQLException) {
                if (mode.requiresParityExtension) {
                    throw parityUnavailable(
                        "the consolidated startup probe could not resolve trino_meta() / comparison canaries: ${e.message}",
                        remote,
                        e,
                    )
                }
                throw e
            }

        private fun requireParityRows(
            result: DuckBridgeStringComparisonProbe.ProbeResult,
            mode: DuckBridgeStringPushdownMode,
            remote: Boolean,
        ) {
            if (mode.requiresParityExtension && (result.parityMetaRows ?: 0) <= 0) {
                val where = if (remote) "on the Quack server" else "after LOAD"
                throw parityUnavailable("trino_meta() returned no rows $where — extension did not register", remote)
            }
        }

        private fun loadEmbedded(connection: Connection) {
            val path = resolvedLocalPath
            val failure =
                when {
                    path == null ->
                        parityUnavailable(
                            "the trino_parity DuckDB extension binary was not found for this platform " +
                                "(${TrinoParityExtensionResolver.detectPlatform() ?: "unknown platform"})",
                            remote = false,
                        )
                    // Say precisely what is wrong rather than surfacing DuckDB's generic signature error.
                    !config.isAllowUnsignedExtensions && TrinoParityExtensionResolver.isUnsigned(path) ->
                        parityUnavailable(
                            "the trino_parity binary at '$path' is UNSIGNED (a local build, not the signed " +
                                "community-extensions release) and duckbridge.allow-unsigned-extensions is false. " +
                                "Either bundle/point at the signed community binary (`INSTALL trino_parity FROM community` " +
                                "and set duckbridge.parity-extension-path to it) or, for extension development only, set " +
                                "duckbridge.allow-unsigned-extensions=true",
                            remote = false,
                        )
                    else -> loadOnly(connection, path, remote = false)
                }
            if (failure != null) {
                throw failure
            }
        }

        private fun loadQuackWhenConfigured(connection: Connection) {
            // On a remote server we can only LOAD a path the SERVER can read. When configured, treat
            // duckbridge.parity-extension-path as a server-side path; otherwise assume the server
            // pre-loaded the extension and just probe.
            val serverPath = config.parityExtensionPath
            if (serverPath != null) {
                loadOnly(connection, serverPath, remote = true)?.let { throw it }
            }
        }

        /** LOAD only; the one consolidated probe runs after transport-specific loading. */
        private fun loadOnly(connection: Connection, path: String, remote: Boolean): TrinoException? =
            try {
                TrinoFunctionAliases.loadInProcess(connection, path)
                null
            } catch (e: SQLException) {
                parityUnavailable("failed to LOAD trino_parity from '$path': ${e.message}", remote, e)
            }

        private fun parityUnavailable(reason: String, remote: Boolean, cause: Throwable? = null): TrinoException {
            log.error(cause, "duckbridge: parity extension unavailable — %s", reason)
            val fix =
                if (remote) {
                    "Install the trino_parity extension on the Quack/DuckDB server (start it with " +
                        "`duckdb -unsigned` and `LOAD` the binary, or set duckbridge.parity-extension-path to a " +
                        "server-side path)"
                } else {
                    "Bundle the signed community binary (`.github/scripts/fetch-parity-extension.sh`, or build it " +
                        "locally with `(cd duckdb-trino-parity-extension && make)` + duckbridge.allow-unsigned-extensions=true), " +
                        "or set duckbridge.parity-extension-path to a valid binary"
                }
            return TrinoException(
                NOT_SUPPORTED,
                "DuckBridge PARITY string-pushdown mode requires the trino_parity extension, but it is unavailable: " +
                    "$reason. $fix, or select a non-PARITY duckbridge.string-pushdown.mode (e.g. GUARDED) / set " +
                    "session property string_pushdown_mode.",
                cause,
            )
        }

        private companion object {
            private val log: Logger = Logger.get(DuckBridgeParity::class.java)
        }
    }
