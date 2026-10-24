package com.aktcl.aron.core.ui.aron

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.Glyph.calendar
import com.aktcl.aron.core.ui.aron.Glyph.check
import com.aktcl.aron.core.ui.aron.Glyph.star
import com.aktcl.aron.core.ui.aron.Glyph.sun
import kotlinx.coroutines.launch

/*
 * Form and action pieces the screen lanes asked for (docs/status/ui-parity-ui-{sr-b,amo,tso}.md, Kit requests, 2026-10-19), drawn in
 * the existing-Aron language: purple ink, gradient for the chosen thing, white on the grey page, 48 dp targets, token colours only.
 */

/** A choice chip: the [selected] one is the gradient pill with white text, the others white with a purple hairline and purple text. */
@Composable
fun AronChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = AronLook.Touch)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) look.barBrush() else Brush.linearGradient(listOf(look.card, look.card)))
            .then(if (selected) Modifier else Modifier.border(1.dp, look.purple.copy(alpha = 0.5f), shape))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = lookStyle(15.sp, medium = true, color = if (selected) look.onBar else look.purple, align = TextAlign.Center)) }
}

/** A radio row: a purple ring (filled dot when [selected]) and the [text]; the whole 48 dp row selects. */
@Composable
fun AronRadioRow(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalAronLook.current
    Row(
        modifier.fillMaxWidth().heightIn(min = AronLook.Touch).alpha(if (enabled) 1f else 0.5f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).border(2.dp, look.purple, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(look.purple))
        }
        Text(text, style = lookStyle(16.sp, color = look.ink), modifier = Modifier.padding(start = 12.dp))
    }
}

/** A checkbox row: a purple rounded square (white check when [checked]) and the [text]; the whole 48 dp row toggles. */
@Composable
fun AronCheckboxRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalAronLook.current
    val box = RoundedCornerShape(4.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = AronLook.Touch).alpha(if (enabled) 1f else 0.5f)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(box).background(if (checked) look.purple else look.card).border(2.dp, look.purple, box),
            contentAlignment = Alignment.Center,
        ) { if (checked) GlyphCanvas(Modifier.size(18.dp)) { check(Color.White) } }
        Text(text, style = lookStyle(16.sp, color = look.ink), modifier = Modifier.padding(start = 12.dp))
    }
}

/** The red error line under a form block (13 sp `aron.red`); fields carry their own through [AronField]'s errorText. */
@Composable
fun AronErrorText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = lookStyle(13.sp, color = LocalAronLook.current.red), modifier = modifier.padding(top = 4.dp))
}

/** Colour of an [AronSolidButton]: [Amber] edit, [Green] mark paid / confirm, [Red] destructive, [Purple] neutral action. */
enum class SolidTone { Amber, Green, Red, Purple }

/** A solid one-colour action button (memo Edit, Mark paid): white bold label, 8 dp radius, 52 dp; grey when disabled. */
@Composable
fun AronSolidButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: SolidTone = SolidTone.Purple, enabled: Boolean = true) {
    val look = LocalAronLook.current
    val fill = if (!enabled) look.primaryDisabled else when (tone) {
        SolidTone.Amber -> look.amber
        SolidTone.Green -> look.goStart
        SolidTone.Red -> look.red
        SolidTone.Purple -> look.purple
    }
    Box(
        modifier.heightIn(min = AronLook.ButtonHeight).clip(RoundedCornerShape(AronLook.ButtonRadius)).background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = lookStyle(18.sp, bold = true, color = Color.White, align = TextAlign.Center), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

/** The outlined button for the grey page (the grey [AronSecondaryButton] vanishes on `#E8E8E8`): white fill, purple hairline and label. */
@Composable
fun AronOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.ButtonRadius)
    Box(
        modifier.heightIn(min = AronLook.ButtonHeight).alpha(if (enabled) 1f else 0.5f).clip(shape).background(look.card).border(1.5.dp, look.purple, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = lookStyle(17.sp, semibold = true, color = look.purple, align = TextAlign.Center), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}

/** A text link button in `aron.purple` (underlined, 48 dp target). */
@Composable
fun AronTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalAronLook.current
    Box(
        modifier.heightIn(min = AronLook.Touch).widthIn(min = AronLook.Touch).alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = lookStyle(16.sp, medium = true, color = look.purple).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
    }
}

/**
 * A Yes / No pill pair (Visit Query, Joint Call): [value] true = Yes chosen (purple), false = No chosen (red), null = neither (both
 * white with a grey hairline). Each pill is a radio button for TalkBack; 48 dp targets.
 */
@Composable
fun AronYesNo(yes: String, no: String, value: Boolean?, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalAronLook.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(true to yes, false to no).forEach { (answer, label) ->
            val on = value == answer
            val fill = when {
                !on -> look.card  // white with a hairline: visible on the grey page
                answer -> look.purple
                else -> look.red
            }
            val pill = RoundedCornerShape(50)
            Box(
                Modifier.weight(1f).heightIn(min = AronLook.Touch).alpha(if (enabled) 1f else 0.5f).clip(pill).background(fill)
                    .then(if (on) Modifier else Modifier.border(1.dp, look.underline, pill))
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { onChange(answer) }),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = lookStyle(16.sp, semibold = true, color = if (on) Color.White else look.secondaryText, align = TextAlign.Center)) }
        }
    }
}

