package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.GlassKind
import com.aktcl.aron.core.ui.GlassSurface
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalGlassTier
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

/** Money in the current language's digits, `1,234.50 ৳` (docs/24 s7.1). */
@Composable
internal fun outletMoney(mtk: Long): String = Formats.money(mtk, if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN)

/** A dock chip (android-glass s6.17): 40 dp visual in a 48 dp hit area, opaque `surface.solid.raised`, selected = `accent.container` with the ring and a check. */
@Composable
internal fun DockChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = LocalAronColors.current
    val fill = if (selected) c.accentContainer else c.surfaceSolidRaised
    val edge = if (selected) c.accent else c.borderDivider
    val ink = if (selected) c.accentOnContainer else c.textPrimary
    val stroke = if (c.sunlight) AronTokens.Stroke.SunlightHairline else if (selected) AronTokens.Stroke.Strong else AronTokens.Stroke.Hairline
    Box(
        modifier
            .heightIn(min = AronTokens.Touch.Min)
            .clip(RoundedCornerShape(AronTokens.Radius.Chip + AronTokens.Space.Xs))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = AronTokens.Space.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .drawWithCache {
                    // the ring and the stroke style are built once per size, not on every draw of a scrolling dock
                    val r = CornerRadius(AronTokens.Radius.Chip.toPx()); val w = stroke.toPx(); val edgeStyle = Stroke(w)
                    val inner = CornerRadius(r.x - w / 2); val edgeSize = Size(size.width - w, size.height - w); val edgeAt = Offset(w / 2, w / 2)
                    onDrawBehind {
                        drawRoundRect(fill, cornerRadius = r)
                        drawRoundRect(edge, edgeAt, edgeSize, inner, style = edgeStyle)
                    }
                }
                .padding(horizontal = AronTokens.Space.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs),
        ) {
            if (selected) Icon(AronIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Space.L))
            else if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Size.IconSmall))
            Text(label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
        }
    }
}

/** A small opaque status pill (word plus icon, never colour alone): `*.container` fill, `*.onContainer` ink, `radius.chip`. */
@Composable
internal fun StatusPill(label: String, icon: ImageVector, container: Color, ink: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = AronTokens.Size.Chip)
            .drawBehind { drawRoundRect(container, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) }
            .padding(horizontal = 10.dp, vertical = AronTokens.Space.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Space.L))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 2)
    }
}

/** The shape of a flat row by its place in the group: `radius.card` on the outer corners of the first and last row. */
internal fun rowShape(position: RowPosition): RoundedCornerShape {
    val r = AronTokens.Radius.Card
    return when (position) {
        RowPosition.Only -> RoundedCornerShape(r)
        RowPosition.First -> RoundedCornerShape(topStart = r, topEnd = r)
        RowPosition.Last -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        RowPosition.Middle -> RoundedCornerShape(0.dp)
    }
}

/** The flat row paint of a long list (android-glass s6.3): `surface.glass.field`, `radius.card` on the first and last row. */
@Composable
internal fun Modifier.flatRow(position: RowPosition): Modifier {
    val c = LocalAronColors.current
    val solid = LocalGlassTier.current == GlassTier.C || c.sunlight
    return background(if (solid) c.surfaceSolid else c.surfaceField, rowShape(position))
}

/** An error line under a field or a form (android-glass s6.12): a 16 dp warning glyph and the words in `danger.onContainer`. */
@Composable
internal fun FieldError(text: String, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Icon(AronIcons.Warning, contentDescription = null, tint = c.dangerOnContainer, modifier = Modifier.size(AronTokens.Space.L))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.dangerOnContainer)
    }
}

/**
 * A radio row (android-glass s6.14): 56 dp, `radius.chip`, `surface.solid.raised` with a 1 dp `border.input`; selected =
 * `accent.container` fill, a 2 dp `accent` edge, a check in the dot. The whole row is the target, with radio semantics.
 */
@Composable
internal fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val fill = if (selected) c.accentContainer else c.surfaceSolidRaised
    val edge = if (selected) c.accent else c.borderInput
    val edgeWidth = if (selected || c.sunlight) AronTokens.Stroke.Focus else AronTokens.Stroke.Hairline
    val dot = if (selected) c.accent else c.borderInput
    val shape = RoundedCornerShape(AronTokens.Radius.Chip)
    Box(
        modifier
            .fillMaxWidth()
            .drawWithCache {
                val r = CornerRadius(AronTokens.Radius.Chip.toPx()); val w = edgeWidth.toPx(); val edgeStyle = Stroke(w)
                val inner = CornerRadius(r.x - w / 2); val edgeSize = Size(size.width - w, size.height - w); val edgeAt = Offset(w / 2, w / 2)
                onDrawBehind {
                    drawRoundRect(fill, cornerRadius = r)
                    drawRoundRect(edge, edgeAt, edgeSize, inner, style = edgeStyle)
                }
            }
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        AronListRow(
            title = label,
            leading = {
                Box(
                    Modifier.size(22.dp).drawBehind {
                        val w = AronTokens.Stroke.Focus.toPx()
                        drawCircle(dot, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
                        if (selected) drawCircle(dot, radius = size.minDimension / 2 - w)
                    },
                    contentAlignment = Alignment.Center,
                ) { if (selected) Icon(AronIcons.Check, contentDescription = null, tint = c.textOnAccent, modifier = Modifier.size(14.dp)) }
            },
        )
    }
}

/** One action of the tagged bar: the test tag names the button for the host's tests. */
internal class BarAction(val text: String, val onClick: () -> Unit, val tag: String? = null, val enabled: Boolean = true, val icon: ImageVector? = null)

/**
 * The action bar of the outlet screens (android-glass s6.7): the `ActionBar` recipe, 80 dp (104 with a reason), the
 * secondary at 40 percent and the primary at 60. Built from the kit's surface and buttons because the host's tests find
 * Refresh, Force Sale, Retry and Save by their test tags, which `AronBottomBar` does not carry.
 */
@Composable
internal fun OutletBar(primary: BarAction, secondary: BarAction? = null, reason: String? = null, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    GlassSurface(modifier = modifier.fillMaxWidth(), kind = GlassKind.ActionBar) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .imePadding()
                .heightIn(min = if (reason != null) AronTokens.Size.BottomBarWithReason else AronTokens.Size.BottomBar)
                .padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically) {
                if (secondary != null) {
                    AronSecondaryButton(
                        secondary.text, secondary.onClick, Modifier.weight(0.4f).then(secondary.tag?.let { Modifier.testTagged(it) } ?: Modifier),
                        enabled = secondary.enabled, icon = secondary.icon, tall = true,
                    )
                }
                AronPrimaryButton(
                    primary.text, primary.onClick, Modifier.weight(if (secondary != null) 0.6f else 1f).then(primary.tag?.let { Modifier.testTagged(it) } ?: Modifier),
                    enabled = primary.enabled, icon = primary.icon,
                )
            }
        }
    }
}

internal fun Modifier.testTagged(tag: String): Modifier = this.then(Modifier.testTag(tag))
