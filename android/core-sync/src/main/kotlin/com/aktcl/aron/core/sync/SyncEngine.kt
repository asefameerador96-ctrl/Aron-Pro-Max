package com.aktcl.aron.core.sync

import androidx.room.withTransaction
import com.aktcl.aron.contract.ContractInfo
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.RecordAck
import com.aktcl.aron.contract.Resolution
import com.aktcl.aron.contract.RouteDayState
import com.aktcl.aron.contract.ServerTotals
import com.aktcl.aron.contract.SyncBatchResponse
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.contract.TimeAnchor
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.network.ProofResult
import com.aktcl.aron.core.network.TransportFailure
import com.aktcl.aron.core.network.WireJson
import com.aktcl.aron.core.session.SessionRepository
import com.aktcl.aron.rules.BusinessDate
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The upload grant of one user (docs/24 s3.2, D24-57). */
interface UploadAuth {
    /** The stored upload access token of [userId], or null. */
    suspend fun token(userId: Long): String?

    /** Rotates [userId]'s upload token after [rejected] was refused; true when a new one is stored. */
    suspend fun refresh(userId: Long, rejected: String?): Boolean
}

/** [UploadAuth] backed by core-session: works for a user who is logged out or switched away. */
class SessionUploadAuth(private val session: SessionRepository) : UploadAuth {
    override suspend fun token(userId: Long): String? = session.uploadAccessToken(userId)
    override suspend fun refresh(userId: Long, rejected: String?): Boolean = session.refresh(userId, Grant.UPLOAD, rejected)
}

/** Why a run ended. Every value except [DRAINED] leaves rows for a later trigger; none of them loses a row. */
enum class SyncStop {
    /** Nothing left to send now (rows deferred by a retryable reject are counted in [SyncReport.deferred]). */
    DRAINED,

    /** No connection, a timeout or an edge page: the batch stays in flight and is resent identically. */
    OFFLINE,

    /** 429, 503, a `hold_s`, or a batch that failed with 500: try again after [SyncReport.retryAfterMs]. */
    RETRY_LATER,

    /** No upload token and none could be obtained: the user must sign in online once. */
    AUTH_REQUIRED,

    /** The device is not allowed to upload now (proof, suspension, revocation, enrolment). */
    BLOCKED,

    /** 426: this build is below the minimum version. */
    UPDATE_REQUIRED,

    /** No device uuid yet. */
    NO_DEVICE,

    /** A batch-level answer the engine cannot act on (400, a malformed or mismatched response). */
    FAILED,

    /** [SyncPolicy.maxBatchesPerRun] reached with rows still pending. */
    RUN_LIMIT,
}

data class SyncReport(
    val stop: SyncStop,
    val batches: Int,
    val acked: Int,
    val rejected: Int,
    val quarantined: Int,
    /** Rows put back to pending by a retryable reject or an isolated 500 in this run. */
    val deferred: Int,
    /** Rows still pending or in flight after the run. */
    val unsent: Int,
    val retryAfterMs: Long? = null,
    val code: String? = null,
)

/**
 * The upload half of the sync engine (docs/24 s4.3 to s4.6; F-SYS-008). One run:
 * 1. resends every persisted in-flight batch first, with the same `batch_uuid` and `X-Batch-Attempt` + 1, so a kill
 *    mid-send or a lost response ends in the server's stored response (`replayed: true`), never a second write;
 * 2. then assembles new batches from pending rows in commit order (at most 200 rows and 256 KiB raw, families kept whole
 *    where they fit), persists their membership with a new `batch_uuid` in the same transaction that marks them in
 *    flight, gzips and signs them, and applies the acks in one transaction.
 *
 * Idempotency rests on the server (client_uuid registry and the batch store, s3.3); the engine's part is that a batch's
 * membership never changes once persisted and that no row leaves the state machine except through an ack, a resolution
 * or the local retry limit. Scheduling (debounce, WorkManager, connectivity) is F-SYS-011/F-SYS-046; this class only runs.
 */
