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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The RE2-safe pattern allowlist that gates regex pushdown (EV-A8). */
class TestRe2Safety {
    @Test
    fun acceptsSharedCore() {
        for (p in listOf(
            "", "abc", "[0-9]+", "^abc", "a|b", "(ab)+", "(?:ab)+?", "a.b", "\\d{3}-\\d{4}", "x{2,}", "x{2,5}?",
            "[^a-z]", "[a-z0-9_.-]", "\\w+\\s*\\W", "\\bword\\B", "\\t\\n\\r\\f", "\\.\\*\\+\\?\\(\\)\\[\\]\\{\\}\\|\\\\",
            "\\x41\\x{1F600}", "\\p{L}+\\p{Nd}", "\\P{Lu}", "\\Qa.b\\E", "\\Aabc\\z", "[\\d\\s]", "[\\]\\[]", "a*?b+?c??",
            "[a\\-z]", "(a)(b)(c)",
        )) {
            assertThat(Re2Safety.isSafe(p)).`as`("should accept: %s", p).isTrue()
        }
    }

    @Test
    fun rejectsDialectDivergentConstructs() {
        for (p in listOf(
            // `$` matches before a trailing newline in Joni, not in RE2
            "c$", "^a$",
            // lookaround, inline flags, named / atomic groups
            "a(?=b)", "a(?!b)", "(?<=a)b", "(?i)abc", "(?<name>a)", "(?>a)", "(?x) a b",
            // backreferences
            "(a)\\1", "\\k<n>",
            // Java-only escapes / classes
            "\\Zx", "\\Gx", "\\v", "\\h", "\\R", "\\X", "\\N{LATIN}", "\\e", "\\a", "\\cA", "\\u0041", "\\0101",
            // possessive quantifiers
            "a*+", "a++", "a?+", "a{2}+",
            // `{` that is not a bounded quantifier (Java errors; RE2 takes it literally)
            "a{", "a{x}", "a{,3}", "a{5,2}",
            // class features with different meaning
            "[a-z&&[^b]]", "[[:alpha:]]", "[a[b]]", "[]", "[^]", "[\\b]",
            // property names RE2 does not know / Java POSIX and script forms
            "\\p{Alpha}", "\\p{IsLatin}", "\\p{javaLowerCase}", "\\pL",
            // unterminated
            "[abc", "\\", "\\x{41", "\\p{L",
        )) {
            assertThat(Re2Safety.isSafe(p)).`as`("should reject: %s", p).isFalse()
        }
    }
}
