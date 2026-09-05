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

import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.LongWriteFunction
import io.trino.plugin.jdbc.ObjectReadFunction
import io.trino.plugin.jdbc.ObjectWriteFunction
import io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN
import io.trino.plugin.jdbc.SliceReadFunction
import io.trino.plugin.jdbc.SliceWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.longDecimalWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.timeReadFunction
import io.trino.plugin.jdbc.StandardColumnMappings.timeWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.timestampColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.varbinaryColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.varbinaryWriteFunction
import io.trino.plugin.jdbc.WriteMapping
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.type.DateTimeEncoding.packTimeWithTimeZone
import io.trino.spi.type.DateTimeEncoding.unpackMillisUtc
import io.trino.spi.type.DateTimeEncoding.unpackOffsetMinutes
import io.trino.spi.type.DateTimeEncoding.unpackTimeNanos
import io.trino.spi.type.DecimalType.createDecimalType
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.Int128
import io.trino.spi.type.LongTimestampWithTimeZone
import io.trino.spi.type.SmallintType.SMALLINT
import io.trino.spi.type.TimeType
import io.trino.spi.type.TimeType.TIME_MICROS
import io.trino.spi.type.TimeWithTimeZoneType
import io.trino.spi.type.TimeWithTimeZoneType.TIME_TZ_MICROS
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS
import io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS
import io.trino.spi.type.TimestampType.TIMESTAMP_SECONDS
import io.trino.spi.type.Type
import io.trino.spi.type.UuidType
import io.trino.spi.type.UuidType.UUID
import io.trino.spi.type.VarbinaryType.VARBINARY
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset

/**
 * Lossless DuckDB scalar mappings not covered by the original upstream DuckDbClient port (EV-C5).
 *
 * JDBC 1.5.5 was probed at both `ResultSetMetaData` and value-getter levels before adding these:
 * BLOB→`getBytes`, TIMESTAMPTZ→[OffsetDateTime], TIME→[LocalTime], TIMETZ raw object→[OffsetTime],
 * UUID→[java.util.UUID], and unsigned integers widened to the next signed Trino type. Predicate
 * domain pushdown is disabled for UUID and zoned temporal values until their ordering/bind semantics
 * have their own cross-engine proof; function-expression pushdown is independent of that controller.
 *
 * DuckDB HUGEINT/UHUGEINT are intentionally absent: both can require 39 decimal digits, while Trino
 * DECIMAL tops out at 38. Mapping either to DECIMAL(38,0), BIGINT, or VARCHAR by default would degrade
 * value/type semantics; unsupported handling remains explicit and fail-safe.
 */
internal object DuckBridgeScalarColumnMappings {
    private val UBIGINT_DECIMAL = createDecimalType(20, 0)

    /** Mapping selected by DuckDB's JDBC type name before the coarser JDBC type-code switch. */
    fun byTypeName(typeName: String?, sessionZone: io.trino.spi.type.TimeZoneKey): ColumnMapping? =
        when (typeName?.uppercase()) {
            "BLOB" -> varbinaryColumnMapping()
            "TIMESTAMP_S" -> timestampColumnMapping(TIMESTAMP_SECONDS)
            "TIMESTAMP_MS" -> timestampColumnMapping(TIMESTAMP_MILLIS)
            "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> timestampWithTimeZoneMapping(sessionZone)
            "TIME" ->
                ColumnMapping.longMapping(
                    TIME_MICROS,
                    timeReadFunction(TIME_MICROS),
                    timeWriteFunction(TIME_MICROS.precision),
                    DISABLE_PUSHDOWN,
                )
            "TIME WITH TIME ZONE", "TIMETZ" -> timeWithTimeZoneMapping()
            "UUID" -> uuidMapping()
            "UTINYINT" -> io.trino.plugin.jdbc.StandardColumnMappings.smallintColumnMapping()
            "USMALLINT" -> io.trino.plugin.jdbc.StandardColumnMappings.integerColumnMapping()
            "UINTEGER" -> io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping()
            "UBIGINT" -> unsignedBigintMapping()
            // HUGEINT/UHUGEINT: no lossless Trino primitive; leave to configured unsupported handling.
            else -> null
        }

