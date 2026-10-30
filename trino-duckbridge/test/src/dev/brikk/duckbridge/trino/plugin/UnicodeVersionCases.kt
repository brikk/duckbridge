package dev.brikk.duckbridge.trino.plugin

/** Unicode 14/16 additions that the old ICU 66.1 baseline could not evaluate correctly. */
internal object UnicodeVersionCases {
    fun text(vararg points: Int): String = buildString { points.forEach { appendCodePoint(it) } }

    val caseMappingInputs: List<String> = buildList {
        add(text(0x2C2F))
        add(text(0x2C5F))
        // Include Vithkuqi's holes as identity controls, not an assumed blanket offset.
        for (point in 0x10570..0x10595) { add(text(point)); add(text(point + 39)) }
        for ((upper, lower) in listOf(0x1C89 to 0x1C8A, 0xA7CB to 0x264, 0xA7CC to 0xA7CD,
            0xA7DA to 0xA7DB, 0xA7DC to 0x19B)) {
            add(text(upper))
            add(text(lower))
        }
    }

    val normalizationInputs: List<String> = listOf(
        text(0x105D2, 0x307), text(0x11382, 0x113C9),
        text(0x1611E, 0x1611E, 0x1611F), text(0x16D63, 0x16D67, 0x16D67),
        text(0x300, 0x113CE),
    )
}
