package com.aktcl.aron.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Minimum touch target everywhere in the kit (docs/24 s5.6: 48 dp). */
val MinTouch = AronTokens.Touch.Min

/** Primary action: full width, at least 48 dp tall, the label wraps (never truncates) at large font scales. */
@Composable
fun AronPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Primary),
        shape = AronTokens.ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = LocalAronColors.current.accent, contentColor = LocalAronColors.current.textOnAccent,
            disabledContainerColor = LocalAronColors.current.stateDisabledFill, disabledContentColor = LocalAronColors.current.stateDisabledLabel,
        ),
        contentPadding = PaddingValues(horizontal = AronTokens.Space.Xl, vertical = AronTokens.Space.M),
    ) { Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium) }   // 18 sp bold: the glare exception of s2a item 4 depends on it
}

/** Secondary action with an outline. */
@Composable
fun AronSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouch),
        shape = AronTokens.ButtonShape,
        border = outlineStroke(enabled),
        contentPadding = PaddingValues(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M),
    ) { Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
}

/** The outline of secondary and stepper buttons: 1 dp, 2 dp in sunlight; the disabled outline uses the disabled label colour. */
@Composable
internal fun outlineStroke(enabled: Boolean = true): BorderStroke {
    val c = LocalAronColors.current
    return BorderStroke(if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline, if (enabled) c.accent else c.stateDisabledLabel)
}

/** A tappable row: title, optional subtitle and trailing text. The row is the touch target and wraps at large fonts. */
@Composable
fun AronListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier = modifier.fillMaxWidth().aronFocusRing(RoundedCornerShape(AronTokens.Radius.Chip)).then(click).heightIn(min = MinTouch).padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

enum class BannerKind { Info, Warning, Error }

/** A full-width status banner (offline, update required, sync problem). Not interactive unless [onClick] is given. */
@Composable
fun AronBanner(text: String, modifier: Modifier = Modifier, kind: BannerKind = BannerKind.Info, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        BannerKind.Info -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BannerKind.Warning -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BannerKind.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Surface(color = bg, contentColor = fg, modifier = modifier.fillMaxWidth().then(click)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = MinTouch).padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M))
    }
}

/** The shared offline banner with the stock "your work is saved" message. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) =
    AronBanner(androidx.compose.ui.res.stringResource(R.string.core_ui_offline_mode), modifier, BannerKind.Warning)

/**
 * One tile of the home grid: a glass backdrop (chrome) with a SOLID label plate, so the reading never depends on the
 * glass (docs/32 s2a item 1). [badge] shows below the label, hidden when 0.
 */
@Composable
fun AronTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0, enabled: Boolean = true) {
    val c = LocalAronColors.current
    GlassSurface(modifier = modifier.heightIn(min = 88.dp), onClick = onClick, enabled = enabled) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(AronTokens.Space.S),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs, Alignment.CenterVertically),
        ) {
            Surface(shape = RoundedCornerShape(AronTokens.Radius.Chip), color = c.surfaceSolid) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) c.textPrimary else c.stateDisabledLabel, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = AronTokens.Space.S, vertical = AronTokens.Space.Xs))
            }
            if (badge > 0) {
                Surface(shape = RoundedCornerShape(AronTokens.Radius.Chip), color = c.dangerContainer) {
                    Text(localizedNumber(badge.toLong()), color = c.dangerOnContainer, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = AronTokens.Space.S, vertical = AronTokens.Space.Xs))
                }
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
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val cols = columns ?: autoTileColumns(config.screenWidthDp.toFloat(), fontScale)
    require(cols > 0)
    Column(modifier.fillMaxWidth().padding(AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        items.chunked(cols).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onValueChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
            modifier = Modifier.sizeIn(minWidth = MinTouch, minHeight = MinTouch).semantics { contentDescription = minusLabel },
            border = outlineStroke(value > min),
            contentPadding = PaddingValues(0.dp),
        ) { Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { }) }
        Text(
            localizedNumber(value.toLong()),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = AronTokens.Space.S).semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(
            onClick = { onValueChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
            modifier = Modifier.sizeIn(minWidth = MinTouch, minHeight = MinTouch).semantics { contentDescription = plusLabel },
            border = outlineStroke(value < max),
            contentPadding = PaddingValues(0.dp),
        ) { Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { }) }
    }
}
