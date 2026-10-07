package com.aktcl.aron.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-SYS-011 / F-SYS-046 lint (docs/24 s4.7, s5.4, docs/04 battery budget): no foreground service outside printing, no alarm
 * except the device owner's daily hard-end alarm, no repeating alarm, no timer or scheduled executor, no periodic work under
 * 15 minutes, and no `delay` inside an endless loop (polling) outside the printer's paced write. Scans every Android
 * module's main sources and manifests.
 */
class NoPollingLintTest {
    private val androidRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "core-sync").isDirectory && File(it, "app-sr").isDirectory }

    private data class Rule(val name: String, val pattern: Regex, val allowedIn: List<String> = emptyList())

    private val rules = listOf(
        Rule("repeating alarm", Regex("""\bset(Inexact)?Repeating\s*\(""")),
        Rule("exact-alarm permission", Regex("""(SCHEDULE|USE)_EXACT_ALARM""")),
        Rule("alarm", Regex("""\bAlarmManager\b|\bALARM_SERVICE\b"""), allowedIn = listOf("dpc/src/main/kotlin/com/aktcl/aron/dpc/blocking/")),
        Rule("timer", Regex("""\bjava\.util\.Timer\b|\bTimer\s*\(|\bTimerTask\b|\bticker\s*\(|\bfixedRateTimer\s*\(|\btimer\s*\(|\bCountDownTimer\b""")),
        Rule("scheduled executor", Regex("""scheduleAtFixedRate|scheduleWithFixedDelay|ScheduledExecutorService|newScheduledThreadPool""")),
        Rule("delayed handler post", Regex("""\bpostDelayed\s*\(|\bsendMessageDelayed\s*\(""")),
        Rule(
            "foreground service",
            Regex("""\bstartForeground\s*\(|\bstartForegroundService\s*\(|\bsetForeground(Async)?\s*\(|FOREGROUND_SERVICE|foregroundServiceType"""),
            allowedIn = listOf("core-printing/"),
        ),
    )

    private fun sources(): List<File> = androidRoot.listFiles().orEmpty().filter { File(it, "src/main").isDirectory }
        .flatMap { m -> File(m, "src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.name == "AndroidManifest.xml") }.toList() }

    private fun rel(f: File) = f.relativeTo(androidRoot).invariantSeparatorsPath

    fun violations(files: List<Pair<String, String>>): List<String> {
        val out = ArrayList<String>()
        for ((path, text) in files) {
            val code = if (path.endsWith(".kt")) stripKotlin(text) else text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            for (r in rules) if (r.pattern.containsMatchIn(code) && r.allowedIn.none { path.startsWith(it) }) out += "$path: ${r.name}"
            // A periodic request must say its interval as a literal of at least 15 minutes, so a reviewer can see it.
            Regex("""PeriodicWorkRequestBuilder<[^>]*>\s*\(|PeriodicWorkRequest\.Builder\s*\(""").findAll(code).forEach { m ->
                val args = code.substring(m.range.last + 1).take(160)
                if (!periodicAtLeast15(args)) out += "$path: periodic work not a literal of at least 15 min"
            }
            if (!path.startsWith("core-printing/")) {
                for (open in loopBodies(code)) {
                    val body = block(code, open)
                    if (Regex("""\bdelay\s*\(|\bThread\.sleep\s*\(""").containsMatchIn(body)) { out += "$path: polling loop"; break }
                }
            }
        }
        return out
    }

    private fun periodicAtLeast15(args: String): Boolean {
        Regex("""^\s*(?:[A-Za-z_][\w.]*::class\.java\s*,\s*)?(\d+)L?\s*,\s*TimeUnit\.(MINUTES|HOURS|DAYS)""").find(args)?.let { m ->
            val n = m.groupValues[1].toLong()
            return if (m.groupValues[2] == "MINUTES") n >= 15 else n >= 1
        }
        Regex("""^\s*(?:[A-Za-z_][\w.]*::class\.java\s*,\s*)?Duration\.of(Minutes|Hours|Days)\s*\(\s*(\d+)L?\s*\)""").find(args)?.let { m ->
            val n = m.groupValues[2].toLong()
            return if (m.groupValues[1] == "Minutes") n >= 15 else n >= 1
        }
        return false
    }

    /** Offsets of the `{` that opens each loop body: `while (...) {`, `for (...) {`, `repeat(...) {` (balanced parentheses), `do {`. */
    private fun loopBodies(code: String): List<Int> {
        val out = ArrayList<Int>()
        Regex("""\b(while|for|repeat)\s*\(""").findAll(code).forEach { m ->
            var depth = 0
            var i = m.range.last
            while (i < code.length) {
                when (code[i]) { '(' -> depth++; ')' -> if (--depth == 0) break }
                i++
            }
            var j = i + 1
            while (j < code.length && code[j].isWhitespace()) j++
            if (j < code.length && code[j] == '{') out += j
        }
        Regex("""\bdo\s*\{""").findAll(code).forEach { out += it.range.last }
        return out
    }

    /**
     * Kotlin source with comments, string and char literals blanked out, read left to right like the compiler does, so a
     * block-comment opener inside a string or a `'"'` cannot hide code; the code inside `${...}` templates is kept.
     */
    internal fun stripKotlin(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        fun template(start: Int): Int { // text[start] == '{' after '$'; copies the expression, returns the index after '}'
            var depth = 0
            var k = start
            while (k < text.length) {
                val c = text[k]
                if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) { out.append(' '); return k + 1 } }
                if (depth > 0 && k > start) out.append(c)
                k++
            }
            return k
        }
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> { while (i < text.length && text[i] != '\n') i++ }
                text.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < text.length) {
                        if (text.startsWith("/*", i)) { depth++; i += 2 } else if (text.startsWith("*/", i)) { depth--; i += 2; if (depth == 0) break } else i++
                    }
                    out.append(' ')
                }
                text.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) {
                        if (text.startsWith("\${", i)) i = template(i + 1) else i++
                    }
                    i += 3
                    out.append("\"\"")
                }
                c == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') {
                        when {
                            text[i] == '\\' -> i += 2
                            text.startsWith("\${", i) -> i = template(i + 1)
                            else -> i++
                        }
                    }
                    i++
                    out.append("\"\"")
                }
                c == '\'' -> {
                    i++
                    while (i < text.length && text[i] != '\'' && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
                    i++
                    out.append("' '")
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** The text of the brace block that opens at [open]. */
    private fun block(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(open, i + 1)
            }
        }
        return code.substring(open)
    }

    @Test fun theAppsHaveNoForegroundServiceAlarmTimerOrPollingLoop() {
        val files = sources()
        assertTrue("scanned too little: ${files.size}", files.size > 50)
        assertEquals(emptyList<String>(), violations(files.map { rel(it) to it.readText() }))
    }

    @Test fun theLintCatchesEachForbiddenPattern() {
        val bad = listOf(
            "feature-x/src/main/A.kt" to "val t = java.util.Timer()",
            "feature-x/src/main/B.kt" to "am.setRepeating(0, 0, 60_000, pi)",
            "feature-x/src/main/C.kt" to "fun f() { startForeground(1, n) }",
            "feature-x/src/main/D.kt" to "PeriodicWorkRequestBuilder<W>(5, TimeUnit.MINUTES)",
            "feature-x/src/main/E.kt" to "scope.launch { while (isActive) { sync(); delay(30_000) } }",
            "feature-x/src/main/AndroidManifest.xml" to "<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" />",
            "feature-x/src/main/F.kt" to "val am: AlarmManager = x",
        )
        assertEquals(bad.size, violations(bad).size)
        val fine = listOf(
            "core-printing/src/main/P.kt" to "while (true) { if (ok) break; delay(5) }",
            "dpc/src/main/kotlin/com/aktcl/aron/dpc/blocking/H.kt" to "val am: AlarmManager = x",
            "core-sync/src/main/S.kt" to "PeriodicWorkRequestBuilder<W>(15, TimeUnit.MINUTES); while (true) { i++ ; if (i > 3) break }",
            "feature-y/src/main/T.kt" to "/* never use AlarmManager or Timer() */ val s = \"startForeground( in a string\" // postDelayed in a comment",
        )
        assertEquals(emptyList<String>(), violations(fine))
    }
}
