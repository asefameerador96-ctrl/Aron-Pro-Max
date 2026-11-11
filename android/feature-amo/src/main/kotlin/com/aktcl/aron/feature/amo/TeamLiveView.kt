package com.aktcl.aron.feature.amo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.rules.UiLocale

object LiveTags { const val SALES = "live_sales"; const val STRIKE = "live_strike"; const val GEO = "live_geo"; const val LOGIN = "live_login"; const val ROUTE = "live_route"; const val AMO = "live_amo"; const val FILTER = "live_filter" }

/** The key figures of the purple panel: cyan on the deep purple in the normal look; the panel's own ink in Sunlight (solid, 7:1). */
@Composable
private fun panelFigure(): Color = LocalAronLook.current.let { if (it.sunlight) it.onPanel else Color(0xFF5CE1E6) }

@Composable
private fun SalesPanel(d: LiveData, perRoute: Boolean) {
    val look = LocalAronLook.current
    val fig = panelFigure()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(look.panel).padding(vertical = 14.dp, horizontal = 12.dp).testTag(LiveTags.SALES),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.amo_live_sales_title), style = amoText(20, bold = true, color = look.onPanel))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (perRoute) d.columns().take(4).forEach { c ->
                val field = c.field()
                PanelColumn(field?.let { FieldQty.plain(it.first) } ?: c.qtyBase.toString(), categoryLabel(c.code), field?.let { com.aktcl.aron.core.ui.entryUnitLabel(it.second) } ?: (UnitWords.key(c.baseUnit, c.code) ?: FieldQty.baseUnitKey(c.code))?.let { com.aktcl.aron.core.ui.entryUnitLabel(it) }, fig, memo = c.memos?.let { stringResource(R.string.amo_team_live_memos, it.toString()) } ?: stringResource(R.string.amo_team_live_memos_none))
            } else {
                PanelColumn(d.memoCount.toString(), stringResource(R.string.amo_live_total_sales), null, fig)
                PanelColumn(d.totalTaka(), stringResource(R.string.amo_live_total_taka), null, fig)
            }
        }
    }
}

@Composable
private fun PanelColumn(value: String, label: String, unit: String?, fig: Color, memo: String? = null) {
    val look = LocalAronLook.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(value, style = amoText(24, bold = true, color = fig))
        Text(label, style = amoText(14, color = look.onPanel))
        if (unit != null) Text(unit, style = amoText(11, color = look.onPanelMuted))
        // the server's count of accepted memos with a line of this category on this day (CategoryVolume.memos); a dash when it sent none
        if (memo != null) Text(memo, style = amoText(11, color = look.onPanelMuted))
    }
}

@Composable
private fun categoryLabel(code: String): String = when (code) {
    "cigarette" -> stringResource(R.string.amo_team_cat_cigarette); "bidi" -> stringResource(R.string.amo_team_cat_bidi)
    "lighter" -> stringResource(R.string.amo_team_cat_lighter); "match" -> stringResource(R.string.amo_team_cat_match); else -> code
}

/** A ring gauge: grey track, green progress with round caps, the percent in the centre and a caption under it. */
@Composable
private fun Ring(fraction: Float, centre: String, caption: String?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val track = look.track
    val fill = if (look.sunlight) Color(0xFF1B5E20) else Color(0xFF2E9E4F)
    Box(modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(112.dp)) {
            val w = 12.dp.toPx()
            val inset = w / 2
            val arc = Size(size.width - w, size.height - w)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            if (fraction > 0f) drawArc(fill, 135f, 270f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centre, style = amoText(18, bold = true))
            if (caption != null) Text(caption, style = amoText(11, color = look.inkMuted))
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val look = LocalAronLook.current
    Column { Text(value, style = amoText(26, bold = true)); Text(label, style = amoText(13, color = look.inkMuted)) }
}

@Composable
private fun LiveCard(title: String, tag: String, content: @Composable () -> Unit) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card).padding(14.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = amoText(17, bold = true, align = TextAlign.Center))
        content()
    }
}

