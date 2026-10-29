package com.aktcl.aron.feature.tso

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.aron.NightBar
import com.aktcl.aron.core.ui.aron.NightGlowCard
import com.aktcl.aron.core.ui.aron.NightOutlineCard
import com.aktcl.aron.core.ui.aron.NightRing
import com.aktcl.aron.core.ui.aron.NightRowCard
import com.aktcl.aron.core.ui.aron.nightGlow
import com.aktcl.aron.core.ui.aron.nightRoles
import com.aktcl.aron.core.ui.aron.nightText
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale

/** Why a card has no figures: offline with no kept copy, or refused by the server (403/404). */
enum class TsoFailure { NONE, OFFLINE, REFUSED }

/** One card's answer: [value] (fresh, or a kept copy when [kept]) and why it is missing. */
data class CardLoad<T>(val value: T?, val kept: Boolean = false, val failure: TsoFailure = TsoFailure.NONE)

/** What the Dashboard shows: one answer per card (s4.2), the channel card's mode, and the business day the figures are for. */
data class TsoDashboardState(
    val sales: CardLoad<SalesCards> = CardLoad(null),
    val geo: CardLoad<GeoCard> = CardLoad(null),
    val channelStd: CardLoad<ChannelCard> = CardLoad(null),
    val channelCalls: CardLoad<ChannelCard> = CardLoad(null),
    val cpr: CardLoad<CprCard> = CardLoad(null),
    val brand: CardLoad<BrandCprCard> = CardLoad(null),
    val login: CardLoad<LoginSubmitCard> = CardLoad(null),
    val segment: CardLoad<SegmentCard> = CardLoad(null),
    val businessDate: String = "",
) {
    private val stamps: List<String> get() = listOfNotNull(sales.value?.asOf, geo.value?.asOf, channelStd.value?.asOf, channelCalls.value?.asOf, cpr.value?.asOf, brand.value?.asOf, login.value?.asOf, segment.value?.asOf)

    /** The oldest server stamp among the cards on screen: the "as of" line never claims fresher figures than the oldest card. */
    val asOf: String? get() = stamps.minOrNull()

    /** True when at least one card is a kept copy (the server could not be reached). */
    val anyKept: Boolean get() = listOf(sales, geo, channelStd, channelCalls, cpr, brand, login, segment).any { it.kept && it.value != null }
}

/** Test tags of the Dashboard and of Login & Bikroy Joma Status. */
object TsoDashTags {
    const val LIST = "tso_dash_list"
    const val AS_OF = "tso_dash_as_of"
    const val SALES = "tso_dash_sales"
    const val GEO = "tso_dash_geo"
    const val CHANNEL = "tso_dash_channel"
    const val CHANNEL_SWITCH = "tso_dash_channel_switch"
    const val CPR = "tso_dash_cpr"
    const val BRAND = "tso_dash_brand"
    const val LOGIN = "tso_dash_login"
    const val SEGMENT = "tso_dash_segment"
    const val USERS = "tso_dash_users"
    fun tab(i: Int) = "tso_dash_tab_$i"
    fun sales(category: String) = "tso_dash_sales_$category"
}

@Composable
internal fun uiLocale(): UiLocale = if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN

