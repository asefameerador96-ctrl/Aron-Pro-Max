package com.aktcl.aron.feature.amo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.rules.Formats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object TeamReportTags { const val STD = "report_tile_std"; const val SUMMARY = "report_tile_summary"; const val RANGE = "report_range"; const val TABLE = "report_std_table"; const val TOTAL = "report_std_total" }

/** Reports menu (screenshot 71 left): two small square tiles at the top left, each its own colour picture over a two-line caption (D-PAR-AMO-28). */
@Composable
fun ReportsTilesContent(onStd: () -> Unit, onSummary: () -> Unit, modifier: Modifier = Modifier) {
    // the old tiles are small and left aligned; at large text sizes they share the row so a long Bangla caption keeps its width
    val big = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    AmoPage(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val tileWidth: (Modifier) -> Modifier = { m -> if (big) with(this@Row) { m.weight(1f) } else m.width(116.dp) }
            ReportTile(true, stringResource(R.string.amo_rep_std), onStd, tileWidth(Modifier.testTag(TeamReportTags.STD)))
            ReportTile(false, stringResource(R.string.amo_rep_summary), onSummary, tileWidth(Modifier.testTag(TeamReportTags.SUMMARY)))
        }
    }
}

@Composable
private fun ReportTile(std: Boolean, label: String, onClick: () -> Unit, modifier: Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.heightIn(min = 132.dp).shadow(1.dp, shape).clip(shape).background(look.card).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        ReportArt(std, Modifier.size(56.dp))
        Text(label, style = amoText(14, color = look.purple, align = TextAlign.Center), modifier = Modifier.padding(top = 6.dp))
    }
}

/** The date-range field (a calendar icon and "October 1, 2026 - October 25, 2026"); the range cannot end after [today]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeField(from: String, to: String, today: String, onPick: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(look.card).border(BorderStroke(2.dp, look.purple), shape).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = true }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { TeamIcon(R.drawable.amo_team_ic_calendar, tint = look.purple, size = 24.dp); Text(TeamReports.rangeText(from, to), style = amoText(16)) }
    if (open) {
        fun ms(d: String) = LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val max = ms(today)
        val state = rememberDateRangePickerState(ms(from), ms(to), selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= max })
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null, onClick = {
                    fun iso(v: Long) = Instant.ofEpochMilli(v).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    val a = state.selectedStartDateMillis; val b = state.selectedEndDateMillis
                    if (a != null && b != null) onPick(iso(a), iso(b))
                    open = false
                }) { Text(stringResource(R.string.amo_team_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.amo_team_cancel)) } },
        ) { DateRangePicker(state, Modifier.heightIn(max = 520.dp)) }
    }
}

@Composable
private fun categoryTitle(code: String): String = when (code) {
    "cigarette" -> stringResource(R.string.amo_team_cat_cigarette); "bidi" -> stringResource(R.string.amo_team_cat_bidi)
    "lighter" -> stringResource(R.string.amo_team_cat_lighter); "match" -> stringResource(R.string.amo_team_cat_match); else -> code
}

/** STD Memo Report (screenshot 71 right): the range, then the route x category matrix; the zone name is the first header cell and the categories scroll sideways. */
@Composable
fun StdMatrixContent(from: String, to: String, today: String, onRange: (String, String) -> Unit, m: StdMatrix?, stale: Boolean, failed: Boolean, refused: Boolean = false, modifier: Modifier = Modifier, truncated: Boolean = false) {
    val look = LocalAronLook.current
    val l = teamLocale()
    val h = rememberScrollState()
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AmoFetchBanner(failed, stale, refused)
        RangeField(from, to, today, onRange, Modifier.testTag(TeamReportTags.RANGE))
        // a cut-off answer is never printed as complete: no matrix, no totals, only the note
        if (truncated) { AmoBanner(stringResource(R.string.amo_rep_truncated), kind = BannerKind.Warning); return@Column }
        if (m == null || m.rows.isEmpty()) { AmoEmpty(stringResource(R.string.amo_rep_empty), hint = stringResource(R.string.amo_rep_empty_hint)); return@Column }
        Column(Modifier.fillMaxWidth().testTag(TeamReportTags.TABLE).border(0.5.dp, look.rowRule)) {
            StdLine(m.zone, m.categories.map { c -> categoryTitle(c) + (m.units[c]?.let { " (" + com.aktcl.aron.core.ui.entryUnitLabel(it) + ")" } ?: "") }, look.card, true, h)
            m.rows.forEach { r -> StdLine(r.route, m.categories.map { m.cellText(r, it, l) }, look.card, false, h) }
            Box(Modifier.testTag(TeamReportTags.TOTAL)) { StdLine(stringResource(R.string.amo_team_total_row), m.categories.map { m.totalText(it, l) }, TableLavender, true, h) }
        }
    }
}


