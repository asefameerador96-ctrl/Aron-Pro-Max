package com.aktcl.aron.core.printing.doc

import com.aktcl.aron.core.printing.render.PrintDocument
import com.aktcl.aron.core.printing.template.DigitStyle
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Money
import com.aktcl.aron.rules.UiLocale
import kotlinx.datetime.LocalDate

/*
 * What each paper document prints, as plain data taken from the stored rows (N-018). Every amount is the stored
 * integer milli-taka; nothing is recomputed here, so the printed total is the stored total (the checker proves
 * it against the shared memo oracle, `MemoMath`). Quantities are in the SKU's base unit (sticks, pieces, dozens).
 */

data class MemoPrintLine(val sku: String, val qtyBase: Long, val grossMtk: Long)

data class MemoPrintDiscount(val sku: String, val qtyBase: Long, val valueMtk: Long)

data class MemoPrint(
    /** Contract `PrintTemplate.kind`: cash_memo, credit_memo, offer_memo, drp_memo, zero_memo or edited_memo. */
    val kind: String,
    val memoNo: String,
    val committedAtEpochMs: Long,
    val outlet: String,
    val sr: String,
    val route: String,
    val lines: List<MemoPrintLine>,
    val discounts: List<MemoPrintDiscount>,
    val grossMtk: Long,
    val offerDiscountMtk: Long,
    val drpDiscountMtk: Long,
    val qcDeductionMtk: Long,
    val roundAdjMtk: Long,
    val netMtk: Long,
    val paidMtk: Long,
    val dueMtk: Long,
    val isCredit: Boolean,
    /** 0 for the original print; n for the n-th counted reprint (prints the duplicate marker). */
    val reprintNo: Int = 0,
    /** For an edited memo, the number of the memo it replaces. */
    val supersedesMemoNo: String? = null,
) {
    init {
        require(kind in MEMO_KINDS) { "memo kind $kind" }
        require(reprintNo >= 0) { "reprintNo $reprintNo" }
    }

    companion object {
        val MEMO_KINDS = setOf("cash_memo", "credit_memo", "offer_memo", "drp_memo", "zero_memo", "edited_memo")
    }
}

data class StockSlipLine(val category: String, val sku: String, val qtyBase: Long)

data class StockSlipPrint(
    val printedAtEpochMs: Long,
    val sr: String,
    val route: String,
    val distributor: String,
    val lines: List<StockSlipLine>,
    val reprintNo: Int = 0,
)

data class DaySummaryLine(val sku: String, val memoCount: Int, val qtyBase: Long, val valueMtk: Long)

data class DaySummaryPrint(
    val printedAtEpochMs: Long,
    val sr: String,
    val route: String,
    val lines: List<DaySummaryLine>,
    val grossMtk: Long,
    val discountMtk: Long,
    val qcMtk: Long,
    val netMtk: Long,
    val dueMtk: Long,
    val memoCount: Int,
)

data class VoidSlipPrint(
    val memoNo: String,
    val voidedAtEpochMs: Long,
    val outlet: String,
    val sr: String,
    val reason: String,
    val netMtk: Long,
)

data class DueReceiptPrint(
    val memoNo: String,
    val collectedAtEpochMs: Long,
    val outlet: String,
    val sr: String,
    val dueBeforeMtk: Long,
    val collectedMtk: Long,
    val dueAfterMtk: Long,
    val reprintNo: Int = 0,
)

/** Turns the print models into filled [PrintDocument]s in one digit style. */
class DocumentBuilder(private val digits: DigitStyle) {
    private val locale = if (digits == DigitStyle.BENGALI) UiLocale.BN else UiLocale.EN

    /** Taka with 2 decimals; an amount that is not a whole paisa keeps 3 decimals so nothing is rounded on paper. */
    fun money(mtk: Long): String = Formats.digits(Money.formatTaka(mtk, if (mtk % 10L == 0L) 2 else 3), locale)

    fun qty(n: Long): String = Formats.integer(n, locale)

    fun count(n: Int): String = Formats.integer(n.toLong(), locale)

    fun date(epochMs: Long): String =
        Formats.date(LocalDate.fromEpochDays(Math.floorDiv(epochMs + BusinessDate.DHAKA_OFFSET_MS, 86_400_000L).toInt()), locale, iso = false)

    fun time(epochMs: Long): String = Formats.time(epochMs, locale)