/**
 * The TSO Dashboard (screenshots 6 and 7, D-TSO-MAN-09/10): the purple Sales card (Cigarette, Bidi, Lighter in Pieces, Match in
 * Dozen: Total Sales, Total Target and the daily ring), Geo Fencing, By Channel STD (▸ switches to By Channel Successful Call,
 * [onToggleChannel]), CPR, Brand CPR, Login & Bikroy Joma Status ([onLoginStatus] opens the lists of screenshot 8) and By Segment
 * Value Contribution. It reads when it is opened and on pull-to-refresh ([onRefresh]); never on a timer. An "as of" line under the
 * bar names the oldest server stamp, and a kept copy says so. Every figure comes from [TsoDashboards] (the shared rules).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TsoDashboardContent(
    state: TsoDashboardState?,
    refreshing: Boolean,
    channelMode: ChannelMode,
    onRefresh: () -> Unit,
    onToggleChannel: () -> Unit,
    onLoginStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(refreshing, onRefresh, modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(TsoDashTags.LIST),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (state == null) {
                TsoLoading(Modifier.heightIn(min = 240.dp))
                return@Column
            }
            AsOf(state)
            SalesCardView(state.sales)
            GeoCardView(state.geo)
            ChannelCardView(if (channelMode == ChannelMode.STD) state.channelStd else state.channelCalls, channelMode, onToggleChannel)
            CprCardView(state.cpr)
            BrandCprView(state.brand)
            LoginCardView(state.login, onLoginStatus)
            SegmentCardView(state.segment)
            Spacer(Modifier.height(TsoFabPadding))
        }
    }
}

@Composable
private fun AsOf(state: TsoDashboardState) {
    val n = nightRoles()
    val clock = TsoDashboards.asOfClock(state.asOf)?.let { localizedDigits(it) } ?: return
    val text = if (state.anyKept) stringResource(R.string.tso_dash_kept_copy, clock) else stringResource(R.string.tso_dash_as_of, clock)
    Text(text, Modifier.fillMaxWidth().testTag(TsoDashTags.AS_OF), style = nightText(13.sp, if (state.anyKept) n.warning else n.inkMuted, align = TextAlign.End))
}

/** The card frame when a card has no figures: its title and why (offline with no copy, refused, or nothing yet). */
@Composable
private fun MissingCard(title: String, failure: TsoFailure, tag: String) {
    val n = nightRoles()
    NightOutlineCard(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle(title)
        val why = when (failure) { TsoFailure.OFFLINE -> R.string.tso_dash_card_offline; TsoFailure.REFUSED -> R.string.tso_dash_card_refused; TsoFailure.NONE -> R.string.tso_dash_card_none }
        Text(stringResource(why), Modifier.fillMaxWidth(), style = nightText(15.sp, n.inkMuted, align = TextAlign.Center))
    }
}

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Center) {
    Text(text, modifier.fillMaxWidth().semantics { heading() }, style = nightText(18.sp, nightRoles().ink, semibold = true, align = align))
}

/** A cyan figure over its caption, read together by TalkBack ("40.0 Total Sales"). */
@Composable
private fun FigureBlock(figure: String, caption: String, modifier: Modifier = Modifier, figureColor: Color? = null, captionColor: Color? = null, figureSize: Int = 26) {
    val n = nightRoles()
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            figure, style = nightText(figureSize.sp, figureColor ?: n.figure, bold = true, align = TextAlign.Center), maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = figureSize.sp, stepSize = 1.sp),
        )
        Text(caption, style = nightText(15.sp, captionColor ?: n.label, align = TextAlign.Center), maxLines = 2)
    }
}

@Composable
private fun salesTitle(category: String): String = when (category) {
    "cigarette" -> stringResource(R.string.tso_dash_sales_cigarette)
    "bidi" -> stringResource(R.string.tso_dash_sales_bidi)
    "lighter" -> stringResource(R.string.tso_dash_sales_lighter)
    "match" -> stringResource(R.string.tso_dash_sales_match)
    else -> stringResource(R.string.tso_dash_sales_other, category)
}

/** Screenshot 6: one purple glow card, a section per category divided by a faint rule. */
@Composable
private fun SalesCardView(load: CardLoad<SalesCards>) {
    val v = load.value ?: return MissingCard(stringResource(R.string.tso_dash_sales_cigarette), load.failure, TsoDashTags.SALES)
    val n = nightRoles()
    val loc = uiLocale()
    NightGlowCard(Modifier.testTag(TsoDashTags.SALES), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        v.rows.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(n.label.copy(alpha = 0.18f)))
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp).testTag(TsoDashTags.sales(r.category)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle(salesTitle(r.category))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FigureBlock(TsoDashboards.totalSales(r, loc), stringResource(R.string.tso_dash_total_sales))
                        FigureBlock(TsoDashboards.totalTarget(r, v.workingDays, loc), stringResource(R.string.tso_dash_total_target))
                    }
                    NightRing(TsoDashboards.ringTenths(r, v.workingDays), TsoDashboards.ring(r, v.workingDays, loc), Modifier.padding(end = 8.dp), size = 132.dp)
                }
            }
        }
    }
}

