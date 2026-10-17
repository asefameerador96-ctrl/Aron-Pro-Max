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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

private fun pos(i: Int, n: Int) = if (n == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == n - 1) RowPosition.Last else RowPosition.Middle

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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        FailureBanner(state.failure, state.stale, d != null)
        AronListRow(zoneLabel, subtitle = AsOfLine(state.businessDate, d?.asOf), flat = true)
        Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            if (state.zones.isNotEmpty()) AronSecondaryButton(stringResource(R.string.tso_dash_filter), onFilter, Modifier.weight(1f))
            AronSecondaryButton(stringResource(R.string.tso_dash_refresh), onRefresh, Modifier.weight(1f))
        }
        if (d == null) {
            AronEmptyState(stringResource(R.string.tso_dash_none), Modifier.fillMaxWidth(), hint = stringResource(R.string.tso_dash_none_hint))
            return@Column
        }
        SalesTiles(d.categories)
        CprCard(d)
        ChannelCard(d.channels, money)
        SegmentCard(d.segments, money)
        BrandCard(d.brands, d.activeMemos)
        LoginCard(state.login, state.loginStale, state.loginFailure, onNotLoggedIn, onNotUploaded)
    }
}

/** Three honest states: offline with no copy, refused by the server, or a kept copy of an earlier answer. */
@Composable
private fun FailureBanner(failure: TsoFailure, stale: Boolean, hasData: Boolean) {
    when {
        failure == TsoFailure.REFUSED -> AronBanner(stringResource(R.string.tso_dash_refused), kind = BannerKind.Error)
        failure == TsoFailure.OFFLINE -> AronBanner(stringResource(if (hasData) R.string.tso_dash_offline_kept else R.string.tso_dash_offline_none), kind = BannerKind.Warning)
        stale -> AronBanner(stringResource(R.string.tso_dash_kept), kind = BannerKind.Info)
    }
}

/** "2026-10-17 · as of 14:30": the business day next to the stamp, so a kept copy of another day is never taken for today's. */
@Composable
private fun AsOfLine(businessDate: String, asOf: String?): String? {
    val clock = asOf?.let { TsoDashboards.asOfClock(it) }?.let { stringResource(R.string.tso_dash_as_of, localizedDigits(it)) }
    return listOfNotNull(businessDate.takeIf { it.isNotBlank() }?.let { localizedDigits(it) }, clock).joinToString(stringResource(R.string.tso_dash_sep)).ifEmpty { null }
}

