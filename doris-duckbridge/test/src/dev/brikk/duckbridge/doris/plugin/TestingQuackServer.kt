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
package dev.brikk.duckbridge.doris.plugin

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.sql.Connection
import java.util.Properties

/**
 * A real out-of-process DuckDB hosting a Quack RPC listener — the remote server the P4 metadata
 * probe and the over-Quack metadata tests talk to via `quack-jdbc` (`jdbc:quack://host:port`),
 * the same driver the FE uses at plan time.
 *
 * Module-local COPY of the trino module's fixture (copy-don't-share): the Docker context lives in
 * this module's `test/resources/docker/quack-server/`. Lean — no parity extension (P4 is pure
 * metadata; parity is P1's concern).
 */
internal class TestingQuackServer : AutoCloseable {
    private val container: GenericContainer<*> =
        GenericContainer(buildImage())
            .withExposedPorts(CONTAINER_PORT)
            .withEnv("QUACK_PORT", CONTAINER_PORT.toString())
            .withEnv("QUACK_TOKEN", TOKEN)
            .withStartupAttempts(3)
            .waitingFor(Wait.forListeningPort())

    val token: String = TOKEN

    init {
        container.start()
    }

    val host: String get() = container.host
    val mappedPort: Int get() = container.getMappedPort(CONTAINER_PORT)

    /** `jdbc:quack://host:mappedPort` — the connection URL the connector/tests pass to quack-jdbc. */
    fun connectionUrl(): String = "jdbc:quack://$host:$mappedPort"

    /** Open a quack-jdbc connection authenticated with the server token. Caller closes it. */
    fun openConnection(): Connection {
        // Load the driver on the test classpath (ServiceLoader may not pick it up under the test
        // JVM's classloader arrangement, so force it — mirrors how the connector does it).
        Class.forName(DRIVER_CLASS)
        val props = Properties().apply { setProperty("token", TOKEN) }
        return java.sql.DriverManager.getConnection(connectionUrl(), props)
    }

    /** Execute one or more `;`-free DDL/DML statements over a fresh connection. */
    fun exec(vararg statements: String) {
        openConnection().use { conn ->
            conn.createStatement().use { st ->
                for (sql in statements) {
                    st.execute(sql)
                }
            }
        }
    }

    override fun close() {
        container.stop()
    }

    companion object {
        private const val CONTAINER_PORT = 9494
        private const val TOKEN = "duckbridge-doris-token"

        /** gizmo quack-jdbc driver class (verified against the trino module's import). */
        const val DRIVER_CLASS: String = "com.gizmodata.quack.jdbc.sql.QuackDriver"

        private fun buildImage(): ImageFromDockerfile =
            // Testcontainers content-hashes the Dockerfile + files, so this builds once per test JVM.
            ImageFromDockerfile("doris-duckbridge-quack-server", false)
                .withFileFromClasspath("Dockerfile", "docker/quack-server/Dockerfile")
                .withFileFromClasspath("entrypoint.sh", "docker/quack-server/entrypoint.sh")
    }
}
