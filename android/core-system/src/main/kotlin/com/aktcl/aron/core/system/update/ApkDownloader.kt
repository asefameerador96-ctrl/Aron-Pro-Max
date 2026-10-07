package com.aktcl.aron.core.system.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed interface DownloadResult {
    /** The APK, complete and matching the release's SHA-256. */
    data class Done(val apk: File) : DownloadResult
    /** Less than twice the APK size free: nothing downloaded. */
    data class NoSpace(val neededBytes: Long, val freeBytes: Long) : DownloadResult
    /** Interrupted (offline, server error, timeout): the part file is kept and the next call resumes from it. */
    data class Retry(val code: String) : DownloadResult
    /** The bytes do not match the release (SHA-256 or size): the part file is deleted, never installed. */
    data class Corrupt(val code: String) : DownloadResult
}

/**
 * Resumable APK download (F-SYS-020, docs/17 s10.2): HTTP Range from the bytes already on disk, progress as a percentage,
 * SHA-256 and size checked before anything else sees the file, free space of at least twice the APK checked first. A
 * kill resumes from the part file; a part older than [PART_KEEP_DAYS] is dropped. No Aron header or token is sent: the
 * APK comes from storage behind Front Door. https only (loopback for tests); no https-to-http redirect.
 */
class ApkDownloader(
    baseClient: OkHttpClient,
    private val dir: File,
    private val freeBytes: () -> Long = { dir.usableSpace },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val client = baseClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // a 30 MB file on a slow link: the read timeout guards a stall instead
        .followSslRedirects(false)
        .build()

    fun apkFile(release: ReleaseInfo) = File(dir, "aron-${release.versionCode}-${release.sha256.take(12)}.apk")
    private fun partFile(release: ReleaseInfo) = File(dir, apkFile(release).name + ".part")

    suspend fun download(release: ReleaseInfo, onProgress: (percent: Int) -> Unit = {}): DownloadResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        cleanup(release)
        val apk = apkFile(release)
        if (apk.isFile) {
            if (apk.length() == release.sizeBytes && sha256(apk) == release.sha256) return@withContext DownloadResult.Done(apk)
            apk.delete()
        }
        val url = release.downloadUrl.toHttpUrlOrNull()?.takeIf { it.isHttps || it.host in LOOPBACK }
            ?: return@withContext DownloadResult.Corrupt("insecure_url")
        val part = partFile(release)
        if (part.length() > release.sizeBytes) part.delete()
        val needed = 2 * release.sizeBytes - part.length()
        val free = freeBytes()
        if (free < needed) return@withContext DownloadResult.NoSpace(needed, free)

        val from = part.length()
        if (from < release.sizeBytes) {
            val req = Request.Builder().url(url).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
            try {
                client.newCall(req).execute().use { resp ->
                    val append = when {
                        // A 206 is used only when it starts exactly where the part file ends (some CDNs answer 206 to
                        // every request); anything else restarts the download, never splices bytes at a wrong offset.
                        resp.code == 206 -> {
                            val start = CONTENT_RANGE.find(resp.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                            if (start != from) { part.delete(); return@withContext DownloadResult.Retry("range_mismatch") }
                            from > 0
                        }
                        resp.code == 200 -> false // the server ignored the range: start over
                        resp.code == 416 -> return@withContext verify(release, part, apk).also { if (it !is DownloadResult.Done) part.delete() }
                        resp.code in 500..599 || resp.code == 408 || resp.code == 429 -> return@withContext DownloadResult.Retry("http_${resp.code}")
                        else -> return@withContext DownloadResult.Corrupt("http_${resp.code}")
                    }
                    val body = resp.body
                    var done = if (append) from else 0L
                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            var lastPct = -1
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                if (done + n > release.sizeBytes) return@withContext DownloadResult.Corrupt("too_long").also { part.delete() }
                                out.write(buf, 0, n)
                                done += n
                                val pct = (done * 100 / release.sizeBytes).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                            out.fd.sync()
                        }
                    }
                }
            } catch (e: IOException) {
                return@withContext DownloadResult.Retry("io")
            }
        }
        if (part.length() < release.sizeBytes) return@withContext DownloadResult.Retry("short")
        verify(release, part, apk).also { if (it is DownloadResult.Corrupt) part.delete() }
    }

    private fun verify(release: ReleaseInfo, part: File, apk: File): DownloadResult {
        if (part.length() != release.sizeBytes) return DownloadResult.Corrupt("size")
        if (sha256(part) != release.sha256) return DownloadResult.Corrupt("sha256")
        if (!part.renameTo(apk)) return DownloadResult.Retry("rename")
        return DownloadResult.Done(apk)
    }

    /**
     * Removes the updater's own downloads of other releases and part files older than [PART_KEEP_DAYS]. Only files named
     * by this class are ever touched, whatever folder it is given (the app passes `files/updates/`).
     */
    private fun cleanup(current: ReleaseInfo) {
        val keep = setOf(apkFile(current).name, partFile(current).name)
        dir.listFiles().orEmpty().filter { it.isFile && OWN_FILE.matches(it.name) }.forEach { f ->
            val stale = nowMs() - f.lastModified() > PART_KEEP_DAYS * 24 * 3_600_000L
            if (f.name !in keep || stale) f.delete()
        }
    }

    companion object {
        const val PART_KEEP_DAYS = 7L
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1")
        private val OWN_FILE = Regex("^aron-\\d+-[0-9a-f]{1,12}\\.apk(\\.part)?$")
        private val CONTENT_RANGE = Regex("^bytes (\\d+)-\\d+/(\\d+|\\*)$")

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
