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

    @Test fun thePreloadStoresOncePerAssetAndTheAdminPictureThenWins() = runBlocking {
        val cache = ImageCache(tmp.newFolder("img"), OkHttpClient(), { false }) // a metered network: thumbnails still come
        var asset = "a7"
        val p = SkuPackshots(cache, devAssets(), lookup = { id -> if (id == 7L) SkuPackshots.SkuInfo("MaxR-10S", SkuPackshots.SkuImageRef(asset)) else null })
        val dev = p.bitmap("7")!! // nothing cached yet: the dev Regular art stands in, nothing is downloaded on the row's path
        assertEquals(webpPixel("maxim-regular"), dev.getPixel(73, 60))

        var downloads = 0
        val download: suspend (String) -> ByteArray? = { downloads++; webp("maxim-bolt") }
        assertEquals(1, p.preload(listOf(7L to "a7"), download))
        val admin = p.bitmap("7")!!
        assertTrue(admin.getPixel(73, 60) != webpPixel("maxim-regular")) // the preload dropped the stand-in: the Bolt picture
        assertEquals("cached under the asset id: never downloaded again", 0, p.preload(listOf(7L to "a7"), download))
        assertEquals(1, downloads)

        asset = "a8" // a new upload is a new asset: the stand-in until the next preload
        p.invalidate()
        assertEquals(webpPixel("maxim-regular"), p.bitmap("7")!!.getPixel(73, 60))
        assertEquals(1, p.preload(listOf(7L to "a8"), download))
        assertEquals(2, downloads)
    }

    @Test fun aRefusedFailingOrNonImageDownloadIsSkippedNeverThrown() = runBlocking {
        val cache = ImageCache(tmp.newFolder("img"), OkHttpClient(), { false })
        val p = SkuPackshots(cache, devAssets = null)
        val bodies = mapOf("x1" to null, "x3" to "<html>captive portal</html>".toByteArray(), "x4" to ByteArray(300 * 1024 + 1) { 0xFF.toByte() })
        assertEquals(0, p.preload(listOf(1L to "x1", 2L to "x2", 3L to "x3", 4L to "x4")) { id -> if (id == "x2") error("offline") else bodies[id] })
        assertEquals(0L, cache.sizeBytes())
        assertEquals(0, SkuPackshots(images = null, devAssets = null).preload(listOf(1L to "x1")) { webp("maxim-bolt") })
    }

    @Test fun aMissingOrBrokenPictureIsNullNeverAnError() = runBlocking {
        val broken = File(tmp.newFolder("img"), "x").also { it.writeText("not an image") }
        val cache = ImageCache(broken.parentFile!!, OkHttpClient(), { false })
        val p = SkuPackshots(cache, devAssets = null, lookup = { SkuPackshots.SkuInfo("MaxR-10S", SkuPackshots.SkuImageRef("a")) })
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
        assertEquals("sku-image:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", SkuPackshots.cacheKey("AAAAAAAA-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
        assertEquals(SkuPackshots.cacheKey("a7"), SkuPackshots.cacheKey("a7"))
        assertTrue(SkuPackshots.cacheKey("a7") != SkuPackshots.cacheKey("a8"))
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
        android.graphics.BitmapFactory.decodeByteArray(webp(art), 0, webp(art).size, android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }).getPixel(73, 60) // decoded as the provider does
}
