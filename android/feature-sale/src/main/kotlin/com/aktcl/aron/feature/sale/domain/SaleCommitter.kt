package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.SaleCapture

/**
 * Memo numbers per docs/24 s7.5. REQUEST: the persistent counter belongs to F-SYS-027 (android-core); until it lands
 * the app wires a fake. `next` must be called inside no other transaction and must never return a number twice.
 */
fun interface MemoNumbers { suspend fun next(businessDate: String): String }

/** The envelope facts of "now" (trusted clock, boot count, bundle/config versions); supplied by the app. */
fun interface CaptureMetaSource { fun meta(businessDate: String, routeId: Long?): CaptureMeta }

/** Thrown when review has problems; nothing is written. */
class ReviewNotCommittable(val problems: List<ReviewProblem>) : IllegalStateException("review has problems: $problems")

/** Result of a commit. */
data class CommittedSale(val memoUuid: String, val memoNo: String, val netMtk: Long, val dueMtk: Long)

/**
 * Turns a reviewed draft into the immutable memo and commits it with its outbox records in ONE Room transaction through
 * [CaptureRepository.recordSale] (docs/24 s4.1). A zero sale writes a memo with line_count 0 that still consumes a number
 * (F-SR-029). A replay after a kill is safe: the memo UUID is fixed in the draft, a second insert aborts without writing.
 */
class SaleCommitter(
    private val repo: CaptureRepository,
    private val numbers: MemoNumbers,
    private val metaSource: CaptureMetaSource,
    private val nowIso: () -> String,
    private val newUuid: () -> String = ClientIds::newUuid,
) {
    suspend fun commit(
        draft: SaleDraft,
        catalog: Map<Long, SaleSku>,
        editFix: GeoFixEntity? = null,
        qcCapMtkBySku: Map<Long, Long> = emptyMap(),
    ): CommittedSale {
        val review = SaleReviewCalculator.review(draft, catalog, qcCapMtkBySku)
        if (!review.canCommit) throw ReviewNotCommittable(review.problems)
        val meta = metaSource.meta(draft.businessDate, draft.routeId)
        val memoNo = numbers.next(draft.businessDate)
        val t = review.totals
        val s = review.settlement
        val lines = review.lines.mapIndexed { i, l ->
            MemoLineEntity(
                clientUuid = newUuid(), meta = meta, memoClientUuid = draft.memoUuid, lineNo = i + 1, skuId = l.sku.skuId,
                lineKind = "sale", qtyEntered = l.entry.qtyEntered, unitEntered = l.entry.unit, packFactor = l.sku.packFactor,
                qtyBase = l.qtyBase, priceType = draft.priceType, priceValidFrom = l.sku.priceValidFrom,
                basePriceMtk = l.sku.unitPriceMtk, pricePerQty = l.sku.pricePerQty, grossMtk = l.memoLine.grossMtk,
            )
        }
        val discounts = review.drp.map {
            MemoDiscountEntity(
                clientUuid = newUuid(), meta = meta, memoClientUuid = draft.memoUuid, kind = "drp", skuId = it.skuId,
                qtyBase = it.rewardQtyBase, valueMtk = it.valueMtk, basisQtyBase = it.emptyPackets,
            )
        }
        val qcLines = review.qc.map {
            QcLineEntity(
                clientUuid = newUuid(), meta = meta, visitClientUuid = draft.visitUuid, memoClientUuid = draft.memoUuid,
                appliedToMemo = true, skuId = it.entry.skuId, faultTypeCode = it.entry.faultTypeCode, faultGroup = it.entry.faultGroup,
                qtyBase = it.entry.defectQty, unitPriceMtk = it.unitPriceMtk, settlementMtk = it.settlementMtk,
            )
        }
        val edit = draft.edit
        val memo = MemoEntity(
            clientUuid = draft.memoUuid, meta = meta, visitClientUuid = draft.visitUuid, outletId = draft.outletId, memoNo = memoNo,
            memoKind = if (lines.isEmpty()) "zero_sale" else "sale", committedAt = nowIso(), priceListDate = draft.businessDate, priceType = draft.priceType,
            grossMtk = t.grossMtk, offerDiscountMtk = t.offerDiscountMtk, drpDiscountMtk = t.drpDiscountMtk,
            qcDeductionMtk = t.qcDeductionMtk, roundAdjMtk = t.roundAdjMtk, netMtk = t.netMtk, paidMtk = s.paidMtk, dueMtk = s.dueMtk,
            isCredit = s.isCredit, lineCount = lines.size, discountLineCount = discounts.size, qcLineCount = qcLines.size,
            supersedesClientUuid = edit?.supersedesMemoUuid, editReasonCode = edit?.reasonCode,
            editFixClientUuid = editFix?.clientUuid,
        )
        repo.recordSale(SaleCapture(memo, lines, discounts, qcLines, editFix))
        return CommittedSale(draft.memoUuid, memoNo, t.netMtk, s.dueMtk)
    }
}
