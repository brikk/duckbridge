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

import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import io.trino.spi.type.TimeZoneKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager

/** End-to-end lossless scalar mappings and newly-reachable BLOB hash pushdown (EV-C5). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDuckBridgeScalarTypes {
    private lateinit var connectionUrl: String
    private lateinit var queryRunner: QueryRunner

    @BeforeAll
    fun setUp() {
        connectionUrl = DuckBridgeQueryRunner.freshDatabaseUrl()
        DriverManager.getConnection(connectionUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA ${DuckBridgeQueryRunner.SCHEMA}")
                stmt.execute(
                    """
                    CREATE TABLE ${DuckBridgeQueryRunner.SCHEMA}.native_scalars (
                        id BIGINT,
                        b BLOB,
                        key BLOB,
                        tz TIMESTAMPTZ,
                        tm TIME,
                        tmz TIME WITH TIME ZONE,
                        u UUID,
                        ut UTINYINT,
                        us USMALLINT,
                        ui UINTEGER,
                        ub UBIGINT,
                        h HUGEINT,
                        uh UHUGEINT
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "INSERT INTO ${DuckBridgeQueryRunner.SCHEMA}.native_scalars " +
                        "VALUES (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
                )
                stmt.execute(
                    """
                    INSERT INTO ${DuckBridgeQueryRunner.SCHEMA}.native_scalars VALUES (
                        1,
                        unhex('00ff616263'),
                        unhex('6b6579'),
                        TIMESTAMPTZ '2024-03-01 02:03:04.123456 America/New_York',
                        TIME '23:59:58.123456',
                        TIMETZ '23:59:58.123456+05:30',
                        UUID '123e4567-e89b-12d3-a456-426614174000',
                        255,
                        65535,
                        4294967295,
                        18446744073709551615,
                        170141183460469231731687303715884105727,
                        340282366920938463463374607431768211455
                    )
                    """.trimIndent(),
                )
            }
        }
        queryRunner = DuckBridgeQueryRunner.create(connectionUrl)
    }

    @AfterAll
    fun tearDown() {
        if (::queryRunner.isInitialized) {
            queryRunner.close()
        }
    }

    @Test
    fun nativeDuckDbTypesReadLosslesslyInTrino() {
        val columns =
            queryRunner.execute("SHOW COLUMNS FROM native_scalars").materializedRows
                .associate { it.getField(0) as String to it.getField(1).toString() }

        assertThat(columns).containsEntry("b", "varbinary")
        assertThat(columns).containsEntry("tz", "timestamp(6) with time zone")
        assertThat(columns).containsEntry("tm", "time(6)")
        assertThat(columns).containsEntry("tmz", "time(6) with time zone")
        assertThat(columns).containsEntry("u", "uuid")
        assertThat(columns).containsEntry("ut", "smallint")
        assertThat(columns).containsEntry("us", "integer")
        assertThat(columns).containsEntry("ui", "bigint")
        assertThat(columns).containsEntry("ub", "decimal(20,0)")
        // DuckDB's signed/unsigned 128-bit maxima need 39 digits; Trino DECIMAL tops out at 38.
        assertThat(columns).doesNotContainKeys("h", "uh")

        val row =
            queryRunner.execute(
                """
                SELECT
                    to_hex(b),
                    to_unixtime(tz),
                    CAST(tm AS varchar),
                    CAST(tmz AS varchar),
                    CAST(u AS varchar),
                    ut, us, ui, CAST(ub AS varchar)
                FROM native_scalars WHERE id = 1
                """.trimIndent(),
            ).materializedRows.single()
        assertThat(row.getField(0)).isEqualTo("00FF616263")
        assertThat(row.getField(1)).isEqualTo(1709276584.123456)
        assertThat(row.getField(2)).isEqualTo("23:59:58.123456")
        assertThat(row.getField(3)).isEqualTo("23:59:58.123456+05:30")
        assertThat(row.getField(4)).isEqualTo("123e4567-e89b-12d3-a456-426614174000")
        assertThat(row.getField(5)).isEqualTo(255.toShort())
        assertThat(row.getField(6)).isEqualTo(65535)
        assertThat(row.getField(7)).isEqualTo(4294967295L)
        assertThat(row.getField(8)).isEqualTo("18446744073709551615")

        val nullRow =
            queryRunner.execute("SELECT b, tz, tm, tmz, u, ut, us, ui, ub FROM native_scalars WHERE id = 2")
                .materializedRows.single()
        assertThat(nullRow.fields).containsOnlyNulls()
    }

    @Test
    @Suppress("LongMethod") // one write followed by the native DuckDB type/value assertions
    fun trinoWritesNativeScalarTypesBackToDuckDb() {
        queryRunner.execute(
            """
            CREATE TABLE written_scalars (
                id bigint,
                b varbinary,
                tz3 timestamp(3) with time zone,
                tz timestamp(6) with time zone,
                tm time(6),
                tmz time(6) with time zone,
                u uuid
            )
            """.trimIndent(),
        )
        try {
            queryRunner.execute(
                """
                INSERT INTO written_scalars VALUES (
                    1,
                    X'00FF616263',
                    TIMESTAMP '2024-03-01 07:03:04.123 UTC',
                    TIMESTAMP '2024-03-01 07:03:04.123456 UTC',
                    TIME '23:59:58.123456',
                    TIME '23:59:58.123456+05:30',
                    UUID '123e4567-e89b-12d3-a456-426614174000'
                )
                """.trimIndent(),
            )
            DriverManager.getConnection(connectionUrl).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        SELECT
                            typeof(b), hex(b),
                            typeof(tz3), epoch_ms(tz3),
                            typeof(tz), epoch_us(tz),
                            typeof(tm), CAST(tm AS varchar),
                            typeof(tmz), CAST(tmz AS varchar),
                            typeof(u), CAST(u AS varchar)
                        FROM ${DuckBridgeQueryRunner.SCHEMA}.written_scalars
                        """.trimIndent(),
                    ).use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getString(1)).isEqualTo("BLOB")
                        assertThat(rs.getString(2)).isEqualTo("00FF616263")
                        assertThat(rs.getString(3)).isEqualTo("TIMESTAMP WITH TIME ZONE")
                        assertThat(rs.getLong(4)).isEqualTo(1709276584123L)
                        assertThat(rs.getString(5)).isEqualTo("TIMESTAMP WITH TIME ZONE")
                        assertThat(rs.getLong(6)).isEqualTo(1709276584123456L)
                        assertThat(rs.getString(7)).isEqualTo("TIME")
                        assertThat(rs.getString(8)).isEqualTo("23:59:58.123456")
                        assertThat(rs.getString(9)).isEqualTo("TIME WITH TIME ZONE")
                        assertThat(rs.getString(10)).isEqualTo("23:59:58.123456+05:30")
                        assertThat(rs.getString(11)).isEqualTo("UUID")
                        assertThat(rs.getString(12)).isEqualTo("123e4567-e89b-12d3-a456-426614174000")
                    }
                }
            }
        } finally {
            queryRunner.execute("DROP TABLE IF EXISTS written_scalars")
        }
    }

    @Test
    fun blobHashPredicatesAreNowReachableAndFullyPushed() {
        val md5 = queryRunner.execute("SELECT to_hex(md5(X'00FF616263'))").materializedRows.single().getField(0)
        val sha1 = queryRunner.execute("SELECT to_hex(sha1(X'00FF616263'))").materializedRows.single().getField(0)
        val sha256 = queryRunner.execute("SELECT to_hex(sha256(X'00FF616263'))").materializedRows.single().getField(0)
        val sha512 = queryRunner.execute("SELECT to_hex(sha512(X'00FF616263'))").materializedRows.single().getField(0)
        val xxhash = queryRunner.execute("SELECT to_hex(xxhash64(X'00FF616263'))").materializedRows.single().getField(0)
        val hmac =
            queryRunner.execute("SELECT to_hex(hmac_sha256(X'00FF616263', X'6B6579'))")
                .materializedRows.single().getField(0)
        assertFullyPushed("SELECT id FROM native_scalars WHERE to_hex(b) = '00FF616263'")
        assertFullyPushed("SELECT id FROM native_scalars WHERE to_base64(b) = 'AP9hYmM='")
        assertFullyPushed("SELECT id FROM native_scalars WHERE to_hex(md5(b)) = '$md5'")
        assertFullyPushed("SELECT id FROM native_scalars WHERE to_hex(sha1(b)) = '$sha1'")
        assertFullyPushed("SELECT id FROM native_scalars WHERE to_hex(sha256(b)) = '$sha256'")
        assertFullyPushed("SELECT id FROM native_scalars WHERE to_hex(sha512(b)) = '$sha512'")
        assertFullyPushed("SELECT id FROM native_scalars WHERE to_hex(xxhash64(b)) = '$xxhash'")
        assertFullyPushed("SELECT id FROM native_scalars WHERE to_hex(hmac_sha256(b, key)) = '$hmac'")

        queryRunner.execute("INSERT INTO native_scalars (id, b, key) VALUES (3, X'64617461', X'')")
        try {
            val emptyKeySql = "SELECT id FROM native_scalars WHERE to_hex(hmac_sha256(b, key)) = '00'"
            val plan =
                queryRunner.execute("EXPLAIN (TYPE DISTRIBUTED) $emptyKeySql")
                    .materializedRows.joinToString("\n") { it.getField(0).toString() }
            assertThat(plan).doesNotContain("filterPredicate")
            assertThatThrownBy { queryRunner.execute(emptyKeySql) }.hasMessageContaining("Empty key")
        } finally {
            queryRunner.execute("DELETE FROM native_scalars WHERE id = 3")
        }
    }

    @Test
    fun timestampWithTimeZoneUsesTheQuerySessionZoneAboveAndBelowTheScan() {
        val newYork = session("America/New_York")
        val singapore = session("Asia/Singapore")

        // Stored instant is 2024-03-01 07:03 UTC: 02:03 New York, 15:03 Singapore.
        assertThat(queryRunner.execute(newYork, "SELECT hour(tz) FROM native_scalars WHERE id = 1").onlyValue)
            .isEqualTo(2L)
        assertThat(queryRunner.execute(singapore, "SELECT hour(tz) FROM native_scalars WHERE id = 1").onlyValue)
            .isEqualTo(15L)

        val pushed = "SELECT id FROM native_scalars WHERE hour(tz) = 2"
        assertThat(queryRunner.execute(newYork, pushed).materializedRows.map { it.getField(0) }).containsExactly(1L)
        val plan = queryRunner.execute(newYork, "EXPLAIN (TYPE DISTRIBUTED) $pushed").onlyValue.toString()
        assertThat(plan).doesNotContain("filterPredicate")
    }

    @Test
    fun temporalWritesAboveMicrosecondPrecisionFailLoud() {
        assertThatThrownBy {
            queryRunner.execute("CREATE TABLE too_precise_tz (v timestamp(9) with time zone)")
        }.hasMessageContaining("Unsupported column type: timestamp(9) with time zone")
        assertThatThrownBy {
            queryRunner.execute("CREATE TABLE too_precise_time (v time(9))")
        }.hasMessageContaining("Unsupported column type: time(9)")
    }

    private fun assertFullyPushed(sql: String) {
        assertThat(queryRunner.execute(sql).materializedRows.map { it.getField(0) }).containsExactly(1L)
        val plan =
            queryRunner.execute("EXPLAIN (TYPE DISTRIBUTED) $sql")
                .materializedRows.joinToString("\n") { it.getField(0).toString() }
        assertThat(plan).`as`("hash predicate should be in the remote TableScan").doesNotContain("filterPredicate")
    }

    private fun session(zone: String): io.trino.Session =
        testSessionBuilder()
            .setCatalog(DuckBridgeQueryRunner.CATALOG)
            .setSchema(DuckBridgeQueryRunner.SCHEMA)
            .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(zone))
            .build()
}
