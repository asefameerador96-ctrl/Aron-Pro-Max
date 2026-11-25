package com.aktcl.aron.feature.programmes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale

/** The three lists "See all brands" opens (screen 61): cigarette volume, memo, and the BLM categories. */
enum class KpiKind { VOLUME, MEMO, BLM }

object KpiTags {
    const val MONTH_CARD = "kpi_month_card"
    const val ALLOCATED = "kpi_allocated"
    const val ACHIEVED = "kpi_achieved"
    const val ACHIEVEMENT = "kpi_achievement"
    const val THROUGH = "kpi_through"
    const val UNCONFIRMED = "kpi_unconfirmed"
    const val OFFLINE = "kpi_offline"
    const val EMPTY = "kpi_empty"
    const val CUT = "kpi_cut"
    const val CPR = "kpi_cpr"
    const val INCOMPLETE = "kpi_incomplete"
    const val LEFT_OUT = "kpi_volume_left_out"
    fun section(kind: KpiKind) = "kpi_section_${kind.name.lowercase()}"
    fun seeAll(kind: KpiKind) = "kpi_see_all_${kind.name.lowercase()}"
    fun today(kind: KpiKind) = "kpi_today_${kind.name.lowercase()}"
    fun brand(id: Long) = "kpi_brand_$id"
    fun category(code: String) = "kpi_category_$code"
    const val SUMMARY = "kpi_summary"
}

private val Amber = Color(0xFFE0A000)
private val Green = Color(0xFF2E9E57)

@Composable private fun unitWord(key: String): String = stringResource(
    when (key) {
        "packet" -> R.string.pg_tgt_unit_packet; "box" -> R.string.pg_tgt_unit_box; "dozen" -> R.string.pg_tgt_unit_dozen; "piece" -> R.string.pg_tgt_unit_piece; "memo" -> R.string.pg_kpi_unit_memo
        else -> R.string.pg_tgt_unit_stick
    },
)

/** The local "today on this phone" figures of the KPI: volume in field units (milli) and memos, from the figure frame; shown in their own line, never in a total. */
data class KpiToday(val volume: FieldQty?, val memos: Long)

/**
 * KPI Dashboard (manual 60): the month card, then Volume, Memo, BLM and CPR sections, all the SERVER's figures as sent, complete through their last closed day. Money the owner has not defined yet
 * (achieved KPI, its percentage, KPI today, achieved CPR) is a dash in the manual's own layout, never a number made up here. The warning about absence is NOT built (the bundle carries no absence
 * count, docs/requests/sr-kpi-absence-count.md). [data] null is the empty state; loading and error belong to the host.
 */
