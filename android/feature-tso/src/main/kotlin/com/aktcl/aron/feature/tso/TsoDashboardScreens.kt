package com.aktcl.aron.feature.tso

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

@Composable private fun pct1(v: Double?): String = v?.let { localizedDigits(TsoDashboards.oneDecimal(it)) + " %" } ?: stringResource(R.string.tso_dash_dash)

@Composable
private fun categoryName(code: String) = when (code) {
    "cigarette" -> stringResource(R.string.tso_cat_cigarette); "bidi" -> stringResource(R.string.tso_cat_bidi)
    "lighter" -> stringResource(R.string.tso_cat_lighter); "match" -> stringResource(R.string.tso_cat_match); else -> code
}

@Composable
private fun unitName(unit: String?) = when (unit) {
    "stick" -> stringResource(R.string.tso_unit_stick); "dozen" -> stringResource(R.string.tso_unit_dozen); "box" -> stringResource(R.string.tso_unit_box)
    "pack" -> stringResource(R.string.tso_unit_pack); else -> stringResource(R.string.tso_unit_piece)
}

/** What the dashboard knows: the territory figures and the login status, each possibly a kept copy; [asOf] is shown on every aggregate card. */
enum class TsoFailure { NONE, OFFLINE, REFUSED }

data class TsoDashboardState(
    val dash: TsoDash?, val login: LoginSubmit?, val stale: Boolean, val failure: TsoFailure, val loginStale: Boolean = false, val loginFailure: TsoFailure = TsoFailure.NONE,
    /** Zones the TSO can filter by (only zones; empty hides the Filter button). */
    val zones: List<ZoneRef> = emptyList(), val businessDate: String = "",
    /** A zone is chosen: Filter stays reachable so the TSO can go back to all zones even when the zone list could not be read. */
    val zoneChosen: Boolean = false,
)

/**
 * F-TSO-002..007 and F-TSO-027: the TSO dashboard. Opened on demand: Refresh is a button, there is no timer. Each aggregate card names its
 * "as of hh:mm". [money] formats milli-taka in the user's digits. No target and no achievement ring (docs/27).
 */
@Composable
fun TsoDashboardContent(
    state: TsoDashboardState, zoneLabel: String, money: (Long) -> String, onRefresh: () -> Unit, onFilter: () -> Unit, onNotLoggedIn: () -> Unit, onNotUploaded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = state.dash
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FailureBanner(state.failure, state.stale, d != null)
        AronCard {
            Text(zoneLabel, style = MaterialTheme.typography.titleMedium, color = LocalAronLook.current.purple)
            AsOfLine(state.businessDate, d?.asOf)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalAronLook.current.inkMuted) }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.zones.isNotEmpty() || state.zoneChosen) AronSecondaryButton(stringResource(R.string.tso_dash_filter), onFilter, Modifier.weight(1f))
                AronPrimaryButton(stringResource(R.string.tso_dash_refresh), onRefresh, Modifier.weight(1f))
            }
        }
        if (d == null) {
            // The note above already says why; the open-me hint is for a plain empty answer only.
            if (state.failure == TsoFailure.NONE) TsoNote(stringResource(R.string.tso_dash_none) + "\n" + stringResource(R.string.tso_dash_none_hint))
        } else {
            SalesTiles(d.categories)
            CprCard(d)
            ChannelCard(d.channels, money)
            SegmentCard(d.segments, money)
            BrandCard(d.brands, d.activeMemos)
        }
        LoginCard(state.login, state.loginStale, state.loginFailure, onNotLoggedIn, onNotUploaded)
    }
}

/** Three honest states: offline with no copy, refused by the server, or a kept copy of an earlier answer. */
@Composable
internal fun FailureBanner(failure: TsoFailure, stale: Boolean, hasData: Boolean) {
    when {
        failure == TsoFailure.REFUSED -> TsoNote(stringResource(R.string.tso_dash_refused))
        failure == TsoFailure.OFFLINE -> TsoNote(stringResource(if (hasData) R.string.tso_dash_offline_kept else R.string.tso_dash_offline_none))
        stale -> TsoNote(stringResource(R.string.tso_dash_kept))
    }
}

/** "2026-10-17 · as of 14:30": the business day next to the stamp, so a kept copy of another day is never taken for today's. */
@Composable
internal fun AsOfLine(businessDate: String, asOf: String?): String? {
    val clock = asOf?.let { TsoDashboards.asOfClock(it) }?.let { stringResource(R.string.tso_dash_as_of, localizedDigits(it)) }
    return listOfNotNull(businessDate.takeIf { it.isNotBlank() }?.let { localizedDigits(it) }, clock).joinToString(stringResource(R.string.tso_dash_sep)).ifEmpty { null }
}

@Composable
private fun CardTitle(text: String, asOf: String?) {
    val look = LocalAronLook.current
    Text(text, style = MaterialTheme.typography.titleMedium, color = look.purple)
    if (asOf != null) Text(stringResource(R.string.tso_dash_as_of, localizedDigits(asOf)), style = MaterialTheme.typography.labelSmall, color = look.inkMuted)
}

