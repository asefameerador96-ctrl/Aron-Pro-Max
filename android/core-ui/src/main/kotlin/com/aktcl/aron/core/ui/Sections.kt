package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A group label on the bare page ("আজকের কাজ", "অন্যান্য"): `type.label` in `text.secondary`, 20 dp above, 8 dp below; a heading for TalkBack. */
@Composable
fun AronSectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = c.textSecondary,
        modifier = modifier.fillMaxWidth().padding(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, top = 20.dp, bottom = AronTokens.Space.S).semantics { heading() },
    )
}

/**
 * Segmented control (android-glass s6.15): a 48 dp track (`surface.solid.raised`, 1 dp divider, `radius.chip`); the
 * selected segment is an `accent.container` thumb with a 1.5 dp `accent` ring, a 16 dp check and its label in
 * `accent.onContainer`; the others are `text.secondary`. Segment widths are weighted by the MEASURED label width plus
 * 8 dp (one `TextMeasurer` pass per label set, remembered), not equal thirds. From font scale 1.5 (`fontScale.vertical`)
 * the control is a vertical stack of 48 dp rows with the same thumb recipe. Tabs use the same control with [role] `Role.Tab`.
 * The thumb does not animate between segments yet (a follow-up: offset and width in the draw phase over `motion.base`).
 */
@Composable
fun AronSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, role: Role = Role.RadioButton) {
    val c = LocalAronColors.current
    val track = c.surfaceSolidRaised; val divider = c.borderDivider
    val hairline = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val outer = RoundedCornerShape(AronTokens.Radius.Chip + AronTokens.Space.Xs)
    val vertical = LocalDensity.current.fontScale >= AronTokens.Size.VerticalFontScale
    val labelStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // the weight of a segment: its label's measured width (one line) plus 8 dp, plus the chrome EVERY segment reserves (12 dp
    // padding each side and the 16 dp check with its 4 dp gap) so selecting a segment never changes the widths and
    // "ভিজিট হয়েছে ৬" gets the room it needs
    val weights = remember(options, labelStyle, density) {
        val chrome = with(density) { (AronTokens.Space.S + AronTokens.Space.M * 2 + AronTokens.Space.L + AronTokens.Space.Xs).toPx() }
        options.map { (measurer.measure(it, labelStyle, maxLines = 1).size.width + chrome).coerceAtLeast(1f) }
    }
    val trackPaint = Modifier.drawBehind {
        val r = CornerRadius(outer.topStart.toPx(size, this))
        drawRoundRect(track, cornerRadius = r)
        val w = hairline.toPx()
        drawRoundRect(divider, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
    }
    if (vertical) {
        Column(modifier.fillMaxWidth().then(trackPaint).selectableGroup()) {
            options.forEachIndexed { i, label -> Segment(label, i == selected, role, outer, Modifier.fillMaxWidth()) { onSelect(i) } }
        }
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = MinTouch).then(trackPaint).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, label -> Segment(label, i == selected, role, outer, Modifier.weight(weights[i])) { onSelect(i) } }
        }
    }
}

/** One segment: the full 48 dp hit area; the thumb is drawn 4 dp inside it (40 dp visual) with the ring and the check. */
@Composable
private fun Segment(label: String, on: Boolean, role: Role, outer: RoundedCornerShape, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalAronColors.current
    val thumb = c.accentContainer; val ring = c.accent
    val ringWidth = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Strong
    val inner = RoundedCornerShape(AronTokens.Radius.Chip)
    Box(
        modifier
            .heightIn(min = MinTouch)
            .aronFocusRing(inner)
            .drawBehind {
                if (on) {
                    val inset = AronTokens.Space.Xs.toPx()
                    val box = Size(size.width - 2 * inset, size.height - 2 * inset)
                    val r = CornerRadius(inner.topStart.toPx(box, this))
                    drawRoundRect(thumb, Offset(inset, inset), box, cornerRadius = r)
                    val w = ringWidth.toPx()
                    drawRoundRect(ring, Offset(inset + w / 2, inset + w / 2), Size(box.width - w, box.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
                }
            }
            .clip(outer)
            .selectable(selected = on, role = role, onClick = onClick)
            .padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (on) {
                Icon(AronIcons.Check, contentDescription = null, tint = c.accentOnContainer, modifier = Modifier.size(AronTokens.Space.L))
                Spacer(Modifier.width(AronTokens.Space.Xs))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.accentOnContainer else c.textSecondary, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
