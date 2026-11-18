package com.aktcl.aron.feature.amo

import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook

object PerfTags {
    fun dist(id: Long) = "amo_pa_dist_$id"
    fun oos(id: Long) = "amo_pa_oos_$id"
    const val POSM_YES = "amo_pa_posm_yes"; const val POSM_NO = "amo_pa_posm_no"; const val SAVE = "amo_pa_save"; const val SAVED = AmoDialogTags.SAVED; const val EMPTY = "amo_pa_empty"
    const val POSM_ERROR = "amo_pa_posm_error"; const val HEADER = "amo_pa_header"
}

/** The orange Distribution header (screenshot 27), darkened until its white label holds 4.5:1. */
internal val AmoOrange = Color(0xFFB45309)
/** The solid save green of the assessment screens (screenshots 27, 31), white label above 4.5:1. */
internal val AmoSaveGreen = Color(0xFF3F7D20)

/** A section card with a coloured header band ("●" + title and, inside the same band, the white info line with its (i): screenshot 27). */
@Composable
internal fun AmoBandCard(title: String, band: Color, info: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.card)) {
        Column(Modifier.fillMaxWidth().background(band).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("● $title", style = amoText(16, bold = true, color = Color.White))
            if (info != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.Icon(com.aktcl.aron.core.ui.AronIcons.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(info, style = amoText(13, color = Color.White))
            }
        }
        content()
    }
}

/** The retailer/route card shared by the assessment screens (screenshots 27, 31): the shop icon, bold labels, grey values; retailer first. */
@Composable
internal fun AmoOutletHeader(outletLabel: String?, routeName: String?, modifier: Modifier = Modifier) {
    if (outletLabel == null && routeName == null) return
    if (outletLabel != null) AmoSaleHeaderCard(routeName, outletLabel, modifier.testTag(PerfTags.HEADER), padded = false, retailerFirst = true)
    else Text(stringResource(R.string.amo_pa_route) + " " + routeName.orEmpty(), style = amoText(15, bold = true), modifier = modifier.testTag(PerfTags.HEADER))
}

/** The wide green Save button of the assessment screens. */
@Composable
internal fun AmoSaveButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) AmoSaveGreen else look.disabledFill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = amoText(18, bold = true, color = if (enabled) Color.White else look.disabledText)) }
}

/** One brand cell: a dark-outlined box and a 14 sp name that wraps at word breaks (the kit's 16 sp row broke names mid-word in three columns at 360 dp). */
@Composable
private fun AmoBrandCheck(name: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalAronLook.current
    val box = RoundedCornerShape(4.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.5f)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(box).background(if (checked) look.purple else look.card).border(2.dp, if (checked) look.purple else look.ink, box),
            contentAlignment = Alignment.Center,
        ) { if (checked) androidx.compose.material3.Icon(com.aktcl.aron.core.ui.AronIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        Text(name, style = amoText(14, color = look.ink), modifier = Modifier.padding(start = 6.dp))
    }
}

/** Brands in a 3-column grid of checkboxes (screenshot 27); [tag] names a cell's checkbox. */
@Composable
private fun BrandGrid(brands: List<AmoBrand>, bn: Boolean, checked: (Long) -> Boolean, enabled: (Long) -> Boolean, tag: (Long) -> String, onClick: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // at large text sizes one brand per line: three columns cannot hold a word at 200 % (D-PAR-AMO-26)
        val cols = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 1 else 3
        brands.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { b ->
                    Box(Modifier.weight(1f)) { AmoBrandCheck(if (bn) b.nameBn ?: b.name else b.name, checked(b.id), { onClick(b.id) }, Modifier.testTag(tag(b.id)), enabled = enabled(b.id)) }
                }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * F-AMO-007/008/009 (screenshots 27-28): the header card, the orange Distribution card, the purple OOS card, POSM as two checkboxes
 * (one is required: without it Save shows [posmError] and writes nothing), brands optional. [saved] opens the "data saved" dialog;
 * OK calls [onSavedOk], and the host then returns to the Control Call outlet selection with the outlet cleared.
 */
@Composable
fun PerfAssessmentContent(
    brands: List<AmoBrand>, state: PerfState, saved: Boolean, saving: Boolean,
    onToggleDistributed: (Long) -> Unit, onToggleOos: (Long) -> Unit, onPosm: (Boolean?) -> Unit, onSave: () -> Unit,
    modifier: Modifier = Modifier,
    outletLabel: String? = null, routeName: String? = null, posmError: Boolean = false, onSavedOk: () -> Unit = {},
) {
    if (brands.isEmpty()) {
        AmoEmptyState(stringResource(R.string.amo_pa_no_brands), modifier.fillMaxSize().testTag(PerfTags.EMPTY))
        return
    }
    val look = LocalAronLook.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val editable = !saved && !saving
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AmoOutletHeader(outletLabel, routeName)
            AmoBandCard(stringResource(R.string.amo_pa_dist), AmoOrange, stringResource(R.string.amo_pa_dist_hint)) {
                BrandGrid(brands, bn, { it in state.present }, { editable }, PerfTags::dist, onToggleDistributed)
            }
            AmoBandCard(stringResource(R.string.amo_pa_oos), look.purple, stringResource(R.string.amo_pa_oos_hint2)) {
                BrandGrid(brands, bn, { it in state.oos }, { editable && state.canTickOos(it) }, PerfTags::oos, onToggleOos)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.amo_pa_posm), style = amoText(17, bold = true, color = look.purple))
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { com.aktcl.aron.core.ui.aron.AronCheckboxRow(stringResource(R.string.amo_pa_yes), state.posm == true, { onPosm(if (state.posm == true) null else true) }, Modifier.testTag(PerfTags.POSM_YES), enabled = editable) }
                    Box(Modifier.weight(1f)) { com.aktcl.aron.core.ui.aron.AronCheckboxRow(stringResource(R.string.amo_pa_no), state.posm == false, { onPosm(if (state.posm == false) null else false) }, Modifier.testTag(PerfTags.POSM_NO), enabled = editable) }
                }
                if (posmError) Text(stringResource(R.string.amo_pa_posm_required), style = amoText(14, color = look.red), modifier = Modifier.testTag(PerfTags.POSM_ERROR))
            }
        }
        AmoSaveButton(stringResource(R.string.amo_pa_save), editable, onSave, Modifier.padding(12.dp).testTag(PerfTags.SAVE))
    }
    if (saved) AmoSavedDialog(stringResource(R.string.amo_saved_title), stringResource(R.string.amo_ok), onSavedOk)
}
