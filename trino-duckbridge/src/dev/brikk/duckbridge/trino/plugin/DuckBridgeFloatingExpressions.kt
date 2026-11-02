package dev.brikk.duckbridge.trino.plugin

import io.trino.spi.type.DoubleType
import io.trino.spi.type.RealType
import io.trino.spi.type.Type

internal object DuckBridgeFloatingExpressions {
    fun isFloating(type: Type): Boolean = type is DoubleType || type is RealType

    /** Primitive Trino comparisons are IEEE comparisons, not DuckDB's total NaN ordering. */
    fun comparison(left: String, operator: String, right: String): String {
        val nanResult = if (operator == "<>") "TRUE" else "FALSE"
        return "(CASE WHEN $left IS NULL OR $right IS NULL THEN NULL " +
            "WHEN isnan($left) OR isnan($right) THEN $nanResult ELSE ($left $operator $right) END)"
    }
}
