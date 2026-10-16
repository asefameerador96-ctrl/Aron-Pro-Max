package com.aktcl.aron.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * The one primary action per screen (android-glass s6.1): 56 dp pill, label 18 sp bold (`titleMedium`: the glare exception
 * of docs/32 s2a item 4 depends on it), full width. Tiers A and B: a vertical `accent.hi` to `accent.fill` gradient, a 1 dp
 * white 35 percent top highlight and an accent-tinted spot shadow (6 dp, 2 dp pressed); tier C: flat `accent.hi`; sunlight:
 * flat `#002A73` (that is `accent.hi` there). Pressed: `accent.pressed` and scale 0.97 over 90 ms. Disabled:
 * `state.disabled.fill` and `.label`, no shadow. [busy] keeps the label, swaps the icon for a 20 dp arc and ignores taps.
 * Drawn with one `drawWithCache` and one clickable, no Material3 `Button` (android-glass s1 item 10).
 */
@Composable
fun AronPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    busy: Boolean = false,
) {
    val c = LocalAronColors.current
    val tier = LocalGlassTier.current
    val flat = tier == GlassTier.C || c.sunlight
    val active = enabled && !busy
    val shape = AronTokens.ButtonShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shadow = if (flat || !enabled) Modifier else Modifier.shadow(
        if (pressed) 2.dp else 6.dp, shape, clip = false, ambientColor = c.accent, spotColor = c.accent,
    )
    val hi = c.accentHi; val fill = c.accentFill; val pressedFill = c.accentPressed
    val disabledFill = c.stateDisabledFill
    val busyText = stringResource(R.string.core_ui_busy)
    val paint = Modifier.drawWithCache {
        val r = CornerRadius(size.height / 2f)
        val body: Brush = when {
            !enabled -> Brush.verticalGradient(listOf(disabledFill, disabledFill))
            pressed -> Brush.verticalGradient(listOf(pressedFill, pressedFill))
            flat -> Brush.verticalGradient(listOf(hi, hi))
            else -> Brush.verticalGradient(listOf(hi, fill))
        }
        val highlight = if (enabled && !flat && !pressed) Color.White.copy(alpha = AronTokens.Alpha.ButtonHighlight) else null
        val hairline = AronTokens.Stroke.Hairline.toPx()
        val inset = size.height * 0.28f
        onDrawBehind {
            drawRoundRect(body, cornerRadius = r)
            highlight?.let { drawLine(it, Offset(inset, hairline / 2 + hairline), Offset(size.width - inset, hairline / 2 + hairline), strokeWidth = hairline, cap = StrokeCap.Round) }
        }
    }
    val labelColor = if (enabled) c.textOnAccent else c.stateDisabledLabel
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = AronTokens.Touch.Primary)
            .aronFocusRing(shape)
            .then(shadow)
            .pressScale(interaction, AronTokens.Motion.ButtonPressScale)
            .then(paint)
            .clip(shape)
            .semantics { if (busy) stateDescription = busyText }
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = AronTokens.Space.Xl, vertical = AronTokens.Space.M),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            BusyArc(labelColor, AronTokens.Size.IconSmall)
            androidx.compose.foundation.layout.Spacer(Modifier.size(AronTokens.Space.S))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(AronTokens.Size.Icon))
            androidx.compose.foundation.layout.Spacer(Modifier.size(AronTokens.Space.S))
        }
        Text(text, color = labelColor, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Secondary action (android-glass s6.2): an OPAQUE `surface.solid.raised` fill with a 1 dp `border.divider`, label
 * `type.bodyStrong` in `accent.text`; tier C and sunlight: `surface.solid` with a 1.5 dp (2 dp sunlight) `accent` border
 * so it never reads as disabled grey. 48 dp, or 56 dp beside a primary in a bar ([tall]). Pressed: `state.pressed` overlay
 * and scale 0.97. Disabled: `state.disabled.fill` and `.label`, border removed.
 */
@Composable
fun AronSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    busy: Boolean = false,
    tall: Boolean = false,
) {
    val c = LocalAronColors.current
    val tier = LocalGlassTier.current
    val flat = tier == GlassTier.C || c.sunlight
    val active = enabled && !busy
    val shape = AronTokens.ButtonShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (!enabled) c.stateDisabledFill else if (flat) c.surfaceSolid else c.surfaceSolidRaised
    val border = if (!enabled) null else if (flat) c.accent else c.borderDivider
    val borderWidth = if (c.sunlight) AronTokens.Stroke.SunlightHairline else if (flat) AronTokens.Stroke.Strong else AronTokens.Stroke.Hairline
    val overlay = c.statePressed
    val busyText = stringResource(R.string.core_ui_busy)
    val paint = Modifier.drawWithCache {
        val r = CornerRadius(size.height / 2f)
        val w = borderWidth.toPx()
        onDrawBehind {
            drawRoundRect(fill, cornerRadius = r)
            if (pressed) drawRoundRect(overlay, cornerRadius = r)
            border?.let { drawRoundRect(it, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w)) }
        }
    }
    val labelColor = if (enabled) c.accentText else c.stateDisabledLabel
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (tall) AronTokens.Touch.Primary else MinTouch)
            .aronFocusRing(shape)
            .pressScale(interaction, AronTokens.Motion.ButtonPressScale)
            .then(paint)
            .clip(shape)
            .semantics { if (busy) stateDescription = busyText }
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            BusyArc(labelColor, AronTokens.Size.IconSmall)
            androidx.compose.foundation.layout.Spacer(Modifier.size(AronTokens.Space.S))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(AronTokens.Size.Icon))
            androidx.compose.foundation.layout.Spacer(Modifier.size(AronTokens.Space.S))
        }
        Text(text, color = labelColor, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Text button (dialogs, banners, links): no fill, `accent.text` label, 48 dp target, `state.pressed` overlay. Inside a
 * banner pass [color] (the banner's `*.onContainer`) and [underline].
 */
@Composable
fun AronTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = LocalAronColors.current.accentText,
    underline: Boolean = false,
) {
    val c = LocalAronColors.current
    val shape = AronTokens.ButtonShape
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val overlay = c.statePressed
    val labelColor = if (enabled) color else c.stateDisabledLabel
    Row(
        modifier
            .sizeIn(minWidth = MinTouch, minHeight = MinTouch)
            .aronFocusRing(shape)
            .drawWithCache { val r = CornerRadius(size.height / 2f); onDrawBehind { if (pressed) drawRoundRect(overlay, cornerRadius = r) } }
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(AronTokens.Size.IconSmall))
            androidx.compose.foundation.layout.Spacer(Modifier.size(AronTokens.Space.S))
        }
        Text(
            text, color = labelColor, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }
}

/** A 20 dp three-quarter arc that turns while busy (static under reduced motion). Functional, not decorative. */
@Composable
internal fun BusyArc(color: Color, size: androidx.compose.ui.unit.Dp) {
    val reduced = LocalReducedMotion.current
    val angle: Float = if (reduced) 0f else {
        val t = rememberInfiniteTransition()
        val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(AronTokens.Motion.SyncTurnMillis, easing = LinearEasing), RepeatMode.Restart))
        a
    }
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = angle }) {
        val w = 2.dp.toPx()
        drawArc(color, startAngle = 0f, sweepAngle = 270f, useCenter = false, topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Kept for callers that lay a label beside an icon in a bar: the minimum width of a text target. */
internal fun Modifier.minTextTarget(): Modifier = widthIn(min = MinTouch).heightIn(min = MinTouch)
