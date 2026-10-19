package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.entity.SurveyQuestionEntity
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.feature.outlet.AnswerType
import com.aktcl.aron.feature.outlet.PosmSurvey
import com.aktcl.aron.feature.outlet.SurveyAnswer
import com.aktcl.aron.feature.outlet.SurveyQuestion

object AmoSurveyTags {
    const val EMPTY = "amo_sv_empty"; const val SUBMIT = "amo_sv_submit"; const val SKIP = "amo_sv_skip"; const val DONE = "amo_sv_done"
    fun yes(key: String) = "amo_sv_yes_$key"; fun no(key: String) = "amo_sv_no_$key"; fun photo(key: String) = "amo_sv_photo_$key"
}

/** One question with the label in the user's language. */
data class AmoSurveyQuestion(val question: SurveyQuestion, val label: String)

/**
 * F-AMO-043: the AMO Survey is the SR's POSM survey component ([PosmSurvey] rules: visibility, required answers, row uuids)
 * without points (docs/24 s4.8: the AMO survey earns nothing). Only yes/no and photo questions can be answered here; a
 * required question of another type would block Submit for ever, so it is shown as nothing and counts as optional.
 */
object AmoSurvey {
    private val SUPPORTED = setOf(AnswerType.BOOL, AnswerType.PHOTO_ONLY)

    fun questions(rows: List<SurveyQuestionEntity>, bn: Boolean): List<AmoSurveyQuestion> =
        rows.sortedBy { it.ordinal }.map {
            val type = runCatching { AnswerType.valueOf(it.answerType.uppercase()) }.getOrDefault(AnswerType.TEXT)
            val q = SurveyQuestion(
                it.questionId, type, it.questionKey ?: ("q" + it.questionId), (it.required ?: true) && type in SUPPORTED,
                it.requiresPhoto || type == AnswerType.PHOTO_ONLY, it.showIfKey, it.showIfBool,
            )
            AmoSurveyQuestion(q, if (bn) it.labelBn?.takeIf { l -> l.isNotBlank() } ?: it.labelEn else it.labelEn)
        }

    /** The questions on screen now: visible by their condition and answerable here. */
    fun shown(all: List<AmoSurveyQuestion>, answers: Map<String, SurveyAnswer>): List<AmoSurveyQuestion> {
        val visible = PosmSurvey.visible(all.map { it.question }, answers).map { it.key }.toSet()
        return all.filter { it.question.key in visible && it.question.answerType in SUPPORTED }
    }

    fun canSubmit(all: List<AmoSurveyQuestion>, answers: Map<String, SurveyAnswer>): Boolean = PosmSurvey.canSubmit(all.map { it.question }, answers)
}

/** The survey form. [onYes]/[onNo] answer a yes/no question, [onPhoto] takes the photo of a question that needs one; Submit asks to confirm. */
@Composable
fun AmoSurveyContent(
    all: List<AmoSurveyQuestion>, answers: Map<String, SurveyAnswer>, confirming: Boolean, saving: Boolean,
    onYes: (String) -> Unit, onNo: (String) -> Unit, onPhoto: (String) -> Unit,
    onSubmit: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (all.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            AmoEmptyState(stringResource(R.string.amo_sv_empty), Modifier.weight(1f).fillMaxWidth().testTag(AmoSurveyTags.EMPTY), hint = stringResource(R.string.amo_sv_empty_hint))
            // already submitted for this call (or none downloaded): a way back to the menu
            AmoSecondary(stringResource(R.string.amo_sv_skip), onSkip, Modifier.fillMaxWidth().padding(AronTokens.Space.Screen).testTag(AmoSurveyTags.SKIP))
        }
        return
    }
    val c = LocalAronColors.current
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        AmoSurvey.shown(all, answers).forEach { (q, label) ->
            val cur = answers[q.key]
            Text(label, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            if (q.answerType == AnswerType.BOOL) {
                Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    AmoYes(stringResource(R.string.amo_pa_yes), { onYes(q.key) }, Modifier.weight(1f).testTag(AmoSurveyTags.yes(q.key)), enabled = !saving && cur?.bool != true)
                    AmoNo(stringResource(R.string.amo_pa_no), { onNo(q.key) }, Modifier.weight(1f).testTag(AmoSurveyTags.no(q.key)), enabled = !saving && cur?.bool != false)
                }
            }
            if (q.requiresPhoto) {
                val taken = cur?.photoUuid != null
                AmoSecondary(
                    stringResource(if (taken) R.string.amo_sv_photo_retake else R.string.amo_sv_photo), { onPhoto(q.key) },
                    Modifier.fillMaxWidth().testTag(AmoSurveyTags.photo(q.key)), enabled = !saving,
                )
                if (taken) Text(stringResource(R.string.amo_sv_photo_taken), style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
            }
        }
        AmoPrimary(stringResource(R.string.amo_sv_submit), onSubmit, Modifier.fillMaxWidth().testTag(AmoSurveyTags.SUBMIT), enabled = !saving && AmoSurvey.canSubmit(all, answers), busy = saving)
        // The call is never blocked: with no camera or no light the AMO can leave without saving the survey.
        AmoSecondary(stringResource(R.string.amo_sv_skip), { if (!saving) onSkip() }, Modifier.fillMaxWidth().testTag(AmoSurveyTags.SKIP))
    }
    if (confirming) {
        AronConfirmDialog(
            stringResource(R.string.amo_cc_menu_survey), stringResource(R.string.amo_sv_confirm), stringResource(R.string.amo_pa_yes), stringResource(R.string.amo_pa_no),
            onConfirm = onConfirm, onDismiss = onDismiss,
        )
    }
}
