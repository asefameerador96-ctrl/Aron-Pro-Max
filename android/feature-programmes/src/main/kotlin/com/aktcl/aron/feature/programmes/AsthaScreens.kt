package com.aktcl.aron.feature.programmes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronChoiceChip
import com.aktcl.aron.core.ui.aron.AronDropdown
import com.aktcl.aron.core.ui.aron.AronTextButton
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.rules.UiLocale
import com.aktcl.aron.core.ui.R as CoreR

object AsthaTags {
    const val UNIT = "astha_unit"
    const val AWAITING = "astha_awaiting"
    const val MENU_TARGET = "astha_menu_target"
    const val MENU_GIFT = "astha_menu_gift"
    const val TAB_ROUTE = "astha_tab_route"
    const val TAB_SHOP = "astha_tab_shop"
    const val YEAR = "astha_year"
    const val QUARTER = "astha_quarter"
    const val EMPTY = "astha_empty"
    const val TABLE = "astha_table"
    const val AS_OF = "astha_as_of"
    const val SHOP_BACK = "astha_shop_back"
    const val SHOP_TITLE = "astha_shop_title"
    const val SEARCH = "astha_search"
    fun shop(id: Long) = "astha_shop_$id"
    const val TRUNCATED = "astha_truncated"
    const val MONTH_LOADING = "astha_month_loading"
    const val MONTH_RETRY = "astha_month_retry"
    const val SUB_STD = "astha_sub_std"
    const val SUB_MEMO = "astha_sub_memo"
    fun month(m: String) = "astha_month_$m"
    fun row(brandId: Long?) = "astha_row_${brandId ?: "all"}"
}

/** The Astha menu (manual 48, left): two tiles, Target and Gift selection. A tile whose programme is not offered is left out, never greyed. */
@Composable
fun AsthaMenuScreen(onTarget: () -> Unit, onGift: (() -> Unit)?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxSize().background(look.page).padding(12.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        AronTile(stringResource(R.string.pg_astha_tile_target), painterResource(CoreR.drawable.aron_art_astha), onTarget, Modifier.weight(1f).testTag(AsthaTags.MENU_TARGET), menu = true, labelColor = look.tileLabelOutlet)
        if (onGift != null) {
            AronTile(stringResource(R.string.pg_astha_tile_gift), painterResource(CoreR.drawable.aron_art_loyalty_point), onGift, Modifier.weight(1f).testTag(AsthaTags.MENU_GIFT), menu = true, labelColor = look.tileLabelOutlet)
        } else {
            Box(Modifier.weight(1f))
        }
    }
    }
}

/** A starting selection of the target page (restores a screen, seeds a golden); every member optional. */
data class AsthaSelection(val year: Int? = null, val quarter: Int? = null, val month: String? = null, val memoTab: Boolean = false)

/**
 * The Astha target page (manual 48 right, 49, 50). [data] null means no programme is active (the empty state, never a crash). The year,
 * quarter, month and sub-tab survive rotation and process death. The month rows come from the downloaded bundle only; a month the phone
 * does not hold shows "no data found" (server request N-1 asks for the whole quarter). [shops] feeds the second tab, "shop-wise info" (manual 51): a searchable list of the Astha outlets, and a tap opens that outlet's
 * own target table; without [shops] the tab is not drawn.
 */
