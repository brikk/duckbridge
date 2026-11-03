package dev.brikk.duckbridge.trino.plugin

internal object LikeEdgeAudit {
    fun run(context: EdgeAuditContext): List<String> {
        val findings = context.verifyInput("strings").toMutableList()
        val maximum = String(Character.toChars(0x10FFFF))
        val patterns = listOf("", "%", "_", "__", "a", "a%", "a_", "a_%", "a_b", "a%b", "a'b", "a\\b",
            "é%", "é_", "é%", "😀%", "😀_", "$maximum%", "a\uFFFF%", "a\u0000%", "a\u0000_", "\u0000%")
        val predicates = patterns.flatMap { pattern ->
            val literal = "(${PushdownEdgeFixtures.text(pattern)})"
            listOf("s LIKE $literal", "s NOT LIKE $literal")
        } + listOf("s LIKE 'a!_%' ESCAPE '!'", "s LIKE 'a!%%' ESCAPE '!'", "s LIKE 'a\\_%' ESCAPE '\\'",
            "s LIKE 'aé_%' ESCAPE 'é'", "s LIKE 'a%' ESCAPE chr(0)", "s LIKE ('a' || chr(0) || '%') OR s IS NULL")
        val observations = mutableListOf(listOf("predicate", "native_trino", "expression_disabled", "production", "production_match",
            "baseline_match", "production_filter_retained", "raw_duckdb"))
        for ((index, predicate) in predicates.withIndex()) {
            val row = context.predicate("strings", predicate, "like-$index")
            observations.add(row)
            if (row[4] != "true") findings.add("LIKE mismatch: ${EdgeAuditValue.display(predicate)}")
            if (row[5] != "true") findings.add("LIKE baseline/domain mismatch: ${EdgeAuditValue.display(predicate)}")
        }
        context.write("predicates.tsv", observations)
        return findings
    }
}
