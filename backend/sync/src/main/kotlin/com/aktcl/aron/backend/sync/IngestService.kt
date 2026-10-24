package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RecordHandlers
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.isTransientDbFailure
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ContractInfo
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import com.aktcl.aron.backend.platform.DayPlan
import kotlinx.serialization.json.jsonArray
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The verified uploader: identity only from the token and the device row, never from the body (docs/24 s4.1 item 3). */
data class Uploader(
    val userId: Long,
    val role: Role,
    val scopeVersion: Long,
    /** The uploading phone; null only for a paper backfill keyed on the web for this user ([IngestService.ingestManual]). */
    val deviceId: Long?,
    val deviceUuid: String?,
    /** The device's key for record `sig` checks; null for a phone without a usable key (dev database only). */
    val deviceKey: ECPublicKey?,
)

/** The uploading device's standing for the N-027 gate, with the two switches, read once per batch. */
internal data class DeviceGate(val notEnrolled: Boolean, val verdict: String, val requireEnrolled: Boolean, val requireIntegrity: Boolean)

/**
 * Idempotent ingest of one sync batch (F-API-006, docs/24 s3.3, s4.4 to s4.6):
 *
 * - **Batch replay.** `(device, batch_uuid)` is stored with the fingerprint of its record set (SHA-256 over the sorted
 *   `client_uuid:payload_sha256` lines) and the gzip response. The same set again returns the stored response with
 *   `replayed: true`; another set under the same `batch_uuid` is `409 ERR_SYNC_BATCH_UUID_REUSED`.
 * - **Records.** Processed in array order; consecutive records of one family share one transaction, each record in its
 *   own savepoint, so a rejected child never rolls back its accepted header. `ingest_registry` (client_uuid PK) is the
 *   one uniqueness point: same uuid and hash is `duplicate`, same uuid and another hash is quarantined
 *   `payload_conflict`, and a record is never applied twice or overwritten.
 * - **Nothing is dropped.** Every record ends accepted/duplicate, rejected (kept in `sync_rejected`) or quarantined
 *   (kept in `sync_quarantine`); exactly one ack per record in request order.
 * - An unexpected error inside a family rolls the family back and acks its records `rejected(server_error)`,
 *   retryable, parked for a resend; the rest of the batch goes on (poison-row isolation).
 */
