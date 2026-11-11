package com.aktcl.aron.amo

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.map.LiteMapScreen
import com.aktcl.aron.core.map.MapPoint
import com.aktcl.aron.core.map.MapSettings
import com.aktcl.aron.core.map.MapTileCache
import com.aktcl.aron.core.map.MapsKey
import com.aktcl.aron.core.ui.aron.AronLoadingScreen
import com.aktcl.aron.core.ui.entryUnitLabel
import com.aktcl.aron.feature.amo.AmoFrame
import com.aktcl.aron.feature.amo.IssueLine
import com.aktcl.aron.feature.amo.JourneyTimelineContent
import com.aktcl.aron.feature.amo.LiveDataContent
import com.aktcl.aron.feature.amo.LiveDataParser
import com.aktcl.aron.feature.amo.LiftedStockContent
import com.aktcl.aron.feature.amo.LocationChoice
import com.aktcl.aron.feature.amo.PerfCards
import com.aktcl.aron.feature.amo.PerfTab
import com.aktcl.aron.feature.amo.SkuInfo
import com.aktcl.aron.feature.amo.SrListContent
import com.aktcl.aron.feature.amo.TeamJourneys
import com.aktcl.aron.feature.amo.TeamLocationContent
import com.aktcl.aron.feature.amo.TeamLocationRules
import com.aktcl.aron.feature.amo.TeamLocations
import com.aktcl.aron.feature.amo.TeamMember
import com.aktcl.aron.feature.amo.TeamPerformanceDetailsContent
import com.aktcl.aron.feature.amo.TeamPerformanceListContent
import com.aktcl.aron.feature.amo.TeamStock
import com.aktcl.aron.feature.amo.RouteOutOfReach
import com.aktcl.aron.feature.amo.TeamTargetNode
import com.aktcl.aron.feature.amo.TeamTargets
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.feature.home.R as HomeR
import java.io.File

/** The SR/route rows of the zone (`GET /v1/team/members`), with the kept copy's flags. */
internal class Members(val rows: List<TeamMember>, val stale: Boolean, val failed: Boolean, val refused: Boolean = false)

@Composable
internal fun rememberMembers(day: AmoDay): Members? {
    var m by remember(day) { mutableStateOf<Members?>(null) }
    LaunchedEffect(day) {
        val f = day.api.teamMembers(day.businessDate())
        m = Members(TeamJourneys.members(f.jsonOrNull()), f is AmoFetch.Cached, f.isMissing(), f is AmoFetch.Gone)
    }
    return m
}

/** The zone node and one node per route (brand rows) for Team Performance and the sales-till-now summary; routes fill in as they arrive. */
internal class TargetNodes(
    val zone: TeamTargetNode?, val routes: List<TeamTargetNode>, val stale: Boolean, val failed: Boolean, val refused: Boolean = false,
    /** Routes the server refused with 403 (their own row says so) and the 400 case of a server without the route level (one line, no access banner). */
    val outOfReach: List<RouteOutOfReach> = emptyList(), val routeLevelUnavailable: Boolean = false,
)

@Composable
internal fun rememberTargetNodes(day: AmoDay, members: Members?): TargetNodes? {
    var out by remember(day, members) { mutableStateOf<TargetNodes?>(null) }
    LaunchedEffect(day, members) {
        members ?: return@LaunchedEffect
        val month = day.businessDate().take(7)
        loadTargetNodes({ day.api.brandTargets(month, null) }, { id -> day.api.routeTargets(month, id) }, members.rows.map { it.routeId }.distinct().take(MAX_ROUTE_CARDS), { id -> members.rows.firstOrNull { it.routeId == id }?.routeName }) { out = it }
    }
    return out
}

private const val MAX_ROUTE_CARDS = 20

/**
 * The zone, then one route at a time (`level=route&node_id=`), publishing after each answer. A refused zone (403) ends it at once (one refusal, no ask per route). A 403 on a route marks THAT route out of reach and the loop goes on with the
 * rest; a 400 (the server has no route level) ends the loop with one note and no banner; an offline or unreadable answer ends it too. Nothing is asked twice.
 */
