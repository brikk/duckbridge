package dev.brikk.duckbridge.trino.plugin

import io.trino.Session
import io.trino.spi.type.TimeZoneKey

internal object TemporalEdgeAudit {
    fun run(context: EdgeAuditContext): List<String> {
        val findings = boundaryChecks(context).toMutableList()
        val observations = mutableListOf(listOf("zone", "predicate", "native_trino", "expression_disabled", "production", "production_match",
            "baseline_match", "production_filter_retained", "raw_duckdb"))
        val scalars = mutableListOf(listOf("zone", "expression", "measurement", "native_trino", "duckdb", "trino_types", "duckdb_types", "raw_match"))
        for ((zoneIndex, zone) in listOf("UTC", "America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe").withIndex()) {
            val session = Session.builder(context.session).setTimeZoneKey(TimeZoneKey.getTimeZoneKey(zone)).build()
            context.duck.createStatement().use { it.execute("SET TimeZone='${zone.replace("'", "''")}'") }
            // DuckDB stores instants, not per-value zone IDs. Its Trino mapping explicitly carries the query session zone.
            val input = PushdownEdgeFixtures.trinoInput("temporal").replaceFirst("WITH input(", "WITH raw(") +
                ", input AS (SELECT id,d,ts,z AT TIME ZONE '$zone' AS z FROM raw)"
            // UTC is an output measurement only: the test client's wall+zone parser loses late-overlap offsets.
            val source = context.trino("$input SELECT id,d,ts,z AT TIME ZONE 'UTC' FROM input ORDER BY id", session)
            val scanned = context.trino("SELECT id,d,ts,z AT TIME ZONE 'UTC' FROM edge_local.audit.temporal ORDER BY id", session)
            check(source.errorCode == null) { "Invalid temporal source oracle: ${source.describe()}" }
            if (!source.sameContract(scanned)) findings.add("TEMPORAL input/type mapping mismatch in $zone: ${scanned.describe()}")
            scalars.addAll(scalarObservations(context, input, zone, session))
            val predicates = listOf("year(d) < 1970", "month(d) = 2", "day(d) = 29", "week(d) = 1",
                "date_diff('day',DATE '1970-01-01',d) < 0", "year(ts) = 2024", "hour(ts) = 2", "millisecond(ts) = 123",
                "date_trunc('day',ts) = TIMESTAMP '2024-03-10 00:00:00'", "year(z) = 2024", "hour(z) = 1",
                "date_trunc('day',z) >= TIMESTAMP '1970-01-01 00:00:00 UTC'", "to_unixtime(z) < 0",
                "z >= TIMESTAMP '2024-11-03 06:00:00 UTC'", "z IS NULL",
                "length(CAST(date_trunc('month',d) AS VARCHAR)) = 10",
                "CAST(date_trunc('month',d) AS VARCHAR) = '2024-11-01'",
                "date_trunc('month',d) IS NULL OR year(d) < 1",
                "date_trunc('hour',d) IS NOT NULL", "date_trunc('century',ts) IS NOT NULL",
                "date_diff('hour',d,DATE '1970-01-01') >= 0",
                "date_diff('millisecond',d,DATE '1970-01-01') >= 0",
                "date_trunc(if(id=0,'day','hour'),d) IS NOT NULL",
                "date_trunc('day',z) <= z", "date_trunc('hour',z) <= z", "date_trunc('hour',ts) <= ts",
                "minute(date_trunc('hour',z)) = 0", "second(date_trunc('hour',z)) = 0",
                "hour(with_timezone(ts,'UTC')) = hour(ts)",
                "with_timezone(ts,'America/New_York') = z")
            for ((index, predicate) in predicates.withIndex()) {
                val row = context.predicate("temporal", predicate, "zone-$zoneIndex-$index", session,
                    nativePredicate = nativePredicate(predicate), oracleInput = input)
                observations.add(listOf(zone) + row)
                if (row[4] != "true") findings.add("TEMPORAL predicate mismatch in $zone: $predicate")
                if (row[5] != "true") findings.add("TEMPORAL baseline/domain mismatch in $zone: $predicate")
            }
        }
        context.write("predicates.tsv", observations)
        context.write("scalars.tsv", scalars)
        return findings
    }

    private fun boundaryChecks(context: EdgeAuditContext): List<String> {
        val findings = context.verifyInput("date_boundaries").toMutableList()
        val expected = context.trino("${PushdownEdgeFixtures.trinoInput("date_boundaries")} SELECT * FROM input ORDER BY id")
        check(expected.errorCode == null) { "Invalid finite DATE oracle: ${expected.describe()}" }
        // Native JDBC's LocalDate getter delegates through java.sql.Date and is itself lossy for BCE/extreme dates.
        // Validate actual stored dates with exact integer day counts; retain typed-getter observations separately.
        val typed = context.native("SELECT * FROM audit.date_boundaries ORDER BY id")
        val expectedDays = context.trino("${PushdownEdgeFixtures.trinoInput("date_boundaries")} " +
            "SELECT id,date_diff('day',DATE '1970-01-01',d) FROM input ORDER BY id")
        val physicalDays = context.native("SELECT id,date_diff('day',DATE '1970-01-01',d) FROM audit.date_boundaries ORDER BY id")
        val scanned = context.trino("SELECT * FROM edge_prod.audit.date_boundaries ORDER BY id")
        if (!physicalDays.sameValue(expectedDays)) findings.add("Finite DATE physical storage/seed differs from Trino")
        if (!scanned.sameContract(expected)) findings.add("Finite DATE production read differs from Trino")
        val written = context.trino("CREATE TABLE edge_prod.audit.date_bound AS ${PushdownEdgeFixtures.trinoInput("date_boundaries")} SELECT * FROM input")
        if (written.errorCode != null) findings.add("Finite DATE connector binding fails: ${written.describe()}")
        else {
            val read = context.trino("SELECT * FROM edge_prod.audit.date_bound ORDER BY id")
            if (!read.sameContract(expected)) findings.add("Finite DATE CTAS/bind/read mismatch: ${read.describe()}")
        }
        check(context.trino("DROP TABLE IF EXISTS edge_prod.audit.date_bound").errorCode == null)
        context.write("date-boundaries.tsv", listOf(
            listOf("expected", "typed_jdbc_diagnostic", "expected_days", "stored_days", "production_read", "connector_write"),
            listOf(expected.describe(), typed.describe(), expectedDays.describe(), physicalDays.describe(), scanned.describe(), written.describe())))
        for ((index, predicate) in listOf("year(d) > 9999", "length(CAST(d AS VARCHAR)) = 10",
            "length(CAST(date_trunc('month',d) AS VARCHAR)) = 10").withIndex()) {
            val row = context.predicate("date_boundaries", predicate, "date-boundary-$index")
            context.write("date-boundary-predicate-$index.tsv", listOf(row))
            if (row[4] != "true") findings.add("Finite DATE boundary predicate mismatch: $predicate")
        }
        return findings
    }

    private fun scalarObservations(context: EdgeAuditContext, input: String, zone: String, session: Session): List<List<String>> =
        listOf("year(d)", "date_diff('day',DATE '1970-01-01',d)", "date_trunc('month',d)",
            "date_trunc('day',ts)", "date_trunc('hour',z)", "date_trunc('day',z)").map { expression ->
            val zoned = expression in setOf("date_trunc('hour',z)", "date_trunc('day',z)")
            val measurement = if (zoned) "date_diff('millisecond',TIMESTAMP '1970-01-01 00:00:00.000000 UTC',$expression)*BIGINT '1000'"
                else expression
            val host = context.trino("$input SELECT id,$measurement FROM input ORDER BY id", session)
            check(host.errorCode == null) { "Invalid temporal scalar oracle: ${host.describe()}" }
            val nativeExpression = if (zoned) "epoch_us(${nativePredicate(expression)})" else nativePredicate(expression)
            val native = context.native("SELECT id,$nativeExpression FROM audit.temporal ORDER BY id")
            listOf(zone, expression, if (zoned) "engine_integer_epoch_micros" else "value",
                host.describe(), native.describe(), host.types.toString(), native.types.toString(),
                host.sameValue(native).toString())
        }

    private fun nativePredicate(sql: String): String = sql.replace("date_diff(", "date_sub(")
        .replace("millisecond(ts)", "CAST(extract('millisecond' FROM ts) % 1000 AS BIGINT)")
        .replace("to_unixtime(z)", "CAST(epoch(z) AS DOUBLE)")
        .replace("with_timezone(ts,'UTC')", "timezone('UTC',ts)")
        .replace("with_timezone(ts,'America/New_York')", "timezone('America/New_York',ts)")
        .replace("TIMESTAMP '1970-01-01 00:00:00 UTC'", "TIMESTAMPTZ '1970-01-01 00:00:00+00'")
        .replace("TIMESTAMP '2024-11-03 06:00:00 UTC'", "TIMESTAMPTZ '2024-11-03 06:00:00+00'")
}