@Composable
private fun StdLine(first: String, cells: List<String>, fill: Color, bold: Boolean, h: androidx.compose.foundation.ScrollState) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().background(fill).border(0.5.dp, look.rowRule)) {
        Text(first, style = amoText(14, bold = bold, color = if (bold) look.purple else look.ink), modifier = Modifier.width(112.dp).padding(8.dp))
        Row(Modifier.weight(1f).horizontalScroll(h)) {
            cells.forEach { Text(it, style = amoText(14, bold = bold, color = if (bold) look.purple else look.ink, align = TextAlign.End), modifier = Modifier.width(104.dp).padding(8.dp)) }
        }
    }
}

// ---- sales till now (screenshot 72) ------------------------------------------------------------------------------------------

@Composable
private fun TillCardView(c: TillCard, onDetails: (() -> Unit)?) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(if (c.isZone) Color(0xFFDCE5E2) else look.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(c.heading, style = amoText(18, bold = true))
        Row(Modifier.fillMaxWidth()) {
            listOf(R.string.amo_team_till_category to 1.4f, R.string.amo_team_perf_target to 1.3f, R.string.amo_team_till_sales to 1.2f, R.string.amo_team_till_pct to 0.9f, R.string.amo_team_till_memo to 0.8f).forEachIndexed { i, (s, w) ->
                Text(stringResource(s), style = amoText(12, bold = true, color = look.purple, align = if (i == 0) TextAlign.Start else TextAlign.End), modifier = Modifier.weight(w))
            }
        }
        c.rows.forEach { r ->
            Row(Modifier.fillMaxWidth()) {
                Text(categoryTitle(r.category) + (r.unit?.let { " (" + com.aktcl.aron.core.ui.entryUnitLabel(it) + ")" } ?: ""), style = amoText(13), modifier = Modifier.weight(1.4f))
                listOf(r.target to 1.3f, r.sales to 1.2f, r.pct to 0.9f, r.memo to 0.8f).forEach { (t, w) -> Text(t, style = amoText(13, align = TextAlign.End), modifier = Modifier.weight(w)) }
            }
        }
        if (onDetails != null) Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onDetails).padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
            Text(stringResource(R.string.amo_team_perf_details) + " →", style = amoText(15, bold = true))
        }
    }
}

/** Sales till now (screenshot 72 left): the zone card then one card per route with Category, Target, Sales, % and Memo. */
@Composable
fun TillNowContent(cards: List<TillCard>, onDetails: (TillCard) -> Unit, stale: Boolean, failed: Boolean, refused: Boolean = false, modifier: Modifier = Modifier, memoPending: Boolean = false, truncated: Boolean = false, routeLevelUnavailable: Boolean = false) {
    AmoPage(modifier) {
        AmoFetchBanner(failed, stale, refused)
        if (cards.isEmpty()) AmoEmpty(stringResource(R.string.amo_rep_empty), hint = stringResource(R.string.amo_rep_empty_hint))
        if (truncated) AmoBanner(stringResource(R.string.amo_rep_truncated), kind = BannerKind.Warning)
        if (memoPending && cards.isNotEmpty()) AmoBanner(stringResource(R.string.amo_team_memo_pending))
        if (routeLevelUnavailable) Text(stringResource(R.string.amo_team_route_unavailable), style = amoText(14, color = LocalAronLook.current.inkMuted), modifier = Modifier.testTag(TeamPerfTags.ROUTE_NOTE))
        cards.forEach { c -> if (c.outOfReach) OutOfReachCard(c.heading, TeamPerfTags.OUT_OF_REACH + c.routeId) else TillCardView(c, { onDetails(c) }) }
    }
}

