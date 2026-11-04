package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.aktcl.aron.core.common.AppLanguage
import androidx.compose.ui.text.font.FontFamily
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
internal fun lookStyle(
    size: TextUnit, bold: Boolean = false, color: Color = Color.Unspecified, align: TextAlign = TextAlign.Unspecified, medium: Boolean = false,
    semibold: Boolean = false,
) = TextStyle(
    // Bangla: the bundled Noto Sans Bengali (its Latin glyphs stay: core-printing shares the files); English: the system Roboto,
    // the reference app's Latin face
    fontFamily = if (LocalAppLanguage.current == AppLanguage.BN) AronFonts.Bengali else FontFamily.Default,
    fontSize = size, color = color, textAlign = align,
    fontWeight = when {
        bold -> FontWeight.Bold
        semibold -> FontWeight.SemiBold
        medium -> FontWeight.Medium
        else -> FontWeight.Normal
    },
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
                Text(title, style = lookStyle(22.sp, semibold = true, color = look.onBar, align = TextAlign.Center), modifier = Modifier.semantics { heading() })
            }
            if (trailing != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { trailing() }
        }
        if (hanging != null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp).offset(y = AronLook.Touch / 2)) { hanging() }
    }
}

/**
 * The printer state at the end of the top bar (sr/stock.webp): a light round badge with a red crossed printer while no printer is
 * connected, a purple printer once one is. [label] is the TalkBack text of the state; tapping it opens the connect flow. Inside an
 * [AronInfoCard] it is drawn flat, grey and with a smaller printer, as on sr/summary.png.
 */
@Composable
fun AronPrinterBadge(connected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val inCard = LocalBadgeInCard.current
    val chip = if (inCard) AronLook.Touch + 4.dp else 40.dp
    Box(
        modifier
            .size(maxOf(chip, AronLook.Touch))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // the bar chip is white with a soft shadow (Vision QA: smaller and white); in a card it is flat grey (sr/summary.png)
        Box(
            Modifier.size(chip).then(if (inCard) Modifier else Modifier.shadow(3.dp, CircleShape)).clip(CircleShape).background(if (inCard) look.page else look.card),
            contentAlignment = Alignment.Center,
        ) {
            GlyphCanvas(Modifier.size(20.dp)) {
                printer(if (connected) look.printerOn else look.printerOff, crossed = !connected, gap = if (inCard) look.page else look.card)
            }
        }
    }
}

/** True for the buttons of [AronScaffold] that sit on the purple panel: [AronPrimaryButton] then ends its ramp at `panelButtonEnd`. */
private val LocalOnPanel = staticCompositionLocalOf { false }

/** True inside [AronInfoCard]'s trailing slot: [AronPrinterBadge] draws its flat in-card form there. */
private val LocalBadgeInCard = staticCompositionLocalOf { false }

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
                    CompositionLocalProvider(LocalOnPanel provides (bottomPanel != null)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = bottomButtons)
                    }
                }
            }
        }
    }
}

/**
 * The Home header (PARITY-SPEC s2.3, sr/home-1-tiles.webp): the gradient, a white round avatar with the person glyph (or [avatar]),
 * [title] = "Role - Name (login)" in white medium 15 sp and [subtitle] = route and date in medium 12 sp, each on ONE line (shrinking to
 * 12 / 10 sp to fit, as on the reference), a 40 dp avatar and a 28 dp gear (Vision QA), and the white settings gear at the end
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
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(look.card), contentAlignment = Alignment.Center) {
            if (avatar != null) Image(avatar, contentDescription = null, modifier = Modifier.size(40.dp))
            else GlyphCanvas(Modifier.size(30.dp)) { person(look.purple) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            FitText(title, lookStyle(15.sp, medium = true, color = look.onBar), 12.sp, maxLines = 2, oneLineDown = 12.sp)
            FitText(subtitle, lookStyle(12.sp, medium = true, color = look.onBar), 10.sp, maxLines = 2, oneLineDown = 10.sp)
        }
        Box(
            Modifier.size(AronLook.Touch).testTag(AronHomeSettingsTag).clickable(role = Role.Button, onClick = onSettings).semantics { contentDescription = settingsLabel },
            contentAlignment = Alignment.Center,
        ) { GlyphCanvas(Modifier.size(28.dp)) { gear(look.onBar, hole = if (look.sunlight) look.card else look.barEnd) } }
    }
}

/** Test tag of the [AronHomeHeader] settings gear (lanes find it with `onNodeWithTag(AronHomeSettingsTag)`). */
const val AronHomeSettingsTag = "aron_home_settings"

