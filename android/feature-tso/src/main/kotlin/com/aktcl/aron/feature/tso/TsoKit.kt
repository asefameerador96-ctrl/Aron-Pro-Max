package com.aktcl.aron.feature.tso

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
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

/**
 * The frame of every TSO screen opened through [TsoNav] (build contract s5 A1): the top bar with the title and the back
 * button ([onBack], by default [TsoNav.back]), the Home button when [homeButton] (the s8 FAB column: Y screens pass true),
 * and [bottomBar] docked under the content. a1-stubs gives it a minimal working body in the current look; A1's styling
 * step draws the TsoNight bar, the grey round back button and the HomeFab without changing this signature.
 */
@Composable
fun TsoFrame(
    title: String,
    nav: TsoNav,
    homeButton: Boolean,
    bottomBar: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = { nav.back() },
    content: @Composable BoxScope.() -> Unit,
) {
    val buttons: (@Composable RowScope.() -> Unit)? = if (bottomBar == null) null else { { bottomBar() } }
    AronScaffold(
        modifier = modifier,
        topBar = { AronTopBar(title, onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back)) },
        bottomButtons = buttons,
    ) {
        content()
        if (homeButton) TsoHomeButton({ nav.home() }, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

/** Test tags of the TSO kit. */
object TsoKitTags {
    const val HOME = "tso_kit_home"
    fun segment(index: Int) = "tso_kit_segment_$index"
    fun pill(index: Int) = "tso_kit_pill_$index"
}

/** The round Home button (FAB) of a [TsoFrame]; returns to the root. Minimal body until A1's `aron/HomeFab.kt`. */
@Composable
private fun TsoHomeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val label = stringResource(R.string.tso_chrome_home)
    Box(
        modifier
            .testTag(TsoKitTags.HOME)
            .size(56.dp)
            .clip(CircleShape)
            .background(look.purple)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(com.aktcl.aron.core.ui.AronIcons.Dashboard, contentDescription = null, tint = look.onBar)
    }
}

/** The colour of a [TsoGradientButton]: Lime (Final Submit and plan actions), Blue (Submit ticket), Cyan (Login). */
enum class TsoButtonStyle { Lime, Blue, Cyan }

/**
 * A full-width gradient action button (screenshots 5, 17, 24, 31). A busy button is not pressable. Minimal body in the
 * current look's gradients; A1's styling step takes the TsoNight gradients and the 7:1 text contrast.
 */
@Composable
fun TsoGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TsoButtonStyle = TsoButtonStyle.Blue,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val look = LocalAronLook.current
    val on = enabled && !busy
    val brush = when {
        !on -> SolidColor(look.disabledFill)
        style == TsoButtonStyle.Lime -> Brush.horizontalGradient(listOf(look.goStart, look.goEnd))
        style == TsoButtonStyle.Cyan -> Brush.horizontalGradient(listOf(look.printStart, look.printEnd))
        else -> Brush.horizontalGradient(listOf(look.barStart, look.barEnd))
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(brush)
            .clickable(enabled = on, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (on) look.onBar else look.disabledText, textAlign = TextAlign.Center)
    }
}

/**
 * A TSO content card (Target Status, tickets, dashboard cards). [glow] marks the highlighted card of a screen; [onClick]
 * makes the whole card one button. Minimal body on the kit card; A1's styling step draws the TsoNight gradient and glow.
 */
@Composable
fun TsoCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = LocalAronLook.current
    AronCard(if (glow) modifier.border(1.dp, look.purple, RoundedCornerShape(com.aktcl.aron.core.ui.AronLook.CardRadius)) else modifier, onClick = onClick, content = content)
}

/**
 * A text field with its label ABOVE it (never in the outline notch) and a placeholder inside (screenshots 5, 17, 20, 43).
 * TalkBack names the field by [label] (or [placeholder] when there is no label). Minimal body on a Material outlined field; A1's
 * styling step draws the TsoNight glow outline.
 */
