package com.aktcl.aron.feature.outlet

import java.util.UUID

enum class AnswerType { BOOL, NUM, OPTION, TEXT, PHOTO_ONLY }

/** One survey question (contract SurveyDef.questions[]). */
data class SurveyQuestion(
    val questionId: Long,
    val answerType: AnswerType,
    val key: String,
    val required: Boolean,
    val requiresPhoto: Boolean,
    val showIfKey: String? = null,
    val showIfBool: Boolean? = null,
)

data class SurveyAnswer(
    val bool: Boolean? = null,
    val num: Double? = null,
    val optionCode: String? = null,
    val text: String? = null,
    /** The compressed photo, claimed by the response when it is saved (core-media). */
    val photoUuid: String? = null,
) {
    val isEmpty: Boolean get() = bool == null && num == null && optionCode == null && text.isNullOrBlank() && photoUuid == null
}

/** One stored answer, ready for the survey_response outbox (client uuid is stable per visit and question, so a replay upserts). */
data class SurveyResponseRow(
    val clientUuid: String, val visitUuid: String, val surveyId: Long, val surveyVersion: Int,
    val question: SurveyQuestion, val answer: SurveyAnswer,
)

/** POSM survey rules (F-SR-021): Q1 yes/no, Q1.1 (the photo) only when Q1 is yes, a confirmation before saving. No points here: loyalty is deferred. */
object PosmSurvey {
    /** A question shows when it has no condition, or the answer to its condition key equals the wanted bool. */
    fun visible(questions: List<SurveyQuestion>, answers: Map<String, SurveyAnswer>): List<SurveyQuestion> =
        questions.filter { q -> q.showIfKey == null || answers[q.showIfKey]?.bool == (q.showIfBool ?: true) }

    private fun answered(q: SurveyQuestion, a: SurveyAnswer?): Boolean = when {
        a == null -> false
        q.requiresPhoto && a.photoUuid == null -> false
        q.answerType == AnswerType.PHOTO_ONLY -> a.photoUuid != null
        else -> !a.isEmpty
    }

    /** Every visible required question has its answer (and its photo, where one is required). */
    fun canSubmit(questions: List<SurveyQuestion>, answers: Map<String, SurveyAnswer>): Boolean =
        visible(questions, answers).all { !it.required || answered(it, answers[it.key]) }

    /** Rows for the visible, answered questions only: an answer to a question that was hidden again is dropped. */
    fun rows(visitUuid: String, surveyId: Long, version: Int, questions: List<SurveyQuestion>, answers: Map<String, SurveyAnswer>): List<SurveyResponseRow> =
        visible(questions, answers).mapNotNull { q ->
            answers[q.key]?.takeIf { !it.isEmpty }?.let { SurveyResponseRow(rowUuid(visitUuid, surveyId, q.questionId), visitUuid, surveyId, version, q, it) }
        }

    /** Same visit and question give the same uuid, so a replayed upload posts once. */
    fun rowUuid(visitUuid: String, surveyId: Long, questionId: Long): String =
        UUID.nameUUIDFromBytes("survey_response|$visitUuid|$surveyId|$questionId".toByteArray()).toString()
}
