package com.aktcl.aron.core.system.integrity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No deep link, exported activity or secondary screen may reach UI without the Developer-options gate (D-OWNER-MDM-02 iv).
 * Scans EVERY shipped module's main sources, ignoring comments and strings. Every class with an Activity supertype (in any
 * position, directly or through a custom base) must call `setGatedContent` INSIDE its `onCreate` (not just mention it); a
 * renaming import of the gate, `addContentView`, `setContentView`, `setContent` and `ComposeView` are refused outside the gate;
 * and each of the three Application classes installs the gate in `onCreate`.
 */
class EveryActivityIsGatedTest {
    private val root = File(System.getProperty("aron.androidRoot")!!)

    // Modules that never ship in an APK (test helpers, screenshot and benchmark harnesses) are outside the rule.
    private val notShipped = setOf("core-ui-testing", "ui-screenshots", "benchmark")

    private fun sources() = root.walkTopDown().onEnter { it.name != "build" && it.name != "test" && it.name != "androidTest" && it.name !in notShipped }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }

    internal fun code(f: File): String = strip(f.readText())

    private fun isGateItself(f: File) = f.path.endsWith("integrity/IntegrityGateUi.kt")

    @Test fun noRawContentCallsAnywhere() {
        val offenders = sources().filter { !isGateItself(it) && RAW.containsMatchIn(code(it)) }.map { it.relativeTo(root).path }.toList()
        assertEquals("these files build UI outside the gate: $offenders", emptyList<String>(), offenders)
    }

    @Test fun noRenamingImportOfTheGate() {
        val offenders = sources().filter { Regex("""import\s+[\w.]*setGatedContent\s+as\s+""").containsMatchIn(code(it)) || Regex("""import\s+[\w.]*integrity\.\*""").containsMatchIn(code(it)) && false }.map { it.relativeTo(root).path }.toList()
        assertEquals("import aliases hide the gate from this scan: $offenders", emptyList<String>(), offenders)
    }

    @Test fun everyActivitySubclassCallsTheGateInsideOnCreate() {
        val acts = sources().filter { ACTIVITY_CLASS.containsMatchIn(code(it)) }.toList()
        assertTrue("found no activities, the scan is broken", acts.size >= 4)
        val bad = acts.filter { !callsGateInOnCreate(code(it)) }.map { it.relativeTo(root).path }
        assertEquals("these activities do not call setGatedContent inside onCreate: $bad", emptyList<String>(), bad)
    }

    /** Review: the lab grant is judged for this phone and this environment, so the uuid and the API host must be known before the first gate check after a cold start. */
    @Test fun eachApplicationSetsTheDeviceUuidAndTheApiHostBeforeItInstallsTheGate() {
        for (a in listOf("sr" to "AronSRApplication", "amo" to "AronAMOApplication", "tso" to "AronTSOApplication")) {
            val src = code(File(root, "app-${a.first}/src/main/kotlin/com/aktcl/aron/${a.first}/${a.second}.kt"))
            val body = functionBody(src, "override fun onCreate") ?: error("${a.second} has no onCreate")
            val install = body.indexOf("IntegrityGates.install")
            assertTrue("${a.second} sets IntegrityGates.deviceUuid before install", body.indexOf("IntegrityGates.deviceUuid") in 0 until install)
            assertTrue("${a.second} sets IntegrityGates.apiHost before install", body.indexOf("IntegrityGates.apiHost") in 0 until install)
        }
    }

    @Test fun eachApplicationInstallsTheGateInOnCreate() {
        for (a in listOf("sr" to "AronSRApplication", "amo" to "AronAMOApplication", "tso" to "AronTSOApplication")) {
            val src = code(File(root, "app-${a.first}/src/main/kotlin/com/aktcl/aron/${a.first}/${a.second}.kt"))
            val body = functionBody(src, "override fun onCreate") ?: error("${a.second} has no onCreate")
            assertTrue("${a.second}.onCreate must install the gate (IntegrityGates.install)", "IntegrityGates.install" in body)
        }
    }

    // ---- the scan's own tests

    @Test fun theScanRecognisesActivityClassesInAnyPosition() {
        for (s in listOf(
            "abstract class Base : ComponentActivity() {}", "class A : BaseActivity() {}", "class B(val x: Int) : AppCompatActivity() {}",
            "class C : Listener, ComponentActivity() {}", "class D : Foo(), Bar by x, FragmentActivity(1) {}",
        )) assertTrue(s, ACTIVITY_CLASS.containsMatchIn(s))
        assertFalse(ACTIVITY_CLASS.containsMatchIn("class E : ViewModel() {}"))
    }

    @Test fun onlyACallInsideOnCreateCounts() {
        assertTrue(callsGateInOnCreate("class A : ComponentActivity() { override fun onCreate(b: Bundle?) { super.onCreate(b); setGatedContent { } } }"))
        assertFalse("a mention elsewhere is not enough", callsGateInOnCreate("class A : ComponentActivity() { fun helper() { setGatedContent { } } override fun onCreate(b: Bundle?) { super.onCreate(b) } }"))
        assertFalse("a comment is not a call", callsGateInOnCreate("class A : ComponentActivity() { override fun onCreate(b: Bundle?) { /* setGatedContent { } */ super.onCreate(b) } }"))
        assertFalse("no onCreate at all", callsGateInOnCreate("class A : ComponentActivity() { }"))
    }

    @Test fun rawCallsAreCaughtIncludingAddContentView() {
        for (s in listOf("setContent { }", "setContentView(R.layout.x)", "addContentView(v, lp)", "val v = ComposeView(this)")) assertTrue(s, RAW.containsMatchIn(strip(s)))
        assertFalse(RAW.containsMatchIn(strip("// setContent { }\n/* addContentView(v) */\nval s = \"setContent {\"")))
    }

    companion object {
        private val RAW = Regex("""(^|[^A-Za-z_])(setContent|setContentView|addContentView)\s*[({]|\bComposeView\s*\(""")
        // A supertype list entry that is a call to a constructor whose name ends in Activity, in ANY position of the list.
        private val ACTIVITY_CLASS = Regex("""class\s+\w+\s*(\([^)]*\))?\s*:\s*([^{]*?[,\s:])?[\w.]*Activity\s*\(""")

        fun callsGateInOnCreate(src: String): Boolean = functionBody(strip(src), "override fun onCreate")?.contains("setGatedContent") == true

        /** The text between the braces of the first function that starts with [signature]; null when there is none. */
        fun functionBody(src: String, signature: String): String? {
            val start = src.indexOf(signature).takeIf { it >= 0 } ?: return null
            val open = src.indexOf('{', start).takeIf { it >= 0 } ?: return null
            var depth = 0
            for (i in open until src.length) {
                if (src[i] == '{') depth++ else if (src[i] == '}') { depth--; if (depth == 0) return src.substring(open + 1, i) }
            }
            return null
        }

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