@Composable
fun KpiDashboardScreen(
    data: KpiData?, locale: UiLocale, modifier: Modifier = Modifier, volumeToday: KpiToday? = null, memoToday: KpiToday? = null, unconfirmedEarlier: Int = 0, offline: Boolean = false,
    onSeeAll: (KpiKind) -> Unit = {},
) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (offline) Note(stringResource(R.string.pg_tgt_offline), KpiTags.OFFLINE)
        if (data == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag(KpiTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pg_kpi_empty), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        Note(if (data.fromFirst) stringResource(R.string.pg_tgt_through_first) else stringResource(R.string.pg_tgt_through, LoyaltyModel.dateText(data.throughDate, locale)), KpiTags.THROUGH)
        if (unconfirmedEarlier > 0) Note(countText(R.plurals.pg_tgt_unconfirmed, unconfirmedEarlier, locale), KpiTags.UNCONFIRMED)
        if (data.incomplete) Note(stringResource(R.string.pg_kpi_incomplete), KpiTags.INCOMPLETE)
        MonthCard(data, locale)
        Section(
            KpiKind.VOLUME, stringResource(R.string.pg_kpi_volume_title), look.red, KpiModel.bpText(data.volume.bp, locale),
            { Totals(data.volume, locale, data.volume.bp) }, volumeToday, locale,
            rows = data.volumeBrands.take(3).map { b -> BarRow(b.name?.text(locale) ?: "#${b.brandId}", b.volumeBp, listOf(KpiModel.bpText(b.volumeBp, locale) to look.red, KpiModel.bpText(b.memoBp, locale) to Amber)) },
            headers = listOf(stringResource(R.string.pg_kpi_volume) to look.red, stringResource(R.string.pg_kpi_memo) to Amber), seeAll = data.volumeBrands.size, onSeeAll = { onSeeAll(KpiKind.VOLUME) },
        )
        Section(
            KpiKind.MEMO, stringResource(R.string.pg_kpi_memo_title), Amber, KpiModel.bpText(data.memo.bp, locale),
            { Totals(data.memo, locale, data.memo.bp) }, memoToday, locale,
            rows = data.brands.take(3).map { b -> BarRow(b.name?.text(locale) ?: "#${b.brandId}", b.memoBp, listOf(KpiModel.bpText(b.memoBp, locale) to Amber)) },
            headers = listOf(stringResource(R.string.pg_kpi_memo) to Amber), seeAll = data.brands.size, onSeeAll = { onSeeAll(KpiKind.MEMO) },
        )
        if (data.categories.isNotEmpty()) Section(
            KpiKind.BLM, stringResource(R.string.pg_kpi_blm_title), Green, null,
            { data.categories.forEach { c -> CategoryTotals(c, locale) } }, null, locale,
            rows = data.categories.map { c -> BarRow(categoryName(c.categoryCode), c.pair.bp, listOf(KpiModel.bpText(c.pair.bp, locale) to Green)) },
            headers = listOf(stringResource(R.string.pg_kpi_volume) to Green), seeAll = data.categories.size, onSeeAll = { onSeeAll(KpiKind.BLM) },
        )
        CprSection(data, locale)
        if (data.brandsTruncated) Note(stringResource(R.string.pg_kpi_brands_cut), KpiTags.CUT)
    }
}

@Composable private fun partCategoryName(code: String) = stringResource(
    when (code) { "cigarette" -> R.string.pg_kpi_part_cigarette; "bidi" -> R.string.pg_kpi_part_bidi; "match" -> R.string.pg_kpi_cat_match; "lighter" -> R.string.pg_kpi_cat_lighter; else -> R.string.pg_kpi_part_other },
)

@Composable private fun categoryName(code: String) = stringResource(if (code == "match") R.string.pg_kpi_cat_match else R.string.pg_kpi_cat_lighter)

@Composable private fun Note(text: String, tag: String) {
    val look = LocalAronLook.current
    Text(text, Modifier.testTag(tag), style = TextStyle(fontSize = 14.sp, color = look.purple, fontWeight = FontWeight.Medium))
}

/** The month card (deep purple): the month, the days elapsed of the working days, and the three tiles; every undefined figure is a dash. */
@Composable private fun MonthCard(d: KpiData, locale: UiLocale) {
    val look = LocalAronLook.current
    val m = d.month.substring(5, 7).toIntOrNull()?.minus(1) ?: 0
    val names = stringArrayResource(R.array.pg_months_full)
    val title = names.getOrElse(m) { d.month } + " " + Formats.digits(d.month.take(4), locale)
    val days = if (d.daysElapsed != null && d.workingDays != null) Formats.digits("${d.daysElapsed} / ${d.workingDays}", locale) else Percent.DASH
    Column(Modifier.fillMaxWidth().testTag(KpiTags.MONTH_CARD).clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF4A1FA8), Color(0xFF7B3FE4))))) {
        Row(Modifier.fillMaxWidth().background(Color(0x22000000)).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White))
            Text(days, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White))
        }
        val large = LocalDensity.current.fontScale >= 1.3f
        val tiles: @Composable (Modifier) -> Unit = { m ->
            Tile(stringResource(R.string.pg_kpi_allocated), KpiModel.takaText(d.allocatedMtk, locale), m.testTag(KpiTags.ALLOCATED))
            Tile(stringResource(R.string.pg_kpi_achieved), KpiModel.takaText(d.achievedMtk, locale), m.testTag(KpiTags.ACHIEVED))
            Tile(stringResource(R.string.pg_kpi_achievement), KpiModel.bpText(d.achievementBp, locale), m.testTag(KpiTags.ACHIEVEMENT))
        }
        // at a large font size the three tiles stack so no label or figure is cut
        if (large) Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { tiles(Modifier.fillMaxWidth()) }
        else Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { tiles(Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 6.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = TextStyle(fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center))
        Text(value, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center))
    }
}

