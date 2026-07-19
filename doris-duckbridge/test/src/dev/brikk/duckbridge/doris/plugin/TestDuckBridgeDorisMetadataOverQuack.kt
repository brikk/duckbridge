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
import org.apache.doris.connector.api.DorisConnectorException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Integration test for the REAL metadata plane (probe P4 output): [DuckBridgeDorisMetadata]
 * resolving schemas/tables/columns over a live quack-jdbc connection to a testcontainer DuckDB,
 * asserting the probe-decided type map. Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeDorisMetadataOverQuack {

    private lateinit var server: TestingQuackServer
    private lateinit var metadata: DuckBridgeDorisMetadata

    @BeforeAll
    fun setUp() {
        server = TestingQuackServer()
        server.exec(
            "CREATE SCHEMA sales",
            "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')",
            """
            CREATE TABLE sales.all_types (
                c_boolean      BOOLEAN,
                c_tinyint      TINYINT,
                c_smallint     SMALLINT,
                c_integer      INTEGER,
                c_bigint       BIGINT,
                c_hugeint      HUGEINT,
                c_utinyint     UTINYINT,
                c_usmallint    USMALLINT,
                c_uinteger     UINTEGER,
                c_ubigint      UBIGINT,
                c_float        FLOAT,
                c_double       DOUBLE,
                c_dec          DECIMAL(38,10),
                c_varchar      VARCHAR,
                c_blob         BLOB,
                c_date         DATE,
                c_time         TIME,
                c_timestamp    TIMESTAMP,
                c_timestamptz  TIMESTAMPTZ,
                c_ts_ns        TIMESTAMP_NS,
                c_uuid         UUID,
                c_json         JSON,
                c_list_int     INTEGER[],
                c_list_varchar VARCHAR[],
                c_struct       STRUCT(a INTEGER, b VARCHAR),
                c_map          MAP(VARCHAR, INTEGER),
                c_enum         mood,
                c_notnull      INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE TABLE sales.simple (id BIGINT, name VARCHAR)",
            // Unicode table + column names.
            "CREATE TABLE sales.\"tãble_ünï\" (\"cölumn_ä\" INTEGER, val VARCHAR)",
        )
        val config = DuckBridgeConnectorConfig(
            jdbcUrl = server.connectionUrl(),
            password = server.token,
        )
        metadata = DuckBridgeDorisMetadata(config, DuckBridgeQuackConnections(config), enableTimestampTz = false)
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) {
            metadata.close()
            server.close()
        }
    }

    @Test
    fun listsUserSchemasAsDatabases() {
        val databases = metadata.listDatabaseNames(null)
        // main (the default schema) + sales; system schemas excluded.
        assertThat(databases).contains("main", "sales")
        assertThat(databases).doesNotContain("information_schema", "pg_catalog")
        assertThat(metadata.databaseExists(null, "sales")).isTrue()
        assertThat(metadata.databaseExists(null, "information_schema")).isFalse()
        assertThat(metadata.databaseExists(null, "no_such_db")).isFalse()
    }

    @Test
    fun listsUserTablesOnly() {
        val tables = metadata.listTableNames(null, "sales")
        assertThat(tables).contains("all_types", "simple", "tãble_ünï")
        // No system objects leak in.
        assertThat(tables).doesNotContain("duckdb_columns", "pg_type")
    }

    @Test
    fun resolvesTableHandleAndUnicodeNames() {
        val handle = metadata.getTableHandle(null, "sales", "tãble_ünï")
        assertThat(handle).isPresent
        val schema = metadata.getTableSchema(null, handle.get())
        val names = schema.columns.map { it.name }
        assertThat(names).containsExactly("cölumn_ä", "val")
        assertThat(metadata.getTableHandle(null, "sales", "no_such_table")).isEmpty
    }

    @Test
    fun mapsFullTypeSurfacePerProbe() {
        val handle = metadata.getTableHandle(null, "sales", "all_types").get()
        val cols = metadata.getColumnHandles(null, handle).values
            .filterIsInstance<DuckBridgeColumnHandle>()
            .associateBy { it.columnName }

        fun type(name: String): ConnectorType = cols.getValue(name).columnType
        fun assertType(name: String, typeName: String) =
            assertThat(type(name).typeName).describedAs(name).isEqualTo(typeName)
        fun assertDecimalLike(name: String, typeName: String, p: Int, s: Int) {
            val t = type(name)
            assertThat(t.typeName).describedAs(name).isEqualTo(typeName)
            assertThat(t.precision).describedAs("$name precision").isEqualTo(p)
            assertThat(t.scale).describedAs("$name scale").isEqualTo(s)
        }

        assertType("c_boolean", "BOOLEAN")
        assertType("c_tinyint", "TINYINT")
        assertType("c_smallint", "SMALLINT")
        assertType("c_integer", "INT")
        assertType("c_bigint", "BIGINT")
        assertType("c_hugeint", "LARGEINT")
        // Unsigned promotion (report §notes).
        assertType("c_utinyint", "SMALLINT")
        assertType("c_usmallint", "INT")
        assertType("c_uinteger", "BIGINT")
        assertType("c_ubigint", "LARGEINT")
        assertType("c_float", "FLOAT")
        assertType("c_double", "DOUBLE")
        assertDecimalLike("c_dec", "DECIMALV3", 38, 10)
        assertType("c_varchar", "STRING")
        assertType("c_blob", "VARBINARY")
        assertType("c_date", "DATEV2")
        assertType("c_time", "STRING")
        assertDecimalLike("c_timestamp", "DATETIMEV2", 6, 0)
        // TIMESTAMPTZ → naive DATETIMEV2(6) over UTC instants (report §TIMESTAMPTZ).
        assertDecimalLike("c_timestamptz", "DATETIMEV2", 6, 0)
        assertDecimalLike("c_ts_ns", "DATETIMEV2", 6, 0) // nanos degrade to micros
        assertType("c_uuid", "STRING")
        assertType("c_json", "STRING")
        // LIST → ARRAY<element>.
        assertType("c_list_int", "ARRAY")
        assertThat(type("c_list_int").children.single().typeName).isEqualTo("INT")
        assertType("c_list_varchar", "ARRAY")
        assertThat(type("c_list_varchar").children.single().typeName).isEqualTo("STRING")
        // STRUCT / MAP / ENUM → STRING (v1).
        assertType("c_struct", "STRING")
        assertType("c_map", "STRING")
        assertType("c_enum", "STRING")

        // Nullability faithful.
        assertThat(cols.getValue("c_notnull").nullable).isFalse()
        assertThat(cols.getValue("c_integer").nullable).isTrue()
    }

    @Test
    fun failsLoudOnUnmappableType() {
        // INTERVAL has no faithful Doris mapping → fail loud naming the column + type.
        server.exec("CREATE TABLE sales.bad_interval (id INTEGER, span INTERVAL)")
        val handle = metadata.getTableHandle(null, "sales", "bad_interval").get()
        assertThatThrownBy { metadata.getColumnHandles(null, handle) }
            .isInstanceOf(DorisConnectorException::class.java)
            .hasMessageContaining("span")
            .hasMessageContaining("INTERVAL")
    }

    @Test
    fun failsLoudOnUnsignedHugeint() {
        // UHUGEINT (0..2^128-1) has no correct Doris fit → fail loud.
        server.exec("CREATE TABLE sales.bad_uhuge (id INTEGER, big UHUGEINT)")
        val handle = metadata.getTableHandle(null, "sales", "bad_uhuge").get()
        assertThatThrownBy { metadata.getColumnHandles(null, handle) }
            .isInstanceOf(DorisConnectorException::class.java)
            .hasMessageContaining("big")
            .hasMessageContaining("UHUGEINT")
    }
}
