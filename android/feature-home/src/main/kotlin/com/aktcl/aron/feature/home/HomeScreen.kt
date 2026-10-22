package com.aktcl.aron.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.SolidColor
import com.aktcl.aron.core.ui.aron.aronTileColumns
import com.aktcl.aron.core.ui.aron.verticalBrush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronHomeHeader
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronTargetCard
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.core.ui.aron.AronTileGrid
import com.aktcl.aron.core.ui.R as CoreR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronStatusChip
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.ChipForm
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalReducedMotion
import com.aktcl.aron.core.ui.SunlightToggle
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.core.ui.skeleton

/**
 * Test tags of Home. [TITLE] is the whole glass top bar (the kit's `AronTopBar` has no slot modifiers for its title and
 * subtitle texts), so the header's words are reached by text; [SUBTITLE] is kept for earlier callers but is not applied
 * to a node for the same reason: find the caption lines by text.
 */
object HomeScreenTags {
    const val TITLE = "hm_title"
    const val SUBTITLE = "hm_subtitle"
    const val BANNER = "hm_banner"
    const val HEALTH = "hm_health"
    const val SETTINGS = "hm_settings"
    const val HERO = "hm_hero"
    const val HERO_SKELETON = "hm_hero_skeleton"
    const val DOCK = "hm_dock"
    const val CHIP = "hm_chip"
    const val DAY_DONE = "hm_day_done"
    const val KPI = "hm_kpi"
    const val STOCK = "hm_stock"
    const val MONEY = "hm_money"
    const val DISCOUNT = "hm_money_discount"
    const val QC = "hm_money_qc"
    const val SUNLIGHT = "hm_sunlight"
    fun tile(t: HomeTile) = "hm_tile_${t.key}"
}

@Composable
private fun tileLabel(t: HomeTile): String = stringResource(
    when (t) {
        HomeTile.ATTENDANCE -> R.string.hm_tile_attendance
        HomeTile.STOCK -> R.string.hm_tile_stock
        HomeTile.SALE -> R.string.hm_tile_sale
        HomeTile.MEMO -> R.string.hm_tile_memo
        HomeTile.SUMMARY -> R.string.hm_tile_summary
        HomeTile.SALES_SUBMIT -> R.string.hm_tile_sales_submit
        HomeTile.OUTLET -> R.string.hm_tile_outlet
        HomeTile.TUTORIAL -> R.string.hm_tile_tutorial
        HomeTile.TASKS -> R.string.hm_tile_tasks
        HomeTile.PHOTO_CAPTURE -> R.string.hm_tile_photo_capture
        HomeTile.SALES_JOURNEY -> R.string.hm_tile_sales_journey
        HomeTile.KPI -> R.string.hm_tile_kpi
    },
)

/** The glyph of each tile (screens-sr s2 wireframe); the dock borrows the glyph of the tile it opens. */
fun tileIcon(t: HomeTile): ImageVector = when (t) {
    HomeTile.ATTENDANCE -> AronIcons.Attendance
    HomeTile.STOCK -> AronIcons.Stock
    HomeTile.SALE -> AronIcons.Sale
    HomeTile.MEMO -> AronIcons.Memo
    HomeTile.SUMMARY -> AronIcons.Summary
    HomeTile.SALES_SUBMIT -> AronIcons.Submit
    HomeTile.OUTLET -> AronIcons.Outlet
    HomeTile.TUTORIAL -> AronIcons.Tutorial
    HomeTile.TASKS -> AronIcons.Tasks
    HomeTile.PHOTO_CAPTURE -> AronIcons.Camera
    HomeTile.SALES_JOURNEY -> AronIcons.Journey
    HomeTile.KPI -> AronIcons.Kpi
}

@Composable
fun dockLabel(dock: HomeDock): String = stringResource(
    when (dock) {
        HomeDock.CHECK_IN -> R.string.hm_dock_check_in
        HomeDock.TAKE_STOCK -> R.string.hm_dock_take_stock
        HomeDock.START_SELLING -> R.string.hm_dock_start_selling
        HomeDock.SUBMIT -> R.string.hm_dock_submit
        HomeDock.CHECK_OUT -> R.string.hm_dock_check_out
    },
)

