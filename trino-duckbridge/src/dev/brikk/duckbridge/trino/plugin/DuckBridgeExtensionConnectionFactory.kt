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

import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.spi.connector.ConnectorSession
import java.sql.Connection
import java.sql.SQLException

/**
 * Loads the parity and lance/vortex extensions on every connection. This lives in the factory, not
 * `DuckBridgeClient.getConnection`, because base-jdbc metadata probes (`getTableHandle`,
 * `getColumns`) open connections directly and the scan table functions resolve columns through them.
 */
class DuckBridgeExtensionConnectionFactory(
    private val delegate: ConnectionFactory,
    private val parity: DuckBridgeParity,
    private val scanExtensions: DuckBridgeScanExtensions,
) : ConnectionFactory {
    @Throws(SQLException::class)
    override fun openConnection(session: ConnectorSession): Connection {
        val connection = delegate.openConnection(session)
        try {
            parity.ensureInitialised(connection, session)
            scanExtensions.ensureLoaded(connection)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            runCatching { connection.close() }
            throw e
        }
        return connection
    }

    @Throws(Exception::class)
    override fun close() {
        delegate.close()
    }
}
