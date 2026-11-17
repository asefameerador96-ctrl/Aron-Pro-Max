package com.aktcl.aron.feature.dayclose

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Room reads for Sales Submit (production [DaySource]). Unsent, rejected and quarantined counts are taken over the whole
 * outbox, not only today's rows: anything still unsent blocks the submit, which is the safe side.
 */
class RoomDaySource(private val db: AronDatabase) : DaySource {
    private val outbox = db.outboxDao()
    private val capture = db.captureDao()

    /**
     * The TRUE outbox states (D-AGATE-04 round 2, D1-1): every rejected and quarantined row of any date, seen or not. This is what the gates
     * and the `day_submit` counts read, exactly as before "seen" existed: "seen" is a SCREEN matter only (see [seenRefused]).
     */
    override suspend fun outboxStates(businessDate: String): OutboxStates {
        val pending = outbox.countInState("pending")
        val inFlight = outbox.countInState("in_flight")
        return OutboxStates(pending, inFlight, outbox.countInState("acked"), outbox.countRefused(OutboxState.REJECTED), outbox.countRefused(OutboxState.QUARANTINED))
    }

    /** The rows of each refused state the rep marked as seen: the screen subtracts them from its banner, nothing else does. */
    override suspend fun seenRefused(): OutboxStates =
        OutboxStates(0, 0, 0, outbox.countSeenRefused(OutboxState.REJECTED), outbox.countSeenRefused(OutboxState.QUARANTINED))

    /** The send state of the rows of [businessDate] only (the status line of the screen is scoped to the day on screen). */
    override suspend fun outboxStatesOn(businessDate: String): OutboxStates {
        val c = outbox.stateCountsOn(businessDate).associate { r -> r.state to r.count }
        return OutboxStates(c["pending"] ?: 0, c["in_flight"] ?: 0, c["acked"] ?: 0, c["rejected"] ?: 0, c["quarantined"] ?: 0)
    }

    override suspend fun markSeen(clientUuid: String) { outbox.markRefusedSeen(clientUuid, java.time.Instant.now().toString()) }

    override suspend fun markUnseen(clientUuid: String) { outbox.markRefusedUnseen(clientUuid) }

    override suspend fun sendAgain(clientUuid: String): Boolean = outbox.requeueRetryExhausted(clientUuid) > 0

    override suspend fun deviceCounts(businessDate: String): Map<String, Int> =
        outbox.committedCounts(businessDate).associate { it.recordType to it.count }

    /** Retailers who still owe, over the 7-day window: live memos with a remaining due after the collections against them. */
    override suspend fun dues(businessDate: String): DuesAtSubmit {
        val from = LocalDate.parse(businessDate).minusDays(6).toString()
        val memos = capture.memosBetween(from, businessDate)
        val superseded = capture.supersededIn(from, businessDate).toSet()
        val owing = memos.filter { it.clientUuid !in superseded }.mapNotNull { m ->
            val left = (m.dueMtk - capture.dueCollectionsOf(m.clientUuid).sumOf { it.amountMtk }).coerceAtLeast(0)
            if (left > 0) m.outletId to left else null
        }
        return DuesAtSubmit(owing.map { it.first }.toSet().size, owing.sumOf { it.second })
    }

    /** A submit stands unless the server voided its cycle (the day is reopened for the next cycle). */
    override suspend fun alreadySubmitted(businessDate: String): Boolean = CaptureRepository(db) { businessDate }.submitStands(businessDate)

    override suspend fun submitSettled(businessDate: String): Boolean =
        capture.daySubmitsOn(businessDate).lastOrNull()?.let { outbox.byClientUuid(it.clientUuid)?.state == "acked" } == true

    /**
     * D-NIGHT-DAYCLOSE-01 (b), from the phone's own ack registry: rejected and quarantined rows the outbox still keeps
     * (`cfg.app.outbox_keep_days`), and queued rows the server answered with a retryable code (`last_code` set). The memo
     * number or outlet comes from the stored record itself; the reason from the bundle's `reason_texts`.
     */
    override suspend fun rejectedRows(businessDate: String): List<RejectedRow> = rejectedPage(businessDate, Int.MAX_VALUE)

