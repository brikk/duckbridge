package dev.brikk.duckbridge.trino.plugin

/** Raw DOUBLE inputs, including signed zero and odd/even exponents beyond the integer precision boundary. */
internal object PowerEdgeCases {
    data class Case(val id: Long, val base: Double?, val exponent: Double?)

    private val bases = listOf(
        null, Double.NEGATIVE_INFINITY, -Double.MAX_VALUE, -10.0, -2.0, -1.0,
        -Math.nextDown(1.0), -java.lang.Double.MIN_NORMAL, -Double.MIN_VALUE, -0.0,
        0.0, Double.MIN_VALUE, java.lang.Double.MIN_NORMAL, Math.nextDown(1.0),
        1.0, Math.nextUp(1.0), 2.0, 10.0, Math.PI, Math.E, Double.MAX_VALUE,
        Double.POSITIVE_INFINITY, Double.NaN,
    )
    private val exponents = listOf(
        null, Double.NEGATIVE_INFINITY, -Double.MAX_VALUE, -9007199254740992.0, -9007199254740991.0,
        -1075.0, -1074.0, -1024.0, -1023.0, -3.0, -2.0, -1.0, -0.5, -0.0, 0.0,
        Double.MIN_VALUE, 0.1, 1.0 / 3, 0.5, 1.0, 2.0, 3.0, 1023.0, 1024.0,
        1074.0, 1075.0, 9007199254740991.0, 9007199254740992.0, Double.MAX_VALUE,
        Double.POSITIVE_INFINITY, Double.NaN,
    )
    val cases: List<Case> = bases.flatMap { base -> exponents.map { base to it } }
        .mapIndexed { index, (base, exponent) -> Case(index.toLong(), base, exponent) }

    /** NaN payload/sign is recorded but not treated as a distinct SQL value. All other bits, including -0, matter. */
    fun same(expected: Double?, actual: Double?): Boolean = when {
        expected == null || actual == null -> expected == null && actual == null
        expected.isNaN() && actual.isNaN() -> true
        else -> java.lang.Double.doubleToRawLongBits(expected) == java.lang.Double.doubleToRawLongBits(actual)
    }

    fun describe(value: Double?): String = if (value == null) "NULL" else
        "$value:0x${java.lang.Long.toUnsignedString(java.lang.Double.doubleToRawLongBits(value), 16)}"
}
