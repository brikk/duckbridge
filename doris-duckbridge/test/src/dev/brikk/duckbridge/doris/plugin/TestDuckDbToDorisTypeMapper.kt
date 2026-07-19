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

import org.apache.doris.connector.api.DorisConnectorException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Fast (no-Docker) unit tests pinning [DuckDbToDorisTypeMapper] to the P4-decided map. The integration
 * test proves quack-jdbc reports these exact `TYPE_NAME` strings; this proves the mapping of them.
 */
class TestDuckDbToDorisTypeMapper {

    private fun map(t: String) = DuckDbToDorisTypeMapper.toDorisType(t, "col")

    @Test
    fun scalars() {
        assertThat(map("BOOLEAN").typeName).isEqualTo("BOOLEAN")
        assertThat(map("TINYINT").typeName).isEqualTo("TINYINT")
        assertThat(map("SMALLINT").typeName).isEqualTo("SMALLINT")
        assertThat(map("INTEGER").typeName).isEqualTo("INT")
        assertThat(map("BIGINT").typeName).isEqualTo("BIGINT")
        assertThat(map("HUGEINT").typeName).isEqualTo("LARGEINT")
        assertThat(map("FLOAT").typeName).isEqualTo("FLOAT")
        assertThat(map("DOUBLE").typeName).isEqualTo("DOUBLE")
        assertThat(map("VARCHAR").typeName).isEqualTo("STRING")
    }

    @Test
    fun unsignedPromotion() {
        assertThat(map("UTINYINT").typeName).isEqualTo("SMALLINT")
        assertThat(map("USMALLINT").typeName).isEqualTo("INT")
        assertThat(map("UINTEGER").typeName).isEqualTo("BIGINT")
        assertThat(map("UBIGINT").typeName).isEqualTo("LARGEINT")
    }

    @Test
    fun decimalCarriesPrecisionScale() {
        val t = map("DECIMAL(38,10)")
        assertThat(t.typeName).isEqualTo("DECIMALV3")
        assertThat(t.precision).isEqualTo(38)
        assertThat(t.scale).isEqualTo(10)
        // Whitespace variant (defensive).
        assertThat(map("DECIMAL(9, 2)").precision).isEqualTo(9)
    }

    @Test
    fun temporal() {
        assertThat(map("DATE").typeName).isEqualTo("DATEV2")
        assertThat(map("TIME").typeName).isEqualTo("STRING")
        assertThat(map("TIMESTAMP").let { it.typeName to it.precision }).isEqualTo("DATETIMEV2" to 6)
        assertThat(map("TIMESTAMP_S").let { it.typeName to it.precision }).isEqualTo("DATETIMEV2" to 0)
        assertThat(map("TIMESTAMP_MS").let { it.typeName to it.precision }).isEqualTo("DATETIMEV2" to 3)
        assertThat(map("TIMESTAMP_NS").let { it.typeName to it.precision }).isEqualTo("DATETIMEV2" to 6)
        assertThat(map("TIMESTAMP WITH TIME ZONE").let { it.typeName to it.precision })
            .isEqualTo("DATETIMEV2" to 6)
    }

    @Test
    fun binaryAndSemiStructured() {
        assertThat(map("BLOB").typeName).isEqualTo("VARBINARY")
        assertThat(map("UUID").typeName).isEqualTo("STRING")
        assertThat(map("JSON").typeName).isEqualTo("STRING")
        assertThat(map("STRUCT(a INTEGER, b VARCHAR)").typeName).isEqualTo("STRING")
        assertThat(map("MAP(VARCHAR, INTEGER)").typeName).isEqualTo("STRING")
        assertThat(map("ENUM('sad', 'ok', 'happy')").typeName).isEqualTo("STRING")
    }

    @Test
    fun arrays() {
        val ints = map("INTEGER[]")
        assertThat(ints.typeName).isEqualTo("ARRAY")
        assertThat(ints.children.single().typeName).isEqualTo("INT")
        assertThat(map("VARCHAR[]").children.single().typeName).isEqualTo("STRING")
        // Nested list of largeint (HUGEINT[]).
        assertThat(map("HUGEINT[]").children.single().typeName).isEqualTo("LARGEINT")
    }

    @Test
    fun failsLoudOnUnmappable() {
        assertThatThrownBy { DuckDbToDorisTypeMapper.toDorisType("INTERVAL", "span") }
            .isInstanceOf(DorisConnectorException::class.java)
            .hasMessageContaining("span")
            .hasMessageContaining("INTERVAL")
        assertThatThrownBy { DuckDbToDorisTypeMapper.toDorisType("UHUGEINT", "big") }
            .isInstanceOf(DorisConnectorException::class.java)
            .hasMessageContaining("big")
        // An unmappable ARRAY element propagates the fail-loud.
        assertThatThrownBy { DuckDbToDorisTypeMapper.toDorisType("INTERVAL[]", "spans") }
            .isInstanceOf(DorisConnectorException::class.java)
    }
}