@Composable
fun AsthaTargetScreen(
    data: AsthaData?, locale: UiLocale, currentYear: Int, modifier: Modifier = Modifier,
    shops: List<ShopInfo>? = null, initial: AsthaSelection = AsthaSelection(), initialShop: Long? = null, startOnShops: Boolean = false,
    /** The Dhaka month of today (`YYYY-MM`): the page opens on it (year, quarter and month already chosen) so it shows data at once; null keeps the empty start. */
    currentMonth: String? = null,
    /** Memos sent but not yet answered when the figures were fetched: the figures may already hold them, so the page says they await confirmation. */
    awaiting: Int = 0,
    /** The user's own Wing, Division, Territory and Zone for the shop card (bundle `user.geography`); null shows a dash until the server sends it. */
    geography: Geography? = null,
    /** The read of each quarter (`2026-Q4`) in progress or failed; a quarter not listed has no read in progress. */
    quarterState: Map<String, QuarterFetch> = emptyMap(),
    /** Asks the host to read a quarter online (`2026-Q4`) when the page shows one with months the phone does not hold; null (previews, offline-only callers) never asks. */
    onQuarter: ((String) -> Unit)? = null,
) {
    val look = LocalAronLook.current
    var tab by rememberSaveable { mutableStateOf(if (initialShop != null || startOnShops) 1 else 0) }
    var shop by rememberSaveable { mutableStateOf(initialShop) }
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (awaiting > 0) Text(stringResource(R.string.pg_awaiting_memos, com.aktcl.aron.rules.Formats.integer(awaiting.toLong(), locale)), Modifier.testTag(AsthaTags.AWAITING), style = TextStyle(fontSize = 14.sp, color = look.purple, fontWeight = FontWeight.Medium))
        if (shops != null) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(look.rowAlt).padding(3.dp)) {
                SegTab(stringResource(R.string.pg_tab_route), tab == 0, { tab = 0 }, Modifier.weight(1f).testTag(AsthaTags.TAB_ROUTE))
                SegTab(stringResource(R.string.pg_tab_shop), tab == 1, { tab = 1 }, Modifier.weight(1f).testTag(AsthaTags.TAB_SHOP))
            }
        }
        if (tab == 1 && shops != null) {
            val chosen = shop?.let { id -> shops.firstOrNull { it.outletId == id } }
            if (chosen == null) ShopList(shops, query, { query = it }, { shop = it }, locale, geography)
            else {
                AronTextButton(stringResource(R.string.pg_all_shops), { shop = null }, Modifier.testTag(AsthaTags.SHOP_BACK))
                Text(chosen.nameLine, style = label(look.purple), modifier = Modifier.testTag(AsthaTags.SHOP_TITLE))
                TargetBody(data, locale, currentYear, chosen.outletId, startAt(initial, currentMonth), quarterState, onQuarter)
            }
        } else TargetBody(data, locale, currentYear, null, startAt(initial, currentMonth), quarterState, onQuarter)
    }
}

private fun startAt(initial: AsthaSelection, currentMonth: String?): AsthaSelection =
    if (initial != AsthaSelection() || currentMonth == null || !Regex("^\\d{4}-(0[1-9]|1[0-2])$").matches(currentMonth)) initial
    else AsthaSelection(currentMonth.take(4).toInt(), AsthaModel.quarterOf(currentMonth), currentMonth)

