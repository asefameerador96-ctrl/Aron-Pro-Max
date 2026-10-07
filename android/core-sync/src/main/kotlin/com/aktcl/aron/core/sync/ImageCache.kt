package com.aktcl.aron.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Bounded image cache (F-SYS-029, docs/04 battery and data, D-12): SKU pack thumbnails and AV/KV assets on disk with a
 * hard size cap (`cfg.app.image_cache_mb`, default [DEFAULT_CAP_MB]) and least-recently-used eviction: a read touches the
 * file, a write evicts the oldest files first until the cache is under the cap again. AV and KV assets download only on
 * an unmetered network (Wi-Fi); thumbnails are small and may come over mobile data. A screen shows what is cached and
 * never waits on the network (offline-first); a missing image is a placeholder, never an error (a file returned by [get]
 * may be evicted before it is decoded: a decode failure is the placeholder too). One file per URL without its query; a
 * thumbnail over [THUMBNAIL_MAX_BYTES], an AV asset over half the cap, or a body that is not an image (a captive portal
 * page) is refused, and a URL that failed is not asked again in this process.
 */
class ImageCache(
    private val dir: File,
    private val http: OkHttpClient,
    /** True on Wi-Fi or another unmetered network. */
    private val unmetered: () -> Boolean,
    capBytes: Long = DEFAULT_CAP_MB * MB,
) {
    @Volatile private var capBytes: Long = capBytes

    /** LRU stamp: the next mtime, seeded once from disk (no directory scan per read, no wall clock). */
    private val stamp = java.util.concurrent.atomic.AtomicLong(-1)

    /** URLs that failed in this process: not asked again until the next start (no retry storm per screen open). */
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    enum class Kind { THUMBNAIL, AV }

    private val lock = Mutex()

    /** Applies `cfg.app.image_cache_mb` (held to the registry's 10..70 MB, db V0055) and evicts down to it at once. */
    suspend fun setCapMb(mb: Int) {
        capBytes = mb.coerceIn(10, 70) * MB
        lock.withLock { withContext(Dispatchers.IO) { evict(null) } }
    }

    /** The cached file for [url], touched for LRU, or null. Disk only: never the network. */
    suspend fun get(url: String): File? = withContext(Dispatchers.IO) {
        fileFor(url).takeIf { it.isFile }?.also { it.setLastModified(nextStamp()) } // LRU order without the wall clock
    }

    /**
     * Makes [url] available on disk: the cached file, else a download when the network kind allows ([Kind.AV] only on an
     * unmetered network). Null when it may not or cannot be fetched now. Never throws.
     */
    suspend fun fetch(url: String, kind: Kind): File? = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                fileFor(url).takeIf { it.isFile }?.let { return@withContext it }
                if (kind == Kind.AV && !runCatching(unmetered).getOrDefault(false)) return@withContext null
                if (keyOf(url) in failed) return@withContext null
                http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    val type = r.header("Content-Type").orEmpty().lowercase()
                    val typeOk = if (kind == Kind.AV) type.startsWith("video/") || type.startsWith("image/") || type.startsWith("audio/") else type.startsWith("image/")
                    if (!r.isSuccessful || !typeOk) { failed += keyOf(url); return@withContext null } // a captive portal page is never an image
                    val body = r.body
                    val limit = if (kind == Kind.AV) capBytes / 2 else minOf(THUMBNAIL_MAX_BYTES, capBytes / 4)
                    if (body.contentLength() > limit) { failed += keyOf(url); return@withContext null }
                    dir.mkdirs()
                    val tmp = File(dir, fileFor(url).name + ".part")
                    var written = 0L
                    tmp.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                written += n
                                if (written > limit) { out.close(); tmp.delete(); failed += keyOf(url); return@withContext null }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    val target = fileFor(url)
                    if (!tmp.renameTo(target)) { tmp.delete(); return@withContext null }
                    target.setLastModified(nextStamp())
                    evict(keep = target)
                    target
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Bytes on disk now. */
    fun sizeBytes(): Long = files().sumOf { it.length() }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.toList() ?: emptyList()
    private fun nextStamp(): Long {
        if (stamp.get() < 0) stamp.compareAndSet(-1, files().maxOfOrNull { it.lastModified() } ?: 0L)
        return stamp.incrementAndGet()
    }

    /** Deletes least-recently-used files until the cache is under the cap ([keep] goes last). */
    private fun evict(keep: File?) {
        var total = sizeBytes()
        if (total <= capBytes) return
        for (f in files().sortedWith(compareBy<File> { it == keep }.thenBy { it.lastModified() })) {
            if (total <= capBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /** The URL without its query (a rotated SAS token is the same image). */
    private fun keyOf(url: String): String = url.substringBefore('?')

    private fun fileFor(url: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(keyOf(url).toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, h.take(40) + EXT)
    }

    companion object {
        /** Pilot default (no value in the specs; decision AC-15). */
        const val DEFAULT_CAP_MB = 40L
        /** One compressed thumbnail per SKU (docs/15): anything bigger is refused, never stored over mobile data. */
        const val THUMBNAIL_MAX_BYTES = 300L * 1024L
        const val CFG_CAP_MB = "cfg.app.image_cache_mb"
        private const val MB = 1024L * 1024L
        private const val EXT = ".img"
    }
}
