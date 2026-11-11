package com.aktcl.aron.feature.amo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.entryUnitLabel
import com.aktcl.aron.rules.Band
import com.aktcl.aron.rules.Formats

object TeamPerfTags { const val ROUTE_NOTE = "team_perf_route_note"; const val OUT_OF_REACH = "team_perf_out_of_reach_"; const val CARD = "team_perf_card_"; const val DETAILS = "team_perf_details_"; const val CAT = "team_perf_cat_"; const val TAB = "team_perf_tab" }

/** What Team Performance shows: the zone card first, then one card per route. [detail] set means the brand table of that node is open. */
data class PerfCards(val zone: TeamTargetNode?, val routes: List<TeamTargetNode>, val outOfReach: List<RouteOutOfReach> = emptyList(), val routeLevelUnavailable: Boolean = false)

@Composable
private fun categoryName(code: String): String = when (code) {
    "cigarette" -> stringResource(R.string.amo_team_cat_cigarette); "bidi" -> stringResource(R.string.amo_team_cat_bidi)
    "lighter" -> stringResource(R.string.amo_team_cat_lighter); "match" -> stringResource(R.string.amo_team_cat_match); else -> code
}

@Composable
private fun bandColor(b: Band?): Color {
    val c = LocalAronColors.current
    return when (b) { Band.GREEN -> c.success; Band.AMBER -> c.warning; Band.RED -> c.danger; null -> c.textSecondary }
}

/** One category row of a card: name, "achievement/target", the coloured percent, and the bar. [onTap] filters the Details table. */
@Composable
private fun CategoryRow(r: TeamTargetRow, tab: PerfTab, selected: Boolean, onTap: (() -> Unit)?, tagBase: String) {
    val look = LocalAronLook.current
    val l = teamLocale()
    val band = TeamTargets.band(r, tab)
    val color = bandColor(band)
    val pct = TeamTargets.cardPct(r, tab)
    Column(
        Modifier.fillMaxWidth().let { if (onTap != null) it.clickable(role = Role.Button, onClick = onTap) else it }.testTag(tagBase + r.categoryCode).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(categoryName(r.categoryCode), style = amoText(17, bold = selected), modifier = Modifier.weight(1f))
            Text(TeamTargets.fraction(r, tab, l) + (TeamTargets.unitKey(r)?.let { " " + entryUnitLabel(it) } ?: ""), style = amoText(13, color = look.inkMuted), textAlign = TextAlign.End)
            Text(TeamTargets.cardPctText(r, tab, l), style = amoText(14, bold = true, color = color))
        }
        AmoBandBar((pct ?: 0L) / 100f, color)
    }
}

@Composable
private fun PerfCard(
    heading: String, rows: List<TeamTargetRow>, tab: PerfTab, tinted: Boolean, tag: String, selectedCategory: String?, onCategory: ((String) -> Unit)?, onDetails: (() -> Unit)?,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(if (tinted) ZONE_TINT else look.card).padding(16.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(heading, style = amoText(18, bold = true))
        if (rows.isEmpty()) Text(stringResource(R.string.amo_team_perf_card_empty), style = amoText(14, color = look.inkMuted))
        rows.forEach { r -> CategoryRow(r, tab, selectedCategory == r.categoryCode, onCategory?.let { f -> { f(r.categoryCode) } }, TeamPerfTags.CAT) }
        if (onDetails != null) Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onDetails).padding(top = 4.dp).testTag(TeamPerfTags.DETAILS + tag),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
        ) { Text(stringResource(R.string.amo_team_perf_details) + " →", style = amoText(15, bold = true)) }
    }
}

@Composable
internal fun OutOfReachCard(heading: String, tag: String) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card).padding(16.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(heading, style = amoText(18, bold = true))
        Text(stringResource(R.string.amo_team_route_out_of_reach), style = amoText(14, color = look.inkMuted))
    }
}

/** The tint of the zone card (screenshot 34, about #DCE5E2). */
private val ZONE_TINT = Color(0xFFDCE5E2)

