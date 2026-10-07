package com.aktcl.aron.core.printing.render

import com.aktcl.aron.core.printing.AndroidPrintLabels
import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.doc.MemoPrintLine
import com.aktcl.aron.core.printing.template.PrintTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private val renderer = TicketRenderer(Fixtures.fonts)

    @Test fun labelsExistInBothLanguagesAndInTheAndroidIdMap() {
        assertEquals(Fixtures.en.keys, Fixtures.bn.keys)
        assertEquals(Fixtures.en.keys.filter { it.startsWith("print_") }.toSet(), AndroidPrintLabels.IDS.keys)
        assertTrue(Fixtures.bn.values.none { it.isBlank() })
    }

    @Test fun longTextWrapsInsteadOfBeingCut() {
        val name = "অত্যন্ত দীর্ঘ নামের একটি আউটলেট যার নাম এক লাইনে ধরে না Very Long Outlet Name Limited"
        val lines = renderer.wrap(name, false, 22, 300)
        assertTrue(lines.size >= 3)
        assertEquals(name.split(' ').filter { it.isNotEmpty() }, lines.flatMap { it.split(' ') })
        lines.forEach { assertTrue(renderer.measure(it, false, 22) <= 300) }
        // One word wider than the line breaks between syllables, never inside one.
        val word = "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val parts = renderer.wrap(word, false, 22, 120)
        assertEquals(word, parts.joinToString(""))
        parts.forEach { assertTrue(renderer.measure(it, false, 22) <= 120) }
        val conj = "স্ত্রীক্ষ্মন্দ্রজ্জ্বস্ত্রীক্ষ্মন্দ্রজ্জ্বস্ত্রীক্ষ্ম"
        val cp = renderer.wrap(conj, false, 22, 60)
        assertEquals(conj, cp.joinToString(""))
        cp.drop(1).forEach { assertTrue("starts with a sign: $it", it[0] !in "্ািীুূৃেৈোৌঁংঃ়") }
        cp.forEach { assertTrue("ends with virama: $it", !it.endsWith("্")) }
    }

    /** Text keeps a 4-dot margin; a glyph's ink may overhang its advance by a dot or two, never the paper edge. */
    @Test fun nothingPrintsInTheSideMargins() {
        val long = Fixtures.seededSale.copy(
            outlet = "রহিম স্টোর্স অ্যান্ড ব্রাদার্স জেনারেল মার্চেন্টস (O-10234) মোহাম্মদপুর টাউন হল মার্কেট",
            lines = Fixtures.seededSale.lines + MemoPrintLine("Very Long SKU Name 20s Special Filter King Size", 123456, 987_654_321_000),
        )
        val bmp = Fixtures.renderer().memo(long).bitmap
        for (y in 0 until bmp.height) for (x in listOf(0, 1, 382, 383)) assertTrue("dot at $x,$y", !bmp[x, y])
    }

    @Test fun conditionalBlocks() {
        val t = PrintTemplate.parse("""{"schema":1,"blocks":[{"type":"if","when":"a","blocks":[{"type":"space","dots":10}]},{"type":"if","unless":"a","blocks":[{"type":"space","dots":3}]}]}""")
        assertEquals(10 + 24, renderer.render(t, PrintDocument(emptyMap(), flags = setOf("a"))) { it }.height)
        assertEquals(3 + 24, renderer.render(t, PrintDocument(emptyMap())) { it }.height)
    }

    @Test fun fortyLineMemoRendersFastEnough() {
        val r = Fixtures.renderer()
        r.memo(Fixtures.fortyLines)
        val t0 = System.nanoTime()
        val b = r.memo(Fixtures.fortyLines).bitmap
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("40-line memo took $ms ms on the host", ms < 1500)
        assertTrue(b.height > 40 * 30)
    }
}
