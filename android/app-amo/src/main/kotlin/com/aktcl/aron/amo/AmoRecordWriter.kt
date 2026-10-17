package com.aktcl.aron.amo

import androidx.room.withTransaction
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.feature.outlet.FixReading
import com.aktcl.aron.feature.outlet.toEntities

/**
 * The AMO's record writes (everything AmoDay queues that is not the SR sale stack): joint call, assessment, survey, price compliance,
 * verification decisions, exception reviews and task assignment. Each is all-or-nothing in one transaction and idempotent: a replay of the same
 * operation (a double tap, a retry after a kill and relaunch) writes nothing twice. Kept free of Android and session types so the kill-and-
 * relaunch and replay tests drive it over a file database (AmoRecordWriterReplayTest).
 */
class AmoRecordWriter(
    private val db: AronDatabase,
    private val capture: CaptureRepository,
    private val meta: CaptureMetaProvider,
    /** The route of the open call (0 when none): records of a call carry it. */
    private val routeOfCall: () -> Long,
    private val newUuid: () -> String,
    private val requestSync: () -> Unit,
    private val readFix: suspend (purpose: String) -> FixReading,
    private val claimPhoto: suspend (photoUuid: String, purpose: String, refType: String, refUuid: String, fix: GeoFixEntity?) -> Unit,
) {
    /** Joint Call assessment (F-AMO-011): one assessment and its answers, family = the visit; a second Save for the visit writes nothing. */
    suspend fun saveJointCall(visitUuid: String, rubric: com.aktcl.aron.feature.amo.JointRubric, state: com.aktcl.aron.feature.amo.JointState, assessedUserId: Long?) {
        if (db.captureDao().amoRecordsOfFamily(visitUuid).any { it.recordType == "call_assessment" }) return
        val planned = com.aktcl.aron.feature.amo.JointCall.records(visitUuid, rubric, state, assessedUserId, newUuid)
        db.withTransaction {
            for (p in planned) capture.recordAmo(
                com.aktcl.aron.core.database.entity.AmoRecordEntity(p.clientUuid, meta.meta(routeOfCall()), p.type, p.familyUuid, p.rank, p.payload.toString()),
            )
        }
        runCatching { requestSync() }
    }

    /** Request uuids this phone has already decided (queued or sent), so they leave the pending list at once. */
    suspend fun decidedRequests(): Set<String> = db.captureDao().amoRecordsOfType("outlet_request_verification").filter { r ->
        // A decision the server refused (rejected, quarantined) does not hide the request: the AMO can decide it again.
        db.outboxDao().byClientUuid(r.clientUuid)?.state.let { it != com.aktcl.aron.core.database.entity.OutboxState.REJECTED && it != com.aktcl.aron.core.database.entity.OutboxState.QUARANTINED }
    }.mapNotNull {
        runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson) as kotlinx.serialization.json.JsonObject)["request_uuid"]?.let { v -> (v as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull()
    }.toSet()

    /**
     * Queues the AMO's decision on one request: `verified` (Save) or `discarded` (Cancel on a request they reject), with a fresh fix read
     * now (the server applies the 100 m, move-limit and mock guards). [recordUuid] is fixed when the form opens, so a double tap or a replay
     * queues one record. Returns false when the phone has no usable fix (nothing is queued).
     */
    suspend fun decideRequest(recordUuid: String, requestUuid: String, verify: Boolean, subChannelId: Long?, geoClass: String?, note: String?, photoUuid: String?): Boolean {
        if (requestUuid in decidedRequests()) return true // already decided on this phone: nothing to queue twice
        val reading = readFix("outlet_verification")
        if (!reading.isOk || reading.isMock) return false
        val fixEntity = reading.toEntity(newUuid(), recordUuid, "outlet_verification", 0)
        val fix = com.aktcl.aron.core.database.record.RecordMapping.json.encodeToJsonElement(
            com.aktcl.aron.contract.GeoFix.serializer(), com.aktcl.aron.core.database.record.RecordMapping.fix(fixEntity),
        ) as kotlinx.serialization.json.JsonObject
        val payload = com.aktcl.aron.core.database.record.AmoPayloads.outletRequestVerification(
            requestUuid, if (verify) "verified" else "discarded", subChannelId, geoClass, fix, photoUuid, note,
        )
        photoUuid?.let { runCatching { claimPhoto(it, "outlet_verification", "outlet_request_verification", recordUuid, fixEntity) } }
        try {
            capture.recordAmo(
                com.aktcl.aron.core.database.entity.AmoRecordEntity(recordUuid, meta.meta(0L), "outlet_request_verification", recordUuid, 0, payload.toString()),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A concurrent second call with the same record uuid hit the primary key: the decision is queued once.
            if (requestUuid !in decidedRequests()) throw e
        }
        runCatching { requestSync() }
        return true
    }

    suspend fun decidedSignals(): Set<Long> = db.captureDao().amoRecordsOfType("risk_review").filter { r ->
        // A review the server refused (rejected, quarantined) does not hide the signal: it can be reviewed again.
        db.outboxDao().byClientUuid(r.clientUuid)?.state.let { it != com.aktcl.aron.core.database.entity.OutboxState.REJECTED && it != com.aktcl.aron.core.database.entity.OutboxState.QUARANTINED }
    }.mapNotNull {
        runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson) as kotlinx.serialization.json.JsonObject)["signal_id"]?.let { v -> (v as kotlinx.serialization.json.JsonPrimitive).content.toLong() } }.getOrNull()
    }.toSet()

    /** Queues one review event for [signalId]; a signal already reviewed on this phone queues nothing (so a retry never doubles it). */
    suspend fun reviewSignal(recordUuid: String, signalId: Long, action: String, note: String?): Boolean {
        if (signalId in decidedSignals()) return false
        val payload = com.aktcl.aron.core.database.record.AmoPayloads.riskReview(signalId, action, note)
        val queued = capture.recordAmo(
            com.aktcl.aron.core.database.entity.AmoRecordEntity(recordUuid, meta.meta(0L), "risk_review", recordUuid, 0, payload.toString()),
        )
        runCatching { requestSync() }
        return queued
    }

    /** Queues one `task` record (offline too); [recordUuid] is fixed when the form opens, so a double tap queues one. */
    suspend fun assignTask(recordUuid: String, typeCode: String, assigneeId: Long, outletId: Long?, title: String, description: String?, dueDate: String?): Boolean {
        val payload = com.aktcl.aron.core.database.record.AmoPayloads.task(typeCode, assigneeId, outletId, title, description, dueDate, null)
        val queued = capture.recordAmo(
            com.aktcl.aron.core.database.entity.AmoRecordEntity(recordUuid, meta.meta(0L), "task", recordUuid, 0, payload.toString()),
        )
        runCatching { requestSync() }
        return queued
    }

    /** Writes the confirmed answers all or nothing (one per question per visit; a kill mid-way keeps none) and claims the photos for them. */
    suspend fun saveSurvey(visitUuid: String, survey: com.aktcl.aron.core.database.entity.SurveyEntity, rows: List<com.aktcl.aron.feature.outlet.SurveyResponseRow>) {
        if (rows.isEmpty() || db.captureDao().surveyResponsesOf(visitUuid).isNotEmpty()) return
        val meta = meta.meta(routeOfCall())
        db.withTransaction {
            for (r in rows) {
                val a = r.answer
                capture.recordSurveyResponse(
                    com.aktcl.aron.core.database.entity.SurveyResponseEntity(
                        r.clientUuid, meta, visitUuid, survey.surveyId, survey.version, r.question.questionId,
                        r.question.answerType.name.lowercase(), a.bool, a.num, a.optionCode, a.text, a.photoUuid,
                    ),
                )
            }
        }
        for (r in rows) r.answer.photoUuid?.let { runCatching { claimPhoto(it, "survey", "survey_response", r.clientUuid, null) } }
        runCatching { requestSync() }
    }

    /** One `price_compliance_check` per typed SKU, all or nothing. A SKU already recorded for the visit is skipped, so a later Save adds only new SKUs and a double tap writes nothing twice. */
    suspend fun savePriceCompliance(visitUuid: String, rows: List<com.aktcl.aron.feature.amo.PriceRow>, typed: Map<Long, String>, toleranceBp: Int) {
        db.withTransaction {
            val done = db.captureDao().amoRecordsOfFamily(visitUuid).filter { it.recordType == "price_compliance_check" }
                .mapNotNull { runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson) as kotlinx.serialization.json.JsonObject)["sku_id"].toString().toLong() }.getOrNull() }.toSet()
            val planned = com.aktcl.aron.feature.amo.PriceCompliance.records(visitUuid, rows.filter { it.skuId !in done }, typed, toleranceBp, newUuid)
            for (p in planned) capture.recordAmo(
                com.aktcl.aron.core.database.entity.AmoRecordEntity(p.clientUuid, meta.meta(routeOfCall()), "price_compliance_check", p.familyUuid, p.rank, p.payload.toString()),
            )
        }
        runCatching { requestSync() }
    }

    /** SR Perf. Assessment (F-AMO-007/008/009): the check and its brand lines, all or nothing, family = the visit. A second Save for the visit writes nothing. */
    suspend fun saveAssessment(visitUuid: String, brands: List<com.aktcl.aron.feature.amo.AmoBrand>, state: com.aktcl.aron.feature.amo.PerfState) {
        if (db.captureDao().amoRecordsOfFamily(visitUuid).any { it.recordType == "distribution_check" }) return
        val planned = com.aktcl.aron.feature.amo.PerfAssessment.records(visitUuid, brands, state, newUuid)
        db.withTransaction {
            for (p in planned) capture.recordAmo(
                com.aktcl.aron.core.database.entity.AmoRecordEntity(p.clientUuid, meta.meta(routeOfCall()), p.type, p.familyUuid, p.rank, p.payload.toString()),
            )
        }
        runCatching { requestSync() }
    }

    private val requestFixUuids = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Commits one own outlet request (New shop, Permanent close, Change information, Update Base): the request and its fix in one transaction,
     * the photos claimed for it, a sync requested. The request uuid is fixed when the form opens, so a replay (a double tap, a retry after a
     * kill and relaunch with a new fix uuid) finds the request and writes nothing.
     */
    suspend fun commitOutletRequest(draft: com.aktcl.aron.feature.outlet.OutletRequestDraft) {
        val fix = draft.fix ?: readFix("outlet_capture")
        val fixUuid = requestFixUuids.getOrPut(draft.requestUuid) { com.aktcl.aron.core.common.ClientIds.newUuid() }
        val (entity, fixEntity) = draft.toEntities(meta.meta(0L), fix, fixUuid)
        draft.photoUuids.forEach { claimPhoto(it, "outlet_capture", "outlet_change_request", entity.clientUuid, fixEntity) }
        capture.recordOutletRequest(entity, fixEntity)
        requestSync()
    }
}
