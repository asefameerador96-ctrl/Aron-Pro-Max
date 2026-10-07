package com.aktcl.aron.core.map

import java.io.File

/**
 * The bounded map cache (docs/17 s10.5, `cfg.map.tile_cache_mb`): the last rendered lite-mode image of each map view,
 * kept so the screen still shows the map as last seen when the network is off. Least recently used files go first when
 * the total passes the cap; the cap is read at every write, so a config change applies at the next map open.
 * Lite mode draws one static image per view, so this cache, not a tile pyramid, is what the phone keeps on disk.
 */
class MapTileCache(private val dir: File, private val maxBytes: () -> Long) {
    @Synchronized
    fun get(key: String): File? {
        val f = File(dir, name(key))
        if (!f.isFile) return null
        f.setLastModified(maxOf(f.lastModified() + 1, newestStamp() + 1)) // most recently used
        return f
    }

    /** Stores [bytes] for [key] (written to a temp file, then renamed: a kill never leaves a half image), then trims. */
    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        if (bytes.size > maxBytes()) return
        dir.mkdirs()
        val target = File(dir, name(key))
        val tmp = File(dir, name(key) + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        target.setLastModified(newestStamp() + 1)
        trim()
    }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    /** Deletes least recently used images until the total is within the cap; stray temp files always go. */
    @Synchronized
    fun trim() {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        val cap = maxBytes()
        var total = sizeBytes()
        for (f in files().sortedBy { it.lastModified() }) {
            if (total <= cap) break
            total -= f.length()
            f.delete()
        }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.toList().orEmpty()

    private fun newestStamp(): Long = files().maxOfOrNull { it.lastModified() } ?: 0L

    companion object {
        private const val EXT = ".webp"

        /**
         * Keys are caller ids (a screen and its scope). Anything outside [a-z0-9_-] is replaced so a key is never a path,
         * and a hash of the raw key keeps two keys that read alike (`zone/1`, `zone_1`) in separate files.
         */
        internal fun name(key: String): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(6).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
            return key.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(60) + "-" + hash + EXT
        }
    }
}
