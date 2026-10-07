package com.aktcl.aron.core.system.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** F-SYS-020 download: resumable by HTTP Range, SHA-256 and size verified, space checked first. */
class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.close() }

    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private fun release(sha256: String = sha, size: Long = apk.size.toLong()) =
        ReleaseInfo("1.2.3", 12, "arm64-v8a", sha256, size, server.url("/aron.apk").toString(), "c".repeat(64))
    private fun downloader(free: Long = Long.MAX_VALUE) = ApkDownloader(OkHttpClient(), tmp.root, { free })

    @Test fun aCompleteDownloadIsVerifiedAndReported() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apk)).build())
        val seen = mutableListOf<Int>()
        val r = downloader().download(release()) { seen += it } as DownloadResult.Done
        assertArrayEquals(apk, r.apk.readBytes())
        assertEquals(100, seen.last())
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test fun aBrokenDownloadResumesFromTheBytesOnDisk() = runTest {
        // First attempt: the connection drops after 120 000 bytes (the server promised more).
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apk.copyOfRange(0, 120_000))).setHeader("Content-Length", apk.size.toString()).build())
        assertTrue(downloader().download(release()) is DownloadResult.Retry)
        // Relaunch: a new downloader asks only for the rest.
        server.enqueue(MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 120000-${apk.size - 1}/${apk.size}").body(Buffer().write(apk.copyOfRange(120_000, apk.size))).build())
        val r = downloader().download(release()) as DownloadResult.Done
        assertArrayEquals(apk, r.apk.readBytes())
        server.takeRequest()
        assertEquals("bytes=120000-", server.takeRequest().headers["Range"])
    }

    @Test fun aServerThatIgnoresTheRangeStartsOver() = runTest {
        java.io.File(tmp.root, downloader().apkFile(release()).name + ".part").writeBytes(apk.copyOfRange(0, 1000))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apk)).build())
        assertTrue(downloader().download(release()) is DownloadResult.Done)
    }

    @Test fun wrongBytesAreNeverKept() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apk)).build())
        assertEquals(DownloadResult.Corrupt("sha256"), downloader().download(release(sha256 = "0".repeat(64))))
        assertTrue(tmp.root.listFiles()!!.isEmpty())
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apk + byteArrayOf(1))).build())
        assertEquals(DownloadResult.Corrupt("too_long"), downloader().download(release()))
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun lessThanTwiceTheSizeFreeDownloadsNothing() = runTest {
        val r = downloader(free = apk.size * 2L - 1).download(release())
        assertTrue(r is DownloadResult.NoSpace)
        assertEquals(0, server.requestCount)
    }

    @Test fun serverErrorsAreRetriesAndKeepThePart() = runTest {
        java.io.File(tmp.root, downloader().apkFile(release()).name + ".part").writeBytes(apk.copyOfRange(0, 1000))
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(DownloadResult.Retry("http_503"), downloader().download(release()))
        assertEquals(1000L, java.io.File(tmp.root, downloader().apkFile(release()).name + ".part").length())
    }

    @Test fun cleartextUrlsAreRefused() = runTest {
        val r = downloader().download(release().copy(downloadUrl = "http://cdn.example/aron.apk"))
        assertEquals(DownloadResult.Corrupt("insecure_url"), r)
    }

    @Test fun aFinishedApkIsReusedAndOldDownloadsAreRemoved() = runTest {
        java.io.File(tmp.root, "aron-11-abcdef.apk").writeBytes(byteArrayOf(1))
        java.io.File(tmp.root, "aron-u7.db").writeBytes(byteArrayOf(1)) // not ours: never touched
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apk)).build())
        downloader().download(release())
        assertTrue(downloader().download(release()) is DownloadResult.Done)
        assertEquals(1, server.requestCount)
        assertFalse(java.io.File(tmp.root, "aron-11-abcdef.apk").exists())
        assertTrue(java.io.File(tmp.root, "aron-u7.db").exists())
    }
}
