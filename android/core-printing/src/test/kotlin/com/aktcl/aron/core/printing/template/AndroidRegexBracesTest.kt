package com.aktcl.aron.core.printing.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's java.util.regex is ICU, not the JDK engine: a brace outside a character class that is not part of a
 * `{n}`, `{n,}` or `{n,m}` quantifier is a syntax error there (U_REGEX_RULE_SYNTAX), while the desktop JVM, and so
 * every JVM and Robolectric test, reads it as a literal. `PrintTemplate.TOKEN` ended in such a `}` and every print
 * failed on the phone ("The app could not prepare the paper", owner 2026-10-24). This test applies ICU's rule to
 * every regex literal of the app, so the next one fails here instead of on a phone.
 */
class AndroidRegexBracesTest {
    /** The first brace ICU would reject, as an index into [pattern]; null when the pattern is safe. */
    private fun badBrace(pattern: String): Int? {
        var i = 0
        var classDepth = 0
        var prevIsAtom = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val n = pattern.getOrNull(i + 1)
                    i = when {
                        n == 'Q' -> pattern.indexOf("\\E", i + 2).let { if (it < 0) pattern.length else it + 2 } // \Q...\E quotes everything
                        n != null && n in "pPxN" && pattern.getOrNull(i + 2) == '{' -> pattern.indexOf('}', i + 3).let { if (it < 0) pattern.length else it + 1 } // \p{L}, \x{41}
                        else -> i + 2
                    }
                    prevIsAtom = classDepth == 0
                    continue
                }
                classDepth > 0 -> when (c) {
                    '[' -> classDepth++ // ICU nests classes: [a[b]]
                    ']' -> { classDepth--; if (classDepth == 0) prevIsAtom = true }
                }
                c == '[' -> {
                    classDepth = 1
                    if (pattern.getOrNull(i + 1) == '^') i++
                    if (pattern.getOrNull(i + 1) == ']') i++ // a leading ] is a literal
                }
                c == '{' -> {
                    val m = QUANTIFIER.matchAt(pattern, i)
                    if (m == null || !prevIsAtom) return i
                    i += m.value.length
                    if (i < pattern.length && (pattern[i] == '?' || pattern[i] == '+')) i++ // lazy or possessive
                    prevIsAtom = false
                    continue
                }
                c == '}' -> return i
                // a group start, an alternation, an anchor or a quantifier leaves nothing a {n} could repeat
                c in "(|^*+?" -> prevIsAtom = false
                else -> prevIsAtom = true
            }
            i++
        }
        return null
    }

    @Test fun theRuleAgreesWithIcu() {
        // each verdict checked against ICU 74's uregex_open (Android 14 ships ICU 72) on 2026-10-24
        // ICU rejects these (its error offset points just past the brace)
        assertEquals(19, badBrace("""\{(@?)([^{}]{1,60})}"""))
        assertEquals(1, badBrace("a}"))
        assertEquals(2, badBrace("a*{2}"))
        for (ok in listOf("""\{(@?)([^\{\}]{1,60})\}""", """\{(@?)([^{}]{1,60})\}""", """\{x\}""", "[ঀ-৿]+", """\d{10,}""",
            """^[0-9a-f-]{36}\.u(4242|0)\.json$""", """^/mnt/expand/[0-9a-fA-F-]+/user/0/\Qa{b}\E$""", "x{2}?y{1,3}+", """\p{L}{2}""", """\x{41}""", "[[a]{]", "(?:ab){2}")) {
            assertEquals(ok, null, badBrace(ok))
        }
    }

    @Test fun theTemplateTokenIsAndroidSafeAndStillMatchesFieldsAndLabels() {
        assertEquals(null, badBrace(PrintTemplate.TOKEN.pattern))
        assertEquals(listOf("sr", "@print_route"), PrintTemplate.TOKEN.findAll("{sr} / {@print_route} {}").map { it.groupValues[1] + it.groupValues[2] }.toList())
    }

    @Test fun everyRegexLiteralInTheAppIsAndroidSafe() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile && File(it, "android").isDirectory }
        val sources = (File(root, "android").walk().filter { "/src/main/" in it.path } + File(root, "shared").walk().filter { Regex("/src/[a-zA-Z]*Main/").containsMatchIn(it.path) })
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }.toList()
        assertTrue("found the sources", sources.size > 100)
        val bad = ArrayList<String>()
        var checked = 0
        for (f in sources) {
            for (m in LITERAL.findAll(f.readText())) {
                val raw = m.groups[1]?.value
                val body = raw ?: unescape(m.groups[2]!!.value)
                if (Regex("""\$[{A-Za-z_]""").containsMatchIn(body)) continue // built at run time: covered by its own module's tests
                checked++
                badBrace(body)?.let { bad += "${f.relativeTo(root)}: index $it of $body" }
            }
        }
        assertTrue("checked the literals ($checked)", checked > 30)
        assertTrue("regexes Android (ICU) rejects:\n" + bad.joinToString("\n"), bad.isEmpty())
        assertFalse(bad.any())
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> { append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                    'n' -> append('\n'); 't' -> append('\t'); 'r' -> append('\r'); 'b' -> append('\b')
                    else -> append(n)
                }
                i += 2
            } else { append(c); i++ }
        }
    }

    private companion object {
        val QUANTIFIER = Regex("""\{\d+(,\d*)?\}""")
        val LITERAL = Regex("(?:Regex\\(|toRegex\\(|Pattern\\.compile\\()\\s*(?:\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\")", RegexOption.DOT_MATCHES_ALL)
    }
}
