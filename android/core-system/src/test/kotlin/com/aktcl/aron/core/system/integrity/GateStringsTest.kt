package com.aktcl.aron.core.system.integrity

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate steps must tell a rep how to reach USB debugging when the Developer options menu is hidden, in both languages. */
class GateStringsTest {
    private val res = File(System.getProperty("user.dir")!!, "src/main/res")
    private fun steps(dir: String) = Regex("""<string name="integrity_gate_steps">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File(res, "$dir/strings.xml").readText())!!.groupValues[1]

    @Test fun englishStepsCoverTheHiddenMenuAndSamsung() {
        val s = steps("values")
        assertTrue("Build number" in s && "seven times" in s); assertTrue("Samsung" in s); assertTrue("Wireless debugging" in s)
    }

    @Test fun banglaStepsCoverTheHiddenMenuAndSamsung() {
        val s = steps("values-bn")
        assertTrue("বিল্ড নম্বর" in s && "সাতবার" in s); assertTrue("স্যামসাং" in s); assertTrue("ওয়্যারলেস ডিবাগিং" in s)
    }

    @Test fun everyGateStringExistsInBothLanguages() {
        fun names(d: String) = Regex("""<string name="(integrity_gate_[a-z_]+)">""").findAll(File(res, "$d/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertTrue(names("values") == names("values-bn") && names("values").containsAll(listOf("integrity_gate_flag_adb_wifi", "integrity_gate_sync_now")))
    }
}
