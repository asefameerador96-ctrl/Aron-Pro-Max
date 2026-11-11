package com.aktcl.aron.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D-AMO-SHELL-12: a STRUCTURAL guard (it reads `MainActivity.kt`; it is not a behavioural Activity test) against the regression of
 * 2fad3282, where a composable-local `resumeTick` shadowed the Activity's counter and the day-bundle effect stopped re-running on resume
 * (the 30-minute delta check and the full bundle of a new Dhaka day, D-AMO-BUNDLE-01). The Activity owns the one `resumeTick`, `onResume`
 * bumps it, the day-bundle effect is keyed on it, and the call-targets card effect uses its own `targetsRetryTick`.
 */
class MainActivityEffectKeysTest {
    private val source: String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "app-amo/src/main/kotlin/com/aktcl/aron/amo/MainActivity.kt") }.first { it.isFile }.readText()

    private val code = source.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")

    @Test fun theActivityOwnsTheOneResumeTickAndOnResumeBumpsIt() {
        assertEquals("exactly one declaration of resumeTick (a second one would shadow it)", 1, Regex("""\bva[rl]\s+resumeTick\b""").findAll(code).count())
        val onResume = code.substringAfter("override fun onResume()").substringBefore("\n    }\n")
        assertTrue("onResume must bump resumeTick", Regex("""\bresumeTick\+\+""").containsMatchIn(onResume))
    }

    @Test fun theDayBundleEffectIsKeyedOnTheActivitysResumeTick() {
        assertTrue(Regex("""LaunchedEffect\(\s*day\s*,\s*resumeTick\s*\)\s*\{\s*val d = day[^\n]*\n\s*val outcome = d\.downloadBundle""").containsMatchIn(code))
    }

    @Test fun theCardEffectUsesItsOwnRetryTickAndNeverResumeTick() {
        val cardEffect = Regex("""LaunchedEffect\([^)]*\)\s*\{\s*val d = day[^\n]*\n\s*val r = runCatching \{ d\.callTargets""").find(code)?.value
        assertTrue("the call-targets effect must exist", cardEffect != null)
        assertTrue(cardEffect!!.contains("targetsRetryTick"))
        assertFalse("the card effect must not key on the bundle's resumeTick", Regex("""\bresumeTick\b""").containsMatchIn(cardEffect))
        assertEquals(1, Regex("""\bva[rl]\s+targetsRetryTick\b""").findAll(code).count())
    }
}