@Composable
private fun routeKindLabel(k: RouteKind?): String? = k?.let {
    stringResource(when (it) { RouteKind.DAILY -> R.string.hm_route_daily; RouteKind.THREE_F -> R.string.hm_route_3f; RouteKind.TWO_F -> R.string.hm_route_2f })
}

/** Below this window height the compact variant applies (screens-sr s0): one-line hero meta dropped, device line hidden. */
private const val COMPACT_WINDOW_HEIGHT_DP = 700

/** The floor of a shrunk quantity in the stock card (tokens s7.3: numbers shrink to fit, never wrap or clip). */
private val QuantityFloorSp = 12.sp

/** TalkBack reading order (screens-sr s0): header, status chip, hero, dock, tiles, cards. */
private object Traversal {
    const val HEADER = 0f; const val STATUS = 1f; const val HERO = 2f; const val DOCK = 3f; const val TILES = 4f; const val CARDS = 5f
}

private fun Modifier.traversal(index: Float): Modifier = semantics { isTraversalGroup = true; traversalIndex = index }

/**
 * The SR Home (screens-sr s2; F-SR-008 header, F-SR-009 tiles with the task badge, F-SR-063 banners, F-SR-064 device
 * line, F-SR-010/069 KPI, stock and money cards). Everything comes from local state: nothing here waits for the network.
 * The header is the glass top bar (title `SR - name (username)`, the route name over `<ISO date> · <route kind>` under
 * it, the sun switch and the gear); then the status row, the hero, the two tile groups, the cards and the device line;
 * the next-step dock is the bottom bar and the tile it opens carries the accent ring. Sections that are not given
 * ([hero], [kpis], [money], [dock]) are simply absent, so every earlier caller and test keeps compiling. When the bundle
 * is expired or missing, the tiles that need a sale are disabled and so is a dock that would open one; attendance
 * always works. Under 700 dp of window height the compact variant drops the hero's meta line and the device line.
 */