internal suspend fun loadTargetNodes(fetchZone: suspend () -> AmoFetch, fetchRoute: suspend (Long) -> RouteTargetsRead, routeIds: List<Long>, routeName: (Long) -> String?, publish: (TargetNodes) -> Unit) {
    val zoneFetch = fetchZone()
    val zone = TeamTargets.parse(zoneFetch.jsonOrNull())
    var stale = zoneFetch is AmoFetch.Cached
    val zoneGone = zoneFetch is AmoFetch.Gone
    val acc = mutableListOf<TeamTargetNode>(); val out = mutableListOf<RouteOutOfReach>()
    var unavailable = false
    fun emit() = publish(TargetNodes(zone, acc.toList(), stale, zoneFetch.isMissing(), zoneGone, out.toList(), unavailable))
    emit()
    if (zoneGone) return // the zone itself is refused: one refusal, the routes are not asked
    for (id in routeIds) {
        val r = fetchRoute(id)
        if (r.notOffered) { unavailable = true; emit(); break }
        stale = stale || r.fetch is AmoFetch.Cached
        if (r.fetch is AmoFetch.Gone) { out += RouteOutOfReach(id, routeName(id)); emit(); continue }
        if (r.fetch.jsonOrNull() == null) { emit(); break }
        TeamTargets.parse(r.fetch.jsonOrNull())?.let { n ->
            // the route's name from the members list when the node carries none
            acc += if (n.name.isNullOrBlank()) n.copy(name = routeName(id)) else n
            emit()
        }
    }
}

/** Team Performance (screenshots 34 to 36): the zone card and the route cards; Details opens the brand table; a category tap filters it. */
@Composable
fun AmoTeamPerformanceHost(day: AmoDay, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(PerfTab.MONTHLY) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) } // "zone" or a route id
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val members = rememberMembers(day)
    val nodes = rememberTargetNodes(day, members)
    // Back clears the category filter first, then closes Details, then leaves (screenshot 36 note)
    BackHandler(enabled = detail != null) { if (category != null) category = null else detail = null }
    val open = nodes?.let { n -> if (detail == "zone") n.zone else n.routes.firstOrNull { it.id.toString() == detail } }
    AmoFrame(open?.name ?: stringResource(AmoR.string.amo_leaf_team_performance), { if (detail != null) { category = null; detail = null } else onBack() }, icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_team_performance) {
        if (nodes == null) { AronLoadingScreen(); return@AmoFrame }
        if (open != null) TeamPerformanceDetailsContent(open, tab, { tab = it }, category, { category = TeamTargets.toggle(category, it) }, nodes.stale, nodes.failed, nodes.refused)
        else TeamPerformanceListContent(PerfCards(nodes.zone, nodes.routes, nodes.outOfReach, nodes.routeLevelUnavailable), tab, { tab = it }, { n -> category = null; detail = if (n === nodes.zone) "zone" else n.id.toString() }, nodes.stale, nodes.failed, nodes.refused)
    }
}

