package dev.brikk.duckbridge.trino.plugin

import io.trino.Session
import io.trino.plugin.jdbc.JdbcPlugin
import io.trino.spi.type.TimeZoneKey
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.DistributedQueryRunner
import io.trino.testing.QueryFailedException
import io.trino.testing.QueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestPushdownEdgeAudits : AbstractTestQueryFramework() {
    private lateinit var duck: Connection
    private val output = Path.of("build/pushdown-edge-audits")
    private val nativeSession = testSessionBuilder().setCatalog("edge_prod").setSchema("audit")
        .setTimeZoneKey(TimeZoneKey.UTC_KEY).build()

    override fun createQueryRunner(): QueryRunner {
        val url = DuckBridgeQueryRunner.freshDatabaseUrl()
        duck = DriverManager.getConnection(url, DuckBridgeQueryRunner.duckJdbcProperties())
        recordProfile()
        PushdownEdgeFixtures.seed(duck)
        val runner = DistributedQueryRunner.builder(nativeSession).build()
        try {
            runner.installPlugin(DuckBridgePlugin())
            runner.installPlugin(JdbcPlugin("edge_no_expr") { DuckBridgeClientModule(expressionPushdownEnabled = false) })
            val properties = buildMap {
                put("connection-url", url)
                if (DuckBridgeQueryRunner.bundledParityIsUnsigned()) put("duckbridge.allow-unsigned-extensions", "true")
            }
            runner.createCatalog("edge_prod", "duckbridge", properties)
            runner.createCatalog("edge_local", "edge_no_expr", properties)
            return runner
        } catch (failure: Throwable) { runner.close(); duck.close(); throw failure }
    }

    @AfterAll
    fun closeOracle() { if (::duck.isInitialized) duck.close() }

    @Test
    fun testRemainingMathEdges() = audit("math", MathEdgeAudit::run)

    @Test
    fun testDecimalBindingAndCastEdges() = audit("decimal", DecimalEdgeAudit::run)

    @Test
    fun testLikeUnicodeNulEscapeAndPrefixEdges() = audit("like", LikeEdgeAudit::run)

    @Test
    fun testTemporalZonesBoundariesAndLocalGuards() = audit("temporal", TemporalEdgeAudit::run)

    @Test
    fun testFiniteDateWriteRoundTrip() {
        val context = EdgeAuditContext(duck, nativeSession, output.resolve("date-write-fix"), ::outcome)
        context.controls()
        val input = PushdownEdgeFixtures.trinoInput("date_boundaries")
        val expected = context.trino("$input SELECT * FROM input ORDER BY id")
        check(expected.errorCode == null)
        val created = context.trino("CREATE TABLE edge_prod.audit.finite_date_write AS $input SELECT * FROM input")
        assertThat(created.errorCode).describedAs(created.describe()).isNull()
        try {
            val read = context.trino("SELECT * FROM edge_prod.audit.finite_date_write ORDER BY id")
            val storedDays = context.native("SELECT id,date_diff('day',DATE '1970-01-01',d) FROM audit.finite_date_write ORDER BY id")
            val expectedDays = context.trino("$input SELECT id,date_diff('day',DATE '1970-01-01',d) FROM input ORDER BY id")
            context.write("round-trip.tsv", listOf(listOf("expected", "read", "expected_days", "stored_days"),
                listOf(expected.describe(), read.describe(), expectedDays.describe(), storedDays.describe())))
            assertThat(read.sameContract(expected)).describedAs(read.describe()).isTrue()
            assertThat(storedDays.sameValue(expectedDays)).describedAs(storedDays.describe()).isTrue()
        } finally {
            check(context.trino("DROP TABLE edge_prod.audit.finite_date_write").errorCode == null)
        }
    }

    @Test
    fun testInvalidTemporalUnitsStayLocal() = audit("temporal-unit-fix") { context ->
        listOf("date_trunc('hour',d) IS NOT NULL", "date_trunc('century',ts) IS NOT NULL",
            "date_diff('hour',d,DATE '1970-01-01') >= 0", "date_diff('millisecond',d,DATE '1970-01-01') >= 0")
            .mapIndexedNotNull { index, predicate ->
                val row = context.predicate("temporal", predicate, "invalid-unit-$index")
                context.write("invalid-unit-$index.tsv", listOf(row))
                if (row[4] == "true") null else "$predicate: ${row[3]} differs from ${row[1]}"
            }
    }

    @Test
    fun testTargetedDateTextCandidate() = audit("date-text-candidate") { context ->
        val input = PushdownEdgeFixtures.trinoInput("date_boundaries")
        val expected = context.trino("$input SELECT id,CAST(d AS VARCHAR) FROM input ORDER BY id")
        val expression = DuckBridgeDateExpressions.toVarchar("d")
        val actual = context.native("SELECT id,$expression FROM audit.date_boundaries ORDER BY id")
        context.write("candidate.tsv", listOf(listOf("trino", "native"), listOf(expected.describe(), actual.describe())))
        val production = context.trino("SELECT id,CAST(d AS VARCHAR) FROM edge_prod.audit.date_boundaries ORDER BY id")
        val predicate = context.predicate("date_boundaries", "length(CAST(d AS VARCHAR))=10", "date-text-length")
        val problems = mutableListOf<String>()
        if (!actual.sameValue(expected)) problems.add("DATE text candidate mismatch: ${actual.describe()} versus ${expected.describe()}")
        if (!production.sameContract(expected)) problems.add("DATE text production mismatch: ${production.describe()}")
        if (predicate[4] != "true" || predicate[6] != "false") problems.add("DATE text predicate lost parity or pushdown: $predicate")
        problems
    }

    @Test
    fun testTargetedFloatingComparisons() = audit("floating-comparison-fix") { context ->
        val predicates = listOf("x > 0", "x >= 0", "x < 0", "x <= 0", "x=0", "x<>0",
            "NOT (x > 0)", "x+x > 0", "x*x >= 0", "x = y", "x <> y", "x < y", "x <= y", "x > y", "x >= y",
            "NOT (x=y)", "x IS NOT DISTINCT FROM y", "x IS DISTINCT FROM y", "nullif(x,y) > 0", "nullif(x,y) IS NULL",
            "sign(x)>0", "sign(x)=x", "atan2(CAST(0 AS DOUBLE),sign(x))>1",
            "x>0 OR x IS NULL", "x IN (0,1)", "x NOT IN (0,1)", "x BETWEEN -1 AND 1", "x NOT BETWEEN -1 AND 1")
        listOf("math", "real_numbers").flatMap { table ->
            predicates.mapIndexedNotNull { index, predicate ->
                // Accepted Trino 483 limitation: compiled REAL IN distinguishes -0 bits, while equality/domains do not.
                val oracleQuery = if (table == "real_numbers" && predicate == "x IN (0,1)")
                    PushdownEdgeFixtures.realMembershipEqualitySql(negated = false) else null
                val row = context.predicate(table, predicate, "$table-$index", oracleInput = PushdownEdgeFixtures.runtimeFloatingInput(table),
                    oracleQuery = oracleQuery)
                context.write("$table-$index.tsv", listOf(row))
                if (predicate == "x > 0") check(row[6] == "false") { "Common floating range lost full pushdown" }
                if (row[4] == "true" && row[5] == "true") null else "$table::$predicate: production=${row[3]} baseline=${row[2]} expected=${row[1]}"
            }
        }
    }

    @Test
    fun testRealSignedZeroInCompilationDiagnostic() = audit("trino-real-in-diagnostic") { context ->
        // Trino 483's InCodeGenerator DIRECT_SWITCH compares long-backed REAL bit patterns.
        check(context.verifyInput("real_numbers").isEmpty()) { "REAL source values/types changed" }
        val negativeZeros = PushdownEdgeFixtures.math.mapIndexedNotNull { id, pair ->
            if (pair.first?.toFloat()?.toRawBits() == (-0.0f).toRawBits()) listOf("I:$id") else null
        }.toSet()
        check(negativeZeros.isNotEmpty())
        val observations = mutableListOf(listOf("predicate", "folded", "runtime", "runtime_equality", "normalized_OR", "expression_disabled", "production"))
        val findings = mutableListOf<String>()
        for (negated in listOf(false, true)) {
            val predicate = if (negated) "x NOT IN (0,1)" else "x IN (0,1)"
            val equality = if (negated) "NOT (x=0 OR x=1)" else "(x=0 OR x=1)"
            val select = "SELECT id FROM input WHERE $predicate ORDER BY id"
            val folded = context.trino("${PushdownEdgeFixtures.trinoInput("real_numbers")} $select")
            val runtime = context.trino("${PushdownEdgeFixtures.runtimeFloatingInput("real_numbers")} $select")
            val equalitySql = PushdownEdgeFixtures.realMembershipEqualitySql(negated)
            val equal = context.trino(equalitySql)
            val equalityPlan = context.trino("EXPLAIN (TYPE DISTRIBUTED) $equalitySql")
            context.write("equality-$negated-plan.tsv", listOf(listOf(equalityPlan.describe())))
            check(equalityPlan.errorCode == null && !equalityPlan.describe().contains(" IN (")) { "Equality oracle was rewritten into IN" }
            val normalizedOr = context.trino("${PushdownEdgeFixtures.runtimeFloatingInput("real_numbers")} " +
                "SELECT id FROM input WHERE $equality ORDER BY id")
            val physical = listOf("edge_local", "edge_prod").map { catalog ->
                val sql = "SELECT id FROM $catalog.audit.real_numbers WHERE $predicate ORDER BY id"
                val plan = context.trino("EXPLAIN (TYPE DISTRIBUTED) $sql")
                context.write("$catalog-$negated-plan.tsv", listOf(listOf(plan.describe())))
                check(plan.errorCode == null)
                check(plan.describe().contains("filterPredicate") == negated) { "Revisit REAL membership planner-path contract" }
                context.trino(sql)
            }
            observations.add(listOf(predicate, folded.describe(), runtime.describe(), equal.describe(), normalizedOr.describe(),
                physical[0].describe(), physical[1].describe()))
            context.write("folded-runtime-equality-scanned.tsv", observations)
            check((listOf(folded, runtime, equal, normalizedOr) + physical).all { it.errorCode == null })
            check(folded.sameValue(equal)) { "Folded REAL membership no longer matches independent equality" }
            val legacy = System.getProperty("duckbridge.test.trinoVersion", "483") == "483"
            val expectedCompiled = when {
                !legacy -> equal.rows.toSet() // 484 excludes REAL from the faulty DIRECT_SWITCH path.
                negated -> equal.rows.toSet() + negativeZeros
                else -> equal.rows.toSet() - negativeZeros
            }
            check(runtime.rows.toSet() == expectedCompiled) { "Compiled REAL membership does not match the verified engine-version contract" }
            val expected = if (negated) runtime else equal
            if (physical.any { !it.sameValue(expected) }) findings.add("REAL $predicate differs from its verified Trino execution-path contract")
        }
        context.write("folded-runtime-equality-scanned.tsv", observations)
        findings
    }

    private fun audit(name: String, operation: (EdgeAuditContext) -> List<String>) {
        duck.createStatement().use { it.execute("SET TimeZone='UTC'") }
        val context = EdgeAuditContext(duck, nativeSession, output.resolve(name), ::outcome)
        context.controls()
        val problems = operation(context)
        context.controls()
        context.write("findings.tsv", listOf(listOf("finding")) + problems.map { listOf(it) })
        assertThat(problems).describedAs("%s edge audit; complete evidence: %s", name, context.output.toAbsolutePath()).isEmpty()
    }

    private fun recordProfile() {
        val trino = Path.of(io.trino.operator.scalar.MathFunctions::class.java.protectionDomain.codeSource.location.toURI())
        val driver = Path.of(org.duckdb.DuckDBDriver::class.java.protectionDomain.codeSource.location.toURI())
        val target = System.getProperty("duckbridge.test.trinoVersion", "483")
        check(target == DuckBridgeTrinoTarget.VERSION.toString()) { "Compiled ABI and test engine do not match" }
        check(trino.fileName.toString() == "trino-main-$target.jar" && driver.fileName.toString() == "duckdb_jdbc-2.0.0.0.jar")
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(driver).use { input -> DigestInputStream(input, digest).use { it.transferTo(OutputStream.nullOutputStream()) } }
        val hash = java.util.HexFormat.of().formatHex(digest.digest())
        check(hash == "5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186")
        Files.createDirectories(output)
        Files.writeString(output.resolve("profile.txt"), "Trino=$trino\nDuckDB JDBC=$driver\nSHA256=$hash\n" +
            "JDK=${Runtime.version()}\nOS=${System.getProperty("os.name")} ${System.getProperty("os.arch")}\n" +
            "JVM.default.zone=${java.time.ZoneId.systemDefault()}\n" +
            "SQL NULL/type/exact floating bits; NaN payload ignored; no numeric tolerance\n")
    }

    private fun outcome(session: Session, sql: String): EdgeAuditResult = try {
        val result = computeActual(session, sql)
        EdgeAuditResult(result.types.map { it.toString() }, result.materializedRows.map { row ->
            (0 until row.fieldCount).map { EdgeAuditValue.canonical(row.getField(it)) }
        })
    } catch (failure: QueryFailedException) {
        val info = (queryRunner as DistributedQueryRunner).coordinator.queryManager.getFullQueryInfo(failure.queryId)
        val code = checkNotNull(info.errorCode).name
        check(code !in setOf("SYNTAX_ERROR", "FUNCTION_NOT_FOUND", "COLUMN_NOT_FOUND", "TYPE_MISMATCH")) {
            "Invalid audit shape/oracle: $code ${failure.message} SQL=$sql"
        }
        EdgeAuditResult(errorCode = code, errorType = info.failureInfo?.type, message = info.failureInfo?.message ?: failure.message)
    }
}
