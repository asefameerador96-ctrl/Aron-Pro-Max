package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

@Composable
private fun pctText(v: Double?): String = LiveDashboard.pct(v)?.let { localizedDigits("$it %") } ?: stringResource(R.string.amo_live_dash)

@Composable
private fun liveCategoryName(code: String): String = when (code) {
    "cigarette" -> stringResource(R.string.amo_live_cat_cigarette)
    "bidi" -> stringResource(R.string.amo_live_cat_bidi)
    "lighter" -> stringResource(R.string.amo_live_cat_lighter)
    "match" -> stringResource(R.string.amo_live_cat_match)
    else -> code
}

/** "62.5 packets": the exact quantity text in the field unit (digits localised, never rounded). */
@Composable
private fun liveCategoryQty(c: LiveCategory): String = localizedDigits(c.qtyText) + " " + fieldUnitWord(c.unitKey)

/** "80,000.00 ৳ · 4 memos": the category's money and, when the server has it, its memo count. */
@Composable
private fun liveCategoryMoney(c: LiveCategory, money: (Long) -> String): String =
    money(c.netMtk) + (c.memos?.let { " · " + stringResource(R.string.amo_live_memos, localizedNumber(it)) } ?: "")

/** F-AMO-020: the zone's figures with an as-of stamp. [scopeLabel] names the filter in force; [money] formats milli-taka in the user's digits. */
@Composable
fun LiveDashboardContent(
    f: LiveFigures?, asOfLabel: String?, scopeLabel: String, routeFilter: Boolean, stale: Boolean, failed: Boolean, money: (Long) -> String,
    onFilter: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        AmoPage(Modifier.weight(1f)) {
            if (failed) AmoNotice(stringResource(R.string.amo_task_list_offline), warning = true)
            else if (stale) AmoNotice(stringResource(R.string.amo_task_list_kept))
            AmoRowCard(scopeLabel, subtitle = asOfLabel?.let { stringResource(R.string.amo_live_as_of, localizedDigits(it)) })
            if (f == null) {
                AmoNotice(stringResource(R.string.amo_live_none))
            } else {
                if (routeFilter) AmoNotice(stringResource(R.string.amo_live_route_note))
                AmoCard(title = stringResource(R.string.amo_live_sales)) {
                    AmoFigure(stringResource(R.string.amo_live_gross), money(f.grossMtk))
                    AmoFigure(stringResource(R.string.amo_live_net), money(f.netMtk))
                    // the volume per category in the field unit (packets, boxes of 25, dozens), exact text, with its money and memo count (contract 1.11.1)
                    f.categories.forEach { c ->
                        AmoFigure(liveCategoryName(c.code), liveCategoryQty(c))
                        androidx.compose.material3.Text(liveCategoryMoney(c, money), style = amoText(13, color = com.aktcl.aron.core.ui.LocalAronLook.current.inkMuted), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                AmoCard(title = stringResource(R.string.amo_live_strike)) {
                    AmoFigure(stringResource(R.string.amo_live_visited), stringResource(R.string.amo_live_x_of_y, localizedNumber(f.visitedOutlets), localizedNumber(f.targetOutlets)))
                    AmoFigure(
                        stringResource(R.string.amo_live_strike),
                        LiveDashboard.pct(f.strikeRatePct)?.let { localizedDigits("$it %") + " (" + stringResource(R.string.amo_live_x_of_y, localizedNumber(f.successfulCalls), localizedNumber(f.targetOutlets)) + ")" } ?: stringResource(R.string.amo_live_dash),
                    )
                    AmoBar(((f.strikeRatePct ?: 0.0) / 100.0).toFloat())
                }
                AmoCard(title = stringResource(R.string.amo_live_geo)) {
                    AmoFigure(stringResource(R.string.amo_live_geo_valid), pctText(f.geoValidPct))
                    if (f.geoValidVisits != null && f.visitsTotal != null) AmoFigure(stringResource(R.string.amo_live_geo_visits), stringResource(R.string.amo_live_x_of_y, localizedNumber(f.geoValidVisits), localizedNumber(f.visitsTotal)))
                    if (f.photoValidVisits != null && f.visitsTotal != null) AmoFigure(stringResource(R.string.amo_live_photo_valid), stringResource(R.string.amo_live_x_of_y, localizedNumber(f.photoValidVisits), localizedNumber(f.visitsTotal)))
                    AmoFigure(stringResource(R.string.amo_live_force), pctText(f.forceSalePct))
                    AmoFigure(stringResource(R.string.amo_live_mock), localizedNumber(f.mockVisits))
                    AmoFigure(stringResource(R.string.amo_live_suspicious), localizedNumber(f.suspiciousVisits))
                }
                AmoCard(title = stringResource(R.string.amo_live_login)) {
                    AmoFigure(stringResource(R.string.amo_live_logged_in), stringResource(R.string.amo_live_x_of_y, localizedNumber(f.loggedInRoutes), localizedNumber(f.targetRoutes)) + " (" + pctText(f.loginPct) + ")")
                    AmoBar(((f.loginPct ?: 0.0) / 100.0).toFloat())
                    AmoFigure(stringResource(R.string.amo_live_submitted), stringResource(R.string.amo_live_x_of_y, localizedNumber(f.submittedRoutes), localizedNumber(f.loggedInRoutes)) + " (" + pctText(f.submitPct) + ")")
                    AmoBar(((f.submitPct ?: 0.0) / 100.0).toFloat())
                    AmoFigure(stringResource(R.string.amo_live_not_logged_in), localizedNumber(f.notLoggedIn.size.toLong()))
                    AmoFigure(stringResource(R.string.amo_live_not_submitted), localizedNumber(f.loggedInNotSubmitted.size.toLong()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp)) { AronPrimaryButton(stringResource(R.string.amo_live_filter), onFilter, Modifier.weight(1f)) }
    }
}

/** The filter: nothing changes until Filter is pressed. */
@Composable
fun LiveFilterContent(
    zones: List<Pair<Long, String>>, routes: List<Pair<Long, String>>, zone: Long?, route: Long?, onZone: (Long?) -> Unit, onRoute: (Long?) -> Unit, onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        AmoPage(Modifier.weight(1f)) {
            AmoHeading(stringResource(R.string.amo_live_zone))
            val z = listOf<Pair<Long?, String>>(null to stringResource(R.string.amo_live_all_zones)) + zones
            z.forEach { (id, name) -> AmoRowCard(name, selected = id == zone, trailing = if (id == zone) stringResource(R.string.amo_verify_selected) else null, onClick = { onZone(id) }) }
            AmoHeading(stringResource(R.string.amo_live_route))
            val r = listOf<Pair<Long?, String>>(null to stringResource(R.string.amo_live_all_routes)) + routes
            r.forEach { (id, name) -> AmoRowCard(name, selected = id == route, trailing = if (id == route) stringResource(R.string.amo_verify_selected) else null, onClick = { onRoute(id) }) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp)) { AronPrimaryButton(stringResource(R.string.amo_live_apply), onApply, Modifier.weight(1f)) }
    }
}