/** Live Data (screenshot 39): the AMO (the caller only), the route selector, Filter and refresh. */
@Composable
fun AmoLiveDataHost(day: AmoDay, onBack: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("all") }
    var applied by rememberSaveable { mutableStateOf("all") }
    var tick by rememberSaveable { mutableStateOf(0) }
    var routes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var shown by remember { mutableStateOf<com.aktcl.aron.feature.amo.LiveData?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    var zoneFallback by remember { mutableStateOf(false) }
    LaunchedEffect(day) { routes = runCatching { day.routes() }.getOrDefault(emptyList()).map { it.route.routeId.toString() to (it.route.displayLabel ?: it.route.name) } }
    LaunchedEffect(day, applied, tick) {
        loaded = false; zoneFallback = false
        val date = day.businessDate()
        val route = applied.toLongOrNull()
        var f = if (route != null) day.api.liveDashboardRoute(date, route) else day.api.liveDashboard(date, null)
        // a server without the route level answers with the zone: the route's own login rows still apply and the screen says so
        if (route != null && f.jsonOrNull() == null) { f = day.api.liveDashboard(date, null); zoneFallback = f.jsonOrNull() != null }
        stale = f is AmoFetch.Cached; failed = f.isMissing(); refused = f is AmoFetch.Gone
        shown = LiveDataParser.parse(f.jsonOrNull(), route.takeIf { zoneFallback })
        loaded = true
    }
    val all = stringResource(AmoR.string.amo_live_all_routes)
    AmoFrame(
        stringResource(AmoR.string.amo_team_live_title), onBack, icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_live_dashboard,
        trailing = { androidx.compose.material3.IconButton(onClick = { tick++ }) { com.aktcl.aron.feature.amo.TeamIcon(AmoR.drawable.amo_team_ic_refresh, tint = androidx.compose.ui.graphics.Color.White, size = 26.dp) } },
    ) {
        if (!loaded) { AronLoadingScreen(); return@AmoFrame }
        LiveDataContent(
            shown, day.userDisplayName, routes.firstOrNull { it.first == draft }?.second ?: all, listOf("all" to all) + routes, applied != "all",
            { draft = it }, { applied = draft }, stale, failed, refused, routeNote = zoneFallback,
        )
    }
}

/** SR List and Lifted Stock (screenshot 40): one row per SR per route; the detail is the SR's issue today in field units with packshots. */
@Composable
fun AmoSrStockHost(day: AmoDay, onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<Long?>(null) } // route id of the chosen row
    val members = rememberMembers(day)
    var stock by remember { mutableStateOf<StockRead?>(null) }
    var skus by remember { mutableStateOf<Map<Long, SkuInfo>>(emptyMap()) }
    LaunchedEffect(day) {
        skus = runCatching { day.skuInfo() }.getOrDefault(emptyMap())
        val f = day.api.teamStock(day.businessDate())
        stock = StockRead(TeamStock.parse(f.jsonOrNull()), f.isMissing(), f is AmoFetch.Gone, f is AmoFetch.Cached)
    }
    BackHandler(enabled = selected != null) { selected = null }
    val bangla = com.aktcl.aron.core.ui.LocalAppLanguage.current == com.aktcl.aron.core.common.AppLanguage.BN
    val row = members?.rows?.firstOrNull { it.routeId == selected }
    AmoFrame(
        stringResource(if (row != null) AmoR.string.amo_team_lifted_title else AmoR.string.amo_team_sr_list_title), { if (selected != null) selected = null else onBack() },
        icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_sr_stock,
    ) {
        if (members == null || stock == null) { AronLoadingScreen(); return@AmoFrame }
        if (row == null) SrListContent(members.rows.filter { !it.vacant }, members.stale || stock!!.stale, members.failed || stock!!.failed, { selected = it.routeId }, refused = members.refused || stock!!.refused)
        else {
            val sr = stock!!.srs.firstOrNull { it.userId == row.userId }
            val lines = sr?.lines.orEmpty().filter { it.lifted > 0 }.map { l ->
                val info = skus[l.skuId]
                val key = TeamStock.unitKeyOf(l, skus)
                IssueLine(l.skuId.toString(), info?.let { if (bangla) it.nameBn ?: it.name else it.name } ?: l.skuId.toString(), TeamStock.entered(l.lifted, l, skus), if (key.isEmpty()) null else entryUnitLabel(key))
            }
            // a stock read that failed or was refused is said so: an empty list would print "none issued today" as a fact
            if (stock!!.failed || stock!!.refused) androidx.compose.foundation.layout.Column(Modifier.padding(12.dp)) { com.aktcl.aron.feature.amo.AmoFetchBanner(stock!!.failed, false, stock!!.refused) }
            else LiftedStockContent(row, lines)
        }
    }
}

internal class StockRead(val srs: List<com.aktcl.aron.feature.amo.SrStock>, val failed: Boolean = false, val refused: Boolean = false, val stale: Boolean = false)

