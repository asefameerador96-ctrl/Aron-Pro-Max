package com.aktcl.aron.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/**
 * The frame of every screen (screens-sr s0): the page background, the top bar, the content, and the bottom bar as a
 * LAYOUT SLOT below the content (never an overlay: rows never scroll under it, nothing shows through it). The bars pad
 * themselves with the safe-drawing insets, so the frame works whether or not the activity draws edge to edge.
 */
@Composable
fun AronScreen(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().aronPageBackground()) {
        topBar()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        bottomBar()
    }
}

/**
 * Top bar (android-glass s6.7): the `TopBar` recipe (`surface.glass.strong` 96 percent on tier B, solid on C and in
 * sunlight, a 1 dp bottom edge), 64 dp plus the status inset, a 48 dp back button, the title in `type.title` up to two
 * lines, an optional caption subtitle, and a trailing slot for the status chip, the sun switch and the gear.
 */
@Composable
fun AronTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalAronColors.current
    GlassSurface(modifier = modifier.fillMaxWidth(), kind = GlassKind.TopBar) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = AronTokens.Size.TopBar)
                .padding(end = AronTokens.Space.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                AronIconButton(AronIcons.ArrowBack, stringResource(R.string.core_ui_back), onBack)
            } else {
                Spacer(Modifier.width(AronTokens.Space.L))
            }
            Column(Modifier.weight(1f).padding(vertical = AronTokens.Space.S)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

/** A 48 dp icon-only button; [contentDescription] is its only text, so it is required. */
@Composable
fun AronIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalAronColors.current
    Box(
        modifier
            .size(MinTouch)
            .aronFocusRing(CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) c.textPrimary else c.textDisabled, modifier = Modifier.size(AronTokens.Size.Icon))
    }
}

/**
 * The 56 dp icon-only secondary of a bar that also carries a summary: the opaque secondary recipe (`surface.solid.raised`,
 * 1 dp divider; tier C and sunlight `surface.solid` with a 1.5 dp accent border), the icon in `accent.text`, named by [text].
 */
@Composable
fun AronBarIconButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalAronColors.current
    val tier = LocalGlassTier.current
    val flat = tier == GlassTier.C || c.sunlight
    val fill = if (!enabled) c.stateDisabledFill else if (flat) c.surfaceSolid else c.surfaceSolidRaised
    val border = if (!enabled) null else if (flat) c.accent else c.borderDivider
    val borderWidth = if (c.sunlight) AronTokens.Stroke.SunlightHairline else if (flat) AronTokens.Stroke.Strong else AronTokens.Stroke.Hairline
    Box(
        modifier
            .size(AronTokens.Touch.Primary)
            .aronFocusRing(CircleShape)
            .drawBehind {
                drawCircle(fill)
                border?.let { drawCircle(it, radius = size.minDimension / 2f - borderWidth.toPx() / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(borderWidth.toPx())) }
            }
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) c.accentText else c.stateDisabledLabel, modifier = Modifier.size(AronTokens.Size.Icon))
    }
}

/** One action of the bottom bar. */
@Immutable
data class AronBarAction(
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
    val busy: Boolean = false,
)

/**
 * The action bar (android-glass s6.7): `surface.glass.field` 96 percent with a top edge, 80 dp (12 + 56 + 12) plus the
 * navigation inset, riding above the keyboard. Slots: an optional summary at the start (caption over numeral), a secondary
 * action (40 percent) and the primary (60 percent). A disabled primary shows [reasonWhenDisabled] as a caption above the
 * buttons; when a reason is given the 104 dp height is reserved so the bar never animates. From font scale 1.3 the bar is
 * one column: the summary on one line above a full-width primary, the secondary as a text button under it.
 */
@Composable
fun AronBottomBar(
    summaryLabel: String? = null,
    summaryValue: String? = null,
    secondary: AronBarAction? = null,
    primary: AronBarAction,
    reasonWhenDisabled: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalAronColors.current
    val stacked = LocalDensity.current.fontScale >= AronTokens.Size.ReflowFontScale
    val reserve = if (reasonWhenDisabled != null) AronTokens.Size.BottomBarWithReason else AronTokens.Size.BottomBar
    GlassSurface(modifier = modifier.fillMaxWidth(), kind = GlassKind.ActionBar) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .imePadding()
                .heightIn(min = reserve)
                .padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            if (reasonWhenDisabled != null && !primary.enabled) {
                Text(reasonWhenDisabled, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            val hasSummary = summaryLabel != null || summaryValue != null
            if (stacked) {
                if (hasSummary) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        if (summaryLabel != null) Text(summaryLabel, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f, fill = false))
                        if (summaryValue != null) MoneyText(summaryValue, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f, fill = false))
                    }
                }
                AronPrimaryButton(primary.text, primary.onClick, enabled = primary.enabled, icon = primary.icon, busy = primary.busy)
                if (secondary != null) AronTextButton(secondary.text, secondary.onClick, Modifier.fillMaxWidth(), icon = secondary.icon, enabled = secondary.enabled && !secondary.busy)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    if (hasSummary) {
                        // the summary is capped at `size.barSummaryMax` (96 dp; MoneyText shrinks to its floor) so the primary keeps
                        // `size.barPrimaryMin` (144 dp) on the 360 dp phone: 328 - 96 - 12 - 56 - 12 = 152 (android-glass s6.7)
                        Column(Modifier.widthIn(max = AronTokens.Size.BarSummaryMax)) {
                            if (summaryLabel != null) Text(summaryLabel, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2)
                            if (summaryValue != null) MoneyText(summaryValue, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    if (secondary != null && !hasSummary) {
                        AronSecondaryButton(secondary.text, secondary.onClick, Modifier.weight(0.4f), enabled = secondary.enabled, icon = secondary.icon, busy = secondary.busy, tall = true)
                    } else if (secondary != null && secondary.icon != null) {
                        // beside a summary there is no room for a labelled secondary on 360 dp: an icon-only 56 dp button named by its text
                        AronBarIconButton(secondary.icon, secondary.text, secondary.onClick, enabled = secondary.enabled && !secondary.busy)
                    }
                    AronPrimaryButton(
                        primary.text, primary.onClick,
                        Modifier.weight(if (secondary != null && !hasSummary) 0.6f else 1f).widthIn(min = AronTokens.Size.BarPrimaryMin),
                        enabled = primary.enabled, icon = primary.icon, busy = primary.busy,
                        horizontalPadding = if (hasSummary) AronTokens.Space.L else AronTokens.Space.Xl,
                    )
                }
                // a secondary without an icon cannot sit beside a summary: it becomes a 48 dp text button under the primary (android-glass s6.7)
                if (secondary != null && hasSummary && secondary.icon == null) {
                    AronTextButton(secondary.text, secondary.onClick, Modifier.fillMaxWidth(), enabled = secondary.enabled && !secondary.busy)
                }
            }
        }
    }
}
