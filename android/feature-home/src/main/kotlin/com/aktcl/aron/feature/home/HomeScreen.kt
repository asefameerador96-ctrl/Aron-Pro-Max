package com.aktcl.aron.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
    // the two caption lines of s2: the route name, then "<ISO date> · <route kind>"; earlier callers without the parts keep their one line
    val subtitle = if (header.isoDate != null) {
        listOfNotNull(header.routeName, listOfNotNull(localizedDigits(header.isoDate), kind).joinToString(" · ")).joinToString("\n")
    } else header.subtitle
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
        bottomButtons = if (dock != null) {
            {
                val fade = tween<Float>(if (reduced) AronTokens.Motion.Reduced else AronTokens.Motion.Fast, easing = LinearEasing)
                AnimatedContent(targetState = dock, transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, modifier = Modifier.weight(1f)) { d ->
                    AronPrimaryButton(
                        dockLabel(d), onClick = { onTile(d.target) }, enabled = !dockBlocked,
                        modifier = Modifier.fillMaxWidth().testTag(HomeScreenTags.DOCK).traversal(Traversal.DOCK),
                    )
                }
            }
        } else null,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = AronTokens.Space.Xl)) {
            if (sync != null || onSunlight != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xs).traversal(Traversal.STATUS),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
                ) {
                    if (sync != null) AronStatusChip(sync, Modifier.testTag(HomeScreenTags.CHIP), ChipForm.Full, onClick = onSync)
                    Spacer(Modifier.weight(1f))
                    // the optional Sunlight theme stays one tap away (docs/32 s2a); it is not the default look
                    if (onSunlight != null) SunlightToggle(sunlight, onSunlight, Modifier.testTag(HomeScreenTags.SUNLIGHT))
                }
            }
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
            if (dockBlocked) HomeBanner(stringResource(R.string.hm_blocked_by_bundle), BannerKind.Warning)
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
                TargetCard(
                    total = hero?.grandTotalText ?: localizedNumber(0L), visited = visited, planned = planned, meterText = meterText,
                    checkIn = hero?.let { h -> h.checkInText?.let { stringResource(R.string.hm_checkin_at, localizedDigits(it)) } ?: stringResource(R.string.hm_checkin_due) },
                    onDetails = onHero ?: { onTile(HomeTile.SUMMARY) },
                    modifier = Modifier
                        .padding(horizontal = HOME_GUTTER, vertical = AronTokens.Space.S)
                        .testTag(if (hero != null) HomeScreenTags.HERO else HomeScreenTags.HERO_SKELETON)
                        .traversal(Traversal.HERO).semantics { stateDescription = spoken }
                        .skeleton(hero == null),
                )
            }
            // KPI row, stock card, money card (sr/home-2-kpis.webp): white cards, zero categories hidden
            Column(Modifier.fillMaxWidth().traversal(Traversal.CARDS)) {
                kpis?.let { k ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = HOME_GUTTER, vertical = AronTokens.Space.S).height(IntrinsicSize.Max).testTag(HomeScreenTags.KPI),
                        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
                    ) {
                        KpiTile(stringResource(R.string.hm_kpi_strike), k.strikeRateText?.let { localizedDigits(it) } ?: stringResource(R.string.hm_kpi_none), Modifier.weight(1f).fillMaxHeight())
                        KpiTile(stringResource(R.string.hm_kpi_non_visit), localizedNumber(k.nonVisit.toLong()), Modifier.weight(1f).fillMaxHeight())
                        KpiTile(stringResource(R.string.hm_kpi_no_sale), localizedNumber(k.noSale.toLong()), Modifier.weight(1f).fillMaxHeight())
                    }
                    val stock = k.stock.filter { !it.zero }
                    if (stock.isNotEmpty()) StockCard(stock)
                }
                money?.let { MoneyCard(it) }
                if (!compact) health?.let { h -> DeviceLine(h) }
            }
        }
    }
}

private val HOME_GUTTER = 12.dp

/** A white card of the reference (radius 12 dp, soft 2 dp shadow, no blur, no gradient). */
@Composable
private fun HomeCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(look.card)) { content() }
}

@Composable
private fun KpiTile(label: String, value: String, modifier: Modifier) {
    val look = LocalAronLook.current
    HomeCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = look.purple, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(label, color = look.inkMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The home bottom card (`aron.target`: vertical gradient, white text, progress track `#EFEFEF`, fill `#FF0100`). Target and
 * achievement percentages are deferred (docs/27), so it carries what is real today: the day's sales total and the outlets
 * visited of the planned count; "Details" opens Summary.
 */
@Composable
private fun TargetCard(total: String, visited: Int, planned: Int, meterText: String, checkIn: String?, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    val ink = Color.White
    val fraction = if (planned > 0) (visited.toFloat() / planned).coerceIn(0f, 1f) else 0f
    Column(
        modifier.fillMaxWidth().clip(shape).background(Brush.verticalGradient(listOf(look.targetTop, look.targetBottom))).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.hm_target_title), color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.hm_hero_total), color = ink, fontSize = 14.sp)
        Text(total, color = ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.hm_hero_meter), color = ink, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(look.track)) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(look.trackFill, RoundedCornerShape(11.dp)))
            Text(meterText, color = look.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
        }
        checkIn?.let { Text(it, color = ink, fontSize = 12.sp) }
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onDetails), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.hm_target_details), color = ink, fontSize = 14.sp, textDecoration = TextDecoration.Underline)
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
    HomeTile.ATTENDANCE -> CoreR.drawable.aron_ic_attendance
    HomeTile.STOCK -> CoreR.drawable.aron_ic_stock
    HomeTile.SALE -> CoreR.drawable.aron_ic_sale
    HomeTile.MEMO -> CoreR.drawable.aron_ic_memo
    HomeTile.SUMMARY -> CoreR.drawable.aron_ic_summary
    HomeTile.SALES_SUBMIT -> CoreR.drawable.aron_ic_sales_submit
    HomeTile.OUTLET -> CoreR.drawable.aron_ic_outlet
    HomeTile.TUTORIAL -> CoreR.drawable.aron_ic_tutorial
    HomeTile.TASKS -> CoreR.drawable.aron_ic_task_delegation
    HomeTile.PHOTO_CAPTURE -> CoreR.drawable.aron_ic_photo_capture
    HomeTile.SALES_JOURNEY -> CoreR.drawable.aron_ic_sales_journey
    HomeTile.KPI -> CoreR.drawable.aron_ic_kpi
}

