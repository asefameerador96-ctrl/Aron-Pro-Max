package com.aktcl.aron.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.ContentItemEntity
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** F-SR-020 / F-SYS-029: AV/KV assets ahead of the call, Wi-Fi by policy, checked against their sha256; reads are disk only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ContentAssetsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val server = MockWebServer()
    private var wifi = false
    private val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).addHeader("Content-Type", if (request.url.encodedPath.startsWith("/av")) "video/mp4" else "image/jpeg")
                    .body(Buffer().write(bytes)).build()
        }
        server.start()
    }

    @After fun tearDown() {
        db.close()
        server.close()
    }

    private fun item(id: Long, path: String, sha256: String = sha, to: String = "2026-10-31") = ContentItemEntity(
        id, 1, if (path.startsWith("av")) "av" else "kv", "t", null, server.url("/$path").toString() + "?sig=1", sha256, bytes.size.toLong(),
        null, "2026-10-01", to, 1, true,
    )

    @Test fun assetsComeOnWifiOnlyUnlessThePolicyIsAnyAndAWrongHashIsNeverKept() = runBlocking {
        val assets = ContentAssets(ImageCache(tmp.newFolder("content"), OkHttpClient(), { wifi }, ContentAssets.CAP_MB * 1024 * 1024))
        val av = item(1, "av-1")
        val kv = item(2, "kv-2")
        val bad = item(3, "kv-3", sha256 = "0".repeat(64))
        val old = item(4, "kv-4", to = "2026-10-04")
        db.referenceDao().insertContentItems(listOf(av, kv, bad, old))

        // On mobile data with wifi_only: nothing is downloaded and the screen sees "missing".
        assertEquals(0, assets.prefetch(db, "2026-10-05", allowMetered = false))
        assertEquals(0, server.requestCount)
        assertNull(assets.file(av))
        // Policy any: mobile data is allowed.
        assertEquals(2, assets.prefetch(db, "2026-10-05", allowMetered = true))
        assertNotNull(assets.file(av))
        assertNotNull(assets.file(kv))
        assertNull("a body whose sha256 differs is dropped", assets.file(bad))
        assertNull("an expired item is not fetched", assets.file(old))
        val asked = server.requestCount
        // On Wi-Fi the next job finds them on disk: no request for what is cached.
        wifi = true
        assertEquals(2, assets.prefetch(db, "2026-10-05", allowMetered = false))
        assertEquals("only the failed item is not asked again in this process either", asked, server.requestCount)
    }

    @Test fun thePolicyReadsTheConfigValue() {
        assertTrue(ContentAssets.allowsMetered("\"any\""))
        assertTrue(ContentAssets.allowsMetered("any"))
        assertFalse(ContentAssets.allowsMetered("\"wifi_only\""))
        assertFalse(ContentAssets.allowsMetered("\"wifi_preferred\""))
        assertFalse(ContentAssets.allowsMetered(null))
    }
}
