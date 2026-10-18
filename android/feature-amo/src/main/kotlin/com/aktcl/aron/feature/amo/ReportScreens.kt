package com.aktcl.aron.feature.amo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private fun pos(i: Int, n: Int) = if (n == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == n - 1) RowPosition.Last else RowPosition.Middle

@Composable
private fun Notes(stale: Boolean, failed: Boolean, truncated: Boolean) {
    if (failed) AronBanner(stringResource(R.string.amo_task_list_offline), kind = BannerKind.Warning)
    else if (stale) AronBanner(stringResource(R.string.amo_task_list_kept), kind = BannerKind.Info)
    if (truncated) AronBanner(stringResource(R.string.amo_rep_truncated), kind = BannerKind.Info)
}

@Composable
fun ReportMenuContent(onStd: () -> Unit, onSummary: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = AronTokens.Space.Screen)) {
        AronListRow(stringResource(R.string.amo_rep_std), onClick = onStd, position = RowPosition.First, divider = true, flat = true)
        AronListRow(stringResource(R.string.amo_rep_summary), onClick = onSummary, position = RowPosition.Last, flat = true)
    }
}

private fun pctText(v: Double?): String? = LiveDashboard.pct(v)?.let { "$it %" }

/** One cell of a report table by its column type; a null is a dash. */
@Composable
private fun cell(v: JsonPrimitive?, type: String, money: (Long) -> String): String {
    if (v == null || v is JsonNull) return stringResource(R.string.amo_live_dash)
    return when (type) {
        "mtk" -> v.longOrNull?.let(money) ?: v.content
        "integer" -> v.longOrNull?.let { localizedNumber(it) } ?: v.content
        "pct" -> v.doubleOrNull?.let { pctText(it)?.let { localizedDigits(it) } } ?: v.content
        "decimal" -> v.doubleOrNull?.let { localizedDigits(String.format(java.util.Locale.ROOT, "%.2f", it)) } ?: v.content
        "bool" -> v.content
        else -> localizedDigits(v.contentOrNull.orEmpty())
    }
}


/**
 * F-AMO-032 STD Memo Report: the range (first of the month to yesterday), the per-route totals, then the memos in a table whose first
 * column stays put while the rest scrolls sideways. A long report shows its first pages and says so.
 */
