package dev.brikk.duckbridge.trino.plugin

/**
 * Deliberately smaller than Re2Safety: every admitted atom consumes at least one ASCII character.
 * No zero-width matches, anchors, alternation, captures, escapes, flags or dialect-specific syntax.
 * See dev-docs/REPORT-regexp-count-qualification-2026-10-29.md.
 */
internal object RegexpCountSafety {
    private val LITERALS = ('a'..'z').toSet() + ('A'..'Z') + ('0'..'9') + " _-/:,=".toSet()
    private val CLASSES = setOf(
        "[0-9]", "[a-z]", "[A-Z]", "[A-Za-z]", "[a-zA-Z]",
        "[A-Za-z0-9]", "[a-zA-Z0-9]", "[a-z0-9]", "[A-Z0-9]",
    )

    fun isSafe(pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            if (character == '[') {
                val end = pattern.indexOf(']', index + 1)
                if (end < 0 || pattern.substring(index, end + 1) !in CLASSES) return false
                index = end + 1
            } else {
                if (character !in LITERALS) return false
                index++
            }
            if (index < pattern.length && pattern[index] == '+') index++
        }
        return true
    }
}
