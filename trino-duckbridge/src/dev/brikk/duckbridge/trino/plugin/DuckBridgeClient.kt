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

import com.google.common.collect.ImmutableList
import com.google.inject.Inject
import io.airlift.log.Logger
import io.trino.plugin.base.expression.ConnectorExpressionRewriter
import io.trino.plugin.base.mapping.IdentifierMapping
import io.trino.plugin.jdbc.BaseJdbcClient
import io.trino.plugin.jdbc.BaseJdbcClient.TopNFunction
import io.trino.plugin.jdbc.BaseJdbcConfig
import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcOutputTableHandle
import io.trino.plugin.jdbc.JdbcSortItem
import io.trino.plugin.jdbc.JdbcSplit
import io.trino.plugin.jdbc.JdbcTableHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.LongWriteFunction
import io.trino.plugin.jdbc.PreparedQuery
import io.trino.plugin.jdbc.QueryBuilder
import io.trino.plugin.jdbc.RemoteTableName
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.bigintWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.booleanWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.charWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.decimalColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.doubleColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.doubleWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.integerColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.integerWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.longDecimalWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.realColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.realWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.shortDecimalWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.smallintColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.smallintWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.timestampWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.tinyintColumnMapping
import io.trino.plugin.jdbc.StandardColumnMappings.tinyintWriteFunction
import io.trino.plugin.jdbc.StandardColumnMappings.varcharReadFunction
import io.trino.plugin.jdbc.StandardColumnMappings.varcharWriteFunction
import io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.getUnsupportedTypeHandling
import io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR
import io.trino.plugin.jdbc.WriteMapping
import io.trino.plugin.jdbc.logging.RemoteQueryModifier
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.connector.ColumnMetadata
import io.trino.spi.connector.ColumnPosition
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.SchemaTableName
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.CharType
import io.trino.spi.type.DateType.DATE
import io.trino.spi.type.DecimalType
import io.trino.spi.type.DecimalType.createDecimalType
import io.trino.spi.type.DoubleType.DOUBLE
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.RealType.REAL
import io.trino.spi.type.SmallintType.SMALLINT
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TinyintType.TINYINT
import io.trino.spi.type.Type
import io.trino.spi.type.TimestampWithTimeZoneType
import io.trino.spi.type.TimeWithTimeZoneType
import io.trino.spi.type.VarcharType
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.sql.Types
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField.EPOCH_DAY
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import java.util.regex.Pattern

/**
 * base-jdbc client for DuckDB: type mappings, session time zone, and expression, LIMIT and TopN
 * pushdown.
 */
