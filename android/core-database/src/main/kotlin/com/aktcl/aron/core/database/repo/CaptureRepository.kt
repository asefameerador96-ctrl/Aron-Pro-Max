package com.aktcl.aron.core.database.repo

import androidx.room.withTransaction
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.entity.TaskEventEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoDiscountEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.QcLineEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.contract.OutletRequestType
import com.aktcl.aron.core.database.record.RecordMapping
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A complete sale as the SR saves it: the memo with its lines, discount lines and QC lines (docs/24 s4.2, s7.4). */
data class SaleCapture(
    val memo: MemoEntity,
    val lines: List<MemoLineEntity>,
    val discounts: List<MemoDiscountEntity> = emptyList(),
    val qcLines: List<QcLineEntity> = emptyList(),
    val editFix: GeoFixEntity? = null,
)

/**
 * Commits captures (docs/24 s4.1 item 1, s5.2): the domain rows and their outbox rows in ONE Room transaction, so a kill
 * at any point leaves either the whole capture with its outbox rows or nothing. The network is never touched here.
 * A duplicate client UUID aborts the whole transaction.
 */
class CaptureRepository(
    private val db: AronDatabase,
    private val nowIso: () -> String = { ISO_MILLIS.format(Instant.now()) },
) {
    private val capture = db.captureDao()
    private val outbox = db.outboxDao()

    suspend fun recordAttendance(event: AttendanceEventEntity, fix: GeoFixEntity) = db.withTransaction {
        requireUuids(event.clientUuid, fix.clientUuid)
        requireFixOf(fix, event.clientUuid, event.fixClientUuid)
        capture.insertFix(fix)
        capture.insertAttendance(event)
        outbox.insert(listOf(RecordMapping.attendance(event, fix, nowIso())))
    }

    /** One Save of the Stock screen: one event per SKU, all or nothing. */
    suspend fun recordStock(movements: List<StockMovementEntity>) {
        require(movements.isNotEmpty()) { "nothing to save" }
        requireUuids(*movements.map { it.clientUuid }.toTypedArray())
        db.withTransaction {
            capture.insertStock(movements)
            val at = nowIso()
            outbox.insert(movements.map { RecordMapping.stock(it, at) })
        }
    }

    suspend fun recordVisitOpen(visit: VisitEntity, fix: GeoFixEntity) = db.withTransaction {
        requireUuids(visit.clientUuid, fix.clientUuid)
        requireRoute(visit.meta)
        requireOpenRouteDay(visit.meta)
        requireFixOf(fix, visit.clientUuid, visit.fixClientUuid)
        capture.insertFix(fix)
        capture.insertVisit(visit)
        outbox.insert(listOf(RecordMapping.visit(visit, fix, nowIso())))
    }

    /**
     * Saves a memo with everything that belongs to it. Outbox order inside the transaction follows the family ranks
     * (memo, then lines and discounts, then QC lines), so the batch carries parents before children (s4.2).
     */
    suspend fun recordSale(sale: SaleCapture) {
        val m = sale.memo
        require(sale.lines.all { it.memoClientUuid == m.clientUuid }) { "every line must belong to the memo" }
        require(sale.discounts.all { it.memoClientUuid == m.clientUuid }) { "every discount line must belong to the memo" }
        require(sale.qcLines.all { it.visitClientUuid == m.visitClientUuid }) { "QC lines must belong to the memo's visit" }
        // QC lines saved with the memo are deducted on it (s7.4); a line for another memo would break qc_deduction.
        require(sale.qcLines.all { it.memoClientUuid == m.clientUuid && it.appliedToMemo }) { "QC lines saved with a memo must be applied to that memo" }
        require((m.editFixClientUuid == null) == (sale.editFix == null)) { "an edited memo carries its edit fix, and only then" }
        // An edit is a new memo that supersedes the old one, with a reason and a fresh fix for the server's geofence re-check.
        require((m.supersedesClientUuid != null) == (sale.editFix != null && m.editReasonCode != null)) {
            "an edited memo carries supersedes_client_uuid, edit_reason_code and its edit fix together"
        }
        requireUuids(
            m.clientUuid, m.visitClientUuid, *sale.lines.map { it.clientUuid }.toTypedArray(),
            *sale.discounts.map { it.clientUuid }.toTypedArray(), *sale.qcLines.map { it.clientUuid }.toTypedArray(),
        )
        m.supersedesClientUuid?.let { requireUuids(it) }
        sale.editFix?.let { requireUuids(it.clientUuid) }
        (listOf(m.meta) + sale.lines.map { it.meta } + sale.discounts.map { it.meta } + sale.qcLines.map { it.meta }).forEach(::requireRoute)
        require(m.lineCount == sale.lines.size && m.discountLineCount == sale.discounts.size && m.qcLineCount == sale.qcLines.size) {
            "memo counts must match the lines committed with it"
        }
        db.withTransaction {
            requireOpenRouteDay(m.meta)
            val visit = checkNotNull(capture.visit(m.visitClientUuid)) { "memo ${m.memoNo} has no visit on this phone" }
            require(visit.outletId == m.outletId) { "memo ${m.memoNo} is for another outlet than its visit" }
            sale.editFix?.let {
                requireFixOf(it, m.clientUuid, m.editFixClientUuid)
                capture.insertFix(it)
            }
            capture.insertMemo(m)
            capture.insertMemoLines(sale.lines)
            capture.insertMemoDiscounts(sale.discounts)
            capture.insertQcLines(sale.qcLines)
            val at = nowIso()
            val family = m.visitClientUuid
            outbox.insert(
                listOf(RecordMapping.memo(m, sale.editFix, at)) +
                    sale.lines.sortedBy { it.lineNo }.map { RecordMapping.memoLine(it, family, at) } +
                    sale.discounts.map { RecordMapping.memoDiscount(it, family, at) } +
                    sale.qcLines.map { RecordMapping.qcLine(it, at) },
            )
        }
    }

    suspend fun recordVisitClose(close: VisitCloseEntity) = db.withTransaction {
        requireUuids(close.clientUuid, close.visitClientUuid)
        requireRoute(close.meta)
        requireOpenRouteDay(close.meta)
        checkNotNull(capture.visit(close.visitClientUuid)) { "visit_close without its visit" }
        capture.insertVisitClose(close)
        outbox.insert(listOf(RecordMapping.visitClose(close, nowIso())))
    }

    /**
     * A sale numbered on this phone (F-SYS-027): the number is reserved in its own committed step, then the sale is written
     * with it in one transaction. Returns the memo number. If the save fails the number is burned, never reused.
     */
    suspend fun recordNumberedSale(sale: SaleCapture, numbering: MemoNumbering): String {
        val memoNo = MemoNumbers(db).reserve(sale.memo.meta.businessDate, numbering)
        recordSale(sale.copy(memo = sale.memo.copy(memoNo = memoNo)))
        return memoNo
    }

    /** A due collected against an earlier memo: its own family (a signed header record, docs/24 s4.2). */
    suspend fun recordDueCollection(due: DueCollectionEntity, fix: GeoFixEntity? = null) {
        requireUuids(due.clientUuid, due.againstMemoClientUuid)
        due.visitClientUuid?.let { requireUuids(it); requireRoute(due.meta) }
        require(due.amountMtk >= 10) { "amount_mtk is at least 10" }
        require(due.paymentMode == "cash") { "payment_mode is cash (contract)" }
        require(due.amountMtk <= due.outstandingBeforeMtk) { "a collection cannot exceed what is outstanding" }
        require(due.isFullSettlement == (due.amountMtk == due.outstandingBeforeMtk)) { "full settlement means the whole outstanding" }
        require((fix == null) == (due.fixClientUuid == null)) { "the record references its fix, and only then" }
        db.withTransaction {
            due.visitClientUuid?.let { checkNotNull(capture.visit(it)) { "due collection for a visit not on this phone" } }
            if (due.meta.routeId != null) requireOpenRouteDay(due.meta)
            fix?.let { requireFixOf(it, due.clientUuid, due.fixClientUuid); capture.insertFix(it) }
            capture.insertDueCollection(due)
            outbox.insert(listOf(RecordMapping.dueCollection(due, fix, nowIso())))
        }
    }

    /** An outlet skipped without a visit: no fix (F-SR-057). */
    suspend fun recordVisitSkip(skip: VisitSkipEntity) = db.withTransaction {
        requireUuids(skip.clientUuid)
        requireRoute(skip.meta)
        require(REASON_CODE.matches(skip.reasonCode)) { "reason_code must match ${REASON_CODE.pattern}" }
        requireOpenRouteDay(skip.meta)
        capture.insertVisitSkip(skip)
        outbox.insert(listOf(RecordMapping.visitSkip(skip, nowIso())))
    }

    /**
     * Sales Submit: the route-day's last record. Nothing of that route-day (visits, sales, closes, skips, dues) is committed
     * after it until the server reopens the day ([reopenRouteDay]); one submit per cycle.
     */
    suspend fun recordDaySubmit(submit: DaySubmitEntity) = db.withTransaction {
        requireUuids(submit.clientUuid)
        require(submit.scope in setOf("route_day", "supervisor_day")) { "scope is route_day or supervisor_day" }
        if (submit.scope == "route_day") requireRoute(submit.meta)
        require(submit.submitCycle in 1..50) { "submit_cycle is 1..50" }
        require(listOf(submit.rejectedCount, submit.quarantinedCount, submit.pendingCount, submit.retailersWithDues).all { it >= 0 }) { "counts are not negative" }
        require(submit.duesOutstandingMtk >= 0) { "dues_outstanding_mtk is not negative" }
        check(capture.daySubmitsFor(submit.meta.businessDate, submit.meta.routeId).none { it.scope == submit.scope && it.submitCycle == submit.submitCycle }) {
            "this route-day is already submitted in cycle ${submit.submitCycle}"
        }
        requireOpenRouteDay(submit.meta)
        capture.insertDaySubmit(submit)
        outbox.insert(listOf(RecordMapping.daySubmit(submit, nowIso())))
    }

    /** A new-outlet, edit, relocation or closure request with the fix taken for it. */
    suspend fun recordOutletRequest(request: OutletChangeRequestEntity, fix: GeoFixEntity) = db.withTransaction {
        requireUuids(request.clientUuid, fix.clientUuid)
        require(OutletRequestType.entries.any { it.wire == request.requestType }) { "request_type ${request.requestType} is not in the contract" }
        require((request.requestType == OutletRequestType.NEW.wire) == (request.outletId == null)) { "outlet_id is null exactly for a new outlet" }
        val photos = Json.decodeFromString(ListSerializer(String.serializer()), request.photoUuidsJson)
        require(photos.size <= 4) { "at most 4 photos" }
        requireUuids(*photos.toTypedArray())
        request.originVisitClientUuid?.let { requireUuids(it); checkNotNull(capture.visit(it)) { "origin visit not on this phone" } }
        requireFixOf(fix, request.clientUuid, request.fixClientUuid)
        capture.insertFix(fix)
        capture.insertOutletRequest(request)
        outbox.insert(listOf(RecordMapping.outletRequest(request, fix, nowIso())))
    }

    /** Resolves a task: the local task turns `completed` and its `task_event` is queued, in one transaction. */
    suspend fun resolveTask(event: TaskEventEntity, resolvedAt: String) = db.withTransaction {
        requireUuids(event.clientUuid, event.taskUuid)
        require(event.event == "resolved") { "resolveTask writes a resolved event" }
        check(db.referenceDao().completeTask(event.taskUuid, resolvedAt) == 1) { "task ${event.taskUuid} is not on this phone" }
        capture.insertTaskEvent(event)
        outbox.insert(listOf(RecordMapping.taskEvent(event, nowIso())))
    }

    /**
     * The server reopened a submitted route-day (a submit void): captures of [businessDate] and [routeId] are allowed again
     * until a submit of a later cycle than [voidedCycle].
     */
    suspend fun reopenRouteDay(businessDate: String, routeId: Long?, voidedCycle: Int) =
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(reopenKey(businessDate, routeId), voidedCycle.toString()))

    /** Refuses a route-day capture after its Sales Submit (docs/24 s4.2 rule 2: day_submit is the route-day's last record). */
    private suspend fun requireOpenRouteDay(meta: CaptureMeta) {
        val latest = capture.daySubmitsFor(meta.businessDate, meta.routeId).maxOfOrNull { it.submitCycle } ?: return
        val reopened = db.referenceDao().meta(reopenKey(meta.businessDate, meta.routeId))?.toIntOrNull() ?: 0
        check(latest <= reopened) { "route-day ${meta.businessDate} is submitted; nothing more can be captured" }
    }

    private fun reopenKey(businessDate: String, routeId: Long?) = "route_day.reopened.$businessDate.${routeId ?: "none"}"

    /** Device ids are lower-case UUID v4 (contract `Uuid`); anything else would be rejected on upload as schema_invalid. */
    private fun requireUuids(vararg ids: String) {
        ids.forEach { require(ClientIds.isUuidV4(it)) { "not a lower-case UUID v4: $it" } }
    }

    /** `route_id` is required on visit families and route-day records (docs/24 s4.3). */
    private fun requireRoute(meta: CaptureMeta) {
        require(meta.routeId != null) { "visit-family records carry the route worked (route_id)" }
    }

    private fun requireFixOf(fix: GeoFixEntity, ownerUuid: String, referencedFix: String?) {
        require(fix.ownerClientUuid == ownerUuid) { "the fix must belong to the record that took it" }
        require(referencedFix == fix.clientUuid) { "the record must reference its fix" }
    }

    private companion object {
        val REASON_CODE = Regex("^[a-z][a-z0-9_]{1,40}$")

        /** RFC 3339 UTC with exactly three fraction digits, as on the wire (docs/24 s3.1 item 5). */
        val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
