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
 * The SR outlet forms exactly as the owner's manual draws them (screenshots 42, 43, 44): every entry is a **label above a
 * purple-outlined white box**, the outlet drop-down carries a round status dot, the first-letter filter is a dark grey panel
 * whose letters wrap over as many rows as they need, and the two wide actions are gradient bars (GEO purple to crimson, Save
 * crimson to purple with a save glyph).
 *
 * It lives beside [FormField] and [SearchPickerField] rather than replacing them: the AMO's own request form (OutletRequestContent)
 * keeps the kit look it was built with, so nothing outside the SR journey moves. Only `SrOutletRequestContent` draws from here.
 *
 * Outdoor legibility (standing rule 14a, docs/32 s2a) decides where the manual is only a colour: the field outline is the look's
 * own purple on white, the label is full-strength ink rather than the vendor's grey, and a letter chip is never colour alone —
 * the selected one is also the only filled one and carries `selected` in its semantics.
 */

/** The vendor's field outline: a 1.5 dp purple rule around a white 10 dp box. */
private val FieldShape = RoundedCornerShape(10.dp)
private val FieldBorder = 1.5.dp
private val FieldMinHeight = 52.dp

/** The bar ramp (red to purple) the manual fills the selected letter and the gradient actions with. */
@Composable
private fun barBrush(): androidx.compose.ui.graphics.Brush {
    val look = LocalAronLook.current
    return androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(look.barStart, look.barMid, look.barEnd))
}

@Composable
private fun fieldOutline(isError: Boolean): Color = if (isError) LocalAronLook.current.red else LocalAronLook.current.purple

/** The small label the manual puts above every box ("ক্লাস্টার নির্বাচন করুন", "দোকানের নাম", ...). */
@Composable
internal fun VendorLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = LocalAronLook.current.ink)
}

/**
 * A labelled text box of the manual's forms. [fieldModifier] (the test tag) goes on the editable node so the existing tests
 * keep finding it; the box turns red on [isError] and the error's own line is written by the caller, as before.
 */
@Composable
internal fun VendorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    fieldModifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        VendorLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = FieldMinHeight).clip(FieldShape).background(look.card)
                .border(FieldBorder, fieldOutline(isError), FieldShape)
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .semantics { contentDescription = label }
                .then(fieldModifier),
            textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge).copy(color = look.ink),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            cursorBrush = SolidColor(look.purple),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    }
}

/**
 * A labelled drop-down of the manual's forms: the chosen label (or the placeholder, which the manual repeats from the label)
 * with a caret, over the same searchable dialog [SearchPickerField] opens. [dot] draws the manual's round status marker before
 * the text; it is never the only thing that carries meaning (its colour repeats what the row already says in words).
 */
@Composable
internal fun VendorPicker(
    label: String,
    title: String,
    searchHint: String,
    items: List<PickItem>,
    selected: PickItem?,
    onPick: (PickItem) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    dot: Color? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        VendorLabel(label)
        SearchPickerField(
            title, searchHint, items, selected, onPick, tag,
            Modifier.fillMaxWidth(), isError = isError, boxed = true, leadingDot = if (selected != null) dot else null,
        )
    }
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
