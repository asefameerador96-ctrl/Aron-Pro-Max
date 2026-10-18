package com.aktcl.aron.amo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.feature.amo.ReportData
import com.aktcl.aron.feature.amo.ReportMenuContent
import com.aktcl.aron.feature.amo.Reports
import com.aktcl.aron.feature.amo.SalesSummaryContent
import com.aktcl.aron.feature.amo.SalesSummaryRouteContent
import com.aktcl.aron.feature.amo.StdMemoReportContent
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

private enum class ReportLeaf { MENU, STD, SUMMARY, ROUTE }

/** The Report tile (F-AMO-032/033): STD Memo Report and Sales Summary Up To Now over the AMO's zone, online with a kept copy. */
@Composable
fun AmoReportHub(day: AmoDay, onBack: () -> Unit) {
    var leaf by rememberSaveable { mutableStateOf(ReportLeaf.MENU) }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val back: () -> Unit = {
        when (leaf) { ReportLeaf.MENU -> onBack(); ReportLeaf.ROUTE -> leaf = ReportLeaf.SUMMARY; else -> leaf = ReportLeaf.MENU }
    }
    BackHandler(onBack = back)
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    val money: (Long) -> String = { Formats.money(it, if (bangla) UiLocale.BN else UiLocale.EN) }
    val title = when (leaf) { ReportLeaf.STD -> AmoR.string.amo_rep_std; ReportLeaf.SUMMARY, ReportLeaf.ROUTE -> AmoR.string.amo_rep_summary; else -> AmoR.string.amo_rep_title }
    AronScreen(topBar = { AronTopBar(stringResource(title), onBack = back) }) {
        when (leaf) {
            ReportLeaf.MENU -> ReportMenuContent({ leaf = ReportLeaf.STD }, { leaf = ReportLeaf.SUMMARY })
            ReportLeaf.STD -> {
                val range = remember { Reports.stdMemoRange(day.businessDate()) }
                val loaded = rememberReport(day, "std-memo", range.first, range.second)
                // Route totals and the per-category STD come from the server (`route-memo`, `route-std`, zone-scoped by the token); a refused or missing one falls back to the memo rows.
                val totals = rememberReport(day, "route-memo", range.first, range.second)
                val std = rememberReport(day, "route-std", range.first, range.second)
                if (loaded == null || totals == null || std == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else {
                    val server = totals.data?.takeIf { !it.truncated && it.rows.isNotEmpty() }?.let { Reports.routeMemoTotals(it) }
                    StdMemoReportContent(
                        range, loaded.data, server ?: loaded.data?.let { Reports.stdByRoute(it) }.orEmpty(), bangla, money, loaded.stale || (totals.stale && server != null), loaded.failed,
                        exactTotals = if (server != null) true else null,
                        categories = std.data?.takeIf { !it.truncated }?.let { Reports.routeCategories(it) }.orEmpty(),
                    )
                }
            }
            ReportLeaf.SUMMARY, ReportLeaf.ROUTE -> {
                // F-AMO-033: month to date over the trading days up to yesterday (today on the 1st), the same range as the STD report.
                val range = remember { Reports.stdMemoRange(day.businessDate()) }
                val loaded = rememberReport(day, "sales-summary", range.first, range.second)
                if (loaded == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else {
                    val cards = loaded.data?.let { Reports.routeCards(it) }.orEmpty()
                    val open = cards.firstOrNull { it.code == route }
                    if (leaf == ReportLeaf.ROUTE && open != null) SalesSummaryRouteContent(open, money)
                    else SalesSummaryContent(cards, money, loaded.stale, loaded.failed, { route = it.code; leaf = ReportLeaf.ROUTE })
                }
            }
        }
    }
}

private class Loaded(val data: ReportData?, val stale: Boolean, val failed: Boolean)

@Composable
private fun rememberReport(day: AmoDay, key: String, from: String, to: String): Loaded? {
    var loaded by remember(key, from, to) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(key, from, to) {
        val f = day.api.report(key, from, to)
        val json = f.jsonOrNull()
        loaded = Loaded(Reports.parse(json), f is AmoFetch.Cached, f.isMissing())
    }
    return loaded
}
