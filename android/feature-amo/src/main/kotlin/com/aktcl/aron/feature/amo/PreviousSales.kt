package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

/** One SKU line of the previous sale data (screenshot 26): what was sold to the outlet that day, in the field unit. */
data class SaleHistoryLine(val skuId: Long, val skuCode: String, val name: String, val qtyReport: java.math.BigDecimal, val reportUnit: String, val qtyBase: Long, val valueMtk: Long, val categoryCode: String = "")

/** Where the lines came from: the server (all sales to the outlet that day, any SR) or this phone's own memos (offline). */
enum class SaleHistorySource { SERVER, KEPT_COPY, LOCAL }

/** The sales to one outlet on one business date. The total is the true sum of the lines (R3: the vendor's total did not equal its lines). */
data class SaleHistory(val date: String, val lines: List<SaleHistoryLine>, val source: SaleHistorySource, val unreadable: Int = 0) {
    val totalMtk: Long get() = lines.sumOf { it.valueMtk }
}

object PreviousSales {
    /** The server keeps 90 days (contract 1.11.0); the phone offers today and the 89 days before. */
    const val WINDOW_DAYS = 90

    fun earliest(today: String): String = LocalDate.parse(today).minusDays(WINDOW_DAYS - 1L).toString()

    /** Moves [date] by [days] inside the window [earliest(today), today]; a move outside stays put. */
    fun shift(date: String, days: Int, today: String): String {
        val d = LocalDate.parse(date).plusDays(days.toLong())
        val lo = LocalDate.parse(earliest(today)); val hi = LocalDate.parse(today)
        return if (d.isBefore(lo) || d.isAfter(hi)) date else d.toString()
    }

    fun inWindow(date: String, today: String): Boolean = runCatching { LocalDate.parse(date) }.getOrNull()?.let { !it.isBefore(LocalDate.parse(earliest(today))) && !it.isAfter(LocalDate.parse(today)) } == true

