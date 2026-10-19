package com.aktcl.aron.feature.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.aron.ButtonGlyph
import com.aktcl.aron.core.ui.aron.PackCard
import com.aktcl.aron.core.ui.aron.PackCategory
import com.aktcl.aron.core.ui.aron.PackListHeader
import com.aktcl.aron.core.ui.aron.TotalsLine
import com.aktcl.aron.core.ui.aron.TotalsPanel
import com.aktcl.aron.core.ui.localizedNumber

object StockTags {
    const val SAVE = "stk_save"
    const val MESSAGE = "stk_message"
    const val TOTALS = "stk_totals"
    /** The scrolling list of SKU rows; the totals panel is its last item. */
    const val LIST = "stk_list"
    const val PRINT = "stk_print"
    fun row(skuId: Long) = "stk_row_$skuId"
}

/** What the last Save (or print) said, shown as a banner above the list. */
enum class StockMessage { SAVED, REFUSED_SAME_VALUES, NOTHING_ENTERED, SAVE_FAILED, PRINT_FAILED }

/** Why the Print secondary is disabled (screens-sr s4): the reason is shown as a caption in the bar. */
enum class StockPrintBlock { SAVE_FIRST, PRINTER_OFF }

@Composable
private fun categoryLabel(code: String): String = when (code) {
    "cigarette" -> stringResource(R.string.stk_cat_cigarette)
    "bidi" -> stringResource(R.string.stk_cat_bidi)
    "lighter" -> stringResource(R.string.stk_cat_lighter)
    "match" -> stringResource(R.string.stk_cat_match)
    else -> code
}

@Composable
private fun unitLabel(unit: String): String = when (unit) {
    "piece" -> stringResource(R.string.stk_unit_piece)
    "dozen" -> stringResource(R.string.stk_unit_dozen)
    else -> stringResource(R.string.stk_unit_stick)
}

/** The order the groups appear in (screens-sr s4): cigarette, bidi, lighter, match, then anything else by code. */
private fun categoryRank(code: String): Int = when (code) { "cigarette" -> 0; "bidi" -> 1; "lighter" -> 2; "match" -> 3; else -> 4 }

/**
 * The Stock screen content (F-SR-014, sr/stock.webp): the column head (SKU, Issue, Stock), one kit [PackCard] per SKU (packshot with
 * the packs badge, SKU code, red minus, underlined Issue field, purple plus, the stock figure), the purple totals panel by category
 * (the unit as a caption under each category) and the Save and Print buttons. The host frame supplies the top bar and the printer badge.
 * Save never waits for the printer or the network (Q-UI-03); Print is grey until a printer is connected and the slip is saved.
 */
@Composable
fun StockContent(
    rows: List<StockRow>,
    totals: List<CategoryTotal>,
    message: StockMessage?,
    slipNotPrinted: Boolean,
    onIssue: (skuId: Long, qty: Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    softCeiling: Long = StockLoad.DEFAULT_SOFT_CEILING,
    onPrint: (() -> Unit)? = null,
    printEnabled: Boolean = true,
    /** Why Print is disabled; null derives it from [printEnabled] (false reads as the printer being off). */
    printBlock: StockPrintBlock? = null,
    /** "synced at" caption of the empty state, when the host knows it. */
    syncedAt: String? = null,
) {
    val look = LocalAronLook.current
    val hasChange = rows.any { it.entered > 0 }
    // the key figure is the stock in the bag only when the host supplies it; otherwise it is the total load, and says so
    val hasStock = rows.any { it.stockBase != null }
    val block = printBlock ?: if (printEnabled) null else StockPrintBlock.PRINTER_OFF
    val ordered = rows.sortedBy { categoryRank(it.sku.categoryCode) }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            // the printer badge hangs half below the bar (sr/stock.webp): keep its band clear of the banners
            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                when (message) {
                    StockMessage.SAVED -> AronBanner(
                        stringResource(R.string.stk_saved), Modifier.testTag(StockTags.MESSAGE), BannerKind.Success,
                        actionLabel = if (onPrint != null) stringResource(R.string.stk_print_short) else null, onAction = onPrint,
                    )
                    StockMessage.REFUSED_SAME_VALUES -> AronBanner(stringResource(R.string.stk_refused), Modifier.testTag(StockTags.MESSAGE), BannerKind.Warning)
                    StockMessage.NOTHING_ENTERED -> AronBanner(stringResource(R.string.stk_nothing), Modifier.testTag(StockTags.MESSAGE), BannerKind.Warning)
                    StockMessage.SAVE_FAILED -> AronBanner(stringResource(R.string.stk_save_failed), Modifier.testTag(StockTags.MESSAGE), BannerKind.Error)
                    StockMessage.PRINT_FAILED -> AronBanner(
                        stringResource(R.string.stk_print_failed), Modifier.testTag(StockTags.MESSAGE), BannerKind.Warning,
                        actionLabel = if (onPrint != null) stringResource(R.string.stk_retry) else null, onAction = onPrint,
                    )
                    null -> Unit
                }
                if (slipNotPrinted && message != StockMessage.SAVED) AronBanner(stringResource(R.string.stk_slip_not_printed), kind = BannerKind.Warning)
            }
            if (rows.isEmpty()) {
                AronEmptyState(stringResource(R.string.stk_empty), icon = AronIcons.Stock, syncedAt = syncedAt)
            } else {
                PackListHeader(
                    sku = stringResource(R.string.stk_col_sku), quantity = stringResource(R.string.stk_col_issue),
                    stock = stringResource(if (hasStock) R.string.stk_col_stock else R.string.stk_col_loaded_short),
                )
                LazyColumn(
                    Modifier.weight(1f).testTag(StockTags.LIST),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ordered, key = { it.sku.skuId }, contentType = { "sku" }) { r -> StockSkuRow(r, softCeiling, onIssue) }
                }
            }
        }
        // the purple panel and the buttons, docked at the bottom (sr/stock.webp)
        val reason = listOfNotNull(
            if (!hasChange) stringResource(R.string.stk_reason_no_change) else null,
            when {
                onPrint == null -> null
                block == StockPrintBlock.SAVE_FIRST -> stringResource(R.string.stk_reason_save_first)
                block == StockPrintBlock.PRINTER_OFF -> stringResource(R.string.stk_reason_connect_printer)
                else -> null
            },
        ).takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.stk_reason_join))
        Column(Modifier.fillMaxWidth().background(look.panel).windowInsetsPadding(WindowInsets.navigationBars)) {
            StockTotals(rows, totals, hasStock)
            if (reason != null) Text(reason, color = look.onPanelMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AronPrimaryButton(
                    stringResource(R.string.stk_save), onSave, Modifier.weight(1f).testTag(StockTags.SAVE), enabled = hasChange, glyph = ButtonGlyph.Save,
                )
                AronSecondaryButton(
                    stringResource(R.string.stk_print_short), { onPrint?.invoke() }, Modifier.weight(1f).testTag(StockTags.PRINT),
                    enabled = onPrint != null && block == null, glyph = ButtonGlyph.Print,
                )
            }
        }
    }
}

