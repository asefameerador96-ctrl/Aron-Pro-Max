package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import io.mockk.mockk
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * GET /v1/sku-images/{asset_id}/read-url (contract getSkuImageReadUrl, PARITY-SPEC s2.6): a 5-minute read URL of a pack picture that a
 * SKU binds now; an unknown id, another purpose's asset or an unbound picture is 404; a malformed id 400; storage down 503.
 */
class SkuImageReadTest {
    private val now = Instant.parse("2027-01-03T04:00:00Z")
    private val issued = mutableListOf<Pair<String, Instant>>()
    private val reader = PhotoReadIssuer { path, until -> issued += path to until; "https://blob.invalid/media/$path?sig=r" }

    private val bound = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val unbound = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val gift = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    private fun fixture(): FreshDb {
        val fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            for ((id, purpose) in listOf(bound to "sku_image", unbound to "sku_image", gift to "gift_image")) {
                h.execute(
                    "INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES (CAST(? AS uuid), ?, 'image/jpeg', 1000, decode(repeat('ab', 32), 'hex'), ?)",
                    id, purpose, "assets/$purpose/$id.jpg",
                )
            }
            h.execute("UPDATE app.sku SET image_asset_id = CAST(? AS uuid) WHERE code = 'MaxR-10S'", bound)
        }
        return fresh
    }

    @Test fun aBoundPackPictureGetsAFiveMinuteReadUrl() {
        fixture().use { f ->
            val r = SkuImageRead(MediaDeps(f.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, reader = reader))
            val u = r.readUrl(bound)
            assertEquals("https://blob.invalid/media/assets/sku_image/$bound.jpg?sig=r", u.url)
            assertEquals("2027-01-03T04:05:00.000Z", u.expires_at)
            assertEquals("assets/sku_image/$bound.jpg" to now.plusSeconds(300), issued.single())
            assertEquals("https://blob.invalid/media/assets/sku_image/$bound.jpg?sig=r", r.readUrl(bound.uppercase()).url, "the id is case-insensitive")
        }
    }

    @Test fun onlyABoundSkuImageIsServed() {
        fixture().use { f ->
            val r = SkuImageRead(MediaDeps(f.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, reader = reader))
            fun code(id: String?) = assertFailsWith<ApiProblem> { r.readUrl(id) }.code.name
            assertEquals("ERR_NOT_FOUND", code(unbound), "uploaded but no SKU binds it")
            assertEquals("ERR_NOT_FOUND", code(gift), "another purpose")
            assertEquals("ERR_NOT_FOUND", code("dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
            assertEquals("ERR_VALIDATION", code("not-a-uuid"))
            assertEquals("ERR_VALIDATION", code(null))
            assertEquals(emptyList(), issued, "no URL is signed for a refused id")
            val down = SkuImageRead(MediaDeps(f.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, reader = PhotoReadIssuer { _, _ -> error("storage down") }))
            assertEquals("ERR_SERVICE_UNAVAILABLE", assertFailsWith<ApiProblem> { down.readUrl(bound) }.code.name)
        }
    }
}