/**
 * The stock card: "ইস্যু | বর্তমান স্টক" per category in its own unit, never summed across units. Quantities are one line
 * that shrinks to fit (never wraps or clips, tokens s7.3); from font scale 1.3 each category stacks its label over one
 * "ইস্যু … · বর্তমান স্টক …" line (the s0 reflow rule).
 */
@Composable
private fun StockCard(lines: List<HomeStockLine>) {
    val c = LocalAronLook.current
    val reflow = LocalDensity.current.fontScale >= AronTokens.Size.ReflowFontScale
    HomeCard(Modifier.padding(horizontal = HOME_GUTTER, vertical = AronTokens.Space.S).testTag(HomeScreenTags.STOCK)) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Icon(AronIcons.Stock, contentDescription = null, tint = c.purple, modifier = Modifier.size(AronTokens.Size.IconSmall))
                Text(stringResource(R.string.hm_stock_title), style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(LABEL_WEIGHT))
                if (!reflow) {
                    Text(stringResource(R.string.hm_stock_issue), style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.weight(VALUE_WEIGHT), textAlign = TextAlign.End)
                    Text(stringResource(R.string.hm_stock_current), style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.weight(VALUE_WEIGHT), textAlign = TextAlign.End)
                }
            }
            lines.forEach { l ->
                val issue = stringResource(R.string.hm_qty_unit, localizedNumber(l.issueBase), l.unitLabel)
                val current = stringResource(R.string.hm_qty_unit, localizedNumber(l.currentBase), l.unitLabel)
                if (reflow) {
                    // label over "ইস্যু …" over "বর্তমান স্টক …": a line may wrap at the label's space, never between a number and its unit
                    Column(Modifier.fillMaxWidth()) {
                        Text(l.categoryLabel, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2)
                        Text(stringResource(R.string.hm_stock_issue) + NBSP + issue.replace(' ', NBSP), style = MaterialTheme.typography.titleSmall, color = c.ink)
                        Text(stringResource(R.string.hm_stock_current) + NBSP + current.replace(' ', NBSP), style = MaterialTheme.typography.titleSmall, color = c.ink)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        Text(l.categoryLabel, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(LABEL_WEIGHT), maxLines = 2)
                        QuantityText(issue, Modifier.weight(VALUE_WEIGHT))
                        QuantityText(current, Modifier.weight(VALUE_WEIGHT))
                    }
                }
            }
        }
    }
}

/** A quantity with its unit on one line, right-aligned, shrinking to the 12 sp floor before anything would clip. */
@Composable
private fun QuantityText(text: String, modifier: Modifier) {
    val c = LocalAronLook.current
    val style = MaterialTheme.typography.titleSmall
    val shrink = if (style.fontSize.isSp && style.fontSize > QuantityFloorSp) TextAutoSize.StepBased(minFontSize = QuantityFloorSp, maxFontSize = style.fontSize, stepSize = 1.sp) else null
    Text(text, modifier, style = style, color = c.ink, textAlign = TextAlign.End, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, autoSize = shrink)
}

/** The label column of the stock card against each value column: the value columns get the room a Bangla quantity needs. */
private const val LABEL_WEIGHT = 0.8f
private const val VALUE_WEIGHT = 1.1f
private const val NBSP = ' '

/** The money card with the canonical words (মোট, ডিসকাউন্ট, QC, সর্বমোট); deductions only when non-zero. */
@Composable
private fun MoneyCard(m: HomeMoneyLines) {
    val c = LocalAronLook.current
    HomeCard(Modifier.padding(horizontal = HOME_GUTTER, vertical = AronTokens.Space.S).testTag(HomeScreenTags.MONEY)) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Icon(AronIcons.Wallet, contentDescription = null, tint = c.purple, modifier = Modifier.size(AronTokens.Size.IconSmall))
                Text(stringResource(R.string.hm_money_title), style = MaterialTheme.typography.titleSmall, color = c.ink)
            }
            m.categories.forEach { MoneyLine(it.label, it.amountText) }
            MoneyLine(stringResource(R.string.hm_money_gross), m.grossText)
            m.discountText?.let { MoneyLine(stringResource(R.string.hm_money_discount), it) }
            m.qcText?.let { MoneyLine(stringResource(R.string.hm_money_qc), it) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Text(stringResource(R.string.hm_money_grand), style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                Text(m.grandTotalText, color = c.purple, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun MoneyLine(label: String, amount: String) {
    val c = LocalAronLook.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f), maxLines = 2)
        Text(amount, color = c.ink, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
    }
}

/** Danger only under this much free storage (screens-sr s2 "the one danger case"). */
private const val STORAGE_DANGER_MB = 200L

/**
 * The device line (F-SR-064, screens-sr s2): caption text, no card. Under a threshold the line starts with the warning
 * word and a warning icon (the icon speaks the full sentence); the one danger case, storage under 200 MB, adds a danger
 * banner because the SR must act before the next sale.
 */
@Composable
private fun DeviceLine(h: DeviceHealth) {
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
