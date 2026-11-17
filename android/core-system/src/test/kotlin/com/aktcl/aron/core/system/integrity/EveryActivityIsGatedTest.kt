package com.aktcl.aron.core.system.integrity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No deep link, exported activity or secondary screen may reach UI without the Developer-options gate
 * (D-OWNER-MDM-02 iv). Scans EVERY module's main sources (apps, features, cores), ignores comments and strings, and
 * catches a custom base activity: any class whose supertype name ends in "Activity" must call `setGatedContent`, and
 * `setContent` / `setContentView` / `ComposeView` may not be used directly outside the gate itself.
 */
class EveryActivityIsGatedTest {
    private val root = File(System.getProperty("aron.androidRoot")!!)

    // Modules that never ship in an APK (test helpers, screenshot and benchmark harnesses) are outside the rule.
    private val notShipped = setOf("core-ui-testing", "ui-screenshots", "benchmark")

    private fun sources() = root.walkTopDown().onEnter { it.name != "build" && it.name != "test" && it.name != "androidTest" && it.name !in notShipped }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }

    /** Source text without comments and string literals. */
    internal fun code(f: File): String = strip(f.readText())

    private fun isGateItself(f: File) = f.path.endsWith("integrity/IntegrityGateUi.kt")

    @Test fun noRawSetContentSetContentViewOrComposeViewAnywhere() {
        val raw = Regex("""(^|[^A-Za-z_])(setContent|setContentView)\s*[({]|\bComposeView\s*\(""")
        val offenders = sources().filter { !isGateItself(it) && raw.containsMatchIn(code(it)) }.map { it.relativeTo(root).path }.toList()
        assertEquals("these files build UI outside the gate: $offenders", emptyList<String>(), offenders)
    }

    @Test fun everyActivitySubclassUsesTheGate() {
        val activityClass = Regex("""class\s+\w+\s*(\([^)]*\))?\s*:\s*[\w.]*Activity\s*\(""")
        val acts = sources().filter { activityClass.containsMatchIn(code(it)) }.toList()
        assertTrue("found no activities, the scan is broken", acts.size >= 4)
        val missing = acts.filter { "setGatedContent" !in code(it) }.map { it.relativeTo(root).path }
        assertEquals("these activities never call setGatedContent: $missing", emptyList<String>(), missing)
    }

    @Test fun aCustomBaseActivityWouldBeCaught() {
        val activityClass = Regex("""class\s+\w+\s*(\([^)]*\))?\s*:\s*[\w.]*Activity\s*\(""")
        assertTrue(activityClass.containsMatchIn("abstract class Base : ComponentActivity() {}"))
        assertTrue(activityClass.containsMatchIn("class A : BaseActivity() {}"))
        assertTrue(activityClass.containsMatchIn("class B(val x: Int) : AppCompatActivity() {}"))
    }

    @Test fun aCommentOrStringMentioningSetContentIsNotAViolationButCodeIs() {
        val raw = Regex("""(^|[^A-Za-z_])(setContent|setContentView)\s*[({]""")
        assertTrue(!raw.containsMatchIn(strip("// setContent { }\n/* setContent { } */\nval s = \"setContent {\"")))
        assertTrue(raw.containsMatchIn(strip("fun f() { setContent { } }")))
    }

    companion object {
        /** Removes // and block comments and the contents of string literals, keeping line structure. */
        fun strip(src: String): String {
            val sb = StringBuilder(); var i = 0
            while (i < src.length) {
                val c = src[i]; val n = src.getOrNull(i + 1)
                when {
                    c == '/' && n == '/' -> { while (i < src.length && src[i] != '\n') i++ }
                    c == '/' && n == '*' -> { i += 2; while (i < src.length && !(src[i] == '*' && src.getOrNull(i + 1) == '/')) { if (src[i] == '\n') sb.append('\n'); i++ }; i += 2 }
                    c == '"' && src.startsWith("\"\"\"", i) -> { i += 3; while (i < src.length && !src.startsWith("\"\"\"", i)) { if (src[i] == '\n') sb.append('\n'); i++ }; i += 3; sb.append("\"\"") }
                    c == '"' -> { i++; while (i < src.length && src[i] != '"') { if (src[i] == '\\') i++; i++ }; i++; sb.append("\"\"") }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }
    }
}
