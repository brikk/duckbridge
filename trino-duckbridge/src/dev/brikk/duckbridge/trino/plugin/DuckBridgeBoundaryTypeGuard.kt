package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import java.sql.Types
import java.util.Locale

/** Temporary fail-closed policy; revisit project TODO 5bdd9ba4 after the current qualification rounds. */
internal object DuckBridgeBoundaryTypeGuard {
    fun verify(typeName: String, jdbcType: Int, textRequested: Boolean) {
        val name = typeName.trim().uppercase(Locale.ROOT)
        val family = name.substringBefore('(').substringBefore('[').trim().removeSurrounding("\"")
        val reason = when {
            jdbcType == Types.NULL || family in setOf("NULL", "SQLNULL") -> "untyped SQL NULL needs an explicitly supported source type"
            family == "VARIANT" -> "dynamic VARIANT has no qualified host carrier"
            family == "TUPLE" -> "positional TUPLE carriers are unqualified; native JDBC TUPLE-list getString can crash the JVM"
            textRequested && (family in setOf("STRUCT", "MAP", "UNION") || name.endsWith("]")) ->
                "automatic composite-to-VARCHAR conversion is not qualified for this getter/vector shape"
            else -> return
        }
        throw TrinoException(NOT_SUPPORTED, "DuckBridge temporarily rejects DuckDB type '$typeName': $reason. " +
            "Use an explicit source CAST to a supported type if its semantics are intended; no automatic SQL CAST is inserted. " +
            "See dev-docs/REPORT-boundary-type-policy-2026-10-30.md (revisit TODO 5bdd9ba4).")
    }
}
