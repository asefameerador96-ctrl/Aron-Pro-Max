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

private val MonthShort = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val QuarterSuffix = listOf("Jan-Mar", "Apr-Jun", "Jul-Sep", "Oct-Dec")

internal fun monthChip(month: String): String = MonthShort[month.substring(5, 7).toInt() - 1]

/** "Q-1 (Jan-Mar)" as in manual 49. */
internal fun quarterLabel(q: Int): String = "Q-$q (${QuarterSuffix[q - 1]})"

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
) {
    val look = LocalAronLook.current
    var tab by rememberSaveable { mutableStateOf(if (initialShop != null || startOnShops) 1 else 0) }
    var shop by rememberSaveable { mutableStateOf(initialShop) }
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (shops != null) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(look.rowAlt).padding(3.dp)) {
                SegTab(stringResource(R.string.pg_tab_route), tab == 0, { tab = 0 }, Modifier.weight(1f).testTag(AsthaTags.TAB_ROUTE))
                SegTab(stringResource(R.string.pg_tab_shop), tab == 1, { tab = 1 }, Modifier.weight(1f).testTag(AsthaTags.TAB_SHOP))
            }
        }
        if (tab == 1 && shops != null) {
            val chosen = shop?.let { id -> shops.firstOrNull { it.outletId == id } }
            if (chosen == null) ShopList(shops, query, { query = it }, { shop = it }, locale)
            else {
                AronTextButton(stringResource(R.string.pg_all_shops), { shop = null }, Modifier.testTag(AsthaTags.SHOP_BACK))
                Text(chosen.nameLine, style = label(look.purple), modifier = Modifier.testTag(AsthaTags.SHOP_TITLE))
                TargetBody(data, locale, currentYear, chosen.outletId, initial)
            }
        } else TargetBody(data, locale, currentYear, null, initial)
    }
}

/** The pickers, the month chips, the sub-tabs and the table of the route (outletId null) or of one outlet; its own state is kept per outlet. */
@Composable
private fun ColumnScope.TargetBody(data: AsthaData?, locale: UiLocale, currentYear: Int, outletId: Long?, initial: AsthaSelection) {
    val look = LocalAronLook.current
    var year by rememberSaveable(outletId) { mutableStateOf(initial.year) }
    var quarter by rememberSaveable(outletId) { mutableStateOf(initial.quarter) }
    var month by rememberSaveable(outletId) { mutableStateOf(initial.month) }
    var memoTab by rememberSaveable(outletId) { mutableStateOf(initial.memoTab) }
        if (data == null) { EmptyNote(stringResource(R.string.pg_no_programme)); return }
        val years = (currentYear..currentYear + 4).toList()
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
                AronDropdown((1..4).toList(), quarter, { quarter = it; month = null }, { quarterLabel(it) }, Modifier.testTag(AsthaTags.QUARTER), placeholder = stringResource(R.string.pg_quarter), enabled = year != null)
            }
        }
        // each picker has its own weighted column: AronDropdown fills the width it is given, so a bare pair would let the first take the row
        if (big) { yearPicker(Modifier.fillMaxWidth()); quarterPicker(Modifier.fillMaxWidth()) }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { yearPicker(Modifier.weight(1f)); quarterPicker(Modifier.weight(1f)) }
        val y = year; val q = quarter
        if (y == null || q == null) { EmptyNote(stringResource(R.string.pg_no_data)); return }
        val months = AsthaModel.monthsOfQuarter(y, q)
        Text(stringResource(R.string.pg_pick_month), style = label(look.ink))
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            months.forEach { m -> AronChoiceChip(monthChip(m), month == m, { month = m }, Modifier.testTag(AsthaTags.month(m))) }
        }
        val m = month
        if (m == null) return
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(look.rowAlt).padding(3.dp)) {
            SegTab(stringResource(R.string.pg_sub_std), !memoTab, { memoTab = false }, Modifier.weight(1f).testTag(AsthaTags.SUB_STD))
            SegTab(stringResource(R.string.pg_sub_memo), memoTab, { memoTab = true }, Modifier.weight(1f).testTag(AsthaTags.SUB_MEMO))
        }
        val brandRows = data.brandRows(m, outletId, locale)
        val allBrand = data.allBrandRow(m, outletId)
        if (brandRows.isEmpty() && allBrand == null) { EmptyNote(stringResource(R.string.pg_no_data)); return }
        if (big) StackedCards(data, brandRows, allBrand, memoTab, locale)
        else if (!memoTab) StdTable(data, brandRows, locale) else MemoTable(allBrand, locale)
        data.asOf?.let { Text(stringResource(R.string.pg_as_of, com.aktcl.aron.rules.Formats.digits(it.take(10), locale)), Modifier.testTag(AsthaTags.AS_OF), style = small(look.inkMuted)) }
        if (data.truncated) Text(stringResource(R.string.pg_truncated), Modifier.testTag(AsthaTags.TRUNCATED), style = small(look.inkMuted))

}

@Composable private fun EmptyNote(text: String) {
    val look = LocalAronLook.current
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag(AsthaTags.EMPTY), contentAlignment = Alignment.Center) { Text(text, style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center)) }
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
                Text(data.brandName(r.brandId, locale), Modifier.weight(Weights[0]), style = TextStyle(fontSize = 16.sp, color = look.purple, fontWeight = FontWeight.Medium))
                Cell(AsthaModel.stdText(r.stdTargetMilli, locale), AsthaModel.valueText(r.valueTargetMtk, locale), Modifier.weight(Weights[1]))
                Cell(AsthaModel.stdText(r.stdAchievedMilli, locale), AsthaModel.valueText(r.valueAchievedMtk, locale), Modifier.weight(Weights[2]))
                Cell(AsthaModel.stdText(AsthaModel.remaining(r.stdTargetMilli, r.stdAchievedMilli), locale), AsthaModel.valueText(AsthaModel.remaining(r.valueTargetMtk, r.valueAchievedMtk), locale), Modifier.weight(Weights[3]))
                Cell(AsthaModel.pctText(r.stdAchievedMilli, r.stdTargetMilli, locale), AsthaModel.pctText(r.valueAchievedMtk, r.valueTargetMtk, locale), Modifier.weight(Weights[4]))
            }
            r.byPack.forEach { p ->
                val packets = AsthaModel.stdText(p.stdAchievedMilli / p.packFactor, locale)
                Text(
                    stringResource(R.string.pg_pack_line, data.brandName(r.brandId, locale), com.aktcl.aron.rules.Formats.digits(p.packFactor.toString(), locale), packets),
                    Modifier.padding(horizontal = 10.dp, vertical = 2.dp), style = small(look.inkMuted),
                )
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
                    Text(
                        stringResource(R.string.pg_pack_line, data.brandName(r.brandId, locale), com.aktcl.aron.rules.Formats.digits(p.packFactor.toString(), locale), AsthaModel.stdText(p.stdAchievedMilli / p.packFactor, locale)),
                        style = small(look.inkMuted),
                    )
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
private fun ShopList(shops: List<ShopInfo>, query: String, onQuery: (String) -> Unit, onOpen: (Long) -> Unit, locale: UiLocale) {
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
            sh.subChannel?.let { ShopLine(R.string.pg_shop_sub, it, look.red) }
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