/** The pickers, the month chips, the sub-tabs and the table of the route (outletId null) or of one outlet; its own state is kept per outlet. */
@Composable
private fun ColumnScope.TargetBody(data: AsthaData?, locale: UiLocale, currentYear: Int, outletId: Long?, initial: AsthaSelection, quarterState: Map<String, QuarterFetch>, onQuarter: ((String) -> Unit)?) {
    val look = LocalAronLook.current
    // Latin in both languages, as the manual shows them (screens 49 and 50); kept in resources like every visible text
    val monthNames = androidx.compose.ui.res.stringArrayResource(R.array.pg_months_short)
    val quarterNames = androidx.compose.ui.res.stringArrayResource(R.array.pg_quarters)
    var year by rememberSaveable(outletId) { mutableStateOf(initial.year) }
    var quarter by rememberSaveable(outletId) { mutableStateOf(initial.quarter) }
    var month by rememberSaveable(outletId) { mutableStateOf(initial.month) }
    var memoTab by rememberSaveable(outletId) { mutableStateOf(initial.memoTab) }
        if (data == null) { EmptyNote(stringResource(R.string.pg_no_programme)); return }
        // this year and four more, plus any earlier year the phone holds rows for
        val years = ((currentYear..currentYear + 4).toList() + data.rows.map { it.month.take(4).toInt() }).distinct().sorted()
        val big = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
        val yearPicker: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pg_pick_year), style = label(look.ink))
                AronDropdown(years, year, { year = it; quarter = null; month = null }, { it.toString() }, Modifier.testTag(AsthaTags.YEAR), placeholder = stringResource(R.string.pg_year))
            }
        }
        val quarterPicker: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pg_pick_quarter), style = label(look.ink))
                AronDropdown((1..4).toList(), quarter, { quarter = it; month = null }, { quarterNames[it - 1] }, Modifier.testTag(AsthaTags.QUARTER), placeholder = stringResource(R.string.pg_quarter), enabled = year != null)
            }
        }
        // each picker has its own weighted column: AronDropdown fills the width it is given, so a bare pair would let the first take the row
        if (big) { yearPicker(Modifier.fillMaxWidth()); quarterPicker(Modifier.fillMaxWidth()) }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { yearPicker(Modifier.weight(1f)); quarterPicker(Modifier.weight(1f)) }
        val y = year; val q = quarter
        if (y == null || q == null) { EmptyNote(stringResource(R.string.pg_no_data)); return }
        val months = AsthaModel.monthsOfQuarter(y, q)
        val quarterKey = "%04d-Q%d".format(y, q)
        // a quarter with a month the phone holds nothing of is read online when there is a connection (and kept); offline the page stays as the phone has it
        val lacking = data.missingMonths(months).isNotEmpty() || months.any { data.cutShort(it) }
        androidx.compose.runtime.LaunchedEffect(quarterKey, lacking) { if (lacking) onQuarter?.invoke(quarterKey) }
        Text(stringResource(R.string.pg_pick_month), style = label(look.ink))
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            months.forEach { m -> AronChoiceChip(monthNames[m.substring(5, 7).toInt() - 1], month == m, { month = m }, Modifier.testTag(AsthaTags.month(m))) }
        }
        val m = month
        if (m == null) return
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(look.rowAlt).padding(3.dp)) {
            SegTab(stringResource(R.string.pg_sub_std), !memoTab, { memoTab = false }, Modifier.weight(1f).testTag(AsthaTags.SUB_STD))
            SegTab(stringResource(R.string.pg_sub_memo), memoTab, { memoTab = true }, Modifier.weight(1f).testTag(AsthaTags.SUB_MEMO))
        }
        val brandRows = data.brandRows(m, outletId, locale)
        val allBrand = data.allBrandRow(m, outletId)
        if (brandRows.isEmpty() && allBrand == null) {
            // a month the phone never downloaded is not zero: say so. A month the server was asked about and holds nothing for is "no data"; a read in progress or failed says so.
            val st = quarterState[quarterKey]
            when {
                m in data.fetchedMonths || data.rows.any { it.month == m } -> EmptyNote(stringResource(R.string.pg_no_data))
                st == QuarterFetch.LOADING -> EmptyNote(stringResource(R.string.pg_month_loading), AsthaTags.MONTH_LOADING)
                st == QuarterFetch.FAILED -> {
                    EmptyNote(stringResource(R.string.pg_month_failed))
                    AronTextButton(stringResource(R.string.pg_retry), { onQuarter?.invoke(quarterKey) }, Modifier.testTag(AsthaTags.MONTH_RETRY))
                }
                else -> EmptyNote(stringResource(R.string.pg_not_downloaded))
            }
            return
        }
        if (big) StackedCards(data, brandRows, allBrand, memoTab, locale)
        else if (!memoTab) StdTable(data, brandRows, locale) else MemoTable(allBrand, locale)
        data.asOfOf(m)?.let { Text(stringResource(R.string.pg_as_of, com.aktcl.aron.rules.Formats.digits(DhakaTime.dateOf(it) ?: "—", locale)), Modifier.testTag(AsthaTags.AS_OF), style = small(look.inkMuted)) }
        if (data.cutShort(m)) Text(stringResource(R.string.pg_truncated), Modifier.testTag(AsthaTags.TRUNCATED), style = small(look.inkMuted))

}

@Composable private fun EmptyNote(text: String, tag: String = AsthaTags.EMPTY) {
    val look = LocalAronLook.current
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag(tag), contentAlignment = Alignment.Center) { Text(text, style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center)) }
}

@Composable private fun SegTab(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val look = LocalAronLook.current
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) look.red else look.rowAlt)
            .semantics { role = Role.Tab; this.selected = selected }.clickable(onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (selected) androidx.compose.ui.graphics.Color.White else look.ink, textAlign = TextAlign.Center)) }
}