@Composable
fun TsoGlowField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = look.ink)
        OutlinedTextField(
            value, onValueChange, modifier.fillMaxWidth().semantics { (label ?: placeholder)?.let { contentDescription = it } },
            enabled = enabled, singleLine = singleLine, minLines = minLines, isError = isError,
            placeholder = if (placeholder == null) null else { { Text(placeholder, color = look.inkMuted) } },
            supportingText = if (supportingText == null) null else { { Text(supportingText, color = if (isError) look.red else look.inkMuted) } },
            keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = look.card, unfocusedContainerColor = look.card, disabledContainerColor = look.card,
                focusedTextColor = look.ink, unfocusedTextColor = look.ink, disabledTextColor = look.inkMuted,
                focusedBorderColor = look.purple, unfocusedBorderColor = look.underline, cursorColor = look.purple,
            ),
        )
    }
}

/**
 * A closed field that opens a list of [options] (issue type, leave type, route, zone levels; screenshots 17, 21, 23, 32):
 * the label above, the chosen option's text (or [placeholder]) and a chevron. Choosing calls [onSelect] and closes the
 * list. Minimal body on a Material dropdown menu; A1's styling step moves it to core-ui `aron/AronDropdown.kt`.
 */
@Composable
fun <T> TsoDropdownField(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val look = LocalAronLook.current
    var open by remember { mutableStateOf(false) }
    val shown = selected?.let(optionLabel)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = look.ink)
        Box {
            Row(
                modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(look.card)
                    .border(1.dp, look.underline, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = label) { open = true }
                    // TalkBack reads the field's name and what it holds now ("Issue type, Login OTP issue")
                    .semantics { contentDescription = listOfNotNull(label, shown ?: placeholder.ifEmpty { null }).joinToString(", ") }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shown ?: placeholder, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (shown != null) look.ink else look.inkMuted)
                Icon(com.aktcl.aron.core.ui.AronIcons.ChevronRight, contentDescription = null, tint = look.inkMuted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) })
                }
            }
        }
    }
}

/**
 * Two or more equal tabs in one bar (Monthly Target / Till Date Target, screenshots 39 to 42): the selected one filled.
 * Each tab is a selectable Tab for TalkBack. Minimal body; A1's styling step takes the TsoNight green fill and glow.
 */
@Composable
fun TsoSegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, look.underline, RoundedCornerShape(12.dp))) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .background(if (on) look.goStart else look.card)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .testTag(TsoKitTags.segment(i))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text(l, style = MaterialTheme.typography.titleSmall, color = if (on) look.onBar else look.ink) }
        }
    }
}

/**
 * A scrolling row of pill tabs (All / New / In Progress / Resolved / Closed, screenshots 12 and 13): the selected pill
 * filled. Minimal body; A1's styling step takes the TsoNight blue fill.
 */
@Composable
fun TsoPillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) look.purple else look.card)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .testTag(TsoKitTags.pill(i))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, style = MaterialTheme.typography.labelLarge, color = if (on) look.onBar else look.ink) }
        }
    }
}

/** What a [TsoStatusPill] says by colour; the words always carry the meaning. */
enum class TsoPillTone { Neutral, Info, Success, Warning, Danger }

/** A small status chip (P1 - Highest, RESOLVED, Pending; screenshots 12 to 14, 35). Minimal body; A1's styling step takes the TsoNight chips. */
@Composable
fun TsoStatusPill(text: String, modifier: Modifier = Modifier, tone: TsoPillTone = TsoPillTone.Neutral) {
    val look = LocalAronLook.current
    val ink = when (tone) { TsoPillTone.Danger -> look.red; TsoPillTone.Success -> look.goStart; TsoPillTone.Warning -> look.amber; TsoPillTone.Info -> look.purple; TsoPillTone.Neutral -> look.inkMuted }
    Box(modifier.clip(RoundedCornerShape(50)).border(1.dp, ink, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = ink)
    }
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
