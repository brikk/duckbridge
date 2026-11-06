package dev.brikk.duckbridge.trino.plugin

internal object DecimalEdgeAudit {
    fun run(context: EdgeAuditContext): List<String> {
        val findings = context.verifyInput("decimals").toMutableList()
        val expected = context.trino("${PushdownEdgeFixtures.trinoInput("decimals")} SELECT * FROM input ORDER BY id")
        val physical = context.native("SELECT * FROM audit.decimals ORDER BY id")
        if (!physical.sameValue(expected)) findings.add("Prepared BigDecimal source binding differs from native Trino")
        val create = context.trino("CREATE TABLE edge_prod.audit.decimal_bound AS ${PushdownEdgeFixtures.trinoInput("decimals")} SELECT * FROM input")
        if (create.errorCode != null) findings.add("DECIMAL CTAS/bind failed: ${create.describe()}")
        else {
            val roundTrip = context.trino("SELECT * FROM edge_prod.audit.decimal_bound ORDER BY id")
            context.write("binding.tsv", listOf(listOf("expected", "prepared_jdbc", "connector_ctas", "types"),
                listOf(expected.describe(), physical.describe(), roundTrip.describe(), roundTrip.types.toString())))
            if (!roundTrip.sameContract(expected)) findings.add("DECIMAL(38,18) connector CTAS/bind/read changes values or type")
            check(context.trino("DROP TABLE edge_prod.audit.decimal_bound").errorCode == null)
        }
        val scalars = mutableListOf(listOf("expression", "id", "native_trino", "local_column", "duckdb", "trino_type", "duckdb_type"))
        val predicates = mutableListOf(listOf("predicate", "native_trino", "expression_disabled", "production", "production_match",
            "baseline_match", "production_filter_retained", "raw_duckdb"))
        val limitations = mutableListOf(listOf("target", "native_trino", "production"))
        val targets = listOf("TINYINT", "SMALLINT", "INTEGER", "BIGINT", "DOUBLE", "BOOLEAN", "VARCHAR", "DECIMAL(10,2)")
        for ((targetIndex, target) in targets.withIndex()) {
            val expression = "TRY_CAST(d AS $target)"
            val host = context.trino("${PushdownEdgeFixtures.trinoInput("decimals")} SELECT id,$expression FROM input ORDER BY id")
            check(host.errorCode == null) { "Invalid decimal audit control: ${host.describe()}" }
            val local = context.trino("SELECT id,$expression FROM edge_local.audit.decimals ORDER BY id")
            val actual = context.native("SELECT id,$expression FROM audit.decimals ORDER BY id")
            scalars.add(listOf(expression, "ALL", host.describe(), local.describe(), actual.describe(), host.types.toString(), actual.types.toString()))
            if (!local.sameContract(host)) findings.add("DECIMAL local read/TRY_CAST differs: $target")
            val suffix = if (target == "BOOLEAN") " = TRUE" else if (target == "VARCHAR") " = '0.500000000000000000'" else " > 0"
            for ((shape, predicate) in listOf("$expression$suffix", "$expression IS NULL").withIndex()) {
                val row = context.predicate("decimals", predicate, "try-$targetIndex-$shape")
                predicates.add(row)
                if (row[4] != "true") findings.add("DECIMAL predicate mismatch: $predicate")
            }
            for (id in listOf(0, 8, 21)) {
                val cast = "CAST(d AS $target)"
                val nativeInput = singleInput(id)
                val hostCast = context.trino("$nativeInput SELECT id,$cast FROM input")
                val localCast = context.trino("SELECT id,$cast FROM edge_local.audit.decimals WHERE id=$id")
                val nativeCast = context.native("SELECT id,$cast FROM audit.decimals WHERE id=$id")
                scalars.add(listOf(cast, id.toString(), hostCast.describe(), localCast.describe(), nativeCast.describe(),
                    hostCast.types.toString(), nativeCast.types.toString()))
                if (!localCast.sameContract(hostCast)) findings.add("DECIMAL CAST local value/error contract differs: $target id=$id")
                val productionCast = context.trino("SELECT id,$cast FROM edge_prod.audit.decimals WHERE id=$id")
                if (!productionCast.sameContract(hostCast)) findings.add("DECIMAL CAST production value/error contract differs: $target id=$id")
                val predicate = "id=$id AND $cast$suffix"
                val row = context.predicate("decimals", predicate, "cast-$targetIndex-$id", oracleInput = nativeInput)
                predicates.add(row)
                recordPredicateDifference(target, id, row, limitations, findings)
            }
        }
        context.write("scalars.tsv", scalars)
        context.write("predicates.tsv", predicates)
        context.write("approved-overflow-limitations.tsv", limitations)
        check(limitations.size == 5) { "Revisit the four explicitly approved narrowing-overflow limitations" }
        return findings
    }

    private fun recordPredicateDifference(target: String, id: Int, row: List<String>,
        limitations: MutableList<List<String>>, findings: MutableList<String>) {
        if (row[4] == "true") return
        if (approvedOverflowLimitation(target, id, row)) limitations.add(listOf(target, row[1], row[3]))
        else findings.add("DECIMAL CAST predicate value/error contract differs: $target id=$id")
    }

    private fun approvedOverflowLimitation(target: String, id: Int, row: List<String>): Boolean {
        val nativeTarget = mapOf("TINYINT" to "INT8", "SMALLINT" to "INT16", "INTEGER" to "INT32", "BIGINT" to "INT64")[target] ?: return false
        return id == 21 && row[1] == "ERROR[NUMERIC_VALUE_OUT_OF_RANGE/io.trino.spi.TrinoException] " +
            "Cannot cast '-99999999999999999999.999999999999999999' to $target" &&
            row[3].startsWith("ERROR[JDBC_ERROR/io.trino.spi.TrinoException] Conversion Error: Failed to cast decimal value " +
                "-100000000000000000000 to type $nativeTarget when casting from source column d")
    }

    private fun singleInput(id: Int): String {
        val value = PushdownEdgeFixtures.decimals[id]
        return "WITH input(id,d) AS (VALUES (CAST($id AS BIGINT),CAST(${value?.let(PushdownEdgeFixtures::quote) ?: "NULL"} AS DECIMAL(38,18))))"
    }
}
