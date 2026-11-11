package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/** "80,000.00 ৳ · 4 memos": the category's money and, when the server has it, its memo count. */
@Composable
private fun liveCategoryMoney(c: LiveCategory, money: (Long) -> String): String =
    money(c.netMtk) + (c.memos?.let { " · " + androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(R.plurals.amo_live_memos, if (it == 1L) 1 else 2, localizedNumber(it)) } ?: "")

private data class SalesCell(val figure: String, val label: String, val unit: String?, val sub: String?)

/** One stacked figure of the purple Sales panel (screenshot 39): the figure in cyan, its unit, its label in white, an optional small line. */
@Composable
private fun SalesStat(figure: String, label: String, onPanel: androidx.compose.ui.graphics.Color, figureColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, unit: String? = null, sub: String? = null) {
    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
        androidx.compose.material3.Text(figure, style = amoText(20, bold = true, color = figureColor), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (!unit.isNullOrEmpty()) androidx.compose.material3.Text(unit, style = amoText(12, color = onPanel), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        androidx.compose.material3.Text(label, style = amoText(14, color = onPanel), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (sub != null) androidx.compose.material3.Text(sub, style = amoText(12, color = onPanel), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/**
 * The Sales panel (screenshot 39): a purple gradient card with the white title and the figures in cyan columns, one per category in its field unit
 * (exact text), then the gross and net money. Contrast (owner rule 7:1 for key figures): the cyan 00E5FF holds 7.26:1 on the AMO panel purple 4A1A96,
 * so the gradient only deepens toward the left (it never gets lighter than the panel colour, which the screenshot's lighter violet end would); on a light
 * panel (Sunlight) the figures are the plain panel text colour.
 */
@Composable
private fun LiveSalesPanel(f: LiveFigures, money: (Long) -> String) {
    val look = com.aktcl.aron.core.ui.LocalAronLook.current
    val darkPanel = look.panel.luminance() < 0.2f
    val figure = if (darkPanel) androidx.compose.ui.graphics.Color(0xFF00E5FF) else look.onPanel
    val brush = if (darkPanel) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.lerp(look.panel, androidx.compose.ui.graphics.Color.Black, 0.18f), look.panel))
        else androidx.compose.ui.graphics.SolidColor(look.panel)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    Column(Modifier.fillMaxWidth().clip(shape).background(brush).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.Text(stringResource(R.string.amo_live_sales), style = amoText(16, bold = true, color = look.onPanel), modifier = Modifier.fillMaxWidth().semantics { heading() }, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        val cells = f.categories.map { c -> SalesCell(localizedDigits(c.qtyText), liveCategoryName(c.code), fieldUnitWord(c.unitKey, c.code, c.qtyText), liveCategoryMoney(c, money)) } +
            listOf(SalesCell(money(f.grossMtk), stringResource(R.string.amo_live_gross), null, null), SalesCell(money(f.netMtk), stringResource(R.string.amo_live_net), null, null))
        // a category per column, then the two money figures; the large-text sizes stack them one under another
        cells.chunked(if (stacked) 1 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                row.forEach { c -> SalesStat(c.figure, c.label, look.onPanel, figure, Modifier.weight(1f), unit = c.unit, sub = c.sub) }
            }
        }
    }
}

/** A bare count over its label (screenshot 39, the Geo Fencing card). */
@Composable
private fun CountStat(count: Long, label: String, modifier: Modifier = Modifier) {
    val look = com.aktcl.aron.core.ui.LocalAronLook.current
    Column(modifier) {
        androidx.compose.material3.Text(localizedNumber(count), style = amoText(22, bold = true, color = look.purple))
        androidx.compose.material3.Text(label, style = amoText(14, color = look.inkMuted))
    }
}

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
                LiveSalesPanel(f, money)
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
                    // bare counts as the screenshot has them: target outlets, geo validation, photo validation, and the total outlet visits as its own figure
                    val amoCounts = f.geoValidVisits != null || f.photoValidVisits != null || f.visitsTotal != null // an older server sends none: no bare-count rows then
                    if (amoCounts) Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        CountStat(f.targetOutlets, stringResource(R.string.amo_rep_target), Modifier.weight(1f))
                        f.visitsTotal?.let { CountStat(it, stringResource(R.string.amo_live_visits_total), Modifier.weight(1f)) }
                    }
                    if (f.geoValidVisits != null || f.photoValidVisits != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        f.geoValidVisits?.let { CountStat(it, stringResource(R.string.amo_live_geo_visits), Modifier.weight(1f)) }
                        f.photoValidVisits?.let { CountStat(it, stringResource(R.string.amo_live_photo_valid), Modifier.weight(1f)) }
                    }
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
