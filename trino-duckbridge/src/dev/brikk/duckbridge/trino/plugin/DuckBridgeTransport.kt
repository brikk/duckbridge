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

/**
 * The JDBC transport, determined solely by the `connection-url` prefix: `jdbc:duckdb:` is an
 * in-process DuckDB, `jdbc:quack://` a remote DuckDB over the Quack RPC protocol.
 */
enum class DuckBridgeTransport {
    EMBEDDED,
    QUACK,
    ;

    val isRemote: Boolean get() = this == QUACK

    companion object {
        private const val EMBEDDED_PREFIX = "jdbc:duckdb:"
        private const val QUACK_PREFIX = "jdbc:quack:"

        fun fromConnectionUrl(connectionUrl: String): DuckBridgeTransport =
            when {
                connectionUrl.startsWith(EMBEDDED_PREFIX) -> EMBEDDED
                connectionUrl.startsWith(QUACK_PREFIX) -> QUACK
                else ->
                    throw IllegalArgumentException(
                        "Unsupported connection-url '$connectionUrl' for the duckbridge connector. " +
                            "Use 'jdbc:duckdb:...' (embedded) or 'jdbc:quack://host:port/...' (remote Quack).",
                    )
            }
    }
}
