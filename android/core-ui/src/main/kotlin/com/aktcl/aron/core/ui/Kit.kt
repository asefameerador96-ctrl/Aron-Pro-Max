package com.aktcl.aron.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Minimum touch target everywhere in the kit (docs/24 s5.6: 48 dp). */
val MinTouch = AronTokens.Touch.Min

/** The outline of stepper buttons: 1 dp, 2 dp in sunlight; the disabled outline uses the disabled label colour. */
@Composable
internal fun outlineStroke(enabled: Boolean = true): BorderStroke {
    val c = LocalAronColors.current
    return BorderStroke(if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline, if (enabled) c.accent else c.stateDisabledLabel)
}

/** Where a flat row sits in its list, for the `radius.card` rounding of the first and last row (android-glass s6.3). */
enum class RowPosition { First, Middle, Last, Only }

/**
 * A list row (android-glass s6.3): min 64 dp, title `type.bodyStrong` up to 2 lines, caption `type.caption` in
 * `text.secondary`, optional [leading] slot (thumbnail, icon or the reserved programme-dot slot), a trailing value or
 * [trailingContent] (chevron, check, chip). Transparent inside a card; [flat] paints the flat `surface.glass.field` row of
 * a long list, rounded by [position]. [divider] draws the 16 dp inset `border.divider` under the row. The whole row is
 * the touch target and wraps at large fonts. The older positional call `(title, modifier, subtitle, trailing, onClick)` still works.
 */
@Composable
fun AronListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    position: RowPosition = RowPosition.Only,
    divider: Boolean = false,
    flat: Boolean = false,
) {
    val c = LocalAronColors.current
    val tier = LocalGlassTier.current
    val solid = tier == GlassTier.C || c.sunlight
    val radius = AronTokens.Radius.Card
    val shape = when (position) {
        RowPosition.Only -> RoundedCornerShape(radius)
        RowPosition.First -> RoundedCornerShape(topStart = radius, topEnd = radius)
        RowPosition.Last -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
        RowPosition.Middle -> RoundedCornerShape(0.dp)
    }
    val fill = if (solid) c.surfaceSolid else c.surfaceField
    val dividerColor = c.borderDivider
    val dividerWidth = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val inset = AronTokens.Space.L
    val paint = Modifier.drawBehind {
        if (flat) {
            val r = radius.toPx()
            val top = if (position == RowPosition.First || position == RowPosition.Only) r else 0f
            val bottom = if (position == RowPosition.Last || position == RowPosition.Only) r else 0f
            val path = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(top), CornerRadius(top), CornerRadius(bottom), CornerRadius(bottom)))
            }
            drawPath(path, fill)
        }
        if (divider) {
            val w = dividerWidth.toPx()
            drawRect(dividerColor, Offset(inset.toPx(), size.height - w), Size(size.width - inset.toPx(), w))
        }
    }
    val click = if (onClick != null) Modifier.clip(shape).clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier = modifier.fillMaxWidth().aronFocusRing(shape).then(paint).then(click).heightIn(min = 64.dp).padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        if (trailingContent != null) trailingContent()
    }
}

enum class BannerKind { Info, Warning, Error, Success, Offline }

/**
 * A status banner (android-glass s6.10), in flow at the top of the content: opaque `*.container` fill, `*.onContainer`
 * words, its kind's 24 dp icon (status never rides on colour alone), `radius.card`, min 56 dp, a 1 dp edge of the status
 * colour at 20 percent, no shadow. Optional [title] in `type.bodyStrong` and an underlined text [actionLabel] at the end
 * (under the text from font scale 1.5). Not interactive unless [onClick] is given.
 */