@Composable
fun HomeContent(
    header: HomeHeader,
    tiles: List<ResolvedTile>,
    freshness: BundleFreshness,
    offline: Boolean,
    health: DeviceHealth?,
    onTile: (HomeTile) -> Unit,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
    banner: String? = null,
    hero: HomeHero? = null,
    kpis: HomeKpis? = null,
    money: HomeMoneyLines? = null,
    sync: SyncChipState? = null,
    onSync: (() -> Unit)? = null,
    sunlight: Boolean = false,
    onSunlight: ((Boolean) -> Unit)? = null,
    dock: HomeDock? = null,
    /** AKTCL's route kind wording ("Daily", "3F", "2F"); null falls back to the localized resource of [HomeHeader.routeKind]. */
    routeKindLabel: String? = null,
    /** The hero and KPI reads are still running: a skeleton holds the hero's place (never a spinner). */
    loading: Boolean = false,
    reauthRequired: Boolean = false,
    updateRequired: Boolean = false,
    /** No route is planned for the date (screens-sr s2 "No route today"): an info banner, the hero shows zero. */
    noRoute: Boolean = false,
    /** The hero's tap target (the Summary tile by default). */
    onHero: (() -> Unit)? = null,
) {
    val kind = routeKindLabel ?: routeKindLabel(header.routeKind)
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_WINDOW_HEIGHT_DP
    val reduced = LocalReducedMotion.current
    // the reference subtitle: `<route name>, <yyyy-MM-dd>` with Latin digits, nothing else (sr/home.md)
    val subtitle = header.subtitle
    val dockBlocked = dock != null && dock.target in SELLING_TILES && !freshness.canSell
    val look = LocalAronLook.current
    // the reference header (sr/home-1-tiles.webp): the title and the two caption lines are one gradient block with the gear
    AronScaffold(
        modifier = modifier,
        home = true,
        topBar = {
            Column(Modifier.testTag(HomeScreenTags.TITLE).traversal(Traversal.HEADER)) {
                AronHomeHeader(
                    title = header.title, subtitle = subtitle,
                    onSettings = { onSettings?.invoke() }, settingsLabel = stringResource(R.string.set_open),
                    modifier = Modifier.testTag(HomeScreenTags.SETTINGS),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = AronTokens.Space.Xl)) {
            when (freshness) {
                BundleFreshness.Fresh -> Unit
                is BundleFreshness.Stale -> HomeBanner(stringResource(R.string.hm_banner_stale, localizedNumber(freshness.ageDays.toLong())), BannerKind.Warning, Modifier.testTag(HomeScreenTags.BANNER))
                is BundleFreshness.Expired -> HomeBanner(stringResource(R.string.hm_banner_expired, localizedNumber(freshness.ageDays.toLong())), BannerKind.Warning, Modifier.testTag(HomeScreenTags.BANNER))
                BundleFreshness.Missing -> HomeBanner(stringResource(R.string.hm_banner_missing), BannerKind.Warning, Modifier.testTag(HomeScreenTags.BANNER))
            }
            if (noRoute) HomeBanner(stringResource(R.string.hm_no_route), BannerKind.Info)
            banner?.let { HomeBanner(it, BannerKind.Info) }
            if (offline) HomeBanner(stringResource(R.string.hm_banner_offline), BannerKind.Offline)
            if (reauthRequired) HomeBanner(stringResource(R.string.home_reauth_required), BannerKind.Warning)
            if (updateRequired) HomeBanner(stringResource(R.string.home_update_required), BannerKind.Warning)
            if (hero?.dayState == HomeDayState.COMPLETE) HomeBanner(stringResource(R.string.hm_day_complete), BannerKind.Success, Modifier.testTag(HomeScreenTags.DAY_DONE))
            // the tile grid of the reference: 4 columns, one list, the order of the screenshot; Astha and Loyalty have no tile (docs/27)
            Column(Modifier.fillMaxWidth().padding(horizontal = HOME_GUTTER, vertical = AronTokens.Space.S).traversal(Traversal.TILES)) {
                AronTileGrid(tiles) { rt, mod -> HomeTileItem(rt, freshness, dock, mod, onTile) }
            }
            // the "Target and achievement" card shell: the day's sales and the outlet progress; no target percentage (deferred, docs/27)
            if (hero != null || loading) {
                val visited = hero?.visited ?: 0
                val planned = hero?.target ?: 1
                val meterText = stringResource(R.string.hm_meter_value, localizedNumber(visited.toLong()), localizedNumber(planned.toLong()))
                val spoken = stringResource(R.string.hm_meter_spoken, localizedNumber(visited.toLong()), localizedNumber(planned.toLong()))
                AronTargetCard(
                    title = stringResource(R.string.hm_target_title), label = stringResource(R.string.hm_hero_total),
                    value = hero?.grandTotalText ?: localizedNumber(0L),
                    percentText = meterText,
                    percent = if (planned > 0) (visited * 100 / planned).coerceAtLeast(1) else null,
                    detailsLabel = stringResource(R.string.hm_target_details), onDetails = onHero ?: { onTile(HomeTile.SUMMARY) },
                    modifier = Modifier
                        .padding(horizontal = HOME_GUTTER, vertical = AronTokens.Space.S)
                        .testTag(if (hero != null) HomeScreenTags.HERO else HomeScreenTags.HERO_SKELETON)
                        .traversal(Traversal.HERO).semantics { stateDescription = spoken }
                        .skeleton(hero == null),
                )
            }
            // the lavender container of the reference: the six KPI tiles, then the gradient sales panel (sr/home-2-kpis.webp)
            Column(Modifier.fillMaxWidth().traversal(Traversal.CARDS)) {
                if (kpis != null || money != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = HOME_GUTTER, vertical = AronTokens.Space.S).clip(RoundedCornerShape(16.dp)).background(KpiContainer).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (kpis != null) KpiGrid(hero, kpis)
                        if (money != null) SalesPanel(money)
                    }
                }
                money?.let { DiscountPanel(it) }
            }
        }
    }
}

