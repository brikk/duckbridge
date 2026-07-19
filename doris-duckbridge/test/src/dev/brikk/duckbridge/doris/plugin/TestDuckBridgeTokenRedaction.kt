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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Item 2 — the Quack token rides `jdbc_password`; our OWN `toString`s must never print it. (At the
 * pin, FE-core already masks `password` in SHOW CREATE CATALOG / EXPLAIN VERBOSE / the audit log —
 * documented in NOTES-p5-p2-scan.md; this test guards the surfaces that ARE ours to redact.)
 */
class TestDuckBridgeTokenRedaction {

    private val secret = "s3cr3t-quack-token-DO-NOT-LEAK"

    @Test
    fun configToStringMasksPassword() {
        val config = DuckBridgeConnectorConfig(
            jdbcUrl = "jdbc:quack://duck:4200",
            user = "analyst",
            password = secret,
        )
        val rendered = config.toString()
        assertThat(rendered).doesNotContain(secret)
        assertThat(rendered).contains("password=***")
        // Non-secret fields stay for debuggability.
        assertThat(rendered).contains("jdbc:quack://duck:4200").contains("user=analyst")
    }

    @Test
    fun configToStringRendersAbsentPasswordAsNull() {
        val config = DuckBridgeConnectorConfig(jdbcUrl = "jdbc:quack://duck:4200")
        assertThat(config.toString()).contains("password=null").doesNotContain("***")
    }

    @Test
    fun scanRangeToStringMasksJdbcPassword() {
        val range = DuckBridgeJdbcScanRange.Builder()
            .querySql("SELECT 1")
            .jdbcUrl("jdbc:quack://duck:4200")
            .jdbcUser("analyst")
            .jdbcPassword(secret)
            .driverClass("com.gizmodata.quack.jdbc.sql.QuackDriver")
            .tableType(DuckBridgeJdbcScanRange.TABLE_TYPE_DUCKDB)
            .build()
        val rendered = range.toString()
        assertThat(rendered).doesNotContain(secret)
        assertThat(rendered).contains("jdbc_password=***")
        // The BE still gets the real value via getProperties() — only toString masks.
        assertThat(range.getProperties()["jdbc_password"]).isEqualTo(secret)
        // Non-secret props still visible.
        assertThat(rendered).contains("query_sql=SELECT 1").contains("table_type=DUCKDB")
    }

    @Test
    fun getPropertiesStillCarriesRealTokenForTheBe() {
        // Redaction must NOT break the wire: the BE needs the real jdbc_password.
        val range = DuckBridgeJdbcScanRange.Builder().jdbcPassword(secret).build()
        assertThat(range.getProperties()["jdbc_password"]).isEqualTo(secret)
    }
}
