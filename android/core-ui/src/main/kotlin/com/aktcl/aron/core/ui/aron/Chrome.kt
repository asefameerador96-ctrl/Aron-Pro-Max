package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Image
import com.aktcl.aron.core.ui.aron.Glyph.info
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronFonts
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronLookColors
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.Glyph.bar
import com.aktcl.aron.core.ui.aron.Glyph.chevronBack
import com.aktcl.aron.core.ui.aron.Glyph.gear
import com.aktcl.aron.core.ui.aron.Glyph.person
import com.aktcl.aron.core.ui.aron.Glyph.printer
import com.aktcl.aron.core.ui.aron.Glyph.save
import com.aktcl.aron.core.ui.aron.Glyph.sync

/** The horizontal `aron.gradient.bar` (red, plum, purple); solid in sunlight. [reverse] runs purple to red (the Summary Print button). */
fun AronLookColors.barBrush(reverse: Boolean = false): Brush =
    if (sunlight) SolidColor(barStart) else Brush.horizontalGradient(if (reverse) listOf(barEnd, barMid, barStart) else listOf(barStart, barMid, barEnd))

/** Sunlight bars are solid white: a 2 dp ink rule under them keeps the edge visible (the gradient does it otherwise). */
internal fun Modifier.sunlightRule(look: AronLookColors): Modifier =
    if (!look.sunlight) this else drawBehind { drawRect(look.ink, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }

/** The vertical gradient of the selected letter pill. */
fun AronLookColors.verticalBrush(): Brush = if (sunlight) SolidColor(purple) else Brush.verticalGradient(listOf(barStart, barEnd))

/** A text style in the bundled font of the current language. */
@Composable
internal fun lookStyle(size: TextUnit, bold: Boolean = false, color: Color = Color.Unspecified, align: TextAlign = TextAlign.Unspecified, medium: Boolean = false) =
    TextStyle(
        fontFamily = AronFonts.forLanguage(LocalAppLanguage.current), fontSize = size, color = color, textAlign = align,
        fontWeight = if (bold) FontWeight.Bold else if (medium) FontWeight.Medium else FontWeight.Normal,
    )

/**
 * The top bar of every non-Home screen (PARITY-SPEC s2.1, sr/stock.webp, sr/sale-select.png, sr/summary.png): full-width gradient, a white
 * back chevron at the start (shown when [onBack] is set; [backLabel] is its TalkBack label), the screen [icon] (36 dp, the gradient line
 * icon of the tile) and the white bold [title] (22 sp) centred, an optional [trailing] slot at the end inside the bar, and an optional
 * [hanging] slot whose centre sits on the bar's bottom edge at the end (the printer state, [AronPrinterBadge], as in sr/stock.webp).
 * Inside [AronScaffold] the bar is drawn above the content so the hanging badge stays visible and tappable.
 */
@Composable
fun AronTopBar(
    title: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    hanging: (@Composable () -> Unit)? = null,
) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(look.barBrush())
                .sunlightRule(look)
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = AronLook.BarHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (onBack != null) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(AronLook.Touch)
                        .clickable(role = Role.Button, onClick = onBack)
                        .semantics { if (backLabel != null) contentDescription = backLabel },
                    contentAlignment = Alignment.Center,
                ) { GlyphCanvas(Modifier.size(24.dp)) { chevronBack(look.onBar) } }
            }
            Row(
                Modifier.padding(horizontal = AronLook.Touch + 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(AronLook.BarIcon))
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = lookStyle(22.sp, bold = true, color = look.onBar, align = TextAlign.Center), modifier = Modifier.semantics { heading() })
            }
            if (trailing != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { trailing() }
        }
        if (hanging != null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp).offset(y = AronLook.Touch / 2)) { hanging() }
    }
}

/**
 * The printer state at the end of the top bar (sr/stock.webp): a light round badge with a red crossed printer while no printer is
 * connected, a purple printer once one is. [label] is the TalkBack text of the state; tapping it opens the connect flow.
 */
@Composable
fun AronPrinterBadge(connected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(
        modifier
            .size(AronLook.Touch + 4.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(look.rowAlt)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GlyphCanvas(Modifier.size(28.dp)) { printer(if (connected) look.printerOn else look.printerOff, crossed = !connected, gap = look.card) }
    }
}

/**
 * The screen frame (PARITY-SPEC s2.2): the [topBar], the content on the light grey `aron.page` (white for the Home grid: [home]),
 * then an optional [bottomPanel] (solid purple, [TotalsPanel] goes here) and an optional [bottomButtons] row, which sits on the
 * purple panel when there is one (sr/stock.webp) and on the page otherwise.
 */
@Composable
fun AronScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    home: Boolean = false,
    bottomPanel: (@Composable ColumnScope.() -> Unit)? = null,
    bottomButtons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().background(if (home) look.pageHome else look.page)) {
        Box(Modifier.zIndex(1f)) { topBar() }
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        if (bottomPanel != null || bottomButtons != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (bottomPanel != null) look.panel else look.page)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                bottomPanel?.invoke(this)
                if (bottomButtons != null) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = bottomButtons)
                }
            }
        }
    }
}

