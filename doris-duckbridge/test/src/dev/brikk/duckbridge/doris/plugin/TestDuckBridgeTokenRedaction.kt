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
        assertThat(range.getProperties()["jdbc_password"]).isEqualTo(secret)
        assertThat(rendered).contains("query_sql=SELECT 1").contains("table_type=DUCKDB")
    }

    @Test
    fun getPropertiesStillCarriesRealTokenForTheBe() {
        val range = DuckBridgeJdbcScanRange.Builder().jdbcPassword(secret).build()
        assertThat(range.getProperties()["jdbc_password"]).isEqualTo(secret)
    }
}
