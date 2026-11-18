package com.aktcl.aron.feature.outlet

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** A hard mock-location refusal must say what to do (turn off, uninstall, open the outlet again, tell the TSO), in both languages. */
class HardRefusalTextTest {
    private val res = File(System.getProperty("user.dir")!!, "src/main/res")
    private fun text(dir: String) = Regex("""<string name="out_blocked_mock">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(File(res, "$dir/strings.xml").readText())!!.groupValues[1]

    @Test fun englishSaysWhatToDo() {
        val s = text("values")
        assertTrue("fake-GPS" in s && "uninstall" in s && "open the outlet again" in s && "TSO" in s)
    }

    @Test fun banglaSaysWhatToDo() {
        val s = text("values-bn")
        assertTrue("নকল-জিপিএস" in s && "আনইনস্টল" in s && "আবার খুলুন" in s && "টিএসও" in s)
    }
}
