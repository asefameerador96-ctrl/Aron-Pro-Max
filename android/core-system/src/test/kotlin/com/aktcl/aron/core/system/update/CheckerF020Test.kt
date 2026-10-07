package com.aktcl.aron.core.system.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Independent checker, F-SYS-020. */
class CheckerF020Test {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.close() }

    private val apk = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private fun release() = ReleaseInfo("1.2.3", 12, "arm64-v8a", sha, apk.size.toLong(), server.url("/aron.apk").toString(), "c".repeat(64))

    /** A required update must offer a release that actually lifts the block; one below min_version is useless. */
    @Test fun requiredNeverOffersAReleaseStillBelowTheMinimum() {
        val below = ReleaseInfo("1.0.45", 45, "arm64-v8a", "a".repeat(64), 1, "https://x/a.apk", "c".repeat(64))
        val s = UpdatePolicy.evaluate(40, UpdateInfo(true, false, 50, below), listOf("arm64-v8a")) as UpdateState.Required
        assertTrue("offered ${s.release?.versionCode} < min 50", s.release == null || s.release!!.versionCode >= 50)
    }

    /** The downloader's cleanup must only touch its own files: a dir shared with anything else (db, photos) is wiped. */
    @Test fun cleanupNeverDeletesFilesThatAreNotUpdaterDownloads() = runTest {
        val foreign = File(tmp.root, "aron.db").apply { writeText("pending rows") }
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apk)).build())
        ApkDownloader(OkHttpClient(), tmp.root, { Long.MAX_VALUE }).download(release())
        assertTrue("cleanup deleted a non-updater file", foreign.exists())
    }

    /** 206 at the wrong offset: must never yield Done (it does not today: Corrupt; recorded as a safety check). */
    @Test fun a206AtTheWrongOffsetIsNeverInstalled() = runTest {
        val d = ApkDownloader(OkHttpClient(), tmp.root, { Long.MAX_VALUE })
        File(tmp.root, d.apkFile(release()).name + ".part").writeBytes(apk.copyOfRange(0, 1000))
        server.enqueue(MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 0-199999/200000").body(Buffer().write(apk.copyOfRange(0, 199_000))).build())
        assertTrue(d.download(release()) !is DownloadResult.Done)
    }

    /** A 206 to a request that sent no Range (fresh start) is a valid full body in practice; it should not be Corrupt forever. */
    @Test fun a206WithoutPartIsNotTerminal() = runTest {
        server.enqueue(MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 0-199999/200000").body(Buffer().write(apk)).build())
        val r = ApkDownloader(OkHttpClient(), tmp.root, { Long.MAX_VALUE }).download(release())
        assertTrue("got $r", r !is DownloadResult.Corrupt)
    }
}