@Composable
private fun ProgressBlock(icon: Int, title: String, pct: String, fraction: Float, left: Pair<String, String>, right: Pair<String, String>) {
    val look = LocalAronLook.current
    val blue = if (look.sunlight) Color(0xFF0D47A1) else Color(0xFF2F80ED)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamIcon(icon, tint = Color(0xFF2E9E4F), size = 26.dp)
            Text(title, style = amoText(15, bold = true), modifier = Modifier.weight(1f))
            Text(pct, style = amoText(15, bold = true))
        }
        AmoBandBar(fraction, blue)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(left.first, left.second); Stat(right.first, right.second)
        }
    }
}

/**
 * Live Data (screenshot 39, D-AMO-MAN-11, R11): the selectors, the Sales panel (one route: four categories in base units; all routes:
 * count and taka), Live Strike Rate, Geo Fencing Status and Login and Bikroy Joma status. Latin digits as the vendor prints them.
 */
@Composable
fun LiveDataContent(
    d: LiveData?, amoName: String, routeValue: String, routeOptions: List<Pair<String, String>>, perRoute: Boolean,
    onRoute: (String) -> Unit, onFilter: () -> Unit, stale: Boolean, failed: Boolean, refused: Boolean = false, modifier: Modifier = Modifier, routeNote: Boolean = false,
) {
    val look = LocalAronLook.current
    AmoPage(modifier) {
        AmoFetchBanner(failed, stale, refused)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmoDropdown(amoName, emptyList(), {}, Modifier.weight(1f).testTag(LiveTags.AMO), enabled = false, small = true)
            AmoDropdown(routeValue, routeOptions, onRoute, Modifier.weight(1f).testTag(LiveTags.ROUTE), small = true)
            androidx.compose.material3.IconButton(onClick = onFilter, modifier = Modifier.testTag(LiveTags.FILTER)) { TeamIcon(R.drawable.amo_team_ic_filter, tint = look.purple, size = 28.dp) }
        }
        if (routeNote) AmoNotice(stringResource(R.string.amo_live_route_note))
        if (d == null) { AmoEmpty(stringResource(R.string.amo_live_none)); return@AmoPage }
        SalesPanel(d, perRoute)
        LiveCard(stringResource(R.string.amo_live_strike_title), LiveTags.STRIKE) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(d.targetOutlets.toString(), stringResource(R.string.amo_live_target_outlets)); Stat(d.successfulCalls.toString(), stringResource(R.string.amo_live_successful_calls))
                }
                Ring((d.strikeBp ?: 0L) / 10_000f, d.strikeText(UiLocale.EN), stringResource(R.string.amo_live_strike_rate))
            }
        }
        LiveCard(stringResource(R.string.amo_live_geo_title), LiveTags.GEO) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(d.targetOutlets.toString(), stringResource(R.string.amo_live_target_outlets)); Stat(d.geoValid?.toString() ?: "—", stringResource(R.string.amo_live_geo_validation)); Stat(d.photoValid?.toString() ?: "—", stringResource(R.string.amo_live_photo_validation))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Ring((d.geoBp ?: 0L) / 10_000f, d.geoText(UiLocale.EN), null)
                    Text(stringResource(R.string.amo_live_total_calls), style = amoText(12, color = look.inkMuted)); Text(d.totalCalls?.toString() ?: "—", style = amoText(20, bold = true))
                }
            }
        }
        LiveCard(stringResource(R.string.amo_live_login_title), LiveTags.LOGIN) {
            ProgressBlock(
                R.drawable.amo_team_ic_login, stringResource(R.string.amo_live_login_status), d.loginText(UiLocale.EN), (d.loginPct ?: 0L) / 100f,
                d.targetRoutes.toString() to stringResource(R.string.amo_live_target_routes), d.loggedInRoutes.toString() to stringResource(R.string.amo_live_total_login),
            )
            ProgressBlock(
                R.drawable.amo_team_ic_submit, stringResource(R.string.amo_live_submit_status), d.submitText(UiLocale.EN), (d.submitPct ?: 0L) / 100f,
                d.loggedInRoutes.toString() to stringResource(R.string.amo_live_logged_in_routes), d.submittedRoutes.toString() to stringResource(R.string.amo_live_total_submit),
            )
        }
    }
}
