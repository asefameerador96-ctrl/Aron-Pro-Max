package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.decodeStrict
import com.aktcl.aron.backend.platform.wire
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.util.concurrent.Semaphore
import java.util.zip.GZIPInputStream
import kotlin.random.Random

class SyncDeps(
    val bundles: BundleService,
    val guard: AuthGuardDeps,
    val ingest: IngestService? = null,
    val db: Database? = null,
    val config: ServerConfig? = null,
    val clock: AronClock = AronClock.SYSTEM,
) {
    /** Batches in flight on this replica (docs/24 s3.6, `cfg.api.inflight_batches_per_replica`). */
    val inflight = Semaphore(config?.let { runCatching { it.int("cfg.api.inflight_batches_per_replica") }.getOrNull() } ?: 64)
}

/** backend:sync routes (docs/24 s4, s6.2). */
fun Route.syncRoutes(d: SyncDeps) {
    authenticated(d.guard) {
        get("/sync/bundle") { getBundle(call, d) }
        get("/sync/bundle/page") {
            val p = call.principal
            if (!p.isPhone) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the bundle is for the field apps")
            val q = call.request.queryParameters
            call.respond(withContext(Dispatchers.IO) { d.bundles.page(p, q["bundle_version"], q["section"], q["page"]) })
        }
        get("/sync/delta") { getDelta(call, d) }
        if (d.db != null) get("/sync/generation") { getGeneration(call, d.db) }
        if (d.db != null) {
            val digest = SyncDigestService(d.db, d.clock) {
                d.config?.let { c -> runCatching { c.int("cfg.sync.max_backdate_days").toLong() }.getOrNull() }?.coerceIn(1, 31) ?: SyncDigestService.DEFAULT_WINDOW_DAYS
            }
            post("/sync/digest") {
                val req = call.receiveStrict(SyncDigestRequest.serializer())
                call.respond(withContext(Dispatchers.IO) { digest.compare(call.principal, req) })
            }
        }
        d.ingest?.let { ingest ->
            get("/sync/totals") {
                val raw = call.request.queryParameters["business_date"]
                val date = raw?.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                    ?: throw com.aktcl.aron.backend.platform.ApiProblem(
                        com.aktcl.aron.contract.ProblemCode.ERR_VALIDATION, "business_date must be YYYY-MM-DD",
                        errors = listOf(com.aktcl.aron.backend.platform.FieldError("query.business_date", if (raw == null) "required" else "invalid_value")),
                    )
                call.respond(withContext(Dispatchers.IO) { ingest.totals(call.principal.userId, date) })
            }
        }
    }
    if (d.ingest != null) {
        // The upload grant may call the batch, an access token expired by at most 60 s is accepted, and a stale scope
        // version never stalls an upload (scope is re-checked per record) (docs/24 s3.2, s8.1, s8.4).
        authenticated(d.guard, { audiences = setOf(Audience.API, Audience.UPLOAD); expiredGraceS = 60; checkScopeVersion = false }) {
            post("/sync/batch") { postBatch(call, d) }
        }
    }
}

/** Wire form of the current generation (contract `ServerGeneration`, docs/24 s4.8). */
@kotlinx.serialization.Serializable
data class ServerGenerationDto(
    val generation: String, val kind: String, val restore_point_utc: String?, val lost_after_utc: String?, val minted_at: String,
    /** The generation this one replaced (F-SYS-047: two restores before the phone called). */
    val previous_generation: String? = null,
    /** With `?since=`: the earliest loss among the generations minted after it; the phone re-sends from there. */
    val earliest_lost_after_utc: String? = null,
)

/**
 * GET /v1/sync/generation (contract getServerGeneration): the current `app.server_generation` row, for any signed-in
 * caller. A phone that sees a new `X-Server-Generation` reads it to learn what to re-send (records acked after
 * `lost_after_utc`). Database kinds map to the wire: created -> initial, failover, pitr_restore -> pitr.
 */
private suspend fun getGeneration(call: ApplicationCall, db: Database) {
    val since = call.request.queryParameters["since"]?.let { raw ->
        runCatching { java.util.UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw.lowercase() }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "since must be a generation uuid", errors = listOf(com.aktcl.aron.backend.platform.FieldError("query.since", "invalid_value")))
    }
    val g = withContext(Dispatchers.IO) {
        db.jdbi.withHandle<ServerGenerationDto?, Exception> { h ->
            h.createQuery(
                """
                SELECT g.generation::text, g.kind, g.restore_point_utc, g.lost_after_utc, g.started_at,
                       (SELECT p.generation::text FROM app.server_generation p WHERE p.started_at < g.started_at ORDER BY p.started_at DESC LIMIT 1) AS previous,
                       (SELECT min(a.lost_after_utc) FROM app.server_generation a, app.server_generation s
                         WHERE s.generation = CAST(:since AS uuid) AND s.generation <> g.generation AND a.started_at > s.started_at) AS earliest
                FROM app.server_generation g WHERE g.is_current
                """.trimIndent(),
            ).bind("since", since?.toString())
                .map { rs, _ ->
                    fun ts(c: String) = rs.getObject(c, java.time.OffsetDateTime::class.java)?.toInstant()?.wire()
                    ServerGenerationDto(
                        rs.getString("generation"),
                        when (val k = rs.getString("kind")) { "created" -> "initial"; "pitr_restore" -> "pitr"; else -> k },
                        ts("restore_point_utc"), ts("lost_after_utc"), ts("started_at")!!, rs.getString("previous"), ts("earliest"),
                    )
                }.findOne().orElse(null)
        }
    } ?: throw ApiProblem(ProblemCode.ERR_INTERNAL, "no current server generation")
    call.respond(g)
}

