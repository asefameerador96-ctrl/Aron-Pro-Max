package com.aktcl.aron.core.database.repo

import androidx.room.withTransaction
import com.aktcl.aron.core.database.record.AmoPayloads
import kotlinx.serialization.json.JsonObject
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
        if (purged(event.clientUuid)) return@withTransaction emptyList()
        requireUuids(event.clientUuid, fix.clientUuid)
        requireFixOf(fix, event.clientUuid, event.fixClientUuid)
        capture.insertFix(fix)
        capture.insertAttendance(event)
        outbox.insert(listOf(RecordMapping.attendance(event, fix, nowIso())))
    }

    /**
     * One breadcrumb (N-035): the fix and its outbox row in one transaction, idempotent by the record's UUID (a replay of an
     * already acked one is ignored). It belongs to no route-day, so a Sales Submit never refuses it.
     */
    suspend fun recordBreadcrumb(clientUuid: String, meta: CaptureMeta, fix: GeoFixEntity) = db.withTransaction {
        if (purged(clientUuid)) return@withTransaction emptyList()
        requireUuids(clientUuid, fix.clientUuid)
        require(fix.ownerClientUuid == clientUuid && fix.purpose == "breadcrumb") { "the fix must be the breadcrumb's own" }
        capture.insertFix(fix)
        outbox.insert(listOf(RecordMapping.geoBreadcrumb(clientUuid, meta, fix, nowIso())))
    }

    /**
     * One Save of the Stock screen: one event per SKU, all or nothing.
     *
     * tso-manual X4a (R-W-22): `stock_movement` is one of B3's DAY_LOCKED_TYPES, so a Save whose route-day is closed by the
     * zone's Final Submit is refused ([RouteDayFinalSubmittedException]) and nothing is stored or queued; the stock screens
     * word it with the lock text and no slip is printed for it. A routeless movement (the AMO outside a call) never meets
     * the lock, as on the server. A Sales Submit does not refuse stock (D-X4a-13: the server accepts it and the phone always did).
     */
    suspend fun recordStock(movements: List<StockMovementEntity>) {
        require(movements.isNotEmpty()) { "nothing to save" }
        requireUuids(*movements.map { it.clientUuid }.toTypedArray())
        db.withTransaction {
            val fresh = movements.filterNot { purged(it.clientUuid) } // a purged movement replayed is not captured again
            if (fresh.isEmpty()) return@withTransaction emptyList()
            fresh.map { it.meta }.distinct().forEach { requireNotFinal(it, STOCK_MOVEMENT) }
            capture.insertStock(fresh)
            val at = nowIso()
            outbox.insert(fresh.map { RecordMapping.stock(it, at) })
        }
    }

    suspend fun recordVisitOpen(visit: VisitEntity, fix: GeoFixEntity) = db.withTransaction {
        if (purged(visit.clientUuid)) return@withTransaction emptyList()
        requireUuids(visit.clientUuid, fix.clientUuid)
        requireRoute(visit.meta)
        requireOpenRouteDay(visit.meta, "visit") // tso-manual X4a (R-W-11): the call opens a locked type
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
            if (purged(m.clientUuid)) return@withTransaction emptyList()
            requireOpenRouteDay(m.meta, "memo") // tso-manual X4a (R-W-11): a sale or an edit (a superseding memo)
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
        if (purged(close.clientUuid)) return@withTransaction emptyList()
        requireUuids(close.clientUuid, close.visitClientUuid)
        requireRoute(close.meta)
        requireOpenRouteDay(close.meta, "visit_close") // tso-manual X4a (R-W-11)
        checkNotNull(capture.visit(close.visitClientUuid)) { "visit_close without its visit" }
        capture.insertVisitClose(close)
        outbox.insert(listOf(RecordMapping.visitClose(close, nowIso())))
    }

    /**
     * A sale numbered on this phone (F-SYS-027): the number is reserved in its own committed step, then the sale is written
     * with it in one transaction. Returns the memo number. If the save fails the number is burned, never reused.
     */
    suspend fun recordNumberedSale(sale: SaleCapture, numbering: MemoNumbering): String {
        // A replay of a sale that was already sent and purged must not burn a memo number or look saved: it fails loudly, as a missing visit would.
        check(!purged(sale.memo.clientUuid)) { "memo ${sale.memo.clientUuid} was already saved and sent from this phone" }
        // A replay of a sale that is still stored (a retry after a kill and relaunch) gets its own number back and burns none.
        capture.memo(sale.memo.clientUuid)?.memoNo?.let { return it }
        // tso-manual X4a (D-X4a-03): a sale the Final Submit lock refuses takes no memo number (checked again in recordSale);
        // follow-up (R-W-23): nor does one a standing Sales Submit refuses (the same route-day check, before the reserve)
        requireOpenRouteDay(sale.memo.meta, "memo")
        val memoNo = MemoNumbers(db).reserve(sale.memo.meta.businessDate, numbering)
        recordSale(sale.copy(memo = sale.memo.copy(memoNo = memoNo)))
        return memoNo
    }

    /**
     * tso-manual X4a (fix round, R-W-11 (3)): true when a `due_collection` with [meta] would pass [recordDueCollection]'s
     * route-day check: no Final Submit lock and no standing Sales Submit (route-day or the AMO's supervisor day) closes its
     * route-day; a routeless collection is never checked. The memo screen draws Mark Paid only then. Pure reads, no write.
     */
    suspend fun acceptsDueCollection(meta: CaptureMeta): Boolean = dayRefusal(meta, DUE_COLLECTION) == null

    /**
     * tso-manual X4a (R-W-23): why a capture of [recordType] with [meta] would be refused by this repository's route-day check,
     * or null when it would pass: [DayRefusal.FINAL_SUBMIT] for a type the server refuses after the zone's Final Submit
     * ([FinalSubmitLock.LOCKED_TYPES]) on a closed route-day, [DayRefusal.SALES_SUBMIT] while a Sales Submit stands (the
     * route-day's, or the AMO's supervisor day). Per type as the record methods check it: `stock_movement` meets the Final
     * Submit lock only, a routeless `due_collection` is never checked. Pure reads, no write; a screen asks it before it reads
     * a fix or opens a form, and words the answer. Owner decision 2026-11-04: FINAL_SUBMIT only while the late-sale policy
     * in force at this repository's trusted now locks ([FinalSubmitLock.lockApplies]: quarantine or reject); under
     * accept_and_flag (the default) a final route-day answers as an open one, and SALES_SUBMIT is unchanged.
     */
    suspend fun dayRefusal(meta: CaptureMeta, recordType: String): DayRefusal? = when {
        recordType == STOCK_MOVEMENT -> DayRefusal.FINAL_SUBMIT.takeIf { FinalSubmitLock.refuses(db, meta, STOCK_MOVEMENT, nowIso()) }
        recordType == DUE_COLLECTION && meta.routeId == null -> null
        else -> RouteDayLock.refusal(db, meta, recordType, nowIso())
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
            if (purged(due.clientUuid)) return@withTransaction emptyList()
            due.visitClientUuid?.let { checkNotNull(capture.visit(it)) { "due collection for a visit not on this phone" } }
            if (due.meta.routeId != null) requireOpenRouteDay(due.meta, DUE_COLLECTION) // tso-manual X4a (R-W-11): Mark Paid
            fix?.let { requireFixOf(it, due.clientUuid, due.fixClientUuid); capture.insertFix(it) }
            capture.insertDueCollection(due)
            outbox.insert(listOf(RecordMapping.dueCollection(due, fix, nowIso())))
        }
    }

    /**
     * One AMO record (F-AMO rows): the row and its outbox record in one transaction. A second call with the same client UUID
     * changes nothing and returns false, so a retried tap or replay never doubles it. Only the AMO record types are accepted.
     */
    suspend fun recordAmo(record: com.aktcl.aron.core.database.entity.AmoRecordEntity): Boolean = db.withTransaction {
        if (purged(record.clientUuid) || (record.familyUuid != record.clientUuid && purged(record.familyUuid))) return@withTransaction false // a child of a purged family is stale too
        requireUuids(record.clientUuid, record.familyUuid)
        require(record.recordType in AMO_RECORD_TYPES) { "not an AMO record type: ${record.recordType}" }
        val payload = runCatching { Json.parseToJsonElement(record.payloadJson) as? JsonObject }.getOrNull()
        require(payload != null) { "the payload is a JSON object" }
        AmoPayloads.problem(record.recordType, payload)?.let { throw IllegalArgumentException(it) }
        // docs/24 s4.2/s4.3: family and rank by type; visit-family records carry the route worked.
        val own = record.familyUuid == record.clientUuid
        val ok = when (record.recordType) {
            "distribution_check" -> record.rank == 1 && !own
            "distribution_check_line" -> record.rank == 2 && !own
            "price_compliance_check" -> record.rank == 2 && !own
            "call_assessment" -> (record.rank == 1 && !own) || (record.rank == 0 && own)
            "call_assessment_answer" -> (record.rank == 2 || record.rank == 1) && !own
            else -> record.rank == 0 && own // risk_review, outlet_request_verification, task
        }
        require(ok) { "${record.recordType}: wrong family or rank (rank ${record.rank}, own family $own)" }
        if (!own && record.rank > 0 && record.recordType != "call_assessment_answer") requireRoute(record.meta)
        if (capture.amoRecordCount(record.clientUuid) > 0) return@withTransaction false
        requireOpenRouteDay(record.meta)
        capture.insertAmoRecord(record)
        outbox.insert(listOf(RecordMapping.amoRecord(record, nowIso())))
        true
    }

    /** An outlet skipped without a visit: no fix (F-SR-057). */
    suspend fun recordVisitSkip(skip: VisitSkipEntity) = db.withTransaction {
        if (purged(skip.clientUuid)) return@withTransaction emptyList()
        requireUuids(skip.clientUuid)
        requireRoute(skip.meta)
        require(REASON_CODE.matches(skip.reasonCode)) { "reason_code must match ${REASON_CODE.pattern}" }
        requireOpenRouteDay(skip.meta, "visit_skip") // tso-manual X4a (R-W-11)
        capture.insertVisitSkip(skip)
        outbox.insert(listOf(RecordMapping.visitSkip(skip, nowIso())))
    }

    /**
     * An AV or KV item shown or skipped during an SR call (F-SR-020): in the visit's family, one per item per visit. A
     * second view of the same item in the same visit is not recorded again (returns false), so a replay never doubles it.
     * SR calls only: AMO and TSO visits never carry content (and no points are earned from it here; loyalty is deferred).
     */
    suspend fun recordContentView(view: com.aktcl.aron.core.database.entity.ContentViewEntity): Boolean = db.withTransaction {
        if (purged(view.clientUuid)) return@withTransaction false
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
     * answer carries the photo's media uuid, any other may carry one. SR calls and AMO control calls; no points ledger (docs/27).
     */
    suspend fun recordSurveyResponse(answer: com.aktcl.aron.core.database.entity.SurveyResponseEntity) = db.withTransaction {
        if (purged(answer.clientUuid)) return@withTransaction emptyList()
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
        requireSurveyVisit(answer.visitClientUuid)
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

    /** A survey belongs to an SR call or an AMO Control Call (F-AMO-043; the AMO survey earns no points). Never a Joint Call or a TSO visit. */
    private suspend fun requireSurveyVisit(visitClientUuid: String) {
        val visit = checkNotNull(capture.visit(visitClientUuid)) { "the visit is not on this phone" }
        require(visit.visitKind == com.aktcl.aron.contract.VisitKind.SR_CALL.wire || visit.visitKind == "amo_control_call") { "surveys are for SR calls and AMO control calls only" }
    }

    /**
     * Sales Submit: the route-day's last record. Nothing of that route-day (visits, sales, closes, skips, dues) is committed
     * after it until the server reopens the day ([reopenRouteDay]); one submit per cycle.
     */
    suspend fun recordDaySubmit(submit: DaySubmitEntity) = db.withTransaction {
        if (purged(submit.clientUuid)) return@withTransaction emptyList()
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

    /**
     * A cluster the SR created on the phone, with the GEO fix of the creation, in ONE transaction (SRM-B-44). Idempotent by the client uuid:
     * a replay of a cluster already stored writes nothing and returns TRUE (it IS saved); a purged uuid writes nothing and returns false.
     * LOCAL ONLY: no outbox row until the contract has the cluster-creation record. Returns true when the cluster is on the phone.
     */
    suspend fun recordClusterCreate(c: com.aktcl.aron.core.database.entity.ClusterCreateEntity, fix: GeoFixEntity): Boolean = db.withTransaction {
        if (capture.clusterCreates().any { it.clientUuid == c.clientUuid }) return@withTransaction true
        if (purged(c.clientUuid)) return@withTransaction false
        requireUuids(c.clientUuid, fix.clientUuid)
        requireFixOf(fix, c.clientUuid, c.fixClientUuid)
        capture.insertFix(fix)
        capture.insertClusterCreate(c) > 0L
    }

    /** A new-outlet, edit, relocation or closure request with the fix taken for it. */
    suspend fun recordOutletRequest(request: OutletChangeRequestEntity, fix: GeoFixEntity) = db.withTransaction {
        if (purged(request.clientUuid) || capture.outletRequestCount(request.clientUuid) > 0) return@withTransaction emptyList() // a replay of a request already on this phone writes nothing
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

    /**
     * Several outlet requests (each with its fix) in ONE transaction: if any of them is refused or fails, none is written (SRM-B-40:
     * change information and its cluster change). Each one is idempotent on replay, as [recordOutletRequest].
     */
    suspend fun recordOutletRequests(requests: List<Pair<OutletChangeRequestEntity, GeoFixEntity>>) = db.withTransaction {
        requests.forEach { (r, f) -> recordOutletRequest(r, f) }
    }

    /** Resolves a task: the local task turns `completed` and its `task_event` is queued, in one transaction. */
    suspend fun resolveTask(event: TaskEventEntity, resolvedAt: String) = db.withTransaction {
        if (purged(event.clientUuid)) return@withTransaction emptyList()
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
            if (outbox.byClientUuid(m.mediaUuid) != null || purged(m.mediaUuid)) return@withTransaction false
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
        if (purged(clientUuid)) return@withTransaction emptyList()
        requireUuids(clientUuid)
        outbox.insert(listOf(RecordMapping.deviceStatus(clientUuid, meta, report, nowIso())))
    }

    /**
     * The server reopened a submitted route-day (a submit void): captures of [businessDate] and [routeId] are allowed again
     * until a submit of a later cycle than [voidedCycle].
     */
    suspend fun reopenRouteDay(businessDate: String, routeId: Long?, voidedCycle: Int) = RouteDayLock.reopen(db, businessDate, routeId, voidedCycle)

    /** True while a Sales Submit of the date stands (a server void of its cycle reopens the day). */
    suspend fun submitStands(businessDate: String): Boolean = RouteDayLock.submitStands(db, businessDate)

    /** True when [uuids] holds one that was acked and purged earlier: the record is a replay and is not captured again. */
    private suspend fun purged(vararg uuids: String): Boolean = outbox.anyPurged(uuids.toList())

    /**
     * Refuses a route-day capture after its Sales Submit (docs/24 s4.2 rule 2: day_submit is the route-day's last record;
     * IllegalStateException) and, for a [recordType] the server refuses after the zone's Final Submit (B3's
     * DAY_LOCKED_TYPES, [FinalSubmitLock.LOCKED_TYPES]), on a route-day closed by that lock
     * ([RouteDayFinalSubmittedException], which the screens catch and word as the lock).
     *
     * tso-manual X4a (R-W-11, A3 ask 10): the callers name their type for visit, memo, due_collection, visit_skip and
     * visit_close, and never for day_submit, amo records, survey answers or content views, which the server accepts after
     * the lock as before (D-TSO-MAN-31). The print ledger does not come through here. Follow-up (R-W-22): [recordStock]
     * names `stock_movement` to the Final Submit part alone ([requireNotFinal]).
     */
    private suspend fun requireOpenRouteDay(meta: CaptureMeta, recordType: String? = null) = RouteDayLock.requireOpen(db, meta, recordType, nowIso())

    /** tso-manual X4a: the Final Submit part of [requireOpenRouteDay] alone, for a check that must run before a side effect. */
    private suspend fun requireNotFinal(meta: CaptureMeta, recordType: String) {
        if (FinalSubmitLock.refuses(db, meta, recordType, nowIso())) throw RouteDayFinalSubmittedException(meta.businessDate, checkNotNull(meta.routeId), recordType)
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

    companion object {
        /** Record types [recordAmo] accepts. */
        val AMO_RECORD_TYPES = setOf(
            "distribution_check", "distribution_check_line", "call_assessment", "call_assessment_answer", "price_compliance_check",
            "risk_review", "outlet_request_verification", "task",
        )
        val REASON_CODE = Regex("^[a-z][a-z0-9_]{1,40}$")
        val CONTENT_OUTCOMES = setOf("viewed", "skipped_missing", "skipped_user")

        /** The record type [recordDueCollection] names to the route-day lock, and [acceptsDueCollection] asks with. */
        private const val DUE_COLLECTION = "due_collection"

        /** The record type [recordStock] names to the Final Submit lock (tso-manual X4a, R-W-22). */
        const val STOCK_MOVEMENT = "stock_movement"

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
