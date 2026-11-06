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
 * The data-plane read strategy, selected via `duckbridge.execution-engine`.
 *
 *  - [JDBC] (default, production): the base-jdbc row-by-row path, over either transport.
 *  - [DUCKDB_LOCAL]: an Arrow page source over an in-process DuckDB. Benchmark only.
 *  - [QUACK]: the same Arrow page source over a remote DuckDB via the `quack` DuckDB extension
 *    (quack-jdbc has no Arrow surface). Benchmark only: Quack's fixed server-side connection pool
 *    exhausts under per-split churn.
 */
enum class DuckBridgeExecutionEngine {
    JDBC,
    DUCKDB_LOCAL,
    QUACK,
    ;

    val isArrowPageSource: Boolean get() = this != JDBC
}
