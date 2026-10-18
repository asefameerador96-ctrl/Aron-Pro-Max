package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.rules.QtyUnit

/** Immutable edits of a [SaleDraft]; every call returns a new draft. A quantity of 0 removes the line. */
object SaleDraftOps {
    const val MAX_QTY: Long = 1_000_000L

    fun setQuantity(d: SaleDraft, skuId: Long, qtyEntered: Long, unit: QtyUnit): SaleDraft {
        require(qtyEntered in 0..MAX_QTY) { "quantity out of range" }
        check(!d.qcCompleted) { "sale is locked after QC" }
        val rest = d.lines.filterNot { it.skuId == skuId }
        val lines = if (qtyEntered == 0L) rest else (rest + DraftLine(skuId, qtyEntered, unit.wire)).sortedBy { it.skuId }
        // QC belongs to a SKU on the memo: removing the line removes its QC rows, so no deduction is left that no screen shows
        var qc = if (qtyEntered == 0L) d.qc.filterNot { it.skuId == skuId } else d.qc
        // a QC-only sale (D-18) had QC on SKUs with no line; once a line is sold the rows follow the lines again
        if (d.zeroSale && lines.isNotEmpty()) qc = qc.filter { q -> lines.any { it.skuId == q.skuId } }
        return d.copy(lines = lines, qc = qc, zeroSale = if (lines.isNotEmpty()) false else d.zeroSale)
    }

    fun setSlide(d: SaleDraft, skuId: Long, emptyPackets: Long): SaleDraft {
        require(emptyPackets in 0..MAX_QTY) { "empty packets out of range" }
        check(!d.qcCompleted) { "sale is locked after QC" }
        val rest = d.slide.filterNot { it.skuId == skuId }
        return d.copy(slide = if (emptyPackets == 0L) rest else (rest + SlideEntry(skuId, emptyPackets)).sortedBy { it.skuId })
    }

    /** Replaces the QC entries of one SKU and fault type; 0 removes it. */
    fun setQc(d: SaleDraft, entry: QcEntry): SaleDraft {
        require(entry.defectQty in 0..QcEntryModel.MAX_QTY) { "defect quantity out of range" }
        // the server takes only the six app fault types and the group that belongs to each (the contract enum is MFC or MKT)
        require(QcEntryModel.groupOf(entry.faultTypeCode) == entry.faultGroup) { "unknown QC fault type or group" }
        check(!d.qcCompleted) { "QC is locked once completed" }
        val rest = d.qc.filterNot { it.skuId == entry.skuId && it.faultTypeCode == entry.faultTypeCode }
        return d.copy(qc = if (entry.defectQty == 0L) rest else rest + entry)
    }

    /** Credit sale: the SR states how much was paid now; null returns to paid in full. Range checks happen in review. */
    fun setPaid(d: SaleDraft, paidMtk: Long?): SaleDraft {
        require(paidMtk == null || paidMtk >= 0) { "paid must be >= 0" }
        return d.copy(paidMtk = paidMtk)
    }

    /** Proceed with no SKU: the SR confirms a zero sale (F-SR-029). Refused when lines exist. */
    fun confirmZeroSale(d: SaleDraft): SaleDraft {
        check(d.lines.isEmpty()) { "a zero sale has no lines" }
        return d.copy(zeroSale = true, paidMtk = null)
    }

    /**
     * QC on a zero sale (D-18, owner ruling 2026-10-18): the memo has no sale lines and the QC rows are its only content. It is
     * a zero sale that the SR confirmed by choosing Product QC; the rows may name any SKU of the catalog, and the net may go
     * negative (a credit) only while the gross is zero (the review enforces that).
     */
    fun startQcOnly(d: SaleDraft): SaleDraft = confirmZeroSale(d)

    /** Puts back the QC rows saved when the QC screen opened (Cancel discards what was typed since). */
    fun replaceQc(d: SaleDraft, entries: List<QcEntry>): SaleDraft {
        check(!d.qcCompleted) { "QC is locked once completed" }
        return d.copy(qc = entries)
    }

    /** What survives a restart: QC that was never submitted is dropped; a submitted one keeps only rows of the six app types. */
    fun sanitizeQc(d: SaleDraft): SaleDraft {
        if (d.qc.isEmpty()) return d
        if (!d.qcCompleted) return d.copy(qc = emptyList())
        val valid = d.qc.filter { QcEntryModel.groupOf(it.faultTypeCode) == it.faultGroup && it.defectQty in 1..QcEntryModel.MAX_QTY }
        return if (valid.size == d.qc.size) d else d.copy(qc = valid)
    }

    /** Completing QC locks further edits at this outlet. */
    fun completeQc(d: SaleDraft): SaleDraft = d.copy(qcCompleted = true)
}

/** Parses the paid amount typed on the credit prompt: digits with at most two decimals, to milli-taka; null when invalid. */
object PaidInput {
    private val SHAPE = Regex("^\\d{1,9}(\\.\\d{1,2})?$")
    fun parse(text: String): Long? {
        val t = text.trim().map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString("")
        if (!SHAPE.matches(t)) return null
        val (whole, frac) = t.split('.').let { it[0] to (it.getOrNull(1) ?: "").padEnd(2, '0') }
        return whole.toLong() * 1000 + frac.toLong() * 10
    }
}
