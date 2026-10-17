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
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.feature.tso.R as TsoR
import com.aktcl.aron.feature.tso.TsoDashboardContent
import com.aktcl.aron.feature.tso.TsoDashboardState
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.TsoRouteListContent
import com.aktcl.aron.feature.tso.TsoZoneFilterContent
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

private enum class DashLeaf { MAIN, FILTER, NOT_LOGGED_IN, NOT_UPLOADED }

/**
 * The TSO Dashboard tile (F-TSO-002..007, F-TSO-027): loads once when opened and again only on Refresh or after a Filter; there is no timer.
 * The zone filter is a selector over the territory's zones (taken from the summary), the scope itself comes from the token.
 */
@Composable
fun TsoDashboardHost(api: TsoApi, businessDate: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var leaf by rememberSaveable { mutableStateOf(DashLeaf.MAIN) }
    var zone by rememberSaveable { mutableStateOf<Long?>(null) }
    var draft by rememberSaveable { mutableStateOf<Long?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<TsoDashboardState?>(null) }
    var zones by remember { mutableStateOf<List<com.aktcl.aron.feature.tso.ZoneRef>>(emptyList()) }
    LaunchedEffect(zone, reload) {
        state = null
        val s = api.summary(businessDate, zone)
        val l = api.loginSubmit(businessDate, zone)
        val dash = TsoDashboards.summary(s.jsonOrNull())
        if (zone == null && dash != null) zones = dash.zones
        state = TsoDashboardState(dash, TsoDashboards.loginSubmit(l.jsonOrNull()), stale = s is TsoFetch.Cached, failed = s is TsoFetch.Unavailable || s is TsoFetch.Gone)
    }
    val back: () -> Unit = { if (leaf == DashLeaf.MAIN) onBack() else leaf = DashLeaf.MAIN }
    BackHandler(onBack = back)
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    val money: (Long) -> String = { Formats.money(it, if (bangla) UiLocale.BN else UiLocale.EN) }
    AronScreen(topBar = { AronTopBar(stringResource(TsoR.string.tso_dash_title), onBack = back) }, modifier = modifier) {
        val st = state
        when {
            leaf == DashLeaf.FILTER -> TsoZoneFilterContent(zones, draft, { draft = it; zone = it; leaf = DashLeaf.MAIN })
            st == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            leaf == DashLeaf.NOT_LOGGED_IN -> TsoRouteListContent(st.login?.notLoggedIn.orEmpty())
            leaf == DashLeaf.NOT_UPLOADED -> TsoRouteListContent(st.login?.loggedInNotSubmitted.orEmpty())
            else -> TsoDashboardContent(
                st, zones.firstOrNull { it.id == zone }?.name ?: stringResource(TsoR.string.tso_dash_all_zones), money,
                onRefresh = { reload++ }, onFilter = { draft = zone; leaf = DashLeaf.FILTER },
                onNotLoggedIn = { leaf = DashLeaf.NOT_LOGGED_IN }, onNotUploaded = { leaf = DashLeaf.NOT_UPLOADED },
            )
        }
    }
}