@Composable
fun AronBanner(
    text: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.Info,
    onClick: (() -> Unit)? = null,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    val (bg, fg, edge, icon) = when (kind) {
        BannerKind.Info -> BannerLook(c.accentContainer, c.accentOnContainer, c.accent, AronIcons.Info)
        BannerKind.Success -> BannerLook(c.successContainer, c.successOnContainer, c.success, AronIcons.CheckCircle)
        BannerKind.Warning -> BannerLook(c.warningContainer, c.warningOnContainer, c.warning, AronIcons.Warning)
        BannerKind.Error -> BannerLook(c.dangerContainer, c.dangerOnContainer, c.danger, AronIcons.Warning)
        BannerKind.Offline -> BannerLook(c.offlineContainer, c.offlineOnContainer, c.offline, AronIcons.CloudOff)
    }
    val shape = RoundedCornerShape(AronTokens.Radius.Card)
    val stroke = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val edgeColor = edge.copy(alpha = AronTokens.Alpha.BannerEdge)
    // the action sits at the end only when the banner is wide enough for Bangla beside it; a 360 dp phone stacks it under the text
    val stacked = LocalDensity.current.fontScale >= 1.5f || androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 400
    val click = if (onClick != null) Modifier.clip(shape).clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val r = CornerRadius(AronTokens.Radius.Card.toPx())
                drawRoundRect(bg, cornerRadius = r)
                val w = stroke.toPx()
                drawRoundRect(edgeColor, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
            }
            .then(click)
            .heightIn(min = 56.dp)
            .padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(AronTokens.Size.Icon))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
            if (stacked) BannerActions(actionLabel, onAction, dismissLabel, onDismiss, fg)
        }
        if (!stacked) BannerActions(actionLabel, onAction, dismissLabel, onDismiss, fg)
    }
}

@Composable
private fun BannerActions(actionLabel: String?, onAction: (() -> Unit)?, dismissLabel: String?, onDismiss: (() -> Unit)?, ink: Color) {
    if ((actionLabel == null || onAction == null) && (dismissLabel == null || onDismiss == null)) return
    Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S), verticalAlignment = Alignment.CenterVertically) {
        if (actionLabel != null && onAction != null) AronTextButton(actionLabel, onAction, color = ink, underline = true)
        if (dismissLabel != null && onDismiss != null) AronTextButton(dismissLabel, onDismiss, color = ink)
    }
}

private data class BannerLook(val bg: Color, val fg: Color, val edge: Color, val icon: ImageVector)

/** The shared offline banner with the stock "your work is saved" message. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) =
    AronBanner(stringResource(R.string.core_ui_offline_mode), modifier, BannerKind.Offline)

/** The older tile call, kept for callers that have no icon yet: a generic dashboard glyph. */
@Composable
fun AronTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0, enabled: Boolean = true) =
    AronTile(label = label, icon = AronIcons.Dashboard, onClick = onClick, modifier = modifier, badge = badge, enabled = enabled)

/**
 * One tile of the Home grid (android-glass s6.4): the glass Tile recipe (translucent, sheen, lit edge, no shadow), a
 * squircle icon well (44 dp, radius 14; compact 36 dp, radius 11) in `accent.container`, and the label on an OPAQUE
 * `surface.solid` plate (6 dp inset), so the reading never depends on the glass (docs/32 s2a item 1). [badge] is a top-end
 * pill in `accent.hi` capped at 99+; [target] adds the 2 dp `accent` ring of the tile the Home dock opens. 96 dp (compact 84)
 * below font scale 1.3, a minimum from there; press scale 0.96.
 */