/**
 * Rating stars (SR performance assessment): [max] stars, the first [value] filled gold; tapping star n sets n. Each star is a 48 dp
 * target labelled by [starLabel] (for example "৩ / ৫").
 */
@Composable
fun AronRatingStars(value: Int, onChange: (Int) -> Unit, starLabel: (Int) -> String, modifier: Modifier = Modifier, max: Int = 5, enabled: Boolean = true) {
    val look = LocalAronLook.current
    Row(modifier) {
        for (n in 1..max) {
            Box(
                Modifier.size(AronLook.Touch).selectable(selected = n == value, enabled = enabled, role = Role.RadioButton, onClick = { onChange(n) })
                    .semantics { contentDescription = starLabel(n) },
                contentAlignment = Alignment.Center,
            ) { GlyphCanvas(Modifier.size(30.dp)) { star(if (n <= value) look.gold else look.disabledFill, look.underline) } }
        }
    }
}

/**
 * The press-and-hold confirm (Sales submit, memo Mark paid) in the parity look: the gradient button with a white progress bar along its
 * bottom edge while held for [holdMillis]; lifting early cancels. TalkBack users confirm with the long-press action (no timed hold).
 */
@Composable
fun AronHoldButton(text: String, onConfirmed: () -> Unit, modifier: Modifier = Modifier, holdMillis: Int = 1200, enabled: Boolean = true) {
    val look = LocalAronLook.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val confirm by rememberUpdatedState(onConfirmed)
    val live by rememberUpdatedState(enabled)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = AronLook.ButtonHeight)
            .clip(RoundedCornerShape(AronLook.ButtonRadius))
            .background(if (enabled) look.barBrush() else Brush.linearGradient(listOf(look.primaryDisabled, look.primaryDisabled)))
            .drawBehind { drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 4.dp.toPx()), size = Size(size.width * progress.value, 4.dp.toPx())) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onLongClick(label = text) { if (live) { confirm(); true } else false }
            }
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMillis))
                        if (live) confirm()
                        progress.snapTo(0f)
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        if (progress.value < 1f) { job.cancel(); scope.launch { progress.snapTo(0f) } }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = lookStyle(18.sp, bold = true, color = Color.White, align = TextAlign.Center), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
}

/** A bottom sheet in the parity look: white, 12 dp top corners, a purple bold [title], then [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AronSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val look = LocalAronLook.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = look.card,
        shape = RoundedCornerShape(topStart = AronLook.CardRadius, topEnd = AronLook.CardRadius),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(title, style = lookStyle(20.sp, bold = true, color = look.purple), modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * The language switch of the logged-out screens: two segments ([bangla] / [english]) on a white pill, the chosen one the gradient.
 * [banglaSelected] is the current state; [onSelect] gets true for Bangla.
 */
@Composable
fun AronLanguageToggle(bangla: String, english: String, banglaSelected: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.clip(RoundedCornerShape(50)).background(look.card).padding(3.dp)) {
        listOf(true to bangla, false to english).forEach { (isBn, label) ->
            val on = isBn == banglaSelected
            Box(
                Modifier.heightIn(min = AronLook.Touch).widthIn(min = 56.dp).clip(RoundedCornerShape(50))
                    .then(if (on) Modifier.background(look.barBrush()) else Modifier)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(isBn) }).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = lookStyle(15.sp, semibold = true, color = if (on) look.onBar else look.purple)) }
        }
    }
}

