package com.aktcl.aron.core.printing.check

import com.aktcl.aron.core.printing.BundleTemplate
import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.TemplateSet
import com.aktcl.aron.core.printing.TestFonts
import com.aktcl.aron.core.printing.raster.GlyphRasterizer
import com.aktcl.aron.core.printing.raster.MonoBitmap
import com.aktcl.aron.core.printing.render.PrintDocument
import com.aktcl.aron.core.printing.render.TicketRenderer
import com.aktcl.aron.core.printing.template.EmbeddedTemplates
import com.aktcl.aron.core.printing.template.PrintTemplate
import com.aktcl.aron.core.printing.text.Shaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.io.ByteArrayInputStream

/** Independent checker for N-018: each test fails on commit 9aea69c and documents one defect. */
class N018CheckerTest {
    private fun oracle(text: String): List<Triple<Int, Int, Int>> {
        System.setProperty("java.awt.headless", "true")
        val otf = TestFonts.regular
        val awt = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(TestFonts.regularBytes)).deriveFont(otf.unitsPerEm.toFloat())
            .deriveFont(mapOf(TextAttribute.KERNING to TextAttribute.KERNING_ON, TextAttribute.LIGATURES to TextAttribute.LIGATURES_ON))
        val chars = text.toCharArray()
        val gv = awt.layoutGlyphVector(FontRenderContext(null, false, false), chars, 0, chars.size, Font.LAYOUT_LEFT_TO_RIGHT)
        return (0 until gv.numGlyphs).mapNotNull { i ->
            val code = gv.getGlyphCode(i)
            if (code == 0xFFFF || code == 0xFFFE) null else gv.getGlyphPosition(i).let { p -> Triple(code, Math.round(p.x).toInt(), -Math.round(p.y).toInt()) }
        }
    }

    private fun shaped(text: String) = Shaper(TestFonts.regular).shape(text).let { r -> r.glyphs.indices.map { Triple(r.glyphs[it], r.x[it], r.y[it]) } }

    /** Independent vowel + vowel sign (a common legacy-conversion typo, "অা" for "আ"): HarfBuzz adds a dotted circle. */
    @Test fun independentVowelPlusSignMatchesHarfBuzz() {
        for (t in listOf("অা", "ঋৃ", "ঌৢ", "অাম")) assertEquals(t, oracle(t), shaped(t))
    }

    /** Latin with a combining mark (NFD input): HarfBuzz composes e + U+0301 to é. */
    @Test fun latinCombiningMarkMatchesHarfBuzz() {
        for (t in listOf("Café", "é")) assertEquals(t, oracle(t), shaped(t))
    }

    /**
     * Khanda ta followed by a vowel sign. (The checker's first input "Aৎৈ" differed only because the JVM's script
     * itemisation puts ৎ into the Latin run after "A"; HarfBuzz itself shapes ৎ as a consonant, as below.)
     */
    @Test fun khandaTaPlusSignMatchesHarfBuzz() {
        for (t in listOf("ৎৈ", "কৎৈ", "ৎি", "ৎা", "ৎ্ক", "ৎঁ")) assertEquals(t, oracle(t), shaped(t))
    }

    /** Reph + vowel sign + chandrabindu reaches above the line strip; the top rows are silently cut. */
    @Test fun tallStacksAreNotClippedByTheLineStrip() {
        val text = "র্কৌঁ"
        val t = PrintTemplate.parse("""{"schema":1,"blocks":[{"type":"text","value":"$text","bold":true}]}""")
        val printed = TicketRenderer(Fixtures.fonts).render(t, PrintDocument(emptyMap())) { "" }
        val tall = MonoBitmap(384, 200)
        GlyphRasterizer(Fixtures.fonts.bold, 22).draw(tall, Shaper(Fixtures.fonts.bold).shape(text), 4, 100)
        assertEquals("ink dots lost to clipping", tall.blackDots(), printed.blackDots())
    }

    /** A pair whose right value is wide: the left label is drawn on top of it. */
    @Test fun pairLeftAndRightDoNotOverlap() {
        val r = TicketRenderer(Fixtures.fonts)
        val t = PrintTemplate.parse("""{"schema":1,"blocks":[{"type":"pair","left":"{l}","right":"{r}"}]}""")
        val right = "রহিম স্টোর্স অ্যান্ড ব্রাদার্স জেনারেল মার্চেন্টস"
        val both = r.render(t, PrintDocument(mapOf("l" to "আউটলেট রহিম স্টোর্স", "r" to right))) { "" }
        val rightOnly = r.render(t, PrintDocument(mapOf("l" to "   ", "r" to right))) { "" }
        // Right text sits on the last line of the pair; compare that line only (bottom-aligned, same strip height).
        val off = both.height - rightOnly.height
        var minX = Int.MAX_VALUE
        for (y in 0 until rightOnly.height) for (x in 0 until 384) if (rightOnly[x, y]) minX = minOf(minX, x)
        var overlap = 0
        for (y in 0 until rightOnly.height) for (x in minX until 384) if (both[x, y + off] && !rightOnly[x, y]) overlap++
        assertEquals("left-label dots inside the right value's area", 0, overlap)
    }

    /** A schema-valid 5 % column at size 48 wraps at width 48 (the coerceAtLeast(size) floor); a wider conjunct is split after the virama. */
    @Test fun narrowColumnNeverSplitsAConjunct() {
        for (word in listOf("ক্ষ্ম", "ঙ্ক্ষ", "ন্ত্র", "স্ত্রী")) {
            val parts = TicketRenderer(Fixtures.fonts).wrap(word, false, 48, 48)
            assertTrue("$word split: $parts", parts.none { it.startsWith("্") || it.endsWith("্") })
        }
    }

    /** Over-long unbroken word: wrap is O(n^4); 200 chars take ~3.6 s on the JVM (40 s for 400). */
    @Test(timeout = 1500) fun wrapOfALongUnbrokenWordIsFast() {
        TicketRenderer(Fixtures.fonts).wrap("কর্মকর্তা".repeat(23).take(200), false, 22, 180)
    }

    /** `{@Print_company}` / `{@a.b}` pass label validation (lower-case-only regex) but the renderer resolves them to "". */
    @Test fun unknownLabelSpellingsAreRefused() {
        val json = EmbeddedTemplates.forKind("void_slip").replace("{@print_company}", "{@Print_company}")
        val set = TemplateSet(listOf(BundleTemplate("void_slip", 9, 32, json)), Fixtures.bnLabels)
        assertFalse("template with an unresolvable label accepted", set.get("void_slip").fromBundle)
    }
}
