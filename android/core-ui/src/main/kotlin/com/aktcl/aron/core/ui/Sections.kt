package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
 * `accent.onContainer`; the others are `text.secondary`. Segment widths follow the label length, not equal thirds.
 * Tabs use the same control with [role] `Role.Tab`.
 */
@Composable
fun AronSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, role: Role = Role.RadioButton) {
    val c = LocalAronColors.current
    val track = c.surfaceSolidRaised; val divider = c.borderDivider
    val thumb = c.accentContainer; val ring = c.accent
    val ringWidth = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Strong
    val hairline = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val outer = RoundedCornerShape(AronTokens.Radius.Chip + AronTokens.Space.Xs)
    val inner = RoundedCornerShape(AronTokens.Radius.Chip)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .drawBehind {
                val r = CornerRadius(outer.topStart.toPx(size, this))
                drawRoundRect(track, cornerRadius = r)
                val w = hairline.toPx()
                drawRoundRect(divider, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
            }
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight((label.length + 4).toFloat())
                    .heightIn(min = MinTouch)   // the hit area is the full 48 dp; the thumb is drawn 4 dp inside it (40 dp visual)
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
                    .selectable(selected = on, role = role, onClick = { onSelect(i) })
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
    }
}
