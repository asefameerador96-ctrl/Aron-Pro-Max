package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RecordHandlers
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.ServerConfig
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The verified uploader: identity only from the token and the device row, never from the body (docs/24 s4.1 item 3). */
data class Uploader(
    val userId: Long,
    val role: Role,
    val scopeVersion: Long,
    val deviceId: Long,
    val deviceUuid: String,
    /** The device's key for record `sig` checks; null for a phone without a usable key (dev database only). */
    val deviceKey: ECPublicKey?,
)

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
    private data class Outcome(val status: String, val code: RecordOutcomeCode? = null, val serverId: Long? = null) {
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
        val hash: ByteArray = if (canonError == null) Jcs.sha256(json) else sha256(json.toString().toByteArray())
        val hashHex: String = hash.joinToString("") { "%02x".format(it) }
    }

    /** Per-batch caches. */
    private inner class Ctx(val up: Uploader, val batchUuid: String, val now: Instant) {
        /** Route-days this batch touched (F-SYS-016), settled at its end. */
        val touched = DayStates.Touched()
        val today: LocalDate = BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
        private val reaches = HashMap<LocalDate, Reach>()
        fun reachOn(d: LocalDate): Reach = reaches.getOrPut(d) { reach.reach(up.userId, up.role, up.scopeVersion, d) }
        val backdateDays: Long = runCatching { config.int("cfg.sync.max_backdate_days") }.getOrDefault(7).toLong()
        val parkedTtlDays: Long = runCatching { config.int("cfg.sync.parked_ttl_days") }.getOrDefault(7).toLong()
        /** Memo arithmetic failures of the current family segment (client_uuid to detail). */
        var arith: Map<String, String> = emptyMap()
        /** Memo content fingerprints of the current segment (s4.5: same outlet, lines and minute). */
        var memoFps: Map<String, ByteArray> = emptyMap()
    }

    fun ingest(up: Uploader, req: SyncBatchRequest): SyncBatchResponse {
        val now = clock.now()
        if (req.schema_version > ContractInfo.SCHEMA_VERSION) {
            throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_SCHEMA_VERSION, "schema_version ${req.schema_version} is above ${ContractInfo.SCHEMA_VERSION}")
        }
        val recs = req.records.mapIndexed { i, r -> Rec(i, r) }
        val fingerprint = sha256(recs.map { "${it.clientUuid}:${it.hashHex}" }.sorted().joinToString("\n").toByteArray())

        replayOrClaim(up, req, recs.size, fingerprint, now)?.let { return it }

        val ctx = Ctx(up, req.batch_uuid, now)
        val outcomes = arrayOfNulls<Outcome>(recs.size)
        var i = 0
        while (i < recs.size) {
            var j = i
            while (j + 1 < recs.size && recs[j + 1].family == recs[i].family) j++
            val family = recs.subList(i, j + 1)
            ctx.arith = MemoChecks.familyMismatches(family.map { it.json }) + db.jdbi.withHandle<Map<String, String>, Exception> { h -> MemoChecks.unknownSkuSiblings(h, family.map { it.json }) }
            ctx.memoFps = MemoChecks.memoFingerprints(family.map { it.json })
            try {
                db.jdbi.inTransaction<Unit, Exception> { h -> family.forEach { r -> outcomes[r.index] = processInSavepoint(h, ctx, r) } }
            } catch (e: Exception) {
                log.error("family failed batch_uuid=${req.batch_uuid} family=${recs[i].family}", e)
                db.jdbi.useTransaction<Exception> { h -> family.forEach { r -> outcomes[r.index] = park(h, ctx, r, RecordOutcomeCode.SERVER_ERROR, e.javaClass.simpleName) } }
            }
            i = j + 1
        }

        // Day states move forward from what this batch brought (F-SYS-016, s4.9); a failure here never fails the batch.
        DayStates.settleMinutes = runCatching { config.int("cfg.day.submit_settle_timeout_min").toLong() }.getOrDefault(30L)
        runCatching { db.jdbi.useTransaction<Exception> { h -> DayStates.afterBatch(h, up.userId, ctx.touched, now) } }
            .onFailure { log.error("day states failed batch_uuid=${req.batch_uuid}", it) }

        val acks = recs.map { r ->
            val o = outcomes[r.index]!!
            RecordAck(
                client_uuid = r.clientUuid, type = r.type, status = o.status, code = o.code?.wire,
                retryable = if (o.status == "rejected") o.code?.retryable else null,
                message_key = o.code?.let { "sync.outcome.${it.wire}" }, server_id = o.serverId,
            )
        }
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
     * batch go on. Only when the savepoint itself cannot be rolled back does the family fail as a whole.
     */
    private fun processInSavepoint(h: Handle, ctx: Ctx, r: Rec): Outcome {
        val sp = "rec_${r.index}"
        h.savepoint(sp)
        return try {
            process(h, ctx, r).also { h.release(sp) }
        } catch (e: Exception) {
            if (e is ApiProblem) throw e
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
        val shapeError = shapeError(r)
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
        val env = r.json
        val payload = env["payload"] as JsonObject
        val bd = LocalDate.parse(env.str("business_date")!!)

        // 2. Registry: one uniqueness point for every device record. A transaction-scoped advisory lock on the uuid
        // serialises concurrent batches carrying the same client_uuid (FOR UPDATE locks nothing while no row exists).
        h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", r.clientUuid)
        val prior = h.createQuery("SELECT status, outcome_code, payload_sha256, server_id FROM app.ingest_registry WHERE client_uuid = CAST(:c AS uuid) FOR UPDATE")
            .bind("c", r.clientUuid).map { rs, _ -> Prior(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getObject(4) as Long?) }.findOne().orElse(null)
        if (prior != null) {
            if (!prior.hash.contentEquals(r.hash)) return quarantine(h, ctx, r, bd, RecordOutcomeCode.PAYLOAD_CONFLICT, "same client_uuid, different payload", register = false)
            when (prior.status) {
                "accepted", "voided" -> { touch(h, r); return Outcome.duplicate(prior.serverId) }
                "rejected" -> { touch(h, r); return Outcome.of(code(prior.code) ?: RecordOutcomeCode.SCHEMA_INVALID) }
                "quarantined" -> { touch(h, r); return Outcome.of(code(prior.code) ?: RecordOutcomeCode.PAYLOAD_CONFLICT) }
                // parked: process again (the parent may have arrived)
            }
        }

        // 3. Business-date window (s3.8 item 4): too old or in the future is quarantined, never dropped.
        val captured = Instant.parse(env.str("captured_at")!!)
        if (bd.isBefore(ctx.today.minusDays(ctx.backdateDays)) || bd.isAfter(ctx.today) || captured.isAfter(ctx.now.plusSeconds(600))) {
            return quarantine(h, ctx, r, bd, RecordOutcomeCode.BUSINESS_DATE_OUT_OF_WINDOW, "business_date $bd, today ${ctx.today}")
        }

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

        // 5. Scope on the record's business date (s8.4): from the token's user, never from the body.
        if (!rule.telemetry) {
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

        env.long("acting_for_user_id")?.let { a ->
            if (!actingForValid(h, ctx, routeId, a, bd)) return quarantine(h, ctx, r, bd, RecordOutcomeCode.SCOPE_OUT_OF_REACH, "acting_for_user_id $a without a cover of route $routeId")
        }

        // 6. Record signature on header records when the device has a key (s8.3).
        if (rule.signedHeader && ctx.up.deviceKey != null) {
            val sig = env.str("sig")
            val unsigned = JsonObject(env.filterKeys { it != "sig" })
            val msg = listOf("aron-sig-v1", r.type, r.clientUuid, sha256Hex(Jcs.canonicalize(unsigned).toByteArray())).joinToString("\n")
            if (sig == null || !com.aktcl.aron.backend.platform.DeviceProof.verify(ctx.up.deviceKey, msg, sig)) {
                return quarantine(h, ctx, r, bd, RecordOutcomeCode.DEVICE_INTEGRITY_FAILED, "record signature does not verify")
            }
        }

        // 7. Content fingerprint (F-SYS-055): a header re-sent under regenerated uuids is the same content; the first
        // copy stands and the second is quarantined content_duplicate, never stored twice.
        val contentFp = ctx.memoFps[r.clientUuid] ?: if (rule.signedHeader) contentFingerprint(r) else null
        if (contentFp != null) {
            // Serialise re-mints carrying the same content under different uuids (the uuid lock does not cover them).
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 2))", "cfp:" + contentFp.joinToString("") { "%02x".format(it) })
            val twin = h.createQuery(
                "SELECT client_uuid::text FROM app.ingest_registry WHERE user_id = :u AND record_type = :t AND content_fp = :fp AND client_uuid <> CAST(:c AS uuid) AND status IN ('accepted','voided') LIMIT 1",
            ).bind("u", ctx.up.userId).bind("t", r.type).bind("fp", contentFp).bind("c", r.clientUuid).mapTo(String::class.java).findOne().orElse(null)
            if (twin != null) return quarantine(h, ctx, r, bd, RecordOutcomeCode.CONTENT_DUPLICATE, "same content as $twin", contentFp = contentFp)
        }

        // 8. Money (F-SYS-062, s7.4): a memo family whose equations fail is quarantined whole, before anything is stored.
        (ctx.arith[r.clientUuid] ?: MemoChecks.recordMismatch(r.type, payload))?.let { why ->
            return quarantine(h, ctx, r, bd, RecordOutcomeCode.ARITHMETIC_MISMATCH, why)
        }

        // 8b. Handlers' early checks (before parents): a refusal here is final even when the parent is missing.
        handlers.forType(r.type).let { early ->
            if (early.isNotEmpty()) {
                val rec = IngestRecord(r.type, r.clientUuid, bd, env, payload, ctx.up.userId, ctx.up.role, ctx.up.deviceId, ctx.batchUuid, ctx.now)
                for (hd in early) hd.checkEarly(h, rec)?.let { return refuse(h, ctx, r, bd, it.code, it.detail) }
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
            if (owner != ctx.up.userId && !crossUserParentAllowed(h, ctx, field, parent, bd)) {
                return quarantine(h, ctx, r, bd, RecordOutcomeCode.SCOPE_OUT_OF_REACH, "$field $parent belongs to another user")
            }
        }

        // 10. Visit-kind policy (F-SYS-078): a phone books only its role's kinds; web_entry comes from the web only.
        if (r.type == "visit") payload.str("visit_kind")?.let { k ->
            if (k !in (DEVICE_VISIT_KINDS[ctx.up.role] ?: emptySet())) {
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
            return refuse(h, ctx, r, bd, refusal.code, refusal.detail)
        }
        val stored = RecordWriter.write(h, r.type, rule, env, payload, ctx.up, ctx.batchUuid, ctx.now)
        return when (stored) {
            is RecordWriter.Result.Stored -> {
                register(h, ctx, r, bd, "accepted", null, stored.serverId, contentFp)
                MemoChecks.afterChildStored(h, r.type, payload)
                DuesLedger.afterStored(h, r.type, env, payload)
                // Day states follow the record but never decide its outcome: a failure here is logged, the record stays stored.
                h.savepoint("day_${r.index}")
                try {
                    DayStates.afterStored(h, r.type, env, payload, ctx.up, ctx.now, routeDays, ctx.touched)
                    h.release("day_${r.index}")
                } catch (e: Exception) {
                    h.rollbackToSavepoint("day_${r.index}")
                    log.error("day state update failed client_uuid=${r.clientUuid} type=${r.type}", e)
                }
                hs.forEach { it.afterStored(h, ingestRec!!, stored.serverId) }
                outOfBounds(h, ctx, r, rule, payload, bd, routeId)
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

    /** GEO_OUT_OF_BOUNDS (s11.4): a fix outside the Bangladesh box raises a signal; the record itself is stored. */
    private fun outOfBounds(h: Handle, ctx: Ctx, r: Rec, rule: TypeRule, payload: JsonObject, bd: LocalDate, routeId: Long?) {
        for (slot in listOf("fix", "edit_fix")) (payload[slot] as? JsonObject)?.let { outOfBoundsFix(h, ctx, r, rule, it, bd, routeId) }
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

    private fun register(h: Handle, ctx: Ctx, r: Rec, bd: LocalDate?, status: String, code: RecordOutcomeCode?, serverId: Long?, contentFp: ByteArray? = null) {
        h.createUpdate(
            """
            INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, content_fp, family_uuid, status, outcome_code, server_id, business_date, user_id, device_id, first_batch_uuid, received_at, last_seen_at)
            VALUES (CAST(:c AS uuid), :t, :h, :fp, CAST(:f AS uuid), :s, :code, :sid, :bd, :u, :d, CAST(:b AS uuid), :now, :now)
            ON CONFLICT (client_uuid) DO UPDATE SET status = EXCLUDED.status, outcome_code = EXCLUDED.outcome_code, server_id = EXCLUDED.server_id, content_fp = EXCLUDED.content_fp,
                last_seen_at = EXCLUDED.last_seen_at, seen_count = app.ingest_registry.seen_count + 1
            WHERE app.ingest_registry.status = 'parked' AND app.ingest_registry.payload_sha256 = EXCLUDED.payload_sha256
            """.trimIndent(),
        ).bind("c", r.clientUuid).bind("t", r.type).bind("h", r.hash).bind("fp", contentFp).bind("f", r.family.takeIf { UUID_V4.matches(it) } ?: r.clientUuid)
            .bind("s", status).bind("code", code?.wire).bind("sid", serverId).bind("bd", bd).bind("u", ctx.up.userId).bind("d", ctx.up.deviceId)
            .bind("b", ctx.batchUuid).bind("now", ts(ctx.now)).execute()
        if (status == "accepted") {
            h.createUpdate("UPDATE app.sync_rejected SET stored_at = :now WHERE client_uuid = CAST(:c AS uuid) AND payload_sha256 = :h AND stored_at IS NULL")
                .bind("now", ts(ctx.now)).bind("c", r.clientUuid).bind("h", r.hash).execute()
        }
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
        return Outcome.of(code)
    }

    private fun quarantine(h: Handle, ctx: Ctx, r: Rec, bd: LocalDate, code: RecordOutcomeCode, detail: String?, register: Boolean = true, contentFp: ByteArray? = null): Outcome {
        h.createUpdate(
            """
            INSERT INTO app.sync_quarantine (client_uuid, record_type, code, payload_sha256, payload, detail, user_id, device_id, route_id, business_date, batch_uuid, received_at)
            VALUES (CAST(:c AS uuid), :t, :code, :h, CAST(:p AS jsonb), :detail, :u, :d, :route, :bd, CAST(:b AS uuid), :now)
            ON CONFLICT (client_uuid, payload_sha256) DO NOTHING
            """.trimIndent(),
        ).bind("c", r.clientUuid).bind("t", r.type).bind("code", code.wire).bind("h", r.hash).bind("p", storable(r.json)).bind("detail", detail?.take(1000))
            .bind("u", ctx.up.userId).bind("d", ctx.up.deviceId).bind("route", r.json.long("route_id")).bind("bd", bd).bind("b", ctx.batchUuid)
            .bind("now", ts(ctx.now)).execute()
        if (register) register(h, ctx, r, bd, "quarantined", code, null, contentFp)
        return Outcome.of(code)
    }

    /** Envelope and payload members against the contract (required present, nothing unknown); null when the shape is fine. */
    private fun shapeError(r: Rec): String? {
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
        (shape.required - payload.keys).firstOrNull()?.let { return "missing payload member $it" }
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

    companion object {
        private val UUID_ANY = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val VOLATILE = setOf("sig", "bundle_version", "bundle_stale", "config_version", "client_uuid", "family_uuid")
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