private val HOME_GUTTER = 12.dp

private val KpiContainer = Color(0xFFF7F4FA)
private val KpiOutline = Color(0xFF5C3DA8)

/**
 * The six KPI tiles of the reference (sr/home-2-kpis.webp): four to a row, each a white 12 dp tile with a 1 dp indigo outline, a
 * big black numeral over a small indigo label. Order: outlets visited of planned, strike rate, issue, current stock, non-visit,
 * no sale. Issue and current stock are listed per category in the category's own unit (packets, boxes, dozens; never added across
 * units, docs/34 s4.2), with any base-unit remainder in small text.
 */
@Composable
private fun KpiGrid(hero: HomeHero?, k: HomeKpis) {
    val visited = hero?.visited ?: 0
    val planned = hero?.target ?: 0
    val issueLabel = stringResource(R.string.hm_stock_issue)
    val currentLabel = stringResource(R.string.hm_stock_current)
    val cells = listOf<Pair<String?, String>>(
        stringResource(R.string.hm_meter_value, localizedNumber(visited.toLong()), localizedNumber(planned.toLong())) to stringResource(R.string.hm_hero_meter),
        (k.strikeRateText?.let { localizedDigits(it) } ?: stringResource(R.string.hm_kpi_none)) to stringResource(R.string.hm_kpi_strike),
        null to stringResource(R.string.hm_stock_issue),
        null to stringResource(R.string.hm_stock_current),
        localizedNumber(k.nonVisit.toLong()) to stringResource(R.string.hm_kpi_non_visit),
        localizedNumber(k.noSale.toLong()) to stringResource(R.string.hm_kpi_no_sale),
    )
    val columns = aronTileColumns(LocalConfiguration.current.screenWidthDp.toFloat(), LocalDensity.current.fontScale)
    Column(Modifier.fillMaxWidth().testTag(HomeScreenTags.KPI), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    when (label) {
                        issueLabel -> StockKpiTile(label, k.stock.filter { it.issueBase != 0L || it.issueRemBase != 0L }.map { it.categoryLabel to figureText(it.issueBase, it.issueRemBase, it) }, Modifier.weight(1f).fillMaxHeight())
                        currentLabel -> StockKpiTile(label, k.stock.filter { it.currentBase != 0L || it.currentRemBase != 0L }.map { it.categoryLabel to figureText(it.currentBase, it.currentRemBase, it) }, Modifier.weight(1f).fillMaxHeight())
                        else -> KpiTile(label, value.orEmpty(), Modifier.weight(1f).fillMaxHeight())
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** "12 packets" or "12 packets + 5 sticks": whole entry units, then the base-unit remainder (never floored away). */
@Composable
private fun figureText(whole: Long, remBase: Long, line: HomeStockLine): Pair<String, String> =
    stringResource(R.string.hm_qty_unit, localizedNumber(whole), line.unitLabel) to com.aktcl.aron.core.ui.remWithUnit(remBase, line.baseUnitLabel)

/** The Issue and Current-stock tiles: one line per category in its own unit; "0" when nothing is loaded. */
@Composable
private fun StockKpiTile(label: String, lines: List<Pair<String, Pair<String, String>>>, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val look = LocalAronLook.current
    Column(
        modifier.heightIn(min = 84.dp).clip(shape).background(look.card).border(1.dp, if (look.sunlight) look.purple else KpiOutline, shape).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (lines.isEmpty()) Text(localizedNumber(0), color = look.ink, fontSize = 26.sp, maxLines = 1)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            lines.forEach { (cat, figure) ->
                Text(cat, color = look.tileLabel, fontSize = 11.sp, textAlign = TextAlign.Center)
                // the whole units on one line, the base remainder small on its own line: nothing clips at 360 dp or at large text
                Text(
                    figure.first, color = look.ink, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 15.sp, stepSize = 1.sp),
                )
                if (figure.second.isNotEmpty()) Text(
                    figure.second, color = look.ink, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp, stepSize = 1.sp),
                )
                Spacer(Modifier.height(3.dp))
            }
        }
        Text(label, color = look.tileLabel, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun KpiTile(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val look = LocalAronLook.current
    Column(
        modifier.heightIn(min = 84.dp).clip(shape).background(look.card).border(1.dp, if (look.sunlight) look.purple else KpiOutline, shape).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // one line that shrinks from 26 sp down to 12 sp before it would clip (a 5-digit stick count must stay whole)
        Text(
            value, color = look.ink, fontSize = 26.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 26.sp, stepSize = 1.sp),
        )
        Text(label, color = look.tileLabel, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * The sales panel (sr/home-2-kpis.webp): a vertical red-to-purple gradient with white text. Left: the headline "বিক্রয় (প্যাকেট)"
 * over the sticks sold. Right: one row per category with a sale, then gross (`মোট`), the offer discount (`ডিসকাউন্ট`, zero while
 * docs/27 defers programmes) and `QC`, each bound to its OWN field, a rule, and the net `সর্বমোট`. Rows carry no ৳ sign.
 */
@Composable
private fun SalesPanel(m: HomeMoneyLines) {
    val look = LocalAronLook.current
    val white = look.onBar
    Row(
        Modifier.fillMaxWidth().testTag(HomeScreenTags.MONEY).clip(RoundedCornerShape(16.dp)).background(look.verticalBrush()).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(0.8f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.hm_sales_title), color = white, fontSize = 24.sp)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.hm_sales_unit), color = white, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(localizedNumber(m.packetsSold), color = white, fontSize = 26.sp)
        }
        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            m.categories.forEach { PanelRow(it.label, it.amountBare, white, 15) }
            PanelRow(stringResource(R.string.hm_money_gross), m.grossBare, white, 17)
            PanelRow(stringResource(R.string.hm_money_discount), "- " + m.offerDiscountBare, white, 17, tag = HomeScreenTags.DISCOUNT)
            PanelRow(stringResource(R.string.hm_money_qc), "- " + m.qcBare, white, 17, tag = HomeScreenTags.QC)
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(white))
            PanelRow(stringResource(R.string.hm_money_grand), m.netBare, white, 20, bold = true)
        }
    }
}

