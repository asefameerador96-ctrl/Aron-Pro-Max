package com.aktcl.aron.feature.amo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronFonts
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook

/** Shared building blocks of the AMO screens in the existing-Aron look (white 12 dp cards on the grey page, purple titles, gradient actions). */

@Composable
internal fun amoText(size: Int, bold: Boolean = false, color: Color = LocalAronLook.current.ink, align: TextAlign = TextAlign.Unspecified) =
    TextStyle(fontFamily = AronFonts.forLanguage(LocalAppLanguage.current), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color, textAlign = align)

/** A scrolling page body with the 12 dp gutter of the SR list screens. */
@Composable
internal fun AmoPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** The white card (radius 12, 2 dp shadow). [title] draws the purple heading strip of the manual's tiles (Live Data). */
@Composable
internal fun AmoCard(modifier: Modifier = Modifier, title: String? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    ) {
        if (title != null) {
            Box(Modifier.fillMaxWidth().background(look.purple).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(title, style = amoText(16, bold = true, color = look.onBar), modifier = Modifier.semantics { heading() })
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** A list row card: title in purple, an optional second line, a value or chevron at the end. */
@Composable
internal fun AmoRowCard(title: String, modifier: Modifier = Modifier, subtitle: String? = null, trailing: String? = null, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = amoText(18, bold = selected, color = look.purple))
            if (subtitle != null) Text(subtitle, style = amoText(13, color = look.inkMuted))
        }
        if (trailing != null) Text(trailing, style = amoText(18, bold = true, color = look.ink), textAlign = TextAlign.End)
        else if (onClick != null) Text("›", style = amoText(26, color = look.inkMuted))
    }
}

/** A figure inside a card: the label on the left, the big number at the end; at large text sizes the label sits above the number so neither is squeezed. */
@Composable
internal fun AmoFigure(label: String, value: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) {
        Column(modifier.fillMaxWidth()) {
            Text(label, style = amoText(14, color = look.inkMuted))
            Text(value, style = amoText(22, bold = true, color = look.purple))
        }
        return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = amoText(14, color = look.inkMuted), modifier = Modifier.weight(1f))
        Text(value, style = amoText(22, bold = true, color = look.purple), textAlign = TextAlign.End)
    }
}

/** A progress bar on the `aron.track` with the red fill of the Home target card; [fraction] 0..1. */
@Composable
internal fun AmoBar(fraction: Float, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxWidth().heightIn(min = 14.dp).clip(RoundedCornerShape(7.dp)).background(look.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().heightIn(min = 14.dp).clip(RoundedCornerShape(7.dp)).background(look.trackFill))
    }
}

@Composable
internal fun AmoHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = amoText(16, bold = true, color = LocalAronLook.current.purple), modifier = modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() })
}

/** The centred empty state: one line, an optional hint. */
@Composable
internal fun AmoEmpty(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(look.silhouette))
        Text(text, style = amoText(18, bold = true, color = look.purple, align = TextAlign.Center))
        if (hint != null) Text(hint, style = amoText(14, color = look.inkMuted, align = TextAlign.Center))
    }
}

/**
 * The frame of every AMO screen: the kit top bar (gradient, centred [icon] and title, back chevron) over the grey page. [bottomButtons]
 * docks the action row (Save, Filter) under the content as in the SR screens.
 */
@Composable
fun AmoFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.DrawableRes icon: Int? = null,
    bottomButtons: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    com.aktcl.aron.core.ui.aron.AronScaffold(
        topBar = {
            com.aktcl.aron.core.ui.aron.AronTopBar(
                title, icon = icon?.let { androidx.compose.ui.res.painterResource(it) }, onBack = onBack,
                backLabel = androidx.compose.ui.res.stringResource(com.aktcl.aron.core.ui.R.string.core_ui_back), trailing = trailing,
            )
        },
        modifier = modifier, bottomButtons = bottomButtons,
    ) { content() }
}

/** A read-only pack row (manual S-31): the packshot, the SKU name in purple, a second line, the figure at the end. */
@Composable
internal fun AmoPackRow(skuId: String, name: String, modifier: Modifier = Modifier, subtitle: String? = null, figure: String, category: com.aktcl.aron.core.ui.aron.PackCategory = com.aktcl.aron.core.ui.aron.PackCategory.Cigarette) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.aktcl.aron.core.ui.aron.PackshotImage(skuId, Modifier.size(width = 44.dp, height = 70.dp), category = category)
        Column(Modifier.weight(1f)) {
            Text(name, style = amoText(18, bold = true, color = look.purple))
            if (subtitle != null) Text(subtitle, style = amoText(13, color = look.inkMuted))
        }
        Text(figure, style = amoText(20, bold = true), textAlign = TextAlign.End)
    }
}