@Composable
fun AronTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    enabled: Boolean = true,
    target: Boolean = false,
    compact: Boolean = false,
) {
    val c = LocalAronColors.current
    val tier = LocalGlassTier.current
    val shape = RoundedCornerShape(AronTokens.Radius.Card)
    val interaction = remember { MutableInteractionSource() }
    val height = if (compact) AronTokens.Size.TileCompact else AronTokens.Size.Tile
    val well = if (compact) AronTokens.Size.WellCompact else AronTokens.Size.Well
    val wellRadius = if (compact) AronTokens.Radius.WellCompact else AronTokens.Radius.Well
    val ring = c.accent
    val badgeText = if (badge > 99) localizedDigits("99+") else localizedNumber(badge.toLong())
    val badgeDescription = if (badge > 0) stringResource(R.string.core_ui_tile_badge, badgeText) else null
    Box(
        modifier
            .heightIn(min = height)
            .aronFocusRing(shape)
            .pressScale(interaction, AronTokens.Motion.TilePressScale)
            .glassPaint(GlassKind.Tile, tier, shape, AronTokens.Elevation.Page)
            .drawBehind {
                if (target) {
                    val w = AronTokens.Stroke.Focus.toPx()
                    drawRoundRect(ring, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(AronTokens.Radius.Card.toPx() - w / 2), style = Stroke(w))
                }
            }
            .clip(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        // 6 + (well + 4) + 4 + plate 32 + 6 = 96 (compact: 6 + 40 + 0 + 32 + 6 = 84); no weight, which collapses in a scroll column
        Column(Modifier.fillMaxWidth().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else AronTokens.Space.Xs)) {
            Box(Modifier.fillMaxWidth().heightIn(min = well + AronTokens.Space.Xs), contentAlignment = Alignment.Center) {
                val wellFill = if (enabled) c.accentContainer else c.stateDisabledFill
                Box(Modifier.size(well).drawBehind { drawRoundRect(wellFill, cornerRadius = CornerRadius(wellRadius.toPx())) }, contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = if (enabled) c.accentOnContainer else c.stateDisabledLabel, modifier = Modifier.size(if (compact) AronTokens.Size.IconSmall else AronTokens.Size.Icon))
                }
            }
            val plate = c.surfaceSolid
            Box(
                Modifier.fillMaxWidth().drawBehind { drawRoundRect(plate, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) }.padding(horizontal = AronTokens.Space.S, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) c.textPrimary else c.textDisabled, textAlign = TextAlign.Center, maxLines = 3)
            }
        }
        if (badge > 0 && badgeDescription != null) {
            val pill = c.accentHi
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .semantics { contentDescription = badgeDescription }
                    .drawBehind { drawRoundRect(pill, cornerRadius = CornerRadius(size.height / 2f)) }
                    .sizeIn(minHeight = AronTokens.Space.Xl, minWidth = AronTokens.Space.Xl)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(badgeText, color = c.textOnAccent, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/**
 * Columns for a tile grid (docs/design/tokens.md s6): floor((width - 32 + 12) / (96 + 12)) between 2 and 4 (3 on a 360 dp
 * phone), and 2 at font scale 1.5 or more, so Bangla labels never break inside a word or conjunct.
 */
fun autoTileColumns(widthDp: Float, fontScale: Float): Int =
    if (fontScale >= 1.5f) 2 else ((widthDp - 32f + 12f) / (96f + 12f)).toInt().coerceIn(2, 4)

/** A tile grid. [columns] null means [autoTileColumns] from the window width and font scale. Plain rows (a home grid has a handful of tiles). */
@Composable
fun <T> AronTileGrid(items: List<T>, columns: Int? = null, modifier: Modifier = Modifier, tile: @Composable (T, Modifier) -> Unit) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    val cols = columns ?: autoTileColumns(config.screenWidthDp.toFloat(), fontScale)
    require(cols > 0)
    Column(modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        items.chunked(cols).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                rowItems.forEach { tile(it, Modifier.weight(1f)) }
                repeat(cols - rowItems.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Quantity stepper: minus, a value, plus. Every button is 48 dp. [value] is shown in the current language's digits.
 * Typing a value is the caller's job (wrap the value in a field if the screen needs typed entry).
 */
@Composable
fun AronStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    minusLabel: String,
    plusLabel: String,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
) {
    val c = LocalAronColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onValueChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
            modifier = Modifier.sizeIn(minWidth = MinTouch, minHeight = MinTouch).semantics { contentDescription = minusLabel },
            border = outlineStroke(value > min),
            contentPadding = PaddingValues(0.dp),
        ) { Icon(AronIcons.Remove, contentDescription = null, tint = if (value > min) c.textPrimary else c.stateDisabledLabel, modifier = Modifier.size(AronTokens.Size.Icon).clearAndSetSemantics { }) }
        Text(
            localizedNumber(value.toLong()),
            style = MaterialTheme.typography.headlineMedium,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = AronTokens.Space.S).semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(
            onClick = { onValueChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
            modifier = Modifier.sizeIn(minWidth = MinTouch, minHeight = MinTouch).semantics { contentDescription = plusLabel },
            border = outlineStroke(value < max),
            contentPadding = PaddingValues(0.dp),
        ) { Icon(AronIcons.Add, contentDescription = null, tint = if (value < max) c.accentText else c.stateDisabledLabel, modifier = Modifier.size(AronTokens.Size.Icon).clearAndSetSemantics { }) }
    }
}
