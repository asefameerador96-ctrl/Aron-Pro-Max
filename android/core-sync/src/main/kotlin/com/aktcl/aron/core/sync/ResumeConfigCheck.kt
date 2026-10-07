package com.aktcl.aron.core.sync

import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.reference.ConfigDeltaWire
import com.aktcl.aron.core.database.repo.DeltaResult
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.database.repo.ConfigAckStamp
import kotlinx.coroutines.sync.Mutex
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.SyncApi
import com.aktcl.aron.core.network.TransportFailure
import com.aktcl.aron.core.network.WireJson
import com.aktcl.aron.core.session.TrustedClockSource
import kotlinx.serialization.SerializationException

enum class ConfigCheckResult { NOT_DUE, CAPPED, UNCHANGED, APPLIED, NEEDS_BUNDLE, OFFLINE, FAILED }

/**
 * The resume config check (F-SYS-092, ruling R9): when the app comes to the foreground and the last API contact is older
 * than `cfg.sync.config_check_min_gap_min` (5 min), one conditional `GET /v1/config/delta?since=<held version>`; 304 or a
 * delta applied in one transaction; 410 flags a full bundle. At most [dailyCap] requests per business date (AC-17), no
 * timer, never throws, never blocks a visit or a sale (callers launch it and do not wait), nothing offline beyond one
 * failed request.
 */
class ResumeConfigCheck(
    private val db: suspend (userId: Long) -> AronDatabase,
    private val api: SyncApi,
    private val clock: TrustedClockSource,
    private val gapMs: Long = 5 * 60_000L,
    private val dailyCap: Int = 24,
) {
    suspend fun checkOnResume(userId: Long): ConfigCheckResult = locked(userId) { check(userId, afterPush = false) }

    /**
     * An FCM `config_pull` (N-038, docs/19 s4.1 stage 7): the same request without the recent-contact gap, since the server
     * said something changed. It still counts against the daily cap, so a burst of pushes cannot spend the data budget.
     */
    suspend fun pullAfterPush(userId: Long): ConfigCheckResult = locked(userId) { check(userId, afterPush = true) }

    /**
     * The pull job of an FCM config push (F-SYS-073). Unlike [pullAfterPush] it waits for a check already running instead
     * of skipping: that check may have fetched before the change the push announces, and the push job is not retried. An
     * [urgent] push (kill switch, `min_version`, blocked versions, a revert) may go [URGENT_RESERVE] requests over the
     * daily cap, so an ordinary burst that spent the cap cannot hold a kill switch back until tomorrow.
     */
    suspend fun pullForPush(userId: Long, urgent: Boolean): ConfigCheckResult =
        locked(userId, wait = true) { check(userId, afterPush = true, cap = if (urgent) dailyCap + URGENT_RESERVE else dailyCap) }

    private suspend fun locked(userId: Long, wait: Boolean = false, body: suspend () -> ConfigCheckResult): ConfigCheckResult {
        val lock = locks.getOrPut(userId) { Mutex() }
        if (wait) lock.lock()
        else if (!lock.tryLock()) return ConfigCheckResult.NOT_DUE // a check is already running: one request at a time
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }

    private suspend fun check(userId: Long, afterPush: Boolean, cap: Int = dailyCap): ConfigCheckResult {
        val elapsed = clock.elapsedRealtimeMs()
        val boot = clock.bootCountNow()
        val lastContact = clock.recentAnchors().lastOrNull()
            ?.takeIf { (boot <= 0 || it.bootCount == boot) && it.elapsedMs <= elapsed }?.elapsedMs
        if (!afterPush && lastContact != null && elapsed - lastContact < gapMs) return ConfigCheckResult.NOT_DUE
        val database = db(userId)
        val meta = database.referenceDao()
        val countKey = KEY_COUNT + clock.businessDate()
        val count = meta.meta(countKey)?.toIntOrNull() ?: 0
        if (count >= cap) return ConfigCheckResult.CAPPED
        val since = meta.meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: return ConfigCheckResult.NEEDS_BUNDLE
        val r = api.configDelta(since)
        // Only answered requests use the cap: offline does nothing (R9).
        if (r !is ApiResult.Transport) meta.putMeta(SyncMetaEntity(countKey, (count + 1).toString()))
        return when (r) {
            is ApiResult.NotModified -> {
                // 304 at the server's version V: nothing for this phone's chain changed up to V, so the phone holds V's config.
                // Without this, a newer X-Config-Version from an unrelated change made every sync ask again until the daily
                // cap, which then blocked a real change (F-SYS-053 checker).
                val v = r.meta.configVersion
                if (v != null && v > since) meta.putMeta(SyncMetaEntity(ReferenceRepository.KEY_CONFIG_VERSION, v.toString()))
                ConfigCheckResult.UNCHANGED
            }
            is ApiResult.Transport -> if (r.failure == TransportFailure.MALFORMED) ConfigCheckResult.FAILED else ConfigCheckResult.OFFLINE
            is ApiResult.Failure -> if (r.httpStatus == 410) {
                meta.putMeta(SyncMetaEntity(ReferenceRepository.KEY_BUNDLE_REFRESH, "true"))
                ConfigCheckResult.NEEDS_BUNDLE
            } else {
                ConfigCheckResult.FAILED
            }
            is ApiResult.Success -> {
                val delta = try {
                    WireJson.responses.decodeFromString(ConfigDeltaWire.serializer(), r.value)
                } catch (e: SerializationException) {
                    return ConfigCheckResult.FAILED
                } catch (e: IllegalArgumentException) {
                    return ConfigCheckResult.FAILED
                }
                val repo = ReferenceRepository(database)
                when (repo.applyConfigDelta(delta, ackStamp(repo))) {
                    DeltaResult.APPLIED -> ConfigCheckResult.APPLIED
                    DeltaResult.STALE -> ConfigCheckResult.UNCHANGED
                    DeltaResult.GAP -> ConfigCheckResult.NEEDS_BUNDLE
                }
            }
        }
    }

    /** Trusted time and a fresh uuid for the `config_ack` a delta with `requires_ack` keys queues (F-SYS-053). */
    private suspend fun ackStamp(repo: ReferenceRepository): ConfigAckStamp {
        val now = clock.nowMs()
        val meta = com.aktcl.aron.core.database.entity.CaptureMeta(
            businessDate = clock.businessDate().toString(),
            capturedAt = SyncEngine.iso(now),
            capturedElapsedMs = clock.elapsedRealtimeMs(),
            bootCount = clock.bootCountNow(),
            clockOffsetMs = clock.clockOffsetMs(),
            capturedOffline = false,
            routeId = null,
            bundleVersion = repo.bundleVersion(),
            configVersion = repo.configVersionHeld(),
        )
        return ConfigAckStamp(com.aktcl.aron.core.common.ClientIds.newUuid(), meta, SyncEngine.iso(now))
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    companion object {
        private const val KEY_COUNT = "config_check.count."

        /** Requests an urgent config push may make over the daily cap (F-SYS-073; lane decision). */
        const val URGENT_RESERVE = 4
    }
}
