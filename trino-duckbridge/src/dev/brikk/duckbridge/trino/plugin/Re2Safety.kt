/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.duckbridge.trino.plugin

/**
 * Conservative allowlist deciding whether a constant regex pattern may be pushed to DuckDB.
 *
 * Trino compiles patterns with Joni (Java/Ruby-flavoured syntax); DuckDB compiles with RE2. Outside
 * a shared core the engines either reject the pattern (RE2 has no lookaround, backreferences,
 * possessive quantifiers, `\Z`, `\G`, inline `(?x)`, POSIX `\p{Alpha}`) — a loud failure — or, worse,
 * silently disagree (`$` matching before a trailing newline in Joni but not RE2; `[a-z&&[^b]]`
 * intersection; `[[:alpha:]]` POSIX brackets; `\v`, `\h`, `\R` classes). The translator therefore
 * pushes a regex only when the pattern is a constant composed purely of constructs verified to have
 * identical meaning in both engines. Anything else stays in Trino. (EV-A8 in
 * dev-docs/TODO-rectify-from-eval.md.)
 *
 * Accepted: literals; `.`; `|`; `^`; groups `(` `)` and non-capturing `(?:`; quantifiers `* + ?`,
 * lazy `*? +? ??`, and `{m}` `{m,}` `{m,n}`; escapes of ASCII punctuation; `\d \D \w \W \s \S \b \B
 * \t \n \r \f \A \z \xhh \x{h..h} \Q..\E` and general-category `\p{L}`-style classes; bracket classes
 * with literals, ranges, negation and the same escapes (minus `\b`, which is backspace in Java).
 *
 * Rejected: `$`; `\Z \G \v \h \H \R \X \N \e \a \c \u \0 \1..\9 \k`; any `(?` other than `(?:`;
 * possessive quantifiers; `{` not forming a valid bounded quantifier; nested `[`, `[:`, or `&&`
 * inside a class; `\p{...}` with anything but a 1–2 letter general-category code.
 */
internal object Re2Safety {
    /** True iff [pattern] uses only constructs with identical semantics in Joni and RE2. */
    fun isSafe(pattern: String): Boolean {
        val st = Scan(pattern)
        while (st.i < pattern.length) {
            val next = if (st.inClass) stepInClass(st) else stepOutsideClass(st)
            if (next < 0) return false
            st.i = next
        }
        return !st.inClass
    }

    /** Mutable scan position; `classContentStart` is the index just after `[` or `[^`. */
    private class Scan(val p: String) {
        var i: Int = 0
        var inClass: Boolean = false
        var classContentStart: Int = -1
    }

    /** Consume one construct inside a bracket class; returns the next index or -1 to reject. */
    private fun stepInClass(st: Scan): Int {
        val p = st.p
        val i = st.i
        return when (p[i]) {
            '\\' -> escapeLength(p, i, inClass = true).let { if (it < 0) -1 else i + it }
            '[' -> -1 // nested class or POSIX [:name:]
            '&' -> if (i + 1 < p.length && p[i + 1] == '&') -1 else i + 1
            ']' ->
                if (i > st.classContentStart) {
                    st.inClass = false
                    i + 1
                } else {
                    -1 // `[]` / `[^]`: a literal ']' in Java, an error in RE2
                }
            else -> i + 1
        }
    }

