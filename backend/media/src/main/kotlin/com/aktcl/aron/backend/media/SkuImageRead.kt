package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import java.util.UUID

/** Reads one small blob with the API's own identity; null when it is missing or bigger than [maxBytes]. Never a URL. */
fun interface BlobBytesReader {
    fun read(blobPath: String, maxBytes: Long): ByteArray?
}

/** Where blob storage is not configured (local runs, CI image smoke): 503, as the other blob endpoints. */
val UnconfiguredBlobBytesReader = BlobBytesReader { _, _ ->
    throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "blob storage is not configured in this environment")
}

/**
 * GET /v1/sku-images/{asset_id} (contract getSkuImage, PARITY-SPEC s2.6; lead ruling 2026-10-18): the bytes of a SKU's
 * pack picture, streamed by the API so no signed URL ever leaves it. The bundle carries the stable `image_asset_id`; an
 * asset never changes (a new file is a new asset), so the answer is cacheable for a year and its ETag is the asset id.
 * Served only when the asset exists, is of purpose `sku_image`, is at most [MAX_BYTES] and a SKU binds it; anything else
 * (a malformed id included) is a bare 404, so the endpoint cannot be used to probe other assets. Any signed-in caller:
 * pack art is the same for every role and reach.
 */
internal class SkuImageRead(private val d: MediaDeps) {
    /** What the database says about a servable picture; the blob is not read yet (a 304 never touches storage). */
    class Meta(val path: String, val mime: String, val etag: String)

    fun meta(rawId: String?): Meta {
        val id = rawId?.takeIf { UUID_RE.matches(it) }?.lowercase() ?: throw notFound()
        return d.db.readJdbi.withHandle<Meta?, Exception> { h ->
            h.createQuery(
                "SELECT a.blob_path, a.mime FROM app.admin_asset a WHERE a.asset_id = :id AND a.purpose = 'sku_image' AND a.bytes <= :max " +
                    "AND a.mime IN ('image/jpeg', 'image/png') AND EXISTS (SELECT 1 FROM app.sku s WHERE s.image_asset_id = a.asset_id)",
            ).bind("id", UUID.fromString(id)).bind("max", MAX_BYTES)
                .map { rs, _ -> Meta(rs.getString(1), rs.getString(2), "\"" + id + "\"") }.findOne().orElse(null)
        } ?: throw notFound()
    }

    /** The bytes of [m] (at most [MAX_BYTES]); a missing blob is the same bare 404, storage down 503. */
    fun bytes(m: Meta): ByteArray = try {
        d.images.read(m.path, MAX_BYTES)
    } catch (e: ApiProblem) {
        throw e
    } catch (e: Exception) {
        throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "image storage unavailable (${e.javaClass.simpleName})")
    } ?: throw notFound()

    private fun notFound() = ApiProblem(ProblemCode.ERR_NOT_FOUND, "not found")

    companion object {
        /** One compressed thumbnail per SKU (docs/15, the admin upload cap). */
        const val MAX_BYTES = 300L * 1024L
        const val CACHE_CONTROL = "private, max-age=31536000, immutable"

        /** RFC 9110 If-None-Match (weak comparison): `*`, or any listed tag equal to [etag] with or without `W/`. */
        fun notModified(ifNoneMatch: String?, etag: String): Boolean = ifNoneMatch?.split(',')?.map { it.trim().removePrefix("W/") }
            ?.any { it == "*" || it == etag } == true
        private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
