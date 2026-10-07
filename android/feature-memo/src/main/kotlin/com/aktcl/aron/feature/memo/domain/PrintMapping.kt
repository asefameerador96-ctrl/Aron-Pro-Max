package com.aktcl.aron.feature.memo.domain

import com.aktcl.aron.core.printing.doc.DaySummaryLine
import com.aktcl.aron.core.printing.doc.DaySummaryPrint
import com.aktcl.aron.core.printing.doc.MemoPrint
import com.aktcl.aron.core.printing.doc.MemoPrintDiscount
import com.aktcl.aron.core.printing.doc.MemoPrintLine
import java.time.Instant

/** Names the print needs, resolved from the bundle (SKU short names, outlet `name (code)`, SR and route names). */
data class PrintNames(val sku: (Long) -> String, val outlet: (Long) -> String, val sr: String, val route: String)

/**
 * Maps stored rows to the print models for android-print (docs/requests/android-print-integration.md s2): every amount is
 * the stored mtk column, nothing is recomputed. The template kind follows what the memo contains.
 */
object PrintMapping {
    fun kindOf(m: StoredMemo): String = when {
        m.supersedesUuid != null -> "edited_memo"
        m.lines.isEmpty() -> "zero_memo"
        m.dueMtk > 0 -> "credit_memo"
        m.drpDiscountMtk > 0 -> "drp_memo"
        m.offerDiscountMtk > 0 -> "offer_memo"
        else -> "cash_memo"
    }

    fun memo(m: StoredMemo, names: PrintNames, reprintNo: Int = 0): MemoPrint = MemoPrint(
        kind = kindOf(m), memoNo = m.memoNo, committedAtEpochMs = Instant.parse(m.committedAtIso).toEpochMilli(),
        outlet = names.outlet(m.outletId), sr = names.sr, route = names.route,
        lines = m.lines.map { MemoPrintLine(names.sku(it.skuId), it.qtyBase, it.grossMtk) },
        discounts = m.discounts.map { MemoPrintDiscount(it.skuId?.let(names.sku).orEmpty(), it.qtyBase, it.valueMtk) },
        grossMtk = m.grossMtk, offerDiscountMtk = m.offerDiscountMtk, drpDiscountMtk = m.drpDiscountMtk, qcDeductionMtk = m.qcDeductionMtk,
        roundAdjMtk = m.roundAdjMtk, netMtk = m.netMtk, paidMtk = m.paidMtk, dueMtk = m.dueMtk, isCredit = m.dueMtk > 0,
        reprintNo = reprintNo, supersedesMemoNo = m.supersedesMemoNo,
    )

    fun daySummary(s: DaySummary, qcMtk: Long, dueMtk: Long, nowEpochMs: Long, names: PrintNames): DaySummaryPrint = DaySummaryPrint(
        printedAtEpochMs = nowEpochMs, sr = names.sr, route = names.route,
        lines = s.skus.map { DaySummaryLine(names.sku(it.skuId), it.memoCount, it.qtyBase, it.valueMtk) },
        grossMtk = s.grossMtk, discountMtk = s.discountAndOthersMtk - qcMtk, qcMtk = qcMtk, netMtk = s.grandTotalMtk, dueMtk = dueMtk, memoCount = s.memoCount,
    )
}