private data class BarRow(val name: String, val bp: Long?, val percents: List<Pair<String, Color>>)

/** One section: the tinted header with its percentage pill, the totals, the local line, the brand bars and the red "See all" button. */
@Composable
private fun Section(
    kind: KpiKind, title: String, accent: Color, pill: String?, totals: @Composable () -> Unit, today: KpiToday?, locale: UiLocale,
    rows: List<BarRow>, headers: List<Pair<String, Color>>, seeAll: Int, onSeeAll: () -> Unit,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().testTag(KpiTags.section(kind)).clip(shape).background(look.card)) {
        Row(Modifier.fillMaxWidth().background(accent.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).heightIn(min = 22.dp).background(accent))
            Text(title, Modifier.weight(1f).padding(horizontal = 10.dp), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = look.ink))
            if (pill != null) Text(pill, Modifier.clip(RoundedCornerShape(50)).background(accent).padding(horizontal = 12.dp, vertical = 4.dp), style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White))
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            totals()
            if (today != null && (today.memos > 0L || (today.volume?.milli ?: 0L) > 0L)) {
                val text = if (kind == KpiKind.MEMO) stringResource(R.string.pg_tgt_today_phone, Formats.integer(today.memos, locale), unitWord("memo"))
                else stringResource(R.string.pg_tgt_today_phone, today.volume?.let { KpiModel.qtyText(it, locale) } ?: Percent.DASH, today.volume?.let { unitWord(it.unitKey) } ?: "")
                Text(text, Modifier.padding(top = 6.dp).testTag(KpiTags.today(kind)), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pg_kpi_brand), Modifier.weight(1f), style = TextStyle(fontSize = 13.sp, color = look.inkMuted))
                headers.forEachIndexed { i, (h, c) -> Text(h, Modifier.padding(start = if (i == 0) 0.dp else 10.dp), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c)) }
            }
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, Modifier.width(92.dp), style = TextStyle(fontSize = 14.sp, color = look.ink), maxLines = 2)
                    Bar(r.bp, Modifier.weight(1f).padding(horizontal = 8.dp))
                    r.percents.forEach { (t, c) -> Text(t, Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c)) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().testTag(KpiTags.seeAll(kind)).background(look.red).semantics { role = Role.Button }.clickable(onClick = onSeeAll).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.pg_kpi_see_all, Formats.integer(seeAll.toLong(), locale)) + "  >", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color.White))
        }
    }
}

/** A grey track with the red fill of the percentage (capped at a full bar); a dash percentage has an empty track. */
@Composable private fun Bar(bp: Long?, modifier: Modifier) {
    val look = LocalAronLook.current
    val fill = KpiModel.fillPerMille(bp) ?: 0
    Box(modifier.heightIn(min = 8.dp, max = 8.dp).clip(RoundedCornerShape(4.dp)).background(look.track)) {
        if (fill > 0) Box(Modifier.fillMaxWidth(fill / 1000f).heightIn(min = 8.dp, max = 8.dp).background(look.red))
    }
}

@Composable private fun Totals(p: KpiPair, locale: UiLocale, bp: Long?) {
    val look = LocalAronLook.current
    val large = LocalDensity.current.fontScale >= 1.3f
    val unit = unitWord(p.target.unitKey)
    @Composable fun block(label: String, value: String, color: Color, end: Boolean) {
        Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
            Text(label, style = TextStyle(fontSize = 13.sp, color = look.inkMuted))
            Text("$value $unit", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color))
        }
    }
    if (large) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block(stringResource(R.string.pg_kpi_total_target), KpiModel.qtyText(p.target, locale), look.ink, false)
        block(stringResource(R.string.pg_kpi_total_achievement), KpiModel.qtyText(p.achieved, locale), look.red, false)
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        block(stringResource(R.string.pg_kpi_total_target), KpiModel.qtyText(p.target, locale), look.ink, false)
        block(stringResource(R.string.pg_kpi_total_achievement), KpiModel.qtyText(p.achieved, locale), look.red, true)
    }
    Bar(bp, Modifier.fillMaxWidth().padding(top = 8.dp))
}

