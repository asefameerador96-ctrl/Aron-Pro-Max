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

    /** Every memo on the phone (all days it keeps, superseded versions too) with the collections recorded against each: (captured-at ISO, amount in milli-taka). */
    suspend fun allWithCollections(): List<Pair<StoredMemo, List<Pair<String, Long>>>> =
        dao.memosBetween("0000-01-01", "9999-12-31")
            .map { m -> toStored(m) to dao.dueCollectionsOf(m.clientUuid).map { it.meta.capturedAt to it.amountMtk } }

    /** Collections against any memo of the date (a collection may be recorded on a later day than the memo). */
    override suspend fun collections(businessDate: String): List<StoredCollection> =
        dao.memosOn(businessDate).flatMap { m -> dao.dueCollectionsOf(m.clientUuid) }.map { StoredCollection(it.againstMemoClientUuid, it.amountMtk, it.againstMemoBusinessDate) }

    private suspend fun toStored(m: MemoEntity): StoredMemo = StoredMemo(
        memoUuid = m.clientUuid, memoNo = m.memoNo, outletId = m.outletId, businessDate = m.meta.businessDate, committedAtIso = m.committedAt,
        supersedesUuid = m.supersedesClientUuid,
        lines = dao.linesOf(m.clientUuid).map { MemoItem(it.skuId, it.qtyBase, it.basePriceMtk, it.grossMtk, it.qtyEntered, it.unitEntered) },
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

    /** tso-manual X4a (fix round): the route-day check of [write] with the same meta ([CaptureRepository.acceptsDueCollection]). */
    override suspend fun accepts(outletId: Long): Boolean = repo.acceptsDueCollection(meta())
}

/**
 * tso-manual X4a (fix round, R-W-11 (3)): the SR and AMO kits' Mark Paid writer. The collection attaches to the outlet's
 * last visit today and carries THAT visit's route (a visit always has one), so the AMO's Mark Paid outside a call, where
 * the AMO has no route of its own, is a valid visit-family record on the route the memo was sold on. With no visit of the
 * outlet today it carries [metaFor]`(null)` (the caller's own route, if any) and no visit, as before.
 *
 * [accepts] asks [CaptureRepository.acceptsDueCollection] with the very meta [write] would use, so the memo screen draws
 * Mark Paid only when the write would be accepted: no Final Submit lock and no standing Sales Submit on that route-day.
 */
class VisitDueCollectionWriter(
    private val db: AronDatabase,
    private val repo: CaptureRepository,
    /** The capture meta for a route: the visit's route, or null for the caller's own (SR: the day's route; AMO: the open call's, if any). */
    private val metaFor: (routeId: Long?) -> CaptureMeta,
    private val businessDate: () -> String,
    private val newUuid: () -> String = ClientIds::newUuid,
) : DueCollectionWriter {
    /** The meta and the visit of a collection for [outletId]: the one rule [write] and [accepts] share. */
    private suspend fun target(outletId: Long): Pair<CaptureMeta, String?> {
        val visit = db.captureDao().visitsOn(businessDate()).lastOrNull { it.outletId == outletId }
        return metaFor(visit?.meta?.routeId) to visit?.clientUuid
    }

    override suspend fun write(draft: DueCollectionDraft) {
        val (meta, visit) = target(draft.outletId)
        repo.recordDueCollection(
            DueCollectionEntity(
                clientUuid = newUuid(), meta = meta, outletId = draft.outletId, againstMemoClientUuid = draft.againstMemoUuid,
                againstMemoNo = draft.againstMemoNo, againstMemoBusinessDate = draft.againstMemoBusinessDate, amountMtk = draft.amountMtk,
                isFullSettlement = draft.isFullSettlement, outstandingBeforeMtk = draft.outstandingBeforeMtk, visitClientUuid = visit,
            ),
        )
    }

    override suspend fun accepts(outletId: Long): Boolean = repo.acceptsDueCollection(target(outletId).first)
}
