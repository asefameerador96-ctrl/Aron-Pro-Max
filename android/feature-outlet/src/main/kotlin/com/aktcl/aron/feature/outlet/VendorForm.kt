package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook

/**
 * What the SR outlet forms still draw themselves after the landed field and picker (D-PAR-SR2-08): the dark grey first-letter panel of the
 * change-information form (manual 44) whose chips wrap over as many rows as they need, and the colour of the round status dot before a chosen
 * outlet (manuals 43, 44). The labelled purple-outlined fields and drop-downs are the landed `OutlinedFormField` and `SearchPickerField`.
 *
 * Outdoor legibility (standing rule 14a, docs/32 s2a): a letter chip is never colour alone, the selected one is also the only filled one and
 * carries `selected` in its semantics; the panel is the look's own `panelDark`, near-black in Sunlight, so the white letters keep their contrast.
 */

/** The bar ramp (red to purple) the manual fills the selected letter and the gradient actions with. */
@Composable
private fun barBrush(): androidx.compose.ui.graphics.Brush {
    val look = LocalAronLook.current
    return androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(look.barStart, look.barMid, look.barEnd))
}

/**
 * The first-letter filter of the change-information form (manual 44): a dark grey rounded panel whose chips flow onto as many
 * rows as the width needs ("All" and then only the letters the rep's own outlets start with). The selected chip is the filled
 * one and says so to TalkBack, so the gradient is decoration, never the signal.
 */
@Composable
internal fun VendorLetterBar(
    letters: List<String>,
    selected: String,
    allLabel: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagOf: (String) -> String = { it },
) {
    val look = LocalAronLook.current
    val fill = barBrush()
    // panelDark is the manual's #505050 in the default look and near-black in Sunlight, so the white letters keep their contrast outdoors
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.panelDark).padding(AronTokens.Space.S)) {
        FlowChips {
            VendorLetterChip(allLabel, selected == OutletPicker.ALL_CHIP, { onSelect(OutletPicker.ALL_CHIP) }, Modifier.testTag(tagOf("all")), fill)
            letters.forEach { l -> VendorLetterChip(l, selected == l, { onSelect(l) }, Modifier.testTag(tagOf(l)), fill) }
        }
    }
}

@Composable
private fun VendorLetterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, fill: androidx.compose.ui.graphics.Brush) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.heightIn(min = 40.dp).width(40.dp).clip(shape)
            .then(if (selected) Modifier.background(fill) else Modifier)
            .selectable(selected = selected, role = Role.Tab) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Lays the chips out left to right and wraps, like the manual's three rows; no dependency on an experimental flow row. */
@Composable
private fun FlowChips(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val max = constraints.maxWidth
        val gap = 4.dp.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0
        var y = 0
        var rowHeight = 0
        val spots = ArrayList<Pair<Int, Int>>(placeables.size)
        placeables.forEach { p ->
            if (x > 0 && x + p.width > max) { x = 0; y += rowHeight + gap; rowHeight = 0 }
            spots += x to y
            x += p.width + gap
            rowHeight = maxOf(rowHeight, p.height)
        }
        layout(max, y + rowHeight) { placeables.forEachIndexed { i, p -> p.place(spots[i].first, spots[i].second) } }
    }
}

/** The round status dot the manual draws before a chosen outlet (its colour repeats what the label already says). */
internal val VendorOutletDot = Color(0xFFF5B921)
