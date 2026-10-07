package com.aktcl.aron.core.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** F-SYS-029: a hard cap with least-recently-used eviction; AV only on Wi-Fi; reads never touch the network. */
class ImageCacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private var wifi = false
    private val mb = 1024 * 1024

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/portal") return MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>login</html>").build()
                val size = when {
                    path == "/big" -> 300 * 1024 + 1
                    path.startsWith("/av") -> 4 * mb
                    else -> 250 * 1024
                }
                val type = if (path.startsWith("/av")) "video/mp4" else "image/jpeg"
                return MockResponse.Builder().code(200).addHeader("Content-Type", type).body(Buffer().write(ByteArray(size))).build()
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private fun url(name: String) = server.url("/$name").toString()

    private fun cache(): ImageCache = runBlocking {
        ImageCache(tmp.newFolder("img"), OkHttpClient(), { wifi }).also { it.setCapMb(10) } // the registry minimum (db V0055: 10..70)
    }

    @Test fun fillingPastTheCapEvictsTheLeastRecentlyUsedFirst() = runBlocking {
        wifi = true
        val c = cache()
        assertNotNull(c.fetch(url("av-a"), ImageCache.Kind.AV)) // 4 MB each, cap 10 MB
        assertNotNull(c.fetch(url("av-b"), ImageCache.Kind.AV))
        assertNotNull(c.get(url("av-a"))) // a is used again: b is now the oldest
        assertNotNull(c.fetch(url("av-c"), ImageCache.Kind.AV))
        assertTrue(c.sizeBytes() <= 10L * mb)
        assertNull("the least recently used went first", c.get(url("av-b")))
        assertNotNull(c.get(url("av-a")))
        assertNotNull(c.get(url("av-c")))
    }

    /** Checker: a captive portal page is not an image; a rotated SAS query is the same image; a failure is not retried. */
    @Test fun aPortalPageIsRefusedAndTheQueryIsNotPartOfTheKey() = runBlocking {
        val c = cache()
        assertNull(c.fetch(url("portal"), ImageCache.Kind.THUMBNAIL))
        assertNull(c.fetch(url("portal"), ImageCache.Kind.THUMBNAIL))
        assertEquals(1, server.requestCount)
        assertNotNull(c.fetch(url("p1") + "?sig=one", ImageCache.Kind.THUMBNAIL))
        assertNotNull(c.get(url("p1") + "?sig=two"))
        assertEquals(2, server.requestCount)
    }

    @Test fun avDownloadsOnlyOnWifiAndThumbnailsAlways() = runBlocking {
        val c = cache()
        assertNull(c.fetch(url("av1"), ImageCache.Kind.AV)) // mobile data
        assertEquals(0, server.requestCount)
        assertNotNull(c.fetch(url("t1"), ImageCache.Kind.THUMBNAIL))
        wifi = true
        assertNotNull(c.fetch(url("av1"), ImageCache.Kind.AV))
    }

    @Test fun aReadNeverUsesTheNetworkAndACachedFetchIsNotRepeated() = runBlocking {
        val c = cache()
        assertNull(c.get(url("x")))
        assertEquals(0, server.requestCount)
        c.fetch(url("x"), ImageCache.Kind.THUMBNAIL)
        c.fetch(url("x"), ImageCache.Kind.THUMBNAIL)
        assertEquals(1, server.requestCount)
    }

    @Test fun aThumbnailOverTheBoundIsRefused() = runBlocking {
        val c = cache()
        assertNull(c.fetch(url("big"), ImageCache.Kind.THUMBNAIL)) // over the 300 KB thumbnail bound
        assertEquals(0L, c.sizeBytes())
        assertFalse(tmp.root.walkTopDown().any { it.name.endsWith(".part") })
    }
}
