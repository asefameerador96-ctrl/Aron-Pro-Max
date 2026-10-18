package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import io.mockk.mockk
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * GET /v1/sku-images/{asset_id} (contract getSkuImage, PARITY-SPEC s2.6): the API streams the bytes of a pack picture
 * that a SKU binds now, with the asset id as its ETag; an unknown or malformed id, another purpose's asset, an unbound
 * picture, an over-cap asset or a missing blob is a bare 404; storage down is 503. No URL is ever produced.
 */
class SkuImageReadTest {
    private val now = Instant.parse("2027-01-03T04:00:00Z")
    private val bound = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val unbound = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val gift = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val big = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    private val gone = "ffffffff-ffff-4fff-8fff-ffffffffffff"
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private val reads = mutableListOf<Pair<String, Long>>()
    private val blobs = BlobBytesReader { path, max -> reads += path to max; if (path.endsWith("$gone.png")) null else png }

    private fun fixture(): FreshDb {
        val fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            for ((id, purpose, bytes) in listOf(Triple(bound, "sku_image", 1000), Triple(unbound, "sku_image", 1000), Triple(gift, "gift_image", 1000), Triple(big, "sku_image", 400_000), Triple(gone, "sku_image", 1000))) {
                h.execute(
                    "INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES (CAST(? AS uuid), ?, 'image/png', ?, decode(repeat('ab', 32), 'hex'), ?)",
                    id, purpose, bytes, "assets/$purpose/$id.png",
                )
            }
            h.execute("UPDATE app.sku SET image_asset_id = CAST(? AS uuid) WHERE code = 'MaxR-10S'", bound)
            h.execute("UPDATE app.sku SET image_asset_id = CAST(? AS uuid) WHERE code = 'MaxR-20S'", big)
            h.execute("UPDATE app.sku SET image_asset_id = CAST(? AS uuid) WHERE code = 'MaxB-10S'", gone)
        }
        return fresh
    }

    private fun read(f: FreshDb, reader: BlobBytesReader = blobs) = SkuImageRead(MediaDeps(f.db, mockk(relaxed = true), mockk(relaxed = true), AronClock { now }, images = reader))

    @Test fun aBoundPackPictureIsStreamedWithItsAssetIdAsETag() {
        fixture().use { f ->
            val img = read(f).image(bound)
            assertContentEquals(png, img.bytes)
            assertEquals("image/png", img.mime)
            assertEquals("\"$bound\"", img.etag)
            assertEquals(listOf("assets/sku_image/$bound.png" to 300L * 1024L), reads, "read once, capped at 300 KB")
            assertEquals("\"$bound\"", read(f).image(bound.uppercase()).etag, "the id is case-insensitive, the ETag canonical")
        }
    }

    @Test fun onlyABoundSkuImageWithinTheCapIsServed() {
        fixture().use { f ->
            val r = read(f)
            fun code(id: String?) = assertFailsWith<ApiProblem> { r.image(id) }.code.name
            assertEquals("ERR_NOT_FOUND", code(unbound), "uploaded but no SKU binds it")
            assertEquals("ERR_NOT_FOUND", code(gift), "another purpose")
            assertEquals("ERR_NOT_FOUND", code(big), "over the 300 KB cap")
            assertEquals("ERR_NOT_FOUND", code("dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
            assertEquals("ERR_NOT_FOUND", code("not-a-uuid"), "a malformed id is a bare 404 too")
            assertEquals("ERR_NOT_FOUND", code(null))
            assertEquals(emptyList(), reads, "no blob is read for a refused id")
            assertEquals("ERR_NOT_FOUND", code(gone), "the row exists but the blob is gone")
            assertEquals("ERR_SERVICE_UNAVAILABLE", assertFailsWith<ApiProblem> { read(f, BlobBytesReader { _, _ -> error("storage down") }).image(bound) }.code.name)
            assertEquals("ERR_SERVICE_UNAVAILABLE", assertFailsWith<ApiProblem> { read(f, UnconfiguredBlobBytesReader).image(bound) }.code.name)
        }
    }
}
