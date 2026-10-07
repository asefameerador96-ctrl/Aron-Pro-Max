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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Minimum touch target everywhere in the kit (docs/24 s5.6: 48 dp). */
val MinTouch = 48.dp

/** Primary action: full width, at least 48 dp tall, the label wraps (never truncates) at large font scales. */
@Composable
fun AronPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouch),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) { Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
}

/** Secondary action with an outline. */
@Composable
fun AronSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouch),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) { Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge) }
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
        modifier = modifier.fillMaxWidth().then(click).heightIn(min = MinTouch).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
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
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.heightIn(min = MinTouch).padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/** The shared offline banner with the stock "your work is saved" message. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) =
    AronBanner(androidx.compose.ui.res.stringResource(R.string.core_ui_offline_mode), modifier, BannerKind.Warning)

/** One tile of the home grid: [label] under a [badge] count (hidden when 0) on a 48 dp+ target. */
@Composable
fun AronTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0, enabled: Boolean = true) {
    Surface(
        modifier = modifier.heightIn(min = 88.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 3)
            if (badge > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).sizeIn(minWidth = 24.dp, minHeight = 24.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error,
                ) { Text(localizedNumber(badge.toLong()), color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
            }
        }
    }
}

/** A tile grid with [columns] columns. Plain rows (not lazy) because a home grid has a handful of tiles. */
@Composable
fun <T> AronTileGrid(items: List<T>, columns: Int, modifier: Modifier = Modifier, tile: @Composable (T, Modifier) -> Unit) {
    require(columns > 0)
    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { tile(it, Modifier.weight(1f)) }
                repeat(columns - rowItems.size) { Box(Modifier.weight(1f)) }
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
            contentPadding = PaddingValues(0.dp),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            localizedNumber(value.toLong()),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        OutlinedButton(
            onClick = { onValueChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
            modifier = Modifier.sizeIn(minWidth = MinTouch, minHeight = MinTouch).semantics { contentDescription = plusLabel },
            contentPadding = PaddingValues(0.dp),
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}
