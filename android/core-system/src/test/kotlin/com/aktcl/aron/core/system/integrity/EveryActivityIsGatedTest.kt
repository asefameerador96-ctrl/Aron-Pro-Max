package com.aktcl.aron.core.system.integrity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No deep link, exported activity or secondary screen may reach UI without the Developer-options gate
 * (D-OWNER-MDM-02 iv): every Activity class of the three apps (and of any library that declares one) must call
 * [setGatedContent], and none may call the raw `setContent`.
 */
class EveryActivityIsGatedTest {
    private val root = File(System.getProperty("aron.androidRoot")!!)

    private fun sources() = root.walkTopDown().onEnter { it.name != "build" && it.name != "test" && it.name != "androidTest" }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }

    @Test fun noRawSetContentAnywhereInTheApps() {
        val offenders = sources().filter { f -> f.readLines().any { l -> Regex("""(^|[^A-Za-z])setContent\s*[({]""").containsMatchIn(l) && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") } }
            .filter { "/app-" in it.path }.map { it.relativeTo(root).path }.toList()
        assertEquals("these activities bypass the gate: $offenders", emptyList<String>(), offenders)
    }

    @Test fun everyActivitySubclassUsesTheGate() {
        val acts = sources().filter { f -> Regex("""class\s+\w+\s*(\([^)]*\))?\s*:\s*(ComponentActivity|AppCompatActivity|FragmentActivity|Activity)\s*\(""").containsMatchIn(f.readText()) }
            .toList()
        assertTrue("found no activities, the scan is broken", acts.size >= 4)
        val missing = acts.filter { "setGatedContent" !in it.readText() }.map { it.relativeTo(root).path }
        assertEquals("these activities never call setGatedContent: $missing", emptyList<String>(), missing)
    }
}