    /**
     * `GET /v1/outlets/{id}/sales-by-date` (F2): lines carry `sku {sku_id, sku_code, short_name}`, `qty_report`, `report_unit`, `qty_base`,
     * `value_mtk`. The server's `totals` are ignored: the screen prints the sum of the lines. A body that is not that shape is null.
     */
    fun parseServer(json: String?, date: String, source: SaleHistorySource = SaleHistorySource.SERVER): SaleHistory? {
        val root = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull() ?: return null
        val arr = root["lines"] as? JsonArray ?: return null
        val lines = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val sku = o["sku"] as? JsonObject ?: return@mapNotNull null
            SaleHistoryLine(
                skuId = (sku["sku_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                skuCode = (sku["sku_code"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                name = (sku["short_name"] as? JsonPrimitive)?.contentOrNull ?: (sku["sku_code"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                qtyReport = (o["qty_report"] as? JsonPrimitive)?.contentOrNull?.trim()?.toBigDecimalOrNull() ?: return@mapNotNull null, // Decimal3 string "5.000", never a float
                reportUnit = (o["report_unit"] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull.orEmpty(), // none sent: no unit word, never an invented "stick"
                qtyBase = (o["qty_base"] as? JsonPrimitive)?.longOrNull ?: 0L,
                valueMtk = (o["value_mtk"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
            )
        }
        return SaleHistory(date, lines, source, unreadable = arr.size - lines.size) // a malformed line is counted and said, never silently dropped
    }

    /**
     * Offline: this AMO's own live memos to the outlet on [date] (an edited memo's earlier version is skipped), one line per SKU in base
     * units turned into the field unit. Returns the lines of the memos given; the caller picks the outlet and the date.
     */
    fun fromLocal(date: String, memos: List<LocalMemo>, skus: Map<Long, SkuInfo>): SaleHistory {
        val superseded = memos.mapNotNull { it.supersedesUuid }.toSet()
        val per = linkedMapOf<Long, Pair<Long, Long>>() // skuId -> (qtyBase, value)
        memos.filter { it.memoUuid !in superseded }.forEach { m -> m.lines.forEach { l -> val p = per[l.skuId] ?: (0L to 0L); per[l.skuId] = (p.first + l.qtyBase) to (p.second + l.grossMtk) } }
        val lines = per.map { (id, v) ->
            val info = skus[id]
            val spec = info?.entry
            // A count that is not a whole number of packets/boxes/dozens is never truncated: that line shows its base units (sticks/pieces), exact.
            val whole = spec != null && spec.remainderBase(v.first) == 0L
            val baseKey = com.aktcl.aron.rules.EntryUnits.baseLabelKey(info?.categoryCode.orEmpty())
            SaleHistoryLine(
                id, "", info?.name ?: "#$id",
                java.math.BigDecimal(if (whole) spec!!.fromBase(v.first) else v.first),
                if (whole) com.aktcl.aron.rules.EntryUnits.labelKey(info!!.categoryCode, spec!!.unit) else baseKey, v.first, v.second, info?.categoryCode.orEmpty(),
            )
        }.sortedBy { it.name }
        return SaleHistory(date, lines, SaleHistorySource.LOCAL)
    }

    /** The server lines carry no category: the phone's SKU list gives it, so the server's `pack` reads packet, box or dozen per category. */
    fun withCategories(h: SaleHistory, skus: Map<Long, SkuInfo>): SaleHistory = h.copy(lines = h.lines.map { l -> l.copy(categoryCode = skus[l.skuId]?.categoryCode.orEmpty()) })

    data class LocalMemoLine(val skuId: Long, val qtyBase: Long, val grossMtk: Long)
    data class LocalMemo(val memoUuid: String, val supersedesUuid: String?, val lines: List<LocalMemoLine>)
}

typealias LocalMemo = PreviousSales.LocalMemo

object PreviousSalesTags { const val DATE = "amo_ps_date"; const val PREV = "amo_ps_prev"; const val NEXT = "amo_ps_next"; const val EMPTY = "amo_ps_empty"; const val TOTAL = "amo_ps_total"; const val LOADING = "amo_ps_loading"; const val NOTE = "amo_ps_note"; const val UNREADABLE = "amo_ps_unreadable"
    fun row(skuId: Long) = "amo_ps_row_$skuId" }

/**
 * Previous sale data (screenshot 26): the chosen date with step arrows (today by default, 90 days back), every SKU sold to the outlet
 * that day with its packshot, quantity in the field unit and value, then the true total. [history] null = loading.
 */
@Composable
fun PreviousSalesContent(date: String, today: String, history: SaleHistory?, onShift: (Int) -> Unit, modifier: Modifier = Modifier, outletLabel: String? = null) {
    val look = LocalAronLook.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val loc = if (bn) UiLocale.BN else UiLocale.EN
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.amo_ps_title), style = amoText(18, bold = true, color = look.purple)) // screenshot 26: "সেল ডাটা"
        if (outletLabel != null) Text(outletLabel, style = amoText(16, bold = true))
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            AmoSecondary("‹", { onShift(-1) }, Modifier.testTag(PreviousSalesTags.PREV), enabled = PreviousSales.shift(date, -1, today) != date)
            Text(Formats.digits(date, loc), style = amoText(20, bold = true, color = look.purple), modifier = Modifier.testTag(PreviousSalesTags.DATE))
            AmoSecondary("›", { onShift(1) }, Modifier.testTag(PreviousSalesTags.NEXT), enabled = PreviousSales.shift(date, 1, today) != date)
        }
        when {
            history == null -> Text(stringResource(R.string.amo_ps_loading), style = amoText(16, color = look.inkMuted), modifier = Modifier.testTag(PreviousSalesTags.LOADING))
            history.lines.isEmpty() -> AmoEmpty(stringResource(R.string.amo_ps_empty), Modifier.testTag(PreviousSalesTags.EMPTY))
            else -> {
                history.lines.forEach { l ->
                    AmoPackRow(l.skuId.toString(), l.name, Modifier.testTag(PreviousSalesTags.row(l.skuId)), subtitle = com.aktcl.aron.core.ui.localizedDigits(l.qtyReport.stripTrailingZeros().toPlainString()) + " " + fieldUnitWord(l.reportUnit, l.categoryCode), figure = Formats.digits(com.aktcl.aron.rules.Money.formatTaka(l.valueMtk, 2), loc))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.amo_ps_total), style = amoText(18, bold = true))
                    Text(Formats.digits(com.aktcl.aron.rules.Money.formatTaka(history.totalMtk, 2), loc), style = amoText(18, bold = true, color = look.purple), modifier = Modifier.testTag(PreviousSalesTags.TOTAL))
                }
            }
        }
        // Notes sit under the list AND under an empty answer: an empty offline day is the phone's own copy, not "nothing sold anywhere".
        if (history != null) {
            if (history.source == SaleHistorySource.LOCAL) Text(stringResource(R.string.amo_ps_local_note), style = amoText(13, color = look.inkMuted), modifier = Modifier.testTag(PreviousSalesTags.NOTE))
            if (history.source == SaleHistorySource.KEPT_COPY) Text(stringResource(R.string.amo_ps_kept_note), style = amoText(13, color = look.inkMuted), modifier = Modifier.testTag(PreviousSalesTags.NOTE))
            if (history.unreadable > 0) Text(stringResource(R.string.amo_ps_unreadable, localizedNumber(history.unreadable.toLong())), style = amoText(13, color = look.inkMuted), modifier = Modifier.testTag(PreviousSalesTags.UNREADABLE))
        }
    }
}