/** The server sends no BLM total (matches in dozens and lighters in boxes are not added together): one line per category with its own target and achievement. */
@Composable private fun CategoryTotals(c: KpiCategoryRow, locale: UiLocale) {
    val look = LocalAronLook.current
    val unit = unitWord(c.pair.target.unitKey)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(categoryName(c.categoryCode), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = look.ink))
        Text("${KpiModel.qtyText(c.pair.achieved, locale)} / ${KpiModel.qtyText(c.pair.target, locale)} $unit", style = TextStyle(fontSize = 15.sp, color = look.ink))
    }
}

@Composable private fun CprSection(d: KpiData, locale: UiLocale) {
    val look = LocalAronLook.current
    val purple = look.purple
    Column(Modifier.fillMaxWidth().testTag(KpiTags.CPR).clip(RoundedCornerShape(14.dp)).background(look.card)) {
        Row(Modifier.fillMaxWidth().background(purple.copy(alpha = 0.10f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).heightIn(min = 22.dp).background(purple))
            Text(stringResource(R.string.pg_kpi_cpr_title), Modifier.padding(horizontal = 10.dp), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = look.ink))
        }
        val large = LocalDensity.current.fontScale >= 1.3f
        val cells: List<Triple<String, String, Color>> = listOf(
            Triple(stringResource(R.string.pg_kpi_target_cpr), KpiModel.bpText(d.cprTargetBp, locale), look.ink), Triple(stringResource(R.string.pg_kpi_achieved_cpr), KpiModel.bpText(d.cprAchievedBp, locale), look.red),
            Triple(stringResource(R.string.pg_kpi_allocated), KpiModel.takaText(d.cprAllocMtk, locale), look.ink), Triple(stringResource(R.string.pg_kpi_kpi_today), Percent.DASH, look.red),
        )
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (large) cells.forEach { (l, v, c) -> Column { Label(l); Big(v, c) } }
            else cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    pair.forEachIndexed { i, (l, v, c) -> Column(horizontalAlignment = if (i == 0) Alignment.Start else Alignment.End) { Label(l); Big(v, c) } }
                }
            }
        }
    }
}

@Composable private fun Label(t: String) = Text(t, style = TextStyle(fontSize = 13.sp, color = LocalAronLook.current.inkMuted))
@Composable private fun Big(t: String, c: Color) = Text(t, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c))

/**
 * Brand wise KPI (manual 61): the summary card (volume and memo percentages in the order of [kind]) and the list "FMC BRANDS" with each brand's target and KPI today (a dash until the owner defines it) and
 * its two percentages. For [KpiKind.BLM] the rows are the match and lighter categories. A dash percentage is a dash, never 0.
 */
