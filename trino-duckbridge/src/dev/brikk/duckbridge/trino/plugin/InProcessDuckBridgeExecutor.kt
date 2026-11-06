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

import io.airlift.log.Logger
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.DuckDBConnection
import org.duckdb.DuckDBResultSet
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** [DuckBridgeExecutor] over a fresh embedded DuckDB connection per execution. */
class InProcessDuckBridgeExecutor(
    private val connectionUrl: String,
    private val tuning: DuckDbTuning,
    private val parityExtensionPath: String?,
    private val allowUnsignedExtensions: Boolean = false,
    private val parityExtensionDirectory: String? = null,
) : DuckBridgeExecutor {
    @Throws(SQLException::class)
    override fun openPreparedConnection(duckDbTimeZone: String?): DuckDBConnection {
        val connection = openRawConnection()
        try {
            loadParityExtension(connection)
            connection.createStatement().use { init ->
                DuckDbTuningSql.applyDirect(init, tuning)
                applySessionTimeZone(init, duckDbTimeZone)
            }
            return connection
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            closeQuietly(connection)
            throw e
        } catch (e: SQLException) {
            closeQuietly(connection)
            throw e
        }
    }

    private fun openRawConnection(): DuckDBConnection {
        // allow_unsigned_extensions is a startup setting: a runtime SET is rejected and closes the Statement.
        val props = Properties()
        if (allowUnsignedExtensions) {
            props.setProperty("allow_unsigned_extensions", "true")
        }
        return DriverManager.getConnection(connectionUrl, props) as DuckDBConnection
    }

    @Throws(SQLException::class)
    override fun execute(request: DuckBridgeExecutor.ExecutionRequest): DuckBridgeExecutor.ExecutionContext {
        val connection = openRawConnection()
        var statement: Statement? = null
        var resultSet: DuckDBResultSet? = null
        var allocator: BufferAllocator? = null
        var arrowReader: ArrowReader? = null
        try {
            loadParityExtension(connection)
            connection.createStatement().use { init ->
                DuckDbTuningSql.applyDirect(init, tuning)
                applySessionTimeZone(init, request.duckDbTimeZone)
            }
            statement = connection.createStatement()
            resultSet = statement.executeQuery(request.sql) as DuckDBResultSet
            allocator = RootAllocator()
            arrowReader = resultSet.arrowExportStream(allocator, ARROW_BATCH_SIZE) as ArrowReader
            return InProcessExecutionContext(connection, statement, resultSet, allocator, arrowReader)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            closeQuietly(arrowReader, resultSet, statement, allocator, connection)
            throw e
        } catch (e: SQLException) {
            closeQuietly(arrowReader, resultSet, statement, allocator, connection)
            throw e
        }
    }

    private fun loadParityExtension(connection: Connection) {
        if (parityExtensionDirectory != null) {
            TrinoFunctionAliases.loadInstalled(connection, parityExtensionDirectory)
        } else {
            parityExtensionPath?.let { TrinoFunctionAliases.loadInProcess(connection, it) }
        }
    }

    private class InProcessExecutionContext(
        private val connection: DuckDBConnection,
        private val statement: Statement,
        private val resultSet: DuckDBResultSet,
        private val allocator: BufferAllocator,
        private val arrowReader: ArrowReader,
    ) : DuckBridgeExecutor.ExecutionContext {
        override fun arrowReader(): ArrowReader = arrowReader

        override fun memoryUsage(): Long = allocator.allocatedMemory

        @Throws(IOException::class)
        override fun close() {
            closeQuietly(arrowReader, resultSet, statement, connection, allocator)
        }
    }

    companion object {
        private val log: Logger = Logger.get(InProcessDuckBridgeExecutor::class.java)
        private const val ARROW_BATCH_SIZE: Long = 1024

        private val timeZoneFailureWarned = ConcurrentHashMap<String, Boolean>()

        private fun applySessionTimeZone(stmt: Statement, zone: String?) {
            val z = zone ?: return
            try {
                stmt.execute("SET TimeZone = '" + z.replace("'", "''") + "'")
            } catch (@Suppress("SwallowedException") e: SQLException) {
                if (timeZoneFailureWarned.putIfAbsent(z, true) == null) {
                    log.warn(
                        "duckbridge: in-process DuckDB rejected SET TimeZone for zone '%s': %s. " +
                            "Pushed TIMESTAMP WITH TIME ZONE expressions may evaluate in a different zone for this split.",
                        z,
                        e.message?.lineSequence()?.firstOrNull() ?: e.message ?: "(no message)",
                    )
                }
            }
        }

        private fun closeQuietly(vararg resources: AutoCloseable?) {
            for (r in resources) {
                if (r == null) {
                    continue
                }
                try {
                    r.close()
                } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") ignored: Exception) {
                    // best-effort teardown
                }
            }
        }
    }
}
