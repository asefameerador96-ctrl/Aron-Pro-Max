package com.aktcl.aron.amo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * D-AMO-CALLS-33 (7): no host test of this module builds its Compose rule with `createEmptyComposeRule(` directly; [hostComposeRule] is the one place (the
 * default rule's Unconfined Recomposer can run a frame on a Room thread, D-AMO-CALLS-32). Comment lines are ignored.
 */
class NoDirectComposeRuleTest {
    @Test fun onlyHostComposeRuleCreatesAnEmptyComposeRule() {
        val dir = File("src/test/kotlin")
        val offenders = dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "HostComposeRule.kt" && it.name != "NoDirectComposeRuleTest.kt" }
            .flatMap { f -> f.readLines().withIndex().filter { (_, l) -> "createEmptyComposeRule(" in l && !l.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }.map { (i, _) -> f.name + ":" + (i + 1) } }.toList()
        assertEquals("use hostComposeRule() instead of createEmptyComposeRule(): $offenders", emptyList<String>(), offenders)
    }
}
