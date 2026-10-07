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
        Rule("alarm", Regex("""\bAlarmManager\b"""), allowedIn = listOf("dpc/src/main/kotlin/com/aktcl/aron/dpc/blocking/")),
        Rule("timer", Regex("""\bjava\.util\.Timer\b|\bTimer\s*\(|\bTimerTask\b""")),
        Rule("scheduled executor", Regex("""scheduleAtFixedRate|scheduleWithFixedDelay|ScheduledExecutorService|newScheduledThreadPool""")),
        Rule("foreground service", Regex("""\bstartForeground\s*\(|FOREGROUND_SERVICE|foregroundServiceType"""), allowedIn = listOf("core-printing/")),
    )

    private fun sources(): List<File> = androidRoot.listFiles().orEmpty().filter { File(it, "src/main").isDirectory }
        .flatMap { m -> File(m, "src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.name == "AndroidManifest.xml") }.toList() }

    private fun rel(f: File) = f.relativeTo(androidRoot).invariantSeparatorsPath

    fun violations(files: List<Pair<String, String>>): List<String> {
        val out = ArrayList<String>()
        for ((path, text) in files) {
            val code = text.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
            for (r in rules) if (r.pattern.containsMatchIn(code) && r.allowedIn.none { path.startsWith(it) }) out += "$path: ${r.name}"
            Regex("""PeriodicWorkRequestBuilder<[^>]+>\(\s*(\d+)L?\s*,\s*TimeUnit\.(SECONDS|MINUTES)""").findAll(code).forEach { m ->
                val minutes = if (m.groupValues[2] == "SECONDS") m.groupValues[1].toLong() / 60 else m.groupValues[1].toLong()
                if (minutes < 15) out += "$path: periodic work under 15 min"
            }
            if (!path.startsWith("core-printing/")) {
                Regex("""while\s*\(\s*(true|isActive)\s*\)\s*\{""").findAll(code).forEach { m ->
                    val body = block(code, m.range.last)
                    if (Regex("""\bdelay\s*\(|\bThread\.sleep\s*\(""").containsMatchIn(body)) out += "$path: polling loop"
                }
            }
        }
        return out
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
        )
        assertEquals(emptyList<String>(), violations(fine))
    }
}