/** Screenshot 6: Geo Fencing on a purple glow card: Geo Validation and Total Call at the start, the ring under its title at the end. */
@Composable
private fun GeoCardView(load: CardLoad<GeoCard>) {
    val v = load.value ?: return MissingCard(stringResource(R.string.tso_dash_geo), load.failure, TsoDashTags.GEO)
    val n = nightRoles()
    val loc = uiLocale()
    NightGlowCard(Modifier.testTag(TsoDashTags.GEO)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                FigureBlock(Formats.integer(v.geoValidated, loc), stringResource(R.string.tso_dash_geo_validation))
                FigureBlock(Formats.integer(v.totalCalls, loc), stringResource(R.string.tso_dash_total_call))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tso_dash_geo), style = nightText(15.sp, n.ink, semibold = true), modifier = Modifier.semantics { heading() })
                NightRing(TsoDashboards.geoTenths(v), Percent.text(TsoDashboards.geoTenths(v), 1, loc), size = 140.dp, stroke = 14.dp)
            }
        }
    }
}

@Composable
private fun channelPalette(): List<Color> { val n = nightRoles(); return listOf(n.pie, n.figure, n.valueLine, n.positive, n.barFillEnd, n.label) }

/**
 * Screenshot 6/7: By Channel STD ▸ (cigarette sticks by outlet channel) as a pie with one label per slice ("100.00% Astha
 * Channel", 2 decimals of the card total). The ▸ switches to By Channel Successful Call and back (D-TSO-MAN-41; the host keeps the
 * choice).
 */
@Composable
private fun ChannelCardView(load: CardLoad<ChannelCard>, mode: ChannelMode, onToggle: () -> Unit) {
    val n = nightRoles()
    val loc = uiLocale()
    val title = stringResource(if (mode == ChannelMode.STD) R.string.tso_dash_channel_std else R.string.tso_dash_channel_calls)
    val other = stringResource(if (mode == ChannelMode.STD) R.string.tso_dash_channel_calls else R.string.tso_dash_channel_std)
    val switchLabel = stringResource(R.string.tso_dash_channel_switch, other)
    NightOutlineCard(Modifier.testTag(TsoDashTags.CHANNEL), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = switchLabel, onClick = onToggle)
                .semantics { contentDescription = "$title, $switchLabel" }.testTag(TsoDashTags.CHANNEL_SWITCH),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = nightText(18.sp, n.ink, semibold = true))
            Spacer(Modifier.width(6.dp))
            Text("▸", style = nightText(18.sp, n.ink, bold = true))
        }
        val v = load.value
        if (v == null) {
            val why = when (load.failure) { TsoFailure.OFFLINE -> R.string.tso_dash_card_offline; TsoFailure.REFUSED -> R.string.tso_dash_card_refused; TsoFailure.NONE -> R.string.tso_dash_card_none }
            Text(stringResource(why), Modifier.fillMaxWidth(), style = nightText(15.sp, n.inkMuted, align = TextAlign.Center))
            return@NightOutlineCard
        }
        val slices = v.channels.filter { it.value > 0 }
        if (slices.isEmpty() || v.total <= 0) {
            Text(stringResource(R.string.tso_dash_no_points), Modifier.fillMaxWidth(), style = nightText(15.sp, n.inkMuted, align = TextAlign.Center))
            return@NightOutlineCard
        }
        val colors = channelPalette()
        val labels = slices.map { s ->
            stringResource(R.string.tso_dash_channel_label, Percent.text(TsoDashboards.channelBp(s, v.total), 2, loc), s.channel)
        }
        Box(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = labels.joinToString(", ") }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp)) {
                if (!n.solid) drawCircle(Brush.radialGradient(listOf(colors[0].copy(alpha = 0.45f), Color.Transparent), radius = size.minDimension * 0.62f), radius = size.minDimension * 0.62f)
                var start = -90f
                slices.forEachIndexed { i, s ->
                    // the angle is drawing only (tenths of a degree from the integers); the printed share is the integer bp above
                    val sweepTenths = TsoDashboards.sweepTenths(s, v.total)
                    val sweep = sweepTenths / 10f
                    drawArc(colors[i % colors.size], start, sweep, useCenter = true, topLeft = Offset(size.width * 0.1f, size.height * 0.1f), size = androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.8f))
                    start += sweep
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(colors[i % colors.size]))
                    Spacer(Modifier.width(8.dp))
                    Text(l, style = nightText(15.sp, n.ink))
                }
            }
        }
    }
}