/** The leading glyph of a button (sr/stock.webp: Save has a disk, Print a printer). */
enum class ButtonGlyph { Save, Print, Sync, Submit }

@Composable
private fun ButtonContent(text: String, glyph: ButtonGlyph?, icon: Painter?, ink: Color, gap: Color, bold: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        when {
            icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            glyph == ButtonGlyph.Save -> GlyphCanvas(Modifier.size(13.dp)) { save(ink, gap) }  // about 0.6 of the label (sr/stock.webp)
            glyph == ButtonGlyph.Print -> GlyphCanvas(Modifier.size(15.dp)) { printer(ink, crossed = false, gap = gap) }
            glyph == ButtonGlyph.Sync -> GlyphCanvas(Modifier.size(20.dp)) { sync(ink) }
            glyph == ButtonGlyph.Submit -> GlyphCanvas(Modifier.size(18.dp)) { save(ink, gap) }
        }
        if (icon != null || glyph != null) Spacer(Modifier.width(6.dp))
        Text(text, style = lookStyle(18.sp, bold = bold, color = ink, align = TextAlign.Center))
    }
}

/**
 * The big action button (PARITY-SPEC s2.8): red-to-purple gradient ([reverse]: purple to red, as the Summary Print), 8 dp radius,
 * white bold label with an optional leading [glyph] or [icon], at least 52 dp tall. On the purple panel of [AronScaffold] the ramp ends
 * at about `#7D2B7B` (sr/stock.webp); [reverse] uses the Summary Print stops `#4A1C99` to `#A73964`. Disabled: solid grey `#B8B8B8` with a white label
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
    val onPanel = LocalOnPanel.current
    val fill: Brush = when {
        !enabled -> SolidColor(look.primaryDisabled)
        look.sunlight -> SolidColor(look.purple)
        // sr/summary.png Print: #4A1C99 to #A73964
        reverse -> Brush.horizontalGradient(listOf(look.printStart, look.printEnd))
        // sr/stock.webp Save on the purple panel: only the first part of the ramp, ending about #7D2B7B, so the edge stays visible
        onPanel -> Brush.horizontalGradient(listOf(look.barStart, look.panelButtonEnd))
        else -> look.barBrush()
    }
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

/** The grey button (PARITY-SPEC s2.8, the Stock Print before a printer is connected): `#E8E8E8`, dark bold label; disabled `#595959` regular (sr/stock.webp). */
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
    val ink = if (enabled) look.secondaryText else look.secondaryDisabledText
    Box(
        modifier
            .heightIn(min = AronLook.ButtonHeight)
            .clip(shape)
            .background(look.disabledFill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { ButtonContent(text, glyph, icon, ink, look.disabledFill, bold = enabled) }
}

@Composable
private fun StepSquare(fill: Color, plus: Boolean, onClick: () -> Unit, contentDescription: String, enabled: Boolean, modifier: Modifier, keepColour: Boolean = false) {
    val look = LocalAronLook.current
    Box(
        modifier
            .size(AronLook.Touch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val live = enabled || keepColour
        Box(Modifier.size(AronLook.Step).clip(RoundedCornerShape(AronLook.StepRadius)).background(if (live) fill else look.disabledFill), contentAlignment = Alignment.Center) {
            GlyphCanvas(Modifier.size(26.dp)) { bar(if (live) Color.White else look.disabledText, vertical = plus) }
        }
    }
}

/** The red rounded-square minus (36 dp drawn, 48 dp touch target; PARITY-SPEC s2.5). It stays red at zero ([enabled] = false: no-op, TalkBack says disabled), as on sr/stock.webp. */
@Composable
fun AronMinus(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, enabled: Boolean = true) =
    StepSquare(LocalAronLook.current.minus, plus = false, onClick, contentDescription, enabled, modifier, keepColour = true)

/** The purple rounded-square plus (36 dp drawn, 48 dp touch target; PARITY-SPEC s2.5). */
@Composable
fun AronPlus(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, enabled: Boolean = true) =
    StepSquare(LocalAronLook.current.purple, plus = true, onClick, contentDescription, enabled, modifier)

/**
 * The white info card at the top of Summary (sr/summary.png): a dark round "i", the [text] at the top, and an optional [trailing]
 * slot (the light printer circle, [AronPrinterBadge]).
 */
@Composable
fun AronInfoCard(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    // sr/summary.png: 1 dp #C0C0C0 border, no shadow, the text at the top, the printer circle cut by the card's bottom edge
    Row(
        modifier.fillMaxWidth().clip(shape).background(look.card).border(1.dp, look.infoBorder, shape).padding(start = 16.dp, end = 16.dp, top = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape).background(look.inkMuted), contentAlignment = Alignment.Center) {
            GlyphCanvas(Modifier.size(20.dp)) { info(Color.White) }
        }
        Text(text, style = lookStyle(15.sp, color = look.ink), modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 16.dp))
        if (trailing != null) {
            Box(Modifier.padding(top = 10.dp).offset(y = 12.dp)) { CompositionLocalProvider(LocalBadgeInCard provides true) { trailing() } }
        }
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
            // the figure follows the red fill on the grey track (sr/home-1-tiles.webp); past 75 % it sits inside the fill, in white
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 20.dp).clip(RoundedCornerShape(50)).background(look.track)) {
                val fraction = percent.coerceIn(1, 100) / 100f
                val fill = maxWidth * fraction
                Box(Modifier.width(fill).heightIn(min = 20.dp).clip(RoundedCornerShape(50)).background(look.trackFill))
                if (percentText != null) {
                    val inside = fraction > 0.75f
                    Text(
                        percentText,
                        style = lookStyle(13.sp, bold = true, color = if (inside) Color.White else look.trackFill),
                        modifier = if (inside) Modifier.align(Alignment.CenterStart).width(fill).padding(end = 8.dp).wrapContentWidth(Alignment.End)
                        else Modifier.align(Alignment.CenterStart).padding(start = fill + 6.dp),
                    )
                }
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

/**
 * The pink notice card of Sales submit (sr/sales-submit.png): `#E6C8CA`, red 4 dp edges left and right, 14 sp text. With [action] and
 * [onAction] it ends in a purple text button (a notice with an action, e.g. "retry").
 */
@Composable
fun AronNoticeCard(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.ButtonRadius)
    Row(
        modifier.fillMaxWidth().clip(shape).background(look.red).padding(horizontal = 4.dp).background(look.noticeFill).padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = lookStyle(14.sp, color = look.ink), modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (action != null && onAction != null) AronTextButton(action, onAction)
    }
}

// tso-manual A1: the TSO top bar (D-TSO-MAN-13, screenshots 8, 17, 24, 39)
/**
 * The top bar of every TSO screen opened from the drawer: on the navy page (no gradient band), the grey round back button at the
 * start ([NightBackButton], shown when [onBack] is set; [backLabel] is its TalkBack name), the green-to-yellow gradient [title]
 * centred ([NightTitle], two lines at most), and an optional [trailing] slot at the end. In Sunlight the page is light and a 2 dp ink
 * rule closes the bar.
 */
@Composable
fun AronNightTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val look = LocalAronLook.current
    val n = look.nightRoles
    Box(
        modifier
            .fillMaxWidth()
            .background(n.page)
            .then(if (n.solid) Modifier.drawBehind { drawRect(n.ink, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) } else Modifier)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 76.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (onBack != null) NightBackButton(onBack, backLabel ?: "", Modifier.align(Alignment.CenterStart))
        NightTitle(title, Modifier.align(Alignment.Center).padding(horizontal = 60.dp))
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}