private fun label(c: androidx.compose.ui.graphics.Color) = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c)
private fun small(c: androidx.compose.ui.graphics.Color) = TextStyle(fontSize = 13.sp, color = c)

private val Weights = listOf(1.7f, 1.7f, 1.7f, 1.7f, 1.6f)

/** The STD target table (manual 50 left): per brand, the figure on top is the volume (STD) and below it the value in taka; percent likewise. One line per pack size under the achieved volume. */
@Composable
private fun StdTable(data: AsthaData, rows: List<AsthaRow>, locale: UiLocale) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth().testTag(AsthaTags.TABLE).clip(RoundedCornerShape(10.dp)).background(look.card)) {
        HeaderRow(listOf(R.string.pg_col_brand, R.string.pg_col_target, R.string.pg_col_achieved, R.string.pg_col_remaining, R.string.pg_col_pct), twoLine = true)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().testTag(AsthaTags.row(r.brandId)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(Weights[0])) {
                    Text(data.brandName(r.brandId, locale), style = TextStyle(fontSize = 16.sp, color = look.purple, fontWeight = FontWeight.Medium))
                    // the unit of the volume figures of this row (the base unit the target and achievement are stored in), so a column never mixes sticks, pieces and dozens unmarked
                    data.baseUnitKey(r.brandId)?.let { Text(com.aktcl.aron.core.ui.entryUnitLabel(it), Modifier.testTag(AsthaTags.UNIT), style = small(look.inkMuted)) }
                }
                Cell(AsthaModel.stdText(r.stdTargetMilli, locale), AsthaModel.valueText(r.valueTargetMtk, locale), Modifier.weight(Weights[1]))
                Cell(AsthaModel.stdText(r.stdAchievedMilli, locale), AsthaModel.valueText(r.valueAchievedMtk, locale), Modifier.weight(Weights[2]))
                Cell(AsthaModel.stdText(AsthaModel.remaining(r.stdTargetMilli, r.stdAchievedMilli), locale), AsthaModel.valueText(AsthaModel.remaining(r.valueTargetMtk, r.valueAchievedMtk), locale), Modifier.weight(Weights[3]))
                Cell(AsthaModel.pctText(r.stdAchievedMilli, r.stdTargetMilli, locale), AsthaModel.pctText(r.valueAchievedMtk, r.valueTargetMtk, locale), Modifier.weight(Weights[4]))
            }
            r.byPack.forEach { p ->
                Text(packText(data, r.brandId, p, locale), Modifier.padding(horizontal = 10.dp, vertical = 2.dp), style = small(look.inkMuted))
            }
        }
    }
}

/** The memo target table (manual 50 right): one All Brand row, memos as whole counts. */
@Composable
private fun MemoTable(row: AsthaRow?, locale: UiLocale) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth().testTag(AsthaTags.TABLE).clip(RoundedCornerShape(10.dp)).background(look.card)) {
        HeaderRow(listOf(R.string.pg_col_brand, R.string.pg_col_target, R.string.pg_col_achieved, R.string.pg_col_remaining, R.string.pg_col_pct), twoLine = false)
        if (row == null) { EmptyNote(stringResource(R.string.pg_no_data)); return@Column }
        Row(Modifier.fillMaxWidth().testTag(AsthaTags.row(null)).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pg_all_brand), Modifier.weight(Weights[0]), style = TextStyle(fontSize = 16.sp, color = look.purple, fontWeight = FontWeight.Medium))
            val t = TextStyle(fontSize = 17.sp, color = look.ink, textAlign = TextAlign.Center)
            Text(AsthaModel.countText(row.memoTarget, locale), Modifier.weight(Weights[1]), style = t)
            Text(AsthaModel.countText(row.memoAchieved, locale), Modifier.weight(Weights[2]), style = t)
            Text(AsthaModel.countText(AsthaModel.remaining(row.memoTarget, row.memoAchieved), locale), Modifier.weight(Weights[3]), style = t)
            Text(AsthaModel.pctText(row.memoAchieved, row.memoTarget, locale), Modifier.weight(Weights[4]), style = t)
        }
    }
}