/** Screenshot 7: CPR: Target Outlet (green) and Successful Calls (teal) at the start, the ring with "CPR" under the figure. */
@Composable
private fun CprCardView(load: CardLoad<CprCard>) {
    val v = load.value ?: return MissingCard(stringResource(R.string.tso_dash_cpr), load.failure, TsoDashTags.CPR)
    val n = nightRoles()
    val loc = uiLocale()
    NightOutlineCard(Modifier.testTag(TsoDashTags.CPR), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle(stringResource(R.string.tso_dash_cpr))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                FigureBlock(Formats.integer(v.targetOutlets, loc), stringResource(R.string.tso_dash_target_outlet), figureColor = n.ink, captionColor = n.positive)
                FigureBlock(Formats.integer(v.successfulCalls, loc), stringResource(R.string.tso_dash_successful_calls), figureColor = n.ink, captionColor = n.teal)
            }
            NightRing(TsoDashboards.cprTenths(v), Percent.text(TsoDashboards.cprTenths(v), 1, loc), caption = stringResource(R.string.tso_dash_cpr_caption), size = 140.dp, onOutline = true, textSize = 22.sp)
        }
    }
}

/** Screenshot 7: Brand CPR, one point per brand (outlets bought of the configured whole), y in whole percent. */
@Composable
private fun BrandCprView(load: CardLoad<BrandCprCard>) {
    val v = load.value ?: return MissingCard(stringResource(R.string.tso_dash_brand_cpr), load.failure, TsoDashTags.BRAND)
    val n = nightRoles()
    val loc = uiLocale()
    NightOutlineCard(Modifier.testTag(TsoDashTags.BRAND), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardTitle(stringResource(R.string.tso_dash_brand_cpr))
        val points = v.brands.map { b -> TsoDashboards.brandBp(b, v) }
        if (v.brands.isEmpty() || points.all { it == null }) {
            Text(stringResource(R.string.tso_dash_no_points), Modifier.fillMaxWidth(), style = nightText(15.sp, n.inkMuted, align = TextAlign.Center))
            return@NightOutlineCard
        }
        val described = v.brands.map { b -> stringResource(R.string.tso_dash_point, b.name, Percent.text(TsoDashboards.brandWhole(b, v), 0, loc)) }
        LineChart(listOf(points to n.valueLine), v.brands.map { it.name }, steps = 6, height = 280.dp, description = described.joinToString(", "))
    }
}

/** Screenshot 7: Login & Bikroy Joma Status: two rows with the icon, the whole percent, the bar and the two counts. Tapping opens screenshot 8. */
@Composable
private fun LoginCardView(load: CardLoad<LoginSubmitCard>, onOpen: () -> Unit) {
    val v = load.value ?: return MissingCard(stringResource(R.string.tso_dash_login_submit), load.failure, TsoDashTags.LOGIN)
    val loc = uiLocale()
    val open = stringResource(R.string.tso_dash_open_status)
    NightOutlineCard(
        Modifier.testTag(TsoDashTags.LOGIN).semantics { contentDescription = open }, onClick = onOpen, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CardTitle(stringResource(R.string.tso_dash_login_submit))
        StatusRow(
            AronIcons.Login, stringResource(R.string.tso_dash_login_status), TsoDashboards.loginWhole(v), loc,
            Formats.integer(v.targetRoutes, loc) to stringResource(R.string.tso_dash_target_route),
            Formats.integer(v.successfulLogin, loc) to stringResource(R.string.tso_dash_total_login),
        )
        StatusRow(
            AronIcons.UploadFile, stringResource(R.string.tso_dash_bikroy_status), TsoDashboards.bikroyWhole(v), loc,
            Formats.integer(v.successfulLogin, loc) to stringResource(R.string.tso_dash_login_count),
            Formats.integer(v.submitted, loc) to stringResource(R.string.tso_dash_total_bikroy),
        )
    }
}

