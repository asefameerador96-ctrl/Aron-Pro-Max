package com.aktcl.aron.feature.tso

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronDropdown
import com.aktcl.aron.core.ui.aron.AronHomeFab
import com.aktcl.aron.core.ui.aron.AronHomeFabHeight
import com.aktcl.aron.core.ui.aron.AronMinus
import com.aktcl.aron.core.ui.aron.AronNightTopBar
import com.aktcl.aron.core.ui.aron.AronPlus
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.aron.NightButton
import com.aktcl.aron.core.ui.aron.NightButtonStyle
import com.aktcl.aron.core.ui.aron.NightGlowCard
import com.aktcl.aron.core.ui.aron.NightGlowField
import com.aktcl.aron.core.ui.aron.NightOutlineCard
import com.aktcl.aron.core.ui.aron.nightGlow
import com.aktcl.aron.core.ui.aron.nightPage
import com.aktcl.aron.core.ui.aron.nightRoles
import com.aktcl.aron.core.ui.aron.nightText

/**
 * Room the root Dashboard leaves after its last card for the Home button it draws over its content. A [TsoFrame] keeps its own
 * content above the Home disc ([TsoFabBand]), so a screen inside it needs no such room.
 */
val TsoFabPadding: Dp = AronHomeFabHeight

/**
 * tso-manual X4b: the band of the Home disc (64 dp over a 14 dp margin) under a [TsoFrame]'s content, as the vendor's notched bottom
 * band (screenshots 31, 43): the content (a form's Save, a list's last row) never sits under the disc, so the disc never takes the
 * tap meant for it; only the disc's soft glow rises over the content's lower edge.
 */
internal val TsoFabBand: Dp = 64.dp + 14.dp

/**
 * tso-manual X4b: with the Home button AND a bottom bar, the bar's action sits above the Home disc, the disc overlapping only its
 * lower edge (screenshot 33: Set Plan over the house): 16 dp of the disc may overlap the bar's 52 dp button, so the button's centre
 * (where a tap lands) is never under the disc.
 */
internal val TsoBarOverFab: Dp = TsoFabBand - 16.dp

/**
 * The frame of the TSO screens that are not migrated to the [TsoNav] frame yet (A3's areas): the TSO top bar (grey round back
 * button, gradient title) on the navy page, and the action buttons docked at the bottom. [tile] is kept for the callers and no longer
 * drawn: the screenshots show no icon in the bar (D-TSO-MAN-13).
 */
@Composable
fun TsoFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") tile: String? = null,
    bottomBar: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val n = nightRoles()
    Column(modifier.fillMaxSize().nightPage(n)) {
        AronNightTopBar(title, onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back))
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        if (bottomBar != null) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), content = bottomBar,
            )
        }
    }
}

/**
 * The frame of every TSO screen opened through [TsoNav] (build contract s5 A1; screenshots 8, 17, 24, 39): the TSO top bar with the
 * gradient [title] and the grey round back button ([onBack], by default [TsoNav.back]) on the navy page, [bottomBar] docked under
 * the content, and the Home button at the bottom centre when [homeButton] (pass `TsoFab.of(screen)`, the s8 FAB column). A scrolling
 * content ends above the Home disc ([TsoFabBand]); a bottom bar sits above the disc too ([TsoBarOverFab]), as screenshots 31, 33, 43.
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
    val n = nightRoles()
    Box(modifier.fillMaxSize().nightPage(n)) {
        Column(Modifier.fillMaxSize()) {
            AronNightTopBar(title, onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back))
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .then(if (homeButton && bottomBar == null) Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = TsoFabBand) else Modifier),
                content = content,
            )
            if (bottomBar != null) {
                Box(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (homeButton) TsoBarOverFab else 16.dp),
                ) { bottomBar() }
            }
        }
        if (homeButton) {
            AronHomeFab({ nav.home() }, stringResource(R.string.tso_chrome_home), Modifier.align(Alignment.BottomCenter), buttonModifier = Modifier.testTag(TsoKitTags.HOME))
        }
    }
}

/** Test tags of the TSO kit. */
object TsoKitTags {
    const val HOME = "tso_kit_home"
    fun segment(index: Int) = "tso_kit_segment_$index"
    fun pill(index: Int) = "tso_kit_pill_$index"
}

/** The colour of a [TsoGradientButton]: Lime (Final Submit and plan actions), Blue (Submit ticket), Cyan (Login). */
enum class TsoButtonStyle { Lime, Blue, Cyan }