/** The purple totals panel (PARITY-SPEC s2.7) as an inline block: [header] columns, then one line per row, values right-aligned. */
@Composable
internal fun AmoTotalsBlock(header: List<String>, lines: List<List<String>>, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) { header.forEachIndexed { i, h -> Text(h, style = amoText(12, color = look.onPanelMuted, align = if (i == 0) TextAlign.Start else TextAlign.End), modifier = Modifier.weight(1f)) } }
        lines.forEach { l -> Row(Modifier.fillMaxWidth()) { l.forEachIndexed { i, v -> Text(v, style = amoText(20, color = look.onPanel, align = if (i == 0) TextAlign.Start else TextAlign.End), modifier = Modifier.weight(1f)) } } }
    }
}

// ---- Drop-in forms of the old list/banner/button calls, in the parity look (the callers keep their shape) ----

/** A card row; [position], [divider] and [flat] belong to the old grouped list and are ignored (every row is its own white card). */
@Composable
fun AmoListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") position: com.aktcl.aron.core.ui.RowPosition = com.aktcl.aron.core.ui.RowPosition.Only,
    @Suppress("UNUSED_PARAMETER") divider: Boolean = false,
    @Suppress("UNUSED_PARAMETER") flat: Boolean = false,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.padding(vertical = 3.dp).fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = amoText(18, color = look.purple))
            if (subtitle != null) Text(subtitle, style = amoText(13, color = look.inkMuted))
        }
        if (trailingContent != null) trailingContent()
        else if (trailing != null) Text(trailing, style = amoText(16, bold = true), textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 120.dp))
        else if (onClick != null) Text("›", style = amoText(26, color = look.inkMuted))
    }
}

@Composable
fun AmoBanner(text: String, modifier: Modifier = Modifier, kind: com.aktcl.aron.core.ui.BannerKind = com.aktcl.aron.core.ui.BannerKind.Info) =
    AmoNotice(text, modifier, warning = kind == com.aktcl.aron.core.ui.BannerKind.Warning || kind == com.aktcl.aron.core.ui.BannerKind.Error)

@Composable
fun AmoSection(text: String, modifier: Modifier = Modifier) = AmoHeading(text, modifier)

@Composable
fun AmoEmptyState(title: String, modifier: Modifier = Modifier, hint: String? = null) = AmoEmpty(title, modifier, hint)

/** The gradient action button; [busy] disables it while a save runs. */
@Composable
fun AmoPrimary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) =
    com.aktcl.aron.core.ui.aron.AronPrimaryButton(text, onClick, modifier, enabled = enabled && !busy)

/** The outlined action button: white with a purple edge and label, a grey ghost when disabled. [busy] disables it while a save runs. */
@Composable
fun AmoSecondary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val look = LocalAronLook.current
    val on = enabled && !busy
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.heightIn(min = 52.dp).clip(shape).background(if (on) look.card else look.disabledFill)
            .border(1.5.dp, if (on) look.purple else look.disabledText, shape).clickable(enabled = on, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = amoText(18, bold = true, color = if (on) look.purple else look.disabledText, align = TextAlign.Center), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
}

/** Yes (purple, like the plus square) and No / confirm-negative (red, like the minus square); a dimmed fill once chosen or disabled. */
@Composable
private fun AmoChoice(text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, fill: Color) {
    val look = LocalAronLook.current
    Box(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(if (enabled) fill else look.disabledFill).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = amoText(18, bold = true, color = if (enabled) Color.White else look.disabledText, align = TextAlign.Center), modifier = Modifier.padding(8.dp)) }
}

@Composable
fun AmoYes(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = AmoChoice(text, onClick, modifier, enabled, LocalAronLook.current.purple)

@Composable
fun AmoNo(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = AmoChoice(text, onClick, modifier, enabled, LocalAronLook.current.minus)

/** A row of choice chips (replaces the old segmented control); the selected one is the purple gradient square of the A-Z filter. */
@Composable
internal fun AmoChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) look.purple else look.card).clickable(role = Role.RadioButton) { onSelect(i) }.padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, style = amoText(14, bold = on, color = if (on) look.onBar else look.purple, align = TextAlign.Center)) }
        }
    }
}
