package com.aktcl.aron.feature.tso

import com.aktcl.aron.core.common.AppLanguage
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
 * Number-format check (qa), NOT a money check: the TSO dashboard's compact figure for large QUANTITIES (F-TSO-002 sales
 * tiles: TsoDashboardScreens `quantity()` = TsoDashboards.compact plus the lakh or crore word, or the grouped whole
 * number below a lakh) against the lakh/crore figures of contract/fixtures/money-format-corpus.json, which the web's
 * formatLakhCrore is checked against in web/tests/money-format-corpus.test.ts. The corpus is reused only for its
 * numbers: each entry's milli-taka is turned into a whole count half away from zero (as the generator does) and that
 * count is formatted as the screen formats a quantity. compact() takes non-negative quantities, so negatives are skipped.
 *
 * Everything on the screen's path is product code: TsoDashboards.compact, LocaleDigits.formatInteger (what
 * `localizedNumber` calls) and LocaleDigits.localize (what `localizedDigits` calls), and the words from the module's
 * strings.xml (values and values-bn). Only the Compose glue of `quantity()` is re-assembled here.
 *
 * KNOWN findings (qa 2026-10-18; exactly KNOWN_CEILING entries, the list only shrinks). FIXED by android-tso (d124df7) and
 * removed from the list, history kept here:
 * - CRORE_UNGROUPED (QA-MNY-08): from 1,000 crore up the crore figure is not grouped ("1234.57" against "1,234.57";
 *   F-SYS-051 Western grouping; the web groups it).
 * - CRORE_WORD_EN: the English crore word is "Crore" here and "Cr" on the web (unit.crore).
 */
class TsoCompactCorpusTest {
    private val corpusFile = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "contract/fixtures/money-format-corpus.json") }.first { it.isFile }
    private val repo = corpusFile.parentFile.parentFile.parentFile
    private val corpus = Json.parseToJsonElement(corpusFile.readText()).jsonObject

    private val known = emptyMap<String, Int>() // finding -> most (entry, language) pairs it may explain; both findings were fixed on 2026-10-18 (d124df7), the list is empty
    private val knownCeiling = 0

    private fun word(values: String, name: String): String =
        Regex("<string name=\"$name\">([^<]*)</string>").find(File(repo, "android/feature-tso/src/main/res/$values/strings.xml").readText())!!.groupValues[1]

    private fun countHalfAway(mtk: Long): Long { val q = mtk / 1000; val r = mtk % 1000; return if (2 * r >= 1000) q + 1 else q }

    /** The screen's quantity() for a whole count, with the product helpers and the module's words. */
    private fun screen(n: Long, lang: AppLanguage): String {
        val values = if (lang == AppLanguage.BN) "values-bn" else "values"
        val c = TsoDashboards.compact(n)
        return when (c.scale) {
            TsoDashboards.Scale.CRORE -> LocaleDigits.localize(c.text, lang) + " " + word(values, "tso_dash_crore")
            TsoDashboards.Scale.LAKH -> LocaleDigits.localize(c.text, lang) + " " + word(values, "tso_dash_lakh")
            TsoDashboards.Scale.NONE -> LocaleDigits.formatInteger(n, lang)
        }
    }

    @Test
    fun compactQuantityMatchesTheCorpusFigures() {
        val bad = ArrayList<String>(); val seen = HashMap<String, Int>(); var checked = 0
        val webCrore = corpus["lakh_crore_words"]!!.jsonObject["en"]!!.jsonObject["crore"]!!.jsonPrimitive.content
        for (a in corpus["amounts"]!!.jsonArray.map { it.jsonObject }) {
            val mtk = a["mtk"]!!.jsonPrimitive.long
            if (mtk < 0) continue
            val n = countHalfAway(mtk)
            for (lang in AppLanguage.entries) {
                val want = a["expect"]!!.jsonObject["lakh_crore"]!!.jsonObject[lang.tag]!!.jsonPrimitive.content
                var got = screen(n, lang)
                checked++
                if (got == want) continue
                val reasons = mutableListOf<String>()
                if (lang == AppLanguage.EN && got.endsWith(" " + word("values", "tso_dash_crore"))) {
                    got = got.removeSuffix(word("values", "tso_dash_crore")) + webCrore; reasons += "CRORE_WORD_EN"
                }
                if (got != want && TsoDashboards.compact(n).scale == TsoDashboards.Scale.CRORE && got == want.replace(",", "")) reasons += "CRORE_UNGROUPED"
                else if (got != want) { bad += "$n (${lang.tag}): screen \"${screen(n, lang)}\", corpus \"$want\""; continue }
                reasons.forEach { seen.merge(it, 1, Int::plus) }
            }
        }
        assertTrue("corpus read", checked > 200)
        assertEquals("${bad.size} differ", emptyList<String>(), bad.take(10))
        assertEquals("the known list holds exactly KNOWN_CEILING findings", knownCeiling, known.size)
        for ((k, max) in known) {
            val n = seen[k] ?: 0
            assertTrue("$k no longer occurs: remove it from known and lower knownCeiling", n > 0)
            assertTrue("$k explains $n entries, more than the recorded $max", n <= max)
        }
        assertEquals("only known findings explain differences", known.keys, seen.keys)
    }

    @Test
    fun lakhWordsAgreeWithTheWeb() {
        val words = corpus["lakh_crore_words"]!!.jsonObject
        assertEquals(words["en"]!!.jsonObject["lakh"]!!.jsonPrimitive.content, word("values", "tso_dash_lakh"))
        assertEquals(words["bn"]!!.jsonObject["lakh"]!!.jsonPrimitive.content, word("values-bn", "tso_dash_lakh"))
        assertEquals(words["bn"]!!.jsonObject["crore"]!!.jsonPrimitive.content, word("values-bn", "tso_dash_crore"))
    }
}
