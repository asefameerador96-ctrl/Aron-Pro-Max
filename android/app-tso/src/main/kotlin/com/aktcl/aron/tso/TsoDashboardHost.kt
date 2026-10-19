package com.aktcl.aron.tso

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronTopBar
import androidx.compose.ui.res.painterResource
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.feature.tso.R as TsoR
import com.aktcl.aron.feature.tso.TsoDashboardContent
import com.aktcl.aron.feature.tso.TsoDashboardState
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.ZoneRef
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.TsoRouteListContent
import com.aktcl.aron.feature.tso.TsoZoneFilterContent
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

private enum class DashLeaf { MAIN, FILTER, NOT_LOGGED_IN, NOT_UPLOADED }

/**
 * The TSO Dashboard tile (F-TSO-002..007, F-TSO-027): loads once when opened and again only on Refresh or after a Filter; there is no timer.
 * The zone filter is a selector over the territory's zones (taken from the territory-wide summary), applied when chosen; the scope itself comes from the token.
 */
@Composable
fun TsoDashboardHost(api: TsoApi, businessDate: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var leaf by rememberSaveable { mutableStateOf(DashLeaf.MAIN) }
    var zone by rememberSaveable { mutableStateOf<Long?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<TsoDashboardState?>(null) }
    // The zone list survives a restore (id|name pairs), so a refresh with a zone chosen does not fetch the territory-wide answer again.
    var zoneSaved by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(zone, reload, businessDate) {
        state = null
        val s = api.summary(businessDate, zone)
        val l = api.loginSubmit(businessDate, zone)
        val dash = TsoDashboards.summary(s.jsonOrNull())
        // The zones to choose from come from the territory-wide answer (a kept copy is enough), also after a restore with a zone chosen.
        val zones = when {
            zone == null -> dash?.zones.orEmpty().also { z -> if (z.isNotEmpty()) zoneSaved = z.map { it.id.toString() + "|" + it.name } }
            zoneSaved.isNotEmpty() -> zoneSaved.mapNotNull { e -> e.substringBefore('|').toLongOrNull()?.let { ZoneRef(it, e.substringAfter('|')) } }
            else -> TsoDashboards.summary(api.summary(businessDate, null).jsonOrNull())?.zones.orEmpty()
                .also { z -> if (z.isNotEmpty()) zoneSaved = z.map { it.id.toString() + "|" + it.name } }
        }
        fun failure(f: TsoFetch) = when (f) { TsoFetch.Gone -> TsoFailure.REFUSED; TsoFetch.Unavailable -> TsoFailure.OFFLINE; else -> TsoFailure.NONE }
        state = TsoDashboardState(
            dash, TsoDashboards.loginSubmit(l.jsonOrNull()), stale = s is TsoFetch.Cached, failure = failure(s),
            loginStale = l is TsoFetch.Cached, loginFailure = failure(l), zones = zones, businessDate = businessDate, zoneChosen = zone != null,
        )
    }
    val back: () -> Unit = { if (leaf == DashLeaf.MAIN) onBack() else leaf = DashLeaf.MAIN }
    BackHandler(onBack = back)
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    val money: (Long) -> String = { Formats.money(it, if (bangla) UiLocale.BN else UiLocale.EN) }
    AronScaffold(
        topBar = { AronTopBar(stringResource(TsoR.string.tso_dash_title), icon = painterResource(CoreR.drawable.aron_art_kpi), onBack = back, backLabel = stringResource(CoreR.string.core_ui_back)) },
        modifier = modifier,
    ) {
        val st = state
        when {
            st == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            leaf == DashLeaf.FILTER -> TsoZoneFilterContent(st.zones, zone, { zone = it; leaf = DashLeaf.MAIN })
            leaf == DashLeaf.NOT_LOGGED_IN -> TsoRouteListContent(st.login?.notLoggedIn.orEmpty(), st.businessDate, st.login?.asOf, st.loginStale, st.loginFailure)
            leaf == DashLeaf.NOT_UPLOADED -> TsoRouteListContent(st.login?.loggedInNotSubmitted.orEmpty(), st.businessDate, st.login?.asOf, st.loginStale, st.loginFailure)
            else -> TsoDashboardContent(
                st, if (zone == null) stringResource(TsoR.string.tso_dash_all_zones) else (st.zones.firstOrNull { it.id == zone }?.name ?: st.dash?.nodeName ?: stringResource(TsoR.string.tso_dash_zone)), money,
                onRefresh = { reload++ }, onFilter = { leaf = DashLeaf.FILTER },
                onNotLoggedIn = { leaf = DashLeaf.NOT_LOGGED_IN }, onNotUploaded = { leaf = DashLeaf.NOT_UPLOADED },
            )
        }
    }
}