class SyncEngine(
    private val userId: Long,
    private val db: AronDatabase,
    private val api: BatchSender,
    private val auth: UploadAuth,
    private val deviceUuid: () -> String?,
    private val appVersion: String,
    private val clock: WallClock,
    private val policy: SyncPolicy = SyncPolicy(),
    /** Trusted-time anchors (F-SYS-049 supplies them); at most 3 are sent. */
    private val timeAnchors: () -> List<TimeAnchor> = { emptyList() },
    private val random: Random = Random.Default,
    /** The enrolled Keystore key (F-SYS-072); null or a null answer before enrolment: records go without `sig`. */
    private val recordSigner: com.aktcl.aron.core.network.DeviceProofSigner? = null,
    /** F-SYS-081: the daily telemetry object of a closed date rides the batch body; null in most tests. */
    private val telemetry: BatchTelemetry? = null,
) {
    private val outbox = db.outboxDao()
    private val meta = db.referenceDao()

    suspend fun run(trigger: SyncTrigger): SyncReport = lockOf(userId).withLock { Run(trigger).execute() }

    /**
     * F-SYS-072 (BC-53): the server quarantines every header without a valid sig as `device_integrity_failed`; BC-53 asks
     * it to accept them unless `cfg.sec.record_signature_mode` is enforce and to release a resend of such a registry row
     * (not on INT on 2026-10-07: docs/requests/android-core-backend-record-signature-mode.md; until then rounds re-quarantine).
     * The phone treats a quarantine as terminal, so those rows are resent by uuid in rounds: at most one per business date
     * and [INTEGRITY_RELEASE_ROUNDS] per episode, because the phone cannot see the server's mode (an older server or
     * enforce answers them quarantined again; a later day's round catches a server upgraded meanwhile). The episode ends
     * only when no row carries the code in any state (released rows keep `last_code` while pending or in flight), so a
     * run that stops before the answer never ends it; rows quarantined later start a new episode. Meta: `<rounds>:<date>`.
     */
    private suspend fun releaseIntegrityQuarantine() {
        val code = com.aktcl.aron.contract.RecordOutcomeCode.DEVICE_INTEGRITY_FAILED.wire
        val today = BusinessDate.of(clock.nowMs()).toString()
        db.withTransaction {
            val state = meta.meta(KEY_INTEGRITY_RELEASE)
            val rounds = state?.substringBefore(':')?.toIntOrNull() ?: 0
            when {
                outbox.countWithCode(code) == 0 -> if (state != null) meta.deleteMeta(KEY_INTEGRITY_RELEASE)
                outbox.countQuarantined(code) == 0 -> Unit // released rows still on their way
                rounds >= INTEGRITY_RELEASE_ROUNDS || state?.substringAfter(':') == today -> Unit
                else -> {
                    outbox.releaseQuarantined(code)
                    meta.putMeta(SyncMetaEntity(KEY_INTEGRITY_RELEASE, "${rounds + 1}:$today"))
                }
            }
        }
    }

    private inner class Run(val trigger: SyncTrigger) {
        var token: String? = null
        var device: String = ""
        var batches = 0
        /** The telemetry date the request being sent carries, if any. */
        var telemetryDate: String? = null
        var acked = 0
        var rejected = 0
        var quarantined = 0
        var deferred = 0

        /** Families held back for the rest of this run: retryable rejects and isolated 500s. */
        val excludedFamilies = linkedSetOf<String>()

        suspend fun execute(): SyncReport {
            device = deviceUuid() ?: return report(SyncStop.NO_DEVICE)
            releaseIntegrityQuarantine()
            if (outbox.unsentCount() == 0) return report(SyncStop.DRAINED)
            token = auth.token(userId) ?: if (auth.refresh(userId, null)) auth.token(userId) else null
            if (token == null) return report(SyncStop.AUTH_REQUIRED)

            // 1. Persisted batches first (s4.6), oldest first, each exactly as it was assembled.
            val persisted = outbox.inFlightBatches().map { it to outbox.inFlight(it) }.sortedBy { (_, rows) -> rows.minOfOrNull { it.seq } ?: 0 }
            for ((batchUuid, rows) in persisted) {
                if (rows.isEmpty()) { meta.deleteMeta(attemptKey(batchUuid)); continue }
                when (val step = send(batchUuid, rows)) {
                    is Step.Next, is Step.Split, is Step.Isolate -> Unit
                    is Step.Stop -> return step.report
                }
            }

            // 2. New batches from pending rows.
            var limit = policy.batchMaxRows
            while (true) {
                if (batches >= policy.maxBatchesPerRun) return report(SyncStop.RUN_LIMIT)
                val candidates = outbox.nextSendable(limit + 1, policy.familySkipAfter, excludedFamilies.toList())
                if (candidates.isEmpty()) break
                val chosen = assemble(candidates, limit)
                val batchUuid = ClientIds.newUuid()
                // F-SYS-072: header records are signed once, here, before the batch is persisted; every resend and every
                // later batch of the row carries the same sig (the server's batch fingerprint includes it).
                val sigs = signatures(chosen) ?: return holdForKeystore()
                val marked = db.withTransaction {
                    sigs.forEach { (seq, sig) -> outbox.setSig(seq, sig) }
                    val n = outbox.markInFlight(batchUuid, chosen.map { it.seq })
                    meta.putMeta(SyncMetaEntity(attemptKey(batchUuid), "0"))
                    n
                }
                val rows = outbox.inFlight(batchUuid)
                if (marked != chosen.size || rows.size != chosen.size) {
                    outbox.returnToPending(batchUuid, "assembly_race")
                    meta.deleteMeta(attemptKey(batchUuid))
                    return report(SyncStop.FAILED, code = "assembly_race")
                }
                when (val step = send(batchUuid, rows)) {
                    // After a split, grow back once batches go through again; after the culprit is isolated, full size.
                    is Step.Next -> limit = minOf(policy.batchMaxRows, limit * 2)
                    is Step.Isolate -> limit = policy.batchMaxRows
                    is Step.Split -> limit = step.limit
                    is Step.Stop -> return step.report
                }
            }
            return if (excludedFamilies.isNotEmpty()) {
                report(SyncStop.RETRY_LATER, retryAfterMs = Backoff.delayMs(1, policy, random))
            } else {
                report(SyncStop.DRAINED)
            }
        }

        /** Sends one persisted batch until a definitive answer or a stop. */
        suspend fun send(batchUuid: String, rows: List<OutboxEntity>): Step {
            var refreshed = false
            while (true) {
                val attempt = nextAttempt(batchUuid)
                val body = gzip(requestJson(batchUuid, rows))
                val headers = BatchHeaders(
                    attempt = attempt,
                    pendingRows = outbox.unsentCount(),
                    lastSyncError = meta.meta(KEY_LAST_ERROR),
                    deviceTimeIso = iso(clock.nowMs()),
                    configVersion = meta.meta(KEY_CONFIG_VERSION)?.toLongOrNull(),
                )
                batches++
                val result = api.send(token!!, device, batchUuid, body, headers)
                when (result) {
                    is ApiResult.Success -> {
                        val step = applyResponse(batchUuid, rows, result.value)
                        // Answered: the day's telemetry is never sent again (the server upserts by device and date).
                        telemetryDate?.let { d -> try { telemetry?.sent(d) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { } }
                        telemetryDate = null
                        return step
                    }
                    is ApiResult.NotModified -> return stopKeeping(SyncStop.FAILED, "not_modified")
                    is ApiResult.Transport -> return transport(batchUuid, rows, result.failure)
                    is ApiResult.Failure -> {
                        val code = result.problem.problemCode
                        if (result.httpStatus == 401 && !refreshed &&
                            (code == ProblemCode.ERR_TOKEN_EXPIRED || code == ProblemCode.ERR_SCOPE_CHANGED)
                        ) {
                            refreshed = true
                            if (auth.refresh(userId, token)) {
                                token = auth.token(userId) ?: return stopKeeping(SyncStop.AUTH_REQUIRED, result.problem.code)
                                continue
                            }
                        }
                        // A batch refused for its envelope (400/422 naming no record) while carrying telemetry: counted, so a
                        // day the server cannot read is dropped. Holds, 5xx, 413, auth and version answers never count.
                        // A 500 at single-family size also counts: the bisect has ruled the other families out, and a day
                        // the server chokes on must be dropped before the family's own rows run out of retries.
                        val envelopeRefusal = ((result.httpStatus == 400 || result.httpStatus == 422) &&
                            result.problem.errors.none { e -> RECORD_POINTER.containsMatchIn(e.pointer ?: "") }) ||
                            (result.httpStatus == 500 && rows.map { it.familyUuid }.distinct().size == 1)
                        if (envelopeRefusal) telemetryDate?.let { d -> try { telemetry?.failed(d) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { } }
                        return failure(batchUuid, rows, result)
                    }
                }
            }
        }

        suspend fun failure(batchUuid: String, rows: List<OutboxEntity>, f: ApiResult.Failure): Step {
            val code = f.problem.code ?: "http_${f.httpStatus}"
            return when {
                f.httpStatus == 401 && f.problem.problemCode == ProblemCode.ERR_DEVICE_PROOF_INVALID -> stopKeeping(SyncStop.BLOCKED, code)
                f.httpStatus == 401 -> stopKeeping(SyncStop.AUTH_REQUIRED, code)
                f.httpStatus == 403 -> stopKeeping(SyncStop.BLOCKED, code)
                f.httpStatus == 426 -> stopKeeping(SyncStop.UPDATE_REQUIRED, code)
                f.httpStatus == 429 || f.httpStatus == 503 -> {
                    val ra = f.meta.retryAfterS ?: f.problem.retryAfterS
                    val delay = if (ra != null) Backoff.retryAfterMs(ra, random) else Backoff.delayMs(attemptOf(batchUuid), policy, random)
                    stopKeeping(SyncStop.RETRY_LATER, code, delay)
                }
                // Same batch_uuid with other members (s3.3): only possible after the server lost the first copy's rows or
                // a bug; the rows are re-batched under a new uuid and the client_uuid registry keeps them single.
                f.httpStatus == 409 && f.problem.problemCode == ProblemCode.ERR_SYNC_BATCH_UUID_REUSED -> {
                    release(batchUuid, code)
                    Step.Next
                }
                // A strict 400/422 counts against rows only when its errors point into `/records/<i>`: those families take one
                // failure (exhausted after row_max_retries, never stuck) and are held back; the rest are re-batched. A 400
                // about the envelope (or with no pointer) is not the rows' fault: released, nothing counted.
                f.httpStatus == 400 || f.httpStatus == 422 -> {
                    val culprits = f.problem.errors.mapNotNull { e -> RECORD_POINTER.find(e.pointer ?: "")?.groupValues?.get(1)?.toIntOrNull() }
                        .filter { it in rows.indices }.map { rows[it].familyUuid }.toSet()
                    if (culprits.isEmpty()) {
                        release(batchUuid, code)
                        Step.Stop(report(SyncStop.FAILED, code = code))
                    } else {
                        isolateFamilies(batchUuid, rows, culprits, code)
                        Step.Isolate
                    }
                }
                // A failure the batch itself caused: 500 (s4.7), 413. Bisect: halves get new batch_uuids until the failing
                // family is alone; that family then takes one failure and is held back, so the families behind it still go.
                f.httpStatus >= 500 || f.httpStatus == 413 -> {
                    if (rows.map { it.familyUuid }.distinct().size > 1) {
                        release(batchUuid, code)
                        Step.Split((rows.size / 2).coerceAtLeast(1))
                    } else {
                        isolate(batchUuid, rows, code)
                        Step.Isolate
                    }
                }
                else -> {
                    release(batchUuid, code)
                    Step.Stop(report(SyncStop.FAILED, code = code))
                }
            }
        }

        /** One definitive failure for each of [rows]: back to pending (held for the run) or rejected(retry_exhausted). */
        suspend fun isolate(batchUuid: String, rows: List<OutboxEntity>, code: String) = db.withTransaction {
            for (row in rows) {
                val t = AckRules.retry(code, row.attempts + 1, policy.rowMaxRetries)
                outbox.countFailure(row.clientUuid)
                outbox.applyAck(row.clientUuid, t.state, t.code, null, iso(clock.nowMs()))
                if (t.state == OutboxState.REJECTED) rejected++ else deferred++
            }
            excludedFamilies += rows.map { it.familyUuid }
            meta.deleteMeta(attemptKey(batchUuid))
            meta.putMeta(SyncMetaEntity(KEY_LAST_ERROR, code))
        }

        /** [culprits] take one failure each; the other rows of the batch go back to pending untouched. */
        suspend fun isolateFamilies(batchUuid: String, rows: List<OutboxEntity>, culprits: Set<String>, code: String) = db.withTransaction {
            isolate(batchUuid, rows.filter { it.familyUuid in culprits }, code)
            outbox.returnToPending(batchUuid, null)
        }

        suspend fun transport(batchUuid: String, rows: List<OutboxEntity>, failure: TransportFailure): Step {
            val code = "transport_${failure.name.lowercase()}"
            if (failure == TransportFailure.MALFORMED) {
                // The server may have stored the batch and would replay the same unreadable answer: re-batch under a new
                // uuid, and count it as a failure so a server that keeps answering badly cannot loop the rows forever.
                isolate(batchUuid, rows, code)
                return Step.Stop(report(SyncStop.FAILED, code = code))
            }
            meta.putMeta(SyncMetaEntity(KEY_LAST_ERROR, code))
            return Step.Stop(report(SyncStop.OFFLINE, code = code, retryAfterMs = Backoff.delayMs(attemptOf(batchUuid), policy, random)))
        }

        suspend fun applyResponse(batchUuid: String, rows: List<OutboxEntity>, r: SyncBatchResponse): Step {
            val matches = r.batchUuid == batchUuid && r.acks.size == rows.size &&
                r.acks.indices.all { r.acks[it].clientUuid == rows[it].clientUuid }
            if (!matches) {
                isolate(batchUuid, rows, "ack_mismatch")
                return Step.Stop(report(SyncStop.FAILED, code = "ack_mismatch"))
            }
            val now = iso(clock.nowMs())
            db.withTransaction {
                rows.forEachIndexed { i, row ->
                    val ack = r.acks[i]
                    val t = AckRules.target(ack, row.attempts, policy.rowMaxRetries)
                    if (AckRules.isFailure(t)) outbox.countFailure(row.clientUuid)
                    outbox.applyAck(row.clientUuid, t.state, t.code, ack.serverId, now)
                    when (t.state) {
                        OutboxState.ACKED -> acked++
                        OutboxState.REJECTED -> rejected++
                        OutboxState.QUARANTINED -> quarantined++
                        OutboxState.PENDING -> { deferred++; excludedFamilies += row.familyUuid }
                    }
                }
                applyResolutions(r.resolutions, now)
                meta.deleteMeta(attemptKey(batchUuid))
                meta.deleteMeta(KEY_LAST_ERROR)
                meta.putMeta(SyncMetaEntity(KEY_LAST_SUCCESS, now))
                // A replay is the stored answer of an earlier send (up to 48 h old): its acks hold, its server state does not.
                if (!r.replayed) {
                    // The server's current version only tells the phone a delta exists; the held version moves with the delta.
                    meta.putMeta(SyncMetaEntity(KEY_CONFIG_VERSION_SERVER, r.configVersion.toString()))
                    // The nil generation means "unknown" (backend-core, android-core-503-and-generation s2): never stored, never compared.
                    if (r.generation != NIL_GENERATION) meta.putMeta(SyncMetaEntity(KEY_GENERATION, r.generation))
                    r.bundleVersionCurrent?.let { meta.putMeta(SyncMetaEntity(KEY_BUNDLE_CURRENT, it)) }
                    for (totals in r.serverTotals) {
                        meta.putMeta(SyncMetaEntity(KEY_SERVER_TOTALS + totals.businessDate, WireJson.requests.encodeToString(ServerTotals.serializer(), totals)))
                        // Phone time of this answer, the same `now` its acks carry: reconciliation tells rows answered
                        // later (the figures are older than them) from a real shortfall without comparing two clocks.
                        meta.putMeta(SyncMetaEntity(com.aktcl.aron.core.database.repo.ReconciliationRepository.KEY_SERVER_TOTALS_AT + totals.businessDate, now))
                    }
                    meta.putMeta(SyncMetaEntity(KEY_DAY_STATES, WireJson.requests.encodeToString(ListSerializer(RouteDayState.serializer()), r.dayStates)))
                }
            }
            val automatic = trigger != SyncTrigger.MANUAL && trigger != SyncTrigger.DAY_SUBMIT
            if (r.holdS > 0 && automatic) {
                return Step.Stop(report(SyncStop.RETRY_LATER, code = "hold", retryAfterMs = Backoff.holdMs(r.holdS, random)))
            }
            return Step.Next
        }

        /**
         * Applies quarantine resolutions (s4.5). The server delivers each once; one for a row the phone has not seen
         * quarantined yet (its batch's answer was lost and the batch is still in flight) is kept and applied as soon as
         * the row is quarantined, so it is never dropped.
         */
        suspend fun applyResolutions(delivered: List<Resolution>, now: String) {
            for (res in delivered) {
                if (AckRules.resolution(res.resolution) != null) meta.putMeta(SyncMetaEntity(RESOLUTION_PREFIX + res.clientUuid, res.resolution))
            }
            ResolutionStash.drain(db, now)
        }

        suspend fun requestJson(batchUuid: String, rows: List<OutboxEntity>): String {
            val today = BusinessDate.of(clock.nowMs()).toString()
            val dates = (rows.map { it.businessDate } + today).toSortedSet()
            val recon = com.aktcl.aron.core.database.repo.ReconciliationRepository(db) { iso(clock.nowMs()) }
            val counts = buildJsonObject { for (date in dates) put(date, recon.deviceCountsJson(date)) }
            // MoneyTotals per date (s4.4, s4.12): every batch carries them for its dates, so day_submit and "at least once
            // per date" are both covered; a few hundred bytes gzipped.
            val money = buildJsonObject { for (date in dates) put(date, recon.deviceMoney(date)) }
            val body = buildJsonObject {
                put("batch_uuid", JsonPrimitive(batchUuid))
                put("device_uuid", JsonPrimitive(device))
                put("schema_version", JsonPrimitive(ContractInfo.SCHEMA_VERSION))
                put("app_version", JsonPrimitive(appVersion))
                put("trigger", JsonPrimitive(trigger.wire))
                put("sent_at_device", JsonPrimitive(iso(clock.nowMs())))
                put("pending_rows", JsonPrimitive(outbox.unsentCount()))
                put("time_anchors", WireJson.requests.encodeToJsonElement(ANCHORS, timeAnchors().takeLast(3)))
                put("device_counts", counts)
                put("device_money", money)
                telemetryDate = null
                val day = try { telemetry?.pending() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                if (day != null) { put("telemetry", day.second); telemetryDate = day.first }
                put("records", JsonArray(rows.map { recordJson(it) }))
            }
            return body.toString()
        }

        /** The record as sent: the stored payload plus its `sig` when it has one (F-SYS-072). */
        fun recordJson(row: OutboxEntity): JsonElement {
            val payload = RECORD_JSON.parseToJsonElement(row.payloadJson)
            val sig = row.sig ?: return payload
            return JsonObject((payload as JsonObject).filterKeys { it != "sig" } + ("sig" to JsonPrimitive(sig)))
        }

        /**
         * Signs the header records among [rows] that have no sig yet; no key (not enrolled) or an unreadable payload signs
         * nothing. Null when the device is enrolled but the Keystore failed twice for a row: the caller holds the batch,
         * because a row sent unsigned is never re-signed (and the server quarantines it). The hold lasts at most
         * [SIG_HOLD_MS] of elapsed time from the first failure (counted in this boot; a reboot starts it again); after that
         * the phone is degraded and every batch goes, unsigned where signing fails, until a signature succeeds again: a
         * broken key must not keep the day's sales on the phone, and the server keeps what it quarantines for review.
         */
        suspend fun signatures(rows: List<OutboxEntity>): List<Pair<Long, String>>? {
            val signer = recordSigner ?: return emptyList()
            // Only rows never sent: the server's registry hash of a record includes sig, so a row that once went out
            // unsigned (before enrolment, or a Keystore miss) and was parked must come back byte-identical, or it is
            // quarantined as payload_conflict (F-SYS-072 checker). Keystore calls are blocking: off the caller's thread.
            val fresh = rows.filter { it.sig == null && it.recordType in SIGNED_TYPES && it.attempts == 0 && it.lastCode == null }
            if (fresh.isEmpty()) return emptyList()
            var failed = false
            val sigs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fresh.mapNotNull { row ->
                try {
                    val payload = RECORD_JSON.parseToJsonElement(row.payloadJson) as? JsonObject ?: return@mapNotNull null
                    val proof = com.aktcl.aron.core.network.ProofStrings.record(row.recordType, row.clientUuid, payload)
                    var r = signer.attempt(proof)
                    if (r is ProofResult.Failed) r = signer.attempt(proof) // one immediate retry: most misses are transient
                    when (r) {
                        is ProofResult.Signed -> row.seq to r.value
                        ProofResult.NotEnrolled -> null
                        ProofResult.Failed -> { failed = true; null }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } }
            val hold = SigHold.parse(meta.meta(KEY_SIG_HOLD))
            if (!failed) {
                if (hold != null) meta.deleteMeta(KEY_SIG_HOLD)
                return sigs
            }
            val now = clock.elapsedRealtimeMs()
            val next = when {
                hold == null -> SigHold(now, 1, false)
                hold.degraded -> hold // only a batch with no failure ends it: a partly working key must not re-hold
                now < hold.sinceElapsedMs -> SigHold(now, 1, false) // rebooted: elapsed time restarted
                now - hold.sinceElapsedMs >= SIG_HOLD_MS -> hold.copy(degraded = true)
                else -> hold.copy(runs = hold.runs + 1)
            }
            meta.putMeta(SyncMetaEntity(KEY_SIG_HOLD, next.wire()))
            if (!next.degraded) return null
            meta.putMeta(SyncMetaEntity(KEY_LAST_ERROR, CODE_KEY_UNAVAILABLE))
            return sigs
        }

        /** Nothing was marked in flight: the rows stay pending, unsigned and unsent, for the next run to sign. */
        suspend fun holdForKeystore(): SyncReport {
            meta.putMeta(SyncMetaEntity(KEY_LAST_ERROR, CODE_KEY_UNAVAILABLE))
            val runs = SigHold.parse(meta.meta(KEY_SIG_HOLD))?.runs ?: 1
            return report(SyncStop.RETRY_LATER, code = CODE_KEY_UNAVAILABLE, retryAfterMs = Backoff.delayMs(runs, policy, random))
        }

        suspend fun release(batchUuid: String, code: String) = db.withTransaction {
            outbox.returnToPending(batchUuid, code)
            meta.deleteMeta(attemptKey(batchUuid))
            meta.putMeta(SyncMetaEntity(KEY_LAST_ERROR, code))
        }

        /** Stops with the batch kept in flight: the next run resends it unchanged. */
        suspend fun stopKeeping(stop: SyncStop, code: String?, retryAfterMs: Long? = null): Step {
            code?.let { meta.putMeta(SyncMetaEntity(KEY_LAST_ERROR, it.take(80))) }
            return Step.Stop(report(stop, code = code, retryAfterMs = retryAfterMs))
        }

        suspend fun nextAttempt(batchUuid: String): Int {
            val attempt = attemptOf(batchUuid) + 1
            meta.putMeta(SyncMetaEntity(attemptKey(batchUuid), attempt.toString()))
            return attempt
        }

        suspend fun attemptOf(batchUuid: String): Int = meta.meta(attemptKey(batchUuid))?.toIntOrNull() ?: 0

        suspend fun report(stop: SyncStop, code: String? = null, retryAfterMs: Long? = null) = SyncReport(
            stop = stop, batches = batches, acked = acked, rejected = rejected, quarantined = quarantined, deferred = deferred,
            unsent = outbox.unsentCount(), retryAfterMs = retryAfterMs, code = code,
        )
    }

    /**
     * Takes rows in order up to [limit] rows and [SyncPolicy.batchMaxRawBytes]; when the cut would split a family whose rows
     * are adjacent, it moves back to the family boundary (as long as one row remains), so a header travels with its children.
     */
    internal fun assemble(candidates: List<OutboxEntity>, limit: Int): List<OutboxEntity> {
        val taken = ArrayList<OutboxEntity>()
        var bytes = 0
        for (row in candidates) {
            val size = row.payloadJson.toByteArray(Charsets.UTF_8).size + 1
            if (taken.size >= limit || (taken.isNotEmpty() && bytes + size > policy.batchMaxRawBytes)) break
            taken += row
            bytes += size
        }
        if (taken.size < candidates.size) {
            val next = candidates[taken.size]
            if (next.familyUuid == taken.last().familyUuid) {
                val boundary = taken.indexOfLast { it.familyUuid != next.familyUuid }
                if (boundary >= 0) return taken.subList(0, boundary + 1).toList()
            }
        }
        return taken
    }

    /** The F-SYS-072 hold of header rows on a Keystore failure; the database file is per user, so this is too. */
    internal data class SigHold(val sinceElapsedMs: Long, val runs: Int, val degraded: Boolean) {
        fun wire() = "$sinceElapsedMs:$runs:${if (degraded) 1 else 0}"
        companion object {
            fun parse(v: String?): SigHold? = v?.split(':')?.takeIf { it.size == 3 }?.let { p ->
                val since = p[0].toLongOrNull() ?: return null
                SigHold(since, p[1].toIntOrNull() ?: 1, p[2] == "1")
            }
        }
    }

    sealed interface Step {
        data object Next : Step
        data class Split(val limit: Int) : Step
        data object Isolate : Step
        data class Stop(val report: SyncReport) : Step
    }

    companion object {
        /** Header records that carry `sig` (contract RecordEnvelope.sig; backend TypeRules.signedHeader). */
        val SIGNED_TYPES = setOf(
            "attendance_event", "stock_movement", "visit", "memo", "memo_void", "due_collection", "outlet_change_request", "redemption", "gift_photo",
        )
        const val KEY_LAST_ERROR = "sync.last_error"
        /** F-SYS-072: header rows held because the enrolled key could not sign them (`<since elapsed ms>:<runs>:<0|1 degraded>`). */
        const val KEY_SIG_HOLD = "sync.sig_hold.v1"
        /** Longest hold before unsigned headers go (the server quarantines them for review rather than the phone keeping them). */
        const val SIG_HOLD_MS = 30 * 60_000L
        const val CODE_KEY_UNAVAILABLE = "device_key_unavailable"
        /** F-SYS-072: rounds of device_integrity_failed releases in this episode (`<rounds>:<business date>`). */
        const val KEY_INTEGRITY_RELEASE = "sync.integrity_release.v1"
        const val INTEGRITY_RELEASE_ROUNDS = 7
        const val KEY_CONFIG_VERSION = ReferenceRepository.KEY_CONFIG_VERSION
        const val KEY_CONFIG_VERSION_SERVER = "sync.config_version_server"
        const val KEY_GENERATION = "sync.server_generation"
        /** `X-Server-Generation` of a replica that has not read it yet: unknown, never a reason to re-send (F-SYS-047). */
        const val NIL_GENERATION = "00000000-0000-4000-8000-000000000000"
        const val KEY_BUNDLE_CURRENT = "sync.bundle_version_current"
        const val KEY_SERVER_TOTALS = com.aktcl.aron.core.database.repo.ReconciliationRepository.KEY_SERVER_TOTALS
        const val KEY_DAY_STATES = "sync.day_states"
        const val KEY_LAST_SUCCESS = "sync.last_success_at"
        private const val ATTEMPT_PREFIX = "sync.batch_attempt."
        internal const val RESOLUTION_PREFIX = ReferenceRepository.KEY_RESOLUTION_PREFIX

        private val RECORD_POINTER = Regex("^/records/(\\d+)(?:/|$)")

        fun attemptKey(batchUuid: String) = ATTEMPT_PREFIX + batchUuid

        private val ANCHORS = kotlinx.serialization.builtins.ListSerializer(TimeAnchor.serializer())
        private val RECORD_JSON = Json
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        fun iso(epochMs: Long): String = ISO.format(Instant.ofEpochMilli(epochMs))

        private val locks = ConcurrentHashMap<Long, Mutex>()
        private fun lockOf(userId: Long): Mutex = locks.getOrPut(userId) { Mutex() }

        internal fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }
    }
}
