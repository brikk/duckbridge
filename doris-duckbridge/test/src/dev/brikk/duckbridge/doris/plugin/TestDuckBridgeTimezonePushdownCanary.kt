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

import org.apache.doris.connector.api.ConnectorType
import org.apache.doris.connector.api.handle.ConnectorColumnHandle
import org.apache.doris.connector.api.pushdown.ConnectorColumnRef
import org.apache.doris.connector.api.pushdown.ConnectorComparison
import org.apache.doris.connector.api.pushdown.ConnectorExpression
import org.apache.doris.connector.api.pushdown.ConnectorLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * P3/P6 drift canary — proves the connector's temporal pushdown is **server-zone independent**.
 *
 * For a naive `TIMESTAMP` column and a `TIMESTAMPTZ` column, this runs the EXACT SQL the connector
 * composes ([DuckBridgeQueryBuilder]) against a DuckDB/quack server started in BOTH UTC and an
 * exotic non-UTC zone (`America/Los_Angeles`), and asserts identical rows. If the rendering ever
 * regressed to the zone-dependent naive-literal-vs-TIMESTAMPTZ form, the LA run would return
 * different rows and this fails. (The report `dev-docs/REPORT-doris-timezone-probe.md` records the
 * live evidence this test guards.)
 *
 * Requires Docker. Two containers (one per zone) — small, torn down at test end.
 */
class TestDuckBridgeTimezonePushdownCanary {

    private fun cols(vararg names: String): List<ConnectorColumnHandle> =
        names.map { DuckBridgeColumnHandle(it, ConnectorType.of("STRING"), "VARCHAR", true, 0) }

    private fun ge(col: String, lit: ConnectorLiteral): ConnectorExpression =
        ConnectorComparison(
            ConnectorComparison.Operator.GE,
            ConnectorColumnRef(col, ConnectorType.of("DATETIMEV2", 6, 0)),
            lit,
        )

    private val boundary = ConnectorLiteral.ofDatetime(LocalDateTime.of(2024, 6, 1, 6, 30, 0))

    /** Seed the fixture table + return the id set matching the connector-rendered predicate. */
    private fun idsMatching(
        server: TestingQuackServer,
        col: String,
        duckdbType: String,
    ): List<Int> {
        val sql = DuckBridgeQueryBuilder.buildQuery(
            "memory", "sales", "t", cols("id"), ge(col, boundary), -1,
            mapOf(col to duckdbType),
        )
        // The predicate MUST push (temporal pushdown is enabled and the column type is known).
        assertThat(sql).describedAs("temporal predicate must push: $sql").contains("WHERE")
        val out = ArrayList<Int>()
        server.openConnection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("$sql ORDER BY id").use { rs -> while (rs.next()) out.add(rs.getInt(1)) }
            }
        }
        return out
    }

    private fun seed(server: TestingQuackServer) {
        server.exec(
            "CREATE SCHEMA sales",
            "CREATE TABLE sales.t (id INTEGER, dt TIMESTAMP, ts TIMESTAMPTZ)",
            // Two rows straddling the 06:30 UTC boundary. Instants chosen so a zone-dependent
            // (buggy) rendering would flip membership under a non-UTC session zone.
            "INSERT INTO sales.t VALUES " +
                "(1, TIMESTAMP '2024-06-01 06:30:00', TIMESTAMPTZ '2024-06-01 06:30:00+00')," +
                "(2, TIMESTAMP '2024-06-01 08:00:00', TIMESTAMPTZ '2024-06-01 08:00:00+00')",
        )
    }

    @Test
    fun temporalPushdownIsServerZoneIndependent() {
        // Expected rows are the SAME at both zones (both instants ≥ the 06:30 UTC boundary).
        val expected = listOf(1, 2)

        TestingQuackServer(serverTimeZone = "Etc/UTC").use { utc ->
            seed(utc)
            assertThat(idsMatching(utc, "dt", "TIMESTAMP")).describedAs("naive dt @UTC").isEqualTo(expected)
            assertThat(idsMatching(utc, "ts", "TIMESTAMP WITH TIME ZONE")).describedAs("tz @UTC").isEqualTo(expected)
        }

        TestingQuackServer(serverTimeZone = "America/Los_Angeles").use { la ->
            seed(la)
            // The decisive assertion: SAME rows under an exotic server zone. A zone-dependent
            // rendering (naive literal vs TIMESTAMPTZ) would return {} here for the tz column.
            assertThat(idsMatching(la, "dt", "TIMESTAMP")).describedAs("naive dt @LA").isEqualTo(expected)
            assertThat(idsMatching(la, "ts", "TIMESTAMP WITH TIME ZONE")).describedAs("tz @LA").isEqualTo(expected)
        }
    }
}