@Composable
private fun PanelRow(label: String, value: String, color: Color, sp: Int, bold: Boolean = false, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = sp.sp, modifier = Modifier.weight(1f))
        Text(
            value, color = color, fontSize = sp.sp, textAlign = TextAlign.End, maxLines = 1, softWrap = false,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}

/**
 * The second panel (sr/home-2-kpis.webp): total discount, DRP discount and total QC in three columns, a rule, `সর্ব মোট` (the
 * gross) very large and `মোট টাকা` (the net payable) large, each with a glued ৳. Discount cells show 0.00 while docs/27 defers.
 */
@Composable
private fun DiscountPanel(m: HomeMoneyLines) {
    val look = LocalAronLook.current
    val white = look.onBar
    val brush = if (look.sunlight) SolidColor(look.purple) else Brush.verticalGradient(listOf(Color(0xFF6A3F6E), Color(0xFFA06FA1)))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = HOME_GUTTER, vertical = AronTokens.Space.S).clip(RoundedCornerShape(16.dp)).background(brush).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text(stringResource(R.string.hm_total_discount), color = white, fontSize = 14.sp); Text(m.totalDiscountBare, color = white, fontSize = 22.sp, modifier = Modifier.padding(top = 6.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.hm_drp_discount), color = white, fontSize = 14.sp); Text(m.drpDiscountBare, color = white, fontSize = 22.sp, modifier = Modifier.padding(top = 6.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Text(stringResource(R.string.hm_total_qc), color = white, fontSize = 14.sp); Text(m.totalQcBare, color = white, fontSize = 22.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(white))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hm_gross_total), color = white, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(m.grossBare + "৳", color = white, fontSize = 28.sp, maxLines = 1, softWrap = false)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hm_total_taka), color = white, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text(m.netBare + "৳", color = white, fontSize = 24.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun HomeBanner(text: String, kind: BannerKind, modifier: Modifier = Modifier) =
    AronBanner(text, modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xs), kind)

