package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.api.ConnectorType
import org.apache.doris.connector.api.DorisConnectorException
import java.util.Locale
import java.util.regex.Pattern

/**
 * Maps a DuckDB type STRING to a Doris [ConnectorType].
 *
 * **Why a string mapper (probe P4):** quack-jdbc's `DatabaseMetaData.getColumns().TYPE_NAME` is
 * 100% faithful — it returns the exact DuckDB type spelling for every type — while `DATA_TYPE`
 * (the JDBC int code) collapses ~40% of the surface to `OTHER(1111)`. So the metadata plane reads
 * `TYPE_NAME` and hands it here; we never trust the JDBC code. Full evidence + the decided map:
 * `dev-docs/REPORT-quack-jdbc-metadata-probe.md`.
 *
 * This mirrors doris-ducklake's `DuckLakeTypeMapping` (which maps from the DuckLake `column_type`
 * *string* to `ConnectorType`), targeting the same `ConnectorType` factories. Where the two differ
 * (BLOB, UUID, UBIGINT) it's because duckbridge decodes result-set values through the BE
 * `DuckDbTypeHandler` rather than a parquet reader — the report documents each difference.
 *
 * Fail-loud contract (repo rule): a type with no faithful Doris fit throws [DorisConnectorException]
 * naming the column + DuckDB type — never a silent skip or a wrong-semantics coercion.
 */
internal object DuckDbToDorisTypeMapper {

    // Micros is Doris's DATETIMEV2 max scale and DuckDB's default TIMESTAMP resolution.
    private const val MICROS_SCALE = 6

    // Doris ConnectorType demands a length for VARBINARY. doris-ducklake uses 65535 for blob.
    private const val VARBINARY_DEFAULT_LEN = 65535