/**
 * The Home header (PARITY-SPEC s2.3, sr/home-1-tiles.webp): the gradient, a white round avatar with the person glyph (or [avatar]),
 * [title] = "Role - Name (login)" in white bold 15 sp, [subtitle] = route and date in 11 sp (measured on the reference), and the white settings gear at the end
 * ([settingsLabel] is its TalkBack label).
 */
@Composable
fun AronHomeHeader(
    title: String,
    subtitle: String,
    onSettings: () -> Unit,
    settingsLabel: String,
    modifier: Modifier = Modifier,
    avatar: Painter? = null,
) {
    val look = LocalAronLook.current
    Row(
        modifier
            .fillMaxWidth()
            .background(look.barBrush())
            .sunlightRule(look)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 12.dp, end = 4.dp, top = 20.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(look.card), contentAlignment = Alignment.Center) {
            if (avatar != null) Image(avatar, contentDescription = null, modifier = Modifier.size(56.dp))
            else GlyphCanvas(Modifier.size(40.dp)) { person(look.purple) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = lookStyle(15.sp, bold = true, color = look.onBar))
            Text(subtitle, style = lookStyle(11.sp, color = look.onBar))
        }
        Box(
            Modifier.size(AronLook.Touch).clickable(role = Role.Button, onClick = onSettings).semantics { contentDescription = settingsLabel },
            contentAlignment = Alignment.Center,
        ) { GlyphCanvas(Modifier.size(34.dp)) { gear(look.onBar, hole = if (look.sunlight) look.card else look.barEnd) } }
    }
}

/** The leading glyph of a button (sr/stock.webp: Save has a disk, Print a printer). */
enum class ButtonGlyph { Save, Print, Sync, Submit }

@Composable
private fun ButtonContent(text: String, glyph: ButtonGlyph?, icon: Painter?, ink: Color, gap: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        when {
            icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            glyph == ButtonGlyph.Save -> GlyphCanvas(Modifier.size(18.dp)) { save(ink, gap) }
            glyph == ButtonGlyph.Print -> GlyphCanvas(Modifier.size(20.dp)) { printer(ink, crossed = false, gap = gap) }
            glyph == ButtonGlyph.Sync -> GlyphCanvas(Modifier.size(20.dp)) { sync(ink) }
            glyph == ButtonGlyph.Submit -> GlyphCanvas(Modifier.size(18.dp)) { save(ink, gap) }
        }
        if (icon != null || glyph != null) Spacer(Modifier.width(8.dp))
        Text(text, style = lookStyle(18.sp, bold = true, color = ink, align = TextAlign.Center))
    }
}

/**
 * The big action button (PARITY-SPEC s2.8): red-to-purple gradient ([reverse]: purple to red, as the Summary Print), 8 dp radius,
 * white bold label with an optional leading [glyph] or [icon], at least 52 dp tall. Disabled: solid grey `#B8B8B8` with a white label
 * (the Sales-submit button before the sync, sr/sales-submit.png); the grey Print of Stock is [AronSecondaryButton].
 */
@Composable
fun AronPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: ButtonGlyph? = null,
    icon: Painter? = null,
    reverse: Boolean = false,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.ButtonRadius)
    val fill: Brush = if (enabled) (if (look.sunlight) SolidColor(look.purple) else look.barBrush(reverse)) else SolidColor(look.primaryDisabled)
    val ink = Color.White
    Box(
        modifier
            .heightIn(min = AronLook.ButtonHeight)
            .clip(shape)
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { ButtonContent(text, glyph, icon, ink, if (enabled) look.purple else look.primaryDisabled) }
}