/** Team Performance list (screenshot 34): the tabs, the tinted zone card, one card per route; each with the category bars and Details. */
@Composable
fun TeamPerformanceListContent(
    cards: PerfCards, tab: PerfTab, onTab: (PerfTab) -> Unit, onDetails: (TeamTargetNode) -> Unit, stale: Boolean, failed: Boolean, refused: Boolean = false, modifier: Modifier = Modifier,
) {
    AmoPage(modifier) {
        AmoFetchBanner(failed, stale, refused, offlineText = R.string.amo_perf_offline)
        AmoSegments(listOf(stringResource(R.string.amo_team_perf_monthly), stringResource(R.string.amo_team_perf_till_date)), if (tab == PerfTab.MONTHLY) 0 else 1, { onTab(if (it == 0) PerfTab.MONTHLY else PerfTab.TILL_DATE) }, Modifier.testTag(TeamPerfTags.TAB))
        if (cards.zone == null && cards.routes.isEmpty()) AmoEmpty(stringResource(R.string.amo_perf_empty), hint = stringResource(R.string.amo_perf_empty_hint))
        cards.zone?.let { z ->
            PerfCard(stringResource(R.string.amo_team_perf_zone, z.name.orEmpty()), TeamTargets.categoryCards(z.rows, z.periodDays), tab, true, TeamPerfTags.CARD + "zone", null, null) { onDetails(z) }
        }
        // the server has no route level (400): the zone card stands and one line says so, never an access problem
        if (cards.routeLevelUnavailable) Text(stringResource(R.string.amo_team_route_unavailable), style = amoText(14, color = LocalAronLook.current.inkMuted), modifier = Modifier.testTag(TeamPerfTags.ROUTE_NOTE))
        cards.routes.forEach { r ->
            PerfCard(stringResource(R.string.amo_team_perf_route, r.name.orEmpty()), TeamTargets.categoryCards(r.rows, r.periodDays), tab, false, TeamPerfTags.CARD + r.id, null, null) { onDetails(r) }
        }
        // a route outside the caller's reach (403) says so on its own row; the other routes carry on
        cards.outOfReach.forEach { r -> OutOfReachCard(stringResource(R.string.amo_team_perf_route, r.name.orEmpty()), TeamPerfTags.OUT_OF_REACH + r.routeId) }
    }
}

/** Details (screenshots 35, 36): the node's summary card (its categories are tappable filters) and the brand table. */
@Composable
fun TeamPerformanceDetailsContent(
    node: TeamTargetNode, tab: PerfTab, onTab: (PerfTab) -> Unit, category: String?, onCategory: (String) -> Unit, stale: Boolean, failed: Boolean, refused: Boolean = false, modifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    val l = teamLocale()
    val all = TeamTargets.categoryCards(node.rows, node.periodDays)
    val shown = if (category == null) all else all.filter { it.categoryCode == category }
    val items = TeamTargets.items(node.rows, category)
    AmoPage(modifier) {
        AmoFetchBanner(failed, stale, refused, offlineText = R.string.amo_perf_offline)
        AmoSegments(listOf(stringResource(R.string.amo_team_perf_monthly), stringResource(R.string.amo_team_perf_till_date)), if (tab == PerfTab.MONTHLY) 0 else 1, { onTab(if (it == 0) PerfTab.MONTHLY else PerfTab.TILL_DATE) }, Modifier.testTag(TeamPerfTags.TAB))
        PerfCard(
            stringResource(if (node.type == "route") R.string.amo_team_perf_route else R.string.amo_team_perf_zone, node.name.orEmpty()), shown, tab, node.type != "route",
            TeamPerfTags.CARD + node.id, category, onCategory, null,
        )
        // the table: lavender header, white bordered rows (screenshot 35)
        val head = listOf(stringResource(R.string.amo_team_perf_item), stringResource(R.string.amo_team_perf_target), stringResource(R.string.amo_team_perf_achieved), stringResource(R.string.amo_team_perf_remaining), "%")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
            PerfTableRow(head, TableLavender, true)
            if (items.isEmpty()) Text(stringResource(R.string.amo_team_perf_items_empty), style = amoText(14, color = look.inkMuted), modifier = Modifier.padding(12.dp))
            items.forEach { r ->
                PerfTableRow(
                    listOf(r.name + (TeamTargets.unitKey(r)?.let { " (" + entryUnitLabel(it) + ")" } ?: ""), TeamTargets.targetText(r, tab, l), TeamTargets.achievedText(r, l), TeamTargets.remainingText(r, tab, l), TeamTargets.detailsText(r, tab, l)),
                    look.card, false,
                )
            }
        }
    }
}

@Composable
private fun PerfTableRow(cells: List<String>, fill: Color, header: Boolean) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().background(fill).border(0.5.dp, look.rowRule)) {
        cells.forEachIndexed { i, t ->
            Text(
                t, style = amoText(if (header) 14 else 13, bold = header, color = if (header) look.purple else look.ink, align = if (i == 0) TextAlign.Start else TextAlign.End),
                modifier = Modifier.weight(if (i == 0) 1.45f else if (i == 4) 1.4f else 1.1f).padding(horizontal = 5.dp, vertical = 8.dp),
            )
        }
    }
}