    private val DECIMAL_PATTERN: Pattern =
        Pattern.compile("decimal\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE)

    /**
     * Map a DuckDB `TYPE_NAME` string (from quack-jdbc metadata) to a Doris [ConnectorType].
     *
     * @param duckdbType the exact DuckDB type spelling, e.g. `HUGEINT`, `DECIMAL(38,10)`,
     *   `INTEGER[]`, `STRUCT(a INTEGER, b VARCHAR)`, `TIMESTAMP WITH TIME ZONE`.
     * @param columnName only used to make a fail-loud message actionable.
     * @param enableTimestampTz mirrors the iceberg/ducklake `enable.mapping.timestamp_tz` knob;
     *   v1 default false. See the report §TIMESTAMPTZ — either way v1 maps to naive `DATETIMEV2(6)`
     *   over correct UTC instants (tz-sensitive pushdown is gated off, plan §Timezone).
     * @throws DorisConnectorException for a type with no faithful Doris fit (UHUGEINT, INTERVAL,
     *   unmappable ARRAY element, unparseable type).
     */
    fun toDorisType(
        duckdbType: String,
        columnName: String,
        enableTimestampTz: Boolean = false,
    ): ConnectorType {
        val raw = duckdbType.trim()
        val normalized = raw.lowercase(Locale.ROOT)

        // ARRAY: DuckDB TYPE_NAME ends with "[]" (e.g. "INTEGER[]", "VARCHAR[]"). Element is the
        // prefix, mapped recursively — only element types the BE DuckDbTypeHandler decodes are
        // allowed (recursion fails loud for the rest).
        if (normalized.endsWith("[]")) {
            val element = raw.substring(0, raw.length - 2).trim()
            return ConnectorType.arrayOf(toDorisType(element, columnName, enableTimestampTz))
        }

        // STRUCT / MAP / ENUM → STRING in v1 (plan §The dialect gap; enum values are strings).
        // Keyed on the prefix so any field/value spec inside the parens is ignored.
        if (isStructMapOrEnum(normalized)) {
            return ConnectorType.of("STRING")
        }

        // DECIMAL(p,s) — TYPE_NAME embeds precision/scale (probe: COLUMN_SIZE/DECIMAL_DIGITS agree).
        val decimal = DECIMAL_PATTERN.matcher(normalized)
        if (decimal.matches()) {
            val precision = decimal.group(1).toInt()
            val scale = decimal.group(2).toInt()
            return ConnectorType.of("DECIMALV3", precision, scale)
        }

        return mapScalar(normalized, columnName, raw)
    }

    private fun isStructMapOrEnum(normalized: String): Boolean {
        val structOrMap = normalized.startsWith("struct(") || normalized.startsWith("struct<") ||
            normalized.startsWith("map(") || normalized.startsWith("map<")
        return structOrMap || normalized.startsWith("enum(")
    }

    @Suppress("CyclomaticComplexMethod") // A flat DuckDB-type → Doris-type dispatch; one arm per type.
    private fun mapScalar(normalized: String, columnName: String, raw: String): ConnectorType =
        when (normalized) {
            "boolean", "bool" -> ConnectorType.of("BOOLEAN")

            "tinyint", "int1" -> ConnectorType.of("TINYINT")
            "smallint", "int2", "short" -> ConnectorType.of("SMALLINT")
            "integer", "int", "int4", "signed" -> ConnectorType.of("INT")
            "bigint", "int8", "long" -> ConnectorType.of("BIGINT")
            "hugeint", "int128" -> ConnectorType.of("LARGEINT")

            // Unsigned: promote to the next signed type that holds the full range (report §notes).
            "utinyint" -> ConnectorType.of("SMALLINT")
            "usmallint" -> ConnectorType.of("INT")
            "uinteger" -> ConnectorType.of("BIGINT")
            // u64 (0..2^64-1) exceeds BIGINT but fits int128 → LARGEINT (handler normalizes wide
            // unsigned to BigInteger).
            "ubigint" -> ConnectorType.of("LARGEINT")
            // u128 (0..2^128-1) exceeds int128's positive range → no correct Doris fit.
            "uhugeint" -> failLoud(columnName, raw, "no Doris type holds an unsigned 128-bit integer")

            "float", "float4", "real" -> ConnectorType.of("FLOAT")
            "double", "float8" -> ConnectorType.of("DOUBLE")

            "varchar", "char", "bpchar", "text", "string" -> ConnectorType.of("STRING")

            // BLOB decoded by the BE DuckDbTypeHandler (doris-patches/be) → VARBINARY.
            "blob", "bytea", "binary", "varbinary" ->
                ConnectorType.of("VARBINARY", VARBINARY_DEFAULT_LEN, 0)

            // No Doris TIME type — read as STRING (doris-ducklake does the same).
            "time" -> ConnectorType.of("STRING")

            "date" -> ConnectorType.of("DATEV2")

            "timestamp", "datetime" -> ConnectorType.of("DATETIMEV2", MICROS_SCALE, 0)
            "timestamp_s" -> ConnectorType.of("DATETIMEV2", 0, 0)
            "timestamp_ms" -> ConnectorType.of("DATETIMEV2", 3, 0)
            // DEGRADED (documented-lossy): Doris DATETIMEV2 caps scale at 6, so nanos clamp to
            // micros — same widen-to-micros doris-ducklake applies.
            "timestamp_ns" -> ConnectorType.of("DATETIMEV2", MICROS_SCALE, 0)

            // TIMESTAMPTZ (report §TIMESTAMPTZ): v1 → naive DATETIMEV2(6) over correct UTC instants,
            // tz-sensitive pushdown gated off (plan §Timezone). enableTimestampTz reserved for a
            // future zone-aware mapping once the BE converter + P3 land; today both branches map
            // to naive DATETIMEV2(6) (we do NOT claim zone-aware semantics we can't honor).
            "timestamp with time zone", "timestamptz" -> ConnectorType.of("DATETIMEV2", MICROS_SCALE, 0)
            "time with time zone", "timetz" -> ConnectorType.of("STRING")

            // JSON → STRING in v1 (plan §The dialect gap); round-trippable as text.
            "json" -> ConnectorType.of("STRING")

            // UUID → STRING in v1: quack-jdbc surfaces UUID as a string and the DuckDbTypeHandler
            // has no UUID arm yet (report §notes).
            "uuid" -> ConnectorType.of("STRING")

            // No faithful Doris fit; DuckDB's interval string isn't a Doris-castable value on the
            // JDBC path → fail loud rather than risk silently-wrong data.
            "interval" -> failLoud(columnName, raw, "Doris has no faithful INTERVAL mapping")

            else -> failLoud(columnName, raw, "unrecognized DuckDB type")
        }

    private fun failLoud(columnName: String, duckdbType: String, why: String): Nothing =
        throw DorisConnectorException(
            "duckbridge cannot map column '$columnName' of DuckDB type '$duckdbType' to a Doris " +
                "type: $why. This type is not supported in v1 — see " +
                "dev-docs/REPORT-quack-jdbc-metadata-probe.md. (Failing loud rather than returning " +
                "wrong data.)",
        )
}