/**
 * POST /v1/sync/batch (contract postSyncBatch): gzip body only (415 otherwise), at most `cfg.api.max_batch_body_kb`
 * compressed and `cfg.api.max_batch_decompressed_mb` decompressed, 1 to 500 records, `X-Device-Proof` over the gzip
 * bytes when the device has a key, at most `cfg.api.inflight_batches_per_replica` in flight (503 with Retry-After
 * 5 to 60 s). Never gated by the app version.
 */
private suspend fun postBatch(call: ApplicationCall, d: SyncDeps) {
    val p = call.principal
    val cfg = d.config!!
    val deviceId = p.deviceId
    val deviceUuid = p.deviceUuid
    if (!p.isPhone || deviceId == null || deviceUuid == null) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the batch is for the field apps")
    if (cfg.boolOr("cfg.ops.read_only_mode", false)) throw retryLater(ProblemCode.ERR_READ_ONLY_MODE, "read-only mode", 300)
    holdByVersion(cfg, call.request.headers["X-App-Version"])?.let { throw it }
    if (call.request.headers["Content-Encoding"]?.lowercase() != "gzip") throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE, "the batch must be sent with Content-Encoding: gzip")
    val ct = call.request.contentType()
    if (ct.contentType != "application" || ct.contentSubtype != "json") throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json")
    val maxGz = cfg.intOr("cfg.api.max_batch_body_kb", 1024).toLong() * 1024
    val maxRaw = cfg.intOr("cfg.api.max_batch_decompressed_mb", 8).toLong() * 1024 * 1024
    call.request.headers["Content-Length"]?.toLongOrNull()?.let { if (it > maxGz) throw ApiProblem(ProblemCode.ERR_SYNC_BATCH_TOO_LARGE, "batch above $maxGz bytes compressed") }
    val gz = call.receiveChannel().readRemaining(maxGz + 1).readByteArray()
    if (gz.size > maxGz) throw ApiProblem(ProblemCode.ERR_SYNC_BATCH_TOO_LARGE, "batch above $maxGz bytes compressed")
    val raw = try {
        GZIPInputStream(gz.inputStream()).use { it.readNBytes((maxRaw + 1).toInt()) }
    } catch (e: java.io.IOException) {
        throw ApiProblem(ProblemCode.ERR_MALFORMED_JSON, "body is not valid gzip")
    }
    if (raw.size > maxRaw) throw ApiProblem(ProblemCode.ERR_SYNC_DECOMPRESSION_LIMIT, "batch above $maxRaw bytes decompressed")
    val req = decodeStrict(SyncBatchRequest.serializer(), raw.decodeToString())
    if (!IngestService.UUID_V4.matches(req.batch_uuid)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "batch_uuid", errors = listOf(FieldError("/batch_uuid", "invalid_value")))
    if (req.records.isEmpty()) throw ApiProblem(ProblemCode.ERR_VALIDATION, "records is empty", errors = listOf(FieldError("/records", "min_items")))
    if (req.records.size > cfg.intOr("cfg.api.max_batch_rows", 500).coerceAtMost(500)) throw ApiProblem(ProblemCode.ERR_SYNC_BATCH_TOO_LARGE, "more than 500 records")
    if (req.trigger !in TRIGGERS) throw ApiProblem(ProblemCode.ERR_VALIDATION, "trigger", errors = listOf(FieldError("/trigger", "invalid_value")))
    if (req.device_uuid.lowercase() != p.deviceUuid) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device_uuid differs from the token's device")

    // The device row: its key verifies X-Device-Proof over the gzip bytes (s8.3); identity never comes from the body.
    val device = d.db!!.jdbi.withHandle<Pair<String?, Long>?, Exception> { h ->
        h.createQuery("SELECT public_key_jwk::text, id FROM app.device WHERE id = :d AND device_uuid = CAST(:u AS uuid)").bind("d", p.deviceId).bind("u", p.deviceUuid)
            .map { rs, _ -> rs.getString(1) to rs.getLong(2) }.findOne().orElse(null)
    } ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device unknown")
    val key = device.first?.let(DeviceProof::publicKey)
    if (key != null) {
        val proof = call.request.headers["X-Device-Proof"] ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "X-Device-Proof is required")
        val attempt = call.request.headers["X-Batch-Attempt"] ?: "1"
        val msg = listOf("aron-proof-v1", "batch", p.deviceUuid, DeviceProof.sha256Hex(gz), req.batch_uuid, attempt).joinToString("\n")
        if (!DeviceProof.verify(key, msg, proof)) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device proof does not verify")
    } else if (cfg.boolOr("cfg.device.require_enrolled", true)) {
        throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device key unknown")
    }

    if (!d.inflight.tryAcquire()) {
        throw retryLater(ProblemCode.ERR_SERVICE_UNAVAILABLE, "too many batches in flight", Random.nextInt(5, 61))
    }
    try {
        val up = Uploader(p.userId, p.role, p.scopeVersion, deviceId, deviceUuid, key)
        val res = withContext(Dispatchers.IO) { d.ingest!!.ingest(up, req) }
        call.respond(res)
    } finally {
        d.inflight.release()
    }
}