class DuckBridgeClient
    @Inject
    constructor(
        config: BaseJdbcConfig,
        connectionFactory: ConnectionFactory,
        queryBuilder: QueryBuilder,
        identifierMapping: IdentifierMapping,
        queryModifier: RemoteQueryModifier,
        private val connectorExpressionRewriter: ConnectorExpressionRewriter<ParameterizedExpression>,
        private val duckBridgeConfig: DuckBridgeConfig,
    ) : BaseJdbcClient(
            "\"",
            connectionFactory,
            queryBuilder,
            config.jdbcTypesMappedToVarchar,
            identifierMapping,
            queryModifier,
            false,
        ) {
        private val quackTransport = config.connectionUrl.startsWith("jdbc:quack:")
        private val arrowTransport = duckBridgeConfig.executionEngine != DuckBridgeExecutionEngine.JDBC
        private val zonedNanosTransport = arrowTransport || quackTransport

        private fun lacksTemporalCarrier(typeName: String): Boolean {
            val name = typeName.uppercase()
            if (!zonedNanosTransport && name == "TIMESTAMPTZ_NS") return true
            if (arrowTransport && name in setOf("TIMETZ", "TIME WITH TIME ZONE")) return true
            // DuckDBArray's legacy Timestamp elements normalize JVM DST gaps; typed element
            // getters are not implemented. Native Arrow and Quack's LocalDateTime array are exact.
            return !arrowTransport && !quackTransport && name.startsWith("TIMESTAMP_NS[")
        }

        override fun getConnection(session: ConnectorSession): Connection {
            // BaseJdbcClient.getConnection calls Connection.setReadOnly, which DuckDB does not
            // support, so open directly.
            val connection = connectionFactory.openConnection(session)
            try {
                applySessionTimeZone(connection, session)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                runCatching { connection.close() }
                throw e
            }
            return connection
        }

        /**
         * Renders a split's read query (SQL with `?` placeholders plus typed parameters) without
         * binding it to a `PreparedStatement`, for engines that need a literal SQL string.
         */
        fun renderSplitQuery(
            session: ConnectorSession,
            split: JdbcSplit,
            table: JdbcTableHandle,
            columns: List<JdbcColumnHandle>,
        ): PreparedQuery =
            connectionFactory.openConnection(session).use { connection ->
                prepareQuery(session, connection, table, Optional.empty(), columns, emptyMap(), Optional.of(split))
            }

        private fun applySessionTimeZone(connection: Connection, session: ConnectorSession) {
            val trinoZone = session.timeZoneKey.id
            val duckZone = TrinoTimeZoneNormaliser.normalise(trinoZone) ?: return
            try {
                connection.createStatement().use { stmt ->
                    stmt.execute("SET TimeZone = '" + duckZone.replace("'", "''") + "'")
                }
            } catch (e: SQLException) {
                if (DuckBridgeSessionProperties.isPushdownTimestampWithTimeZone(session)) {
                    // tz-sensitive functions may already be pushed for this session; evaluating
                    // them in a mismatched zone would silently return wrong rows. Without tz
                    // pushdown nothing zone-sensitive is pushed, so a warning suffices.
                    throw TrinoException(
                        NOT_SUPPORTED,
                        "DuckDB rejected SET TimeZone = '$duckZone' (from Trino session zone '$trinoZone') while " +
                            "pushdown_timestamp_with_timezone is enabled. Use a zone DuckDB accepts (named IANA zone or " +
                            "integer-hour offset), or set session property pushdown_timestamp_with_timezone = false.",
                        e,
                    )
                }
                if (loggedTimeZoneFailures.add(duckZone)) {
                    log.warn(
                        "duckbridge: could not SET TimeZone = '%s' (from Trino zone '%s'); harmless for this session " +
                            "because pushdown_timestamp_with_timezone is disabled (no tz-sensitive pushdown). Cause: %s",
                        duckZone,
                        trinoZone,
                        e.message,
                    )
                }
            }
        }

        override fun renameSchema(session: ConnectorSession, schemaName: String, newSchemaName: String) {
            throw TrinoException(NOT_SUPPORTED, "This connector does not support renaming schemas")
        }

        // DuckDB's JDBC metadata reports base tables as "TABLE", not "BASE TABLE".
        override fun getTableTypes(): Optional<List<String>> = Optional.of(ImmutableList.of("TABLE", "VIEW"))

        override fun listSchemas(connection: Connection): Collection<String> {
            val catalog = DuckBridgeNamespaceGuard.requireFlat(connection)
            return connection.metaData.getSchemas(catalog, null).use { rows ->
                buildSet {
                    while (rows.next()) rows.getString("TABLE_SCHEM").let { if (filterRemoteSchema(it)) add(it) }
                }
            }
        }

        override fun getColumns(remoteTableName: RemoteTableName, metadata: DatabaseMetaData): java.sql.ResultSet {
            DuckBridgeNamespaceGuard.requireFlat(metadata.connection)
            return super.getColumns(remoteTableName, metadata)
        }

        @Throws(SQLException::class)
        override fun getTables(
            connection: Connection,
            schemaName: Optional<String>,
            tableName: Optional<String>,
        ): java.sql.ResultSet {
            val catalog = DuckBridgeNamespaceGuard.requireFlat(connection)
            val metadata = connection.metaData
            return metadata.getTables(
                catalog,
                escapeObjectNameForMetadataQuery(schemaName, metadata.searchStringEscape).orElse(null),
                escapeObjectNameForMetadataQuery(tableName, metadata.searchStringEscape).orElse(null),
                getTableTypes().map { it.toTypedArray() }.orElse(null),
            )
        }

        override fun renameTable(session: ConnectorSession, handle: JdbcTableHandle, newTableName: SchemaTableName) {
            val remoteTableName = handle.asPlainTable().remoteTableName
            if (remoteTableName.schemaName.orElseThrow() != newTableName.schemaName) {
                throw TrinoException(NOT_SUPPORTED, "This connector does not support renaming tables across schemas")
            }
            renameTable(session, null, remoteTableName.schemaName.orElseThrow(), remoteTableName.tableName, newTableName)
        }

        @Throws(SQLException::class)
        override fun renameTable(
            session: ConnectorSession,
            connection: Connection?,
            catalogName: String?,
            remoteSchemaName: String,
            remoteTableName: String,
            newRemoteSchemaName: String,
            newRemoteTableName: String,
        ) {
            execute(
                session,
                connection,
                "ALTER TABLE ${quoted(catalogName, remoteSchemaName, remoteTableName)} " +
                    "RENAME TO ${quoted(catalogName, null, newRemoteTableName)}",
            )
        }

        override fun commitCreateTable(session: ConnectorSession, handle: JdbcOutputTableHandle, pageSinkIds: Set<Long>) {
            if (handle.pageSinkIdColumnName.isPresent) {
                finishInsertTable(session, handle, pageSinkIds)
            } else {
                renameTable(
                    session,
                    null,
                    handle.remoteTableName.schemaName.orElse(null),
                    handle.temporaryTableName.orElseThrow { IllegalStateException("Temporary table name missing") },
                    handle.remoteTableName.schemaTableName,
                )
            }
        }

        override fun addColumn(
            session: ConnectorSession,
            handle: JdbcTableHandle,
            column: ColumnMetadata,
            position: ColumnPosition,
        ) {
            if (!column.isNullable) {
                throw TrinoException(NOT_SUPPORTED, "This connector does not support adding not null columns")
            }
            val unsupportedClause: String? =
                when (position) {
                    is ColumnPosition.First -> "FIRST"
                    is ColumnPosition.After -> "AFTER"
                    is ColumnPosition.Last -> null
                }
            if (unsupportedClause != null) {
                throw TrinoException(
                    NOT_SUPPORTED,
                    "This connector does not support adding columns with $unsupportedClause clause",
                )
            }
            super.addColumn(session, handle, column, position)
        }

        override fun toColumnMapping(
            session: ConnectorSession,
            connection: Connection,
            typeHandle: JdbcTypeHandle,
        ): Optional<ColumnMapping> {
            if (lacksTemporalCarrier(typeHandle.jdbcTypeName().orElse(""))) return Optional.empty()
            val forced = checkedVarcharMapping(session, typeHandle)
            if (forced.isPresent) {
                return forced
            }
            DuckBridgeScalarColumnMappings.byTypeName(typeHandle.jdbcTypeName().orElse(null), session.timeZoneKey)
                ?.let { return Optional.of(it) }
            if (DuckBridgeScalarColumnMappings.isNanosTemporal(typeHandle.jdbcTypeName().orElse(null))) {
                // A shared JDBC code is not a precision contract. Keep new nanos types out of
                // generic microsecond mappings until the host read/write/bind path is qualified.
                return Optional.empty()
            }
            when (typeHandle.jdbcType()) {
                Types.BOOLEAN -> return Optional.of(booleanColumnMapping())
                Types.TINYINT -> return Optional.of(tinyintColumnMapping())
                Types.SMALLINT -> return Optional.of(smallintColumnMapping())
                Types.INTEGER -> return Optional.of(integerColumnMapping())
                Types.BIGINT -> return Optional.of(bigintColumnMapping())
                Types.FLOAT, Types.REAL -> return Optional.of(DuckBridgeFloatingPredicatePushdown.mapping(realColumnMapping()))
                Types.DOUBLE -> return Optional.of(DuckBridgeFloatingPredicatePushdown.mapping(doubleColumnMapping()))
                Types.DECIMAL -> {
                    val decimalTypeName = typeHandle.jdbcTypeName().orElseThrow()
                    // Use type name because DuckDB does not report scale in metadata.
                    val matcher = DECIMAL_PATTERN.matcher(decimalTypeName)
                    require(matcher.matches()) { "Decimal type name does not match pattern: $decimalTypeName" }
                    val precision = matcher.group("precision").toInt()
                    val scale = matcher.group("scale").toInt()
                    return Optional.of(decimalColumnMapping(createDecimalType(precision, scale)))
                }
                Types.VARCHAR ->
                    // CHAR is an alias of VARCHAR in DuckDB https://duckdb.org/docs/sql/data_types/text
                    return Optional.of(
                        ColumnMapping.sliceMapping(
                            VarcharType.VARCHAR,
                            varcharReadFunction(VarcharType.VARCHAR),
                            varcharWriteFunction(),
                            DuckBridgeStringPredicatePushdown.VARCHAR_PUSHDOWN,
                        ),
                    )
                Types.DATE ->
                    return Optional.of(
                        ColumnMapping.longMapping(
                            DATE,
                            { resultSet, columnIndex ->
                                DATE_FORMATTER.parse(resultSet.getString(columnIndex)).getLong(EPOCH_DAY)
                            },
                            dateWriteFunction(),
                        ),
                    )
                Types.TIMESTAMP ->
                    // DuckDB TIMESTAMP is microsecond precision.
                    return Optional.of(DuckBridgeTimestampColumnMapping.mapping())
                Types.ARRAY, Types.OTHER -> {
                    // The element type is only in the type name. DatabaseMetaData.getColumns reports
                    // array table columns as Types.OTHER rather than Types.ARRAY.
                    val arrayMapping = typeHandle.jdbcTypeName().orElse(null)?.let(DuckBridgeArrayColumnMapping::fromTypeName)
                    if (arrayMapping != null) {
                        return Optional.of(arrayMapping)
                    }
                }
            }

            if (getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR) {
                return mapToUnboundedVarchar(typeHandle)
            }
            return Optional.empty()
        }

        private fun checkedVarcharMapping(session: ConnectorSession, typeHandle: JdbcTypeHandle): Optional<ColumnMapping> {
            val forced = getForcedMappingToVarchar(typeHandle)
            DuckBridgeBoundaryTypeGuard.verify(typeHandle.jdbcTypeName().orElse(""), typeHandle.jdbcType(),
                forced.isPresent || (getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR &&
                    DuckBridgeArrayColumnMapping.fromTypeName(typeHandle.jdbcTypeName().orElse("")) == null))
            return forced
        }

        override fun toWriteMapping(session: ConnectorSession, type: Type): WriteMapping =
            if (!zonedNanosTransport && type is TimestampWithTimeZoneType && type.precision > 6) {
                throw TrinoException(NOT_SUPPORTED, "Unsupported column type: ${type.displayName}. " +
                    "TIMESTAMPTZ_NS requires Arrow or Quack 0.11 typed getters; native JDBC text loses offset seconds")
            } else if (arrowTransport && type is TimeWithTimeZoneType) {
                throw TrinoException(NOT_SUPPORTED, "DuckDB Arrow TIME WITH TIME ZONE has no offset carrier")
            } else simpleWriteMapping(type)
                ?: parametricWriteMapping(type)
                ?: DuckBridgeScalarColumnMappings.toWriteMapping(type)
                ?: throw TrinoException(NOT_SUPPORTED, "Unsupported column type: ${type.displayName}")

        /** Fixed scalar types whose DuckDB type name carries no precision/scale/length. */
        private fun simpleWriteMapping(type: Type): WriteMapping? =
            when (type) {
                BOOLEAN -> WriteMapping.booleanMapping("boolean", booleanWriteFunction())
                TINYINT -> WriteMapping.longMapping("tinyint", tinyintWriteFunction())
                SMALLINT -> WriteMapping.longMapping("smallint", smallintWriteFunction())
                INTEGER -> WriteMapping.longMapping("integer", integerWriteFunction())
                BIGINT -> WriteMapping.longMapping("bigint", bigintWriteFunction())
                REAL -> WriteMapping.longMapping("float", realWriteFunction())
                DOUBLE -> WriteMapping.doubleMapping("double precision", doubleWriteFunction())
                DATE -> WriteMapping.longMapping("date", dateWriteFunction())
                else -> null
            }

        /** Types whose DuckDB type name is parameterised (decimal/char/varchar/timestamp). */
        private fun parametricWriteMapping(type: Type): WriteMapping? =
            when (type) {
                is DecimalType -> {
                    val dataType = "decimal(${type.precision}, ${type.scale})"
                    if (type.isShort) {
                        WriteMapping.longMapping(dataType, shortDecimalWriteFunction(type))
                    } else {
                        WriteMapping.objectMapping(dataType, longDecimalWriteFunction(type))
                    }
                }
                // CHAR is an alias of VARCHAR in DuckDB https://duckdb.org/docs/sql/data_types/text
                is CharType -> WriteMapping.sliceMapping("varchar", charWriteFunction())
                is VarcharType -> WriteMapping.sliceMapping("varchar", varcharWriteFunction())
                // Short timestamps (precision <= 6) map to DuckDB's microsecond TIMESTAMP.
                is TimestampType -> if (type.isShort) {
                    WriteMapping.longMapping("timestamp", timestampWriteFunction(type))
                } else {
                    null
                }
                else -> null
            }

        override fun convertPredicate(
            session: ConnectorSession,
            expression: ConnectorExpression,
            assignments: Map<String, io.trino.spi.connector.ColumnHandle>,
        ): Optional<ParameterizedExpression> =
            connectorExpressionRewriter.rewrite(session, expression, assignments)

        override fun supportsLimit(): Boolean = true

        // "Guaranteed" means the remote returns at most n rows, not that the row set is
        // deterministic; Trino's LIMIT is non-deterministic anyway.
        override fun isLimitGuaranteed(session: ConnectorSession): Boolean = true

        override fun limitFunction(): Optional<BiFunction<String, Long, String>> =
            Optional.of(BiFunction { sql, limit -> "$sql LIMIT $limit" })

        /**
         * String sort keys are only safe at string-pushdown mode >= BINARY, where the init probe has
         * proven DuckDB's ORDER BY matches Trino's codepoint order and NULLS placement. base-jdbc has
         * no per-key partial TopN, so one string key blocks the whole push.
         */
        override fun supportsTopN(session: ConnectorSession, handle: JdbcTableHandle, sortOrder: List<JdbcSortItem>): Boolean =
            sortOrder.all { isPushableSortKey(session, it.column.columnType) }

        private fun isPushableSortKey(session: ConnectorSession, type: Type): Boolean =
            when (type) {
                is VarcharType, is CharType ->
                    DuckBridgeSessionProperties.getStringPushdownMode(session).allowsFullStringComparison
                else -> true
            }

        /**
         * Guaranteed (Trino drops its own TopN) only at mode >= BINARY, where the probe has verified
         * DuckDB's ORDER BY and NULLS placement match Trino. Non-string keys could be guaranteed in
         * every mode, but the whole guarantee stays probe-gated so there is a single rule.
         */
        override fun isTopNGuaranteed(session: ConnectorSession): Boolean =
            DuckBridgeSessionProperties.getStringPushdownMode(session).allowsFullStringComparison

        override fun topNFunction(): Optional<TopNFunction> =
            Optional.of(
                TopNFunction { query, sortItems, limit ->
                    val orderBy =
                        sortItems.joinToString(", ") { item ->
                            val column = quoted(item.column.columnName)
                            // Explicit, so the result does not depend on either engine's default.
                            val order = if (item.sortOrder.isAscending) "ASC" else "DESC"
                            val nulls = if (item.sortOrder.isNullsFirst) "NULLS FIRST" else "NULLS LAST"
                            "$column $order $nulls"
                        }
                    "$query ORDER BY $orderBy LIMIT $limit"
                },
            )

        private companion object {
            private val log: Logger = Logger.get(DuckBridgeClient::class.java)

            private val loggedTimeZoneFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()

            private val DECIMAL_PATTERN: Pattern = Pattern.compile("DECIMAL\\((?<precision>[0-9]+),(?<scale>[0-9]+)\\)")
            private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")

            fun dateWriteFunction(): LongWriteFunction = DuckBridgeDateWriteFunction
        }
    }
