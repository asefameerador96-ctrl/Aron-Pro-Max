package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.NIL_GENERATION
import com.aktcl.aron.backend.platform.RefreshingCache

/**
 * The current database lineage (docs/24 s4.8): `app.server_generation` where `is_current`, cached for [ttlMs] so the
 * `X-Server-Generation` header on every response costs no query. A failover or restore mints a new row; phones that
 * see a new value re-send what was acked after `lost_after_utc`. While the database is down the last good value is
 * served and a refresh is tried once per back-off window (AUD-REL-01); [peek] never waits.
 */
class ServerGeneration(private val db: Database, ttlMs: Long = 30_000) {
    private val cache = RefreshingCache("server-generation", ttlMs, System::currentTimeMillis) {
        db.jdbi.withHandle<String, Exception> { h ->
            h.createQuery("SELECT generation::text FROM app.server_generation WHERE is_current").mapTo(String::class.java).findOne().orElse(null)
                ?: error("no current server generation")
        }
    }

    fun current(): String = cache.get() ?: NIL_GENERATION

    fun peek(): String = cache.peek() ?: NIL_GENERATION
}
