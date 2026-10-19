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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors

object PerfTags {
    fun dist(id: Long) = "amo_pa_dist_$id"
    fun oos(id: Long) = "amo_pa_oos_$id"
    const val POSM_YES = "amo_pa_posm_yes"; const val POSM_NO = "amo_pa_posm_no"; const val SAVE = "amo_pa_save"; const val SAVED = "amo_pa_saved"; const val EMPTY = "amo_pa_empty"
}

/** F-AMO-007/008/009: the assessment form. [saved] shows the saved message and disables editing (the host then returns to the outlet picker). */
@Composable
fun PerfAssessmentContent(
    brands: List<AmoBrand>, state: PerfState, saved: Boolean, saving: Boolean,
    onToggleDistributed: (Long) -> Unit, onToggleOos: (Long) -> Unit, onPosm: (Boolean?) -> Unit, onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (brands.isEmpty()) {
        AmoEmptyState(stringResource(R.string.amo_pa_no_brands), modifier.fillMaxSize().testTag(PerfTags.EMPTY))
        return
    }
    val c = LocalAronColors.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val editable = !saved && !saving
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            Text(stringResource(R.string.amo_pa_dist), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            brands.forEach { b ->
                BrandRow(if (bn) b.nameBn ?: b.name else b.name, b.id in state.present, editable, PerfTags.dist(b.id)) { onToggleDistributed(b.id) }
            }
            Text(stringResource(R.string.amo_pa_oos), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.padding(top = AronTokens.Space.M))
            if (state.present.isEmpty()) Text(stringResource(R.string.amo_pa_oos_hint), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            brands.forEach { b ->
                BrandRow(if (bn) b.nameBn ?: b.name else b.name, b.id in state.oos, editable && state.canTickOos(b.id), PerfTags.oos(b.id)) { onToggleOos(b.id) }
            }
            Text(stringResource(R.string.amo_pa_posm), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.padding(top = AronTokens.Space.M))
            Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                // tapping the chosen answer again clears it: POSM is tri-state (unanswered, yes, no)
                AmoYes(stringResource(R.string.amo_pa_yes), { onPosm(if (state.posm == true) null else true) }, Modifier.weight(1f).testTag(PerfTags.POSM_YES), enabled = editable && state.posm != false)
                AmoNo(stringResource(R.string.amo_pa_no), { onPosm(if (state.posm == false) null else false) }, Modifier.weight(1f).testTag(PerfTags.POSM_NO), enabled = editable && state.posm != true)
            }
        }
        if (saved) Text(stringResource(R.string.amo_pa_saved), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.padding(AronTokens.Space.Screen).testTag(PerfTags.SAVED))
        AmoPrimary(stringResource(R.string.amo_pa_save), onSave, Modifier.fillMaxWidth().padding(AronTokens.Space.Screen).testTag(PerfTags.SAVE), enabled = editable, busy = saving)
    }
}

@Composable
private fun BrandRow(label: String, checked: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = LocalAronColors.current.textPrimary, modifier = Modifier.padding(start = AronTokens.Space.S))
    }
}
