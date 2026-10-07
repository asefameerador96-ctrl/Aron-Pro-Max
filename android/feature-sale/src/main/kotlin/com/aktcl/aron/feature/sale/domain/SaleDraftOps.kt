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
        return d.copy(lines = lines, zeroSale = if (lines.isNotEmpty()) false else d.zeroSale)
    }

    fun setSlide(d: SaleDraft, skuId: Long, emptyPackets: Long): SaleDraft {
        require(emptyPackets in 0..MAX_QTY) { "empty packets out of range" }
        check(!d.qcCompleted) { "sale is locked after QC" }
        val rest = d.slide.filterNot { it.skuId == skuId }
        return d.copy(slide = if (emptyPackets == 0L) rest else (rest + SlideEntry(skuId, emptyPackets)).sortedBy { it.skuId })
    }

    /** Replaces the QC entries of one SKU and fault type; 0 removes it. */
    fun setQc(d: SaleDraft, entry: QcEntry): SaleDraft {
        require(entry.defectQty in 0..MAX_QTY) { "defect quantity out of range" }
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

    /** Completing QC locks further edits at this outlet. */
    fun completeQc(d: SaleDraft): SaleDraft = d.copy(qcCompleted = true)
}
