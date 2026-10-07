package com.aktcl.aron.core.sync

import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.reference.ConfigDeltaWire
import com.aktcl.aron.core.database.repo.DeltaResult
import com.aktcl.aron.core.database.repo.ReferenceRepository
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
    suspend fun checkOnResume(userId: Long): ConfigCheckResult {
        val lock = locks.getOrPut(userId) { Mutex() }
        if (!lock.tryLock()) return ConfigCheckResult.NOT_DUE // a check is already running: one request at a time
        try {
            return check(userId)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun check(userId: Long): ConfigCheckResult {
        val elapsed = clock.elapsedRealtimeMs()
        val boot = clock.bootCountNow()
        val lastContact = clock.recentAnchors().lastOrNull()
            ?.takeIf { (boot <= 0 || it.bootCount == boot) && it.elapsedMs <= elapsed }?.elapsedMs
        if (lastContact != null && elapsed - lastContact < gapMs) return ConfigCheckResult.NOT_DUE
        val database = db(userId)
        val meta = database.referenceDao()
        val countKey = KEY_COUNT + clock.businessDate()
        val count = meta.meta(countKey)?.toIntOrNull() ?: 0
        if (count >= dailyCap) return ConfigCheckResult.CAPPED
        val since = meta.meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: return ConfigCheckResult.NEEDS_BUNDLE
        val r = api.configDelta(since)
        // Only answered requests use the cap: offline does nothing (R9).
        if (r !is ApiResult.Transport) meta.putMeta(SyncMetaEntity(countKey, (count + 1).toString()))
        return when (r) {
            is ApiResult.NotModified -> ConfigCheckResult.UNCHANGED
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
                when (ReferenceRepository(database).applyConfigDelta(delta)) {
                    DeltaResult.APPLIED -> ConfigCheckResult.APPLIED
                    DeltaResult.STALE -> ConfigCheckResult.UNCHANGED
                    DeltaResult.GAP -> ConfigCheckResult.NEEDS_BUNDLE
                }
            }
        }
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private companion object {
        const val KEY_COUNT = "config_check.count."
    }
}
