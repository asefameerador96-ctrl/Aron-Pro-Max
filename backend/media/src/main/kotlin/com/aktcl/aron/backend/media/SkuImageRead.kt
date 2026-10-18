package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import java.util.UUID

/**
 * GET /v1/sku-images/{asset_id}/read-url (contract getSkuImageReadUrl, PARITY-SPEC s2.6): the pack picture of a SKU for the phones. The
 * bundle carries the stable `image_asset_id`, never a signed URL (the bundle fingerprint must not move per build); the
 * phone asks here for a 5-minute read URL and downloads at once, then keeps the bytes in its bounded cache
 * under the asset id. Only an asset of purpose `sku_image` that a SKU binds right now is served, to any signed-in user
 * (pack art is the same for every role and scope); anything else is 404. The URL is a bearer secret: never logged.
 */
internal class SkuImageRead(private val d: MediaDeps) {
    fun readUrl(rawId: String?): MediaReadUrl {
        val id = rawId?.takeIf { UUID_RE.matches(it) }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "asset_id is not a UUID", errors = listOf(FieldError("path.asset_id", "invalid_value")))
        val path = d.db.readJdbi.withHandle<String?, Exception> { h ->
            h.createQuery(
                "SELECT a.blob_path FROM app.admin_asset a WHERE a.asset_id = :id AND a.purpose = 'sku_image' " +
                    "AND EXISTS (SELECT 1 FROM app.sku s WHERE s.image_asset_id = a.asset_id)",
            ).bind("id", UUID.fromString(id)).mapTo(String::class.java).findOne().orElse(null)
        } ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such pack picture")
        val expires = d.clock.now().plusSeconds(VALID_S)
        val url = try {
            d.reader.readSas(path, expires)
        } catch (e: ApiProblem) {
            throw e
        } catch (e: Exception) {
            throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "image storage unavailable (${e.javaClass.simpleName})")
        }
        return MediaReadUrl(url, expires.wire())
    }

    companion object {
        /** Long enough for the phone to download at once; the bytes are cached, never the URL. */
        const val VALID_S = 300L
        private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