@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, whole: Long?, loc: UiLocale, left: Pair<String, String>, right: Pair<String, String>) {
    val n = nightRoles()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).border(2.dp, n.positive, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = n.positive, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = nightText(16.sp, n.ink, bold = true))
                Text(Percent.text(whole, 0, loc), style = nightText(16.sp, n.teal, bold = true))
            }
            NightBar(whole)
            Row(Modifier.fillMaxWidth()) {
                listOf(left, right).forEach { (fig, cap) ->
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(fig, style = nightText(16.sp, n.teal, bold = true))
                        Text(cap, style = nightText(14.sp, n.inkMuted))
                    }
                }
            }
        }
    }
}

/** Screenshot 7: By Segment Value Contribution: the value line (orange) and the volume line (cyan), segments by value descending. */
@Composable
private fun SegmentCardView(load: CardLoad<SegmentCard>) {
    val v = load.value ?: return MissingCard(stringResource(R.string.tso_dash_segment), load.failure, TsoDashTags.SEGMENT)
    val n = nightRoles()
    val loc = uiLocale()
    NightOutlineCard(Modifier.testTag(TsoDashTags.SEGMENT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardTitle(stringResource(R.string.tso_dash_segment))
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(n.valueLine, stringResource(R.string.tso_dash_value))
            Spacer(Modifier.width(16.dp))
            Legend(n.volumeLine, stringResource(R.string.tso_dash_volume))
        }
        if (v.segments.isEmpty() || v.totalValueMtk <= 0) {
            Text(stringResource(R.string.tso_dash_no_points), Modifier.fillMaxWidth(), style = nightText(15.sp, n.inkMuted, align = TextAlign.Center))
            return@NightOutlineCard
        }
        val value = v.segments.map { TsoDashboards.valueBp(it, v) }
        val volume = v.segments.map { TsoDashboards.volumeBp(it, v) }
        val valueLabel = stringResource(R.string.tso_dash_value)
        val volumeLabel = stringResource(R.string.tso_dash_volume)
        val described = v.segments.map { g ->
            g.name + ": " + valueLabel + " " + Percent.text(TsoDashboards.valueWhole(g, v), 0, loc) + ", " + volumeLabel + " " + Percent.text(TsoDashboards.volumeWhole(g, v), 0, loc)
        }
        LineChart(listOf(value to n.valueLine, volume to n.volumeLine), v.segments.map { it.name }, steps = 5, height = 230.dp, description = described.joinToString("; "))
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    val n = nightRoles()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = nightText(14.sp, n.inkMuted))
    }
}

/**
 * A line chart of basis points (screenshot 7): the whole-percent ticks of [TsoDashboards.axisTicks] at the start, faint vertical grid
 * lines, one coloured line per series and the x labels slanted under it. Heights are drawing only; TalkBack reads [description].
 */
@Composable
private fun LineChart(series: List<Pair<List<Long?>, Color>>, xLabels: List<String>, steps: Int, height: Dp, description: String) {
    val n = nightRoles()
    val loc = uiLocale()
    val maxBp = series.flatMap { it.first }.filterNotNull().maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val ticks = TsoDashboards.axisTicks(maxBp, steps)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().height(height)) {
            Column(Modifier.widthIn(min = 40.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                ticks.reversed().forEach { t -> Text(Percent.text(t, 0, loc), style = nightText(11.sp, n.inkMuted)) }
            }
            Spacer(Modifier.width(6.dp))
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 7.dp)) {
                val count = xLabels.size
                val dx = if (count > 1) size.width / (count - 1) else 0f
                fun x(i: Int) = if (count > 1) i * dx else size.width / 2f
                for (i in 0 until count) drawLine(n.gridLine, Offset(x(i), 0f), Offset(x(i), size.height), strokeWidth = 1.dp.toPx())
                for (k in 0..steps) { val y = size.height * k / steps; drawLine(n.gridLine.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx()) }
                series.forEach { (values, color) ->
                    val path = Path()
                    var started = false
                    values.forEachIndexed { i, bp ->
                        if (bp == null) return@forEachIndexed
                        val y = size.height * (1f - (bp.toFloat() / maxBp.toFloat()))
                        if (!started) { path.moveTo(x(i), y); started = true } else path.lineTo(x(i), y)
                        if (count == 1) drawCircle(color, 4.dp.toPx(), Offset(x(i), y))
                    }
                    drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp).padding(start = 46.dp)) {
            val count = xLabels.size
            xLabels.forEachIndexed { i, l ->
                val frac = if (count > 1) i.toFloat() / (count - 1) else 0.5f
                Text(
                    l, maxLines = 1, overflow = TextOverflow.Ellipsis, style = nightText(11.sp, n.inkMuted),
                    modifier = Modifier.widthIn(max = 96.dp).offset(x = maxWidth * frac - 24.dp, y = 6.dp).graphicsLayer(rotationZ = -30f),
                )
            }
        }
    }
}