@Composable
private fun HomeTileItem(rt: ResolvedTile, freshness: BundleFreshness, dock: HomeDock?, modifier: Modifier, onTile: (HomeTile) -> Unit) {
    val needsSelling = rt.tile in SELLING_TILES
    val open = rt.badge?.let { stringResource(CoreR.string.core_ui_tile_badge, localizedNumber(it.toLong())) }
    AronTile(
        label = tileLabel(rt.tile), icon = painterResource(tilePainterRes(rt.tile)), onClick = { onTile(rt.tile) }, badge = rt.badge ?: 0,
        badgeDescription = open,
        modifier = modifier.testTag(HomeScreenTags.tile(rt.tile)), enabled = !(needsSelling && !freshness.canSell),
    )
}

/** The redrawn reference icon of each tile (`core-ui` drawables `aron_ic_*`, PARITY-SPEC s2.11). */
private fun tilePainterRes(t: HomeTile): Int = when (t) {
    HomeTile.ATTENDANCE -> CoreR.drawable.aron_art_attendance
    HomeTile.STOCK -> CoreR.drawable.aron_art_stock
    HomeTile.SALE -> CoreR.drawable.aron_art_sale
    HomeTile.MEMO -> CoreR.drawable.aron_art_memo
    HomeTile.SUMMARY -> CoreR.drawable.aron_art_summary
    HomeTile.SALES_SUBMIT -> CoreR.drawable.aron_art_sales_submit
    HomeTile.OUTLET -> CoreR.drawable.aron_art_outlet
    HomeTile.TUTORIAL -> CoreR.drawable.aron_art_tutorial
    HomeTile.TASKS -> CoreR.drawable.aron_art_task_delegation
    HomeTile.PHOTO_CAPTURE -> CoreR.drawable.aron_art_photo_capture
    HomeTile.SALES_JOURNEY -> CoreR.drawable.aron_art_sales_journey
    HomeTile.KPI -> CoreR.drawable.aron_art_kpi
}

/** Danger only under this much free storage (screens-sr s2 "the one danger case"). */
private const val STORAGE_DANGER_MB = 200L

/**
 * The device line (F-SR-064, screens-sr s2): caption text, no card. Under a threshold the line starts with the warning
 * word and a warning icon (the icon speaks the full sentence); the one danger case, storage under 200 MB, adds a danger
 * banner because the SR must act before the next sale.
 */
@Composable
internal fun SrDeviceLine(h: DeviceHealth) {
    val c = LocalAronColors.current
    val danger = h.freeStorageMb < STORAGE_DANGER_MB
    Column(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S).testTag(HomeScreenTags.HEALTH), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        val line = listOfNotNull(
            if (h.anyWarning) stringResource(R.string.hm_health_warn_word) else null,
            stringResource(R.string.hm_health_battery, localizedNumber(h.batteryPct.toLong())),
            stringResource(R.string.hm_health_storage, localizedNumber(h.freeStorageMb)),
            stringResource(R.string.hm_health_pending, localizedNumber(h.pendingRows.toLong())),
            h.lastSyncAgeMin?.let { stringResource(R.string.hm_health_sync, localizedNumber(it)) } ?: stringResource(R.string.hm_health_never_synced),
        ).joinToString("  ·  ")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            if (h.anyWarning) {
                Icon(AronIcons.Warning, contentDescription = stringResource(R.string.hm_health_warning), tint = if (danger) c.danger else c.warning, modifier = Modifier.size(AronTokens.Size.IconSmall))
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        if (danger) AronBanner(stringResource(R.string.hm_storage_low), kind = BannerKind.Error)
    }
}

/** Tiles that start or change a sale: disabled when the bundle cannot sell. Memo and Summary stay readable. */
private val SELLING_TILES = setOf(HomeTile.STOCK, HomeTile.SALE, HomeTile.SALES_SUBMIT)
