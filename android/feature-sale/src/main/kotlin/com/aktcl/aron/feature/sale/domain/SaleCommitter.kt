package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.SaleCapture

/**
 * Memo numbers per docs/24 s7.5. REQUEST: the persistent counter belongs to F-SYS-027 (android-core); until it lands
 * the app wires a fake. `next` must be called inside no other transaction and must never return a number twice.
 */
fun interface MemoNumbers {
    /** IDEMPOTENT per [memoUuid]: a retry of the same memo gets the same number, so a rolled-back commit leaves no gap. */
    suspend fun next(businessDate: String, memoUuid: String): String
}

/** The envelope facts of "now" (trusted clock, boot count, bundle/config versions); supplied by the app. */
fun interface CaptureMetaSource {
    fun meta(businessDate: String, routeId: Long?): CaptureMeta

    companion object {
        /**
         * D-LEAD-PREREQ-03 ruling 2 (round-2 review, edit across midnight): a record of a call or of a memo edit carries the business date of the
         * VISIT or MEMO it belongs to, never the clock's date, so the check-in check, the stock given back, the stock check and the saved record read ONE
         * date. [clock] gives the envelope of "now" (its date is the clock's). A date that is not a calendar date, or is AFTER the clock's, falls back
         * to the clock's: a record is never dated in the future (the committer then refuses an EDIT whose date the clock cannot carry, so a clock set back never saves an edit under another day).
         * SERVER, for a D-dated record captured on D+1: accepted and counted in D under accept_and_flag (the default); counted late (`late_rows_after_sales_submit` / `late_rows_after_final`) after a stored D submit or Final Submit; under quarantine HELD `after_final_submit` (captured before the lock, no clock offset, or the midnight job closed after the visit opened) or else refused `day_final_submitted` (final); under reject refused (final). An offline phone cannot know of the 00:00 job close, so under reject a straddling sale may print and then read "NOT on the server". The server accepts a D-dated record captured on D+1 (IngestService step 3 judges
         * `business_date` only against the backdate floor and "after today", and `captured_at` only against "more than 10 minutes ahead").
         */
        fun dated(clock: (Long?) -> CaptureMeta) = CaptureMetaSource { date, routeId ->
            val now = clock(routeId)
            if (date == now.businessDate || !DATE.matches(date) || date > now.businessDate) now else now.copy(businessDate = date)
        }

        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}

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
    /** Looks a memo up by uuid (production: `captureDao().memo`); lets a relaunch notice a commit that landed before the kill. */
    private val findMemo: suspend (String) -> MemoEntity? = { null },
    /** `cfg.memo.edit_reasons`; null accepts any well-formed code. */
    private val editReasons: Set<String>? = null,
    /**
     * Production numbering (F-SYS-027): the repository reserves the number in its own committed step and saves the memo
     * with it (`recordNumberedSale`); a failed save burns the number, which is never reused. When null, [numbers] is used.
     */
    private val numbering: MemoNumbering? = null,
) {
    /**
     * The business date the memo of a sale of [draftDate] will carry, which is the date the saving transaction reads the stock for. The screen reads
     * the stock for THIS date (never a date the record will not carry), so the row, the review and the refusal always speak about the same day.
     * It follows whatever date the meta source gives the record (today's clock in the app kits; the visit's date once the record's own date rules).
     */
    fun stockDate(draftDate: String, routeId: Long?): String = metaSource.meta(draftDate, routeId).businessDate

    /** The result of an already committed memo of this draft, or null. */
    suspend fun existing(memoUuid: String): CommittedSale? =
        findMemo(memoUuid)?.let { CommittedSale(it.clientUuid, it.memoNo, it.netMtk, it.dueMtk) }

    suspend fun commit(
        draft: SaleDraft,
        catalog: Map<Long, SaleSku>,
        editFix: GeoFixEntity? = null,
        qcCapMtkBySku: Map<Long, Long> = emptyMap(),
    ): CommittedSale {
        val review = SaleReviewCalculator.review(draft, catalog, qcCapMtkBySku)
        if (!review.canCommit) throw ReviewNotCommittable(review.problems)
        // Everything that can fail is checked BEFORE a memo number is taken.
        draft.edit?.let { checkEdit(draft, it, editFix) } ?: require(editFix == null) { "a fix is only for an edit" }
        val meta = metaSource.meta(draft.businessDate, draft.routeId)
        // an edit carries its memo's date or it is not saved: a clock set BACK after the draft opened would date it a day earlier and supersede a memo of another date
        if (draft.edit != null) check(meta.businessDate == draft.businessDate) { "the edit's date ${draft.businessDate} is not the record's date ${meta.businessDate}: the phone clock moved back" }
        val memoNo = if (numbering == null) numbers.next(draft.businessDate, draft.memoUuid) else ""
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
        val capture = SaleCapture(memo, lines, discounts, qcLines, editFix)
        val finalNo = if (numbering != null) repo.recordNumberedSale(capture, numbering, enforceStock = true) else { repo.recordSale(capture, enforceStock = true); memoNo }
        return CommittedSale(draft.memoUuid, finalNo, t.netMtk, s.dueMtk)
    }

    /** Edit rules checkable on the phone (F-SR-033): no QC done, a listed reason, a fix, and a real earlier memo of this outlet and day. */
    private suspend fun checkEdit(draft: SaleDraft, edit: EditContext, editFix: GeoFixEntity?) {
        check(!draft.qcCompleted) { "a sale cannot be edited after QC" }
        require(editFix != null) { "an edit needs its fresh fix" }
        require(editReasons?.contains(edit.reasonCode) ?: REASON_SHAPE.matches(edit.reasonCode)) { "unknown edit reason" }
        require(edit.supersedesMemoUuid != draft.memoUuid) { "a memo cannot supersede itself" }
        val old = checkNotNull(findMemo(edit.supersedesMemoUuid)) { "the memo to edit is not on this phone" }
        require(old.outletId == draft.outletId && old.meta.businessDate == draft.businessDate) { "an edit stays on the same outlet and day" }
    }

    private companion object { val REASON_SHAPE = Regex("^[a-z][a-z0-9_]{1,40}$") }
}
