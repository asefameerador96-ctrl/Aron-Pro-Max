package com.aktcl.aron.feature.tso.targets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.aron.NightOutlineCard
import com.aktcl.aron.core.ui.aron.nightRoles
import com.aktcl.aron.core.ui.aron.nightText
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.TsoLoading
import com.aktcl.aron.feature.tso.TsoNightNote
import com.aktcl.aron.feature.tso.TsoSegmentedTabs
import com.aktcl.aron.feature.tso.uiLocale
import com.aktcl.aron.rules.Band
import com.aktcl.aron.rules.Bands

/** Test tags of Target Status and Target Details. */
object TargetTags {
    const val LIST = "tso_tgt_list"
    const val AS_OF = "tso_tgt_as_of"
    const val TABLE = "tso_tgt_table"
    const val SHOW_ALL = "tso_tgt_show_all"
    fun card(index: Int) = "tso_tgt_card_$index"
    fun details(index: Int) = "tso_tgt_details_$index"
    fun bar(category: String) = "tso_tgt_bar_$category"
    fun row(productId: Long) = "tso_tgt_row_$productId"
}

/** Monthly Target | Till Date Target (screenshots 39 to 42): the segmented tabs of both screens. */
@Composable
fun TargetTabs(tab: TargetTab, onTab: (TargetTab) -> Unit, modifier: Modifier = Modifier) {
    TsoSegmentedTabs(
        listOf(stringResource(R.string.tso_dash_tgt_monthly), stringResource(R.string.tso_dash_tgt_till_date)), tab.ordinal,
        { onTab(TargetTab.entries[it]) }, modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Target Status (screenshots 39 and 40, D-TSO-MAN-40): the tabs, then one card per node of [load] (the territory, then each zone),
 * each with the category bars (achieved/target, the card percent capped at 100 and coloured by [bands]) and "Details →" ([onDetails]
 * with the node's key). Reads happen when the screen opens and on pull-to-refresh ([onRefresh]), never on a timer; the "as of" line
 * names the server's stamp, or says the figures are a kept copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetStatusContent(
    load: CardLoad<TargetAnswer>?,
    tab: TargetTab,
    bands: Bands,
    refreshing: Boolean,
    onTab: (TargetTab) -> Unit,
    onDetails: (TargetNodeKey) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TargetTabs(tab, onTab)
        PullToRefreshBox(refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(TargetTags.LIST),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val v = load?.value
                when {
                    load == null -> TsoLoading(Modifier.heightIn(min = 240.dp))
                    v == null -> TsoNightNote(missing(load.failure))
                    else -> {
                        AsOfLine(v.asOf, load.kept)
                        val cards = v.cards
                        if (cards.all { it.rows.isEmpty() }) TsoNightNote(stringResource(R.string.tso_dash_tgt_empty))
                        cards.forEachIndexed { i, node ->
                            TargetNodeCard(
                                node.ref, TargetStatus.ordered(node.rows), tab, bands, TargetTags.card(i), tinted = i == 0,
                                onDetails = { onDetails(TargetNodeKey.of(v, node)) }, detailsTag = TargetTags.details(i),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Target Details (screenshots 41 and 42, D-TSO-MAN-40): the tabs (switching keeps the node and the filter), the node's card with its
 * category bars (each bar the sum of its item rows, R-F-01), and the item table: Item, Target, Achievement, Remaining and the percent
 * with 2 decimals, uncapped ("104.94%"). Tapping a bar filters the table to that category and the card to that bar (screenshot 41);
 * tapping it again, or "Show all categories", clears the filter ([onFilter] null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetDetailsContent(
    load: CardLoad<TargetAnswer>?,
    tab: TargetTab,
    bands: Bands,
    filter: String?,
    refreshing: Boolean,
    onTab: (TargetTab) -> Unit,
    onFilter: (String?) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TargetTabs(tab, onTab)
        PullToRefreshBox(refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(TargetTags.LIST),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val v = load?.value
                when {
                    load == null -> TsoLoading(Modifier.heightIn(min = 240.dp))
                    v == null -> TsoNightNote(missing(load.failure))
                    else -> {
                        AsOfLine(v.asOf, load.kept)
                        val node = v.node
                        val categories = TargetStatus.categoryCards(node.rows, node.periodDays)
                        val shown = if (filter == null) categories else categories.filter { it.categoryCode == filter }.ifEmpty { categories }
                        TargetNodeCard(
                            node.ref, shown, tab, bands, TargetTags.card(0), tinted = false, onDetails = null,
                            selected = filter, onCategory = { code -> onFilter(if (code == filter) null else code) },
                            onShowAll = if (filter != null) ({ onFilter(null) }) else null,
                        )
                        DetailsTable(TargetStatus.items(node.rows, filter), tab)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun missing(failure: TsoFailure): String = stringResource(
    when (failure) { TsoFailure.REFUSED -> R.string.tso_dash_card_refused; TsoFailure.OFFLINE -> R.string.tso_dash_card_offline; TsoFailure.NONE -> R.string.tso_dash_card_none },
)

@Composable
private fun AsOfLine(asOf: String, kept: Boolean) {
    val n = nightRoles()
    val clock = TsoDashboards.asOfClock(asOf)?.let { localizedDigits(it) } ?: return
    Text(
        if (kept) stringResource(R.string.tso_dash_kept_copy, clock) else stringResource(R.string.tso_dash_as_of, clock),
        Modifier.fillMaxWidth().testTag(TargetTags.AS_OF), style = nightText(13.sp, if (kept) n.warning else n.inkMuted, align = TextAlign.End),
    )
}

/** "Territory: Apsis", "Zone: Banani - Test" (screenshots 39 to 42); a node without a name shows its id. */
@Composable
fun targetNodeTitle(ref: TargetNodeRef): String {
    val name = ref.name ?: ref.id.toString()
    return when (ref.type) {
        "territory" -> stringResource(R.string.tso_dash_tgt_node_territory, name)
        "zone" -> stringResource(R.string.tso_dash_tgt_node_zone, name)
        "house" -> stringResource(R.string.tso_dash_tgt_node_house, name)
        "division" -> stringResource(R.string.tso_dash_tgt_node_division, name)
        "wing" -> stringResource(R.string.tso_dash_tgt_node_wing, name)
        "route" -> stringResource(R.string.tso_dash_tgt_node_route, name)
        "national" -> stringResource(R.string.tso_dash_tgt_node_national)
        else -> name
    }
}

/** A category's label: the localized name of the four card categories, else the server's name of the row. */
@Composable
fun targetCategoryLabel(r: TargetRow): String = when (r.categoryCode) {
    "cigarette" -> stringResource(R.string.tso_cat_cigarette)
    "bidi" -> stringResource(R.string.tso_cat_bidi)
    "lighter" -> stringResource(R.string.tso_cat_lighter)
    "match" -> stringResource(R.string.tso_cat_match)
    else -> r.name.ifBlank { r.categoryCode }
}

/**
 * One node card (screenshots 39 to 42): the navy outline card (the first card a little teal, as the territory card of the
 * screenshots), the bold "Territory: <name>", one bar per category of [rows], and "Details →" when [onDetails] is set. With
 * [onCategory] each bar is a toggle button that filters the Details table ([selected] is the filtered category).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TargetNodeCard(
    ref: TargetNodeRef,
    rows: List<TargetRow>,
    tab: TargetTab,
    bands: Bands,
    tag: String,
    tinted: Boolean,
    onDetails: (() -> Unit)?,
    detailsTag: String? = null,
    selected: String? = null,
    onCategory: ((String) -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
) {
    val n = nightRoles()
    val loc = uiLocale()
    val fill = if (tinted && !n.solid) n.outlineEdge.copy(alpha = 0.22f).compositeOver(n.outlineCard) else null
    val title = targetNodeTitle(ref)
    NightOutlineCard(Modifier.testTag(tag), fill = fill, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, Modifier.fillMaxWidth().semantics { heading() }, style = nightText(19.sp, n.ink, bold = true), maxLines = 3)
        rows.forEach { r ->
            val label = targetCategoryLabel(r)
            val fraction = TargetStatus.fraction(r, tab, loc)
            val pct = TargetStatus.cardPctText(r, tab, loc)
            val band = TargetStatus.band(r, tab, bands)
            val ink = when (band) { Band.RED -> n.danger; Band.AMBER -> n.warning; Band.GREEN -> n.success; null -> n.inkMuted }
            val described = stringResource(R.string.tso_dash_tgt_bar_desc, label, fraction, pct)
            val isOn = selected == r.categoryCode
            val clickLabel = if (isOn) stringResource(R.string.tso_dash_tgt_show_all) else stringResource(R.string.tso_dash_tgt_filter, label)
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .then(if (onCategory != null) Modifier.clickable(role = Role.Button, onClickLabel = clickLabel) { onCategory(r.categoryCode) } else Modifier)
                    .semantics(mergeDescendants = true) { contentDescription = described; if (onCategory != null) this.selected = isOn }
                    .testTag(TargetTags.bar(r.categoryCode))
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // one line when it fits; at 200 percent the figures move under the label instead of squeezing it
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, Modifier.padding(end = 8.dp), style = nightText(17.sp, n.ink, semibold = true))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fraction, style = nightText(14.sp, n.inkMuted), maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Text(pct, style = nightText(16.sp, ink, bold = true), maxLines = 1)
                    }
                }
                BandBar(TargetStatus.cardPct(r, tab), band)
            }
        }
        if (onShowAll != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onShowAll).testTag(TargetTags.SHOW_ALL),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) { Text(stringResource(R.string.tso_dash_tgt_show_all), style = nightText(15.sp, n.teal, semibold = true)) }
        }
        if (onDetails != null) {
            val label = stringResource(R.string.tso_dash_tgt_details_of, title)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onDetails)
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .then(if (detailsTag != null) Modifier.testTag(detailsTag) else Modifier),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tso_dash_tgt_details), style = nightText(19.sp, n.ink, bold = true))
                Spacer(Modifier.width(10.dp))
                Icon(AronIcons.ArrowForward, contentDescription = null, tint = n.ink, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** The light track and the band fill to [pct] (0..100, the capped card percent); a red, amber or green stub even at 0 (screenshot 39). */
@Composable
private fun BandBar(pct: Long?, band: Band?) {
    val n = nightRoles()
    val fill: Color? = when (band) { Band.RED -> n.bandRed; Band.AMBER -> n.bandAmber; Band.GREEN -> n.bandGreen; null -> null }
    val part = (pct ?: 0L).coerceIn(0L, 100L).toInt() / 100f
    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(n.barTrack)) {
        if (fill != null) Box(Modifier.widthIn(min = 8.dp).fillMaxHeight().fillMaxWidth(part).clip(RoundedCornerShape(50)).background(fill))
    }
}

/**
 * The item table of Target Details (screenshots 41 and 42): white rules, Item at the start (wraps), the figures centred and the
 * percent at the end. Every figure from the integers: the tab's target, A, Targets.remaining and Targets.detailsBp. A figure shrinks to
 * its cell on one line instead of breaking; the header words hyphenate. From 150 percent font scale the five columns no longer fit a
 * 360 dp phone, so each item becomes a block (its name, then Target, Achievement, Remaining and % as label and figure lines).
 */
@Composable
private fun DetailsTable(items: List<TargetRow>, tab: TargetTab) {
    val n = nightRoles()
    val loc = uiLocale()
    val rule = n.ink.copy(alpha = if (n.solid) 1f else 0.8f)
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val weights = listOf(1.45f, 1f, 1.25f, 1.15f, 1.2f)
    val head = listOf(R.string.tso_dash_tgt_item, R.string.tso_dash_tgt_target, R.string.tso_dash_tgt_achievement, R.string.tso_dash_tgt_remaining, R.string.tso_dash_tgt_pct).map { stringResource(it) }
    Column(Modifier.fillMaxWidth().border(1.dp, rule).testTag(TargetTags.TABLE)) {
        if (!stacked) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                head.forEachIndexed { i, h ->
                    Text(
                        h, Modifier.weight(weights[i]).padding(horizontal = 2.dp),
                        style = nightText(14.sp, n.inkMuted, align = if (i == 0) TextAlign.Start else if (i == 4) TextAlign.End else TextAlign.Center)
                            .copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
        }
        if (items.isEmpty()) {
            Text(stringResource(R.string.tso_dash_tgt_items_empty), Modifier.fillMaxWidth().padding(16.dp), style = nightText(15.sp, n.inkMuted, align = TextAlign.Center))
        }
        items.forEachIndexed { idx, r ->
            if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(rule))
            val cells = listOf(
                TargetStatus.qty(TargetStatus.target(r, tab), loc), TargetStatus.qty(r.achieved, loc),
                TargetStatus.qty(TargetStatus.remaining(r, tab), loc), TargetStatus.detailsText(r, tab, loc),
            )
            val described = stringResource(R.string.tso_dash_tgt_row_desc, r.name, cells[0], cells[1], cells[2], cells[3])
            val rowModifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) { contentDescription = described }
                .testTag(TargetTags.row(r.productId)).padding(horizontal = 12.dp, vertical = 12.dp)
            if (stacked) {
                Column(rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.name, style = nightText(16.sp, n.ink, bold = true))
                    cells.forEachIndexed { i, c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(head[i + 1], Modifier.weight(1f), style = nightText(14.sp, n.inkMuted))
                            Text(c, style = nightText(16.sp, n.ink), maxLines = 1)
                        }
                    }
                }
            } else {
                Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, Modifier.weight(weights[0]).padding(horizontal = 2.dp), style = nightText(16.sp, n.ink))
                    cells.forEachIndexed { i, c ->
                        Text(
                            c, Modifier.weight(weights[i + 1]).padding(horizontal = 2.dp), maxLines = 1, softWrap = false,
                            style = nightText(16.sp, n.ink, align = if (i == 3) TextAlign.End else TextAlign.Center),
                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
                        )
                    }
                }
            }
        }
    }
}
