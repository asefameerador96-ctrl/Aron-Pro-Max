package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.RoutingCall
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID

/** Server-side write of one photo blob (the multipart fallback); overwrites, the caller holds the uuid lock. */
fun interface PhotoBlobWriter {
    fun put(blobPath: String, bytes: ByteArray)
}

/** Where blob storage is not configured (local runs, CI image smoke): 503, as the SAS endpoint. */
val UnconfiguredPhotoBlobWriter = PhotoBlobWriter { _, _ ->
    throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "blob storage is not configured in this environment")
}

/** Contract `MediaUploaded`. */
@Serializable
data class MediaUploaded(val media_uuid: String, val replayed: Boolean)

/** The parsed multipart form (contract `MediaMultipartUpload`). */
internal data class MediaUploadForm(val mediaUuid: String?, val purpose: String?, val sha256: String?, val file: ByteArray?, val fileType: ContentType?)

/**
 * POST /v1/media/upload (F-API-007, contract uploadMediaMultipart; ledger app.media_upload, V0061): one small
 * feedback or support JPEG through the API when the SAS path is not usable. Idempotent by (`media_uuid`, `purpose`):
 * the first upload writes `photos/{business_date}/{device_uuid}/{media_uuid}.jpg` (the device from the token; a web
 * upload uses `web-{user_id}`) and inserts the ledger row; a repeat with the same bytes answers `replayed: true` and
 * writes nothing. BC-81: one uuid = one purpose (the blob path has no purpose in it), so the same uuid under the
 * other purpose, or with other bytes, is a 400. The business date is the first upload's Dhaka date, kept for every
 * retry by the ledger row. The blob is written under a per-uuid advisory lock before the row is inserted in the
 * same transaction: a crash between the two leaves an unacknowledged blob that the retry overwrites.
 */
internal class MediaUpload(private val d: MediaDeps) {
    fun store(p: AronPrincipal, f: MediaUploadForm): MediaUploaded {
        val uuid = f.mediaUuid?.takeIf { UUID_RE.matches(it) } ?: throw invalid("/media_uuid", "a lowercase uuid")
        val purpose = f.purpose?.takeIf { it in PURPOSES } ?: throw invalid("/purpose", "feedback or support")
        val sha = f.sha256?.takeIf { SHA_RE.matches(it) } ?: throw invalid("/sha256", "64 lowercase hex digits")
        val bytes = f.file ?: throw invalid("/file", "the JPEG file is required")
        if (bytes.size > MAX_BYTES) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "at most $MAX_BYTES bytes")
        if (f.fileType != null && !f.fileType.match(ContentType.Image.JPEG)) throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE, "image/jpeg only")
        if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() || bytes[2] != 0xFF.toByte()) {
            throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE, "the file is not a JPEG")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (hex(digest) != sha) throw invalid("/sha256", "does not match the file")
        val canonical = UUID.fromString(uuid)

        return d.db.jdbi.inTransaction<MediaUploaded, Exception> { h ->
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", "media_upload:$canonical")
            val prior = h.createQuery("SELECT purpose, encode(sha256, 'hex') FROM app.media_upload WHERE media_uuid = :u")
                .bind("u", canonical).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
            prior.firstOrNull()?.let { (was, wasSha) ->
                if (was != purpose) throw invalid("/media_uuid", "already uploaded for another purpose")
                if (wasSha != sha) throw invalid("/sha256", "this media uuid was uploaded with other bytes")
                return@inTransaction MediaUploaded(uuid, replayed = true)
            }
            val bd = d.clock.now().atZone(DHAKA).toLocalDate()
            val owner = p.deviceUuid?.let { UUID.fromString(it).toString() } ?: "web-${p.userId}"
            val path = "photos/$bd/$owner/$canonical.jpg"
            // Storage down or slow: retryable 503 (the transaction rolls back, no ledger row), never a 500.
            try { d.blobs.put(path, bytes) } catch (e: ApiProblem) { throw e } catch (e: Exception) {
                throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "photo storage unavailable (${e.javaClass.simpleName})")
            }
            h.createUpdate(
                """
                INSERT INTO app.media_upload (media_uuid, purpose, sha256, bytes, user_id, device_id, blob_path, business_date, uploaded_at)
                VALUES (:u, :p, :sha, :n, :me, (SELECT id FROM app.device WHERE device_uuid = CAST(:dev AS uuid)), :path, :bd, :at)
                ON CONFLICT (media_uuid, purpose) DO NOTHING
                """.trimIndent(),
            ).bind("u", canonical).bind("p", purpose).bind("sha", digest).bind("n", bytes.size).bind("me", p.userId)
                .bind("dev", p.deviceUuid).bind("path", path).bind("bd", bd).bind("at", java.sql.Timestamp.from(d.clock.now())).execute()
            MediaUploaded(uuid, replayed = false)
        }
    }

    companion object {
        const val MAX_BYTES = 307_200
        /** The file plus one byte is read, so an oversize file is seen (413 in [store]) without buffering more of it. */
        private const val PART_LIMIT = MAX_BYTES + 1L
        /** media_uuid, purpose, sha256, file, plus slack for a client that adds a field. */
        private const val MAX_PARTS = 8
        /** The whole body: the file plus room for the three fields and the part headers. */
        private const val BODY_LIMIT = MAX_BYTES + 16_384L
        private val PURPOSES = setOf("feedback", "support")
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val SHA_RE = Regex("^[0-9a-f]{64}$")
        private val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun invalid(pointer: String, msg: String) = ApiProblem(ProblemCode.ERR_VALIDATION, msg, errors = listOf(FieldError(pointer, "invalid_value")))

        /** Reads the multipart body; a file part longer than [MAX_BYTES] is cut at one byte over (then 413). */
        internal suspend fun parse(call: RoutingCall): MediaUploadForm {
            if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE, "multipart/form-data only")
            }
            var uuid: String? = null; var purpose: String? = null; var sha: String? = null
            var file: ByteArray? = null; var type: ContentType? = null
            // A part past Ktor's formFieldLimit fails the whole call, so a body that is too long is refused from its
            // Content-Length first (413); the limit then only guards a chunked body.
            val declared = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > BODY_LIMIT) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "at most $MAX_BYTES bytes")
            // Ktor's parser runs as a child of the call, so its own failures (a part past formFieldLimit, a broken
            // boundary) cannot be caught here; Content-Length above and the part count below are the bounds we own.
            var parts = 0
            call.receiveMultipart(formFieldLimit = BODY_LIMIT).forEachPart { part ->
                try {
                    if (++parts > MAX_PARTS) throw invalid("/", "at most $MAX_PARTS parts")
                    when (part) {
                        is PartData.FormItem -> when (part.name) {
                            "media_uuid" -> uuid = part.value
                            "purpose" -> purpose = part.value
                            "sha256" -> sha = part.value
                        }
                        is PartData.FileItem -> if (part.name == "file") {
                            type = part.contentType
                            file = part.provider().readRemaining(PART_LIMIT).readByteArray()
                        }
                        else -> {}
                    }
                } finally {
                    part.dispose()
                }
            }
            return MediaUploadForm(uuid, purpose, sha, file, type)
        }
    }
}
