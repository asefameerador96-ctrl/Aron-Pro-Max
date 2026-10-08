package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Audience
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** backend:media (docs/24 s4.11, s6.2): photo upload grants. */
object MediaModule {
    const val NAME: String = "media"
}

/** Write-only, one-blob upload URL (Azure user-delegation SAS in production; 503 where blob storage is not configured). */
fun interface PhotoSasIssuer {
    fun writeSas(blobPath: String, maxBytes: Long, expiresAt: Instant): String
}

@Serializable
data class MediaSasItem(val media_uuid: String, val purpose: String, val sha256: String, val bytes: Long, val business_date: String, val mime: String)

@Serializable
data class MediaSasRequest(val items: List<MediaSasItem>)

@Serializable
data class MediaUploadHeaders(@SerialName("x-ms-blob-type") val blobType: String = "BlockBlob", @SerialName("Content-Type") val contentType: String = "image/jpeg")

@Serializable
data class MediaSasTarget(
    val media_uuid: String, val method: String, val upload_url: String, val blob_path: String, val required_headers: MediaUploadHeaders,
    val expires_at: String, val already_uploaded: Boolean,
)

@Serializable
data class MediaSasResponse(val items: List<MediaSasTarget>)

class MediaDeps(
    val db: Database, val sas: PhotoSasIssuer, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM,
    val blobs: PhotoBlobWriter = UnconfiguredPhotoBlobWriter,
)

/**
 * POST /v1/media/sas (F-API-057, contract createMediaUploadUrls): up to 10 write-only URLs, one blob each, pinned to
 * `photos/{business_date}/{device_uuid}/{media_uuid}.jpg` (the device from the token, never the body), valid 15
 * minutes. The upload grant may call it (a phone past its access token still empties its photo queue). Idempotent:
 * the same media uuid always gets the same path; `already_uploaded` is true when the server already holds that blob
 * with the same sha256 (skip the PUT, send media_meta).
 */
fun Route.mediaRoutes(d: MediaDeps) {
    authenticated(d.guard, { audiences = setOf(Audience.API, Audience.UPLOAD); expiredGraceS = 60; checkScopeVersion = false }) {
        post("/media/sas") {
            val req = call.receiveStrict(MediaSasRequest.serializer())
            call.respond(withContext(Dispatchers.IO) { MediaSas(d).grant(call.principal, req) })
        }
    }
    // F-API-007 multipart fallback: an access token only (feedback and support are online actions, not the queue).
    authenticated(d.guard) {
        post("/media/upload") {
            val form = MediaUpload.parse(call)
            call.respond(withContext(Dispatchers.IO) { MediaUpload(d).store(call.principal, form) })
        }
    }
}

internal class MediaSas(private val d: MediaDeps) {
    fun grant(p: AronPrincipal, req: MediaSasRequest): MediaSasResponse {
        val device = p.deviceUuid ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "a phone token is required")
        if (req.items.isEmpty() || req.items.size > MAX_ITEMS) throw invalid("/items", "1 to $MAX_ITEMS items")
        req.items.forEachIndexed { i, it ->
            if (!UUID_RE.matches(it.media_uuid)) throw invalid("/items/$i/media_uuid", "a lowercase uuid")
            if (it.purpose !in PURPOSES) throw invalid("/items/$i/purpose", "unknown purpose")
            if (!SHA_RE.matches(it.sha256)) throw invalid("/items/$i/sha256", "64 lowercase hex digits")
            if (it.bytes !in 1..MAX_BYTES) throw invalid("/items/$i/bytes", "1 to $MAX_BYTES bytes")
            if (!DATE_RE.matches(it.business_date) || runCatching { LocalDate.parse(it.business_date) }.isFailure) throw invalid("/items/$i/business_date", "YYYY-MM-DD")
            if (it.mime != "image/jpeg") throw invalid("/items/$i/mime", "image/jpeg only")
        }
        if (req.items.map { it.media_uuid }.toSet().size != req.items.size) throw invalid("/items", "a media uuid appears twice")
        val stored = d.db.jdbi.withHandle<Map<String, String>, Exception> { h ->
            h.createQuery("SELECT client_uuid::text, encode(blob_sha256, 'hex') FROM app.media WHERE client_uuid = ANY(:u) AND user_id = :me AND status = 'stored' AND blob_sha256 IS NOT NULL")
                .bindArray("u", UUID::class.java, req.items.map { UUID.fromString(it.media_uuid) }).bind("me", p.userId)
                .map { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
        }
        val expires = d.clock.now().plusSeconds(VALID_S)
        return MediaSasResponse(req.items.map {
            val path = "photos/${it.business_date}/$device/${it.media_uuid}.jpg"
            MediaSasTarget(it.media_uuid, "PUT", d.sas.writeSas(path, it.bytes, expires), path, MediaUploadHeaders(), expires.wire(), stored[it.media_uuid] == it.sha256)
        })
    }

    private companion object {
        const val MAX_ITEMS = 10
        const val MAX_BYTES = 307_200L
        const val VALID_S = 15 * 60L
        val PURPOSES = setOf("force_sale", "outlet_capture", "outlet_verification", "survey", "feedback", "support", "gift_photo")
        val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val SHA_RE = Regex("^[0-9a-f]{64}$")
        val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        fun invalid(pointer: String, msg: String) = ApiProblem(ProblemCode.ERR_VALIDATION, msg, errors = listOf(FieldError(pointer, "invalid_value")))
    }
}