    fun memo(m: MemoPrint): PrintDocument {
        val flags = HashSet<String>()
        flags.add("kind_" + m.kind.removeSuffix("_memo"))
        if (m.reprintNo > 0) flags.add("duplicate")
        if (m.supersedesMemoNo != null) flags.add("supersedes")
        if (m.discounts.isNotEmpty()) flags.add("has_discounts")
        if (m.roundAdjMtk != 0L) flags.add("has_rounding")
        if (m.isCredit || m.dueMtk != 0L) flags.add("credit")
        val totalDiscount = m.offerDiscountMtk + m.drpDiscountMtk
        return PrintDocument(
            fields = mapOf(
                "memo_no" to m.memoNo,
                "date" to date(m.committedAtEpochMs),
                "time" to time(m.committedAtEpochMs),
                "outlet" to m.outlet,
                "sr" to m.sr,
                "route" to m.route,
                "reprint_no" to count(m.reprintNo),
                "supersedes_memo_no" to (m.supersedesMemoNo ?: ""),
                "total_discount" to money(totalDiscount),
                "total_qc" to money(m.qcDeductionMtk),
                "rounding" to (if (m.roundAdjMtk > 0) "+" else "") + money(m.roundAdjMtk),
                "grand_total" to money(m.netMtk),
                "paid" to money(m.paidMtk),
                "due" to money(m.dueMtk),
            ),
            tables = mapOf(
                "lines" to m.lines.map { mapOf("sku" to it.sku, "qty" to qty(it.qtyBase), "value" to money(it.grossMtk)) },
                // Quantities of different units (sticks, pieces, dozens) are never added up; totals are money only.
                "line_total" to listOf(mapOf("value" to money(m.grossMtk))),
                "discounts" to m.discounts.map { mapOf("sku" to it.sku, "qty" to qty(it.qtyBase), "value" to money(it.valueMtk)) },
                "discount_total" to listOf(mapOf("value" to money(m.discounts.sumOf { it.valueMtk }))),
            ),
            flags = flags,
        )
    }

    /**
     * Per SKU, grouped by category in first-seen order, with a bold total row per category. One category holds
     * one unit (sticks, pieces or dozens), so its total is meaningful; there is no grand total across units.
     */
    fun stockSlip(s: StockSlipPrint): PrintDocument {
        val rows = ArrayList<Map<String, String>>()
        for ((category, lines) in s.lines.groupBy { it.category }) {
            for (l in lines) rows.add(mapOf("sku" to l.sku, "qty" to qty(l.qtyBase)))
            rows.add(mapOf("sku" to category, "qty" to qty(lines.sumOf { it.qtyBase }), "_bold" to "1"))
        }
        return PrintDocument(
            fields = mapOf(
                "date" to date(s.printedAtEpochMs), "time" to time(s.printedAtEpochMs),
                "sr" to s.sr, "route" to s.route, "distributor" to s.distributor,
            ),
            tables = mapOf("lines" to rows),
            flags = if (s.reprintNo > 0) setOf("duplicate") else emptySet(),
        )
    }

    fun daySummary(d: DaySummaryPrint): PrintDocument = PrintDocument(
        fields = mapOf(
            "date" to date(d.printedAtEpochMs), "time" to time(d.printedAtEpochMs), "sr" to d.sr, "route" to d.route,
            "gross" to money(d.grossMtk), "total_discount" to money(d.discountMtk), "total_qc" to money(d.qcMtk),
            "grand_total" to money(d.netMtk), "due" to money(d.dueMtk), "memo_count" to count(d.memoCount),
        ),
        tables = mapOf("lines" to d.lines.map {
            mapOf("sku" to it.sku, "memos" to count(it.memoCount), "qty" to qty(it.qtyBase), "value" to money(it.valueMtk))
        }),
    )

    fun voidSlip(v: VoidSlipPrint): PrintDocument = PrintDocument(
        fields = mapOf(
            "memo_no" to v.memoNo, "date" to date(v.voidedAtEpochMs), "time" to time(v.voidedAtEpochMs),
            "outlet" to v.outlet, "sr" to v.sr, "reason" to v.reason, "amount" to money(v.netMtk),
        ),
    )

    fun dueReceipt(r: DueReceiptPrint): PrintDocument = PrintDocument(
        fields = mapOf(
            "memo_no" to r.memoNo, "date" to date(r.collectedAtEpochMs), "time" to time(r.collectedAtEpochMs),
            "outlet" to r.outlet, "sr" to r.sr, "due_before" to money(r.dueBeforeMtk),
            "collected" to money(r.collectedMtk), "due_after" to money(r.dueAfterMtk),
        ),
        flags = if (r.reprintNo > 0) setOf("duplicate") else emptySet(),
    )
}
