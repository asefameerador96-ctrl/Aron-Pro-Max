package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Independent checker for contract v1.3 item 5 (SKU pack image). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkuImageCheckerTest {
    private lateinit var t: AdminHarness

    @BeforeAll fun setUp() { t = AdminHarness() }
    @AfterAll fun tearDown() = t.close()

    /** Same routes as AdminHarness.app but with the given blob store (the harness fixes FakeBlob). */
    private fun appWith(blob: BlobSasIssuer, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            val base = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(5000)))
            installAronPlatform(PlatformContext(AronClock.SYSTEM, base, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, base)
            routing { route("/v1") { adminProductsRoutes(AdminProductsDeps(t.env.fresh.db, guard, blob = blob)) } }
        }
        block()
    }

    private fun asset(purpose: String = "sku_image", bytes: Long = 1000): String {
        val id = UUID.randomUUID().toString()
        t.env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path, uploaded_by) VALUES (CAST(:i AS uuid), :p, 'image/png', :b, :s, :path, :by)")
                .bind("i", id).bind("p", purpose).bind("b", bytes).bind("s", ByteArray(32) { 9 }).bind("path", "assets/$purpose/$id.png").bind("by", t.id("admin1001")).execute()
        }
        return id
    }

    private fun body(code: String, variant: Long, extra: String = "") =
        """{"code":"$code","variant_id":$variant,"category_code":"cigarette","name":"$code product","short_name":"$code","base_unit":"stick","base_per_pack":10,"entry_unit_default":"pack","report_unit":"pack","report_factor":"1","sort":3$extra}"""

    private fun variant() = t.scalar("SELECT id FROM app.product_node WHERE level = 'variant' ORDER BY id LIMIT 1").toLong()

    /** The real issuer returns a signed read URL (AzureBlobSasIssuer.readUrl = blob url + ?sv=..&sig=..). */
    private val signing = object : BlobSasIssuer {
        override fun writeSas(blobPath: String, maxBytes: Long, expiresAt: Instant) = "https://blob.test/$blobPath?sp=w&sig=W"
        override fun readUrl(blobPath: String) = "https://blob.test/$blobPath?sp=r&sig=SECRETSIG${System.nanoTime()}"
    }

    @Test
    fun auditRowsMustNotStoreSignedReadUrls() = appWith(signing) {
        val img = asset()
        val c = send(HttpMethod.Post, "/v1/admin/skus", t.admin, body("CHK-A", variant(), ""","image_asset_id":"$img""""))
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText())
        val id = c.obj()["id"]!!.jsonPrimitive.content
        send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"sort":9}""", 1)
        val leaked = t.count("SELECT count(*) FROM app.audit_log WHERE entity = 'sku' AND entity_id = '$id' AND (after::text LIKE '%SECRETSIG%' OR before::text LIKE '%SECRETSIG%')")
        assertEquals(0, leaked, "audit_log keeps a time-limited SAS signature (a secret) and a field that changes on every read")
    }

    @Test
    fun auditRecordsWhichImageChangedEvenWhenStoreIsUnconfigured() = appWith(UnconfiguredBlobSasIssuer) {
        val a = asset(); val b = asset()
        val c = send(HttpMethod.Post, "/v1/admin/skus", t.admin, body("CHK-B", variant()))
        val id = c.obj()["id"]!!.jsonPrimitive.content
        val p1 = send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"image_asset_id":"$a"}""", 1)
        assertEquals(HttpStatusCode.OK, p1.status, p1.bodyAsText()); assertEquals(JsonNull, p1.obj()["image_url"], "no store, no url, no failure")
        val p2 = send(HttpMethod.Patch, "/v1/admin/skus/$id", t.admin, """{"image_asset_id":"$b"}""", 2)
        assertEquals("3", p2.obj()["version"]!!.jsonPrimitive.content)
        val rows = t.count("SELECT count(*) FROM app.audit_log WHERE entity = 'sku' AND entity_id = '$id' AND action = 'update' AND (before::jsonb - 'version' - 'updated_at') IS NOT DISTINCT FROM (after::jsonb - 'version' - 'updated_at')")
        assertEquals(0, rows, "version bumped by an image change but the audit before and after are identical (image_asset_id is not in SkuOut)")
    }

    @Test
    fun listingAndValidationHoldWithoutBlobStore() = appWith(UnconfiguredBlobSasIssuer) {
        val img = asset()
        val c = send(HttpMethod.Post, "/v1/admin/skus", t.admin, body("CHK-C", variant(), ""","image_asset_id":"$img""""))
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText())
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/v1/admin/skus?limit=500", t.admin).status)
        for (bad in listOf(img.uppercase(), "00000000-0000-1000-8000-000000000000", "", "${img.take(35)}\u0000")) {
            val r = send(HttpMethod.Post, "/v1/admin/skus", t.admin, body("CHK-D", variant(), ""","image_asset_id":"$bad""""))
            assertEquals(HttpStatusCode.BadRequest, r.status, bad)
        }
    }
}
