package dev.brikk.duckbridge.doris.plugin

import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.DorisConnectorException
import java.util.Locale
import java.util.regex.Pattern

/**
 * Maps DuckDB type names to Doris types, rejecting types without a supported mapping.
 * JDBC `DATA_TYPE` collapses distinct DuckDB types to `OTHER`, so mapping uses `TYPE_NAME`.
 */
internal object DuckDbToDorisTypeMapper {

    // Micros is Doris's DATETIMEV2 max scale and DuckDB's default TIMESTAMP resolution.
    private const val MICROS_SCALE = 6

    // Doris ConnectorType requires a length even for a variable-width binary value.
    private const val VARBINARY_DEFAULT_LEN = 65535

    private val DECIMAL_PATTERN: Pattern =
        Pattern.compile("decimal\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE)

    fun toDorisType(
        duckdbType: String,
        columnName: String,
        enableTimestampTz: Boolean = false,
    ): ConnectorType {
        val raw = duckdbType.trim()
        val normalized = raw.lowercase(Locale.ROOT)

        if (normalized == "variant" || normalized.startsWith("tuple(")) {
            failLoud(columnName, raw, "temporary boundary guard: no qualified dynamic/positional host carrier; " +
                "use an explicit source CAST only if its different type is intended")
        }

        if (normalized.endsWith("[]")) {
            val element = raw.substring(0, raw.length - 2).trim()
            return ConnectorType.arrayOf(toDorisType(element, columnName, enableTimestampTz))
        }

        if (isStructMapOrEnum(normalized)) {
            return ConnectorType.of("STRING")
        }

        val decimal = DECIMAL_PATTERN.matcher(normalized)
        if (decimal.matches()) {
            val precision = decimal.group(1).toInt()
            val scale = decimal.group(2).toInt()
            return ConnectorType.of("DECIMALV3", precision, scale)
        }

        return mapScalar(normalized, columnName, raw, enableTimestampTz)
    }

    private fun isStructMapOrEnum(normalized: String): Boolean =
        normalized.startsWith("struct(") || normalized.startsWith("struct<") ||
            normalized.startsWith("map(") || normalized.startsWith("map<") || normalized.startsWith("enum(")

    private fun mapScalar(
        normalized: String,
        columnName: String,
        raw: String,
        enableTimestampTz: Boolean,
    ): ConnectorType =
        when (normalized) {
            "null", "\"null\"", "sqlnull" -> failLoud(
                columnName, raw, "untyped SQL NULL needs an explicit supported source type; use a typed CAST",
            )
            "boolean", "bool" -> ConnectorType.of("BOOLEAN")

            "tinyint", "int1" -> ConnectorType.of("TINYINT")
            "smallint", "int2", "short" -> ConnectorType.of("SMALLINT")
            "integer", "int", "int4", "signed" -> ConnectorType.of("INT")
            "bigint", "int8", "long" -> ConnectorType.of("BIGINT")
            "hugeint", "int128" -> ConnectorType.of("LARGEINT")

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

            "blob", "bytea", "binary", "varbinary" ->
                ConnectorType.of("VARBINARY", VARBINARY_DEFAULT_LEN, 0)

            // Doris has no TIME type.
            "time" -> ConnectorType.of("STRING")

            "date" -> ConnectorType.of("DATEV2")

            "timestamp", "datetime" -> ConnectorType.of("DATETIMEV2", MICROS_SCALE, 0)
            "timestamp_s" -> ConnectorType.of("DATETIMEV2", 0, 0)
            "timestamp_ms" -> ConnectorType.of("DATETIMEV2", 3, 0)
            // Doris DATETIMEV2 caps scale at 6, so nanoseconds are reduced to microseconds.
            "timestamp_ns" -> ConnectorType.of("DATETIMEV2", MICROS_SCALE, 0)

            "timestamp with time zone", "timestamptz" -> if (enableTimestampTz) {
                ConnectorType.of("TIMESTAMPTZ", MICROS_SCALE, 0)
            } else {
                ConnectorType.of("DATETIMEV2", MICROS_SCALE, 0)
            }
            "time with time zone", "timetz" -> failLoud(
                columnName, raw, "Quack getString exposes packed TIMETZ bits; a faithful time-text mapping is not qualified",
            )

            "json" -> ConnectorType.of("STRING")

            "uuid" -> ConnectorType.of("UUID")

            // No faithful Doris fit; DuckDB's interval string isn't a Doris-castable value on the
            // JDBC path → fail loud rather than risk silently-wrong data.
            "interval" -> failLoud(columnName, raw, "Doris has no faithful INTERVAL mapping")

            else -> failLoud(columnName, raw, "unrecognized DuckDB type")
        }

    private fun failLoud(columnName: String, duckdbType: String, why: String): Nothing =
        throw DorisConnectorException(
            "duckbridge cannot map column '$columnName' of DuckDB type '$duckdbType' to a Doris " +
                "type: $why. This type is not supported in v1. (Failing loud rather than returning " +
                "wrong data.)",
        )
}
