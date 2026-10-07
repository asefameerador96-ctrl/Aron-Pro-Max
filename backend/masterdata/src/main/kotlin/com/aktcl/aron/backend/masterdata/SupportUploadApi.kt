package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Issues Blob URLs without any SDK or secret in this module: the Azure implementation (a user-delegation SAS from the
 * managed identity) lives in the deployment lane; tests use a fake. A write URL is write-only and short-lived.
 */
interface BlobSasIssuer {
    /** Write-only URL for one blob of at most [maxBytes], valid until [expiresAt]. */
    fun writeSas(blobPath: String, maxBytes: Long, expiresAt: Instant): String

    /** URL phones and the web use to read a stored asset (tutorial, AV, KV). */
    fun readUrl(blobPath: String): String
}

/** Contract `SupportUploadRequest`. */
@Serializable
data class SupportUploadIn(val upload_uuid: String, val bytes: Long, val sha256: String, val app_version: String, val last_sync_at: String? = null, val pending_rows: Int? = null)

/** Contract `SupportUpload`. */
@Serializable
data class SupportUploadOut(val upload_uuid: String, val upload_url: String, val blob_path: String, val expires_at: String)

class SupportUploadDeps(val db: Database, val blob: BlobSasIssuer, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private const val SAS_TTL_S = 900L

/**
 * F-API-030 `POST /v1/support/pda-upload` (createSupportUpload): a write-only SAS for one encrypted zip of at most
 * `cfg.support.max_upload_mb` MB. Idempotent by `upload_uuid`: a repeat returns the same blob path and a fresh SAS.
 * Phones only (X-Device-Id is bound in the token).
 */
fun Route.supportUploadRoutes(d: SupportUploadDeps) {
    authenticated(d.guard) {
        post("/support/pda-upload") { call.respond(create(call, d)) }
    }
}

private suspend fun create(call: ApplicationCall, d: SupportUploadDeps): SupportUploadOut {
    val p = call.principal
    val device = p.deviceUuid?.takeIf { p.isPhone } ?: throw AdminSupport.forbidden("PDA to Support is a phone call")
    val req = call.receiveStrict(SupportUploadIn.serializer())
    val id = AdminSupport.uuid(req.upload_uuid, "body.upload_uuid")
    if (req.bytes !in 1..104_857_600L) bad("body.bytes", "out_of_range")
    if (!Regex("^[0-9a-f]{64}$").matches(req.sha256)) bad("body.sha256")
    if (req.app_version.length !in 1..40) bad("body.app_version", "length")
    val lastSync = req.last_sync_at?.let { runCatching { Instant.parse(it) }.getOrNull() ?: bad("body.last_sync_at") }
    if (req.pending_rows != null && req.pending_rows < 0) bad("body.pending_rows", "out_of_range")
    val maxMb = AdminSupport.intConfig(d.config, "cfg.support.max_upload_mb", 20)
    if (req.bytes > maxMb * 1_048_576L) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "the file is above $maxMb MB", errors = listOf(FieldError("body.bytes", "above_cap")))
    val now = d.clock.now()
    val sha = req.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val stored = d.db.jdbi.inTransaction<Pair<String, Boolean>, Exception> { h ->
        val path = "support/${now.atOffset(ZoneOffset.UTC).year}/${p.userId}/$id.zip.enc"
        val inserted = h.createUpdate(
            "INSERT INTO app.support_upload (upload_uuid, user_id, device_uuid, bytes, sha256, app_version, last_sync_at, pending_rows, blob_path) VALUES (:u, :uid, :dev, :b, :sha, :v, CAST(:ls AS timestamptz), CAST(:pr AS int), :path) ON CONFLICT (upload_uuid) DO NOTHING",
        ).bind("u", id).bind("uid", p.userId).bind("dev", UUID.fromString(device)).bind("b", req.bytes).bind("sha", sha).bind("v", req.app_version)
            .bind("ls", lastSync?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) }).bind("pr", req.pending_rows).bind("path", path).execute()
        val row = h.createQuery("SELECT user_id, bytes, sha256, blob_path FROM app.support_upload WHERE upload_uuid = :u").bind("u", id)
            .map { rs, _ -> arrayOf(rs.getLong(1), rs.getLong(2), rs.getBytes(3), rs.getString(4)) }.one()
        // Another user's uuid or a different file under the same uuid is a conflict, never a second blob path.
        if (row[0] != p.userId || row[1] != req.bytes || !(row[2] as ByteArray).contentEquals(sha)) throw ApiProblem(ProblemCode.ERR_CONFLICT, "upload_uuid already stores a different file")
        (row[3] as String) to (inserted > 0)
    }
    val expires = now.plusSeconds(SAS_TTL_S)
    return SupportUploadOut(id.toString(), d.blob.writeSas(stored.first, req.bytes, expires), stored.first, expires.wire())
}
