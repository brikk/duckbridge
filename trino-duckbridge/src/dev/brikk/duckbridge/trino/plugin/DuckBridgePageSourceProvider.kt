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
import io.trino.plugin.jdbc.ForBaseJdbc
import io.trino.plugin.jdbc.JdbcClient
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcPageSourceProvider
import io.trino.plugin.jdbc.JdbcSplit
import io.trino.plugin.jdbc.JdbcTableHandle
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.connector.ConnectorPageSource
import io.trino.spi.connector.ConnectorPageSourceProvider
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.ConnectorSplit
import io.trino.spi.connector.ConnectorTableCredentials
import io.trino.spi.connector.ConnectorTableHandle
import io.trino.spi.connector.ConnectorTransactionHandle
import io.trino.spi.connector.DynamicFilter
import io.trino.spi.type.Type
import org.duckdb.DuckDBConnection
import java.util.Optional

/**
 * Delegates to base-jdbc's [JdbcPageSourceProvider] for the JDBC engine; the Arrow engines read the
 * same base-jdbc-rendered SQL, so pushdown is identical and only the data plane differs.
 */
class DuckBridgePageSourceProvider
    @Inject
    constructor(
        @param:ForBaseJdbc private val jdbcClient: JdbcClient,
        private val config: DuckBridgeConfig,
        private val executorFactory: DuckBridgeExecutorFactory,
        private val delegate: JdbcPageSourceProvider,
    ) : ConnectorPageSourceProvider {
        @Suppress("LongParameterList", "OVERRIDE_DEPRECATION")
        override fun createPageSource(
            transaction: ConnectorTransactionHandle,
            session: ConnectorSession,
            split: ConnectorSplit,
            table: ConnectorTableHandle,
            credentials: Optional<ConnectorTableCredentials>,
            columns: List<ColumnHandle>,
            dynamicFilter: DynamicFilter,
        ): ConnectorPageSource {
            return when (config.executionEngine) {
                DuckBridgeExecutionEngine.JDBC ->
                    delegate.createPageSource(transaction, session, split, table, credentials, columns, dynamicFilter)
                DuckBridgeExecutionEngine.DUCKDB_LOCAL ->
                    createArrowPageSource(session, split as JdbcSplit, table as JdbcTableHandle, columns)
                DuckBridgeExecutionEngine.QUACK ->
                    createQuackArrowPageSource(session, split as JdbcSplit, table as JdbcTableHandle, columns)
            }
        }

        private fun createArrowPageSource(
            session: ConnectorSession,
            split: JdbcSplit,
            table: JdbcTableHandle,
            columns: List<ColumnHandle>,
        ): ConnectorPageSource {
            val jdbcColumns = columns.map { it as JdbcColumnHandle }
            val columnTypes: List<Type> = jdbcColumns.map { it.columnType }
            val duckDbZone = TrinoTimeZoneNormaliser.normalise(session.timeZoneKey.id)

            val executor = executorFactory.create() as? InProcessDuckBridgeExecutor
                ?: error("DUCKDB_LOCAL page source requires the in-process executor")
            var connection: DuckDBConnection? = null
            try {
                connection = executor.openPreparedConnection(duckDbZone)
                val statement = jdbcClient.buildSql(session, connection, split, table, jdbcColumns)
                val converter = DuckBridgeArrowToPageConverter(columnTypes)
                return DuckBridgeArrowPageSource(connection, statement, converter, jdbcColumns.size)
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                runCatching { connection?.close() }
                throw t
            }
        }

        /** `quack_query_by_name` takes a literal SQL string, so the split query's parameters are inlined. */
        private fun createQuackArrowPageSource(
            session: ConnectorSession,
            split: JdbcSplit,
            table: JdbcTableHandle,
            columns: List<ColumnHandle>,
        ): ConnectorPageSource {
            val jdbcColumns = columns.map { it as JdbcColumnHandle }
            val columnTypes: List<Type> = jdbcColumns.map { it.columnType }
            val duckDbZone = TrinoTimeZoneNormaliser.normalise(session.timeZoneKey.id)

            val client = jdbcClient as? DuckBridgeClient
                ?: error("QUACK page source requires the DuckBridgeClient (@ForBaseJdbc)")
            val prepared = client.renderSplitQuery(session, split, table, jdbcColumns)
            val sql = QuackParameterInliner.inline(prepared)

            val executor = executorFactory.create()
            var context: DuckBridgeExecutor.ExecutionContext? = null
            try {
                context = executor.execute(DuckBridgeExecutor.ExecutionRequest(sql, duckDbZone))
                val converter = DuckBridgeArrowToPageConverter(columnTypes)
                return DuckBridgeExecutorPageSource(context, converter, jdbcColumns.size)
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                runCatching { context?.close() }
                throw t
            }
        }
    }
