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
}
