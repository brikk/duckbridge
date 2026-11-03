package dev.brikk.duckbridge.trino.plugin

import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.Variable
import io.trino.spi.type.DoubleType.DOUBLE
import java.sql.Types
import java.util.Optional

internal object MathEdgeAudit {
    private val names = listOf("abs", "ceil", "floor", "truncate", "exp", "sin", "cos", "tan", "atan", "atan2",
        "sinh", "cosh", "tanh", "degrees", "radians", "sign")
    private val localMath = setOf("atan", "atan2", "cosh")
    private data class ScalarObservation(val rows: List<List<String>>, val finite: List<Pair<Int, Double>>,
        val matches: Boolean, val productionMatches: Boolean)

    fun run(context: EdgeAuditContext): List<String> {
        val problems = context.verifyInput("math")
        if (problems.isNotEmpty()) return problems
        val findings = mutableListOf<String>()
        val scalars = mutableListOf(listOf("function", "id", "x", "y", "trino", "duckdb", "match", "native_emission", "duckdb_types"))
        val predicates = mutableListOf(listOf("predicate", "native_trino", "expression_disabled", "production", "production_match",
            "baseline_match", "production_filter_retained", "raw_duckdb"))
        for (name in names) {
            val expression = if (name == "atan2") "$name(x,y)" else "$name(x)"
            val native = nativeDiagnostic(name, expression)
            val scalar = scalar(context, name, expression, native)
            scalars.addAll(scalar.rows)
            recordScalar(name, scalar, findings)
            val shapes = listOf("$expression > 0", "$expression >= 0", "$expression < 0", "$expression <= 0", "$expression = 0",
                "$expression <> 0", "$expression = 1", "$expression IS NULL", "NOT ($expression > 0)",
                "$expression > 0 OR x IS NULL", "$expression >= 0 AND y <> 0",
                "atan2(CAST(0 AS DOUBLE),$expression) > 1")
            for ((index, predicate) in shapes.withIndex()) {
                val row = context.predicate("math", predicate, "$name-$index", nativePredicate = predicate.replace(expression, native))
                predicates.add(row)
                recordPredicate(row, findings, name in localMath && index == 0)
            }
            for ((id, expected) in scalar.finite) {
                val predicate = "id=$id AND $expression=CAST('$expected' AS DOUBLE)"
                val row = context.predicate("math", predicate, "$name-finite-$id", nativePredicate = predicate.replace(expression, native))
                predicates.add(row)
                recordPredicate(row, findings, name in localMath)
            }
        }
        findings.addAll(floatingControls(context, predicates))
        context.write("scalars.tsv", scalars)
        context.write("predicates.tsv", predicates)
        return findings
    }

    private fun recordScalar(name: String, scalar: ScalarObservation, findings: MutableList<String>) {
        if (!scalar.matches && name !in localMath) findings.add("SCALAR $name: native value/error differs; see scalar outcomes")
        if (!scalar.productionMatches) findings.add("PRODUCTION SCALAR $name differs from native Trino")
    }

    private fun recordPredicate(row: List<String>, findings: MutableList<String>, requiresLocal: Boolean) {
        if (row[4] != "true") findings.add("PREDICATE ${row[0]}: production differs from native Trino")
        if (row[5] != "true") findings.add("BASELINE/DOMAIN ${row[0]}: expression-disabled catalog also differs")
        if (requiresLocal && row[6] != "true") findings.add("LOCAL PLAN ${row[0]} must retain its filter")
    }

    private fun scalar(context: EdgeAuditContext, name: String, expression: String, native: String): ScalarObservation {
        val host = context.trino("${PushdownEdgeFixtures.trinoInput("math")} SELECT id,$expression FROM input ORDER BY id")
        check(host.errorCode == null) { "Unexpected native Trino failure: ${host.describe()}" }
        val production = context.trino("SELECT id,$expression FROM edge_prod.audit.math ORDER BY id")
        context.write("$name-production-scalars.tsv", listOf(listOf("trino", "production", "contract_match"),
            listOf(host.describe(), production.describe(), production.sameContract(host).toString())))
        val actual = context.native("SELECT id,$native FROM audit.math ORDER BY id", doubleValue = true)
        if (actual.errorCode != null) return ScalarObservation(
            listOf(listOf(name, "ALL", "", "", host.describe(), actual.describe(), "false", native, actual.types.toString())),
            emptyList(), false, production.sameContract(host))
        check(actual.rows.size == host.rows.size)
        val finite = mutableListOf<Pair<Int, Double>>()
        val rows = host.rows.mapIndexed { index, row ->
            check(actual.rows[index][0] == row[0])
            val same = row[1] == actual.rows[index][1]
            val input = PushdownEdgeFixtures.math[index]
            if (!same && row[1].startsWith("F:") && row[1] != "F:NaN") {
                val value = java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(row[1].substring(2), 16))
                if (value.isFinite() && finite.size < 3) finite.add(index to value)
            }
            listOf(name, index.toString(), input.first.toString(), input.second.toString(), row[1], actual.rows[index][1],
                same.toString(), native, actual.types.toString())
        }
        return ScalarObservation(rows, finite, host.sameValue(actual), production.sameContract(host))
    }

    private fun nativeDiagnostic(name: String, expression: String): String {
        val admitted = emitted(name)
        if (name in localMath) {
            check(admitted == null) { "Withdrawn math must not emit: $name" }
            return expression // Raw native counterexample only; never an approved production emission.
        }
        return checkNotNull(admitted) { "Expected existing math catalog entry: $name" }
    }

    private fun floatingControls(context: EdgeAuditContext, observations: MutableList<List<String>>): List<String> {
        val findings = context.verifyInput("real_numbers").toMutableList()
        for (table in listOf("math", "real_numbers")) {
            for ((index, predicate) in listOf("x > 0", "x >= 0", "NOT (x > 0)", "x+x > 0", "x*x >= 0").withIndex()) {
                val row = context.predicate(table, predicate, "$table-column-$index")
                observations.add(listOf("$table::$predicate") + row.drop(1))
                if (row[4] != "true") findings.add("FLOATING COLUMN/OPERATOR $table $predicate: production differs")
                if (row[5] != "true") findings.add("FLOATING BASELINE/DOMAIN $table $predicate: expression-disabled catalog also differs")
            }
        }
        return findings
    }

    private fun emitted(name: String): String? {
        val assignments: Map<String, ColumnHandle> = listOf("x", "y").associateWith { column ->
            JdbcColumnHandle(column, JdbcTypeHandle(Types.DOUBLE, Optional.of("DOUBLE"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()), DOUBLE)
        }
        val args: List<ConnectorExpression> = if (name == "atan2") listOf(Variable("x", DOUBLE), Variable("y", DOUBLE)) else listOf(Variable("x", DOUBLE))
        return DuckBridgeExpressionTranslator.translate(Call(DOUBLE, FunctionName(name), args), assignments, null, aliasAvailable = false)
    }
}
