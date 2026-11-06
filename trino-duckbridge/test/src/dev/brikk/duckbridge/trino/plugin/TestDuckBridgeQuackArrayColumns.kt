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

import com.gizmodata.quack.jdbc.sql.QuackDriver
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.Properties

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeQuackArrayColumns : AbstractTestQueryFramework() {
    private lateinit var server: TestingQuackServer
    private val schema = DuckBridgeQueryRunner.SCHEMA

    override fun createQueryRunner(): QueryRunner {
        server = TestingQuackServer()
        val extra =
            mapOf(
                "duckbridge.quack.token" to server.token,
                "duckbridge.string-pushdown.mode" to "GUARDED",
            )
        val runner = DuckBridgeQueryRunner.create(server.connectionUrl(), extra)
        runner.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.CATALOG}.$schema")
        return runner
    }

    @BeforeAll
    fun createData() {
        // Array columns can't be created through the connector (writes unsupported), so build the
        // table directly on the shared server DuckDB via a raw quack connection. The Quack server is
        // one long-lived DuckDB shared by all clients, so the connector's connections see it.
        val props = Properties().apply { setProperty("token", server.token) }
        QuackDriver().connect(server.connectionUrl(), props).use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE $schema.arr_t " +
                        "(id INTEGER, tags INTEGER[], labels VARCHAR[], vals DECIMAL(5,2)[])",
                )
                st.execute("INSERT INTO $schema.arr_t VALUES (1, [1,2,3], ['a','b'], [1.25, 2.50])")
            }
        }
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) {
            server.close()
        }
    }

    @Test
    fun scalarArrayTableColumnsResolveViaGetColumns() {
        val row = computeActual("SELECT tags, labels FROM arr_t WHERE id = 1").materializedRows.single()
        @Suppress("UNCHECKED_CAST")
        assertThat(row.getField(0) as List<Int>).containsExactly(1, 2, 3)
        @Suppress("UNCHECKED_CAST")
        assertThat(row.getField(1) as List<String>).containsExactly("a", "b")
    }

    @Test
    fun parametricDecimalArrayElementResolvesOverQuack() {
        val row = computeActual("SELECT vals FROM arr_t WHERE id = 1").materializedRows.single()
        @Suppress("UNCHECKED_CAST")
        assertThat(row.getField(0) as List<BigDecimal>)
            .containsExactly(BigDecimal("1.25"), BigDecimal("2.50"))
    }
}
