package com.aktcl.aron.core.printing.text

import com.aktcl.aron.core.printing.TestFonts
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.io.ByteArrayInputStream

/**
 * N-018: the in-house shaper equals HarfBuzz (the JVM's text layout engine) glyph for glyph and position for
 * position on the bundled Noto Sans Bengali, over every consonant-matra pair, every two-consonant conjunct,
 * reph, ya/ra/ba-phala, nukta, signs, digits and the memo vocabulary.
 */
class ShaperOracleTest {
    private fun s(vararg cps: Int) = String(cps, 0, cps.size)

    private val consonants = (0x0995..0x09A8) + (0x09AA..0x09B0) + listOf(0x09B2) + (0x09B6..0x09B9) + listOf(0x09DC, 0x09DD, 0x09DF, 0x09CE)
    private val matras = (0x09BE..0x09C4) + listOf(0x09C7, 0x09C8, 0x09CB, 0x09CC, 0x09E2, 0x09E3)
    private val vowels = (0x0985..0x098C) + listOf(0x098F, 0x0990, 0x0993, 0x0994, 0x09E0, 0x09E1)
    private val signs = listOf(0x0981, 0x0982, 0x0983)
    private val H = 0x09CD

    val words = listOf(
        "মেমো", "মোট", "সর্বমোট", "ডিসকাউন্ট", "পরিমাণ", "দাম", "মূল্য", "এসকেইউ", "প্রিন্ট", "এডিট", "ম্যাচ", "লাইটার",
        "সিগারেট", "আউটলেট", "বিক্রয়", "বিক্রেতা", "তারিখ", "নগদ", "বাকি", "পরিশোধ", "ধন্যবাদ", "স্টক", "স্লিপ", "দৈনিক",
        "সারাংশ", "বাতিল", "অনুলিপি", "ছাপা", "ঠিক", "আছে?", "রুট", "ক্ষমা", "জ্ঞান", "উজ্জ্বল", "স্বাক্ষর", "রাষ্ট্র", "কর্মকর্তা",
        "র‍্যাব", "শ্রমিক", "ত্রুটি", "দ্রব্য", "ঈদ", "ঋণ", "কৃষক", "হৃদয়", "শৃঙ্খলা", "ড়", "ঢ়", "য়", "ক়", "আঁকা", "চাঁদ", "দুঃখ",
        "ক্ষ্ম", "ন্ত্র", "স্ত্রী", "ন্দ্র", "ঙ্ক্ষ", "জ্জ্ব", "ৎ", "উৎসব", "সৎ", "০১২৩৪৫৬৭৮৯", "৳৮৪.০০", "১২,৩৪৫.৬৭",
        "কোম্পানি", "গ্রাহক", "পণ্য", "একক", "শলাকা", "পিস", "ডজন", "কার্টন", "প্যাকেট", "রাজশাহী", "চট্টগ্রাম", "সিলেট",
        "ক‌্ষ", "ক্‌ষ", "ক্‍ষ", "র্‍য", "কি", "কে", "কৈ", "কো", "কৌ", "র্কি", "র্কো", "ক্রি", "স্ত্রৈ", "র্ত্স্ন্যি",
        "ক্ি", "িক", "্", "ঁ", "অ্", "ব্ব", "দ্ব", "ম্ব", "ল্ব", "শ্ব", "স্ব", "হ্ব", "ক্য", "হ্য", "র্য", "য্য",
    )

    /** SKU codes, outlet names and English labels as they appear on the memo. */
    private val latin = listOf(
        "FB", "SL Match", "MaxDB-20S_20HL", "Total 1,234.50", "Memo No: SR001-261007-0042", "AVA", "To", "Wa", "LT", "fi", "ffl",
        "Tk 84.00", "(Qty) x Price = Value", "#1234 @ 12/10", "Grand Total", "Discount", "Outlet: Rahim Store", "DUPLICATE",
        "AKTCL", "Thank you!", "মোট 84.00", "FB ৩", "SL Match ১২", "৳ 1,234.50", "মেমো নং: SR001-261007-0042", "(অনুলিপি) DUPLICATE",
        "কিFB", "FBকি", "১২ x ৮৪.০০ = ১,০০৮.০০", "0123456789", "Mohammadpur-12", "VAT 15%", "Yy", "Av", "P.O.", "it's", "\"quoted\"",
    )