    /**
     * The refused or held records of the phone, NEWEST first, every date, at most [limit] ROWS (no payloads are read for the list; one row's
     * payload is read only to name its memo or outlet). A FAMILY (a memo with its lines, discounts and QC) is ONE entry, led by its parent,
     * counting its records; a family any of whose rows the phone gave up on is a "phone gave up" entry whose Send again acts on the family.
     */
    override suspend fun rejectedPage(businessDate: String, limit: Int): List<RejectedRow> {
        val refused = outbox.refusedList(limit)
        // Only codes the server answers as retryable (scope_stale, parent_missing, ...): the phone's own markers on queued
        // rows (resync, digest_resend, a released device_integrity_failed) are not rejections (checker).
        val retry = outbox.nextPending(RETRY_SCAN_LIMIT).filter { it.lastCode in RETRYABLE }
        if (refused.isEmpty() && retry.isEmpty()) return emptyList()
        val reasons = ReasonTextsJson.parse(ReferenceRepository(db).section("reason_texts"))
        val ref = db.referenceDao()
        val entries = ArrayList<RejectedRow>()
        for ((_, members) in refused.groupBy { it.familyUuid }.entries.sortedByDescending { (_, m) -> m.maxOf { it.seq } }) {
            val lead = members.minWith(compareBy({ it.rank }, { it.seq }))
            val row = outbox.byClientUuid(lead.clientUuid) ?: continue
            val gaveUp = members.any { it.state == OutboxState.REJECTED && it.lastCode == com.aktcl.aron.core.sync.AckRules.RETRY_EXHAUSTED }
            entries += entry(row.recordType, row.clientUuid, row.businessDate, row.payloadJson, if (gaveUp) com.aktcl.aron.core.sync.AckRules.RETRY_EXHAUSTED else lead.lastCode,
                when {
                    gaveUp -> RejectedOutcome.PHONE_GAVE_UP
                    lead.state == OutboxState.QUARANTINED -> RejectedOutcome.STORED_FLAGGED
                    else -> RejectedOutcome.DROPPED
                }, lead.lastMessageKey, members.all { it.seenAt != null }, members.size, reasons, ref)
        }
        for (row in retry) {
            entries += entry(row.recordType, row.clientUuid, row.businessDate, row.payloadJson, row.lastCode, RejectedOutcome.RETRY, row.lastMessageKey, false, 1, reasons, ref)
        }
        return entries
    }

    override suspend fun refusedTotal(): Int = outbox.countRefused(OutboxState.REJECTED) + outbox.countRefused(OutboxState.QUARANTINED)

    private suspend fun entry(
        type: String, uuid: String, date: String, payloadJson: String, code: String?, outcome: RejectedOutcome, messageKey: String?, seen: Boolean,
        familySize: Int, reasons: Map<String, Pair<String?, String?>>, ref: com.aktcl.aron.core.database.dao.ReferenceDao,
    ): RejectedRow {
        val payload = RecordPayloads.payloadOf(payloadJson)
        // A memo line names its memo (memo_client_uuid): the memo number and outlet come from that memo.
        val parent = RecordPayloads.string(payload, "memo_client_uuid")?.let { runCatching { capture.memo(it) }.getOrNull() }
        val outletName = (RecordPayloads.long(payload, "outlet_id") ?: parent?.outletId)?.let { id -> runCatching { ref.outlet(id)?.name }.getOrNull() }
        val reason = code?.let { reasons[it] }
        return RejectedRow(
            clientUuid = uuid, recordType = type, businessDate = date, code = code, outcome = outcome,
            memoNo = RecordPayloads.string(payload, "memo_no") ?: parent?.memoNo, outletName = outletName,
            reasonBn = reason?.first, reasonEn = reason?.second, messageKey = messageKey, seen = seen, familySize = familySize,
        )
    }

    private companion object {
        /** Retryable rows are few (a stale scope, a parent not yet there); the scan stops at this many queued rows. */
        const val RETRY_SCAN_LIMIT = 500
        val RETRYABLE = com.aktcl.aron.contract.RecordOutcomeCode.entries.filter { it.retryable == true }.map { it.wire }.toSet()
    }
}

/** The bundle's `reason_texts` section: `{code: {bn, en}}`; an unreadable section gives no texts (the code is shown instead). */
internal object ReasonTextsJson {
    fun parse(json: String?): Map<String, Pair<String?, String?>> = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json ?: return emptyMap()) as JsonObject
        o.mapValues { (_, v) ->
            val t = v as? JsonObject
            (t?.get("bn") as? JsonPrimitive)?.content to (t?.get("en") as? JsonPrimitive)?.content
        }
    }.getOrDefault(emptyMap())
}