/** The kit's pack category of a category code (the colour of the neutral silhouette when no packshot is cached). */
internal fun packCategoryOf(code: String): PackCategory = when (code) {
    "bidi" -> PackCategory.Bidi
    "lighter" -> PackCategory.Lighter
    "match" -> PackCategory.Match
    else -> PackCategory.Cigarette
}

/** Typed digits (Latin or Bangla) to an Issue quantity; empty reads as zero. */
internal fun parseQty(text: String): Int =
    text.filter(Char::isDigit).fold(0L) { acc, ch -> (acc * 10 + Character.digit(ch, 10)).coerceAtMost(StockLoad.MAX_ENTRY) }.toInt()

/**
 * One SKU row (sr/stock.webp): the kit [PackCard]. The packs badge is the packs equivalent of the key figure when the SKU is
 * packed (Q-UI-11). Each control carries its own TalkBack label with the SKU name (minus, the Issue field, plus).
 */
@Composable
private fun StockSkuRow(r: StockRow, softCeiling: Long, onIssue: (Long, Int) -> Unit) {
    val look = LocalAronLook.current
    val unit = unitLabel(r.sku.baseUnit)
    val stock = r.stockBase
    val figure = (stock ?: r.loadedBase) + r.entered
    val packs = if (r.sku.basePerPack > 1) figure / r.sku.basePerPack else null
    val max = StockLoad.MAX_ENTRY.toInt()
    val name = r.sku.shortName
    val entered = r.entered.toInt()
    Column(Modifier.testTag(StockTags.row(r.sku.skuId))) {
        PackCard(
            skuId = r.sku.skuId.toString(), skuCode = name,
            quantity = localizedNumber(r.entered), onQuantityChange = { onIssue(r.sku.skuId, parseQty(it).coerceIn(0, max)) },
            onMinus = { onIssue(r.sku.skuId, (entered - 1).coerceAtLeast(0)) }, onPlus = { onIssue(r.sku.skuId, (entered + 1).coerceAtMost(max)) },
            stock = localizedNumber(figure),
            minusLabel = stringResource(R.string.stk_less) + ", " + name, plusLabel = stringResource(R.string.stk_more) + ", " + name,
            quantityLabel = name + ", " + stringResource(R.string.stk_col_issue) + ", " + unit,
            packBadge = packs?.let { localizedNumber(it) },
            category = packCategoryOf(r.sku.categoryCode),
            minusEnabled = entered > 0, plusEnabled = entered < max,
        )
        if (r.entered > softCeiling) {
            Text(stringResource(R.string.stk_high), color = look.red, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp))
        }
    }
}

/**
 * The totals panel (ক্যাটাগরি / মোট ইস্যু / স্টক): all four categories, zeros included, the unit in the label. Issued is today's
 * total in base units; stock is the bag after the issue being entered when the host supplies it, else the total load.
 */
@Composable
private fun StockTotals(rows: List<StockRow>, totals: List<CategoryTotal>, hasStock: Boolean) {
    val issued = totals.associate { it.categoryCode to it.issuedBase }
    val unitOf = mapOf("cigarette" to "stick", "bidi" to "stick", "lighter" to "piece", "match" to "dozen")
    val cats = listOf("cigarette", "bidi", "lighter", "match")
    val lines = cats.map { code ->
        val unit = unitLabel(rows.firstOrNull { it.sku.categoryCode == code }?.sku?.baseUnit ?: unitOf.getValue(code))
        val stock = rows.filter { it.sku.categoryCode == code }.sumOf { (it.stockBase ?: it.loadedBase) + it.entered }
        TotalsLine(categoryLabel(code), listOf(localizedNumber(issued[code] ?: 0L), localizedNumber(stock)), caption = unit)
    }
    TotalsPanel(
        header = TotalsLine(
            stringResource(R.string.stk_col_category),
            listOf(stringResource(R.string.stk_col_total_issue), stringResource(if (hasStock) R.string.stk_col_stock else R.string.stk_col_loaded)),
        ),
        lines = lines, modifier = Modifier.testTag(StockTags.TOTALS),
    )
}