@Composable
fun StdMemoReportContent(
    range: Pair<String, String>, data: ReportData?, byRoute: List<RouteStd>, bangla: Boolean, money: (Long) -> String,
    stale: Boolean, failed: Boolean, modifier: Modifier = Modifier,
    /** True when [byRoute] comes from the server's route totals (`route-memo`, any range); null: exact only if the memo list is complete. */
    exactTotals: Boolean? = null,
    /** The per-route STD by category (`route-std`), shown under its route; empty while it is missing or too long to be true. */
    categories: List<RouteCategory> = emptyList(),
) {
    val totalsShown = exactTotals ?: (data?.truncated != true)
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = AronTokens.Space.Screen)) {
            Notes(stale, failed, data?.truncated == true)
            AronListRow(stringResource(R.string.amo_rep_range, localizedDigits(range.first), localizedDigits(range.second)), flat = true)
        }
        if (data == null || data.rows.isEmpty()) {
            AronEmptyState(stringResource(R.string.amo_rep_empty), Modifier.fillMaxSize(), hint = stringResource(R.string.amo_rep_empty_hint))
            return@Column
        }
        val cols = Reports.STD_COLUMNS.mapNotNull { k -> data.columns.firstOrNull { it.key == k } }
        val h = rememberScrollState()
        val c = LocalAronColors.current
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = AronTokens.Space.Screen)) {
                    // The route totals are only true for a complete report: a truncated one shows the memos below and its note instead.
                    if (!totalsShown) AronBanner(stringResource(R.string.amo_rep_totals_hidden), kind = BannerKind.Info)
                    else AronSectionLabel(stringResource(R.string.amo_rep_by_route))
                    if (totalsShown) byRoute.forEachIndexed { i, r ->
                        AronListRow(
                            r.code.ifBlank { stringResource(R.string.amo_live_dash) }, subtitle = stringResource(R.string.amo_rep_route_line, localizedNumber(r.memos.toLong()), money(r.grossMtk)),
                            trailing = money(r.netMtk), position = pos(i, byRoute.size), divider = i < byRoute.lastIndex, flat = true,
                        )
                    }
                    val cats = categories.groupBy { it.route }
                    if (totalsShown && cats.isNotEmpty()) {
                        AronSectionLabel(stringResource(R.string.amo_rep_by_category))
                        cats.entries.forEach { (route, list) ->
                            list.forEachIndexed { i, c ->
                                AronListRow(
                                    "${route.ifBlank { stringResource(R.string.amo_live_dash) }} · ${c.category.ifBlank { stringResource(R.string.amo_live_dash) }}",
                                    subtitle = c.qty?.let { q -> stringResource(R.string.amo_rep_cat_qty, localizedNumber(q), reportUnit(c.unit)) },
                                    trailing = money(c.grossMtk), position = pos(i, list.size), divider = i < list.lastIndex, flat = true,
                                )
                            }
                        }
                    }
                    AronSectionLabel(stringResource(R.string.amo_rep_memos))
                }
            }
            stickyHeader(cols, bangla, h)
            items(data.rows, key = { (it["memo_no"] as? JsonPrimitive)?.contentOrNull ?: it.hashCode().toString() }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen)) {
                    cols.firstOrNull()?.let { Text(cell(row[it.key], it.type, money), Modifier.width(FIRST_W), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    Row(Modifier.weight(1f).horizontalScroll(h), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        cols.drop(1).forEach { Text(cell(row[it.key], it.type, money), Modifier.width(CELL_W), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

private val FIRST_W = 112.dp
private val CELL_W = 104.dp

private fun androidx.compose.foundation.lazy.LazyListScope.stickyHeader(cols: List<ReportCol>, bangla: Boolean, h: androidx.compose.foundation.ScrollState) {
    stickyHeader {
        val c = LocalAronColors.current
        Row(Modifier.fillMaxWidth().background(LocalAronColors.current.surfaceSolid).padding(horizontal = AronTokens.Space.Screen)) {
            cols.firstOrNull()?.let { Text(if (bangla) it.labelBn ?: it.labelEn else it.labelEn, Modifier.width(FIRST_W), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c.textSecondary) }
            Row(Modifier.weight(1f).horizontalScroll(h), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cols.drop(1).forEach { Text(if (bangla) it.labelBn ?: it.labelEn else it.labelEn, Modifier.width(CELL_W), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c.textSecondary) }
            }
        }
    }
}

/** F-AMO-033: a card per route (CPR, memos, net); a zero target shows a dash. */
@Composable
fun SalesSummaryContent(cards: List<RouteCard>, money: (Long) -> String, stale: Boolean, failed: Boolean, onOpen: (RouteCard) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        Notes(stale, failed, false)
        if (cards.isEmpty()) AronEmptyState(stringResource(R.string.amo_rep_empty), Modifier.fillMaxWidth(), hint = stringResource(R.string.amo_rep_empty_hint))
        cards.forEachIndexed { i, r ->
            AronListRow(
                listOf(r.code, r.name).filter { it.isNotBlank() }.joinToString(" - "),
                subtitle = stringResource(R.string.amo_rep_cpr, pctText(r.cprPct)?.let { localizedDigits(it) } ?: stringResource(R.string.amo_live_dash)) + stringResource(R.string.amo_verify_sep) + stringResource(R.string.amo_rep_memos_n, localizedNumber(r.memos)),
                trailing = money(r.netMtk), onClick = { onOpen(r) }, position = pos(i, cards.size), divider = i < cards.lastIndex, flat = true,
            )
        }
    }
}

/** One route: CPR, total memos and the brand table (memos with the brand, BSR; a zero base shows a dash). */
@Composable
fun SalesSummaryRouteContent(card: RouteCard, money: (Long) -> String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        val dash = stringResource(R.string.amo_live_dash)
        AronListRow(listOf(card.code, card.name).filter { it.isNotBlank() }.joinToString(" - "), flat = true)
        val head = listOf(
            stringResource(R.string.amo_rep_target) to localizedNumber(card.targetOutlets), stringResource(R.string.amo_rep_calls) to localizedNumber(card.successfulCalls),
            stringResource(R.string.amo_rep_cpr_label) to (pctText(card.cprPct)?.let { localizedDigits(it) } ?: dash), stringResource(R.string.amo_rep_memos_label) to localizedNumber(card.memos), stringResource(R.string.amo_rep_net) to money(card.netMtk),
        )
        head.forEachIndexed { i, (k, v) -> AronListRow(v, subtitle = k, position = pos(i, head.size), divider = i < head.lastIndex, flat = true) }
        AronSectionLabel(stringResource(R.string.amo_rep_brands))
        if (card.brands.isEmpty()) Text(stringResource(R.string.amo_rep_no_brands), style = MaterialTheme.typography.bodyMedium, color = LocalAronColors.current.textSecondary)
        card.brands.forEachIndexed { i, b ->
            AronListRow(
                b.name, subtitle = stringResource(R.string.amo_rep_memos_n, localizedNumber(b.memos)), trailing = pctText(b.bsrPct)?.let { localizedDigits(it) } ?: dash,
                position = pos(i, card.brands.size), divider = i < card.brands.lastIndex, flat = true,
            )
        }
    }
}

@Composable
private fun reportUnit(unit: String?) = when (unit) {
    "stick" -> stringResource(R.string.amo_unit_stick); "dozen" -> stringResource(R.string.amo_unit_dozen); "piece" -> stringResource(R.string.amo_unit_piece); else -> unit.orEmpty()
}