    /** Types the current JDBC drivers cannot expose losslessly and must never hit a generic mapping. */
    fun isLossyUnsupported(typeName: String?): Boolean =
        when (typeName?.uppercase()) {
            // duckdb_jdbc 1.5.5 truncates TIMESTAMP_NS to micros through getObject, getTimestamp AND
            // getString; labelling that value TIMESTAMP(9) or TIMESTAMP(6) would silently lose data.
            "TIMESTAMP_NS" -> true
            else -> false
        }

    /** Write mapping for the same lossless Trino shapes. DuckDB stores temporal values at micros. */
    fun toWriteMapping(type: Type): WriteMapping? =
        when (type) {
            VARBINARY -> WriteMapping.sliceMapping("blob", varbinaryWriteFunction())
            UUID -> WriteMapping.sliceMapping("uuid", uuidWriteFunction())
            is TimeType ->
                if (type.precision <= TIME_MICROS.precision) {
                    WriteMapping.longMapping("time", timeWriteFunction(type.precision))
                } else {
                    null
                }
            is TimeWithTimeZoneType ->
                if (type.precision <= TIME_TZ_MICROS.precision && type.isShort) {
                    WriteMapping.longMapping("time with time zone", timeWithTimeZoneWriteFunction())
                } else {
                    null
                }
            is TimestampWithTimeZoneType -> timestampWithTimeZoneWriteMapping(type)
            else -> null
        }

    private fun timestampWithTimeZoneMapping(zone: io.trino.spi.type.TimeZoneKey): ColumnMapping =
        ColumnMapping.objectMapping(
            TIMESTAMP_TZ_MICROS,
            ObjectReadFunction.of(LongTimestampWithTimeZone::class.java) { rs, index -> readTimestampWithTimeZone(rs, index, zone) },
            timestampWithTimeZoneObjectWriteFunction(),
            DISABLE_PUSHDOWN,
        )

    private fun unsignedBigintMapping(): ColumnMapping =
        ColumnMapping.objectMapping(
            UBIGINT_DECIMAL,
            ObjectReadFunction.of(Int128::class.java) { resultSet, index ->
                Int128.valueOf(unsignedBigint(resultSet.getObject(index)))
            },
            longDecimalWriteFunction(UBIGINT_DECIMAL),
        )

    /** Embedded JDBC returns BigInteger; quack-jdbc exposes the uint64 physical bits as signed Long. */
    private fun unsignedBigint(raw: Any): BigInteger =
        when (raw) {
            is BigInteger -> raw
            is BigDecimal -> raw.toBigIntegerExact()
            is Long -> BigInteger(java.lang.Long.toUnsignedString(raw))
            is Number -> BigInteger(raw.toString())
            else -> BigInteger(raw.toString())
        }

    private fun readTimestampWithTimeZone(
        resultSet: ResultSet,
        index: Int,
        zone: io.trino.spi.type.TimeZoneKey,
    ): LongTimestampWithTimeZone {
        val value = resultSet.getObject(index, OffsetDateTime::class.java)
        val instant = value.toInstant()
        return LongTimestampWithTimeZone.fromEpochMillisAndFraction(
            instant.toEpochMilli(),
            (value.nano % 1_000_000) * 1_000,
            zone,
        )
    }

    private fun timestampWithTimeZoneWriteMapping(type: TimestampWithTimeZoneType): WriteMapping? {
        if (type.precision > TIMESTAMP_TZ_MICROS.precision) {
            return null
        }
        return if (type.isShort) {
            WriteMapping.longMapping(
                "timestamp with time zone",
                LongWriteFunction.of(java.sql.Types.TIMESTAMP_WITH_TIMEZONE) { statement, index, packed ->
                    statement.setObject(index, OffsetDateTime.ofInstant(Instant.ofEpochMilli(unpackMillisUtc(packed)), ZoneOffset.UTC))
                },
            )
        } else {
            WriteMapping.objectMapping("timestamp with time zone", timestampWithTimeZoneObjectWriteFunction())
        }
    }