/**
 * A full-width gradient action button (screenshots 5, 17, 24, 31) in the TSO look ([NightButton]): lime and cyan carry the navy
 * label, blue the white one, all 7:1. A busy or disabled button is not pressable.
 */
@Composable
fun TsoGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TsoButtonStyle = TsoButtonStyle.Blue,
    enabled: Boolean = true,
    busy: Boolean = false,
    /** A decorative glyph before the label (core-ui `AronIcons`), e.g. the picture of Browse Gallery (screenshot 43). */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val s = when (style) { TsoButtonStyle.Lime -> NightButtonStyle.Lime; TsoButtonStyle.Cyan -> NightButtonStyle.Cyan; TsoButtonStyle.Blue -> NightButtonStyle.Blue }
    NightButton(text, onClick, modifier, style = s, enabled = enabled, busy = busy, icon = icon)
}

/**
 * A TSO content card: [glow] draws the purple-gradient glow card of the dashboard (Sales, Geo Fencing; screenshot 6), otherwise the
 * navy card with the teal edge (CPR, Login status, Target Status, tickets; screenshots 7, 11, 39). [onClick] makes the whole card one
 * button.
 */
@Composable
fun TsoCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (glow) NightGlowCard(modifier, onClick = onClick, verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    else NightOutlineCard(modifier, onClick = onClick, verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/**
 * A text field with its label ABOVE it (never in the outline notch) and a placeholder inside (screenshots 5, 17, 20, 43): the navy
 * glow field of the TSO look ([NightGlowField]). [modifier] goes on the input (test tags). TalkBack names the field by [label] (or
 * [placeholder] when there is no label).
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
    NightGlowField(
        value, onValueChange, label = label, placeholder = placeholder, enabled = enabled, singleLine = singleLine, minLines = minLines,
        isError = isError, supportingText = supportingText, keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        fieldModifier = modifier,
    )
}

/**
 * A closed field that opens a list of [options] (issue type, leave type, route, zone levels; screenshots 17, 21, 23, 32): core-ui
 * [AronDropdown] in the TSO look (the bold label above, the navy glow field, the down caret).
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
    AronDropdown(options, selected, onSelect, optionLabel, modifier, label = label, placeholder = placeholder, enabled = enabled)
}

/**
 * Two or more equal tabs in one bar (Monthly Target / Till Date Target, screenshots 39 to 42): a glowing teal frame, the selected tab
 * filled green with a white label, the other on the field navy. Each tab is a selectable Tab for TalkBack.
 */
@Composable
fun TsoSegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val n = nightRoles()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().nightGlow(n, n.outlineEdge, shape, 8.dp).clip(shape)
            .border(if (n.solid) 2.dp else 1.5.dp, n.outlineEdge, shape),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .background(if (on) n.segmentOn else n.field)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .testTag(TsoKitTags.segment(i))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text(l, style = nightText(17.sp, if (on) n.onSegment else n.ink)) }
        }
    }
}

/**
 * A scrolling row of pill tabs (All / New / In Progress / Resolved / Closed, screenshots 12 and 13): the selected pill blue with a
 * white label, the others quiet navy.
 */
@Composable
fun TsoPillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val n = nightRoles()
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) n.pillOn else n.pillOff)
                    .then(if (n.solid && !on) Modifier.border(2.dp, n.outlineEdge, RoundedCornerShape(50)) else Modifier)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .testTag(TsoKitTags.pill(i))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, style = nightText(16.sp, if (on) n.onBlue else n.inkMuted, semibold = on)) }
        }
    }
}

/** What a [TsoStatusPill] says by colour; the words always carry the meaning. */
enum class TsoPillTone { Neutral, Info, Success, Warning, Danger }

/** A small status chip (P1 - Highest, RESOLVED, Pending; screenshots 12 to 14, 35): the quiet chip fill with a tone ink. */
@Composable
fun TsoStatusPill(text: String, modifier: Modifier = Modifier, tone: TsoPillTone = TsoPillTone.Neutral) {
    val n = nightRoles()
    val ink = when (tone) { TsoPillTone.Danger -> n.danger; TsoPillTone.Success -> n.success; TsoPillTone.Warning -> n.warning; TsoPillTone.Info -> n.teal; TsoPillTone.Neutral -> n.chipInk }
    Box(
        modifier.clip(RoundedCornerShape(50)).background(n.chip)
            .then(if (n.solid) Modifier.border(1.dp, n.outlineEdge, RoundedCornerShape(50)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text, style = nightText(13.sp, ink, semibold = true))
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
