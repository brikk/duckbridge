package dev.brikk.duckbridge.trino.plugin

internal object DuckBridgeDateExpressions {
    /** Trino uses astronomical years, minimum four digits, and no '+' on expanded positive years. */
    fun toVarchar(value: String): String =
        "(CASE WHEN $value IS NULL THEN NULL ELSE " +
            "(CASE WHEN year($value)<0 THEN '-' ELSE '' END) || " +
            "printf('%04d-%02d-%02d',abs(year($value)),month($value),day($value)) END)"
}
