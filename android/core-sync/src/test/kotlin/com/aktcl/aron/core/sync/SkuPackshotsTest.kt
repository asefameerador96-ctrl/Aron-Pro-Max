package com.aktcl.aron.core.sync

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException

/** PARITY-SPEC s2.6: pack pictures from the thumbnail cache, then the dev-only art; never the network; null = silhouette. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkuPackshotsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @After fun tearDown() = server.close()

    /** The shipped dev art folder (android/dev-packshots), read the way the APK assets are. */
    private val devRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "android/dev-packshots") }.firstOrNull { it.isDirectory }
        ?: generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "dev-packshots") }.first { it.isDirectory }

    private fun devAssets(): AssetManager = mockk {
        every { open(any()) } answers {
            val f = File(devRoot, firstArg<String>())
            if (!f.isFile) throw FileNotFoundException(f.path)
            f.inputStream()
        }
    }

    private fun webp(art: String) = File(devRoot, "dev-packshots/$art.webp").readBytes()

    @Test fun theDevArtMapsTheSeedCodesAndLeavesTheRestToTheSilhouette() = runBlocking {
        val p = SkuPackshots(images = null, devAssets = devAssets())
        listOf("MaxR-10S", "MaxR-20S", "MaxB-10S", "MaxB-20S", "MaxWB-10S", "MaxWB-20S").forEach { code ->
            val b = p.bitmap(code)
            assertNotNull(code, b)
            assertEquals(146, b!!.width)
            assertEquals(237, b.height)
        }
        assertNull(p.bitmap("Aster")) // no art: the silhouette
        assertNull(p.bitmap("FB"))
        assertNotNull(p.bitmap("maxr-10s")) // codes match case-insensitively
    }

    @Test fun aProductionBuildHasNoDevArt() = runBlocking {
        assertNull(SkuPackshots(images = null, devAssets = null).bitmap("MaxR-10S"))
    }

    @Test fun aDecodedPictureIsServedFromMemoryOnTheFirstFrame() = runBlocking {
        val p = SkuPackshots(images = null, devAssets = devAssets())
        assertNull(p.cached("MaxB-10S"))
        val b = p.bitmap("MaxB-10S")
        assertSame(b, p.cached("MaxB-10S"))
        p.invalidate()
        assertNull(p.cached("MaxB-10S"))
    }

    @Test fun theAdminThumbnailWinsAndIsKeyedByAssetAndVersionNotByTheSignedUrl() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/webp").body(Buffer().write(webp("maxim-bolt"))).build())
        server.start()
        val cache = ImageCache(tmp.newFolder("img"), OkHttpClient(), { false })
        val first = server.url("/sku/7.webp?sig=one").toString()
        assertNotNull(cache.fetch(first, ImageCache.Kind.THUMBNAIL, cacheKey = SkuPackshots.cacheKey("a7", 3)))
        var ref = SkuPackshots.SkuImageRef(server.url("/sku/7.webp?sig=two").toString(), "a7", 3) // a new signature, the same picture
        val p = SkuPackshots(cache, devAssets(), lookup = { id -> if (id == 7L) SkuPackshots.SkuInfo("MaxR-10S", ref) else null })
        val b = p.bitmap("7")
        assertNotNull(b)
        assertEquals(1, server.requestCount) // read from disk: no second download, no network in the row
        assertTrue(b!!.getPixel(73, 60) != webpPixel("maxim-regular")) // the admin (Bolt) picture, not the dev Regular art

        ref = ref.copy(version = 4) // a new version not fetched yet: the dev art until the preload brings it
        p.invalidate()
        assertNotNull(p.bitmap("7"))
        assertEquals(1, server.requestCount)
    }

    @Test fun aMissingOrBrokenPictureIsNullNeverAnError() = runBlocking {
        val broken = File(tmp.newFolder("img"), "x").also { it.writeText("not an image") }
        val cache = ImageCache(broken.parentFile!!, OkHttpClient(), { false })
        val p = SkuPackshots(cache, devAssets = null, lookup = { SkuPackshots.SkuInfo("MaxR-10S", SkuPackshots.SkuImageRef("https://x.invalid/a.png", "a", 1)) })
        assertNull(p.bitmap("1"))
        val throwing = SkuPackshots(cache, devAssets(), lookup = { error("bundle not open") })
        assertNull(throwing.bitmap("1"))
    }

    @Test fun aServerIdIsResolvedInTheLocalCatalogue() = runBlocking {
        val p = SkuPackshots(images = null, devAssets = devAssets(), lookup = { id -> mapOf(11L to "MaxWB-20S", 12L to "Aster")[id]?.let { SkuPackshots.SkuInfo(it) } })
        assertNotNull(p.bitmap("11"))
        assertNull(p.bitmap("12")) // known SKU without art: the silhouette
        assertNull(p.bitmap("13")) // unknown id: never guessed as a code
    }

    @Test fun theCacheKeyIsStable() {
        assertEquals("sku-image:a7:v3", SkuPackshots.cacheKey("a7", 3))
        assertEquals(SkuPackshots.cacheKey("a7", 3), SkuPackshots.cacheKey("a7", 3))
        assertTrue(SkuPackshots.cacheKey("a7", 3) != SkuPackshots.cacheKey("a7", 4))
    }

    @Test fun bigUploadsAreDownsampledOnDecode() {
        assertEquals(1, SkuPackshots.sampleSize(237, 400))
        assertEquals(2, SkuPackshots.sampleSize(800, 400))
        assertEquals(4, SkuPackshots.sampleSize(1700, 400))
    }

    @Test fun theDevIndexIgnoresCommentsAndBadNames() {
        val m = SkuPackshots.parseDevIndex(listOf("# c", "MaxR-10S = maxim-regular # x", "bad=../etc", "=x", "noequals"))
        assertEquals(mapOf("maxr-10s" to "maxim-regular"), m)
    }

    private fun webpPixel(art: String): Int =
        android.graphics.BitmapFactory.decodeByteArray(webp(art), 0, webp(art).size).getPixel(73, 60)
}