/** Sales Journey (screenshots 73, 74): the SR cards (a vacant route is shown but cannot be opened), then the timeline of the chosen SR and route. */
@Composable
fun AmoJourneyHost(day: AmoDay, onBack: () -> Unit) {
    var userId by rememberSaveable { mutableStateOf<Long?>(null) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    val members = rememberMembers(day)
    var journey by remember { mutableStateOf<com.aktcl.aron.feature.amo.TeamJourney?>(null) }
    var jLoaded by remember { mutableStateOf(false) }
    var jStale by remember { mutableStateOf(false) }
    var jFailed by remember { mutableStateOf(false) }
    var jRefused by remember { mutableStateOf(false) }
    LaunchedEffect(day, userId, routeId) {
        val u = userId; val r = routeId
        if (u == null || r == null) { journey = null; return@LaunchedEffect }
        jLoaded = false
        val f = day.api.teamJourney(u, r, day.businessDate())
        jStale = f is AmoFetch.Cached; jFailed = f.isMissing(); jRefused = f is AmoFetch.Gone
        journey = TeamJourneys.journey(f.jsonOrNull()); jLoaded = true
    }
    BackHandler(enabled = userId != null) { userId = null; routeId = null }
    AmoFrame(stringResource(HomeR.string.hm_tile_sales_journey), { if (userId != null) { userId = null; routeId = null } else onBack() }, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_sales_journey) {
        if (members == null) { AronLoadingScreen(); return@AmoFrame }
        if (userId == null) SrListContent(members.rows, members.stale, members.failed, { userId = it.userId; routeId = it.routeId }, openVacant = false, refused = members.refused)
        else if (!jLoaded) AronLoadingScreen()
        else JourneyTimelineContent(journey, jStale, jFailed, jRefused)
    }
}

/** Team Location (screenshot 33): the SR drop-down over the interactive map of the last known fixes (rider marker, compass, my location). */
@Composable
fun AmoTeamLocationHost(day: AmoDay, onBack: () -> Unit) {
    val context = LocalContext.current
    var pins by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.TeamPin>?>(null) }
    var stale by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var settings by remember { mutableStateOf(MapSettings()) }
    val members = rememberMembers(day)
    val cache = remember(day.userId) { MapTileCache(File(context.cacheDir, "map_amo_" + day.userId)) { settings.tileCacheBytes } }
    LaunchedEffect(day) {
        settings = day.mapSettings()
        val f = day.api.teamLocations(day.businessDate())
        stale = f is AmoFetch.Cached; failed = f.isMissing(); refused = f is AmoFetch.Gone
        pins = TeamLocations.parse(f.jsonOrNull())
    }
    val sources = mapOf(
        "check_in" to stringResource(AmoR.string.amo_loc_source_check_in), "check_out" to stringResource(AmoR.string.amo_loc_source_check_out),
        "visit" to stringResource(AmoR.string.amo_loc_source_visit), "breadcrumb" to stringResource(AmoR.string.amo_loc_source_sync),
    )
    AmoFrame(stringResource(AmoR.string.amo_leaf_team_location), onBack, icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_team_location) {
        val list = pins ?: run { AronLoadingScreen(); return@AmoFrame }
        val phones = members?.rows?.filter { it.userId != null && it.phone != null }?.associate { it.userId!! to it.phone!! }.orEmpty()
        val choices: List<LocationChoice> = TeamLocationRules.choices(list, phones)
        val byId = choices.associateBy { it.userId }
        val points = list.filter { it.userId in byId }.map { p ->
            MapPoint(p.userId.toString(), p.name.trim(), p.lat, p.lng, p.atMs, p.source?.let { s -> sources[s] ?: s }, note = byId[p.userId]?.phone)
        }
        TeamLocationContent(choices, selected, { selected = it }, stale, failed, refused) { m ->
            LiteMapScreen(
                points, settings, day.online(), MapsKey.present(context), day.currentMs(), cache, "team_" + day.userId + "_" + day.businessDate(), m,
                interactive = true, riderMarker = true, compass = true, myLocation = true, focusId = selected?.toString(),
            )
        }
    }
}