/** The grey button (PARITY-SPEC s2.8, the Stock Print before a printer is connected): `#E8E8E8`, dark label, `#A5A5A5` when disabled. */
@Composable
fun AronSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: ButtonGlyph? = null,
    icon: Painter? = null,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.ButtonRadius)
    val ink = if (enabled) look.secondaryText else look.disabledText
    Box(
        modifier
            .heightIn(min = AronLook.ButtonHeight)
            .clip(shape)
            .background(look.disabledFill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { ButtonContent(text, glyph, icon, ink, look.disabledFill) }
}

@Composable
private fun StepSquare(fill: Color, plus: Boolean, onClick: () -> Unit, contentDescription: String, enabled: Boolean, modifier: Modifier) {
    val look = LocalAronLook.current
    Box(
        modifier
            .size(AronLook.Touch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(AronLook.Step).clip(RoundedCornerShape(AronLook.StepRadius)).background(if (enabled) fill else look.disabledFill), contentAlignment = Alignment.Center) {
            GlyphCanvas(Modifier.size(26.dp)) { bar(if (enabled) Color.White else look.disabledText, vertical = plus) }
        }
    }
}

/** The red rounded-square minus (36 dp drawn, 48 dp touch target; PARITY-SPEC s2.5). */
@Composable
fun AronMinus(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, enabled: Boolean = true) =
    StepSquare(LocalAronLook.current.minus, plus = false, onClick, contentDescription, enabled, modifier)

/** The purple rounded-square plus (36 dp drawn, 48 dp touch target; PARITY-SPEC s2.5). */
@Composable
fun AronPlus(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, enabled: Boolean = true) =
    StepSquare(LocalAronLook.current.purple, plus = true, onClick, contentDescription, enabled, modifier)

/**
 * The white info card at the top of Summary (sr/summary.png): a dark round "i", the [text] in 17 sp, and an optional [trailing]
 * slot (the light printer circle, [AronPrinterBadge]).
 */
@Composable
fun AronInfoCard(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Row(
        modifier.fillMaxWidth().clip(shape).background(look.card).border(1.dp, look.underline.copy(alpha = 0.4f), shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(look.inkMuted), contentAlignment = Alignment.Center) {
            GlyphCanvas(Modifier.size(24.dp)) { info(Color.White) }
        }
        Text(text, style = lookStyle(15.sp, color = look.ink), modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        trailing?.invoke()
    }
}

/**
 * The Home "Target and achievement" card shell (PARITY-SPEC s1 `aron.target`, sr/home-1-tiles.webp): vertical red-to-purple gradient,
 * white [title] and [label]. Targets are deferred (docs/27), so [percent] stays null until the programme returns: the bar is then
 * hidden and [value] (the day's sales) shows instead. [detailsLabel] with [onDetails] adds the underlined "details" link.
 */
@Composable
fun AronTargetCard(
    title: String,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    percent: Int? = null,
    percentText: String? = null,
    detailsLabel: String? = null,
    onDetails: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Brush.verticalGradient(listOf(look.targetTop, look.targetBottom))).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(title, style = lookStyle(22.sp, bold = true, color = Color.White), modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(label, style = lookStyle(16.sp, color = Color.White))
        if (value != null) Text(value, style = lookStyle(26.sp, bold = true, color = Color.White))
        if (percent != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 20.dp).clip(RoundedCornerShape(50)).background(look.track)) {
                Box(Modifier.fillMaxWidth(percent.coerceIn(1, 100) / 100f).heightIn(min = 20.dp).clip(RoundedCornerShape(50)).background(look.trackFill))
                if (percentText != null) Text(percentText, style = lookStyle(13.sp, bold = true, color = look.trackFill), modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
            }
        }
        if (detailsLabel != null && onDetails != null) {
            Box(Modifier.fillMaxWidth().heightIn(min = AronLook.Touch).clickable(role = Role.Button, onClick = onDetails), contentAlignment = Alignment.Center) {
                Text(detailsLabel, style = lookStyle(14.sp, color = Color.White).copy(textDecoration = TextDecoration.Underline))
            }
        }
    }
}

/**
 * The device status line of Sales submit (sr/sales-submit.png): [label] (`ডিভাইস স্ট্যাটাস`), a 20 dp dot, green when [online] and grey
 * otherwise, then [state] (`অনলাইন` / `অফলাইন`) in the dot's colour. End-aligned.
 */
@Composable
fun AronStatusDot(label: String, state: String, online: Boolean, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val tone = if (online) look.online else look.offlineDot
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = lookStyle(15.sp, color = look.ink))
        Box(Modifier.padding(horizontal = 8.dp).size(20.dp).clip(CircleShape).background(tone))
        Text(state, style = lookStyle(15.sp, color = tone, medium = true))
    }
}

/** The pink notice card of Sales submit (sr/sales-submit.png): `#E6C8CA`, red 4 dp edges left and right, 14 sp text. */
@Composable
fun AronNoticeCard(text: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.ButtonRadius)
    Box(
        modifier.fillMaxWidth().clip(shape).background(look.red).padding(horizontal = 4.dp).background(look.noticeFill).padding(horizontal = 12.dp, vertical = 12.dp),
    ) { Text(text, style = lookStyle(14.sp, color = look.ink)) }
}