/**
 * Login & Bikroy Joma Status (screenshot 8, D-TSO-MAN-12): the two tabs (Not Logged In, Not Uploaded), then one card per (route,
 * user) row: the display name ("SR Not Set" for a vacant route), User Name, Route and Dep Name (the zone). [tab] is the selected tab;
 * the host reads `login-submit/users?tab=` when a tab is opened and on pull-to-refresh, with an "as of" line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TsoLoginSubmitStatusContent(
    tab: LoginSubmitTab,
    load: CardLoad<LoginSubmitUsers>?,
    refreshing: Boolean,
    onTab: (LoginSubmitTab) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = nightRoles()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoginSubmitTab.entries.forEachIndexed { i, t ->
                val on = t == tab
                Box(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                        .then(if (on) Modifier.border(1.5.dp, n.outlineEdge, RoundedCornerShape(10.dp)).nightGlow(n, n.outlineEdge, RoundedCornerShape(10.dp), 4.dp) else Modifier)
                        .selectable(selected = on, role = Role.Tab, onClick = { onTab(t) }).testTag(TsoDashTags.tab(i)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(if (t == LoginSubmitTab.NOT_LOGGED_IN) R.string.tso_dash_tab_not_logged_in else R.string.tso_dash_tab_not_uploaded), style = nightText(17.sp, if (on) n.teal else n.inkMuted, semibold = on))
                }
            }
        }
        PullToRefreshBox(refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(TsoDashTags.USERS),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val v = load?.value
                when {
                    load == null -> TsoLoading(Modifier.heightIn(min = 200.dp))
                    v == null -> TsoNote(stringResource(if (load.failure == TsoFailure.REFUSED) R.string.tso_dash_card_refused else R.string.tso_dash_card_offline))
                    else -> {
                        TsoDashboards.asOfClock(v.asOf)?.let { c ->
                            val clock = localizedDigits(c)
                            Text(
                                if (load.kept) stringResource(R.string.tso_dash_kept_copy, clock) else stringResource(R.string.tso_dash_as_of, clock),
                                Modifier.fillMaxWidth().testTag(TsoDashTags.AS_OF), style = nightText(13.sp, if (load.kept) n.warning else n.inkMuted, align = TextAlign.End),
                            )
                        }
                        if (v.items.isEmpty()) TsoNote(stringResource(R.string.tso_dash_users_empty))
                        v.items.forEach { u -> UserRow(u) }
                        if (v.truncated) Text(stringResource(R.string.tso_dash_truncated, localizedNumber(v.items.size.toLong())), style = nightText(13.sp, n.inkMuted))
                    }
                }
                Spacer(Modifier.height(TsoFabPadding))
            }
        }
    }
}

@Composable
private fun UserRow(u: LoginSubmitUser) {
    val n = nightRoles()
    NightRowCard(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(n.outlineCard), contentAlignment = Alignment.Center) {
                Icon(AronIcons.Person, contentDescription = null, tint = n.teal.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(u.displayName ?: stringResource(R.string.tso_dash_sr_not_set), style = nightText(18.sp, n.ink, bold = true))
                Text(stringResource(R.string.tso_dash_user_name, u.username ?: stringResource(R.string.tso_dash_dash)), style = nightText(14.sp, n.inkMuted))
                Text(stringResource(R.string.tso_dash_user_route, u.routeName), style = nightText(14.sp, n.inkMuted))
                Text(stringResource(R.string.tso_dash_user_dep, u.depName), style = nightText(14.sp, n.inkMuted))
            }
        }
    }
}

/** A white-ink note on the navy outline card (empty lists, refusals). */
@Composable
internal fun TsoNightNote(text: String, modifier: Modifier = Modifier) {
    val n = nightRoles()
    NightOutlineCard(modifier) { Text(text, style = nightText(15.sp, n.ink)) }
}
