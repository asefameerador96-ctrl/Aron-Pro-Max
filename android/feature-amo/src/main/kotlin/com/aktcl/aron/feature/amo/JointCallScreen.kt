package com.aktcl.aron.feature.amo

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors

object JointTags {
    const val SR = "amo_jc_sr"; const val SAVE = "amo_jc_save"; const val SAVED = "amo_jc_saved"; const val EMPTY = "amo_jc_empty"
    fun star(criterionId: Long, n: Int) = "amo_jc_star_${criterionId}_$n"
    /** The whole star row of one criterion (the kit rating control has no per-star tag). */
    fun rating(criterionId: Long) = "amo_jc_rating_$criterionId"
}

/** F-AMO-011: the Joint Call assessment form. The SR is read-only; disabled criteria are listed greyed and never scored. */
@Composable
fun JointCallContent(
    rubric: JointRubric?, sr: AssignedSr?, state: JointState, saved: Boolean, saving: Boolean,
    onStar: (criterionId: Long, stars: Int) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
) {
    if (rubric == null || rubric.criteria.isEmpty()) {
        AmoEmptyState(stringResource(R.string.amo_jc_no_rubric), modifier.fillMaxSize().testTag(JointTags.EMPTY))
        return
    }
    val c = LocalAronColors.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val editable = !saved && !saving
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Text(stringResource(R.string.amo_jc_sr, sr?.name ?: stringResource(R.string.amo_jc_sr_none)), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.testTag(JointTags.SR))
            rubric.criteria.forEach { cr ->
                val on = cr.enabled && cr.answerType == "score_1_5"
                Column(verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                    Text(if (bn) cr.labelBn ?: cr.labelEn else cr.labelEn, style = MaterialTheme.typography.bodyLarge, color = if (on) c.textPrimary else c.textSecondary)
                    val starLabels = (1..5).map { stringResource(R.string.amo_jc_star, localizedNumber(it.toLong())) }
                    com.aktcl.aron.core.ui.aron.AronRatingStars(
                        if (on) state.of(cr.id) else 0, { n -> if (on && editable) onStar(cr.id, n) },
                        { n -> starLabels[n - 1] }, Modifier.testTag(JointTags.rating(cr.id)), enabled = on && editable,
                    )
                }
            }
        }
        if (saved) Text(stringResource(R.string.amo_pa_saved), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.padding(AronTokens.Space.Screen).testTag(JointTags.SAVED))
        AmoPrimary(stringResource(R.string.amo_pa_save), onSave, Modifier.fillMaxWidth().padding(AronTokens.Space.Screen).testTag(JointTags.SAVE), enabled = editable, busy = saving)
    }
}
