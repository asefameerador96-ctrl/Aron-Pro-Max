package com.aktcl.aron.feature.tso

import com.aktcl.aron.core.common.LocaleDigits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Money gate cross-implementation check (qa): the TSO dashboard's lakh/crore figure (TsoDashboards.compact, the numeric
 * part the screen joins with the lakh or crore word) against contract/fixtures/money-format-corpus.json `lakh_crore`,
 * which the web's formatLakhCrore is checked against in web/tests/money-format-corpus.test.ts. The corpus input is
 * milli-taka; it is turned into whole taka half away from zero exactly as the corpus generator does, then compacted.
 * compact() takes non-negative quantities, so negative amounts are skipped.
 *
 * Known findings (qa 2026-10-18; the list only shrinks):
 * - CRORE_UNGROUPED: from 1,000 crore up the crore figure is not grouped ("1234.57" against the corpus "1,234.57";
 *   F-SYS-051 Western grouping; the web groups it).
 * - The English crore word is "Crore" in android/feature-tso strings.xml and "Cr" in the web's unit.crore; checked below.
 */
class TsoCompactCorpusTest {
    private val corpusFile = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "contract/fixtures/money-format-corpus.json") }.first { it.isFile }
    private val corpus = Json.parseToJsonElement(corpusFile.readText()).jsonObject

    private fun takaHalfAway(mtk: Long): Long { val q = mtk / 1000; val r = mtk % 1000; return if (2 * r >= 1000) q + 1 else q }

    @Test
    fun compactNumberMatchesTheCorpusLakhCroreFigure() {
        val bad = ArrayList<String>(); var ungroupedCrore = 0; var checked = 0
        for (a in corpus["amounts"]!!.jsonArray.map { it.jsonObject }) {
            val mtk = a["mtk"]!!.jsonPrimitive.long
            if (mtk < 0) continue
            val want = a["expect"]!!.jsonObject["lakh_crore"]!!.jsonObject["en"]!!.jsonPrimitive.content.substringBefore(' ')
            val c = TsoDashboards.compact(takaHalfAway(mtk))
            val got = if (c.scale == TsoDashboards.Scale.NONE) LocaleDigits.groupSouthAsian(c.text.toLong()) else c.text
            checked++
            if (got == want) continue
            if (c.scale == TsoDashboards.Scale.CRORE && got == want.replace(",", "")) { ungroupedCrore++; continue } // CRORE_UNGROUPED
            bad += "$mtk mtk: compact \"$got\", corpus \"$want\""
        }
        assertTrue("corpus read", checked > 100)
        assertEquals("${bad.size} differ", emptyList<String>(), bad.take(10))
        assertTrue("CRORE_UNGROUPED is fixed: remove it from the known findings", ungroupedCrore > 0)
    }

    @Test
    fun croreWordFinding() {
        val words = corpus["lakh_crore_words"]!!.jsonObject["en"]!!.jsonObject
        val strings = File(corpusFile.parentFile.parentFile.parentFile, "android/feature-tso/src/main/res/values/strings.xml").readText()
        val crore = Regex("<string name=\"tso_dash_crore\">([^<]*)</string>").find(strings)!!.groupValues[1]
        val lakh = Regex("<string name=\"tso_dash_lakh\">([^<]*)</string>").find(strings)!!.groupValues[1]
        assertEquals(words["lakh"]!!.jsonPrimitive.content, lakh)
        // Known finding: the web shows "Cr", the TSO app "Crore". When the two agree, change this to assertEquals.
        assertTrue("crore word now agrees: make this an equality check", crore != words["crore"]!!.jsonPrimitive.content)
    }
}