@Composable
private fun HeaderRow(cols: List<Int>, twoLine: Boolean) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().background(look.red.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        cols.forEachIndexed { i, c ->
            Column(Modifier.weight(Weights[i]), horizontalAlignment = if (i == 0) Alignment.Start else Alignment.CenterHorizontally) {
                Text(stringResource(c), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = look.red, textAlign = if (i == 0) TextAlign.Start else TextAlign.Center))
                if (twoLine && i > 0) {
                    Text(stringResource(R.string.pg_volume), style = small(look.inkMuted)); Text(stringResource(R.string.pg_value), style = small(look.inkMuted))
                }
            }
        }
    }
}

@Composable
private fun Cell(top: String, bottom: String, modifier: Modifier) {
    val look = LocalAronLook.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(top, style = TextStyle(fontSize = 16.sp, color = look.ink, textAlign = TextAlign.Center))
        Text(bottom, style = TextStyle(fontSize = 13.sp, color = look.inkMuted, textAlign = TextAlign.Center))
    }
}

/**
 * The table at a large font (fontScale 1.5 and up, long Bangla): one card per brand with a label and its figures per line, so no word is
 * cut mid-way and nothing is clipped. Same figures, same order, same percent rule as the table.
 */
@Composable
private fun StackedCards(data: AsthaData, rows: List<AsthaRow>, allBrand: AsthaRow?, memoTab: Boolean, locale: UiLocale) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth().testTag(AsthaTags.TABLE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!memoTab) rows.forEach { r ->
            Column(Modifier.fillMaxWidth().testTag(AsthaTags.row(r.brandId)).clip(RoundedCornerShape(10.dp)).background(look.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(data.brandName(r.brandId, locale), style = TextStyle(fontSize = 18.sp, color = look.purple, fontWeight = FontWeight.Bold))
                CardLine(R.string.pg_col_target, AsthaModel.stdText(r.stdTargetMilli, locale) + " · " + AsthaModel.valueText(r.valueTargetMtk, locale))
                CardLine(R.string.pg_col_achieved, AsthaModel.stdText(r.stdAchievedMilli, locale) + " · " + AsthaModel.valueText(r.valueAchievedMtk, locale))
                CardLine(R.string.pg_col_remaining, AsthaModel.stdText(AsthaModel.remaining(r.stdTargetMilli, r.stdAchievedMilli), locale) + " · " + AsthaModel.valueText(AsthaModel.remaining(r.valueTargetMtk, r.valueAchievedMtk), locale))
                CardLine(R.string.pg_col_pct, AsthaModel.pctText(r.stdAchievedMilli, r.stdTargetMilli, locale) + " · " + AsthaModel.pctText(r.valueAchievedMtk, r.valueTargetMtk, locale))
                r.byPack.forEach { p ->
                    Text(packText(data, r.brandId, p, locale), style = small(look.inkMuted))
                }
            }
        } else if (allBrand == null) EmptyNote(stringResource(R.string.pg_no_data)) else {
            Column(Modifier.fillMaxWidth().testTag(AsthaTags.row(null)).clip(RoundedCornerShape(10.dp)).background(look.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pg_all_brand), style = TextStyle(fontSize = 18.sp, color = look.purple, fontWeight = FontWeight.Bold))
                CardLine(R.string.pg_col_target, AsthaModel.countText(allBrand.memoTarget, locale))
                CardLine(R.string.pg_col_achieved, AsthaModel.countText(allBrand.memoAchieved, locale))
                CardLine(R.string.pg_col_remaining, AsthaModel.countText(AsthaModel.remaining(allBrand.memoTarget, allBrand.memoAchieved), locale))
                CardLine(R.string.pg_col_pct, AsthaModel.pctText(allBrand.memoAchieved, allBrand.memoTarget, locale))
            }
        }
    }
}

@Composable
private fun CardLine(labelRes: Int, figures: String) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), style = TextStyle(fontSize = 14.sp, color = look.red, fontWeight = FontWeight.Bold))
        Text(figures, style = TextStyle(fontSize = 17.sp, color = look.ink))
    }
}