/** Reads a field of a stored sync record (envelope + `payload`, docs/24 s4.3) without its DTO; null when absent or unreadable. */
internal object RecordPayloads {
    fun payloadOf(recordJson: String): JsonObject? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(recordJson) as JsonObject
        (o["payload"] as? JsonObject) ?: o
    }.getOrNull()

    fun string(o: JsonObject?, key: String): String? = (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun long(o: JsonObject?, key: String): Long? = (o?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()
}

/** Money and quantity totals of the day for the `day_submit` record (contract `MoneyTotals`); built from stored rows only. */
object MoneyTotalsJson {
    suspend fun build(db: AronDatabase, businessDate: String, categoryOf: (Long) -> String): JsonObject {
        val dao = db.captureDao()
        val all = dao.memosOn(businessDate)
        val superseded = all.mapNotNull { it.supersedesClientUuid }.toSet()
        val live = all.filter { it.clientUuid !in superseded }
        val lines = live.flatMap { dao.linesOf(it.clientUuid) }
        val net = live.sumOf { it.netMtk }
        val byCategory = lines.groupBy { categoryOf(it.skuId) }.mapValues { (_, ls) -> ls.sumOf { it.grossMtk } }.toMutableMap()
        val unallocated = net - byCategory.values.sum()
        if (unallocated != 0L) byCategory["other"] = (byCategory["other"] ?: 0L) + unallocated // discounts and QC not tied to a SKU
        return buildJsonObject {
            put("active_memo_count", live.count { it.lineCount > 0 })
            put("gross_mtk", live.sumOf { it.grossMtk })
            put("offer_discount_mtk", live.sumOf { it.offerDiscountMtk })
            put("drp_discount_mtk", live.sumOf { it.drpDiscountMtk })
            put("qc_deduction_mtk", live.sumOf { it.qcDeductionMtk })
            put("net_mtk", net)
            put("paid_mtk", live.sumOf { it.paidMtk })
            put("due_mtk", live.sumOf { it.dueMtk })
            put("due_collected_mtk", dao.dueCollectionsOn(businessDate).sumOf { it.amountMtk })
            put("net_by_category_mtk", JsonObject(byCategory.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("issued_qty_base_by_sku", JsonObject(dao.stockBalanceOn(businessDate).associate { it.skuId.toString() to JsonPrimitive(it.qtyBase) }.toSortedMap()))
            put("sold_qty_base_by_sku", JsonObject(lines.groupBy { it.skuId }.mapValues { (_, ls) -> JsonPrimitive(ls.sumOf { it.qtyBase }) }.mapKeys { it.key.toString() }.toSortedMap()))
        }
    }
}

/** Queues the `day_submit` as the route-day's last record ([CaptureRepository.recordDaySubmit]). */
class RoomDaySubmitWriter(
    private val db: AronDatabase,
    private val repo: CaptureRepository,
    private val meta: () -> CaptureMeta,
    private val categoryOf: (Long) -> String,
    private val stockSlipPrinted: suspend () -> Boolean = { false },
    private val newUuid: () -> String = ClientIds::newUuid,
    /** `route_day` for the SR; the AMO submits its supervisor day (`supervisor_day`, no route). */
    private val scope: String = "route_day",
) : DaySubmitWriter {
    override suspend fun queue(businessDate: String, gate: SubmitGate, counts: List<CountRow>) {
        val m = meta()
        val cycle = (db.captureDao().daySubmitsFor(businessDate, m.routeId).maxOfOrNull { it.submitCycle } ?: 0) + 1
        val countsJson = JsonObject(counts.associate { it.recordType to JsonPrimitive(it.device) })
        val rejected = gate.notes.filterIsInstance<SubmitBlock.Rejected>().sumOf { it.count }
        val quarantined = gate.notes.filterIsInstance<SubmitBlock.Quarantined>().sumOf { it.count }
        val dues = gate.duesWarning
        repo.recordDaySubmit(
            DaySubmitEntity(
                clientUuid = newUuid(), meta = m, scope = scope, submitCycle = cycle,
                deviceCountsJson = countsJson.toString(), deviceMoneyJson = MoneyTotalsJson.build(db, businessDate, categoryOf).toString(),
                rejectedCount = rejected, quarantinedCount = quarantined, pendingCount = db.outboxDao().unsentOn(businessDate), // D-AGATE-06: the real number
                submittedWithDues = dues != null, duesOutstandingMtk = dues?.outstandingMtk ?: 0L, retailersWithDues = dues?.retailersWithDues ?: 0,
                stockSlipPrinted = stockSlipPrinted(),
            ),
        )
    }
}

/**
 * D-AGATE-06 (owner rule 3): the `day_submit`(s) a check-out writes by itself when the rep has not submitted. One per ROUTE-DAY that has
 * captures on [build]'s business date and no submit standing (the SR works one or more routes a day; the AMO has one supervisor day),
 * with the true device counts, money, dues and the REAL `pending_count`, built inside the check-out's own transaction. No network,
 * no "everything acked" gate: the outbox holds the submit until every earlier record of the date is final and sends the check-out after it.
 * A route-day whose submit already stands (a manual Sales Submit) is left alone, so the check-out then writes only itself.
 */
class AutoDaySubmits(
    private val db: AronDatabase,
    private val repo: CaptureRepository,
    /** The capture envelope of "now" for a route (null: the AMO's supervisor day, no route). */
    private val metaFor: (routeId: Long?) -> CaptureMeta,
    private val categoryOf: (Long) -> String,
    private val stockSlipPrinted: suspend () -> Boolean = { false },
    /** `route_day` for the SR; the AMO submits its supervisor day (`supervisor_day`, no route). */
    private val scope: String = "route_day",
    private val newUuid: () -> String = ClientIds::newUuid,
) {
    suspend fun build(businessDate: String): List<DaySubmitEntity> {
        val dao = db.captureDao()
        val targets: List<Long?> = if (scope == "supervisor_day") (if (dao.captureCountOn(businessDate) > 0) listOf<Long?>(null) else emptyList())
        else dao.captureRoutesOn(businessDate).distinct().sorted()
        if (targets.isEmpty()) return emptyList()
        val dues = RoomDaySource(db).dues(businessDate)
        val counts = db.outboxDao().committedCounts(businessDate)
        val countsJson = JsonObject(counts.associate { it.recordType to JsonPrimitive(it.count) })
        val money = MoneyTotalsJson.build(db, businessDate, categoryOf).toString()
        val rejected = db.outboxDao().countOnInState(businessDate, OutboxState.REJECTED)
        val quarantined = db.outboxDao().countOnInState(businessDate, OutboxState.QUARANTINED)
        val slip = stockSlipPrinted()
        return targets.mapNotNull { route ->
            val meta = metaFor(route).copy(businessDate = businessDate, routeId = route)
            // a submit that stands (the rep's own Sales Submit) is left alone: the check-out then writes only itself
            if (repo.dayRefusal(meta, "visit") == com.aktcl.aron.core.database.repo.DayRefusal.SALES_SUBMIT) return@mapNotNull null
            val cycle = (dao.daySubmitsFor(businessDate, route).maxOfOrNull { it.submitCycle } ?: 0) + 1
            DaySubmitEntity(
                clientUuid = newUuid(), meta = meta, scope = scope, submitCycle = cycle,
                deviceCountsJson = countsJson.toString(), deviceMoneyJson = money,
                rejectedCount = rejected, quarantinedCount = quarantined, pendingCount = 0, // the real pending count is set inside the transaction
                submittedWithDues = dues.retailersWithDues > 0 || dues.outstandingMtk > 0, duesOutstandingMtk = dues.outstandingMtk, retailersWithDues = dues.retailersWithDues,
                stockSlipPrinted = slip,
            )
        }
    }
}

/**
 * D-NIGHT-DAYCLOSE-01 with contract 1.9 row 4: the login totals of [inner] plus, from the `digest` of `GET /v1/sync/totals`
 * (lane-local parser [com.aktcl.aron.core.sync.SubmitDigest] until the generated DTOs land), this phone's own received counts
 * and the server's list of rows it did not store, with the bundle's reason texts and the outlet names. Without the digest
 * (offline, an older server) it adds nothing.
 */
class DigestServerCounts(
    private val inner: ServerCounts,
    private val digests: com.aktcl.aron.core.sync.SubmitDigestSource,
    private val db: AronDatabase,
) : ServerCounts {
    override suspend fun counts(businessDate: String): Map<String, Int>? = inner.counts(businessDate)

    override suspend fun thisDeviceCounts(businessDate: String): Map<String, Int>? = digests.digest(businessDate)?.deviceReceived

    override suspend fun serverRejected(businessDate: String): List<RejectedRow> {
        val rows = digests.digest(businessDate)?.rejected.orEmpty()
        if (rows.isEmpty()) return emptyList()
        val reasons = ReasonTextsJson.parse(ReferenceRepository(db).section("reason_texts"))
        val ref = db.referenceDao()
        return rows.map { r ->
            val reason = reasons[r.code]
            RejectedRow(
                clientUuid = r.clientUuid, recordType = r.type, businessDate = r.businessDate, code = r.code,
                outcome = when {
                    r.status == "quarantined" && !r.final -> RejectedOutcome.STORED_FLAGGED
                    r.final || r.status == "rejected" || r.status == "quarantined" -> RejectedOutcome.DROPPED
                    // Only a row this install still holds can be sent again from here (checker).
                    r.fromThisDevice && db.outboxDao().byClientUuid(r.clientUuid) != null -> RejectedOutcome.RETRY
                    else -> RejectedOutcome.WAITING_ELSEWHERE
                },
                memoNo = r.memoNo, outletName = r.outletId?.let { id -> runCatching { ref.outlet(id)?.name }.getOrNull() },
                reasonBn = reason?.first, reasonEn = reason?.second, fromThisPhone = r.fromThisDevice,
            )
        }
    }
}