    private fun timestampWithTimeZoneObjectWriteFunction(): ObjectWriteFunction =
        ObjectWriteFunction.of(LongTimestampWithTimeZone::class.java) { statement, index, value ->
            val instant =
                Instant.ofEpochMilli(value.epochMillis)
                    .plusNanos(value.picosOfMilli.toLong() / 1_000L)
            statement.setObject(index, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
        }

    private fun timeWithTimeZoneMapping(): ColumnMapping =
        ColumnMapping.longMapping(
            TIME_TZ_MICROS,
            { resultSet, index ->
                // Embedded JDBC returns OffsetTime; quack-jdbc 0.6 exposes DuckDB's packed uint64
                // dtime_tz_t bits as Long. Decode both exact representations.
                packTimeWithTimeZone(resultSet.getObject(index))
            },
            timeWithTimeZoneWriteFunction(),
            DISABLE_PUSHDOWN,
        )

    private fun packTimeWithTimeZone(raw: Any): Long {
        val micros: Long
        val offsetSeconds: Int
        when (raw) {
            is OffsetTime -> {
                micros = raw.toLocalTime().toNanoOfDay() / 1_000L
                offsetSeconds = raw.offset.totalSeconds
            }
            is Number -> {
                // DuckDB dtime_tz_t: high 40 bits = micros since midnight; low 24 bits =
                // MAX_OFFSET(15:59:59) - offset seconds. See duckdb/common/types/datetime.hpp.
                val bits = raw.toLong()
                micros = bits ushr DUCKDB_TIME_TZ_OFFSET_BITS
                offsetSeconds = DUCKDB_TIME_TZ_MAX_OFFSET_SECONDS - (bits and DUCKDB_TIME_TZ_OFFSET_MASK).toInt()
            }
            else -> throw TrinoException(NOT_SUPPORTED, "Unsupported DuckDB TIME WITH TIME ZONE JDBC value: ${raw.javaClass.name}")
        }
        if (offsetSeconds % 60 != 0) {
            // Trino's TIME WITH TIME ZONE stores offset minutes; DuckDB permits offset seconds.
            throw TrinoException(
                NOT_SUPPORTED,
                "DuckDB TIME WITH TIME ZONE offset has second precision ($offsetSeconds seconds), " +
                    "which Trino cannot represent losslessly",
            )
        }
        return packTimeWithTimeZone(Math.multiplyExact(micros, 1_000L), offsetSeconds / 60)
    }

    private fun timeWithTimeZoneWriteFunction(): LongWriteFunction =
        object : LongWriteFunction {
            // DuckDB JDBC 1.5.5 rejects OffsetTime as a prepared parameter ("Unsupported parameter
            // type"). Bind its ISO text and make the target cast explicit instead.
            override fun getBindExpression(): String = "CAST(? AS TIME WITH TIME ZONE)"

            override fun set(statement: PreparedStatement, index: Int, value: Long) {
                val offsetTime =
                    OffsetTime.of(
                        LocalTime.ofNanoOfDay(unpackTimeNanos(value)),
                        ZoneOffset.ofTotalSeconds(unpackOffsetMinutes(value) * 60),
                    )
                statement.setString(index, offsetTime.toString())
            }
        }

    private fun uuidMapping(): ColumnMapping =
        ColumnMapping.sliceMapping(
            UUID,
            SliceReadFunction { resultSet, index -> UuidType.javaUuidToTrinoUuid(resultSet.getObject(index) as java.util.UUID) },
            uuidWriteFunction(),
            DISABLE_PUSHDOWN,
        )

    private fun uuidWriteFunction(): SliceWriteFunction =
        object : SliceWriteFunction {
            override fun getBindExpression(): String = "CAST(? AS UUID)"

            override fun set(statement: PreparedStatement, index: Int, value: io.airlift.slice.Slice) {
                statement.setObject(index, UuidType.trinoUuidToJavaUuid(value))
            }
        }

    private const val DUCKDB_TIME_TZ_OFFSET_BITS: Int = 24
    private const val DUCKDB_TIME_TZ_OFFSET_MASK: Long = (1L shl DUCKDB_TIME_TZ_OFFSET_BITS) - 1
    private const val DUCKDB_TIME_TZ_MAX_OFFSET_SECONDS: Int = 16 * 60 * 60 - 1
}
