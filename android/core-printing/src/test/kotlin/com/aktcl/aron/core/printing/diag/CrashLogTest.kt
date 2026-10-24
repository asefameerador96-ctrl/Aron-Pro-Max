package com.aktcl.aron.core.printing.diag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The last crash and the last caught print error, as the Printer diagnostics export carries them (owner P0 2026-10-24). */
class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun reset() {
        CrashLog.use(null, "?")
        CrashLog.nowMs = System::currentTimeMillis
    }

    @Test fun nothingRecordedReadsNone() {
        CrashLog.use(tmp.root, "0.2.492+1492")
        val text = CrashLog.exportText(tmp.root)
        assertTrue(text, "Last crash: none" in text)
        assertTrue(text, "Last print error (caught, shown to the seller): none" in text)
    }

    @Test fun aCrashKeepsThreadBuildTimeAndStack() {
        CrashLog.use(tmp.root, "0.2.492+1492")
        CrashLog.nowMs = { 1_793_000_000_000L }
        val e = IllegalStateException("socket closed").apply { initCause(NullPointerException("mSocketIS")) }
        CrashLog.crashed(Thread("DefaultDispatcher-worker-3"), e)
        val text = CrashLog.exportText(tmp.root)
        assertTrue(text, "build: 0.2.492+1492" in text)
        assertTrue(text, "thread: DefaultDispatcher-worker-3" in text)
        assertTrue(text, "time: 2026-10-26T" in text)
        assertTrue(text, "java.lang.IllegalStateException: socket closed" in text)
        assertTrue(text, "Caused by: java.lang.NullPointerException" in text)
        assertTrue(text, "at com.aktcl.aron.core.printing.diag.CrashLogTest" in text)
    }

    @Test fun theNewestOfEachKindWinsAndTheyAreKeptApart() {
        CrashLog.use(tmp.root, "b")
        CrashLog.handled("print", RuntimeException("first"))
        CrashLog.handled("reader", RuntimeException("second"))
        CrashLog.crashed(Thread.currentThread(), Error("boom"))
        val text = CrashLog.exportText(tmp.root)
        assertFalse(text, "first" in text)
        assertTrue(text, "where: reader" in text && "second" in text)
        assertTrue(text, "where: uncaught" in text && "java.lang.Error: boom" in text)
    }

    @Test fun phoneNumbersAndBanglaNeverLeaveThePhone() {
        CrashLog.use(tmp.root, "b")
        CrashLog.handled("print", RuntimeException("outlet রয়েল রেস্টুরেন্ট owner 01712-345678 ০১৭১২৩৪৫৬৭৮"))
        val text = CrashLog.exportText(tmp.root)
        assertFalse(text, "345678" in text)
        assertFalse(text, "রয়েল" in text)
        assertTrue(text, "<phone>" in text && "<bn>" in text)
    }

    @Test fun withoutAFolderNothingIsWrittenAndNothingThrows() {
        CrashLog.use(null, "b")
        CrashLog.handled("print", RuntimeException("x"))
        CrashLog.use(java.io.File(tmp.root, "file-not-dir").apply { writeText("x") }, "b")
        CrashLog.handled("print", RuntimeException("x")) // mkdirs fails on a file: swallowed
        assertTrue("Last crash: none" in CrashLog.exportText(null))
    }

    @Test fun theMainThreadOfAnAnrTrace() {
        val trace = """
            ----- pid 4242 at 2026-10-24 10:51:02 -----
            "Signal Catcher" daemon prio=10 tid=2 Runnable
              at a.b.C(Unknown)

            "main" prio=5 tid=1 Runnable
              | group="main" sCount=0
              at com.aktcl.aron.core.printing.raster.GlyphRasterizer.fill(GlyphRasterizer.kt:91)
              at com.aktcl.aron.core.printing.PaperRenderer.memo(PaperRenderer.kt:60)

            "DefaultDispatcher-worker-1" daemon prio=5 tid=20 Waiting
        """.trimIndent()
        val main = CrashLog.mainThreadOf(trace)!!
        assertTrue(main, main.startsWith("\"main\" prio=5"))
        assertTrue(main, "GlyphRasterizer.fill" in main)
        assertFalse(main, "Signal Catcher" in main || "DefaultDispatcher" in main)
        assertEquals(4, main.lines().size)
        assertNull(CrashLog.mainThreadOf("no threads here"))
    }
}
