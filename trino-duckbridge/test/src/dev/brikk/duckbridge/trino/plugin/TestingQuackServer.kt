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

import com.gizmodata.quack.jdbc.sql.QuackConnection
import com.gizmodata.quack.jdbc.sql.QuackDriver
import com.github.dockerjava.api.model.ExposedPort
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * A real out-of-process DuckDB hosting a Quack RPC listener — the remote server the T3
 * (quack-jdbc) transport connects to. The test JVM's `quack-jdbc` driver speaks the Quack wire
 * protocol to it via `jdbc:quack://host:port`.
 *
 * Uses a `debian:trixie-slim` (GLIBC 2.41) base. [installParityExtension] copies the bundled binary
 * into the running container so a subsequent server-side load works.
 * Signature verification stays enabled for signed release binaries; local unsigned builds opt in
 * explicitly before DuckDB starts.
 */
internal class TestingQuackServer(testNetwork: Network? = null) : AutoCloseable {
    data class ParityInstallation(val serverPath: String?)

    private val container: GenericContainer<*> =
        GenericContainer(buildImage())
            .withExposedPorts(CONTAINER_PORT)
            .withEnv("QUACK_PORT", CONTAINER_PORT.toString())
            .withEnv("QUACK_TOKEN", TOKEN)
            .withEnv(
                "DUCKDB_ALLOW_UNSIGNED_EXTENSIONS",
                DuckBridgeQueryRunner.bundledParityIsUnsigned().toString(),
            )
            .withStartupAttempts(3)
            .waitingFor(Wait.forListeningPort())
            .apply { if (testNetwork != null) withNetwork(testNetwork).withNetworkAliases("quack") }

    val token: String = TOKEN

    init {
        container.start()
        val version = container.execInContainer(
            "duckdb",
            "-csv",
            "-noheader",
            "-c",
            "SELECT version();",
        ).stdout.trim()
        check(version == "v2.0.0") { "Quack test server version is '$version', expected v2.0.0" }
        quackConnection().use { connection ->
            val expected = System.getProperty("duckbridge.qualification.quack.version", "0.11.0")
            check(connection.metaData.driverVersion == expected) {
                "Expected Quack JDBC $expected, got ${connection.metaData.driverVersion}"
            }
            check((connection as QuackConnection).session().protocolVersion() == 3L) {
                "DuckDB-2 qualification must exercise negotiated Quack v3"
            }
        }
    }

    val host: String get() = container.host
    // Docker restart can reassign a dynamically published host port. GenericContainer caches its
    // original inspect response, so independent observers must resolve the current binding.
    val mappedPort: Int get() = container.currentContainerInfo.networkSettings.ports.bindings
        .getValue(ExposedPort.tcp(CONTAINER_PORT)).first().hostPortSpec.toInt()

    /** `jdbc:quack://host:mappedPort` — the base-jdbc `connection-url` for the T3 catalog. */
    fun connectionUrl(): String = "jdbc:quack://$host:$mappedPort"

    fun proxyTarget(): String = "quack:$CONTAINER_PORT"

    /** Restart only this fixture's server, preserving its mapped port, never a user's container. */
    fun restart() { container.dockerClient.restartContainerCmd(container.containerId).withTimeout(0).exec() }

    /**
     * Copy the bundled parity extension into the running container, selecting it by the container's
     * ACTUAL platform (`uname -m`), not the JVM host arch. Signed pairs go into DuckDB's installed
     * extension tree and load by name; unsigned development builds return a direct server path.
     * Returns null when no matching binary is available.
     */
    fun installParityExtension(): ParityInstallation? {
        val extension = TrinoParityExtensionResolver.resolveBundledExtensionFor(containerPlatform())
            ?: return null
        val binary = Path.of(extension.path)
        if (!Files.isRegularFile(binary)) {
            return null
        }
        val infoPath = extension.infoPath?.let(Path::of)
        if (infoPath != null) {
            check(Files.isRegularFile(infoPath)) { "Missing bundled install provenance: $infoPath" }
            val installedDirectory = "$CONTAINER_EXTENSION_ROOT/v2.0.0/${extension.duckDbPlatform}"
            container.execInContainer("mkdir", "-p", installedDirectory)
            val installedBinary = "$installedDirectory/trino_parity.duckdb_extension"
            container.copyFileToContainer(MountableFile.forHostPath(binary), installedBinary)
            container.copyFileToContainer(MountableFile.forHostPath(infoPath), "$installedBinary.info")
            quackConnection().use { connection ->
                connection.createStatement().use { statement -> statement.execute("LOAD trino_parity") }
            }
            return ParityInstallation(serverPath = null)
        }
        container.copyFileToContainer(MountableFile.forHostPath(binary), IN_CONTAINER_PARITY_PATH)
        return ParityInstallation(serverPath = IN_CONTAINER_PARITY_PATH)
    }

    /**
     * Server-side count of currently-active Quack connections, read via `quack_active_connections()`
     * over a short-lived quack-jdbc connection. Lets a test replace the inherited "pool exhaustion"
     * fear with an actual number: how many server threads the connector's per-query churn holds.
     * Note the reading connection itself counts as one while open.
     */
    fun activeConnectionCount(): Int {
        quackConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM quack_active_connections()").use { rs ->
                    check(rs.next()) { "quack_active_connections() returned no rows" }
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun quackConnection() =
        QuackDriver().connect(connectionUrl(), Properties().apply { setProperty("token", token) })

    private fun containerPlatform(): String =
        when (val arch = container.execInContainer("uname", "-m").stdout.trim()) {
            "x86_64" -> "linux-amd64"
            "aarch64" -> "linux-arm64"
            else -> error("Unexpected container arch: '$arch'")
        }

    override fun close() {
        container.stop()
    }

    companion object {
        private const val CONTAINER_PORT = 9494
        private const val TOKEN = "duckbridge-test-token"
        private const val CONTAINER_EXTENSION_ROOT = "/root/.duckdb/extensions"

        /** Server-side path where [installParityExtension] drops the binary for a server-side LOAD. */
        const val IN_CONTAINER_PARITY_PATH: String = "/opt/duckdb-extensions/trino_parity.duckdb_extension"

        private fun buildImage(): ImageFromDockerfile =
            // Testcontainers content-hashes the Dockerfile + files, so this builds once per test JVM.
            ImageFromDockerfile("duckbridge-quack-server", false)
                .withFileFromClasspath("Dockerfile", "docker/quack-server/Dockerfile")
                .withFileFromClasspath("entrypoint.sh", "docker/quack-server/entrypoint.sh")
    }
}