    /** Consume one construct outside a bracket class; returns the next index or -1 to reject. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // one branch per regex construct
    private fun stepOutsideClass(st: Scan): Int {
        val p = st.p
        val i = st.i
        val n = p.length
        return when (p[i]) {
            '$' -> -1
            '\\' -> escapeLength(p, i, inClass = false).let { if (it < 0) -1 else i + it }
            '(' ->
                when {
                    i + 1 < n && p[i + 1] == '?' && i + 2 < n && p[i + 2] == ':' -> i + 3
                    i + 1 < n && p[i + 1] == '?' -> -1 // lookaround, inline flags, named/atomic groups
                    else -> i + 1
                }
            '[' -> {
                st.inClass = true
                val afterNegation = if (i + 1 < n && p[i + 1] == '^') i + 2 else i + 1
                st.classContentStart = afterNegation
                afterNegation
            }
            '{' -> {
                val len = boundedQuantifierLength(p, i)
                if (len < 0) return -1
                quantifierSuffix(p, i + len)
            }
            '*', '+', '?' -> quantifierSuffix(p, i + 1)
            else -> i + 1
        }
    }

    /** After a quantifier: possessive `+` rejects (-1); lazy `?` is consumed. */
    private fun quantifierSuffix(p: String, i: Int): Int =
        when {
            i < p.length && p[i] == '+' -> -1
            i < p.length && p[i] == '?' -> i + 1
            else -> i
        }

    /**
     * Length of the escape sequence starting at [i] (which is a backslash), or -1 if it is not on the
     * allowlist.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun escapeLength(p: String, i: Int, inClass: Boolean): Int {
        if (i + 1 >= p.length) return -1
        val e = p[i + 1]
        return when {
            e == 'x' -> hexEscapeLength(p, i)
            e == 'p' || e == 'P' -> propertyEscapeLength(p, i)
            e == 'b' -> if (inClass) -1 else 2
            e in SIMPLE_ESCAPES -> 2
            (e == 'A' || e == 'z' || e == 'Q' || e == 'E') && !inClass -> 2
            !e.isLetterOrDigit() && e.code < 0x80 -> 2 // escaped ASCII punctuation / space
            else -> -1
        }
    }

    private val SIMPLE_ESCAPES: Set<Char> = setOf('d', 'D', 'w', 'W', 's', 'S', 'B', 't', 'n', 'r', 'f')

    /** `\xhh` or `\x{h..h}`; -1 otherwise. */
    private fun hexEscapeLength(p: String, i: Int): Int {
        if (i + 2 < p.length && p[i + 2] == '{') {
            val close = p.indexOf('}', i + 3)
            if (close < 0) return -1
            val digits = p.substring(i + 3, close)
            return if (digits.isNotEmpty() && digits.all { isHex(it) }) close - i + 1 else -1
        }
        return if (i + 3 < p.length && isHex(p[i + 2]) && isHex(p[i + 3])) 4 else -1
    }

    /** `\p{X}` / `\P{X}` with X a general-category code (`L`, `Lu`, `Nd`, ...); -1 otherwise. */
    private fun propertyEscapeLength(p: String, i: Int): Int {
        if (i + 2 >= p.length || p[i + 2] != '{') return -1
        val close = p.indexOf('}', i + 3)
        if (close < 0) return -1
        val name = p.substring(i + 3, close)
        val ok =
            name.length in 1..2 &&
                name[0] in GENERAL_CATEGORY_MAJOR &&
                (name.length == 1 || name[1].isLowerCase())
        return if (ok) close - i + 1 else -1
    }

    private val GENERAL_CATEGORY_MAJOR: Set<Char> = setOf('L', 'M', 'N', 'P', 'S', 'Z', 'C')

    /** `{m}`, `{m,}`, `{m,n}` with m <= n; -1 for anything else (Java errors, RE2 takes literally). */
    private fun boundedQuantifierLength(p: String, i: Int): Int {
        val close = p.indexOf('}', i + 1)
        if (close < 0) return -1
        val body = p.substring(i + 1, close)
        val parts = body.split(',')
        if (parts.isEmpty() || parts.size > 2) return -1
        if (parts[0].isEmpty() || !parts[0].all { it.isDigit() }) return -1
        if (parts.size == 2 && parts[1].isNotEmpty()) {
            if (!parts[1].all { it.isDigit() }) return -1
            if (parts[0].toBigInteger() > parts[1].toBigInteger()) return -1
        }
        return close - i + 1
    }

    private fun isHex(c: Char): Boolean = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
}