private val TRIGGERS = com.aktcl.aron.contract.SyncTrigger.entries.map { it.wire }.toSet()

private fun ServerConfig.intOr(key: String, fallback: Int): Int = runCatching { int(key) }.getOrDefault(fallback)
private fun ServerConfig.boolOr(key: String, fallback: Boolean): Boolean = runCatching { bool(key) }.getOrDefault(fallback)

/** `cfg.ops.sync_hold_by_version`: builds listed (version code, or the full X-App-Version) get 429 ERR_SYNC_HOLD. */
private fun holdByVersion(cfg: ServerConfig, appVersion: String?): ApiProblem? {
    val list = runCatching { cfg.value("cfg.ops.sync_hold_by_version").jsonArray }.getOrNull() ?: return null
    val code = appVersion?.substringAfterLast('+', "")
    val held = list.any { e -> val c = (e as? JsonPrimitive)?.content; c != null && (c == code || c == appVersion) }
    return if (held) retryLater(ProblemCode.ERR_SYNC_HOLD, "uploads from this build are on hold", 300) else null
}

/**
 * GET /v1/sync/bundle (contract getBundle): the full bundle of the caller for `for` (default today), gzip on the wire
 * (platform Compression), `ETag` = `bundle_version`, `304` when `If-None-Match` names it. The login side effects
 * (route-day `logged_in`, frozen target) happen on 200 and 304 alike.
 */
private suspend fun getBundle(call: ApplicationCall, d: SyncDeps) {
    val p = call.principal
    if (!p.isPhone) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the bundle is for the field apps")
    val forDate = call.request.queryParameters["for"]?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { _ -> DATE.matches(it) }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "for must be YYYY-MM-DD", errors = listOf(FieldError("query.for", "invalid_value")))
    }
    val r = d.bundles.bundle(p, forDate, call.request.headers["X-App-Version"])
    call.response.header(HttpHeaders.ETag, "\"${r.version}\"")
    call.response.header("X-Bundle-Version-Current", r.version)
    if (ifNoneMatch(call.request.headers[HttpHeaders.IfNoneMatch], r.version)) {
        call.respond(HttpStatusCode.NotModified)
        return
    }
    call.respond(r.bundle)
}

/**
 * GET /v1/sync/delta (contract getBundleDelta): the changes since the phone's cursor, 304 when there are none
 * (BundleService.delta). gzip on the wire like the bundle.
 */
private suspend fun getDelta(call: ApplicationCall, d: SyncDeps) {
    val p = call.principal
    if (!p.isPhone) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the bundle is for the field apps")
    val forDate = call.request.queryParameters["for"]?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { _ -> DATE.matches(it) }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "for must be YYYY-MM-DD", errors = listOf(FieldError("query.for", "invalid_value")))
    }
    val delta = withContext(Dispatchers.IO) { d.bundles.delta(p, call.request.queryParameters["since"], forDate, call.request.headers["X-App-Version"]) }
    if (delta == null) {
        call.respond(HttpStatusCode.NotModified)
        return
    }
    call.response.header("X-Bundle-Version-Current", delta.meta.bundle_version)
    call.respond(delta)
}

private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** True when the header lists [version] (quoted or bare, weak or strong) or is `*`. */
internal fun ifNoneMatch(header: String?, version: String): Boolean = header != null && header.split(',').map { it.trim() }.any { tag ->
    tag == "*" || tag.removePrefix("W/").trim('"') == version
}

private fun retryLater(code: ProblemCode, detail: String, s: Int) = ApiProblem(code, detail, retryAfterS = s, headers = mapOf("Retry-After" to s.toString()))
