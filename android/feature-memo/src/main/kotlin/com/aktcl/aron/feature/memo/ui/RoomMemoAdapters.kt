package com.aktcl.aron.feature.memo.ui

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.feature.memo.domain.DueCollectionDraft
import com.aktcl.aron.feature.memo.domain.MemoDiscountItem
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.MemoQcItem
import com.aktcl.aron.feature.memo.domain.StoredCollection
import com.aktcl.aron.feature.memo.domain.StoredMemo
import java.time.LocalDate

/** Room reads of the day's memos and the collections against them (production [MemoStore]). */
class RoomMemoStore(private val db: AronDatabase) : MemoStore {
    private val dao = db.captureDao()

    override suspend fun memos(businessDate: String): List<StoredMemo> = dao.memosOn(businessDate).map { toStored(it) }

    /** Memos of the last [days] days (Sale History reads the local 7-day window). */
    suspend fun history(businessDate: String, days: Int = 7): List<StoredMemo> {
        val from = LocalDate.parse(businessDate).minusDays((days - 1).toLong()).toString()
        return dao.memosBetween(from, businessDate).map { toStored(it) }
    }

    /** Collections against any memo of the date (a collection may be recorded on a later day than the memo). */
    override suspend fun collections(businessDate: String): List<StoredCollection> =
        dao.memosOn(businessDate).flatMap { m -> dao.dueCollectionsOf(m.clientUuid) }.map { StoredCollection(it.againstMemoClientUuid, it.amountMtk, it.againstMemoBusinessDate) }

    private suspend fun toStored(m: MemoEntity): StoredMemo = StoredMemo(
        memoUuid = m.clientUuid, memoNo = m.memoNo, outletId = m.outletId, businessDate = m.meta.businessDate, committedAtIso = m.committedAt,
        supersedesUuid = m.supersedesClientUuid,
        lines = dao.linesOf(m.clientUuid).map { MemoItem(it.skuId, it.qtyBase, it.basePriceMtk, it.grossMtk) },
        discounts = dao.discountsOf(m.clientUuid).map { MemoDiscountItem(it.skuId, it.qtyBase ?: 0L, it.valueMtk, it.kind) },
        qcLines = dao.qcLinesOf(m.clientUuid).map { MemoQcItem(it.skuId, it.qtyBase, it.settlementMtk) },
        grossMtk = m.grossMtk, offerDiscountMtk = m.offerDiscountMtk, drpDiscountMtk = m.drpDiscountMtk, qcDeductionMtk = m.qcDeductionMtk,
        netMtk = m.netMtk, paidMtk = m.paidMtk, dueMtk = m.dueMtk, printedAtIso = m.printedAt, roundAdjMtk = m.roundAdjMtk,
        supersedesMemoNo = m.supersedesClientUuid?.let { dao.memo(it)?.memoNo },
    )
}

/** Writes a Mark paid as a `due_collection` with its outbox row in one transaction ([CaptureRepository.recordDueCollection]). */
class RoomDueCollectionWriter(
    private val repo: CaptureRepository,
    private val meta: () -> CaptureMeta,
    private val visitUuidOf: suspend (outletId: Long) -> String? = { null },
    private val newUuid: () -> String = ClientIds::newUuid,
) : DueCollectionWriter {
    override suspend fun write(draft: DueCollectionDraft) {
        repo.recordDueCollection(
            DueCollectionEntity(
                clientUuid = newUuid(), meta = meta(), outletId = draft.outletId, againstMemoClientUuid = draft.againstMemoUuid,
                againstMemoNo = draft.againstMemoNo, againstMemoBusinessDate = draft.againstMemoBusinessDate, amountMtk = draft.amountMtk,
                isFullSettlement = draft.isFullSettlement, outstandingBeforeMtk = draft.outstandingBeforeMtk, visitClientUuid = visitUuidOf(draft.outletId),
            ),
        )
    }
}
