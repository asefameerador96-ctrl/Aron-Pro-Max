package com.aktcl.aron.core.system.integrity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only debuggable and benchmark builds are exempt from the gate; the release variant never is (D-OWNER-MDM-02, review item 6). */
class IntegrityGateBuildTest {
    private val root = File(System.getProperty("aron.androidRoot")!!)

    @Test fun releaseIsNeverExemptByTheBuildExemptionRule() {
        assertNull(IntegrityGates.buildExemption(debuggable = false, metaExempt = null))
        assertNull(IntegrityGates.buildExemption(debuggable = false, metaExempt = "false"))
        assertNull(IntegrityGates.buildExemption(debuggable = false, metaExempt = "TRUE")) // exactly "true", nothing looser
        assertNull(IntegrityGates.buildExemption(debuggable = false, metaExempt = ""))
        assertEquals(Exemption.DEBUG_BUILD, IntegrityGates.buildExemption(debuggable = true, metaExempt = null))
        assertEquals(Exemption.BENCHMARK_BUILD, IntegrityGates.buildExemption(debuggable = false, metaExempt = "true"))
    }

    @Test fun theBuildScriptExemptsOnlyTheBenchmarkBuildTypes() {
        val script = File(root.parentFile, "build.gradle.kts").readText()
        assertTrue("the default must be false", Regex("""defaultConfig\s*\{[^}]*manifestPlaceholders\["integrityGateExempt"\]\s*=\s*"false"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(script))
        val puts = Regex("""variant\.manifestPlaceholders\.put\("integrityGateExempt",[^\n]*""").findAll(script).toList()
        assertEquals("exactly one rule sets the exemption", 1, puts.size)
        val rule = puts[0].value
        assertTrue("true only for exactly the benchmark types: $rule", rule.contains("""if (variant.buildType == "benchmark" || variant.buildType == "benchmarkProfile") "true" else "false"""))
        assertTrue("never release", !rule.contains("release"))
        assertEquals("no other place sets it to true", 1, Regex("""integrityGateExempt"\]?\s*[,=]\s*(if[^\n]*)?"true"""").findAll(script).count())
    }

    @Test fun everyAppManifestCarriesTheMetaDataFromThePlaceholderOnly() {
        for (a in listOf("app-sr", "app-amo", "app-tso")) {
            val m = File(root, "$a/src/main/AndroidManifest.xml").readText()
            assertTrue("$a", m.contains("""android:name="com.aktcl.aron.integrity_gate_exempt" android:value="${'$'}{integrityGateExempt}""""))
            for (variant in listOf("release", "benchmark", "benchmarkProfile", "debug")) {
                val v = File(root, "$a/src/$variant/AndroidManifest.xml")
                assertTrue("$a/$variant must not set the meta-data itself", !v.isFile || "integrity_gate_exempt" !in v.readText())
            }
        }
    }

    @Test fun theMergedManifestsSayReleaseIsNeverExemptAndBenchmarkIs() {
        for (a in listOf("app-sr", "app-amo", "app-tso")) {
            fun value(variant: String): String {
                val f = File(root, "$a/build").walkTopDown().first { it.name == "AndroidManifest.xml" && "merged_manifest/$variant/" in it.path.replace('\\', '/') }
                return Regex("""integrity_gate_exempt"\s+android:value="([^"]*)"""").find(f.readText())!!.groupValues[1]
            }
            assertEquals("$a release", "false", value("release"))
            assertEquals("$a debug", "false", value("debug")) // debug is exempt by FLAG_DEBUGGABLE, not by this
            assertEquals("$a benchmark", "true", value("benchmark"))
        }
    }
}