/** The shop-wise list (manual 51 left): a search box (name, code or phone, typed offline) and one card per outlet. */
@Composable
private fun ShopList(shops: List<ShopInfo>, query: String, onQuery: (String) -> Unit, onOpen: (Long) -> Unit, locale: UiLocale, geography: Geography? = null) {
    val look = LocalAronLook.current
    androidx.compose.material3.OutlinedTextField(
        query, onQuery, Modifier.fillMaxWidth().testTag(AsthaTags.SEARCH), singleLine = true,
        placeholder = { Text(stringResource(R.string.pg_search_hint), style = TextStyle(fontSize = 15.sp, color = look.hint)) },
        shape = RoundedCornerShape(12.dp),
    )
    val shown = ShopModel.filter(shops, query)
    if (shown.isEmpty()) { EmptyNote(stringResource(if (shops.isEmpty()) R.string.pg_no_shops else R.string.pg_no_match)); return }
    shown.forEach { sh ->
        Column(
            Modifier.fillMaxWidth().testTag(AsthaTags.shop(sh.outletId)).clip(RoundedCornerShape(12.dp)).background(look.card)
                .semantics { role = Role.Button }.clickable { onOpen(sh.outletId) }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ShopLine(R.string.pg_shop_name, sh.nameLine, look.ink)
            ShopLine(R.string.pg_shop_owner, sh.owner, look.ink)
            ShopLine(R.string.pg_shop_contact, sh.contact?.let { com.aktcl.aron.rules.Formats.digits(it, locale) }.orEmpty(), look.ink)
            ShopLine(R.string.pg_shop_sub, sh.subChannel ?: "—", look.red) // a dash until the server sends the tier (request N-7)
            ShopLine(R.string.pg_shop_wing, geography?.wing?.text(locale) ?: "—", look.ink) // the user's own zone chain (request N-9); a dash until the bundle carries it
            ShopLine(R.string.pg_shop_division, geography?.division?.text(locale) ?: "—", look.ink)
            ShopLine(R.string.pg_shop_territory, geography?.territory?.text(locale) ?: "—", look.ink)
            ShopLine(R.string.pg_shop_zone, geography?.zone?.text(locale) ?: "—", look.ink)
        }
    }
}

@Composable
private fun ShopLine(labelRes: Int, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), style = TextStyle(fontSize = 13.sp, color = look.inkMuted))
        Text(value, style = TextStyle(fontSize = 16.sp, color = valueColor, fontWeight = FontWeight.Medium))
    }
}

/** "Marise, 20 per pack: 180 packets + 5 sticks" (matches: dozens, lighters: boxes), whole packs and loose units, digits per language. */
@Composable
private fun packText(data: AsthaData, brandId: Long?, p: AsthaPack, locale: UiLocale): String {
    val l = data.packLine(brandId, p)
    val name = data.brandName(brandId, locale)
    val f = com.aktcl.aron.rules.Formats.digits(l.packFactor.toString(), locale)
    val whole = com.aktcl.aron.rules.Formats.integer(l.packs, locale)
    val loose = AsthaModel.stdText(l.looseMilli, locale)
    val hasLoose = l.looseMilli > 0
    // matches count in dozens (the base unit; a fraction is a fraction of a dozen) and an unknown category in neutral base units: one figure, no pack size
    if (l.kind == PackKind.DOZEN) return stringResource(R.string.pg_pack_dozens, name, AsthaModel.stdText(p.stdAchievedMilli, locale))
    if (l.kind == PackKind.UNKNOWN) return stringResource(R.string.pg_pack_unknown, name, AsthaModel.stdText(p.stdAchievedMilli, locale))
    if (l.kind == PackKind.BASE) return stringResource(R.string.pg_pack_base, name, AsthaModel.stdText(p.stdAchievedMilli, locale), com.aktcl.aron.core.ui.entryUnitLabel(l.baseKey))
    val res = when (l.kind) {
        PackKind.PACKET -> if (hasLoose) R.string.pg_pack_packets_loose else R.string.pg_pack_packets
        else -> if (hasLoose) R.string.pg_pack_boxes_loose else R.string.pg_pack_boxes
    }
    return if (hasLoose) stringResource(res, name, f, whole, loose) else stringResource(res, name, f, whole)
}