/** The Sunlight toggle: a 48 dp round button with a sun, filled purple while [on]; [label] is its TalkBack name. */
@Composable
fun AronSunToggle(on: Boolean, onToggle: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, stateOn: String? = null, stateOff: String? = null) {
    val look = LocalAronLook.current
    Box(
        modifier.size(AronLook.Touch).clip(CircleShape).background(if (on) look.purple else look.card)
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .semantics {
                contentDescription = label
                val state = if (on) stateOn else stateOff
                if (state != null) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) { GlyphCanvas(Modifier.size(26.dp)) { sun(if (on) Color.White else look.purple) } }
}

/**
 * The quiet loading state (no spinner, no animation: battery): a white card with [lines] grey bars. Give it the caller's "loading"
 * [label] for TalkBack.
 */
@Composable
fun AronLoadingCard(label: String, modifier: Modifier = Modifier, lines: Int = 3) {
    val look = LocalAronLook.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card).padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(lines) { i ->
            Box(Modifier.fillMaxWidth(if (i == lines - 1) 0.6f else 1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(look.skeleton))
        }
    }
}

/**
 * The loading state of a whole screen whose local data is still being read (no spinner, no animation: battery): the
 * [AronLoadingCard] on the grey page, announced as "Loading" / "লোড হচ্ছে". Use it instead of drawing nothing for the first frame.
 */
@Composable
fun AronLoadingScreen(modifier: Modifier = Modifier, label: String = androidx.compose.ui.res.stringResource(com.aktcl.aron.core.ui.R.string.core_ui_loading)) {
    Box(modifier.fillMaxSize().background(LocalAronLook.current.page).padding(12.dp)) { AronLoadingCard(label) }
}

/** A section header on the grey page: purple semibold 17 sp with a short red rule under it (as the Attendance identity rule). */
@Composable
fun AronSectionHeader(text: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
        Text(text, style = lookStyle(17.sp, semibold = true, color = look.purple), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(32.dp).height(2.dp).background(look.red))
    }
}

/**
 * A date field in the [AronField] look: the [label] above, the [value] (or grey [placeholder]) over the underline with a purple
 * calendar at the end; [onClick] opens the caller's date picker. Dates come formatted and localised from the caller.
 */
@Composable
fun AronDateField(value: String?, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, placeholder: String? = null, errorText: String? = null) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth()) {
        Text(label, style = lookStyle(14.sp, color = look.inkMuted))
        Row(
            Modifier.fillMaxWidth().heightIn(min = AronLook.Touch).clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label + (value?.let { ", $it" } ?: "") }
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawLine(if (errorText != null) look.red else look.underline, androidx.compose.ui.geometry.Offset(0f, size.height - stroke), androidx.compose.ui.geometry.Offset(size.width, size.height - stroke), stroke)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value ?: placeholder.orEmpty(), style = lookStyle(18.sp, color = if (value == null) look.hint else look.ink), modifier = Modifier.weight(1f))
            GlyphCanvas(Modifier.size(24.dp)) { calendar(look.purple) }
        }
        if (errorText != null) AronErrorText(errorText)
    }
}

/**
 * A read-only pack row (Team Stock detail, Price compliance): the packshot, the SKU code in purple medium and an optional [subtitle],
 * then [values] at the end in black bold (one or two figures, already localised). No stepper.
 */
@Composable
fun AronPackRow(skuId: String, skuCode: String, values: List<String>, modifier: Modifier = Modifier, subtitle: String? = null, category: PackCategory = PackCategory.Cigarette, packshotDescription: String? = null) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Row(
        modifier.fillMaxWidth().clip(shape).background(look.card).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PackshotImage(skuId, Modifier.size(40.dp, 64.dp), packshotDescription, category)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(skuCode, style = lookStyle(17.sp, medium = true, color = look.purple))
            if (subtitle != null) Text(subtitle, style = lookStyle(13.sp, color = look.inkMuted))
        }
        values.forEach { Text(it, style = lookStyle(17.sp, bold = true, color = look.ink, align = TextAlign.End), modifier = Modifier.padding(start = 12.dp)) }
    }
}
