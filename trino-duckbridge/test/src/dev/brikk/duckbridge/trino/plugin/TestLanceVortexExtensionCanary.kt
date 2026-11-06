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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * extensions.duckdb.org serves only the latest build per DuckDB version and platform, so FORCE INSTALL
 * checks the served build rather than a cached copy.
 * Skips when an extension can't be downloaded (offline / unsupported platform).
 */
class TestLanceVortexExtensionCanary {
    @Test
    fun lanceSurfaceMatchesVerifiedPin(@TempDir tempDir: Path) {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            assumeInstallable(connection, "lance", force = true)
            connection.createStatement().use { statement ->
                val version = queryString(statement, "SELECT extension_version FROM duckdb_extensions() WHERE extension_name='lance'")

                assertSignature(statement, "__lance_scan", "parameter_types[1] = 'VARCHAR'")
                assertSignature(
                    statement,
                    "lance_vector_search",
                    "parameter_types[1] = 'VARCHAR' AND parameter_types[2] = 'VARCHAR' AND parameter_types[3] = 'DOUBLE[]'",
                )
                assertSignature(statement, "lance_fts", "parameter_types[1] = 'VARCHAR' AND parameter_types[2] = 'VARCHAR'")
                assertSignature(statement, "lance_hybrid_search", "parameter_types[1] = 'VARCHAR'")

                val ds = tempDir.resolve("canary.lance").toString().replace("'", "''")
                statement.execute(
                    "COPY (SELECT * FROM (VALUES (1,'a',[1.0,0.0]::FLOAT[2]),(2,'b',[0.0,1.0]::FLOAT[2])) AS t(id,txt,emb)) " +
                        "TO '$ds' (FORMAT lance)",
                )
                assertThat(queryLong(statement, "SELECT count(*) FROM __lance_scan('$ds')")).isEqualTo(2L)
                assertThat(queryLong(statement, "SELECT id FROM lance_vector_search('$ds','emb',[1.0,0.0]::DOUBLE[], k := 1)"))
                    .isEqualTo(1L)

                assertThat(version)
                    .describedAs("lance build served by extensions.duckdb.org; surface checks above passed, so bump PINNED_LANCE if this trips")
                    .isEqualTo(PINNED_LANCE_VERSION)
            }
        }
    }

    @Test
    fun vortexSurfaceRoundTrips(@TempDir tempDir: Path) {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            assumeInstallable(connection, "vortex", force = false)
            connection.createStatement().use { statement ->
                assertSignature(statement, "read_vortex", "parameter_types[1] = 'VARCHAR'")
                val file = tempDir.resolve("canary.vortex").toString().replace("'", "''")
                statement.execute("COPY (SELECT 1 AS id, 'héllo' AS name) TO '$file' (FORMAT vortex)")
                assertThat(queryString(statement, "SELECT name FROM read_vortex('$file') WHERE id = 1")).isEqualTo("héllo")
            }
        }
    }

    private fun assertSignature(statement: Statement, functionName: String, condition: String) {
        val count =
            queryLong(statement, "SELECT count(*) FROM duckdb_functions() WHERE function_name = '$functionName' AND $condition")
        assertThat(count)
            .describedAs("DuckDB function %s with signature [%s] must exist — upstream churn hit the connector surface", functionName, condition)
            .isPositive()
    }

    private fun assumeInstallable(connection: Connection, extension: String, force: Boolean) {
        try {
            connection.createStatement().use { s ->
                s.execute("${if (force) "FORCE " else ""}INSTALL $extension")
                s.execute("LOAD $extension")
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            assumeTrue(false, "DuckDB '$extension' extension not downloadable (offline / unsupported platform): ${e.message}")
        }
    }

    private fun queryString(statement: Statement, sql: String): String =
        statement.executeQuery(sql).use { rs ->
            check(rs.next()) { "no row from: $sql" }
            rs.getString(1)
        }

    private fun queryLong(statement: Statement, sql: String): Long =
        statement.executeQuery(sql).use { rs ->
            check(rs.next()) { "no row from: $sql" }
            rs.getLong(1)
        }

    private companion object {
        private const val PINNED_LANCE_VERSION = "86118537da"
    }
}
