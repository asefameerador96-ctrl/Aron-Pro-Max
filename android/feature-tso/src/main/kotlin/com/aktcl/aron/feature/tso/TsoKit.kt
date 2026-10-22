package com.aktcl.aron.feature.tso

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronMinus
import com.aktcl.aron.core.ui.aron.AronPlus
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.aron.AronTopBar

/**
 * The frame of every TSO sub-screen in the existing-Aron look (PARITY-SPEC s2.1/s2.2): the gradient top bar with the back chevron,
 * the screen's gradient icon and white bold title, the grey page, and the action buttons docked at the bottom. [tile] names the
 * Home tile whose picture the bar shows (see [tsoIcon]).
 */
@Composable
fun TsoFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    tile: String? = null,
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    AronScaffold(
        modifier = modifier,
        topBar = { AronTopBar(title, icon = tsoIcon(tile), onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back)) },
        bottomButtons = bottomBar,
        content = content,
    )
}

/** One docked action: the label, what it does, whether it can be pressed. */
data class TsoAction(val text: String, val onClick: () -> Unit, val enabled: Boolean = true, val busy: Boolean = false)

/** The docked primary (gradient) and optional secondary (grey) buttons; a busy primary is not pressable. */
@Composable
fun RowScope.TsoBottomBar(primary: TsoAction, modifier: Modifier = Modifier, secondary: TsoAction? = null) {
    if (secondary != null) AronSecondaryButton(secondary.text, secondary.onClick, Modifier.weight(1f), enabled = secondary.enabled)
    AronPrimaryButton(primary.text, primary.onClick, modifier.weight(1f), enabled = primary.enabled && !primary.busy)
}

/** A full-width grey button (the secondary action inside a form or list). */
@Composable
fun TsoButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    AronSecondaryButton(text, onClick, modifier.fillMaxWidth(), enabled = enabled)
}

/** One tile of a menu screen: its label, the icon id for [tsoIcon], the test tag and what it opens. */
data class TsoMenuTile(val label: String, val icon: String, val tag: String, val onClick: () -> Unit)

/** A menu screen's tiles (sr/outlet-menu.png): grey tiles with the gradient icon over a red label, three to a row. */
@Composable
fun TsoMenuGrid(tiles: List<TsoMenuTile>, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    com.aktcl.aron.core.ui.aron.AronTileGrid(tiles, modifier.padding(12.dp), columns = 3) { t, m ->
        com.aktcl.aron.core.ui.aron.AronTile(
            t.label, tsoIcon(t.icon), onClick = t.onClick, modifier = m.then(Modifier.testTag(t.tag)), menu = true, labelColor = look.tileLabelOutlet,
        )
    }
}

/** The quiet loading state (no spinner): grey placeholder lines on a white card, announced as "Loading". */
@Composable
fun TsoLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(12.dp)) { com.aktcl.aron.core.ui.aron.AronLoadingCard(stringResource(R.string.tso_loading)) }
}

enum class TsoBannerKind { Info, Warning, Error, Success, Offline }

/** A status line on a white card; the words carry the meaning, the colour only backs them (error red, success purple). */
@Composable
fun TsoBanner(text: String, modifier: Modifier = Modifier, kind: TsoBannerKind = TsoBannerKind.Info) {
    val look = LocalAronLook.current
    val ink = when (kind) { TsoBannerKind.Error -> look.red; TsoBannerKind.Success -> look.purple; else -> look.ink }
    AronCard(modifier) { Text(text, style = MaterialTheme.typography.bodyMedium, color = ink) }
}

/** An empty list or empty day: a white card with the title and the hint. */
@Composable
fun TsoEmpty(title: String, modifier: Modifier = Modifier, hint: String? = null) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        AronCard {
            Text(title, style = MaterialTheme.typography.titleMedium, color = look.purple, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

/** A failed load: the message and a retry button on a white card. */
@Composable
fun TsoError(message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        AronCard {
            Text(message, style = MaterialTheme.typography.titleMedium, color = look.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            AronSecondaryButton(retryLabel, onRetry, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

/** The red minus, the number over an underline and the purple plus of the Stock and Sale cards (sr/stock.webp). */
@Composable
fun TsoStepper(value: Int, onValueChange: (Int) -> Unit, minusLabel: String, plusLabel: String, modifier: Modifier = Modifier, min: Int = 0, max: Int = Int.MAX_VALUE) {
    val look = LocalAronLook.current
    Row(modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AronMinus({ onValueChange((value - 1).coerceAtLeast(min)) }, minusLabel, enabled = value > min)
        Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(com.aktcl.aron.core.ui.localizedNumber(value.toLong()), style = MaterialTheme.typography.headlineSmall, color = look.ink)
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 2.dp).heightIn(min = 1.dp).background(look.underline))
        }
        AronPlus({ onValueChange((value + 1).coerceAtMost(max)) }, plusLabel, enabled = value < max)
    }
}
