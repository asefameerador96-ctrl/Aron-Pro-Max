package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Contract v1.3 item 5: `image_asset_id` on SkuWrite/SkuPatch (purpose sku_image, 300 KB) and `image_url` on Sku. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkuImageTest {
    private lateinit var t: AdminHarness

    @BeforeAll fun setUp() { t = AdminHarness() }
    @AfterAll fun tearDown() = t.close()

    private fun asset(purpose: String, bytes: Long = 100_000): String {
        val id = UUID.randomUUID().toString()
        t.env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path, uploaded_by) VALUES (CAST(:i AS uuid), :p, 'image/png', :b, :s, :path, :by)")
                .bind("i", id).bind("p", purpose).bind("b", bytes).bind("s", ByteArray(32) { 7 }).bind("path", "assets/$purpose/$id.png").bind("by", t.id("admin1001")).execute()
        }
        return id
    }

    private fun body(code: String, variant: Long, extra: String = "") =
        """{"code":"$code","variant_id":$variant,"category_code":"cigarette","name":"$code product","short_name":"$code","base_unit":"stick","base_per_pack":10,"entry_unit_default":"pack","report_unit":"pack","report_factor":"1","sort":3$extra}"""

    @Test
    fun imageIsValidatedBoundReturnedAndRemoved() = t.app {
        val variant = t.scalar("SELECT id FROM app.product_node WHERE level = 'variant' ORDER BY id LIMIT 1").toLong()
        val img = asset("sku_image")
        // Create without and with an image.
        val plain = send(HttpMethod.Post, "/v1/admin/skus", t.admin, body("IMG-0", variant))
        assertEquals(HttpStatusCode.Created, plain.status, plain.bodyAsText()); assertEquals(JsonNull, plain.obj()["image_url"])
        val c = send(HttpMethod.Post, "/v1/admin/skus", t.admin, body("IMG-1", variant, ""","image_asset_id":"$img""""))
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText())
        assertEquals("https://blob.test/assets/sku_image/$img.png", c.obj()["image_url"]!!.jsonPrimitive.content)
        // Wrong purpose, unknown asset, over the cap, bad uuid.
        suspend fun bad(code: String, extra: String, status: HttpStatusCode = HttpStatusCode.BadRequest) =
            assertEquals(status, send(HttpMethod.Post, "/v1/admin/skus", t.admin, body(code, variant, extra)).status, extra)
        bad("IMG-2", ""","image_asset_id":"${asset("gift_image")}"""")
        bad("IMG-3", ""","image_asset_id":"${UUID.randomUUID()}"""")
        bad("IMG-4", ""","image_asset_id":"${asset("sku_image", 300L * 1024 + 1)}"""", HttpStatusCode.PayloadTooLarge)
        bad("IMG-5", ""","image_asset_id":"not-a-uuid"""")
        // Patch: set, same value (no new version), another, remove with null, omitted keeps.
        val id = plain.obj()["id"]!!.jsonPrimitive.content
        val p1 = send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"image_asset_id":"$img"}""", 1)
        assertEquals(HttpStatusCode.OK, p1.status, p1.bodyAsText()); assertEquals("2", p1.obj()["version"]!!.jsonPrimitive.content)
        assertEquals("https://blob.test/assets/sku_image/$img.png", p1.obj()["image_url"]!!.jsonPrimitive.content)
        val same = send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"image_asset_id":"$img"}""", 2)
        assertEquals("2", same.obj()["version"]!!.jsonPrimitive.content, "the same image changes nothing")
        val keep = send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"sort":9}""", 2)
        assertEquals("https://blob.test/assets/sku_image/$img.png", keep.obj()["image_url"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"image_asset_id":"${asset("tutorial_video")}"}""", 3).status)
        val gone = send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"image_asset_id":null}""", 3)
        assertEquals(HttpStatusCode.OK, gone.status, gone.bodyAsText()); assertEquals(JsonNull, gone.obj()["image_url"])
        // The list carries the url; the audit row records the change.
        val listed = send(HttpMethod.Get, "/v1/admin/skus?limit=500", t.admin).obj()["items"].toString()
        assert(listed.contains("https://blob.test/assets/sku_image/$img.png"))
        assertEquals(1, t.count("SELECT count(*) FROM app.audit_log WHERE entity = 'sku' AND entity_id = '$id' AND action = 'update' AND (after->>'image_url') IS NULL AND (before->>'image_url') IS NOT NULL"))
    }
}
