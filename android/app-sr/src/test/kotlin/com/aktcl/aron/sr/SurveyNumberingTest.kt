package com.aktcl.aron.sr

import org.junit.Assert.assertEquals
import org.junit.Test

/** SRM-B-06: the survey's question numbers as the manual shows them. */
class SurveyNumberingTest {
    @Test fun subQuestionsHangUnderTheirParent() {
        val n = SurveyNumbering.of(listOf("posm" to null, "photo" to "posm", "other" to null, "note" to "other", "note2" to "other"))
        assertEquals(mapOf("posm" to "1", "photo" to "1.1", "other" to "2", "note" to "2.1", "note2" to "2.2"), n)
    }

    @Test fun aQuestionWhoseParentIsMissingCountsAsTopLevel() {
        assertEquals(mapOf("a" to "1", "b" to "2"), SurveyNumbering.of(listOf("a" to null, "b" to "gone")))
    }

    // SRM-B-06: the host drops a sub-question whose parent is unsupported before numbering, so the numbers do not skip
    @Test fun numbersDoNotSkipWhenASubQuestionsParentIsUnsupported() {
        val all = listOf(Triple("a", null, true), Triple("t", null, false), Triple("t1", "t", true), Triple("b", null, true))
        val ok = all.filter { it.third }
        val keys = ok.map { it.first }.toSet()
        val kept = ok.filter { it.second == null || it.second in keys }.map { it.first to it.second }
        assertEquals(mapOf("a" to "1", "b" to "2"), SurveyNumbering.of(kept))
    }
}
