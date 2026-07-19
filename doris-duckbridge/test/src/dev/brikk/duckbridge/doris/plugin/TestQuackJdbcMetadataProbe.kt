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

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DatabaseMetaData
import java.sql.Types

/**
 * Probe P4 — quack-jdbc `DatabaseMetaData` fidelity.
 *
 * Evidence BEFORE code: creates a table covering DuckDB's type surface on a real Quack server,
 * then records what `DatabaseMetaData.getColumns` reports per column (TYPE_NAME, DATA_TYPE JDBC
 * code, COLUMN_SIZE, DECIMAL_DIGITS, nullability) and compares against ground truth from
 * `duckdb_columns()`. Also records getCatalogs / getSchemas / getTables (table-type strings,
 * catalog/schema naming). Findings are printed to stdout AND appended to a machine-diffable
 * section in the report so the type map can key off whatever the driver actually reports.
 *
 * This test does not ASSERT a mapping (that's the metadata plane's job / the integration test);
 * it fails only if the probe itself can't run (server/driver broken). The verdicts it prints are
 * transcribed into dev-docs/REPORT-quack-jdbc-metadata-probe.md.
 *
 * Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestQuackJdbcMetadataProbe {

    private lateinit var server: TestingQuackServer

    @BeforeAll
    fun setUp() {
        server = TestingQuackServer()
        // Cover DuckDB's type surface. Types that DuckDB can't create as a plain column, or that
        // need a cast, are noted inline. ENUM needs a CREATE TYPE first.
        server.exec(
            "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')",
            """
            CREATE TABLE all_types (
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
                c_uhugeint     UHUGEINT,
                c_float        FLOAT,
                c_double       DOUBLE,
                c_decimal_9_2  DECIMAL(9,2),
                c_decimal_18_6 DECIMAL(18,6),
                c_decimal_38_10 DECIMAL(38,10),
                c_varchar      VARCHAR,
                c_blob         BLOB,
                c_date         DATE,
                c_time         TIME,
                c_timestamp    TIMESTAMP,
                c_timestamptz  TIMESTAMPTZ,
                c_timestamp_s  TIMESTAMP_S,
                c_timestamp_ms TIMESTAMP_MS,
                c_timestamp_ns TIMESTAMP_NS,
                c_uuid         UUID,
                c_interval     INTERVAL,
                c_json         JSON,
                c_list_int     INTEGER[],
                c_list_varchar VARCHAR[],
                c_struct       STRUCT(a INTEGER, b VARCHAR),
                c_map          MAP(VARCHAR, INTEGER),
                c_enum         mood,
                "c_ünïcode"    INTEGER,
                c_notnull      INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) {
            server.close()
        }
    }

    @Test
    fun probeMetadataFidelity() {
        val sb = StringBuilder()
        fun out(line: String = "") {
            println(line)
            sb.appendLine(line)
        }

        server.openConnection().use { conn ->
            val md = conn.metaData
            out("### quack-jdbc driver")
            out("driverName=${md.driverName} driverVersion=${md.driverVersion}")
            out("dbProduct=${md.databaseProductName} dbVersion=${md.databaseProductVersion}")
            out("supportsCatalogs(inDataManip)=${md.supportsCatalogsInDataManipulation()} " +
                "supportsSchemas(inDataManip)=${md.supportsSchemasInDataManipulation()}")
            out("identifierQuote='${md.identifierQuoteString}'")
            out()

            out("### getCatalogs()")
            catalogs(md).forEach { out("  catalog=$it") }
            out()

            out("### getSchemas()")
            schemas(md).forEach { (cat, schema) -> out("  catalog=$cat schema=$schema") }
            out()

            out("### getTables(types=null) — what TABLE_TYPE does quack report?")
            tables(md).forEach { t ->
                out("  catalog=${t.catalog} schema=${t.schema} table=${t.name} TABLE_TYPE=${t.type}")
            }
            out()

            out("### getTableTypes()")
            md.tableTypes.use { rs -> while (rs.next()) out("  tableType=${rs.getString(1)}") }
            out()

            // Ground truth from duckdb_columns() for the same table.
            val groundTruth = groundTruth(conn)

            out("### getColumns(all_types) vs duckdb_columns() ground truth")
            out(row("COLUMN", "DUCKDB_TYPE(truth)", "JDBC TYPE_NAME", "DATA_TYPE", "SIZE", "DEC", "NULLABLE"))
            out("  " + "-".repeat(110))
            columns(md, "all_types").forEach { c ->
                val truth = groundTruth[c.name] ?: "?"
                out(
                    row(
                        c.name,
                        truth,
                        c.typeName,
                        jdbcTypeName(c.dataType),
                        c.columnSize?.toString() ?: "-",
                        c.decimalDigits?.toString() ?: "-",
                        c.nullable,
                    ),
                )
            }
            out()
        }

        // Persist the transcript next to the report so the human-written verdicts can be checked
        // against the raw evidence (best-effort; test dir may be read-only in some CI).
        runCatching {
            val target = java.nio.file.Path.of("dev-docs/REPORT-quack-jdbc-metadata-probe.transcript.txt")
            java.nio.file.Files.createDirectories(target.parent)
            java.nio.file.Files.writeString(target, sb.toString())
            println("[probe] transcript written to ${target.toAbsolutePath()}")
        }.onFailure { println("[probe] could not write transcript: ${it.message}") }
    }

    // ---- helpers ----

    private data class ColumnMeta(
        val name: String,
        val typeName: String,
        val dataType: Int,
        val columnSize: Int?,
        val decimalDigits: Int?,
        val nullable: String,
    )

    private fun catalogs(md: DatabaseMetaData): List<String> =
        md.catalogs.use { rs -> generateSequence { if (rs.next()) rs.getString("TABLE_CAT") else null }.toList() }

    private fun schemas(md: DatabaseMetaData): List<Pair<String?, String?>> =
        md.schemas.use { rs ->
            generateSequence {
                if (rs.next()) rs.getString("TABLE_CATALOG") to rs.getString("TABLE_SCHEM") else null
            }.toList()
        }

    private fun tables(md: DatabaseMetaData): List<TableRow> =
        md.getTables(null, null, "%", null).use { rs ->
            generateSequence {
                if (rs.next()) {
                    TableRow(
                        rs.getString("TABLE_CAT"),
                        rs.getString("TABLE_SCHEM"),
                        rs.getString("TABLE_NAME"),
                        rs.getString("TABLE_TYPE"),
                    )
                } else {
                    null
                }
            }.toList()
        }

    private fun columns(md: DatabaseMetaData, table: String): List<ColumnMeta> =
        md.getColumns(null, null, table, "%").use { rs ->
            generateSequence {
                if (rs.next()) {
                    ColumnMeta(
                        name = rs.getString("COLUMN_NAME"),
                        typeName = rs.getString("TYPE_NAME"),
                        dataType = rs.getInt("DATA_TYPE"),
                        columnSize = rs.getInt("COLUMN_SIZE").takeUnless { rs.wasNull() },
                        decimalDigits = rs.getInt("DECIMAL_DIGITS").takeUnless { rs.wasNull() },
                        nullable = when (rs.getInt("NULLABLE")) {
                            DatabaseMetaData.columnNoNulls -> "NO_NULLS"
                            DatabaseMetaData.columnNullable -> "NULLABLE"
                            else -> "UNKNOWN"
                        },
                    )
                } else {
                    null
                }
            }.toList()
        }

    /** column_name -> duckdb data_type text, straight from duckdb_columns() (the truth oracle). */
    private fun groundTruth(conn: java.sql.Connection): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT column_name, data_type FROM duckdb_columns() WHERE table_name = 'all_types'",
            ).use { rs ->
                while (rs.next()) {
                    out[rs.getString("column_name")] = rs.getString("data_type")
                }
            }
        }
        return out
    }

    private fun jdbcTypeName(code: Int): String {
        val name = JDBC_TYPE_NAMES[code] ?: "UNKNOWN"
        return "$name($code)"
    }

    private fun row(vararg cols: String): String {
        val widths = intArrayOf(16, 22, 20, 16, 6, 5, 10)
        val sb = StringBuilder("  ")
        cols.forEachIndexed { i, c ->
            val w = widths.getOrElse(i) { 12 }
            sb.append(c.padEnd(w)).append(' ')
        }
        return sb.toString().trimEnd()
    }

    private class TableRow(val catalog: String?, val schema: String?, val name: String?, val type: String?)

    private companion object {
        val JDBC_TYPE_NAMES: Map<Int, String> = buildMap {
            put(Types.BIT, "BIT"); put(Types.BOOLEAN, "BOOLEAN")
            put(Types.TINYINT, "TINYINT"); put(Types.SMALLINT, "SMALLINT")
            put(Types.INTEGER, "INTEGER"); put(Types.BIGINT, "BIGINT")
            put(Types.FLOAT, "FLOAT"); put(Types.REAL, "REAL"); put(Types.DOUBLE, "DOUBLE")
            put(Types.NUMERIC, "NUMERIC"); put(Types.DECIMAL, "DECIMAL")
            put(Types.CHAR, "CHAR"); put(Types.VARCHAR, "VARCHAR"); put(Types.LONGVARCHAR, "LONGVARCHAR")
            put(Types.DATE, "DATE"); put(Types.TIME, "TIME"); put(Types.TIMESTAMP, "TIMESTAMP")
            put(Types.TIME_WITH_TIMEZONE, "TIME_WITH_TIMEZONE")
            put(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP_WITH_TIMEZONE")
            put(Types.BINARY, "BINARY"); put(Types.VARBINARY, "VARBINARY"); put(Types.LONGVARBINARY, "LONGVARBINARY")
            put(Types.BLOB, "BLOB"); put(Types.CLOB, "CLOB")
            put(Types.ARRAY, "ARRAY"); put(Types.STRUCT, "STRUCT"); put(Types.OTHER, "OTHER")
            put(Types.JAVA_OBJECT, "JAVA_OBJECT"); put(Types.NULL, "NULL"); put(Types.SQLXML, "SQLXML")
            put(Types.ROWID, "ROWID"); put(Types.NCHAR, "NCHAR"); put(Types.NVARCHAR, "NVARCHAR")
        }
    }
}
