package com.aktcl.aron.feature.outlet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallContentTest {
    private fun a(id: Long, kind: ContentKindLocal, seq: Int, outlets: Set<Long> = emptySet(), from: String = "2026-10-01", to: String = "2026-10-31", path: String? = "/f") =
        ContentAsset(id, 1, kind, seq, from, to, outlets, path)

    @Test fun avThenKvEachBySequence() {
        val steps = CallPlan.content(listOf(a(3, ContentKindLocal.KV, 1), a(2, ContentKindLocal.AV, 2), a(1, ContentKindLocal.AV, 1)), 7, "2026-10-07")
        assertEquals(listOf(1L, 2L, 3L), steps.map { it.asset.contentId })
    }

    @Test fun onlyValidTodayAndAssignedToThisOutlet() {
        val steps = CallPlan.content(
            listOf(a(1, ContentKindLocal.AV, 1, outlets = setOf(9)), a(2, ContentKindLocal.AV, 2, outlets = setOf(7)), a(3, ContentKindLocal.KV, 1, to = "2026-10-06")),
            7, "2026-10-07",
        )
        assertEquals(listOf(2L), steps.map { it.asset.contentId })
    }

    @Test fun aMissingFileIsAStepMarkedMissingNotADroppedSale() {
        val s = CallPlan.content(listOf(a(1, ContentKindLocal.AV, 1, path = null)), 7, "2026-10-07").single()
        assertTrue(s.missing)
        assertEquals("skipped_missing", ContentOutcome.SKIPPED_MISSING.wire)
    }

    private val q1 = SurveyQuestion(1, AnswerType.BOOL, "posm_present", required = true, requiresPhoto = false)
    private val q11 = SurveyQuestion(2, AnswerType.PHOTO_ONLY, "posm_photo", required = true, requiresPhoto = true, showIfKey = "posm_present", showIfBool = true)
    private val qs = listOf(q1, q11)

    @Test fun photoQuestionShowsOnlyWhenQ1IsYes() {
        assertEquals(listOf(q1), PosmSurvey.visible(qs, emptyMap()))
        assertEquals(listOf(q1), PosmSurvey.visible(qs, mapOf("posm_present" to SurveyAnswer(bool = false))))
        assertEquals(listOf(q1, q11), PosmSurvey.visible(qs, mapOf("posm_present" to SurveyAnswer(bool = true))))
    }

    @Test fun noCannotSubmitWithoutQ1AndYesNeedsThePhoto() {
        assertFalse(PosmSurvey.canSubmit(qs, emptyMap()))
        assertTrue(PosmSurvey.canSubmit(qs, mapOf("posm_present" to SurveyAnswer(bool = false))))
        assertFalse(PosmSurvey.canSubmit(qs, mapOf("posm_present" to SurveyAnswer(bool = true))))
        assertTrue(PosmSurvey.canSubmit(qs, mapOf("posm_present" to SurveyAnswer(bool = true), "posm_photo" to SurveyAnswer(photoUuid = "p1"))))
    }

    @Test fun anAnswerToAQuestionHiddenAgainIsDropped() {
        val answers = mapOf("posm_present" to SurveyAnswer(bool = false), "posm_photo" to SurveyAnswer(photoUuid = "p1"))
        assertEquals(listOf(1L), PosmSurvey.rows("v1", 5, 1, qs, answers).map { it.question.questionId })
    }

    @Test fun rowUuidIsStablePerVisitAndQuestionSoAReplayPostsOnce() {
        val first = PosmSurvey.rows("v1", 5, 1, qs, mapOf("posm_present" to SurveyAnswer(bool = false))).single().clientUuid
        val again = PosmSurvey.rows("v1", 5, 1, qs, mapOf("posm_present" to SurveyAnswer(bool = false))).single().clientUuid
        assertEquals(first, again)
        assertFalse(first == PosmSurvey.rowUuid("v2", 5, 1))
    }
}