    private fun corpus(): List<String> {
        val out = ArrayList<String>(words)
        for (c in consonants) {
            out.add(s(c))
            for (m in matras) out.add(s(c, m))
            for (sg in signs) out.add(s(c, sg))
            out.add(s(c, H))
            out.add(s(0x09B0, H, c))
            out.add(s(0x09B0, H, c, 0x09BF))
            out.add(s(0x09B0, H, c, 0x09C0, 0x0982))
            out.add(s(c, 0x09BC, 0x09BF))
            for (c2 in consonants) {
                out.add(s(c, H, c2))
                out.add(s(c, H, c2, 0x09BF))
            }
            for (c2 in listOf(0x09AF, 0x09B0, 0x09AC, 0x09AE, 0x09B2, 0x09A8)) {
                out.add(s(c, H, c2, 0x09BE))
                out.add(s(c, H, c2, 0x09C7))
                out.add(s(c, H, c2, 0x09C1))
                out.add(s(0x09B0, H, c, H, c2))
                out.add(s(c, H, 0x09A4, H, c2))
                out.add(s(c, H, c2, H, 0x09AF))
            }
        }
        for (v in vowels) { out.add(s(v)); for (sg in signs) out.add(s(v, sg)) }
        out.addAll(latin)
        return out
    }

    private fun oracle(font: Font, text: String): List<Triple<Int, Int, Int>> {
        val chars = text.toCharArray()
        val gv = font.layoutGlyphVector(FontRenderContext(null, false, false), chars, 0, chars.size, Font.LAYOUT_LEFT_TO_RIGHT)
        return (0 until gv.numGlyphs).mapNotNull { i ->
            val code = gv.getGlyphCode(i)
            if (code == 0xFFFF || code == 0xFFFE) null else {
                val p = gv.getGlyphPosition(i)
                Triple(code, Math.round(p.x).toInt(), -Math.round(p.y).toInt())
            }
        }
    }

    private fun check(bytes: ByteArray, otf: OpenTypeFont) {
        System.setProperty("java.awt.headless", "true")
        // Kerning and ligatures on, so the JVM runs HarfBuzz for Latin text too instead of its simple path.
        val awt = Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(bytes)).deriveFont(otf.unitsPerEm.toFloat())
            .deriveFont(mapOf(TextAttribute.KERNING to TextAttribute.KERNING_ON, TextAttribute.LIGATURES to TextAttribute.LIGATURES_ON))
        val shaper = Shaper(otf)
        val failures = ArrayList<String>()
        val texts = corpus()
        for (t in texts) {
            val want = oracle(awt, t)
            val run = shaper.shape(t)
            val got = run.glyphs.indices.map { Triple(run.glyphs[it], run.x[it], run.y[it]) }
            val same = want.size == got.size && want.indices.all { i ->
                want[i].first == got[i].first && Math.abs(want[i].second - got[i].second) <= 1 && Math.abs(want[i].third - got[i].third) <= 1
            }
            if (!same) failures.add("${t.codePoints().toArray().joinToString(" ") { Integer.toHexString(it) }}\n  want $want\n  got  $got")
        }
        if (failures.isNotEmpty()) {
            println("FAILURES ${failures.size}/${texts.size}")
            failures.take(40).forEach { println(it) }
        }
        assertTrue("${failures.size} of ${texts.size} differ from HarfBuzz; first: ${failures.firstOrNull()}", failures.isEmpty())
    }

    @Test fun regularMatchesHarfBuzz() = check(TestFonts.regularBytes, TestFonts.regular)

    @Test fun boldMatchesHarfBuzz() = check(TestFonts.boldBytes, TestFonts.bold)
}
