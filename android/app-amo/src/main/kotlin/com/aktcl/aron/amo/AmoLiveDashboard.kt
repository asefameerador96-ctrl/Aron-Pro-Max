package com.aktcl.aron.amo

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
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.feature.amo.LiveDashboard
import com.aktcl.aron.feature.amo.LiveDashboardContent
import com.aktcl.aron.feature.amo.LiveFigures
import com.aktcl.aron.feature.amo.LiveFilterContent
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

/**
 * F-AMO-020 Live Dashboard: the zone's sales, strike rate, location checks and login/submit status with an as-of stamp. The filter
 * (zone, route) changes nothing until Filter is pressed. Online read with a kept copy; [filtering] is owned by the hub so Back closes it.
 */
@Composable
fun AmoLiveDashboard(day: AmoDay, filtering: Boolean, onFiltering: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var zones by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var routes by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var applied by rememberSaveable { mutableStateOf<Pair<Long?, Long?>>(null to null) }
    var draftZone by rememberSaveable { mutableStateOf<Long?>(null) }
    var draftRoute by rememberSaveable { mutableStateOf<Long?>(null) }
    var figures by remember { mutableStateOf<LiveFigures?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val zoneLabel = stringResource(AmoR.string.amo_live_zone_n, "%d")
    LaunchedEffect(day) {
        val sup = runCatching { day.reference.section("supervisor") }.getOrNull()
        zones = com.aktcl.aron.feature.amo.OutletVerify.zoneIds(sup).map { it to zoneLabel.format(it) }
        routes = runCatching { day.routes() }.getOrDefault(emptyList()).map { it.route.routeId to it.route.name }
    }
    LaunchedEffect(day, applied) {
        loaded = false
        val date = day.businessDate()
        val s = day.api.dashboardSummary(date, applied.first)
        val l = day.api.loginSubmit(date, applied.first)
        fun json(f: AmoFetch) = when (f) { is AmoFetch.Fresh -> f.json; is AmoFetch.Cached -> f.json; AmoFetch.Unavailable -> null }
        stale = s is AmoFetch.Cached; failed = s is AmoFetch.Unavailable
        figures = LiveDashboard.parse(json(s), json(l), applied.second)
        loaded = true
    }
    if (filtering) {
        LiveFilterContent(zones, routes, draftZone, draftRoute, { draftZone = it }, { draftRoute = it }, { applied = draftZone to draftRoute; onFiltering(false) }, modifier)
        return
    }
    if (!loaded) { Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val digitsBn = LocalAppLanguage.current == com.aktcl.aron.core.common.AppLanguage.BN
    val scope = listOfNotNull(
        applied.first?.let { z -> zones.firstOrNull { it.first == z }?.second } ?: stringResource(AmoR.string.amo_live_all_zones),
        applied.second?.let { r -> routes.firstOrNull { it.first == r }?.second },
    ).joinToString(stringResource(AmoR.string.amo_verify_sep))
    LiveDashboardContent(
        figures, figures?.asOf?.takeIf { it.length >= 16 }?.let { dhakaClock(it) }, scope, applied.second != null, stale, failed,
        { mtk -> Formats.money(mtk, if (digitsBn) UiLocale.BN else UiLocale.EN) }, { draftZone = applied.first; draftRoute = applied.second; onFiltering(true) }, modifier,
    )
}

private fun dhakaClock(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(java.time.ZoneId.of("Asia/Dhaka")).format(java.time.Instant.parse(iso))
}.getOrDefault(iso)
