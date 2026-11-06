package com.aktcl.aron.feature.amo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.rules.UiLocale

/** The digits language of the screen for the shared formatters (Bangla digits in the Bangla locale, Latin in English). */
@Composable
fun teamLocale(): UiLocale = if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN

/** The two-segment toggle under the AMO bars (screenshots 34, 35): a rounded box with a green outline, the selected segment filled green. */
@Composable
fun AmoSegments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(look.card).border(BorderStroke(2.dp, look.goEnd), shape)) {
        labels.forEachIndexed { i, text ->
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).background(if (i == selected) look.goEnd else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(text, style = amoText(16, bold = i == selected, align = TextAlign.Center)) }
        }
    }
}

/** A progress bar whose fill takes the band colour (red, amber, green); [fraction] 0..1. */
@Composable
fun AmoBandBar(fraction: Float, fill: Color, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxWidth().heightIn(min = 8.dp).clip(RoundedCornerShape(4.dp)).background(look.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().heightIn(min = 8.dp).clip(RoundedCornerShape(4.dp)).background(fill))
    }
}

/** Label at the start, value at the end on one line; wraps under each other at large text sizes (no squeezing). */
@Composable
fun AmoLabelValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LocalAronLook.current.ink) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label $value" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = amoText(16, color = look.ink), modifier = Modifier.weight(1f))
        Text(value, style = amoText(14, bold = true, color = valueColor), textAlign = TextAlign.End, modifier = Modifier.weight(1.4f, fill = false))
    }
}

/** A small tinted icon of the team screens. */
@Composable
fun TeamIcon(@androidx.annotation.DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalAronLook.current.ink, size: androidx.compose.ui.unit.Dp = 18.dp) =
    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(id), null, modifier.size(size), tint = tint)


/**
 * A selector field of the Live Dashboard and the Task form (screenshots 38, 39): a white box with a purple outline, the value and a caret;
 * a tap opens the menu of [options] (key to text). [enabled] false keeps the box but not the menu (the AMO selector lists the caller only).
 */
@Composable
fun AmoDropdown(value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false) {
    val look = LocalAronLook.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(look.card).border(BorderStroke(2.dp, look.purple), shape)
                .clickable(enabled = enabled && options.isNotEmpty(), role = Role.DropdownList) { open = true }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = amoText(if (small) 13 else 16), modifier = Modifier.weight(1f), maxLines = 2)
            TeamIcon(R.drawable.amo_team_ic_caret, tint = look.ink, size = 22.dp)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            options.forEach { (k, t) -> androidx.compose.material3.DropdownMenuItem(text = { Text(t, style = amoText(16)) }, onClick = { open = false; onSelect(k) }) }
        }
    }
}

/** The lavender of the table headers (screenshots 35, 72; about #E1D9F0): the purple header text on it holds 8:1, in Sunlight too (it is solid). */
internal val TableLavender = Color(0xFFE1D9F0)