/** F-TSO-002: the purple sales card of the manual (S-04): one row per category; one decimal, lakh/crore for big quantities; no achievement ring (docs/27). */
@Composable
private fun SalesTiles(categories: List<CategorySales>) {
    val look = LocalAronLook.current
    val order = listOf("cigarette", "bidi", "lighter", "match")
    val rows = order.map { code -> categories.firstOrNull { it.code == code } ?: CategorySales(code, TsoDashboards.defaultUnit(code), 0, null, null, 0) } + categories.filter { it.code !in order }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(look.targetTop, look.targetBottom))).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        rows.forEachIndexed { i, cat ->
            val qty = cat.qtyReport ?: cat.qtyBase.toDouble()
            val unit = unitName(cat.reportUnit ?: cat.baseUnit.ifBlank { TsoDashboards.defaultUnit(cat.code) })
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tso_dash_sales_of, categoryName(cat.code)), style = MaterialTheme.typography.bodyMedium, color = look.onPanel)
                    Text(unit, style = MaterialTheme.typography.bodySmall, color = look.onPanelMuted)
                }
                Text(quantity(qty, cat.qtyReport != null), style = MaterialTheme.typography.headlineSmall, color = look.onPanel, maxLines = 1)
            }
            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(look.onPanelMuted.copy(alpha = 0.35f)))
        }
    }
}

/** One decimal, with the lakh or crore word for large quantities, digits in the user's language. */
@Composable
private fun quantity(v: Double, fractional: Boolean): String {
    val whole = v.toLong()
    val compact = TsoDashboards.compact(whole)
    return when (compact.scale) {
        TsoDashboards.Scale.CRORE -> localizedDigits(compact.text) + " " + stringResource(R.string.tso_dash_crore)
        TsoDashboards.Scale.LAKH -> localizedDigits(compact.text) + " " + stringResource(R.string.tso_dash_lakh)
        // A whole base quantity is grouped (28,067); one decimal only for a report-unit quantity (1,110.5 boxes).
        TsoDashboards.Scale.NONE -> if (fractional && v != Math.floor(v)) localizedNumber(whole) + localizedDigits("." + TsoDashboards.fractionDigit(v)) else localizedNumber(whole)
    }
}

/** F-TSO-004: target outlets, successful calls and the call rate (2 of 44 is 4.5 percent). */
@Composable
private fun CprCard(d: TsoDash) {
    val c = LocalAronLook.current
    AronCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle(stringResource(R.string.tso_dash_cpr), d.asOf.let { TsoDashboards.asOfClock(it) })
            Text(pct1(TsoDashboards.strikeRate(d.successfulCalls, d.targetOutlets)), style = MaterialTheme.typography.headlineMedium, color = c.ink)
            Text(stringResource(R.string.tso_dash_x_of_y, localizedNumber(d.successfulCalls), localizedNumber(d.targetOutlets)), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            Text(stringResource(R.string.tso_dash_cpr_labels), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
        }
    }
}

@Composable
private fun palette(): List<Color> { val c = LocalAronLook.current; return listOf(c.purple, c.red, c.barMid, c.inkMuted, c.panelDark, c.silhouetteInk) }

/** F-TSO-003: By Channel STD as a donut with a legend that carries every number (the colour is never the only signal). */
@Composable
private fun ChannelCard(channels: List<SliceRow>, money: (Long) -> String) {
    val c = LocalAronLook.current
    val colors = palette()
    val shares = TsoDashboards.shares(channels.map { it.netMtk })
    AronCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle(stringResource(R.string.tso_dash_channel_std), null)
            if (channels.isEmpty() || shares.sum() <= 0.0) { Text(stringResource(R.string.tso_dash_no_sales), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted); return@Column }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(112.dp)) {
                    val stroke = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Butt)
                    var start = -90f
                    shares.forEachIndexed { i, sh ->
                        val sweep = (sh * 3.6).toFloat()
                        drawArc(colors[i % colors.size], start, sweep, false, Offset(11.dp.toPx(), 11.dp.toPx()), Size(size.width - 22.dp.toPx(), size.height - 22.dp.toPx()), style = stroke)
                        start += sweep
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    channels.forEachIndexed { i, ch ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).background(colors[i % colors.size], RoundedCornerShape(3.dp)))
                            Text(ch.name.ifBlank { ch.code } + " " + pct1(shares[i]) + " · " + money(ch.netMtk), style = MaterialTheme.typography.bodySmall, color = c.ink)
                        }
                    }
                }
            }
        }
    }
}

/** F-TSO-005: value (net) against volume per segment, both as shares of the territory. */
@Composable
private fun SegmentCard(segments: List<SliceRow>, money: (Long) -> String) {
    val c = LocalAronLook.current
    val value = TsoDashboards.shares(segments.map { it.netMtk }); val volume = TsoDashboards.shares(segments.map { it.qtyBase ?: 0 })
    AronCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle(stringResource(R.string.tso_dash_segment), null)
            if (segments.isEmpty()) { Text(stringResource(R.string.tso_dash_no_sales), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted); return@Column }
            segments.forEachIndexed { i, sg ->
                Text(sg.name.ifBlank { sg.code } + " · " + money(sg.netMtk), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                Bar(value[i], c.purple, stringResource(R.string.tso_dash_value) + " " + pct1(value[i]))
                Bar(volume[i], c.red, stringResource(R.string.tso_dash_volume) + " " + pct1(volume[i]))
            }
        }
    }
}