class IngestService(
    private val db: Database,
    private val config: ServerConfig,
    private val reach: ReachResolver,
    private val clock: AronClock = AronClock.SYSTEM,
    private val generation: () -> String = { com.aktcl.aron.backend.platform.NIL_GENERATION },
    /** Type-specific rules and side effects registered by other modules (the ingest extension point). */
    private val handlers: RecordHandlers = RecordHandlers.NONE,
) {
    private val log = LoggerFactory.getLogger("aron.sync.ingest")
    /** Creates a route-day ingest receives records for before the planning job or a bundle did (F-SYS-056). */
    private val routeDays = RouteDayPlanningJob(db, config, clock)

    /** Outcome of one record before the ack is built. */
    private data class Outcome(val status: String, val code: RecordOutcomeCode? = null, val serverId: Long? = null, val retryable: Boolean? = null) {
        companion object {
            fun accepted(id: Long?) = Outcome("accepted", null, id)
            fun duplicate(id: Long?) = Outcome("duplicate", null, id)
            fun of(code: RecordOutcomeCode) = Outcome(code.status.wire, code, null)
        }
    }

    /** One record as received, with what the batch fingerprint and the registry need. */
    private class Rec(val index: Int, val json: JsonObject) {
        val clientUuid: String = uuidKey(json)
        val type: String = (json["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        val family: String = (json["family_uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: clientUuid
        /** Set when the record cannot be canonicalised (a number outside the double range): a per-record schema error. */
        val canonError: String? = runCatching { Jcs.canonicalize(json); null }.getOrElse { "not canonicalisable: ${it.message?.take(80)}" }
        /** The whole envelope: the batch fingerprint (a replayed batch is byte-for-byte the same records). */
        val fullHash: ByteArray = if (canonError == null) Jcs.sha256(json) else sha256(json.toString().toByteArray())
        val hashHex: String = fullHash.joinToString("") { "%02x".format(it) }
        /**
         * The registry hash leaves `sig` out (android-core request item 4): a row first sent unsigned (before enrolment,
         * a Keystore miss) and later signed is the same record, never payload_conflict. Rows registered before this
         * change carry [fullHash], which still matches.
         */
        val hash: ByteArray = if ("sig" !in json || canonError != null) fullHash else Jcs.sha256(JsonObject(json.filterKeys { it != "sig" }))
    }

    /** Per-batch caches. */
    private inner class Ctx(val up: Uploader, val batchUuid: String, val now: Instant, val resync: ResyncWindow? = null, val manual: Manual? = null, val release: RecordOutcomeCode? = null, val reviewed: Boolean = release != null) {
        /** A reviewer's accept ([acceptQuarantined]) overrides the check that raised [release], and only that one. */
        fun overrides(vararg codes: RecordOutcomeCode): Boolean = release != null && release in codes
        /** Handlers' after-commit calls of the current family, by record index (dropped when the record rolls back). */
        val afterCommit = ArrayList<Triple<Int, com.aktcl.aron.backend.platform.RecordHandler, IngestRecord>>()
        /** Route-days this batch touched (F-SYS-016), settled at its end. */
        val touched = DayStates.Touched()
        val today: LocalDate = BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
        private val reaches = HashMap<LocalDate, Reach>()
        fun reachOn(d: LocalDate): Reach = reaches.getOrPut(d) { reach.reach(up.userId, up.role, up.scopeVersion, d) }
        val backdateDays: Long = runCatching { config.int("cfg.sync.max_backdate_days") }.getOrDefault(7).toLong()
        /** The oldest business date inside the backdate window (F-SYS-090: calendar or working days), read once per batch. */
        // A paper backfill counts its own window the same way (calendar or working days, F-SYS-090; lead 2026-10-15).
        val backdateFloor: LocalDate = backdateFloor(up.userId, today, manual?.windowDays ?: backdateDays)
        val parkedTtlDays: Long = runCatching { config.int("cfg.sync.parked_ttl_days") }.getOrDefault(7).toLong()
        /** Read once per batch; a missing or unknown value is `record`, which never drops a sale. */
        val signatureMode: SignatureMode = SignatureMode.of(runCatching { config.string("cfg.sec.record_signature_mode") }.getOrNull())
        /** N-027: the device's enrolment and Play Integrity standing and the two gates, read once per batch. */
        val gate: DeviceGate = if (manual != null) DeviceGate(false, "pass", false, false) else deviceGate(up)
        /** F-SYS-091: the device's applied config versions (ack capture time, version), read once per batch on first use. */
        val acks: List<Pair<Instant, Long>> by lazy {
            // A flag, never a gate: a failed read means "no acks" (no flag), never a parked sale.
            runCatching { up.deviceId?.let { deviceAcks(it) } ?: emptyList() }.onFailure { log.warn("config acks not read device_id=${up.deviceId} cause=${it.javaClass.simpleName}") }.getOrDefault(emptyList())
        }
        /** Memo arithmetic failures of the current family segment (client_uuid to detail). */
        var arith: Map<String, String> = emptyMap()
        /** Memo content fingerprints of the current segment (s4.5: same outlet, lines and minute). */
        var memoFps: Map<String, ByteArray> = emptyMap()
        /** Records of the current segment parked `qty_invalid` for a pack size that is not the SKU's (client_uuid to detail). */
        var packHeld: Map<String, String> = emptyMap()
        /**
         * s7.5: the uploader's login (memo number prefix) and the block size, read once per batch on first use. The size
         * is the largest ever set globally (cfg_value keeps every row), never below the current one: a phone keeps the
         * size it bound with, so a lowered value must not refuse numbers already printed (the four blocks always span
         * `1 .. 4 x size`, so the largest size covers every smaller one).
         */
        val memoNoRule: MemoNumbers.Rule by lazy {
            db.jdbi.withHandle<MemoNumbers.Rule, Exception> { h ->
                val everSet = h.createQuery(
                    "SELECT max(least(floor((value #>> '{}')::numeric), 999))::int FROM app.cfg_value WHERE key = 'cfg.memo.seq_block_size' AND scope_type = 'global' AND jsonb_typeof(value) = 'number'",
                ).mapTo(Int::class.javaObjectType).findOne().orElse(null) ?: 0
                MemoNumbers.Rule(
                    h.createQuery("SELECT username FROM app.app_user WHERE id = :u").bind("u", up.userId).mapTo(String::class.java).one(),
                    maxOf(config.int("cfg.memo.seq_block_size"), everSet).coerceIn(100, 999),
                )
            }
        }
    }

    fun ingest(up: Uploader, req: SyncBatchRequest): SyncBatchResponse {
        val now = clock.now()
        if (req.schema_version > ContractInfo.SCHEMA_VERSION) {
            throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_SCHEMA_VERSION, "schema_version ${req.schema_version} is above ${ContractInfo.SCHEMA_VERSION}")
        }
        val recs = req.records.mapIndexed { i, r -> Rec(i, r) }
        val fingerprint = sha256(recs.map { "${it.clientUuid}:${it.hashHex}" }.sorted().joinToString("\n").toByteArray())

        replayOrClaim(up, req, recs.size, fingerprint, now)?.let { return it }

        val ctx = Ctx(up, req.batch_uuid, now, resyncWindow(req.trigger, now))
        val outcomes = runFamilies(ctx, recs)
        val acks = acksOf(recs, outcomes)
        val response = db.jdbi.withHandle<SyncBatchResponse, Exception> { h ->
            val dates = ((recs.mapNotNull { r -> (r.json["business_date"] as? JsonPrimitive)?.content?.let { runCatching { LocalDate.parse(it) }.getOrNull() } } + ctx.today)
                .filter { it != ctx.today }.distinct().sortedDescending().take(9) + ctx.today).distinct()
            SyncBatchResponse(
                batch_uuid = req.batch_uuid, replayed = false, received_at = now.wire(), acks = acks,
                summary = AckSummary(acks.count { it.status == "accepted" }, acks.count { it.status == "duplicate" }, acks.count { it.status == "rejected" }, acks.count { it.status == "quarantined" }),
                server_totals = dates.sorted().map { serverTotals(h, up.userId, it, now) },
                day_states = dayStates(h, up.userId, dates),
                resolutions = resolutions(h, up.userId, now),
                hold_s = runCatching { config.int("cfg.ops.sync_hold_s") }.getOrDefault(0).coerceIn(0, 900),
                config_version = config.configVersion(), bundle_version_current = null, generation = generation(),
            )
        }
        db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                """
                UPDATE app.sync_batch SET response_gz = :gz, completed_at = :now, counts = CAST(:counts AS jsonb)
                WHERE device_id = :d AND batch_uuid = CAST(:b AS uuid)
                """.trimIndent(),
            ).bind("gz", gzip(ResponseJson.encodeToString(SyncBatchResponse.serializer(), response).toByteArray()))
                .bind("now", ts(now)).bind("counts", ResponseJson.encodeToString(AckSummary.serializer(), response.summary))
                .bind("d", up.deviceId).bind("b", req.batch_uuid).execute()
        }
        return response
    }

    /** A paper backfill run: the web user who keys it and the cfg.entry.paper_backfill_window_days window. */
    class Manual(val actorUserId: Long, val windowDays: Long)

    /**
     * The acks of a paper backfill in request order. [stored] is false when nothing of the entry was written: one record
     * was not accepted (its ack says why; the others' acks say what they would have been) or the memo number was taken
     * ([duplicateMemoNo], every ack `duplicate` with code `memo_no_duplicate`, the memo ack carrying the stored memo's id).
     */
    data class ManualResult(val acks: List<RecordAck>, val duplicateMemoNo: Boolean, val stored: Boolean)

    /**
     * F-API-038 paper backfill (docs/24 s8.5 item 4, D24-73): a web [actor] (SUPPORT, ADMIN, SUPERADMIN) keys one
     * printed memo of a dead-phone day for the active SR [forUserId], through the same per-record checks as a phone
     * batch (registry by client_uuid, references, the SR's scope on the date, money, parents, the writer).
     *
     * One entry is exactly one `visit` (`visit_kind` web_entry, no `fix`, `geo.verdict` no_fix), its one new `memo`
     * (never an edit; no discount or QC lines) and that memo's `memo_line`s (`line_count` of them): one `family_uuid`, one `route_id`, one
     * business date, the memo naming the visit and every line the memo, the same outlet; anything else is 400. The
     * actor's own reach must cover the route and the outlet on the date (403).
     *
     * All or nothing, in one transaction: the memo number is locked and checked inside it; a number already stored under
     * another uuid (phone or an earlier backfill) or any record not accepted (window, scope, money, payload conflict)
     * rolls the whole entry back ([ManualResult.stored] false). A resend of a stored entry is all `duplicate`; stored
     * records resent with new ones (new line uuids under a stored memo) are 409 and nothing is written.
     * No device: no batch replay row, device gate, signature or config-ack checks, no content fingerprint (the memo
     * number is the identity). Rows are marked `entry_source = manual` / `entered_by_user_id` once db adds the columns
     * (docs/requests/backend-core-memo-source.md), and the registry carries the flag `manual_entry` once allowed.
     */
    fun ingestManual(actor: AronPrincipal, forUserId: Long, entryUuid: String, records: List<JsonObject>): ManualResult {
        if (actor.isPhone || actor.role !in MANUAL_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "paper backfill is keyed by SUPPORT, ADMIN or SUPERADMIN on the web")
        fun bad(ptr: String, why: String): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, why, errors = listOf(FieldError(ptr, "invalid_value")))
        if (!UUID_V4.matches(entryUuid)) bad("/entry_uuid", "entry_uuid is not a UUID v4")
        if (records.size < 3 || records.size > MANUAL_MAX_RECORDS) bad("/records", "a visit, a memo and 1..${MANUAL_MAX_RECORDS - 2} lines")
        val now = clock.now()
        val recs = records.mapIndexed { i, r -> Rec(i, r) }
        if (recs.map { it.clientUuid }.toSet().size != recs.size) bad("/records", "a client_uuid appears twice")
        recs.forEach { r -> if (r.type !in MANUAL_TYPES) bad("/records/${r.index}/type", "a paper backfill holds visit, memo and memo_line records only") }
        val visit = recs.singleOrNull { it.type == "visit" } ?: bad("/records", "exactly one visit")
        val memo = recs.singleOrNull { it.type == "memo" } ?: bad("/records", "exactly one memo")
        val lines = recs.filter { it.type == "memo_line" }
        fun p(r: Rec) = r.json["payload"] as? JsonObject ?: bad("/records/${r.index}/payload", "payload must be an object")
        val vp = p(visit); val mp = p(memo)
        if (vp.str("visit_kind") != "web_entry") bad("/records/${visit.index}/payload/visit_kind", "a paper visit is web_entry")
        if (vp["fix"].let { it != null && it !is JsonNull }) bad("/records/${visit.index}/payload/fix", "a paper visit has no fix")
        if ((vp["geo"] as? JsonObject)?.str("verdict") != "no_fix") bad("/records/${visit.index}/payload/geo/verdict", "a paper visit's verdict is no_fix")
        for (k in listOf("supersedes_client_uuid", "edit_fix", "edit_reason_code")) {
            if (mp[k].let { it != null && it !is JsonNull }) bad("/records/${memo.index}/payload/$k", "a paper backfill keys a new memo, never an edit")
        }
        if (mp.str("visit_client_uuid") != visit.clientUuid) bad("/records/${memo.index}/payload/visit_client_uuid", "the memo names the entry's visit")
        if (vp.long("outlet_id") == null || mp.long("outlet_id") != vp.long("outlet_id")) bad("/records/${memo.index}/payload/outlet_id", "the memo's outlet is the visit's")
        lines.forEach { l -> if (p(l).str("memo_client_uuid") != memo.clientUuid) bad("/records/${l.index}/payload/memo_client_uuid", "every line names the entry's memo") }
        if (mp.long("line_count") != lines.size.toLong()) bad("/records/${memo.index}/payload/line_count", "line_count is the number of lines sent")
        // Discounts are deferred (docs/27) and QC is keyed elsewhere: a paper memo has neither, so the family check covers it all.
        for (k in listOf("discount_line_count", "qc_line_count")) if (mp.long(k) != 0L) bad("/records/${memo.index}/payload/$k", "$k is 0 on a paper memo")
        // One family, one route, one date: the whole memo is judged and stored together (money checks see every line).
        if (recs.map { it.family }.toSet().size != 1) bad("/records", "one family_uuid for the entry")
        val routeId = recs.map { it.json.long("route_id") }.toSet().singleOrNull() ?: bad("/records", "one route_id on every record")
        val bdText = recs.map { it.json.str("business_date") }.toSet().singleOrNull() ?: bad("/records", "one business_date for the entry")
        val bd = runCatching { LocalDate.parse(bdText) }.getOrNull() ?: bad("/records/0/business_date", "business_date")
        recs.forEach { r -> if (r.json["acting_for_user_id"].let { it != null && it !is JsonNull }) bad("/records/${r.index}/acting_for_user_id", "a paper backfill is keyed for the SR itself") }
        val memoNo = mp.str("memo_no") ?: bad("/records/${memo.index}/payload/memo_no", "memo_no is required")

        val user = db.jdbi.withHandle<Triple<Role, Long, String>?, Exception> { h ->
            h.createQuery("SELECT role, scope_version, status FROM app.app_user WHERE id = :u").bind("u", forUserId)
                .map { rs, _ -> Triple(Role.entries.first { it.wire == rs.getString(1) }, rs.getLong(2), rs.getString(3)) }.findOne().orElse(null)
        } ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "user not found")
        if (user.first != Role.SR) bad("/for_user_id", "a paper backfill is keyed for an SR")
        if (user.third != "active") bad("/for_user_id", "the SR is not active")

        // The actor's reach on the date (server-side scope, s8.4): the route and the outlet, or 403 and nothing is written.
        val rc = reach.reach(actor.userId, actor.role, actor.scopeVersion, bd)
        db.jdbi.useHandle<Exception> { h ->
            val zone = h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", routeId).mapTo(Long::class.java).findOne().orElse(null)
            if (zone != null && !rc.coversRoute(routeId, zone)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "route $routeId is outside your reach on $bd")
            val o = h.createQuery("SELECT route_id, zone_id FROM app.outlet WHERE id = :o").bind("o", vp.long("outlet_id")!!)
                .map { rs, _ -> (rs.getObject(1) as Long?) to rs.getLong(2) }.findOne().orElse(null)
            if (o != null && !(rc.coversZone(o.second) || (o.first != null && o.first in rc.routeIds))) {
                throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "outlet ${vp.long("outlet_id")} is outside your reach on $bd")
            }
        }

        val window = runCatching { config.int("cfg.entry.paper_backfill_window_days") }.getOrDefault(7).coerceIn(1, 30).toLong()
        val up = Uploader(forUserId, user.first, user.second, null, null, null)
        val ctx = Ctx(up, entryUuid, now, manual = Manual(actor.userId, window))
        val ordered = recs.sortedBy { MANUAL_TYPES.indexOf(it.type) }
        val all = ordered.map { it.json }
        ctx.arith = MemoChecks.familyMismatches(all) + db.jdbi.withHandle<Map<String, String>, Exception> { h -> MemoChecks.unknownSkuSiblings(h, all) }
        ctx.packHeld = db.jdbi.withHandle<Map<String, String>, Exception> { h -> MemoChecks.packFactorHolds(h, all) }
        ctx.memoFps = emptyMap()
        val outcomes = arrayOfNulls<Outcome>(recs.size)
        // Not the SR's own number (s7.5): the memo is refused memo_no_invalid in the run, never answered as another
        // user's stored number (duplicate_memo_no would name that user's memo).
        val ownNumber = MemoNumbers.problem(ctx.memoNoRule, memoNo, bd) == null
        var taken: Long? = null
        var stored = false
        var attempt = 0
        val start = clock.now()
        while (true) {
            ctx.afterCommit.clear()
            taken = null
            try {
                db.jdbi.inTransaction<Unit, Exception> { h ->
                    // One printed number at a time: two keyings of it, or a keying and the phone's upload, never both store.
                    h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 6))", "memo_no:$memoNo:$bd")
                    if (ownNumber) taken = h.createQuery("SELECT id FROM app.memo WHERE memo_no = :n AND business_date = :d AND client_uuid <> CAST(:c AS uuid) LIMIT 1")
                        .bind("n", memoNo).bind("d", bd).bind("c", memo.clientUuid).mapTo(Long::class.java).findOne().orElse(null)
                    if (taken != null) throw ManualRollback()
                    ordered.forEach { r -> outcomes[r.index] = processInSavepoint(h, ctx, r) }
                    if (outcomes[memo.index]?.code == RecordOutcomeCode.MEMO_NO_DUPLICATE) {
                        taken = h.createQuery("SELECT id FROM app.memo WHERE memo_no = :n AND business_date = :d LIMIT 1").bind("n", memoNo).bind("d", bd).mapTo(Long::class.java).findOne().orElse(null)
                        throw ManualRollback()
                    }
                    if (outcomes.any { it!!.status !in setOf("accepted", "duplicate") }) throw ManualRollback()
                    // A stored entry is resent whole or not at all: new lines under a stored memo would over-fill it.
                    if (outcomes.any { it!!.status == "duplicate" } && outcomes.any { it!!.status == "accepted" }) {
                        throw ApiProblem(ProblemCode.ERR_CONFLICT, "this memo is already stored with other records; resend the stored entry unchanged")
                    }
                }
                stored = true
                break
            } catch (e: Exception) {
                if (e is ManualRollback || e.cause is ManualRollback) { ctx.afterCommit.clear(); break }
                if (++attempt >= FAMILY_TRIES || !isTransientDbFailure(e) || clock.now().isAfter(start.plusMillis(FAMILY_RETRY_BUDGET_MS))) throw e
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(20, 101))
            }
        }
        if (taken != null) {
            val code = RecordOutcomeCode.MEMO_NO_DUPLICATE.wire
            return ManualResult(recs.map { r -> RecordAck(r.clientUuid, r.type, "duplicate", code, null, "sync.outcome.$code", if (r === memo) taken else null) }, true, false)
        }
        if (stored) {
            ctx.afterCommit.forEach { (_, hd, rec) -> runCatching { hd.afterCommit(rec) }.onFailure { log.warn("afterCommit failed type=${rec.type}", it) } }
            DayStates.settleMinutes = runCatching { config.int("cfg.day.submit_settle_timeout_min").toLong() }.getOrDefault(30L)
            runCatching { db.jdbi.useTransaction<Exception> { h -> DayStates.afterBatch(h, up.userId, ctx.touched, now) } }
                .onFailure { log.error("day states failed entry_uuid=$entryUuid", it) }
        }
        return ManualResult(acksOf(recs, outcomes), false, stored)
    }

    /** Rolls a paper backfill back whole (no stack trace: it is control flow). */
    private class ManualRollback : RuntimeException(null, null, false, false)

    /**
     * The ingest hook of `POST /v1/admin/quarantine/{id}/resolve` with `accept` or `accept_with_fix` (docs/24 s4.5,
     * `QuarantineAcceptor` in analytics; docs/requests/backend-reports-db-indexes-and-events.md item 1).
     *
     * Re-runs the quarantined record (or [fixedRecord], which keeps its `client_uuid`, `type`, `family_uuid` and
     * `business_date`) through the same per-record path as the original uploader and device, with only the check that
     * raised the item's code overridden by the reviewer: the backdate window, scope (`scope_out_of_reach`,
     * `no_assignment_on_date`), the device gate and record signature, the content fingerprint, or a handler's own
     * quarantine code. Money is never overridden: `arithmetic_mismatch` and `memo_no_duplicate` need a fix that passes,
     * and a `payload_conflict` copy cannot be accepted (the first copy keeps the uuid).
     *
     * Runs in the caller's transaction when one is open on this thread (Jdbi reuses the thread's handle; OpsService holds
     * the item's row lock): stored means the registry row is `accepted` under the same uuid, user and device (so the
     * phone's resend is `duplicate` and its digest reconciles) and the item is `accepted` / `accepted_with_fix`.
     * Children held with it (`parent <uuid> is quarantined`, same code) are released in the same transaction, each in
     * its own savepoint: one that fails for its own reason stays open. If the record itself is not stored, nothing
     * changes and the answer is 409 `ERR_REQUEST_STATE` naming the outcome.
     */
    fun acceptQuarantined(quarantineId: Long, fixedRecord: JsonObject?, resolverUserId: Long, reason: String) {
        val now = clock.now()
        db.jdbi.inTransaction<Unit, Exception> { h ->
            val item = heldItem(h, "id = :id", quarantineId) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such quarantine item")
            if (item.status != "open") throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "already resolved as ${item.status}")
            val code = code(item.code) ?: throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "unknown quarantine code ${item.code}")
            if (code == RecordOutcomeCode.PAYLOAD_CONFLICT) {
                throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "a payload_conflict copy cannot be accepted: the first copy keeps client_uuid ${item.clientUuid}")
            }
            if (fixedRecord != null) {
                // docs/24 s8.6: a fix of a data-entry (money) record needs a second person; the contract's resolve request
                // has no approver yet, so such a fix is refused rather than applied by one person.
                if (item.payload.str("type") in MONEY_TYPES) {
                    throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "a fix of a ${item.payload.str("type")} needs a second person (maker-checker), which is not available yet; discard it and have the record keyed again")
                }
                for (k in listOf("client_uuid", "type", "family_uuid", "business_date", "route_id", "acting_for_user_id")) {
                    if (fixedRecord[k] != item.payload[k]) {
                        throw ApiProblem(ProblemCode.ERR_VALIDATION, "fixed_record keeps the record's $k", errors = listOf(FieldError("/fixed_record/$k", "invalid_value")))
                    }
                }
                // Neither a fix nor its scope override moves a record to another outlet.
                if ((fixedRecord["payload"] as? JsonObject)?.get("outlet_id") != (item.payload["payload"] as? JsonObject)?.get("outlet_id")) {
                    throw ApiProblem(ProblemCode.ERR_VALIDATION, "fixed_record keeps the record's outlet_id", errors = listOf(FieldError("/fixed_record/payload/outlet_id", "invalid_value")))
                }
            }
            val rec = Rec(0, fixedRecord ?: item.payload)
            // The children held with it, parents before children, so a memo's equations see its lines (s7.4).
            val children = heldChildren(h, item.clientUuid, item.code)
            val family = listOf(rec.json) + children.map { it.payload }
            val arith = MemoChecks.familyMismatches(family) + MemoChecks.unknownSkuSiblings(h, family)
            val fps = MemoChecks.memoFingerprints(family)
            val packs = MemoChecks.packFactorHolds(h, family)
            // Every held record runs as its own uploader and device (a TSO's collection on an SR's memo stays the TSO's).
            // The reviewer judged the item's own record: a child of another uploader gets no override of its own checks
            // (its reach, cross-user parent rule, device gate), only the window it was inside when received.
            val ctxs = HashMap<Pair<Long, Long?>, Ctx>()
            fun ctxOf(it: HeldItem): Ctx = ctxs.getOrPut(it.userId to it.deviceId) {
                val batch = it.batchUuid ?: UUID.nameUUIDFromBytes("quarantine:${it.id}".toByteArray()).toString()
                val same = it.userId == item.userId && it.deviceId == item.deviceId
                Ctx(uploaderOf(h, it.userId, it.deviceId), batch, now, release = code.takeIf { same }, reviewed = true).also { c -> c.arith = arith; c.memoFps = fps; c.packHeld = packs }
            }
            val ctx = ctxOf(item)
            val up = ctx.up

            val outcome = release(h, ctx, rec)
            if (outcome.status != "accepted" && outcome.status != "duplicate") {
                throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the record is still not stored: ${outcome.status} ${outcome.code?.wire ?: ""}".trim())
            }
            closeItem(h, item.id, if (fixedRecord != null) "accepted_with_fix" else "accepted", fixedRecord, resolverUserId, reason, now)
            for (child in children) {
                val sp = "release_${child.id}"
                h.savepoint(sp)
                val o = runCatching { release(h, ctxOf(child), Rec(0, child.payload)) }.getOrElse { e -> if (isTransientDbFailure(e) || e is ApiProblem) throw e; null }
                if (o != null && (o.status == "accepted" || o.status == "duplicate")) {
                    closeItem(h, child.id, "accepted", null, resolverUserId, "released with parent ${item.clientUuid}", now)
                    h.release(sp)
                } else {
                    // Held for a reason of its own (or a failure): it stays open for its own review.
                    h.rollbackToSavepoint(sp)
                    log.info("held child stays quarantined client_uuid=${child.clientUuid} parent=${item.clientUuid} outcome=${o?.status} ${o?.code?.wire}")
                }
            }
            // Day states follow but never decide the outcome (as after a batch).
            DayStates.settleMinutes = runCatching { config.int("cfg.day.submit_settle_timeout_min").toLong() }.getOrDefault(30L)
            h.savepoint("release_day")
            try {
                ctxs.values.forEach { c -> DayStates.afterBatch(h, c.up.userId, c.touched, now) }
                h.release("release_day")
            } catch (e: Exception) {
                if (isTransientDbFailure(e)) throw e
                h.rollbackToSavepoint("release_day")
                log.error("day states failed quarantine_id=$quarantineId", e)
            }
        }
        // Inside the caller's transaction this runs before its commit; the handlers' after-commit work is notification only.
        log.info("quarantine accepted id=$quarantineId resolver=$resolverUserId fix=${fixedRecord != null}")
    }

    /**
     * The memo references that follow a matched phone memo to its paper twin: a collection, a void or an edit naming the
     * phone memo's uuid. Lines, discounts, QC lines and print events never do (the paper memo's own lines stand).
     */
    private fun memoAlias(h: Handle, type: String, env: JsonObject): JsonObject {
        val field = MEMO_ALIAS_FIELDS[type] ?: return env
        val p = env["payload"] as? JsonObject ?: return env
        val target = p.str(field)?.takeIf { UUID_V4.matches(it) } ?: return env
        // A match in flight holds the memo's registry row: wait for it, so a collection sent meanwhile is never held behind
        // a memo that is matched a moment later (nothing would release it).
        h.createQuery("SELECT 1 FROM app.ingest_registry WHERE client_uuid = CAST(:u AS uuid) FOR SHARE").bind("u", target).mapTo(Int::class.java).list()
        // A memo uuid registered accepted with another memo row's id has no row of its own: it was matched (or deduplicated).
        val twin = h.createQuery(
            """
            SELECT m.client_uuid::text FROM app.ingest_registry g JOIN app.memo m ON m.id = g.server_id AND m.business_date = g.business_date
            WHERE g.client_uuid = CAST(:u AS uuid) AND g.record_type = 'memo' AND g.status = 'accepted' AND m.client_uuid <> g.client_uuid
            """.trimIndent(),
        ).bind("u", target).mapTo(String::class.java).findOne().orElse(null) ?: return env
        return JsonObject(env + ("payload" to JsonObject(p + (field to JsonPrimitive(twin)))))
    }

    /** What [matchBackfill] did: the paper memo, and the held records it released or left open for their own review. */
    data class BackfillMatch(
        val paperMemoId: Long, val paperMemoUuid: String, val released: List<String>, val keptOpen: List<String>,
        /** Step 3: the paper `web_entry` visit voided in favour of the phone visit (null when already voided); the caller's audit row names both. */
        val voidedPaperVisitUuid: String? = null, val phoneVisitUuid: String? = null,
    )

    /**
     * Release helper of the review action "same memo as backfill" (docs/requests/backend-core-backfill-vs-phone-memo-review.md
     * steps 1, 2, 4, 5 and 7; the endpoint, its roles, reach and audit row are the caller's). The item is an open
     * `memo_no_duplicate` phone memo whose twin (same memo_no and business date, same SR) is a live paper backfill
     * (`entry_source = manual`). In one transaction (the caller's, when one is open on this thread):
     * - **Compare**: outlet, gross, discounts, QC, rounding, net, paid, due, line counts and the lines (sku, qty_base,
     *   base price, gross) of the phone memo and its held lines against the paper memo. Any difference is 409
     *   `ERR_CONFLICT` naming each one, and nothing changes (the reviewer corrects the paper memo or keeps the item).
     * - **Memo**: its registry row turns `accepted` with the paper memo's id (its resends are `duplicate`, the digest
     *   reconciles); the item is `accepted` with the note `same_as_backfill`. No second memo is stored.
     * - **Lines, discounts, QC lines, print events** held with it: registry `accepted`, no rows, items closed.
     * - **Collections and voids** held with it: re-run through the record path as their own uploader; the memo alias in
     *   `process()` points them at the paper memo, so the ledger posts once against it (idempotent by their own uuid). One
     *   refused for a reason of its own stays open. Later uploads naming the phone memo follow the same alias.
     * - **Dirty key**: the route-day's `route_day_agg`.
     */
    fun matchBackfill(quarantineId: Long, resolverUserId: Long, reason: String): BackfillMatch {
        val now = clock.now()
        return db.jdbi.inTransaction<BackfillMatch, Exception> { h ->
            val item = heldItem(h, "id = :id", quarantineId) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such quarantine item")
            if (item.status != "open") throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "already resolved as ${item.status}")
            if (item.code != RecordOutcomeCode.MEMO_NO_DUPLICATE.wire || item.payload.str("type") != "memo") {
                throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "only a memo held as memo_no_duplicate can be matched to its paper twin")
            }
            val phone = item.payload["payload"] as JsonObject
            val memoNo = phone.str("memo_no") ?: throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the held memo has no memo_no")
            val bd = LocalDate.parse(item.payload.str("business_date")!!)
            // The same lock as a paper keying of this number (ingestManual): the twin cannot change under the match.
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 6))", "memo_no:$memoNo:$bd")
            val paper = h.createQuery(
                """
                SELECT id, client_uuid::text AS cu, visit_client_uuid::text AS vcu, user_id, outlet_id, status, voided_at, entry_source, gross_mtk, offer_discount_mtk, drp_discount_mtk,
                       qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, line_count, discount_line_count, qc_line_count
                FROM app.memo WHERE memo_no = :n AND business_date = :d AND client_uuid <> CAST(:c AS uuid) FOR UPDATE
                """.trimIndent(),
            ).bind("n", memoNo).bind("d", bd).bind("c", item.clientUuid).mapToMap().findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "memo $memoNo of $bd has no stored twin")
            if (paper["entry_source"] != "manual") throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the stored memo $memoNo is not a paper backfill")
            if ((paper["user_id"] as Number).toLong() != item.userId) throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the paper memo $memoNo belongs to another SR")
            if (paper["status"] != "active" || paper["voided_at"] != null) throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the paper memo $memoNo is ${paper["status"]}; correct it first")
            val paperId = (paper["id"] as Number).toLong(); val paperUuid = paper["cu"] as String

            val children = heldChildren(h, item.clientUuid, item.code)
            val lineTypes = setOf("memo_line", "memo_discount", "qc_line", "print_event")
            val diffs = ArrayList<FieldError>()
            fun cmp(k: String, phoneV: Any?, paperV: Any?) { if (phoneV?.toString() != paperV?.toString()) diffs += FieldError("/payload/$k", "mismatch", "phone $phoneV, paper $paperV") }
            cmp("outlet_id", phone.long("outlet_id"), paper["outlet_id"])
            for (k in listOf("gross_mtk", "offer_discount_mtk", "drp_discount_mtk", "qc_deduction_mtk", "round_adj_mtk", "net_mtk", "paid_mtk", "due_mtk",
                "line_count", "discount_line_count", "qc_line_count")) cmp(k, phone.long(k), paper[k])
            val phoneLines = children.filter { it.payload.str("type") == "memo_line" && (it.payload["payload"] as JsonObject).str("memo_client_uuid") == item.clientUuid }
                .map { c -> (c.payload["payload"] as JsonObject).let { "${it.long("sku_id")}:${it.long("qty_base")}:${it.long("base_price_mtk")}:${it.long("gross_mtk")}" } }.sorted()
            val paperLines = h.createQuery("SELECT sku_id || ':' || qty_base || ':' || base_price_mtk || ':' || gross_mtk FROM app.memo_line WHERE memo_client_uuid = CAST(:m AS uuid)")
                .bind("m", paperUuid).mapTo(String::class.java).list().sorted()
            if (phoneLines != paperLines) diffs += FieldError("/lines", "mismatch", "phone ${phoneLines.joinToString(",")}, paper ${paperLines.joinToString(",")} (sku:qty_base:price:gross)")
            if (diffs.isNotEmpty()) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the phone memo differs from the paper memo $memoNo", errors = diffs)

            // The memo: matched, never stored twice.
            h.createUpdate("UPDATE app.ingest_registry SET status = 'accepted', outcome_code = NULL, server_id = :sid, last_seen_at = :now WHERE client_uuid = CAST(:c AS uuid) AND status = 'quarantined'")
                .bind("sid", paperId).bind("now", ts(now)).bind("c", item.clientUuid).execute()
            closeItem(h, item.id, "accepted", null, resolverUserId, "same_as_backfill: paper memo $paperUuid; $reason", now)
            val released = ArrayList<String>(); val kept = ArrayList<String>()
            val ctxs = HashMap<Pair<Long, Long?>, Ctx>()
            for (child in children) {
                val type = child.payload.str("type")
                // Only the phone memo's own lines are absorbed by the paper memo's; a held edit's lines are stored with it.
                if (type in lineTypes && (child.payload["payload"] as? JsonObject)?.str("memo_client_uuid") == item.clientUuid) {
                    h.createUpdate("UPDATE app.ingest_registry SET status = 'accepted', outcome_code = NULL, last_seen_at = :now WHERE client_uuid = CAST(:c AS uuid) AND status = 'quarantined'")
                        .bind("now", ts(now)).bind("c", child.clientUuid).execute()
                    closeItem(h, child.id, "accepted", null, resolverUserId, "same_as_backfill: the paper memo's lines stand", now)
                    released += child.clientUuid
                    continue
                }
                // Collections, voids and anything else held with it: their own path, their own uploader, no override.
                val ctx = ctxs.getOrPut(child.userId to child.deviceId) {
                    Ctx(uploaderOf(h, child.userId, child.deviceId), child.batchUuid ?: UUID.nameUUIDFromBytes("quarantine:${child.id}".toByteArray()).toString(), now, reviewed = true)
                }
                val sp = "match_${child.id}"
                h.savepoint(sp)
                val o = runCatching { release(h, ctx, Rec(0, child.payload)) }.getOrElse { e -> if (isTransientDbFailure(e) || e is ApiProblem) throw e; null }
                if (o != null && (o.status == "accepted" || o.status == "duplicate")) {
                    closeItem(h, child.id, "accepted", null, resolverUserId, "same_as_backfill: posted to paper memo $paperUuid", now)
                    h.release(sp); released += child.clientUuid
                } else {
                    h.rollbackToSavepoint(sp); kept += child.clientUuid
                    log.info("held record stays quarantined client_uuid=${child.clientUuid} memo=${item.clientUuid} outcome=${o?.status} ${o?.code?.wire}")
                }
            }
            // Step 3 (db-backend-core-visit-supersede.md, V0081): the phone visit carries the geo evidence, so the paper
            // web_entry visit is voided (tombstone, idempotent) and its registry row turns `voided`, as a data void does.
            // The paper memo keeps its visit_client_uuid (the memo guard refuses a re-point).
            val paperVisit = paper["vcu"] as String?
            val voidedRoute = paperVisit?.let { v ->
                h.createQuery("UPDATE app.visit SET voided_at = :now WHERE client_uuid = CAST(:v AS uuid) AND business_date = :d AND visit_kind = 'web_entry' AND voided_at IS NULL RETURNING route_id")
                    .bind("now", ts(now)).bind("v", v).bind("d", bd).mapTo(Long::class.java).findOne().orElse(null)
            }
            if (voidedRoute != null) {
                h.createUpdate("UPDATE app.ingest_registry SET status = 'voided', last_seen_at = :now WHERE client_uuid = CAST(:v AS uuid) AND status = 'accepted'")
                    .bind("now", ts(now)).bind("v", paperVisit).execute()
            }
            (listOfNotNull(item.payload.long("route_id"), voidedRoute)).distinct()
                .forEach { h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'backfill_matched')", it, bd) }
            DayStates.settleMinutes = runCatching { config.int("cfg.day.submit_settle_timeout_min").toLong() }.getOrDefault(30L)
            h.savepoint("match_day")
            try {
                ctxs.values.forEach { c -> DayStates.afterBatch(h, c.up.userId, c.touched, now) }
                h.release("match_day")
            } catch (e: Exception) {
                if (isTransientDbFailure(e)) throw e
                h.rollbackToSavepoint("match_day"); log.error("day states failed quarantine_id=$quarantineId", e)
            }
            BackfillMatch(paperId, paperUuid, released, kept, paperVisit.takeIf { voidedRoute != null }, phone.str("visit_client_uuid"))
        }
    }

    /** Whether [r] is the original of an item a reviewer accepted with a fix (same uuid, the original's hash). */
    private fun fixedCopy(h: Handle, r: Rec): Boolean = h.createQuery(
        "SELECT EXISTS (SELECT 1 FROM app.sync_quarantine WHERE client_uuid = CAST(:c AS uuid) AND status = 'accepted_with_fix' AND payload_sha256 IN (:h, :f))",
    ).bind("c", r.clientUuid).bind("h", r.hash).bind("f", r.fullHash).mapTo(Boolean::class.java).one()

    /** One quarantine item as stored. */
    private class HeldItem(val id: Long, val clientUuid: String, val code: String, val status: String, val payload: JsonObject, val userId: Long, val deviceId: Long?, val batchUuid: String?)

    private fun heldItem(h: Handle, where: String, id: Long): HeldItem? = h.createQuery(
        "SELECT id, client_uuid::text, code, status, payload::text, user_id, device_id, batch_uuid::text FROM app.sync_quarantine WHERE $where FOR UPDATE",
    ).bind("id", id).map { rs, _ ->
        HeldItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), ResponseJson.parseToJsonElement(rs.getString(5)) as JsonObject,
            rs.getLong(6), rs.getObject(7) as Long?, rs.getString(8))
    }.findOne().orElse(null)

    /**
     * Open items held only because [parentUuid] was, breadth first: same code, and detail `parent <uuid> is quarantined`
     * or, for a memo's lines judged with it (s7.4 family equations, unknown SKU), `memo <uuid>: ...`.
     */
    private fun heldChildren(h: Handle, parentUuid: String, code: String): List<HeldItem> {
        val out = ArrayList<HeldItem>()
        val queue = ArrayDeque(listOf(parentUuid))
        val seen = HashSet<String>(listOf(parentUuid))
        while (queue.isNotEmpty() && out.size < MANUAL_MAX_RECORDS) {
            val p = queue.removeFirst()
            val ids = h.createQuery("SELECT id FROM app.sync_quarantine WHERE status = 'open' AND code = :code AND (detail LIKE :d OR detail LIKE :m) AND client_uuid <> CAST(:p AS uuid) ORDER BY id")
                .bind("code", code).bind("d", "parent $p is quarantined (%").bind("m", "memo $p: %").bind("p", p).mapTo(Long::class.java).list()
            for (id in ids) {
                val c = heldItem(h, "id = :id", id) ?: continue
                if (c.status != "open" || !seen.add(c.clientUuid)) continue
                out += c
                queue += c.clientUuid
            }
        }
        return out
    }

    /** The registry row goes back to `parked` (with a fix's hash), then the record runs the per-record path again. */
    private fun release(h: Handle, ctx: Ctx, r: Rec): Outcome {
        h.createUpdate("UPDATE app.ingest_registry SET status = 'parked', payload_sha256 = :h WHERE client_uuid = CAST(:c AS uuid) AND status = 'quarantined'")
            .bind("h", r.hash).bind("c", r.clientUuid).execute()
        return processInSavepoint(h, ctx, r)
    }

    private fun closeItem(h: Handle, id: Long, status: String, fixed: JsonObject?, resolver: Long, note: String, now: Instant) {
        h.createUpdate(
            """
            UPDATE app.sync_quarantine SET status = :s, fixed_payload = CAST(:fp AS jsonb), resolved_by_user_id = :u, resolved_at = :now,
                   resolution_note = :note, resolution_uuid = :ru
            WHERE id = :id AND status = 'open'
            """.trimIndent(),
        ).bind("s", status).bind("fp", fixed?.let { storable(it) }).bind("u", resolver).bind("now", ts(now)).bind("note", note.take(500))
            .bind("ru", UUID.nameUUIDFromBytes("quarantine:$id:$status:$resolver".toByteArray())).bind("id", id).execute()
    }

    /** The original uploader and device of a held record: role and scope from the user, key from the device. */
    private fun uploaderOf(h: Handle, userId: Long, deviceId: Long?): Uploader {
        val u = h.createQuery("SELECT role, scope_version FROM app.app_user WHERE id = :u").bind("u", userId)
            .map { rs, _ -> Role.entries.first { it.wire == rs.getString(1) } to rs.getLong(2) }.one()
        val d = deviceId?.let { id ->
            h.createQuery("SELECT device_uuid::text, public_key_jwk::text FROM app.device WHERE id = :d").bind("d", id)
                .map { rs, _ -> rs.getString(1) to rs.getString(2) }.findOne().orElse(null)
        }
        return Uploader(userId, u.first, u.second, deviceId, d?.first, d?.second?.let { com.aktcl.aron.backend.platform.DeviceProof.publicKey(it) })
    }

    /** Whether the ingest_registry flags CHECK allows `manual_entry` (docs/requests/backend-core-memo-source.md); read once. */
    private val manualFlagAllowed: Boolean by lazy {
        db.jdbi.withHandle<Boolean, Exception> { h ->
            h.createQuery("SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'app.ingest_registry'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%manual_entry%')")
                .mapTo(Boolean::class.java).one()
        }
    }

    /** Families in order (config acks first), each in its own transaction with retries; then the day states. */
    private fun runFamilies(ctx: Ctx, recs: List<Rec>): Array<Outcome?> {
        val up = ctx.up
        val now = ctx.now
        val outcomes = arrayOfNulls<Outcome>(recs.size)
        // Families as the phone sent them, except that families of config_ack records only go first: an ack has no
        // parent or child, and F-SYS-091 must see an ack of this batch when it judges the batch's other rows (BC-85).
        // The acks are read lazily on the first non-telemetry row, so after these families committed. Outcomes keep
        // their index, so the response order does not change.
        val families = ArrayList<List<Rec>>()
        run {
            var a = 0
            while (a < recs.size) {
                var b = a
                while (b + 1 < recs.size && recs[b + 1].family == recs[a].family) b++
                families += recs.subList(a, b + 1)
                a = b + 1
            }
        }
        val ordered = families.filter { f -> f.all { it.type == "config_ack" } } + families.filterNot { f -> f.all { it.type == "config_ack" } }
        for (family in ordered) {
            val i = family.first().index
            ctx.arith = MemoChecks.familyMismatches(family.map { it.json }) + db.jdbi.withHandle<Map<String, String>, Exception> { h -> MemoChecks.unknownSkuSiblings(h, family.map { it.json }) }
            ctx.memoFps = MemoChecks.memoFingerprints(family.map { it.json })
            ctx.packHeld = db.jdbi.withHandle<Map<String, String>, Exception> { h -> MemoChecks.packFactorHolds(h, family.map { it.json }) }
            try {
                // AUD-REL-02: a transient failure (deadlock, serialization, failover, pool timeout) retries the family
                // in a fresh transaction, at most three tries within 10 s; every record upserts by its client UUID and a
                // failed try rolls back whole, so a retry can never store a record twice.
                var attempt = 0
                val familyStart = clock.now()
                while (true) {
                    ctx.afterCommit.clear()
                    try {
                        db.jdbi.inTransaction<Unit, Exception> { h -> family.forEach { r -> outcomes[r.index] = processInSavepoint(h, ctx, r) } }
                        break
                    } catch (e: Exception) {
                        if (++attempt >= FAMILY_TRIES || !isTransientDbFailure(e) || clock.now().isAfter(familyStart.plusMillis(FAMILY_RETRY_BUDGET_MS))) throw e
                        log.warn("family retry batch_uuid=${ctx.batchUuid} family=${recs[i].family} attempt=$attempt: ${e.javaClass.simpleName}")
                        Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(20, 101))
                    }
                }
                // Committed: only now may a handler reach outside the database.
                ctx.afterCommit.forEach { (_, hd, rec) -> runCatching { hd.afterCommit(rec) }.onFailure { log.warn("afterCommit failed type=${rec.type}", it) } }
            } catch (e: Exception) {
                log.error("family failed batch_uuid=${ctx.batchUuid} family=${recs[i].family}", e)
                db.jdbi.useTransaction<Exception> { h -> family.forEach { r -> outcomes[r.index] = park(h, ctx, r, RecordOutcomeCode.SERVER_ERROR, e.javaClass.simpleName) } }
            }
        }

        // Day states move forward from what this batch brought (F-SYS-016, s4.9); a failure here never fails the batch.
        DayStates.settleMinutes = runCatching { config.int("cfg.day.submit_settle_timeout_min").toLong() }.getOrDefault(30L)
        runCatching { db.jdbi.useTransaction<Exception> { h -> DayStates.afterBatch(h, up.userId, ctx.touched, now) } }
            .onFailure { log.error("day states failed batch_uuid=${ctx.batchUuid}", it) }
        return outcomes
    }

    private fun acksOf(recs: List<Rec>, outcomes: Array<Outcome?>): List<RecordAck> {
        return recs.map { r ->
            val o = outcomes[r.index]!!
            RecordAck(
                client_uuid = r.clientUuid, type = r.type, status = o.status, code = o.code?.wire,
                retryable = if (o.status == "rejected") o.retryable ?: o.code?.retryable else null,
                message_key = o.code?.let { "sync.outcome.${it.wire}" }, server_id = o.serverId,
            )
        }
    }

    /** Returns the stored response of a completed identical batch, 409 for a reused batch_uuid, or null to process. */
    private fun replayOrClaim(up: Uploader, req: SyncBatchRequest, count: Int, fingerprint: ByteArray, now: Instant): SyncBatchResponse? =
        db.jdbi.inTransaction<SyncBatchResponse?, Exception> { h ->
            val retention = runCatching { config.int("cfg.retention.sync_batch_response_h") }.getOrDefault(48).toLong()
            h.createUpdate(
                """
                INSERT INTO app.sync_batch (device_id, batch_uuid, user_id, fingerprint, record_count, trigger, attempt, app_version, pending_rows, received_at, expires_at)
                VALUES (:d, CAST(:b AS uuid), :u, :fp, :n, :trigger, 1, :app, :pending, :now, :exp)
                ON CONFLICT (device_id, batch_uuid) DO NOTHING
                """.trimIndent(),
            ).bind("d", up.deviceId).bind("b", req.batch_uuid).bind("u", up.userId).bind("fp", fingerprint).bind("n", count)
                .bind("trigger", req.trigger).bind("app", req.app_version.take(40)).bind("pending", req.pending_rows.coerceAtLeast(0))
                .bind("now", ts(now)).bind("exp", ts(now.plusSeconds(retention * 3600))).execute()
            val expired = h.createQuery("SELECT expires_at < :now FROM app.sync_batch WHERE device_id = :d AND batch_uuid = CAST(:b AS uuid) FOR UPDATE")
                .bind("now", ts(now)).bind("d", up.deviceId).bind("b", req.batch_uuid).mapTo(Boolean::class.java).one()
            if (expired) {
                // Past cfg.retention.sync_batch_response_h the stored response is gone: the batch is processed afresh
                // (every record is idempotent) and the row restarts under this request.
                h.createUpdate("UPDATE app.sync_batch SET fingerprint = :fp, user_id = :u, record_count = :n, response_gz = NULL, completed_at = NULL, received_at = :now, expires_at = :exp WHERE device_id = :d AND batch_uuid = CAST(:b AS uuid)")
                    .bind("fp", fingerprint).bind("u", up.userId).bind("n", count).bind("now", ts(now)).bind("exp", ts(now.plusSeconds(retention * 3600)))
                    .bind("d", up.deviceId).bind("b", req.batch_uuid).execute()
            }
            val row = h.createQuery("SELECT fingerprint, response_gz, user_id FROM app.sync_batch WHERE device_id = :d AND batch_uuid = CAST(:b AS uuid)")
                .bind("d", up.deviceId).bind("b", req.batch_uuid)
                .map { rs, _ -> Triple(rs.getBytes(1), rs.getBytes(2), rs.getLong(3)) }.one()
            if (!row.first.contentEquals(fingerprint) || row.third != up.userId) {
                throw ApiProblem(ProblemCode.ERR_SYNC_BATCH_UUID_REUSED, "batch_uuid ${req.batch_uuid} was used for another record set")
            }
            val stored = row.second ?: return@inTransaction null
            h.createUpdate("UPDATE app.sync_batch SET replay_count = replay_count + 1 WHERE device_id = :d AND batch_uuid = CAST(:b AS uuid)")
                .bind("d", up.deviceId).bind("b", req.batch_uuid).execute()
            val old = ResponseJson.decodeFromString(SyncBatchResponse.serializer(), gunzip(stored).decodeToString())
            // The fingerprint is order-free (s3.3); the acks follow this request's order (s4.5).
            val byUuid = old.acks.groupBy { it.client_uuid }.mapValues { it.value.toMutableList() }
            val acks = req.records.map { rec -> byUuid[uuidKey(rec)]?.removeFirstOrNull() ?: error("replay ack missing") }
            old.copy(replayed = true, acks = acks)
        }

    /**
     * One record in its own savepoint (poison-row isolation, F-SYS-048): a value the database refuses (SQLSTATE class
     * 22 or 23) is a final `schema_invalid`; any other failure is `server_error`, retryable and parked, so the phone
     * resends it and skips ahead after `cfg.sync.family_skip_after` tries. The other records of the family and the
     * batch go on. A transient database failure, or a savepoint that cannot be rolled back, fails the family as a whole
     * (retried in a fresh transaction, AUD-REL-02).
     */
    private fun processInSavepoint(h: Handle, ctx: Ctx, r: Rec): Outcome {
        val sp = "rec_${r.index}"
        h.savepoint(sp)
        return try {
            process(h, ctx, r).also { h.release(sp) }
        } catch (e: Exception) {
            ctx.afterCommit.removeAll { it.first == r.index }
            if (e is ApiProblem) throw e
            // A transient failure is not the record's fault: the family is retried whole (AUD-REL-02).
            if (isTransientDbFailure(e)) throw e
            h.rollbackToSavepoint(sp)
            val state = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState ?: ""
            if (state.startsWith("22") || state.startsWith("23")) {
                log.info("record refused by the database client_uuid=${r.clientUuid} type=${r.type} sqlstate=$state")
                finalReject(h, ctx, r, RecordOutcomeCode.SCHEMA_INVALID, "database refused the record ($state)")
            } else {
                log.error("record failed client_uuid=${r.clientUuid} type=${r.type} batch_uuid=${ctx.batchUuid} sqlstate=$state", e)
                park(h, ctx, r, RecordOutcomeCode.SERVER_ERROR, "${e.javaClass.simpleName} $state".trim())
            }
        }
    }

    private fun process(h: Handle, ctx: Ctx, r: Rec): Outcome {
        // 1. Shape: envelope and payload members of the contract (unevaluatedProperties false).
        val shapeError = shapeError(r, manual = ctx.manual != null) ?: moneyOutOfRange(r.json)
        if (shapeError != null) {
            if (!UUID_V4.matches(r.clientUuid)) {
                // Without a valid client_uuid the record cannot be registered or kept; it is acked and logged only.
                log.warn("record without a valid client_uuid batch_uuid=${ctx.batchUuid} index=${r.index} type=${r.type.take(40)}")
                return Outcome.of(RecordOutcomeCode.SCHEMA_INVALID)
            }
            val code = if (r.type !in TypeRules.BY_TYPE) RecordOutcomeCode.UNKNOWN_RECORD_TYPE else RecordOutcomeCode.SCHEMA_INVALID
            return finalReject(h, ctx, r, code, shapeError)
        }
        val rule = TypeRules.BY_TYPE.getValue(r.type)
        // A dues record or edit aimed at a phone memo matched to its paper twin follows to the paper memo (matchBackfill);
        // the registry keeps the phone's own hash, so its resends stay duplicates.
        val env = memoAlias(h, r.type, r.json)
        val payload = env["payload"] as JsonObject
        val bd = LocalDate.parse(env.str("business_date")!!)

        // 2. Registry: one uniqueness point for every device record. A transaction-scoped advisory lock on the uuid
        // serialises concurrent batches carrying the same client_uuid (FOR UPDATE locks nothing while no row exists).
        h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", r.clientUuid)
        val prior = h.createQuery("SELECT status, outcome_code, payload_sha256, server_id FROM app.ingest_registry WHERE client_uuid = CAST(:c AS uuid) FOR UPDATE")
            .bind("c", r.clientUuid).map { rs, _ -> Prior(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getObject(4) as Long?) }.findOne().orElse(null)
        var released = false
        if (prior != null) {
            // The phone's own copy of a record a reviewer accepted with a fix is that record (its resend is a duplicate).
            if (!prior.hash.contentEquals(r.hash) && prior.status == "accepted" && fixedCopy(h, r)) { touch(h, r); return Outcome.duplicate(prior.serverId) }
            if (!prior.hash.contentEquals(r.hash) && !prior.hash.contentEquals(r.fullHash)) return quarantine(h, ctx, r, bd, RecordOutcomeCode.PAYLOAD_CONFLICT, "same client_uuid, different payload", register = false)
            // Registered before the hash left `sig` out: re-key it, so the parked upsert and the stored_at mark match (BC-53).
            if (!prior.hash.contentEquals(r.hash)) rekey(h, r)
            when (prior.status) {
                "accepted", "voided" -> { touch(h, r); return Outcome.duplicate(prior.serverId) }
                "rejected" -> { touch(h, r); return Outcome.of(code(prior.code) ?: RecordOutcomeCode.SCHEMA_INVALID) }
                // A signature quarantine is released once the mode is no longer enforce: the resend is processed like a
                // parked row, so a sale held under enforce (or before modes existed) reaches the server (BC-53).
                // Only while its review item is still open: a reviewer's discard or return-to-device stands (android-core
                // integrity-release item 1).
                // N-027: a device-gate quarantine (device_not_enrolled, or device_integrity_failed from the gate) is
                // processed again on every resend while its item is open; the gate and the signature check re-apply,
                // so under enforce a bad signature is quarantined again with the same outcome.
                "quarantined" -> if (prior.code !in DEVICE_HOLD_CODES || !openIntegrityItem(h, r)) {
                    touch(h, r); return Outcome.of(code(prior.code) ?: RecordOutcomeCode.PAYLOAD_CONFLICT)
                } else {
                    released = true
                }
                // A released row that was parked since (a parent missing, a transient error) keeps its release.
                "parked" -> released = ctx.signatureMode != SignatureMode.ENFORCE && openIntegrityItem(h, r)
                // parked: process again (the parent may have arrived)
            }
        }

        // 3. Business-date window (s3.8 item 4): too old or in the future is quarantined, never dropped.
        val captured = Instant.parse(env.str("captured_at")!!)
        // A released signature quarantine was inside the window when first received: it is not too old now (item 2).
        // A reviewer's accept judges a record that was inside the window when it was received and held for another reason.
        val tooOld = !released && !ctx.reviewed && bd.isBefore(ctx.backdateFloor)
        // F-SYS-089: after a failover or restore the phone re-sends rows the lost lineage had acknowledged (trigger
        // `resync`, docs/24 s4.8). One captured before the new lineage started may be older than the window: it is
        // accepted and flagged `resync_late`, never quarantined (idempotency by client_uuid keeps the re-send safe).
        val resyncLate = tooOld && ctx.resync?.let { w -> !captured.isAfter(w.startedAt) && !bd.isBefore(w.oldestDate) } == true
        if (((tooOld && !resyncLate) || bd.isAfter(ctx.today) || captured.isAfter(ctx.now.plusSeconds(600))) && !ctx.overrides(RecordOutcomeCode.BUSINESS_DATE_OUT_OF_WINDOW)) {
            return quarantine(h, ctx, r, bd, RecordOutcomeCode.BUSINESS_DATE_OUT_OF_WINDOW, "business_date $bd, today ${ctx.today}")
        }
        if (resyncLate) {
            // Stored in app.ingest_registry.flags on the accepted row (V0056, db-resync-late-flag-answer.md); the log line stays.
            log.warn("resync_late client_uuid=${r.clientUuid} type=${r.type} business_date=$bd user=${ctx.up.userId} batch_uuid=${ctx.batchUuid}")
        }

        // 3b. A check-out before cfg.day.checkout_earliest_time on its business date (Dhaka, by the user's role and home
        // geography) is s4.5 `checkout_too_early`. The spec quarantines it, but no review path can release a quarantined
        // row yet (OpsApi's acceptor is not wired), so the rep's day would end without a check-out: until it can, the row
        // is accepted and flagged (BC-63; the phone enforces the same value offline, so only a hooked clock reaches it).
        val tooEarly = r.type == "attendance_event" && payload.str("kind") == "check_out" && checkoutTooEarly(h, ctx.up, captured, bd)
        if (tooEarly) {
            // Stored in app.ingest_registry.flags (V0059); the log line stays.
            log.warn("checkout_too_early client_uuid=${r.clientUuid} user=${ctx.up.userId} business_date=$bd captured_at=$captured")
        }

        // 3c. F-SYS-091 (s11.4): a row stamped with a config_version below one the device had already applied before the
        // row's capture time is accepted and flagged `config_stamp_regress` (a rolled-back app or an edited stamp); the
        // third such row of the device and business date raises CONFIG_STAMP_REGRESS. Telemetry rows are not judged.
        val stampRegress = !rule.telemetry && env.long("config_version")?.let { cv ->
            ctx.acks.filter { it.first.isBefore(captured) }.maxOfOrNull { it.second }?.let { cv < it }
        } == true

        // 4. References that must exist (unknown ids are final rejections, s4.5).
        val routeId = env.long("route_id")
        val outletId = payload.long("outlet_id")
        val skuId = payload.long("sku_id")
        var routeZone: Long? = null
        if (routeId != null) {
            routeZone = h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", routeId).mapTo(Long::class.java).findOne().orElse(null)
                ?: return finalReject(h, ctx, r, RecordOutcomeCode.UNKNOWN_ROUTE, "route $routeId")
        }
        var outlet: Pair<Long?, Long>? = null
        if (outletId != null) {
            outlet = h.createQuery("SELECT route_id, zone_id FROM app.outlet WHERE id = :o").bind("o", outletId)
                .map { rs, _ -> (rs.getObject(1) as Long?) to rs.getLong(2) }.findOne().orElse(null)
                ?: return finalReject(h, ctx, r, RecordOutcomeCode.UNKNOWN_OUTLET, "outlet $outletId")
        }
        if (skuId != null && h.createQuery("SELECT count(*) FROM app.sku WHERE id = :s").bind("s", skuId).mapTo(Long::class.java).one() == 0L) {
            return finalReject(h, ctx, r, RecordOutcomeCode.UNKNOWN_SKU, "sku $skuId")
        }
        // 4b. Memo number (s7.5, QA-FLOW-02): the uploader's own login, the record's business date and a sequence inside
        // the user's ordinal blocks or overflow ranges; anything else is a final memo_no_invalid, before the global
        // (memo_no, business_date) uniqueness can see it, so a forged number never holds the real owner's memo as
        // memo_no_duplicate. A stored memo's resend is a duplicate at step 2 and is never re-judged.
        if (r.type == "memo") MemoNumbers.problem(ctx.memoNoRule, payload.str("memo_no"), bd)?.let {
            return finalReject(h, ctx, r, RecordOutcomeCode.MEMO_NO_INVALID, it)
        }
        // A child of a memo refused for its number is refused with it (final), before any check that would park it.
        if (r.type in MEMO_CHILD_TYPES) payload.str("memo_client_uuid")?.let { m ->
            val refused = UUID_V4.matches(m) && h.createQuery("SELECT outcome_code = :c FROM app.ingest_registry WHERE client_uuid = CAST(:m AS uuid) AND status = 'rejected'")
                .bind("c", RecordOutcomeCode.MEMO_NO_INVALID.wire).bind("m", m).mapTo(Boolean::class.java).findOne().orElse(false)
            if (refused) return finalReject(h, ctx, r, RecordOutcomeCode.MEMO_NO_INVALID, "memo $m was refused memo_no_invalid")
        }

        // 5. Scope on the record's business date (s8.4): from the token's user, never from the body.
        val scopeAccepted = ctx.overrides(RecordOutcomeCode.SCOPE_OUT_OF_REACH, RecordOutcomeCode.NO_ASSIGNMENT_ON_DATE)
        if (!rule.telemetry && !scopeAccepted) {
            val reach = ctx.reachOn(bd)
            if (routeId != null && !reach.coversRoute(routeId, routeZone!!)) {
                val code = if (reach.ownRecordsOnly) RecordOutcomeCode.NO_ASSIGNMENT_ON_DATE else RecordOutcomeCode.SCOPE_OUT_OF_REACH
                return quarantine(h, ctx, r, bd, code, "route $routeId on $bd")
            }
            if (outlet != null) {
                val (oRoute, oZone) = outlet
                val ok = if (reach.ownRecordsOnly) oRoute != null && oRoute in reach.routeIds else reach.coversZone(oZone) || (oRoute != null && oRoute in reach.routeIds)
                if (!ok) return quarantine(h, ctx, r, bd, RecordOutcomeCode.SCOPE_OUT_OF_REACH, "outlet $outletId on $bd")
            }
        }

        if (!scopeAccepted) env.long("acting_for_user_id")?.let { a ->
            if (!actingForValid(h, ctx, routeId, a, bd)) return quarantine(h, ctx, r, bd, RecordOutcomeCode.SCOPE_OUT_OF_REACH, "acting_for_user_id $a without a cover of route $routeId")
        }

        // 5b. N-027 device gate (s4.5, s10.4, D24-17/18). Attendance and sales (memo, memo_void, due_collection) from a
        // phone that is not enrolled (no enrolment, or no usable key) are quarantined `device_not_enrolled` while
        // cfg.device.require_enrolled is on; with it off they are accepted and flagged. A keyless phone cannot prove its
        // batch, so under the gate every non-telemetry record of it is held. With cfg.device.require_integrity on, an
        // enrolled phone without a Play Integrity `pass` has its attendance and sales quarantined `device_integrity_failed`;
        // with it off only a genuine `fail` is flagged. Never rejected: a resend after the phone is fixed is released
        // while the review item is open (step 2). Children of a held parent are held with it (step 7).
        val g = ctx.gate
        val gated = r.type in GATED_TYPES
        val deviceAccepted = ctx.overrides(RecordOutcomeCode.DEVICE_NOT_ENROLLED, RecordOutcomeCode.DEVICE_INTEGRITY_FAILED)
        if (deviceAccepted) {
            // A reviewer accepted a record the device checks held: neither the gate nor the signature applies again.
        } else if (g.notEnrolled && (gated || (g.requireEnrolled && ctx.up.deviceKey == null && !rule.telemetry))) {
            flagDevice(h, ctx, r, bd, routeId, "device_not_enrolled")
            if (g.requireEnrolled) return quarantine(h, ctx, r, bd, RecordOutcomeCode.DEVICE_NOT_ENROLLED, "device not enrolled")
        } else if (gated && g.verdict != "pass" && (g.requireIntegrity || g.verdict == "fail")) {
            flagDevice(h, ctx, r, bd, routeId, "play_integrity_${g.verdict}")
            if (g.requireIntegrity) return quarantine(h, ctx, r, bd, RecordOutcomeCode.DEVICE_INTEGRITY_FAILED, "play integrity ${g.verdict}")
        }

        // 6. Record signature on header records when the device has a key (s8.3), by cfg.sec.record_signature_mode
        // (docs/19: off < record < enforce; F-SYS-072): off skips, record accepts and flags, only enforce quarantines.
        if (rule.signedHeader && ctx.up.deviceKey != null && ctx.signatureMode != SignatureMode.OFF && !deviceAccepted) {
            // The phone signed what it sent: never the memo-aliased envelope.
            val sig = r.json.str("sig")
            val unsigned = JsonObject(r.json.filterKeys { it != "sig" })
            val msg = listOf("aron-sig-v1", r.type, r.clientUuid, sha256Hex(Jcs.canonicalize(unsigned).toByteArray())).joinToString("\n")
            if (sig == null || !com.aktcl.aron.backend.platform.DeviceProof.verify(ctx.up.deviceKey, msg, sig)) {
                if (ctx.signatureMode == SignatureMode.ENFORCE) {
                    return quarantine(h, ctx, r, bd, RecordOutcomeCode.DEVICE_INTEGRITY_FAILED, "record signature does not verify")
                }
                flagSignature(h, ctx, r, bd, routeId, if (sig == null) "record_signature_missing" else "record_signature_invalid")
            }
        }

        // 7. Content fingerprint (F-SYS-055): a header re-sent under regenerated uuids is the same content; the first
        // copy stands and the second is quarantined content_duplicate, never stored twice.
        // Not for a paper backfill: its memo number is the identity (checked before the run), and two real paper memos
        // of one outlet with the same lines are often keyed with the same placeholder time.
        val contentFp = if (ctx.manual != null) null else ctx.memoFps[r.clientUuid] ?: if (rule.signedHeader) contentFingerprint(r) else null
        if (contentFp != null && !ctx.overrides(RecordOutcomeCode.CONTENT_DUPLICATE)) {
            // Serialise re-mints carrying the same content under different uuids (the uuid lock does not cover them).
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 2))", "cfp:" + contentFp.joinToString("") { "%02x".format(it) })
            val twin = h.createQuery(
                "SELECT client_uuid::text FROM app.ingest_registry WHERE user_id = :u AND record_type = :t AND content_fp = :fp AND client_uuid <> CAST(:c AS uuid) AND status IN ('accepted','voided') LIMIT 1",
            ).bind("u", ctx.up.userId).bind("t", r.type).bind("fp", contentFp).bind("c", r.clientUuid).mapTo(String::class.java).findOne().orElse(null)
            if (twin != null) return quarantine(h, ctx, r, bd, RecordOutcomeCode.CONTENT_DUPLICATE, "same content as $twin", contentFp = contentFp)
        }

        // 7b. Pack size (docs/34 s4.2, QA-FLOW-06): a line or stock movement entered in packs with a pack_factor that is not
        // the SKU's base_per_pack is parked qty_invalid (retryable), with its memo and the memo's other children, so the
        // wrong quantity and its money are never booked. A line held after its memo was stored marks that memo.
        // A memo whose own line was parked for its pack size earlier (the line came first) is held with it; a line judged
        // outside a family segment (a review or a backfill match) is judged on its own.
        (ctx.packHeld[r.clientUuid] ?: if (r.type == "stock_movement" || r.type == "memo_line") MemoChecks.packFactorProblem(h, payload) else null
            ?: if (r.type == "memo") MemoChecks.parkedPackLine(h, r.clientUuid, ctx.up.userId, bd) else null)?.let { why ->
            if (r.type in MEMO_CHILD_TYPES) payload.str("memo_client_uuid")?.let { MemoChecks.flagStored(h, it, "pack_factor_mismatch") }
            return park(h, ctx, r, RecordOutcomeCode.QTY_INVALID, why)
        }

        // 8. Money (F-SYS-062, s7.4): a memo family whose equations fail is quarantined whole, before anything is stored.
        (ctx.arith[r.clientUuid] ?: MemoChecks.recordMismatch(r.type, payload))?.let { why ->
            return quarantine(h, ctx, r, bd, RecordOutcomeCode.ARITHMETIC_MISMATCH, why)
        }

        // 8a. Zero price (D-SALE-ZERO-PRICE-01): a sale line priced at zero is a final schema_invalid for that line alone
        // (a resend stays refused). After the money check, so its gross is zero: the memo and its other lines still add
        // up and are stored, and the memo is flagged zero_price_line for review (its line count is one short).
        if (r.type == "memo_line") MemoChecks.zeroPriceProblem(payload)?.let { why ->
            payload.str("memo_client_uuid")?.let { MemoChecks.flagStored(h, it, "zero_price_line") }
            return finalReject(h, ctx, r, RecordOutcomeCode.SCHEMA_INVALID, why)
        }

        // 8b. Handlers' early checks (before parents): a refusal here is final even when the parent is missing.
        handlers.forType(r.type).let { early ->
            if (early.isNotEmpty()) {
                val rec = IngestRecord(r.type, r.clientUuid, bd, env, payload, ctx.up.userId, ctx.up.role, ctx.up.deviceId, ctx.batchUuid, ctx.now)
                for (hd in early) hd.checkEarly(h, rec)?.let { if (!ctx.overrides(it.code)) return refuse(h, ctx, r, bd, it.code, it.detail) }
            }
        }

        // 9. Parents (s4.2 rule 3): a child whose parent is not stored yet is parked; the child of a content duplicate
        // is a duplicate too.
        for (field in rule.parents) {
            val parent = payload.str(field) ?: continue
            if (!UUID_V4.matches(parent)) return finalReject(h, ctx, r, RecordOutcomeCode.SCHEMA_INVALID, "$field is not a UUID v4")
            val reg = h.createQuery("SELECT record_type, user_id, status, outcome_code FROM app.ingest_registry WHERE client_uuid = CAST(:p AS uuid)")
                .bind("p", parent).map { rs, _ -> listOf(rs.getString(1), rs.getLong(2).toString(), rs.getString(3), rs.getString(4)) }.findOne().orElse(null)
            // (type, owner) of a stored parent: from the registry, or from its table for parents written outside the
            // batch (online endpoints, migrated history).
            val found: Pair<String, Long>? = if (reg != null && reg[2] in setOf("accepted", "voided")) reg[0] to reg[1].toLong()
            else PARENT_TABLES[field]?.let { t ->
                h.createQuery("SELECT user_id FROM app.$t WHERE client_uuid = CAST(:p AS uuid) LIMIT 1").bind("p", parent).mapTo(Long::class.java).findOne().orElse(null)
                    ?.let { (PARENT_TYPES[field] ?: t) to it }
            }
            if (found == null) {
                // The child of a quarantined parent is held with it (same code), so a review that accepts the parent
                // finds its children; parking it would let the phone give up on it.
                // A memo refused for its number (final) takes its own children with it: they can never be stored.
                if (reg?.get(2) == "rejected" && reg[3] == RecordOutcomeCode.MEMO_NO_INVALID.wire && field == "memo_client_uuid") {
                    return finalReject(h, ctx, r, RecordOutcomeCode.MEMO_NO_INVALID, "memo $parent was refused memo_no_invalid")
                }
                if (reg?.get(2) == "quarantined") {
                    val code = code(reg[3]) ?: RecordOutcomeCode.PAYLOAD_CONFLICT
                    return quarantine(h, ctx, r, bd, code, "parent $parent is quarantined (${reg[3]})")
                }
                return park(h, ctx, r, RecordOutcomeCode.PARENT_MISSING, "$field $parent")
            }
            val (parentType, owner) = found
            PARENT_TYPES[field]?.let { want -> if (parentType != want) return finalReject(h, ctx, r, RecordOutcomeCode.SCHEMA_INVALID, "$field names a $parentType, not a $want") }
            // A child reaches its parent's rows: the parent must be the uploader's own, except where the protocol lets
            // one user act on another's record within reach (dues on a memo, an assigned task, an outlet request).
            if (owner != ctx.up.userId && !scopeAccepted && !crossUserParentAllowed(h, ctx, field, parent, bd)) {
                return quarantine(h, ctx, r, bd, RecordOutcomeCode.SCOPE_OUT_OF_REACH, "$field $parent belongs to another user")
            }
        }

        // 10. Visit-kind policy (F-SYS-078): a phone books only its role's kinds; web_entry comes from the web only.
        if (r.type == "visit") payload.str("visit_kind")?.let { k ->
            val allowed = if (ctx.manual != null) setOf("web_entry") else DEVICE_VISIT_KINDS[ctx.up.role] ?: emptySet()
            if (k !in allowed && !scopeAccepted) {
                return quarantine(h, ctx, r, bd, RecordOutcomeCode.SCOPE_OUT_OF_REACH, "visit_kind $k is not a ${ctx.up.role.wire} device kind")
            }
        }
        // 11. Ledger rules (an edit names an active memo), registered handlers' own checks, then store.
        DuesLedger.check(h, r.type, payload)?.let { (code, detail) -> return refuse(h, ctx, r, bd, code, detail) }
        val hs = handlers.forType(r.type)
        val ingestRec = if (hs.isEmpty()) null else IngestRecord(
            r.type, r.clientUuid, bd, env, payload, ctx.up.userId, ctx.up.role, ctx.up.deviceId, ctx.batchUuid, ctx.now,
        )
        for (hd in hs) {
            val refusal = hd.check(h, ingestRec!!) ?: continue
            if (ctx.overrides(refusal.code)) continue
            return refuse(h, ctx, r, bd, refusal.code, refusal.detail)
        }
        // A domain duplicate (the same fact under a new client_uuid): registered as accepted with the first row's id,
        // so this uuid's resends are duplicates too, and nothing new is stored.
        for (hd in hs) hd.sameAs(h, ingestRec!!)?.let { first ->
            register(h, ctx, r, bd, "accepted", null, first, contentFp)
            return Outcome.duplicate(first)
        }
        val stored = RecordWriter.write(h, r.type, rule, env, payload, ctx.up, ctx.batchUuid, ctx.now, enteredBy = ctx.manual?.actorUserId)
        return when (stored) {
            is RecordWriter.Result.Stored -> {
                val flags = listOfNotNull("resync_late".takeIf { resyncLate }, "checkout_too_early".takeIf { tooEarly }, "config_stamp_regress".takeIf { stampRegress },
                    "manual_entry".takeIf { ctx.manual != null && manualFlagAllowed })
                register(h, ctx, r, bd, "accepted", null, stored.serverId, contentFp, flags)
                if (stampRegress) stampRegressSignal(h, ctx, r, bd, routeId)
                if (r.type == "memo") memoDuplicateSuspect(h, ctx, r, payload, bd, routeId)
                MemoChecks.afterChildStored(h, r.type, payload)
                DuesLedger.afterStored(h, r.type, env, payload)
                // Day states follow the record but never decide its outcome: a failure here is logged, the record stays stored.
                h.savepoint("day_${r.index}")
                try {
                    DayStates.afterStored(h, r.type, env, payload, ctx.up, ctx.now, routeDays, ctx.touched)
                    h.release("day_${r.index}")
                } catch (e: Exception) {
                    // A deadlock or serialisation failure retries the whole family (AUD-REL-02) instead of dropping a late count.
                    if (isTransientDbFailure(e)) throw e
                    h.rollbackToSavepoint("day_${r.index}")
                    log.error("day state update failed client_uuid=${r.clientUuid} type=${r.type}", e)
                }
                hs.forEach { it.afterStored(h, ingestRec!!, stored.serverId) }
                hs.forEach { ctx.afterCommit += Triple(r.index, it, ingestRec!!) }
                outOfBounds(h, ctx, r, rule, payload, bd, routeId)
                gnssConsistency(h, ctx, r, rule, payload, bd, routeId)
                Outcome.accepted(stored.serverId)
            }
            is RecordWriter.Result.AlreadyThere -> { register(h, ctx, r, bd, "accepted", null, stored.serverId); Outcome.duplicate(stored.serverId) }
            is RecordWriter.Result.Refused -> refuse(h, ctx, r, bd, stored.code, stored.detail)
        }
    }

    /** A refusal by the writer or a handler: the outcome code's status decides quarantine, park or final reject. */
    private fun refuse(h: Handle, ctx: Ctx, r: Rec, bd: LocalDate, code: RecordOutcomeCode, detail: String): Outcome = when {
        code.status.wire == "quarantined" -> quarantine(h, ctx, r, bd, code, detail)
        code.retryable == true -> park(h, ctx, r, code, detail)
        else -> finalReject(h, ctx, r, code, detail)
    }

    /**
     * Content of a header record without anything a re-mint changes: every UUID-valued string (its own and its
     * parents' ids) and the envelope's bundle and config stamps are removed; captured_at, the business date and the
     * payload stay, so two genuine records never share it.
     */
    private fun contentFingerprint(r: Rec): ByteArray {
        fun strip(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it !in VOLATILE }.mapValues { strip(it.value) }.filterValues { !(it is JsonPrimitive && it.isString && UUID_ANY.matches(it.content)) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { strip(it) })
            else -> e
        }
        return sha256(("cfp1|" + r.type + "|" + Jcs.canonicalize(strip(r.json))).toByteArray())
    }

    /**
     * Record mode: the sale is stored and the phone is flagged, one DEVICE_INTEGRITY_FAIL per phone and business date.
     * In its own savepoint: the flag never decides the sale's outcome (a failure is logged and the record goes on).
     */
    private fun flagSignature(h: Handle, ctx: Ctx, r: Rec, bd: LocalDate, routeId: Long?, reason: String) {
        val sp = "sigflag_${r.index}"
        h.savepoint(sp)
        try {
            insertSignatureFlag(h, ctx, r, bd, routeId, reason)
            h.release(sp)
        } catch (e: Exception) {
            h.rollbackToSavepoint(sp)
            log.error("signature flag failed client_uuid=${r.clientUuid}", e)
        }
    }

    /** N-027 supervisor flag: DEVICE_INTEGRITY_FAIL on the device for the business date (s11.4: severity 4, weight 30), in its own savepoint. */
    private fun flagDevice(h: Handle, ctx: Ctx, r: Rec, bd: LocalDate, routeId: Long?, reason: String) {
        val sp = "devflag_${r.index}"
        h.savepoint(sp)
        try {
            h.createUpdate(
                """
                INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version)
                VALUES ('DEVICE_INTEGRITY_FAIL', 4, :bd, 'device', :sid, :u, :route, 30, CAST(:ev AS jsonb), :cv)
                ON CONFLICT (code, subject_type, subject_id, business_date) DO UPDATE SET severity = GREATEST(app.risk_signal.severity, EXCLUDED.severity),
                    score = GREATEST(app.risk_signal.score, EXCLUDED.score), evidence = app.risk_signal.evidence || EXCLUDED.evidence, updated_at = now()
                  WHERE app.risk_signal.status = 'open' AND (app.risk_signal.severity < EXCLUDED.severity OR app.risk_signal.evidence ->> 'gate' IS NULL)
                """.trimIndent(),
            ).bind("bd", bd).bind("sid", ctx.up.deviceUuid).bind("u", ctx.up.userId).bind("route", routeId)
                .bind("ev", kotlinx.serialization.json.buildJsonObject {
                    put("gate", JsonPrimitive(reason)); put("record_type", JsonPrimitive(r.type)); put("client_uuid", JsonPrimitive(r.clientUuid))
                    put("held", JsonPrimitive(if (reason == "device_not_enrolled") ctx.gate.requireEnrolled else ctx.gate.requireIntegrity))
                }.toString()).bind("cv", config.configVersion()).execute()
            h.release(sp)
        } catch (e: Exception) {
            h.rollbackToSavepoint(sp)
            log.error("device flag failed client_uuid=${r.clientUuid}", e)
        }
    }

    /** F-SYS-091: (capture time, applied version) of the device's config acks, oldest first. */
    private fun deviceAcks(deviceId: Long): List<Pair<Instant, Long>> = db.jdbi.withHandle<List<Pair<Instant, Long>>, Exception> { h ->
        h.createQuery("SELECT captured_at, acked_config_version FROM app.cfg_ack WHERE device_id = :d AND voided_at IS NULL ORDER BY captured_at")
            .bind("d", deviceId).map { rs, _ -> rs.getObject(1, java.time.OffsetDateTime::class.java).toInstant() to rs.getLong(2) }.list()
    }

    /**
     * The third `config_stamp_regress` row of the device and business date raises CONFIG_STAMP_REGRESS (s11.4: severity 2,
     * weight 20), once per device and day; counted through V0059's partial index. In its own savepoint: a failed signal
     * never rolls the stored record back.
     */
    private fun stampRegressSignal(h: Handle, ctx: Ctx, r: Rec, bd: LocalDate, routeId: Long?) {
        val sp = "stamp_${r.index}"
        h.savepoint(sp)
        try {
            // Concurrent batches of the same device and day count one after the other (READ COMMITTED would let two
            // batches each see two rows and neither raise the signal).
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 5))", "stamp:${ctx.up.deviceId}:$bd")
            val n = h.createQuery("SELECT count(*) FROM app.ingest_registry WHERE device_id = :d AND business_date = :bd AND 'config_stamp_regress' = ANY (flags)")
                .bind("d", ctx.up.deviceId).bind("bd", bd).mapTo(Long::class.java).one()
            if (n >= 3) {
                h.createUpdate(
                    """
                    INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version)
                    VALUES ('CONFIG_STAMP_REGRESS', 2, :bd, 'device', :sid, :u, :route, 20, CAST(:ev AS jsonb), :cv)
                    ON CONFLICT (code, subject_type, subject_id, business_date) DO NOTHING
                    """.trimIndent(),
                ).bind("bd", bd).bind("sid", ctx.up.deviceUuid).bind("u", ctx.up.userId).bind("route", routeId)
                    .bind("ev", kotlinx.serialization.json.buildJsonObject {
                        put("rows", JsonPrimitive(n)); put("record_type", JsonPrimitive(r.type)); put("client_uuid", JsonPrimitive(r.clientUuid))
                    }.toString()).bind("cv", config.configVersion()).execute()
            }
            h.release(sp)
        } catch (e: Exception) {
            h.rollbackToSavepoint(sp)
            log.error("config stamp signal failed client_uuid=${r.clientUuid}", e)
        }
    }

    /**
     * MEMO_DUPLICATE_SUSPECT (lead ruling 2026-10-15, V0077): a memo stored while another live memo of the same outlet,
     * gross and business date exists under a different memo number, one of the two keyed on paper (`entry_source =
     * manual`), is flagged on the newer memo for review: typically a mistyped number on the paper copy of a memo the
     * phone had stored, or the phone's copy arriving after such a keying. A flag, never a block; zero sales are not
     * judged. In its own savepoint: a failed signal never rolls the stored memo back.
     */
    private fun memoDuplicateSuspect(h: Handle, ctx: Ctx, r: Rec, p: JsonObject, bd: LocalDate, routeId: Long?) {
        val gross = p.long("gross_mtk") ?: return
        val outlet = p.long("outlet_id") ?: return
        if (gross <= 0) return
        val sp = "memodup_${r.index}"
        h.savepoint(sp)
        try {
            val twin = h.createQuery(
                """
                SELECT client_uuid::text AS cu, memo_no, entry_source FROM app.memo
                WHERE business_date = :bd AND outlet_id = :o AND gross_mtk = :g AND memo_no <> :n AND status = 'active' AND voided_at IS NULL
                  AND client_uuid <> CAST(:c AS uuid) AND (:manual OR entry_source = 'manual')
                ORDER BY id LIMIT 1
                """.trimIndent(),
            ).bind("bd", bd).bind("o", outlet).bind("g", gross).bind("n", p.str("memo_no")).bind("c", r.clientUuid).bind("manual", ctx.manual != null)
                .mapToMap().findOne().orElse(null)
            if (twin != null) {
                val weight: Double = runCatching<Double?> { (config.value("cfg.geo.integrity_weight") as JsonObject)["MEMO_DUPLICATE_SUSPECT"]?.jsonPrimitive?.content?.toDoubleOrNull() }.getOrNull() ?: 30.0
                h.createUpdate(
                    """
                    INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version)
                    VALUES ('MEMO_DUPLICATE_SUSPECT', 2, :bd, 'memo', :sid, :u, :route, :score, CAST(:ev AS jsonb), :cv)
                    ON CONFLICT (code, subject_type, subject_id, business_date) DO NOTHING
                    """.trimIndent(),
                ).bind("bd", bd).bind("sid", r.clientUuid).bind("u", ctx.up.userId).bind("route", routeId).bind("score", weight.coerceIn(0.0, 300.0))
                    .bind("ev", kotlinx.serialization.json.buildJsonObject {
                        put("memo_no", JsonPrimitive(p.str("memo_no"))); put("entry_source", JsonPrimitive(if (ctx.manual != null) "manual" else "device"))
                        put("twin_client_uuid", JsonPrimitive(twin["cu"] as String)); put("twin_memo_no", JsonPrimitive(twin["memo_no"] as String))
                        put("twin_entry_source", JsonPrimitive(twin["entry_source"] as String)); put("outlet_id", JsonPrimitive(outlet)); put("gross_mtk", JsonPrimitive(gross))
                    }.toString()).bind("cv", config.configVersion()).execute()
            }
            h.release(sp)
        } catch (e: Exception) {
            h.rollbackToSavepoint(sp)
            log.error("memo duplicate signal failed client_uuid=${r.clientUuid}", e)
        }
    }

    /** The device row's standing for the N-027 gate; a device row that vanished counts as not enrolled. */
    private fun deviceGate(up: Uploader): DeviceGate {
        val row = db.jdbi.withHandle<Pair<Boolean, String>?, Exception> { h ->
            h.createQuery("SELECT enrolment_token_id IS NOT NULL, integrity_verdict FROM app.device WHERE id = :d").bind("d", up.deviceId)
                .map { rs, _ -> rs.getBoolean(1) to rs.getString(2) }.findOne().orElse(null)
        }
        return DeviceGate(
            notEnrolled = row == null || !row.first || up.deviceKey == null,
            verdict = row?.second ?: "unevaluated",
            // Registry defaults (s9.4): require_enrolled true (the dev database overrides false), require_integrity false.
            requireEnrolled = runCatching { config.bool("cfg.device.require_enrolled") }.getOrDefault(true),
            requireIntegrity = runCatching { config.bool("cfg.device.require_integrity") }.getOrDefault(false),
        )
    }

    private fun insertSignatureFlag(h: Handle, ctx: Ctx, r: Rec, bd: LocalDate, routeId: Long?, reason: String) {
        h.createUpdate(
            """
            INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version)
            VALUES ('DEVICE_INTEGRITY_FAIL', 2, :bd, 'device', :sid, :u, :route, 20, CAST(:ev AS jsonb), :cv)
            ON CONFLICT (code, subject_type, subject_id, business_date) DO NOTHING
            """.trimIndent(),
        ).bind("bd", bd).bind("sid", ctx.up.deviceUuid).bind("u", ctx.up.userId).bind("route", routeId)
            .bind("ev", kotlinx.serialization.json.buildJsonObject {
                put("reason", JsonPrimitive(reason)); put("mode", JsonPrimitive("record"))
                put("record_type", JsonPrimitive(r.type)); put("client_uuid", JsonPrimitive(r.clientUuid))
            }.toString()).bind("cv", config.configVersion()).execute()
    }

    /** GEO_OUT_OF_BOUNDS (s11.4): a fix outside the Bangladesh box raises a signal; the record itself is stored. */
    private fun outOfBounds(h: Handle, ctx: Ctx, r: Rec, rule: TypeRule, payload: JsonObject, bd: LocalDate, routeId: Long?) {
        for (slot in listOf("fix", "edit_fix")) (payload[slot] as? JsonObject)?.let { fix ->
            // In its own savepoint: a failed signal insert never aborts the sale's family transaction.
            val sp = "oob_${r.index}_$slot"
            h.savepoint(sp)
            try {
                outOfBoundsFix(h, ctx, r, rule, fix, bd, routeId)
                h.release(sp)
            } catch (e: Exception) {
                h.rollbackToSavepoint(sp)
                log.error("out-of-bounds signal failed client_uuid=${r.clientUuid}", e)
            }
        }
    }

    private fun outOfBoundsFix(h: Handle, ctx: Ctx, r: Rec, rule: TypeRule, fix: JsonObject, bd: LocalDate, routeId: Long?) {
        val lat = (fix["lat"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return
        val lng = (fix["lng"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return
        if (lat in 20.5..26.7 && lng in 88.0..92.7) return
        h.createUpdate(
            """
            INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version)
            VALUES ('GEO_OUT_OF_BOUNDS', 3, :bd, :st, :sid, :u, :route, 40, CAST(:ev AS jsonb), :cv)
            ON CONFLICT (code, subject_type, subject_id, business_date) DO NOTHING
            """.trimIndent(),
        ).bind("bd", bd).bind("st", if (r.type == "visit") "visit" else "user").bind("sid", if (r.type == "visit") r.clientUuid else ctx.up.userId.toString())
            .bind("u", ctx.up.userId).bind("route", routeId)
            .bind("ev", kotlinx.serialization.json.buildJsonObject {
                put("record_type", JsonPrimitive(rule.type)); put("client_uuid", JsonPrimitive(r.clientUuid)); put("lat", JsonPrimitive(lat)); put("lng", JsonPrimitive(lng))
            }.toString()).bind("cv", config.configVersion()).execute()
    }

    /**
     * N-028 GEO_GNSS_INCONSISTENT (docs/24 s11.4, an instant per-fix signal): a `gps` fix whose GNSS summary does not fit a
     * real sky: fewer than `cfg.geo.gnss_min_satellites_used` satellites used (4), or at least 6 used with a C/N0 standard
     * deviation below `cfg.geo.gnss_cn0_stddev_min_dbhz` (1.0: every satellite equally strong, as a simulator draws them),
     * or a C/N0 mean above `cfg.geo.gnss_cn0_mean_max_dbhz` (48). A fix without a summary or without C/N0 values is not
     * judged on what it lacks. One signal per subject and date; in its own savepoint, so it never decides the record.
     */
    private fun gnssConsistency(h: Handle, ctx: Ctx, r: Rec, rule: TypeRule, payload: JsonObject, bd: LocalDate, routeId: Long?) {
        for (slot in listOf("fix", "edit_fix")) {
            val fix = payload[slot] as? JsonObject ?: continue
            if ((fix["provider"] as? JsonPrimitive)?.content != "gps") continue // judged only for gps; no config read otherwise
            val reason = GnssRule.check(fix, gnssThresholds()) ?: continue
            val sp = "gnss_${r.index}_$slot"
            h.savepoint(sp)
            try {
                h.createUpdate(
                    """
                    INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, score, evidence, config_version)
                    VALUES ('GEO_GNSS_INCONSISTENT', 3, :bd, :st, :sid, :u, :route, 30, CAST(:ev AS jsonb), :cv)
                    ON CONFLICT (code, subject_type, subject_id, business_date) DO NOTHING
                    """.trimIndent(),
                ).bind("bd", bd).bind("st", if (r.type == "visit") "visit" else "user").bind("sid", if (r.type == "visit") r.clientUuid else ctx.up.userId.toString())
                    .bind("u", ctx.up.userId).bind("route", routeId)
                    .bind("ev", JsonObject(reason + mapOf("record_type" to JsonPrimitive(rule.type), "client_uuid" to JsonPrimitive(r.clientUuid), "slot" to JsonPrimitive(slot))).toString())
                    .bind("cv", config.configVersion()).execute()
                h.release(sp)
            } catch (e: Exception) {
                h.rollbackToSavepoint(sp)
                log.error("gnss signal failed client_uuid=${r.clientUuid}", e)
            }
        }
    }

    private fun gnssThresholds(): GnssRule.Thresholds = GnssRule.Thresholds(
        minUsed = runCatching { config.int("cfg.geo.gnss_min_satellites_used") }.getOrDefault(4).coerceIn(3, 12),
        stddevMin = runCatching { config.value("cfg.geo.gnss_cn0_stddev_min_dbhz").jsonPrimitive.content.toDouble() }.getOrDefault(1.0).coerceIn(0.1, 5.0),
        meanMax = runCatching { config.value("cfg.geo.gnss_cn0_mean_max_dbhz").jsonPrimitive.content.toDouble() }.getOrDefault(48.0).coerceIn(40.0, 60.0),
    )

    /** Cross-user parent references the protocol allows, each still bounded by the uploader's reach. */
    private fun crossUserParentAllowed(h: Handle, ctx: Ctx, field: String, parent: String, bd: LocalDate): Boolean {
        fun outletInReach(outletId: Long?): Boolean {
            if (outletId == null) return false
            val (oRoute, oZone) = h.createQuery("SELECT route_id, zone_id FROM app.outlet WHERE id = :o").bind("o", outletId)
                .map { rs, _ -> (rs.getObject(1) as Long?) to rs.getLong(2) }.findOne().orElse(null) ?: return false
            val reach = ctx.reachOn(bd)
            return if (reach.ownRecordsOnly) oRoute != null && oRoute in reach.routeIds else reach.coversZone(oZone) || (oRoute != null && oRoute in reach.routeIds)
        }
        return when (field) {
            "against_memo_client_uuid" -> outletInReach(
                h.createQuery("SELECT outlet_id FROM app.memo WHERE client_uuid = CAST(:p AS uuid) LIMIT 1").bind("p", parent).mapTo(Long::class.java).findOne().orElse(null),
            )
            "task_uuid" -> h.createQuery("SELECT count(*) FROM app.task WHERE client_uuid = CAST(:p AS uuid) AND assignee_user_id = :u").bind("p", parent)
                .bind("u", ctx.up.userId).mapTo(Long::class.java).one() > 0
            "request_uuid" -> outletInReach(
                h.createQuery("SELECT outlet_id FROM app.outlet_change_request WHERE client_uuid = CAST(:p AS uuid) LIMIT 1").bind("p", parent).mapTo(Long::class.java).findOne().orElse(null),
            )
            else -> false
        }
    }

    /** acting_for_user_id (s4.3) is a claim: true only when the uploader covers the route on that date and the named user is its primary. */
    private fun actingForValid(h: Handle, ctx: Ctx, routeId: Long?, actingFor: Long, bd: LocalDate): Boolean {
        if (routeId == null) return false
        return h.createQuery(
            """
            SELECT count(*) FROM app.route_assignment c JOIN app.route_assignment p ON p.route_id = c.route_id AND p.kind = 'primary'
                AND p.valid_from <= :d AND (p.valid_to IS NULL OR p.valid_to > :d)
            WHERE c.route_id = :r AND c.user_id = :u AND c.kind = 'cover' AND c.valid_from <= :d AND (c.valid_to IS NULL OR c.valid_to > :d) AND p.user_id = :a
            """.trimIndent(),
        ).bind("r", routeId).bind("u", ctx.up.userId).bind("d", bd).bind("a", actingFor).mapTo(Long::class.java).one() > 0
    }

    private data class Prior(val status: String, val code: String?, val hash: ByteArray, val serverId: Long?)

    private fun code(wire: String?): RecordOutcomeCode? = wire?.let { w -> RecordOutcomeCode.entries.firstOrNull { it.wire == w } }

    private fun touch(h: Handle, r: Rec) {
        h.createUpdate("UPDATE app.ingest_registry SET last_seen_at = now(), seen_count = seen_count + 1 WHERE client_uuid = CAST(:c AS uuid)").bind("c", r.clientUuid).execute()
    }

    /**
     * [flags] (V0056 `ingest_registry.flags`, e.g. `resync_late`) are merged on the conflict path, never assigned: a later
     * acceptance of a parked or released row never wipes a flag (db-resync-late-flag-answer.md).
     */
    private fun register(
        h: Handle, ctx: Ctx, r: Rec, bd: LocalDate?, status: String, code: RecordOutcomeCode?, serverId: Long?, contentFp: ByteArray? = null,
        flags: List<String> = emptyList(),
    ) {
        h.createUpdate(
            """
            INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, content_fp, family_uuid, status, outcome_code, server_id, business_date, user_id, device_id, first_batch_uuid, received_at, last_seen_at, flags)
            VALUES (CAST(:c AS uuid), :t, :h, :fp, CAST(:f AS uuid), :s, :code, :sid, :bd, :u, :d, CAST(:b AS uuid), :now, :now, :fl)
            ON CONFLICT (client_uuid) DO UPDATE SET status = EXCLUDED.status, outcome_code = EXCLUDED.outcome_code, server_id = EXCLUDED.server_id, content_fp = EXCLUDED.content_fp,
                last_seen_at = EXCLUDED.last_seen_at, seen_count = app.ingest_registry.seen_count + 1,
                flags = ARRAY(SELECT DISTINCT f FROM unnest(app.ingest_registry.flags || EXCLUDED.flags) AS f ORDER BY f)
            WHERE (app.ingest_registry.status = 'parked' OR (app.ingest_registry.status = 'quarantined' AND app.ingest_registry.outcome_code IN ('device_integrity_failed', 'device_not_enrolled')))
              AND app.ingest_registry.payload_sha256 = EXCLUDED.payload_sha256
            """.trimIndent(),
        ).bind("c", r.clientUuid).bind("t", r.type).bind("h", r.hash).bind("fp", contentFp).bind("f", r.family.takeIf { UUID_V4.matches(it) } ?: r.clientUuid)
            .bind("s", status).bind("code", code?.wire).bind("sid", serverId).bind("bd", bd).bind("u", ctx.up.userId).bind("d", ctx.up.deviceId)
            .bind("b", ctx.batchUuid).bind("now", ts(ctx.now)).bindArray("fl", String::class.java, flags).execute()
        if (status == "accepted") {
            h.createUpdate("UPDATE app.sync_rejected SET stored_at = :now WHERE client_uuid = CAST(:c AS uuid) AND payload_sha256 = :h AND stored_at IS NULL")
                .bind("now", ts(ctx.now)).bind("c", r.clientUuid).bind("h", r.hash).execute()
            // A released signature quarantine leaves the review queue once the sale is stored.
            h.createUpdate("UPDATE app.sync_quarantine SET status = 'accepted', resolved_at = :now WHERE client_uuid = CAST(:c AS uuid) AND code IN ('device_integrity_failed', 'device_not_enrolled') AND status = 'open'")
                .bind("now", ts(ctx.now)).bind("c", r.clientUuid).execute()
        }
    }

    /**
     * The open `device_integrity_failed` review item of this record, locked (FOR UPDATE re-checks `status` after a
     * reviewer's concurrent resolve commits, so a discard decided at the same moment stands). Lock order: the uuid
     * advisory lock, then this row; a reviewer takes only the row.
     */
    private fun openIntegrityItem(h: Handle, r: Rec): Boolean = h.createQuery(
        "SELECT id FROM app.sync_quarantine WHERE client_uuid = CAST(:c AS uuid) AND code IN ('device_integrity_failed', 'device_not_enrolled') AND status = 'open' FOR UPDATE",
    ).bind("c", r.clientUuid).mapTo(Long::class.java).list().isNotEmpty()

    /** A released row that ends rejected for another reason closes its integrity review item (nothing left to review). */
    private fun closeIntegrityItem(h: Handle, r: Rec, now: Instant) {
        h.createUpdate("UPDATE app.sync_quarantine SET status = 'discarded', resolved_at = :now WHERE client_uuid = CAST(:c AS uuid) AND code IN ('device_integrity_failed', 'device_not_enrolled') AND status = 'open'")
            .bind("now", ts(now)).bind("c", r.clientUuid).execute()
    }

    /** Moves a registry row (and its open rejected/quarantine copies) from the full-envelope hash to the sig-less one. */
    private fun rekey(h: Handle, r: Rec) {
        h.createUpdate("UPDATE app.ingest_registry SET payload_sha256 = :h WHERE client_uuid = CAST(:c AS uuid) AND payload_sha256 = :old")
            .bind("h", r.hash).bind("old", r.fullHash).bind("c", r.clientUuid).execute()
        h.createUpdate("UPDATE app.sync_rejected SET payload_sha256 = :h WHERE client_uuid = CAST(:c AS uuid) AND payload_sha256 = :old AND stored_at IS NULL")
            .bind("h", r.hash).bind("old", r.fullHash).bind("c", r.clientUuid).execute()
        h.createUpdate("UPDATE app.sync_quarantine SET payload_sha256 = :h WHERE client_uuid = CAST(:c AS uuid) AND payload_sha256 = :old AND status = 'open'")
            .bind("h", r.hash).bind("old", r.fullHash).bind("c", r.clientUuid).execute()
    }

    private fun rejectedRow(h: Handle, ctx: Ctx, r: Rec, code: RecordOutcomeCode, detail: String?, retryable: Boolean) {
        val bd = r.json.str("business_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        h.createUpdate(
            """
            INSERT INTO app.sync_rejected (client_uuid, record_type, code, retryable, payload_sha256, payload, detail, user_id, device_id, route_id, business_date, batch_uuid,
                                           first_received_at, last_received_at, parked_until)
            VALUES (CAST(:c AS uuid), :t, :code, :retry, :h, CAST(:p AS jsonb), :detail, :u, :d, :route, :bd, CAST(:b AS uuid), :now, :now, :until)
            ON CONFLICT (client_uuid, payload_sha256) DO UPDATE SET attempts = app.sync_rejected.attempts + 1, last_received_at = EXCLUDED.last_received_at,
                code = EXCLUDED.code, retryable = EXCLUDED.retryable, detail = EXCLUDED.detail, batch_uuid = EXCLUDED.batch_uuid,
                parked_until = COALESCE(app.sync_rejected.parked_until, EXCLUDED.parked_until)
            """.trimIndent(),
        ).bind("c", r.clientUuid).bind("t", r.type.take(40).ifEmpty { "unknown" }).bind("code", code.wire).bind("retry", retryable).bind("h", r.hash)
            .bind("p", storable(r.json)).bind("detail", detail?.take(1000)).bind("u", ctx.up.userId).bind("d", ctx.up.deviceId)
            .bind("route", r.json.long("route_id")).bind("bd", bd).bind("b", ctx.batchUuid).bind("now", ts(ctx.now))
            .bind("until", if (retryable) ts(ctx.now.plusSeconds(ctx.parkedTtlDays * 86_400)) else null).execute()
    }

    private fun finalReject(h: Handle, ctx: Ctx, r: Rec, code: RecordOutcomeCode, detail: String?): Outcome {
        rejectedRow(h, ctx, r, code, detail, retryable = false)
        if (UUID_V4.matches(r.clientUuid)) closeIntegrityItem(h, r, ctx.now)
        val bd = r.json.str("business_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (r.type.matches(TYPE_PATTERN)) register(h, ctx, r, bd, "rejected", code, null)
        return Outcome.of(code)
    }

    /** A retryable rejection: kept in sync_rejected with parked_until; the registry says parked so a resend is processed again. */
    private fun park(h: Handle, ctx: Ctx, r: Rec, code: RecordOutcomeCode, detail: String?): Outcome {
        if (!UUID_V4.matches(r.clientUuid)) return Outcome.of(code)
        rejectedRow(h, ctx, r, code, detail, retryable = true)
        val bd = r.json.str("business_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (r.type.matches(TYPE_PATTERN)) register(h, ctx, r, bd, "parked", code, null)
        // Parked is retryable whatever the code's default (qty_invalid of a pack size is resent after a bundle refresh).
        return Outcome.of(code).copy(retryable = true)
    }

    private fun quarantine(h: Handle, ctx: Ctx, r: Rec, bd: LocalDate, code: RecordOutcomeCode, detail: String?, register: Boolean = true, contentFp: ByteArray? = null): Outcome {
        h.createUpdate(
            """
            INSERT INTO app.sync_quarantine (client_uuid, record_type, code, payload_sha256, payload, detail, user_id, device_id, route_id, business_date, batch_uuid, received_at)
            VALUES (CAST(:c AS uuid), :t, :code, :h, CAST(:p AS jsonb), :detail, :u, :d, :route, :bd, CAST(:b AS uuid), :now)
            ON CONFLICT (client_uuid, payload_sha256) DO UPDATE SET code = EXCLUDED.code, detail = EXCLUDED.detail
              WHERE app.sync_quarantine.status = 'open' AND app.sync_quarantine.code IN ('device_integrity_failed', 'device_not_enrolled')

            """.trimIndent(),
        ).bind("c", r.clientUuid).bind("t", r.type).bind("code", code.wire).bind("h", r.hash).bind("p", storable(r.json)).bind("detail", detail?.take(1000))
            .bind("u", ctx.up.userId).bind("d", ctx.up.deviceId).bind("route", r.json.long("route_id")).bind("bd", bd).bind("b", ctx.batchUuid)
            .bind("now", ts(ctx.now)).execute()
        if (register) register(h, ctx, r, bd, "quarantined", code, null, contentFp)
        return Outcome.of(code)
    }

    /**
     * Money members (`*_mtk`, integer milli-taka) above the ledger's bound (db V0079/V0080: |amount| <= 10^13, i.e. 10
     * billion taka): refused per record as `schema_invalid` with the member named, before anything is written, so a
     * crafted memo never reaches the ledger insert. Non-integers are left to the shape and money checks.
     */
    private fun moneyOutOfRange(env: JsonObject): String? {
        val p = env["payload"] as? JsonObject ?: return null
        for ((k, v) in p) {
            if (!k.endsWith("_mtk") || v !is JsonPrimitive || v.isString) continue
            val n = v.content.toBigDecimalOrNull() ?: continue
            if (n.abs() > MAX_MTK) return "$k is above the money bound (|value| <= 10^13 milli-taka)"
        }
        return null
    }

    /** Envelope and payload members against the contract (required present, nothing unknown); null when the shape is fine. */
    private fun shapeError(r: Rec, manual: Boolean = false): String? {
        val env = r.json
        val shape = PayloadShapes.BY_TYPE[r.type] ?: return "unknown type '${r.type.take(40)}'"
        (env.keys - PayloadShapes.ENVELOPE.allowed).firstOrNull()?.let { return "unknown envelope member $it" }
        (PayloadShapes.ENVELOPE.required + "payload" - env.keys).firstOrNull()?.let { return "missing envelope member $it" }
        if (!UUID_V4.matches(r.clientUuid)) return "client_uuid is not a lower-case UUID v4"
        if (!UUID_V4.matches(env.str("family_uuid") ?: "")) return "family_uuid is not a lower-case UUID v4"
        val rank = env.int("rank")
        if (rank == null || rank !in 0..3) return "rank must be 0..3"
        val sv = env.int("schema_version")
        if (sv == null || sv !in 1..ContractInfo.SCHEMA_VERSION) return "schema_version must be 1..${ContractInfo.SCHEMA_VERSION}"
        val bd = env.str("business_date")
        if (bd == null || !DATE.matches(bd) || runCatching { LocalDate.parse(bd) }.isFailure) return "business_date"
        val ca = env.str("captured_at")
        if (ca == null || runCatching { Instant.parse(ca) }.isFailure) return "captured_at"
        for (k in listOf("route_id", "acting_for_user_id")) {
            val v = env[k]
            if (v != null && v !is JsonNull && (v !is JsonPrimitive || v.isString || (v.longOrNull ?: 0) < 1)) return "$k must be a positive integer"
        }
        r.canonError?.let { return it }
        if (NUL in r.json.toString()) return "a string holds a NUL character"
        val payload = env["payload"] as? JsonObject ?: return "payload must be an object"
        for ((k, v) in payload) {
            if (v is JsonNull) continue
            if (k.endsWith("_uuid") && (v !is JsonPrimitive || !v.isString)) return "$k must be a string"
            if ((k.endsWith("_id") || k.endsWith("_user_id")) && (v !is JsonPrimitive || v.isString || v.longOrNull == null)) return "$k must be an integer"
        }
        (payload.keys - shape.allowed).firstOrNull()?.let { return "unknown payload member $it" }
        // A paper visit (F-API-038) has no fix: nobody stood at the outlet with a phone.
        (shape.required - payload.keys - (if (manual && r.type == "visit") setOf("fix") else emptySet())).firstOrNull()?.let { return "missing payload member $it" }
        for ((k, nested) in shape.nested) {
            val v = payload[k] ?: continue
            if (v is JsonNull) continue
            val o = v as? JsonObject ?: return "$k must be an object"
            (o.keys - nested.allowed).firstOrNull()?.let { return "unknown member $k.$it" }
            (nested.required - o.keys).firstOrNull()?.let { return "missing member $k.$it" }
        }
        for (k in listOf("outlet_id", "sku_id")) {
            val v = payload[k] ?: continue
            if (v !is JsonNull && (v !is JsonPrimitive || v.isString || (v.longOrNull ?: 0) < 1)) return "$k must be a positive integer"
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Response sections

    /**
     * GET /v1/sync/totals (F-SYS-005 reconciliation): the same totals and day states a batch response carries, for the
     * caller's own records on [date] only (user from the token; no scope ids from the client). Read-only, so a repeated
     * batch, which stores nothing new, leaves it unchanged. On the primary: a lagging replica would show a short count
     * and keep the phone retrying "Sync data".
     */
    fun totals(userId: Long, date: LocalDate): SyncTotalsResponse = db.jdbi.withHandle<SyncTotalsResponse, Exception> { h ->
        SyncTotalsResponse(serverTotals(h, userId, date, clock.now()), dayStates(h, userId, listOf(date)), supervisorDayState(h, userId, date))
    }

    /** Per type accepted/rejected/quarantined of the user's date from the registry, and the money of the date (s4.12). */
    private fun serverTotals(h: Handle, userId: Long, date: LocalDate, now: Instant): ServerTotals {
        val byType = h.createQuery(
            "SELECT record_type, status, count(*) FROM app.ingest_registry WHERE user_id = :u AND business_date = :d GROUP BY record_type, status",
        ).bind("u", userId).bind("d", date).map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getInt(3)) }.list()
            .groupBy { it.first }.mapValues { (_, rows) ->
                fun n(vararg s: String) = rows.filter { it.second in s }.sumOf { it.third }
                TypeOutcomeCounts(accepted = n("accepted", "voided"), rejected = n("rejected", "parked"), quarantined = n("quarantined"))
            }.toSortedMap()
        return ServerTotals(date.toString(), now.wire(), byType, money(h, userId, date))
    }

    private fun money(h: Handle, userId: Long, date: LocalDate): MoneyTotals {
        data class M(val count: Int, val gross: Long, val offer: Long, val drp: Long, val qc: Long, val net: Long, val paid: Long, val due: Long)
        val m = h.createQuery(
            """
            SELECT count(*) FILTER (WHERE line_count > 0), COALESCE(sum(gross_mtk), 0), COALESCE(sum(offer_discount_mtk), 0), COALESCE(sum(drp_discount_mtk), 0),
                   COALESCE(sum(qc_deduction_mtk), 0), COALESCE(sum(net_mtk), 0), COALESCE(sum(paid_mtk), 0), COALESCE(sum(due_mtk), 0)
            FROM app.memo WHERE user_id = :u AND business_date = :d AND status = 'active' AND voided_at IS NULL
            """.trimIndent(),
        ).bind("u", userId).bind("d", date).map { rs, _ -> M(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)) }.one()
        val collected = h.createQuery("SELECT COALESCE(sum(amount_mtk), 0) FROM app.due_collection WHERE user_id = :u AND business_date = :d AND voided_at IS NULL")
            .bind("u", userId).bind("d", date).mapTo(Long::class.java).one()
        val byCategory = h.createQuery(
            """
            SELECT s.category_code, sum(l.gross_mtk) FROM app.memo_line l
            JOIN app.memo m ON m.client_uuid = l.memo_client_uuid AND m.business_date = l.business_date
            JOIN app.sku s ON s.id = l.sku_id
            WHERE m.user_id = :u AND m.business_date = :d AND m.status = 'active' AND m.voided_at IS NULL AND l.voided_at IS NULL
            GROUP BY s.category_code ORDER BY s.category_code
            """.trimIndent(),
        ).bind("u", userId).bind("d", date).map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
        val sold = h.createQuery(
            """
            SELECT l.sku_id, sum(l.qty_base) FROM app.memo_line l
            JOIN app.memo m ON m.client_uuid = l.memo_client_uuid AND m.business_date = l.business_date
            WHERE m.user_id = :u AND m.business_date = :d AND m.status = 'active' AND m.voided_at IS NULL AND l.voided_at IS NULL
            GROUP BY l.sku_id ORDER BY l.sku_id
            """.trimIndent(),
        ).bind("u", userId).bind("d", date).map { rs, _ -> rs.getLong(1).toString() to rs.getLong(2) }.list().toMap()
        val issued = h.createQuery(
            "SELECT sku_id, sum(qty_base) FROM app.stock_movement WHERE user_id = :u AND business_date = :d AND voided_at IS NULL GROUP BY sku_id ORDER BY sku_id",
        ).bind("u", userId).bind("d", date).map { rs, _ -> rs.getLong(1).toString() to rs.getLong(2) }.list().toMap()
        return MoneyTotals(m.count, m.gross, m.offer, m.drp, m.qc, m.net, m.paid, m.due, collected, byCategory, issued, sold)
    }

    private fun dayStates(h: Handle, userId: Long, dates: List<LocalDate>): List<RouteDayStateDto> = h.createQuery(
        """
        SELECT route_id, business_date, state, planned, submit_cycle, submit_voided, submit_count_mismatch, logged_in_at, sales_submitted_at, final_submitted_at
        FROM app.route_day WHERE (assigned_user_id = :u OR acting_user_id = :u) AND business_date = ANY(CAST(:d AS date[])) ORDER BY business_date, route_id LIMIT 40
        """.trimIndent(),
    ).bind("u", userId).bindArray("d", String::class.java, dates.map { it.toString() }).map { rs, _ ->
        fun t(c: String) = rs.getObject(c, OffsetDateTime::class.java)?.toInstant()?.wire()
        RouteDayStateDto(
            rs.getLong("route_id"), rs.getObject("business_date", LocalDate::class.java).toString(), rs.getString("state"), rs.getBoolean("planned"),
            rs.getInt("submit_cycle"), rs.getBoolean("submit_voided"), rs.getObject("submit_count_mismatch") as Boolean?,
            t("logged_in_at"), t("sales_submitted_at"), t("final_submitted_at"),
        )
    }.list()

    /** Decisions on the user's quarantined records in the last 7 days (the phone applies each once, by client_uuid). */
    private fun resolutions(h: Handle, userId: Long, now: Instant): List<Resolution> = h.createQuery(
        """
        SELECT client_uuid, record_type, status, resolved_at FROM app.sync_quarantine
        WHERE user_id = :u AND status <> 'open' AND resolved_at > :since AND code <> 'payload_conflict' ORDER BY resolved_at DESC LIMIT 200
        """.trimIndent(),
    ).bind("u", userId).bind("since", ts(now.minusSeconds(7 * 86_400))).map { rs, _ ->
        Resolution(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime::class.java).toInstant().wire())
    }.list()

    /**
     * F-SYS-090: the backdate window's floor. `cfg.calendar.window_unit` missing or `calendar` keeps today minus
     * `cfg.sync.max_backdate_days`; `working_days` counts selling days of the uploader's home zone (global weekend and
     * the scoped holiday calendar), capped by the registry retention. Read before any family transaction; a read failure
     * is a retryable 503 for the whole batch.
     */
    /**
     * The backdate floor for [userId] on [today] with the configured `cfg.sync.max_backdate_days` (the digest's window).
     * On a read failure the calendar floor: never older than the working-day one, so the digest only asks for less.
     */
    fun backdateFloorFor(userId: Long, today: LocalDate): LocalDate {
        val days = runCatching { config.int("cfg.sync.max_backdate_days") }.getOrDefault(7).toLong()
        return try {
            backdateFloor(userId, today, days)
        } catch (e: ApiProblem) {
            today.minusDays(days)
        }
    }

    private fun backdateFloor(userId: Long, today: LocalDate, days: Long): LocalDate {
        val unit = runCatching { config.string("cfg.calendar.window_unit") }.getOrDefault(WorkingDays.CALENDAR)
        if (unit != WorkingDays.WORKING_DAYS) return today.minusDays(days)
        val registryDays = runCatching { config.int("cfg.retention.ingest_registry_days") }.getOrDefault(45).toLong()
        val ceiling = WorkingDays.ceiling(registryDays, days)
        val weekend = runCatching {
            config.value("cfg.calendar.weekend_days").jsonArray.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }.filter { it in 1..7 }.toSet()
        }.getOrNull() ?: DayPlan.DEFAULT_WEEKEND
        return runCatching {
            db.jdbi.withHandle<LocalDate, Exception> { h ->
                val zone = h.createQuery(
                    """
                    SELECT z.id, z.territory_id, t.division_id, d.wing_id FROM app.app_user u JOIN app.zone z ON z.id = u.home_zone_id
                    JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id WHERE u.id = :u
                    """.trimIndent(),
                ).bind("u", userId).map { rs, _ -> DayPlan.ZoneChain(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }
                    .findOne().orElse(DayPlan.ZoneChain(-1, -1, -1, -1))
                val calendar = h.createQuery("SELECT date, scope_type, scope_id, kind, selling_day FROM app.calendar_holiday WHERE date >= :a AND date < :b AND revoked_at IS NULL")
                    .bind("a", today.minusDays(ceiling)).bind("b", today).map { rs, _ ->
                        DayPlan.CalendarEntry(rs.getObject(1, LocalDate::class.java), DayPlan.CalendarScope.valueOf(rs.getString(2).uppercase()), rs.getLong(3), rs.getString(4), rs.getBoolean(5))
                    }.list()
                WorkingDays.floor(today, days, unit, ceiling) { WorkingDays.isWorking(it, zone, calendar, weekend) }
            }
        }.getOrElse { e ->
            // Not the calendar rule: a row quarantined as too old keeps that answer on every re-send, so a passing read
            // failure on the first morning after a break would lose the relief for good. The batch is retried instead.
            log.warn("working-day backdate window unavailable; batch refused as retryable", e)
            throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "try again shortly", retryAfterS = 30, headers = mapOf("Retry-After" to "30"))
        }
    }

    /**
     * F-SYS-089: the re-send allowance after a failover or restore, for a `resync` or `digest_resend` batch (the digest
     * re-send can come first, s4.8) while the new generation is young: until `cfg.sync.resync_window_h` +
     * `cfg.sync.resync_jitter_s` + a day after it started. A row may be as old as the lost lineage could have accepted:
     * acked at the earliest at max(lost_after_utc, started_at - resync window), then inside `cfg.sync.max_backdate_days`.
     * Read once per batch, before any family transaction (no second connection inside one).
     */
    private fun resyncWindow(trigger: String, now: Instant): ResyncWindow? {
        if (trigger != "resync" && trigger != "digest_resend") return null
        val windowH = runCatching { config.int("cfg.sync.resync_window_h") }.getOrDefault(24).coerceIn(1, 72).toLong()
        val jitterS = runCatching { config.int("cfg.sync.resync_jitter_s") }.getOrDefault(900).coerceIn(0, 3600).toLong()
        val backdate = runCatching { config.int("cfg.sync.max_backdate_days") }.getOrDefault(7).toLong()
        val gen = runCatching {
            db.jdbi.withHandle<Pair<Instant, Instant?>?, Exception> { h ->
                h.createQuery("SELECT started_at, lost_after_utc FROM app.server_generation WHERE is_current AND kind <> 'created'")
                    .map { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() to rs.getObject(2, OffsetDateTime::class.java)?.toInstant() }
                    .findOne().orElse(null)
            }
        }.getOrNull() ?: return null
        val (startedAt, lostAfter) = gen
        if (now.isAfter(startedAt.plusSeconds(windowH * 3600 + jitterS + 86_400))) return null
        val earliestAck = maxOf(lostAfter ?: Instant.MIN, startedAt.minusSeconds(windowH * 3600))
        return ResyncWindow(startedAt, BusinessDate.of(earliestAck.toEpochMilli()).toJavaLocalDate().minusDays(backdate))
    }

    private data class ResyncWindow(val startedAt: Instant, val oldestDate: LocalDate)

    /**
     * True when [captured] is before the user's `cfg.day.checkout_earliest_time` on the business date [bd] (Dhaka;
     * inclusive bound; a check-out after midnight for the previous date is late, not early). A missing or unreadable
     * value never flags. The role and home geography are the user's at sync (a move in between is judged by the new one).
     */
    private fun checkoutTooEarly(h: Handle, up: Uploader, captured: Instant, bd: LocalDate): Boolean {
        val geo = h.createQuery(
            """
            SELECT (SELECT ordinal FROM app.role_def WHERE role = u.role) AS role_ord, z.id AS zone_id, t.id AS territory_id, d.id AS division_id, d.wing_id
            FROM app.app_user u LEFT JOIN app.zone z ON z.id = u.home_zone_id LEFT JOIN app.territory t ON t.id = z.territory_id
            LEFT JOIN app.division d ON d.id = t.division_id WHERE u.id = :u
            """.trimIndent(),
        ).bind("u", up.userId).mapToMap().findOne().orElse(null) ?: return false
        fun long(k: String) = (geo[k] as Number?)?.toLong()
        val chain = ScopedConfig.Chain.of(
            com.aktcl.aron.contract.ConfigScopeType.ROLE to long("role_ord"), com.aktcl.aron.contract.ConfigScopeType.WING to long("wing_id"),
            com.aktcl.aron.contract.ConfigScopeType.DIVISION to long("division_id"), com.aktcl.aron.contract.ConfigScopeType.TERRITORY to long("territory_id"),
            com.aktcl.aron.contract.ConfigScopeType.ZONE to long("zone_id"),
        )
        // The value in force at the capture (config changed later never re-judges an earlier check-out).
        val word = ScopedConfig.load(h, captured, captured, setOf(CHECKOUT_KEY)).value(CHECKOUT_KEY, chain)
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return false
        val earliest = runCatching { java.time.LocalTime.parse(word) }.getOrNull() ?: return false
        return captured.isBefore(bd.atTime(earliest).atZone(DHAKA).toInstant())
    }

    companion object {
        private const val FAMILY_TRIES = 3
        private const val CHECKOUT_KEY = "cfg.day.checkout_earliest_time"
        private val DHAKA: java.time.ZoneId = java.time.ZoneId.of("Asia/Dhaka")
        private const val FAMILY_RETRY_BUDGET_MS = 10_000L
        private val UUID_ANY = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val VOLATILE = setOf("sig", "bundle_version", "bundle_stale", "config_version", "client_uuid", "family_uuid")
        /** N-027: the attendance and sales records the device gate holds (s10.4). */
        val GATED_TYPES = setOf("attendance_event", "memo", "memo_void", "due_collection")
        private val MAX_MTK = java.math.BigDecimal.TEN.pow(13)
        /** Memo references that follow a matched phone memo to its paper twin ([memoAlias]). */
        private val MEMO_ALIAS_FIELDS = mapOf("due_collection" to "against_memo_client_uuid", "memo_void" to "memo_client_uuid", "memo" to "supersedes_client_uuid")
        /** Data-entry (money and stock) records: a reviewer's fix of one needs a second person (docs/24 s8.6). */
        private val MEMO_CHILD_TYPES = setOf("memo_line", "memo_discount", "qc_line")
        val MONEY_TYPES = setOf("memo", "memo_line", "memo_discount", "qc_line", "memo_void", "due_collection", "stock_movement", "redemption", "redemption_line", "day_submit")
        private val DEVICE_HOLD_CODES = setOf(RecordOutcomeCode.DEVICE_INTEGRITY_FAILED.wire, RecordOutcomeCode.DEVICE_NOT_ENROLLED.wire)
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val TYPE_PATTERN = Regex("^[a-z][a-z_]{1,40}$")

        /** Record type a parent reference must name. */
        private val PARENT_TYPES = mapOf(
            "visit_client_uuid" to "visit", "origin_visit_client_uuid" to "visit", "source_visit_client_uuid" to "visit",
            "memo_client_uuid" to "memo", "against_memo_client_uuid" to "memo", "supersedes_client_uuid" to "memo",
            "check_client_uuid" to "distribution_check", "assessment_client_uuid" to "call_assessment", "request_uuid" to "outlet_change_request",
            "task_uuid" to "task", "plan_client_uuid" to "visit_plan", "redemption_client_uuid" to "redemption",
            "visit_plan_outlet_client_uuid" to "visit_plan_outlet",
        )

        /** Table of a parent reference, for parents stored without a registry row (online commands, migrated history). */
        private val PARENT_TABLES = mapOf(
            "visit_client_uuid" to "visit", "origin_visit_client_uuid" to "visit", "source_visit_client_uuid" to "visit",
            "memo_client_uuid" to "memo", "against_memo_client_uuid" to "memo", "supersedes_client_uuid" to "memo",
            "task_uuid" to "task", "request_uuid" to "outlet_change_request",
        )

        private const val NUL = "\\u0000"

        /** The client_uuid string of a record, as acks and replays key it ("" when absent or not a string). */
        fun uuidKey(rec: JsonObject): String = (rec["client_uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

        /** A record as jsonb can hold it (PostgreSQL refuses NUL in text). */
        fun storable(rec: JsonObject): String = rec.toString().replace(NUL, "\\ufffd")

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        fun sha256Hex(b: ByteArray): String = sha256(b).joinToString("") { "%02x".format(it) }
        fun ts(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)

        fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
        fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(b.inputStream()).use { it.readBytes() }
    }
}

/**
 * Visit kinds a field phone may upload, by the uploader's role (F-SYS-078). An AMO or TSO may also make an own sale call
 * (`sr_call`, e.g. covering a route); `web_entry` visits are created by the Web Entry endpoint, never by a phone.
 */
internal val DEVICE_VISIT_KINDS: Map<com.aktcl.aron.contract.Role, Set<String>> = mapOf(
    com.aktcl.aron.contract.Role.SR to setOf("sr_call"),
    com.aktcl.aron.contract.Role.AMO to setOf("sr_call", "amo_control_call", "amo_joint_call"),
    com.aktcl.aron.contract.Role.TSO to setOf("sr_call", "tso_visit"),
)

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
internal fun JsonElement.isNullish(): Boolean = this is JsonNull

/** `cfg.sec.record_signature_mode` (docs/19 s9, D-104): off < record < enforce. */
enum class SignatureMode {
    OFF, RECORD, ENFORCE;

    companion object {
        fun of(v: String?): SignatureMode = when (v?.trim()?.lowercase()) {
            "off" -> OFF
            "enforce" -> ENFORCE
            else -> RECORD
        }
    }
}

/** Paper backfill (F-API-038): who keys it, what it holds (parents first), and at most how many records at once. */
internal val MANUAL_ROLES = setOf(Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
internal val MANUAL_TYPES = listOf("visit", "memo", "memo_line")
internal const val MANUAL_MAX_RECORDS = 200
