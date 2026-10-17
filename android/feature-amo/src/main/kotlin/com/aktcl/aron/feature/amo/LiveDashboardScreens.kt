package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

private fun pos(i: Int, n: Int) = if (n == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == n - 1) RowPosition.Last else RowPosition.Middle

@Composable
private fun pctText(v: Double?): String = LiveDashboard.pct(v)?.let { localizedDigits("$it %") } ?: stringResource(R.string.amo_live_dash)

/** F-AMO-020: the zone's figures with an as-of stamp. [scopeLabel] names the filter in force; [money] formats milli-taka in the user's digits. */
@Composable
fun LiveDashboardContent(
    f: LiveFigures?, asOfLabel: String?, scopeLabel: String, routeFilter: Boolean, stale: Boolean, failed: Boolean, money: (Long) -> String,
    onFilter: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
            if (failed) AronBanner(stringResource(R.string.amo_task_list_offline), kind = BannerKind.Warning)
            else if (stale) AronBanner(stringResource(R.string.amo_task_list_kept), kind = BannerKind.Info)
            AronListRow(scopeLabel, subtitle = asOfLabel?.let { stringResource(R.string.amo_live_as_of, localizedDigits(it)) }, flat = true)
            if (f == null) {
                AronBanner(stringResource(R.string.amo_live_none), kind = BannerKind.Info)
            } else {
                if (routeFilter) AronBanner(stringResource(R.string.amo_live_route_note), kind = BannerKind.Info)
                AronSectionLabel(stringResource(R.string.amo_live_sales))
                val sales = listOf(
                    stringResource(R.string.amo_live_gross) to money(f.grossMtk), stringResource(R.string.amo_live_net) to money(f.netMtk),
                    stringResource(R.string.amo_live_visited) to stringResource(R.string.amo_live_x_of_y, localizedNumber(f.visitedOutlets), localizedNumber(f.targetOutlets)),
                    stringResource(R.string.amo_live_strike) to (LiveDashboard.pct(f.strikeRatePct)?.let { localizedDigits("$it %") + " (" + stringResource(R.string.amo_live_x_of_y, localizedNumber(f.successfulCalls), localizedNumber(f.visitedOutlets)) + ")" } ?: stringResource(R.string.amo_live_dash)),
                )
                sales.forEachIndexed { i, (k, v) -> AronListRow(v, subtitle = k, position = pos(i, sales.size), divider = i < sales.lastIndex, flat = true) }
                AronSectionLabel(stringResource(R.string.amo_live_geo))
                val geo = listOf(
                    stringResource(R.string.amo_live_geo_valid) to pctText(f.geoValidPct), stringResource(R.string.amo_live_force) to pctText(f.forceSalePct),
                    stringResource(R.string.amo_live_mock) to localizedNumber(f.mockVisits), stringResource(R.string.amo_live_suspicious) to localizedNumber(f.suspiciousVisits),
                )
                geo.forEachIndexed { i, (k, v) -> AronListRow(v, subtitle = k, position = pos(i, geo.size), divider = i < geo.lastIndex, flat = true) }
                AronSectionLabel(stringResource(R.string.amo_live_login))
                val login = listOf(
                    stringResource(R.string.amo_live_logged_in) to (stringResource(R.string.amo_live_x_of_y, localizedNumber(f.loggedInRoutes), localizedNumber(f.targetRoutes)) + " (" + pctText(f.loginPct) + ")"),
                    stringResource(R.string.amo_live_submitted) to (stringResource(R.string.amo_live_x_of_y, localizedNumber(f.submittedRoutes), localizedNumber(f.loggedInRoutes)) + " (" + pctText(f.submitPct) + ")"),
                    stringResource(R.string.amo_live_not_logged_in) to localizedNumber(f.notLoggedIn.size.toLong()),
                    stringResource(R.string.amo_live_not_submitted) to localizedNumber(f.loggedInNotSubmitted.size.toLong()),
                )
                login.forEachIndexed { i, (k, v) -> AronListRow(v, subtitle = k, position = pos(i, login.size), divider = i < login.lastIndex, flat = true) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen)) { AronSecondaryButton(stringResource(R.string.amo_live_filter), onFilter, Modifier.fillMaxWidth()) }
    }
}

/** The filter: nothing changes until Filter is pressed. */
@Composable
fun LiveFilterContent(
    zones: List<Pair<Long, String>>, routes: List<Pair<Long, String>>, zone: Long?, route: Long?, onZone: (Long?) -> Unit, onRoute: (Long?) -> Unit, onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
            AronSectionLabel(stringResource(R.string.amo_live_zone))
            val z = listOf<Pair<Long?, String>>(null to stringResource(R.string.amo_live_all_zones)) + zones
            z.forEachIndexed { i, (id, name) -> AronListRow(name, onClick = { onZone(id) }, trailing = if (id == zone) stringResource(R.string.amo_verify_selected) else null, position = pos(i, z.size), divider = i < z.lastIndex, flat = true) }
            AronSectionLabel(stringResource(R.string.amo_live_route))
            val r = listOf<Pair<Long?, String>>(null to stringResource(R.string.amo_live_all_routes)) + routes
            r.forEachIndexed { i, (id, name) -> AronListRow(name, onClick = { onRoute(id) }, trailing = if (id == route) stringResource(R.string.amo_verify_selected) else null, position = pos(i, r.size), divider = i < r.lastIndex, flat = true) }
        }
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen)) { AronPrimaryButton(stringResource(R.string.amo_live_apply), onApply, Modifier.fillMaxWidth()) }
    }
}