@Composable
private fun Bar(pct: Double, color: Color, label: String) {
    val c = LocalAronLook.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().height(10.dp).background(c.track, RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((pct / 100.0).coerceIn(0.0, 1.0).toFloat()).background(color, RoundedCornerShape(5.dp)))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
    }
}

/** F-TSO-006: memos containing the brand over the territory's active memos, per brand. */
@Composable
private fun BrandCard(brands: List<SliceRow>, activeMemos: Long) {
    val c = LocalAronLook.current
    AronCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle(stringResource(R.string.tso_dash_brand_ratio), null)
            if (brands.isEmpty()) { Text(stringResource(R.string.tso_dash_no_sales), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted); return@Column }
            brands.sortedByDescending { it.memos }.forEach { b ->
                val ratio = TsoDashboards.brandRatio(b, activeMemos)
                Text(b.name.ifBlank { b.code } + " · " + stringResource(R.string.tso_dash_x_of_y, localizedNumber(b.memos), localizedNumber(activeMemos)), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                Bar(ratio ?: 0.0, c.purple, pct1(ratio))
            }
        }
    }
}

/** F-TSO-007: Login Status and Bikroy Joma (submit) status with the two drill-down lists. */
@Composable
private fun LoginCard(login: LoginSubmit?, stale: Boolean, failure: TsoFailure, onNotLoggedIn: () -> Unit, onNotUploaded: () -> Unit) {
    val c = LocalAronLook.current
    AronCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle(stringResource(R.string.tso_dash_login_submit), login?.asOf?.let { TsoDashboards.asOfClock(it) })
            if (failure == TsoFailure.REFUSED) { TsoNote(stringResource(R.string.tso_dash_refused)); return@Column }
            if (login == null) { Text(stringResource(if (failure == TsoFailure.OFFLINE) R.string.tso_dash_offline_none else R.string.tso_dash_no_login), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted); return@Column }
            if (stale || failure == TsoFailure.OFFLINE) TsoNote(stringResource(R.string.tso_dash_kept))
            Text(stringResource(R.string.tso_dash_login_status) + " " + pct1(login.loginPct), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            Bar(login.loginPct ?: 0.0, c.purple, stringResource(R.string.tso_dash_x_of_y, localizedNumber(login.loggedIn), localizedNumber(login.targetRoutes)))
            Text(stringResource(R.string.tso_dash_submit_status) + " " + pct1(login.submitPct), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            Bar(login.submitPct ?: 0.0, c.red, stringResource(R.string.tso_dash_x_of_y, localizedNumber(login.submitted), localizedNumber(login.loggedIn)))
            AronSecondaryButton(stringResource(R.string.tso_dash_not_logged_in, localizedNumber(login.notLoggedIn.size.toLong())), onNotLoggedIn, Modifier.fillMaxWidth())
            AronSecondaryButton(stringResource(R.string.tso_dash_not_uploaded, localizedNumber(login.loggedInNotSubmitted.size.toLong())), onNotUploaded, Modifier.fillMaxWidth())
        }
    }
}

/** The drill-down: the routes behind a count with their stamp. The server names the route (code and name); it sends no SR name yet (request filed). */
@Composable
fun TsoRouteListContent(rows: List<RouteDayRow>, businessDate: String, asOf: String?, stale: Boolean, failure: TsoFailure, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FailureBanner(failure, stale, rows.isNotEmpty() || asOf != null)
        AronListRow(AsOfLine(businessDate, asOf) ?: stringResource(R.string.tso_dash_dash))
        if (rows.isEmpty()) { TsoNote(stringResource(R.string.tso_dash_list_empty)); return@Column }
        TsoSectionTitle(stringResource(R.string.tso_dash_routes))
        rows.forEach { r ->
            AronListRow(
                listOfNotNull(r.routeCode, r.routeName).joinToString(stringResource(R.string.tso_dash_sep)).ifBlank { stringResource(R.string.tso_dash_route_unnamed) },
                subtitle = r.srName?.let { sr -> stringResource(R.string.tso_dash_sr_of_route, sr) },
            )
        }
    }
}

/** The zone filter: nothing changes until the choice is applied. */
@Composable
fun TsoZoneFilterContent(zones: List<ZoneRef>, selected: Long?, onSelect: (Long?) -> Unit, modifier: Modifier = Modifier) {
    val all = listOf<Pair<Long?, String>>(null to stringResource(R.string.tso_dash_all_zones)) + zones.map { it.id to it.name }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TsoSectionTitle(stringResource(R.string.tso_dash_zone))
        all.forEach { (id, name) -> AronListRow(name, onClick = { onSelect(id) }, trailing = if (id == selected) stringResource(R.string.tso_dash_selected) else null) }
    }
}
