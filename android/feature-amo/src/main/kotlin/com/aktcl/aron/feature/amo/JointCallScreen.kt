package com.aktcl.aron.feature.amo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedNumber

object JointTags {
    const val SR = "amo_jc_sr"; const val SAVE = "amo_jc_save"; const val SAVED = AmoDialogTags.SAVED; const val EMPTY = "amo_jc_empty"
    fun star(criterionId: Long, n: Int) = "amo_jc_star_${criterionId}_$n"
    /** The whole star row of one criterion. */
    fun rating(criterionId: Long) = "amo_jc_rating_$criterionId"
    fun section(i: Int) = "amo_jc_section_$i"
    const val LOW = "amo_jc_low"; const val HIGH = "amo_jc_high"
}

private val LowRed = Color(0xFFC62828)
private val HighGreen = Color(0xFF2E7D32)
private val StarOn = Color(0xFFB07A00)

/**
 * F-AMO-011 (screenshots 31-32): the SR call assessment of the open Joint Call. Header card, the two instruction lines, one card per
 * rubric section with the orange header, each item with its guidance line and 1-5 stars (red "Low" under star 1, green "High" under
 * star 5). [defaultStar] is `cfg.amo.joint_call_default_star` (1, or null = the AMO must rate every item). Save is enabled with a
 * default star. [saved] opens the data-saved dialog; OK calls [onSavedOk] and the AMO stays on the outlet screen.
 */
@Composable
fun JointCallContent(
    rubric: JointRubric?, sr: AssignedSr?, state: JointState, saved: Boolean, saving: Boolean,
    onStar: (criterionId: Long, stars: Int) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
    outletLabel: String? = null, routeName: String? = null, defaultStar: Int? = 1, onSavedOk: () -> Unit = {},
) {
    if (rubric == null || rubric.criteria.isEmpty()) {
        AmoEmptyState(stringResource(R.string.amo_jc_no_rubric), modifier.fillMaxSize().testTag(JointTags.EMPTY))
        return
    }
    val look = LocalAronLook.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val editable = !saved && !saving
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AmoOutletHeader(outletLabel, routeName)
            if (sr != null) Text(stringResource(R.string.amo_jc_sr, sr.name), style = amoText(15, bold = true), modifier = Modifier.testTag(JointTags.SR))
            Text(stringResource(R.string.amo_jc_intro1), style = amoText(14, color = look.inkMuted))
            Text(stringResource(R.string.amo_jc_intro2), style = amoText(14, color = look.inkMuted))
            rubric.sections.forEachIndexed { i, sec ->
                val title = (if (bn) sec.titleBn else null) ?: sec.title
                val body: @Composable () -> Unit = {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        sec.criteria.forEach { cr -> Item(cr, bn, state, defaultStar, editable, onStar) }
                    }
                }
                if (title != null) AmoBandCard(title, AmoOrange, null, Modifier.testTag(JointTags.section(i)), body)
                else Column(Modifier.fillMaxWidth().testTag(JointTags.section(i)).clip(RoundedCornerShape(12.dp)).background(look.card)) { body() }
            }
        }
        AmoSaveButton(stringResource(R.string.amo_pa_save), editable && state.canSave(rubric, defaultStar), onSave, Modifier.padding(12.dp).testTag(JointTags.SAVE))
    }
    if (saved) AmoSavedDialog(stringResource(R.string.amo_saved_title), stringResource(R.string.amo_ok), onSavedOk)
}

@Composable
private fun Item(cr: RubricCriterion, bn: Boolean, state: JointState, defaultStar: Int?, editable: Boolean, onStar: (Long, Int) -> Unit) {
    val look = LocalAronLook.current
    val on = cr.enabled && cr.answerType == "score_1_5"
    val value = if (on) state.of(cr.id, defaultStar) else 0
    val guidance = (if (bn) cr.guidanceBn else null) ?: cr.guidanceEn
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text((if (bn) cr.labelBn else null) ?: cr.labelEn, style = amoText(17, bold = true, color = if (on) look.ink else look.inkMuted))
        if (guidance != null) Text(guidance, style = amoText(13, color = look.inkMuted))
        Row(Modifier.fillMaxWidth().testTag(JointTags.rating(cr.id)), horizontalArrangement = Arrangement.SpaceEvenly) {
            (1..5).forEach { n ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    val label = stringResource(R.string.amo_jc_star, localizedNumber(n.toLong()))
                    Box(
                        Modifier.heightIn(min = 44.dp).size(48.dp).testTag(JointTags.star(cr.id, n)).semantics { contentDescription = label }
                            .clickable(enabled = on && editable, role = Role.RadioButton) { onStar(cr.id, n) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (n <= value) "★" else "☆", style = amoText(32, color = if (n <= value) StarOn else look.inkMuted)) }
                    if (n == 1) Badge(stringResource(R.string.amo_jc_low), LowRed, JointTags.LOW)
                    if (n == 5) Badge(stringResource(R.string.amo_jc_high), HighGreen, JointTags.HIGH)
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, fill: Color, tag: String) =
    Text(text, style = amoText(12, bold = true, color = Color.White, align = TextAlign.Center), modifier = Modifier.testTag(tag).clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = 8.dp, vertical = 2.dp))
