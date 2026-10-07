package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.NIL_GENERATION
import java.util.concurrent.atomic.AtomicReference

/**
 * The current database lineage (docs/24 s4.8): `app.server_generation` where `is_current`, cached for [ttlMs] so the
 * `X-Server-Generation` header on every response costs no query. A failover or restore mints a new row; phones that
 * see a new value re-send what was acked after `lost_after_utc`.
 */
class ServerGeneration(private val db: Database, private val ttlMs: Long = 30_000) {
    private val cache = AtomicReference<Pair<Long, String>?>(null)

    fun current(): String {
        val now = System.currentTimeMillis()
        cache.get()?.let { if (now - it.first < ttlMs) return it.second }
        val g = runCatching {
            db.jdbi.withHandle<String?, Exception> { h ->
                h.createQuery("SELECT generation::text FROM app.server_generation WHERE is_current").mapTo(String::class.java).findOne().orElse(null)
            }
        }.getOrNull()
        if (g != null) cache.set(now to g)
        return g ?: cache.get()?.second ?: NIL_GENERATION
    }
}