/** The route detail (screenshot 72 right): CPR and total memos, then the brand table (Brand, Target, Sales, %, Memo). */
@Composable
fun TillRouteDetailContent(
    cpr: String, totalMemo: String, brands: List<BrandLine>, modifier: Modifier = Modifier, stale: Boolean = false, failed: Boolean = false, refused: Boolean = false,
) {
    val look = LocalAronLook.current
    AmoPage(modifier) {
        AmoFetchBanner(failed, stale, refused) // the kept-copy / offline / refused note of its sibling cards
        val shape = RoundedCornerShape(14.dp)
        Row(Modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card).padding(14.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.amo_team_till_cpr), style = amoText(13, color = look.inkMuted)); Text(cpr, style = amoText(22, bold = true, color = look.purple)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.amo_team_till_total_memo), style = amoText(13, color = look.inkMuted)); Text(totalMemo, style = amoText(22, bold = true, color = look.purple)) }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
            val head = listOf(R.string.amo_team_till_brand, R.string.amo_team_perf_target, R.string.amo_team_till_sales, R.string.amo_team_till_pct, R.string.amo_team_till_memo)
            Row(Modifier.fillMaxWidth().background(TableLavender)) {
                head.forEachIndexed { i, s -> Text(stringResource(s), style = amoText(13, bold = true, color = look.purple, align = if (i == 0) TextAlign.Start else TextAlign.End), modifier = Modifier.weight(TillBrandWeights[i]).padding(6.dp)) }
            }
            if (brands.isEmpty()) Text(stringResource(R.string.amo_rep_no_brands), style = amoText(14, color = look.inkMuted), modifier = Modifier.padding(12.dp))
            brands.forEach { b ->
                Row(Modifier.fillMaxWidth().background(look.card).border(0.5.dp, look.rowRule)) {
                    listOf(b.brand + (b.unit?.let { " (" + com.aktcl.aron.core.ui.entryUnitLabel(it) + ")" } ?: ""), b.target, b.sales, b.pct, b.memo).forEachIndexed { i, t -> Text(t, style = amoText(13, align = if (i == 0) TextAlign.Start else TextAlign.End), modifier = Modifier.weight(TillBrandWeights[i]).padding(6.dp)) }
                }
            }
        }
    }
}

/** Column weights of the till-now brand table (Brand, Target, Sales, %, Memo): the percent column keeps "415.17%" on one line in Bangla digits (D-PAR-AMO-15). */
private val TillBrandWeights = listOf(1.35f, 1.15f, 1.1f, 1.3f, 0.85f)


/**
 * The two Reports tile pictures of screenshot 71, drawn in code (D-PAR-AMO-28; a trace of the colour picture was either dull or 40 KB): the donut over three bars on a red
 * base, and the cream sheet with a pie and a blue tick circle. Decoration; the caption names the report.
 */
@Composable
private fun ReportArt(std: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width; val h = size.height
        fun c(v: Long) = androidx.compose.ui.graphics.Color(v)
        val off = androidx.compose.ui.geometry.Offset(0f, 0f)
        if (std) {
            val orange = c(0xFFF28C28); val blue = c(0xFF3D7FE0); val yellow = c(0xFFF7C13A); val red = c(0xFFD9433B)
            val ring = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.17f)
            val box = androidx.compose.ui.geometry.Size(w * 0.5f, w * 0.5f); val tl = androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.02f)
            drawArc(orange, 140f, 130f, false, tl, box, style = ring); drawArc(blue, 270f, 110f, false, tl, box, style = ring); drawArc(yellow, 20f, 120f, false, tl, box, style = ring)
            val bw = w * 0.17f
            drawRect(orange, androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.70f), androidx.compose.ui.geometry.Size(bw, h * 0.14f))
            drawRect(blue, androidx.compose.ui.geometry.Offset(w * 0.40f, h * 0.60f), androidx.compose.ui.geometry.Size(bw, h * 0.24f))
            drawRect(yellow, androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.44f), androidx.compose.ui.geometry.Size(bw, h * 0.40f))
            drawRect(red, androidx.compose.ui.geometry.Offset(w * 0.04f, h * 0.86f), androidx.compose.ui.geometry.Size(w * 0.92f, h * 0.06f))
        } else {
            val paper = c(0xFFFBE7A8); val line = c(0xFF3A2A1A)
            drawRoundRect(paper, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.04f), androidx.compose.ui.geometry.Size(w * 0.58f, h * 0.82f), androidx.compose.ui.geometry.CornerRadius(w * 0.05f))
            for (i in 0..3) drawLine(line, androidx.compose.ui.geometry.Offset(w * 0.62f, h * (0.38f + i * 0.1f)), androidx.compose.ui.geometry.Offset(w * 0.84f, h * (0.38f + i * 0.1f)), strokeWidth = h * 0.025f)
            val pie = androidx.compose.ui.geometry.Size(w * 0.62f, w * 0.62f); val tl = androidx.compose.ui.geometry.Offset(w * 0.02f, h * 0.16f)
            drawArc(c(0xFFE0393E), 270f, 90f, true, tl, pie); drawArc(c(0xFFF7C13A), 150f, 120f, true, tl, pie); drawArc(c(0xFF3FA34D), 0f, 150f, true, tl, pie)
            drawArc(c(0xFFF7C13A), 150f, 120f, true, tl, pie)
            drawCircle(c(0xFF3DA9E8), radius = w * 0.17f, center = androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.74f))
            drawLine(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.geometry.Offset(w * 0.66f, h * 0.74f), androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.80f), strokeWidth = w * 0.045f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.80f), androidx.compose.ui.geometry.Offset(w * 0.83f, h * 0.68f), strokeWidth = w * 0.045f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
