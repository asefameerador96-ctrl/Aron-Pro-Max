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
import com.aktcl.aron.contract.MediaPurpose
import com.aktcl.aron.contract.OutletRequestType
import com.aktcl.aron.contract.RecordType
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
     * An AV or KV item shown or skipped during an SR call (F-SR-020): in the visit's family, one per item per visit. A
     * second view of the same item in the same visit is not recorded again (returns false), so a replay never doubles it.
     * SR calls only: AMO and TSO visits never carry content (and no points are earned from it here; loyalty is deferred).
     */
    suspend fun recordContentView(view: com.aktcl.aron.core.database.entity.ContentViewEntity): Boolean = db.withTransaction {
        requireUuids(view.clientUuid, view.visitClientUuid)
        requireRoute(view.meta)
        require(com.aktcl.aron.contract.ContentKind.entries.any { it.wire == view.kind }) { "kind ${view.kind} is not in the contract" }
        require(view.outcome in CONTENT_OUTCOMES) { "outcome is one of $CONTENT_OUTCOMES" }
        require(view.sequenceNo in 1..20) { "sequence_no is 1..20" }
        require(view.contentVersion >= 1) { "content_version is at least 1" }
        require(view.durationMs == null || view.durationMs in 0..3_600_000L) { "duration_ms is 0..3600000" }
        requireOpenRouteDay(view.meta)
        requireSrCall(view.visitClientUuid)
        if (capture.contentViewsOf(view.visitClientUuid).any { it.contentId == view.contentId }) return@withTransaction false
        capture.insertContentView(view)
        outbox.insert(listOf(RecordMapping.contentView(view, nowIso())))
        true
    }

    /**
     * One survey answer during an SR call (F-SR-021): in the visit's family, one per question per visit (a second answer is
     * refused with IllegalStateException). Exactly the member of [SurveyResponseEntity.answerType] is set; a `photo_only`
     * answer carries the photo's media uuid, any other may carry one. SR calls only; no points ledger (docs/27).
     */
    suspend fun recordSurveyResponse(answer: com.aktcl.aron.core.database.entity.SurveyResponseEntity) = db.withTransaction {
        requireUuids(answer.clientUuid, answer.visitClientUuid)
        answer.photoUuid?.let { requireUuids(it) }
        requireRoute(answer.meta)
        require(answer.surveyVersion >= 1) { "survey_version is at least 1" }
        val set = listOfNotNull(
            answer.answerBool?.let { "bool" }, answer.answerNum?.let { "num" }, answer.answerOptionCode?.let { "option" }, answer.answerText?.let { "text" },
        )
        when (answer.answerType) {
            "photo_only" -> require(set.isEmpty() && answer.photoUuid != null) { "a photo_only answer is its photo uuid alone" }
            in ReferenceRepository.ANSWER_TYPES -> require(set == listOf(answer.answerType)) { "exactly answer_${answer.answerType} is set" }
            else -> throw IllegalArgumentException("answer_type ${answer.answerType} is not in the contract")
        }
        answer.answerOptionCode?.let { require(REASON_CODE.matches(it)) { "answer_option_code must match ${REASON_CODE.pattern}" } }
        answer.answerText?.let { require(it.length <= 1000) { "answer_text is at most 1000 characters" } }
        answer.answerNum?.let { require(it.isFinite()) { "answer_num is a finite number" } }
        requireOpenRouteDay(answer.meta)
        requireSrCall(answer.visitClientUuid)
        check(capture.surveyResponsesOf(answer.visitClientUuid).none { it.surveyId == answer.surveyId && it.questionId == answer.questionId }) {
            "question ${answer.questionId} of survey ${answer.surveyId} is already answered in this visit"
        }
        capture.insertSurveyResponse(answer)
        outbox.insert(listOf(RecordMapping.surveyResponse(answer, nowIso())))
    }

    /** Content and surveys belong to an SR call on this phone (never AMO or TSO, request android-sr-a-av-kv-survey-data). */
    private suspend fun requireSrCall(visitClientUuid: String) {
        val visit = checkNotNull(capture.visit(visitClientUuid)) { "the visit is not on this phone" }
        require(visit.visitKind == com.aktcl.aron.contract.VisitKind.SR_CALL.wire) { "content and surveys are for SR calls only" }
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
     * Queues a photo's `media_meta` record (F-SYS-010, android-sys): one outbox row, one transaction. The media worker may
     * repeat it after a kill, so a media uuid already in the outbox is a no-op that returns false (never a second record).
     * [MediaMetaCapture.fixClientUuid] names a stored geo_fix row; its payload is built from that row.
     */
    suspend fun recordMediaMeta(m: MediaMetaCapture): Boolean {
        requireUuids(m.mediaUuid, m.refClientUuid)
        m.fixClientUuid?.let { requireUuids(it) }
        require(MediaPurpose.entries.any { it.wire == m.purpose }) { "unknown media purpose ${m.purpose}" }
        require(RecordType.entries.any { it.wire == m.refType }) { "unknown record type ${m.refType}" }
        require(SHA256.matches(m.sha256)) { "sha256 must be 64 lower-case hex characters" }
        require(m.phash == null || PHASH.matches(m.phash)) { "phash must be 16 lower-case hex characters" }
        require(m.bytes in 1..307_200) { "bytes out of range: ${m.bytes}" }
        require(m.width in 1..4096 && m.height in 1..4096) { "size out of range: ${m.width}x${m.height}" }
        require(BLOB_PATH.matches(m.blobPath)) { "blob_path is not photos/<date>/<uuid>/<uuid>.jpg: ${m.blobPath}" }
        return db.withTransaction {
            if (outbox.byClientUuid(m.mediaUuid) != null) return@withTransaction false
            val fix = m.fixClientUuid?.let { checkNotNull(capture.fix(it)) { "geo_fix ${m.fixClientUuid} is not stored" } }
            outbox.insert(listOf(RecordMapping.mediaMeta(m, fix, nowIso())))
            true
        }
    }

    /**
     * Queues a `device_status` record (F-SYS-031, N-026): one outbox row in one transaction. It belongs to no route-day, so a
     * Sales Submit never refuses it.
     */
    suspend fun recordDeviceStatus(clientUuid: String, meta: CaptureMeta, report: com.aktcl.aron.contract.DeviceStatusReport) = db.withTransaction {
        requireUuids(clientUuid)
        outbox.insert(listOf(RecordMapping.deviceStatus(clientUuid, meta, report, nowIso())))
    }

    /**
     * The server reopened a submitted route-day (a submit void): captures of [businessDate] and [routeId] are allowed again
     * until a submit of a later cycle than [voidedCycle].
     */
    suspend fun reopenRouteDay(businessDate: String, routeId: Long?, voidedCycle: Int) = RouteDayLock.reopen(db, businessDate, routeId, voidedCycle)

    /** Refuses a route-day capture after its Sales Submit (docs/24 s4.2 rule 2: day_submit is the route-day's last record). */
    private suspend fun requireOpenRouteDay(meta: CaptureMeta) {
        check(RouteDayLock.isOpen(db, meta)) { "route-day ${meta.businessDate} is submitted; nothing more can be captured" }
    }

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
        val CONTENT_OUTCOMES = setOf("viewed", "skipped_missing", "skipped_user")

        /** RFC 3339 UTC with exactly three fraction digits, as on the wire (docs/24 s3.1 item 5). */
        val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

/** A photo's metadata for its `media_meta` record (contract `MediaMetaPayload`); android-sys builds it after the blob upload. */
data class MediaMetaCapture(
    /** = the record's client_uuid. */
    val mediaUuid: String,
    /** Envelope; `business_date` is the photo's business date. */
    val meta: CaptureMeta,
    /** `MediaPurpose`. */
    val purpose: String,
    /** `RecordType` of the owning record. */
    val refType: String,
    val refClientUuid: String,
    val sha256: String,
    val phash: String?,
    val bytes: Int,
    val width: Int,
    val height: Int,
    /** From the SAS response: `photos/<date>/<user uuid>/<media uuid>.jpg`. */
    val blobPath: String,
    /** ISO-8601 UTC. */
    val takenAt: String,
    /** The stored geo_fix row of the photo, or null (`fix: null`). */
    val fixClientUuid: String?,
)

private val SHA256 = Regex("^[0-9a-f]{64}$")
private val PHASH = Regex("^[0-9a-f]{16}$")
private val BLOB_PATH = Regex("^photos/\\d{4}-\\d{2}-\\d{2}/[0-9a-f-]{36}/[0-9a-f-]{36}\\.jpg$")