@Composable
private fun CardTitle(text: String, asOf: String?) {
    val c = LocalAronColors.current
    Text(text, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
    if (asOf != null) Text(stringResource(R.string.tso_dash_as_of, localizedDigits(asOf)), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
}

/** F-TSO-002: four sales tiles; one decimal, lakh/crore for big quantities; no achievement ring (docs/27). */
@Composable
private fun SalesTiles(categories: List<CategorySales>) {
    val order = listOf("cigarette", "bidi", "lighter", "match")
    val rows = order.map { code -> categories.firstOrNull { it.code == code } ?: CategorySales(code, TsoDashboards.defaultUnit(code), 0, null, null, 0) } + categories.filter { it.code !in order }
    rows.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            pair.forEach { cat ->
                val c = LocalAronColors.current
                val qty = cat.qtyReport ?: cat.qtyBase.toDouble()
                val unit = unitName(cat.reportUnit ?: cat.baseUnit.ifBlank { TsoDashboards.defaultUnit(cat.code) })
                AronCard(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                        Text(stringResource(R.string.tso_dash_sales_of, categoryName(cat.code)), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        Text(quantity(qty, cat.qtyReport != null), style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, maxLines = 1)
                        Text(unit, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
            }
            if (pair.size == 1) Box(Modifier.weight(1f))
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
        TsoDashboards.Scale.NONE -> if (fractional && v != Math.floor(v)) localizedDigits(TsoDashboards.oneDecimal(v)) else localizedNumber(whole)
    }
}

/** F-TSO-004: target outlets, successful calls and the call rate (2 of 44 is 4.5 percent). */
@Composable
private fun CprCard(d: TsoDash) {
    val c = LocalAronColors.current
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            CardTitle(stringResource(R.string.tso_dash_cpr), d.asOf.let { TsoDashboards.asOfClock(it) })
            Text(pct1(TsoDashboards.strikeRate(d.successfulCalls, d.targetOutlets)), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
            Text(stringResource(R.string.tso_dash_x_of_y, localizedNumber(d.successfulCalls), localizedNumber(d.targetOutlets)), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Text(stringResource(R.string.tso_dash_cpr_labels), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun palette(): List<Color> { val c = LocalAronColors.current; return listOf(c.accent, c.success, c.warning, c.danger, c.accentHi, c.textSecondary) }

/** F-TSO-003: By Channel STD as a donut with a legend that carries every number (the colour is never the only signal). */
@Composable
private fun ChannelCard(channels: List<SliceRow>, money: (Long) -> String) {
    val c = LocalAronColors.current
    val colors = palette()
    val shares = TsoDashboards.shares(channels.map { it.netMtk })
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            CardTitle(stringResource(R.string.tso_dash_channel_std), null)
            if (channels.isEmpty() || shares.sum() <= 0.0) { Text(stringResource(R.string.tso_dash_no_sales), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary); return@Column }
            Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(112.dp)) {
                    val stroke = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Butt)
                    var start = -90f
                    shares.forEachIndexed { i, sh ->
                        val sweep = (sh * 3.6).toFloat()
                        drawArc(colors[i % colors.size], start, sweep, false, Offset(11.dp.toPx(), 11.dp.toPx()), Size(size.width - 22.dp.toPx(), size.height - 22.dp.toPx()), style = stroke)
                        start += sweep
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                    channels.forEachIndexed { i, ch ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                            Box(Modifier.size(10.dp).background(colors[i % colors.size], RoundedCornerShape(3.dp)))
                            Text(ch.name.ifBlank { ch.code } + " " + pct1(shares[i]) + " · " + money(ch.netMtk), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
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
    val c = LocalAronColors.current
    val value = TsoDashboards.shares(segments.map { it.netMtk }); val volume = TsoDashboards.shares(segments.map { it.qtyBase ?: 0 })
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            CardTitle(stringResource(R.string.tso_dash_segment), null)
            if (segments.isEmpty()) { Text(stringResource(R.string.tso_dash_no_sales), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary); return@Column }
            segments.forEachIndexed { i, sg ->
                Text(sg.name.ifBlank { sg.code } + " · " + money(sg.netMtk), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Bar(value[i], c.accent, stringResource(R.string.tso_dash_value) + " " + pct1(value[i]))
                Bar(volume[i], c.success, stringResource(R.string.tso_dash_volume) + " " + pct1(volume[i]))
            }
        }
    }
}

@Composable
private fun Bar(pct: Double, color: Color, label: String) {
    val c = LocalAronColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().height(10.dp).background(c.accentContainer, RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((pct / 100.0).coerceIn(0.0, 1.0).toFloat()).background(color, RoundedCornerShape(5.dp)))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}

/** F-TSO-006: memos containing the brand over the territory's active memos, per brand. */
@Composable
private fun BrandCard(brands: List<SliceRow>, activeMemos: Long) {
    val c = LocalAronColors.current
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            CardTitle(stringResource(R.string.tso_dash_brand_ratio), null)
            if (brands.isEmpty()) { Text(stringResource(R.string.tso_dash_no_sales), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary); return@Column }
            brands.sortedByDescending { it.memos }.forEach { b ->
                val ratio = TsoDashboards.brandRatio(b, activeMemos)
                Text(b.name.ifBlank { b.code } + " · " + stringResource(R.string.tso_dash_x_of_y, localizedNumber(b.memos), localizedNumber(activeMemos)), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Bar(ratio ?: 0.0, c.accent, pct1(ratio))
            }
        }
    }
}

/** F-TSO-007: Login Status and Bikroy Joma (submit) status with the two drill-down lists. */
@Composable
private fun LoginCard(login: LoginSubmit?, stale: Boolean, failure: TsoFailure, onNotLoggedIn: () -> Unit, onNotUploaded: () -> Unit) {
    val c = LocalAronColors.current
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            CardTitle(stringResource(R.string.tso_dash_login_submit), login?.asOf?.let { TsoDashboards.asOfClock(it) })
            if (failure == TsoFailure.REFUSED) { AronBanner(stringResource(R.string.tso_dash_refused), kind = BannerKind.Error); return@Column }
            if (login == null) { Text(stringResource(if (failure == TsoFailure.OFFLINE) R.string.tso_dash_offline_none else R.string.tso_dash_no_login), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary); return@Column }
            if (stale || failure == TsoFailure.OFFLINE) AronBanner(stringResource(R.string.tso_dash_kept), kind = BannerKind.Info)
            Text(stringResource(R.string.tso_dash_login_status) + " " + pct1(login.loginPct), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            Bar(login.loginPct ?: 0.0, c.accent, stringResource(R.string.tso_dash_x_of_y, localizedNumber(login.loggedIn), localizedNumber(login.targetRoutes)))
            Text(stringResource(R.string.tso_dash_submit_status) + " " + pct1(login.submitPct), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            Bar(login.submitPct ?: 0.0, c.success, stringResource(R.string.tso_dash_x_of_y, localizedNumber(login.submitted), localizedNumber(login.loggedIn)))
            AronTextButton(stringResource(R.string.tso_dash_not_logged_in, localizedNumber(login.notLoggedIn.size.toLong())), onNotLoggedIn)
            AronTextButton(stringResource(R.string.tso_dash_not_uploaded, localizedNumber(login.loggedInNotSubmitted.size.toLong())), onNotUploaded)
        }
    }
}

/** The drill-down: the routes behind a count with their stamp. The server names the route (code and name); it sends no SR name yet (request filed). */
@Composable
fun TsoRouteListContent(rows: List<RouteDayRow>, businessDate: String, asOf: String?, stale: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        if (stale) AronBanner(stringResource(R.string.tso_dash_kept), kind = BannerKind.Info)
        AronListRow(AsOfLine(businessDate, asOf) ?: stringResource(R.string.tso_dash_dash), flat = true)
        if (rows.isEmpty()) { AronEmptyState(stringResource(R.string.tso_dash_list_empty), Modifier.fillMaxWidth()); return@Column }
        AronSectionLabel(stringResource(R.string.tso_dash_routes))
        rows.forEachIndexed { i, r ->
            AronListRow(
                listOfNotNull(r.routeCode, r.routeName).joinToString(stringResource(R.string.tso_dash_sep)).ifBlank { stringResource(R.string.tso_dash_route_unnamed) },
                position = pos(i, rows.size), divider = i < rows.lastIndex, flat = true,
            )
        }
    }
}

/** The zone filter: nothing changes until the choice is applied. */
@Composable
fun TsoZoneFilterContent(zones: List<ZoneRef>, selected: Long?, onSelect: (Long?) -> Unit, modifier: Modifier = Modifier) {
    val all = listOf<Pair<Long?, String>>(null to stringResource(R.string.tso_dash_all_zones)) + zones.map { it.id to it.name }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        AronSectionLabel(stringResource(R.string.tso_dash_zone))
        all.forEachIndexed { i, (id, name) -> AronListRow(name, onClick = { onSelect(id) }, trailing = if (id == selected) stringResource(R.string.tso_dash_selected) else null, position = pos(i, all.size), divider = i < all.lastIndex, flat = true) }
    }
}
