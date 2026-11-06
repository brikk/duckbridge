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

import io.airlift.configuration.Config
import io.airlift.configuration.ConfigDescription
import io.airlift.units.DataSize

/** DuckBridge-specific catalog configuration; the connection URL is base-jdbc's `connection-url`. */
class DuckBridgeConfig {
    /** Disables DuckDB's signature verification for every `LOAD`; only for local unsigned builds. */
    var isAllowUnsignedExtensions: Boolean = false
        private set

    @Config("duckbridge.allow-unsigned-extensions")
    @ConfigDescription(
        "Open the embedded DuckDB with allow_unsigned_extensions (disables extension signature checks). " +
            "Only required for locally built, unsigned trino_parity binaries.",
    )
    fun setAllowUnsignedExtensions(allowUnsignedExtensions: Boolean): DuckBridgeConfig {
        this.isAllowUnsignedExtensions = allowUnsignedExtensions
        return this
    }

    /**
     * Catalog default for the `string_pushdown_mode` session property. Encodes both string-comparison
     * trust (NULL_ONLY < GUARDED < BINARY = FULL = PARITY) and whether the extension-backed ALIAS
     * functions push (PARITY only).
     */
    var stringPushdownMode: DuckBridgeStringPushdownMode = DuckBridgeStringPushdownMode.PARITY
        private set

    @Config("duckbridge.string-pushdown.mode")
    @ConfigDescription(
        "String predicate pushdown mode: NULL_ONLY, GUARDED, BINARY, FULL, or PARITY (default). " +
            "Only PARITY enables the trino_parity extension's ALIAS functions.",
    )
    fun setStringPushdownMode(mode: DuckBridgeStringPushdownMode): DuckBridgeConfig {
        this.stringPushdownMode = mode
        return this
    }

    // Off by default so a deployment without lance/vortex never pays the per-connection extension load.

    /** Enable the `lance_scan` / `lance_vector_search` / `lance_fts` / `lance_hybrid_search` PTFs. */
    var isLanceEnabled: Boolean = false
        private set

    @Config("duckbridge.lance.enabled")
    @ConfigDescription("Enable the lance scan + search table functions (requires the DuckDB lance extension)")
    fun setLanceEnabled(lanceEnabled: Boolean): DuckBridgeConfig {
        this.isLanceEnabled = lanceEnabled
        return this
    }

    /** Enable the `vortex_scan` PTF. */
    var isVortexEnabled: Boolean = false
        private set

    @Config("duckbridge.vortex.enabled")
    @ConfigDescription("Enable the vortex scan table function (requires the DuckDB vortex extension)")
    fun setVortexEnabled(vortexEnabled: Boolean): DuckBridgeConfig {
        this.isVortexEnabled = vortexEnabled
        return this
    }

    /**
     * Optional development path to a directly loadable `trino_parity.duckdb_extension`. Community
     * builds must retain DuckDB's installed `.info` provenance and are bundled automatically; this
     * override is for local unsigned builds or a server-side Quack development path.
     */
    var parityExtensionPath: String? = null
        private set

    @Config("duckbridge.parity-extension-path")
    @ConfigDescription("Development path to a directly loadable trino_parity.duckdb_extension")
    fun setParityExtensionPath(parityExtensionPath: String?): DuckBridgeConfig {
        this.parityExtensionPath = parityExtensionPath?.takeIf { it.isNotBlank() }
        return this
    }

    // Quack credentials live here rather than in the copy-pasteable connection-url.

    /** Quack auth token, passed to quack-jdbc's `token` property. Prefer [quackTokenEnv]/[quackTokenFile] for secrets. */
    var quackToken: String? = null
        private set

    @Config("duckbridge.quack.token")
    @ConfigDescription("Quack authentication token (quack-jdbc 'token' property); prefer token-env/token-file for secrets")
    fun setQuackToken(quackToken: String?): DuckBridgeConfig {
        this.quackToken = quackToken?.takeIf { it.isNotBlank() }
        return this
    }

    /** Environment variable name holding the Quack token (quack-jdbc's `tokenEnv`). */
    var quackTokenEnv: String? = null
        private set

    @Config("duckbridge.quack.token-env")
    @ConfigDescription("Name of an environment variable holding the Quack token (quack-jdbc 'tokenEnv')")
    fun setQuackTokenEnv(quackTokenEnv: String?): DuckBridgeConfig {
        this.quackTokenEnv = quackTokenEnv?.takeIf { it.isNotBlank() }
        return this
    }

    /** File path holding the Quack token (quack-jdbc's `tokenFile`). */
    var quackTokenFile: String? = null
        private set

    @Config("duckbridge.quack.token-file")
    @ConfigDescription("Path to a file holding the Quack token (quack-jdbc 'tokenFile')")
    fun setQuackTokenFile(quackTokenFile: String?): DuckBridgeConfig {
        this.quackTokenFile = quackTokenFile?.takeIf { it.isNotBlank() }
        return this
    }

    /** Whether the Quack HTTP transport uses TLS (`https://`). Maps to quack-jdbc's `tls`. */
    var isQuackTls: Boolean = false
        private set

    @Config("duckbridge.quack.tls")
    @ConfigDescription("Use TLS (https) for the Quack HTTP transport (quack-jdbc 'tls')")
    fun setQuackTls(quackTls: Boolean): DuckBridgeConfig {
        this.isQuackTls = quackTls
        return this
    }

    /** Data-plane read strategy; the Arrow engines are benchmark channels. */
    var executionEngine: DuckBridgeExecutionEngine = DuckBridgeExecutionEngine.JDBC
        private set

    @Config("duckbridge.execution-engine")
    @ConfigDescription("Data-plane engine: JDBC (default), DUCKDB_LOCAL or QUACK (Arrow, experimental)")
    fun setExecutionEngine(executionEngine: DuckBridgeExecutionEngine): DuckBridgeConfig {
        this.executionEngine = executionEngine
        return this
    }

    /** Quack server host for the QUACK execution engine; the quack-jdbc transport reads it from the URL. */
    var quackHost: String? = null
        private set

    @Config("duckbridge.quack.host")
    @ConfigDescription("Quack server host for the QUACK execution engine")
    fun setQuackHost(quackHost: String?): DuckBridgeConfig {
        this.quackHost = quackHost?.takeIf { it.isNotBlank() }
        return this
    }

    var quackPort: Int = DEFAULT_QUACK_PORT
        private set

    @Config("duckbridge.quack.port")
    @ConfigDescription("Quack server port for the QUACK execution engine")
    fun setQuackPort(quackPort: Int): DuckBridgeConfig {
        this.quackPort = quackPort
        return this
    }

    // ---- DuckDB tuning (applied by the Arrow executors) ------------------------------------

    var memoryLimit: DataSize? = null
        private set

    @Config("duckbridge.duckdb.memory-limit")
    @ConfigDescription("DuckDB memory_limit for the in-process execution engine (e.g. '4GB')")
    fun setMemoryLimit(memoryLimit: DataSize?): DuckBridgeConfig {
        this.memoryLimit = memoryLimit
        return this
    }

    var threads: Int? = null
        private set

    @Config("duckbridge.duckdb.threads")
    @ConfigDescription("DuckDB threads for the in-process execution engine")
    fun setThreads(threads: Int?): DuckBridgeConfig {
        this.threads = threads
        return this
    }

    fun toDuckDbTuning(): DuckDbTuning = DuckDbTuning(memoryLimit, threads, null, null, true)

    private companion object {
        private const val DEFAULT_QUACK_PORT = 9494
    }
}
