package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoFrame
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.feature.amo.ReportData
import com.aktcl.aron.feature.amo.ReportsTilesContent
import com.aktcl.aron.feature.amo.StdMatrixContent
import com.aktcl.aron.feature.amo.TeamReports
import com.aktcl.aron.feature.amo.TeamTargets
import com.aktcl.aron.feature.amo.TillNowContent
import com.aktcl.aron.feature.amo.TillRouteDetailContent
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.feature.amo.Reports
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

private enum class ReportLeaf { MENU, STD, SUMMARY, ROUTE }

/**
 * The Report tile (screenshots 71, 72): the two tiles, the STD Memo Report (route x category matrix over a date range, default the month to
 * today) and the sales-till-now summary (zone and route cards with Target, Sales, %, Memo; a route opens its CPR, total memo and brand table).
 */
@Composable
fun AmoReportHub(day: AmoDay, onBack: () -> Unit) {
    var leaf by rememberSaveable { mutableStateOf(ReportLeaf.MENU) }
    var route by rememberSaveable { mutableStateOf<Long?>(null) }
    var from by rememberSaveable { mutableStateOf<String?>(null) }
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    val back: () -> Unit = {
        when (leaf) { ReportLeaf.MENU -> onBack(); ReportLeaf.ROUTE -> leaf = ReportLeaf.SUMMARY; else -> leaf = ReportLeaf.MENU }
    }
    BackHandler(onBack = back)
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    val l = if (bangla) UiLocale.BN else UiLocale.EN
    val today = remember { day.businessDate() }
    val defaults = remember { TeamReports.summaryPeriod(today) }
    val title = when (leaf) { ReportLeaf.STD -> AmoR.string.amo_rep_std; ReportLeaf.SUMMARY -> AmoR.string.amo_rep_summary; ReportLeaf.ROUTE -> -1; else -> AmoR.string.amo_rep_title }
    val members = if (leaf == ReportLeaf.SUMMARY || leaf == ReportLeaf.ROUTE) rememberMembers(day) else null
    val nodes = if (members != null) rememberTargetNodes(day, members) else null
    val openNode = nodes?.routes?.firstOrNull { it.id == route }
    AmoFrame(if (title == -1) openNode?.name.orEmpty() else stringResource(title), back, icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_report) {
        when (leaf) {
            ReportLeaf.MENU -> ReportsTilesContent({ leaf = ReportLeaf.STD }, { leaf = ReportLeaf.SUMMARY })
            ReportLeaf.STD -> {
                val f = from ?: defaults.first; val t = to ?: defaults.second
                val std = rememberReport(day, "route-std", f, t)
                var zone by remember { mutableStateOf("") }
                LaunchedEffect(day) { zone = TeamTargets.parse(day.api.brandTargets(today.take(7), null).jsonOrNull())?.name.orEmpty() }
                if (std == null) com.aktcl.aron.core.ui.aron.AronLoadingScreen()
                else StdMatrixContent(f, t, today, { a, b -> from = a; to = b }, std.data?.takeIf { !it.truncated }?.let { TeamReports.stdMatrix(zone, Reports.routeCategories(it)) }, std.stale, std.failed, std.refused, truncated = std.data?.truncated == true)
            }
            ReportLeaf.SUMMARY, ReportLeaf.ROUTE -> {
                // F-AMO-033: the month to today over the trading days; CPR and memo counts come from the sales-summary report, targets from the target slice
                val loaded = rememberReport(day, "sales-summary", defaults.first, defaults.second)
                if (loaded == null || nodes == null) { com.aktcl.aron.core.ui.aron.AronLoadingScreen(); return@AmoFrame }
                // a cut-off report (a page failed or the page cap was hit) gives no CPR or brand memos: partial rows would print as complete figures
                val summaryCut = loaded.data?.truncated == true
                val reportCards = loaded.data?.takeIf { !summaryCut }?.let { Reports.routeCards(it) }.orEmpty()
                // the Memo column is the server's own per-category count (sales-summary `memos_<category>`, contract 1.11.2): a route's from its card, the zone's
                // the sum of every route's; absent (older server, cut-off report) it is a dash and the note says pending
                val zoneMemos = TeamReports.zoneMemos(reportCards)
                fun routeMemos(id: Long) = TeamReports.cardFor(reportCards, id, members?.rows?.firstOrNull { it.routeId == id }?.routeCode)?.categoryMemos.orEmpty()
                val cards = listOfNotNull(nodes.zone?.let { TeamReports.tillCard(stringResource(AmoR.string.amo_team_perf_zone, it.name.orEmpty()), it, true, zoneMemos, l) }) +
                    nodes.routes.map { TeamReports.tillCard(stringResource(AmoR.string.amo_team_perf_route, it.name.orEmpty()), it, false, routeMemos(it.id), l) }
                val memoPending = zoneMemos.isEmpty() && nodes.routes.all { routeMemos(it.id).isEmpty() }
                if (leaf == ReportLeaf.ROUTE && openNode != null) {
                    val rc = TeamReports.cardFor(reportCards, openNode.id, members?.rows?.firstOrNull { it.routeId == openNode.id }?.routeCode)
                    val brands = openNode.rows.map { r -> TeamReports.brandLine(r, rc?.brands?.firstOrNull { it.name.equals(r.name, ignoreCase = true) }?.memos, l) }
                    TillRouteDetailContent(rc?.let { TeamReports.cprText(it.successfulCalls, it.targetOutlets, l) } ?: Percent.DASH, rc?.let { Formats.integer(it.memos, l) } ?: Percent.DASH, brands, stale = loaded.stale || nodes.stale, failed = (loaded.failed || nodes.failed) && nodes.zone == null, refused = loaded.refused || nodes.refused)
                } else TillNowContent(cards, { c -> route = c.routeId; if (c.routeId != null) leaf = ReportLeaf.ROUTE }, loaded.stale || nodes.stale, (loaded.failed || nodes.failed) && nodes.zone == null, loaded.refused || nodes.refused, memoPending = memoPending, truncated = summaryCut)
            }
        }
    }
}

private class Loaded(val data: ReportData?, val stale: Boolean, val failed: Boolean, val refused: Boolean = false)

@Composable
private fun rememberReport(day: AmoDay, key: String, from: String, to: String): Loaded? {
    var loaded by remember(key, from, to) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(key, from, to) {
        val f = day.api.report(key, from, to)
        val json = f.jsonOrNull()
        loaded = Loaded(Reports.parse(json), f is AmoFetch.Cached, f.isMissing(), f is AmoFetch.Gone)
    }
    return loaded
}