@Composable
fun BrandKpiScreen(data: KpiData?, kind: KpiKind, locale: UiLocale, modifier: Modifier = Modifier, offline: Boolean = false) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (offline) Note(stringResource(R.string.pg_tgt_offline), KpiTags.OFFLINE)
        if (data == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag(KpiTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pg_kpi_not_sent), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        val volumeFirst = kind != KpiKind.MEMO
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().testTag(KpiTags.SUMMARY).clip(RoundedCornerShape(16.dp)).background(look.card).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(when (kind) { KpiKind.VOLUME -> R.string.pg_kpi_on_cigarette; KpiKind.MEMO -> R.string.pg_kpi_on_memo; KpiKind.BLM -> R.string.pg_kpi_on_blm }),
                style = TextStyle(fontSize = 20.sp, color = look.ink, textAlign = TextAlign.Center),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (kind == KpiKind.BLM) data.categories.forEach { c -> Summary(categoryName(c.categoryCode), KpiModel.bpText(c.pair.bp, locale)) }
                else {
                    val vol = stringResource(R.string.pg_kpi_total_volume) to KpiModel.bpText(data.volume.bp, locale)
                    val memo = stringResource(R.string.pg_kpi_total_memo) to KpiModel.bpText(data.memo.bp, locale)
                    (if (volumeFirst) listOf(vol, memo) else listOf(memo, vol)).forEach { (l, v) -> Summary(l, v) }
                }
            }
        }
        Text(stringResource(R.string.pg_kpi_fmc_brands), Modifier.fillMaxWidth().background(look.card).padding(horizontal = 12.dp, vertical = 10.dp), style = TextStyle(fontSize = 14.sp, color = look.inkMuted))
        if (kind == KpiKind.VOLUME && data.volumeLeftOut) Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { Note(stringResource(R.string.pg_kpi_volume_left_out), KpiTags.LEFT_OUT) }
        Column(Modifier.fillMaxWidth().background(look.card)) {
            if (kind == KpiKind.BLM) data.categories.forEach { c ->
                BrandRow(KpiTags.category(c.categoryCode), categoryName(c.categoryCode), stringResource(R.string.pg_tgt_target) + " " + KpiModel.qtyText(c.pair.target, locale) + " " + unitWord(c.pair.target.unitKey),
                    listOf(stringResource(R.string.pg_kpi_volume) to KpiModel.bpText(c.pair.bp, locale)))
            } else (if (kind == KpiKind.VOLUME) data.volumeBrands else data.brands).forEach { b ->
                val cols = listOf(stringResource(R.string.pg_kpi_volume) to KpiModel.bpText(b.volumeBp, locale), stringResource(R.string.pg_kpi_memo) to KpiModel.bpText(b.memoBp, locale))
                // every row prints its unit: the volume in the brand's own unit (a brand whose unit the phone cannot tell is a dash with a note), the memo target as a count of memos
                val perCategory = b.categoryVolumes.map { c ->
                    c.target?.let { KpiModel.qtyText(it, locale) + " " + unitWord(it.unitKey) } // a category whose unit is not known yet is still a part of its own (category word and a dash), never left out
                        ?: stringResource(R.string.pg_kpi_part_unknown, partCategoryName(c.categoryCode))
                }
                // a brand of several categories (a 1.25.0 server) shows each in its own unit, never added; the separator is a separator, not a sum
                val target = if (volumeFirst) (if (b.volumeTarget == null && perCategory.isNotEmpty()) perCategory.joinToString(stringResource(R.string.pg_kpi_part_sep)) else b.volumeTarget?.let { KpiModel.qtyText(it, locale) + " " + unitWord(it.unitKey) }) ?: (Percent.DASH + " (" + stringResource(R.string.pg_kpi_unit_unknown) + ")")
                else Formats.integer(b.memoTarget, locale) + " " + unitWord("memo")
                BrandRow(KpiTags.brand(b.brandId), b.name?.text(locale) ?: "#${b.brandId}", stringResource(R.string.pg_kpi_target_line, target, KpiModel.takaText(b.kpiTodayMtk, locale)), if (volumeFirst) cols else cols.reversed())
            }
        }
        if (data.incomplete) Note(stringResource(R.string.pg_kpi_incomplete), KpiTags.INCOMPLETE)
        if (data.brandsTruncated) Note(stringResource(R.string.pg_kpi_brands_cut), KpiTags.CUT)
    }
}

@Composable private fun Summary(label: String, value: String) {
    val look = LocalAronLook.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = TextStyle(fontSize = 15.sp, color = look.ink, textAlign = TextAlign.Center))
        Text(value, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = look.red, textAlign = TextAlign.Center))
    }
}

@Composable private fun BrandRow(tag: String, name: String, line: String, columns: List<Pair<String, String>>) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().testTag(tag).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = TextStyle(fontSize = 18.sp, color = look.ink))
            Text(line, style = TextStyle(fontSize = 14.sp, color = look.inkMuted))
        }
        columns.forEach { (h, v) ->
            Column(Modifier.padding(start = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(h, style = TextStyle(fontSize = 12.sp, color = look.inkMuted))
                Text(v, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = look.red))
            }
        }
    }
}
