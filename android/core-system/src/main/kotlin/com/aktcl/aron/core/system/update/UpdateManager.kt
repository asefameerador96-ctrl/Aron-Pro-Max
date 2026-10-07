package com.aktcl.aron.core.system.update

import android.content.Context
import kotlinx.serialization.json.Json

/** Where the update answer comes from ([UpdateApi] in the app; a fake in tests). */
fun interface UpdateSource {
    suspend fun check(flavour: String, versionCode: Int, abi: String): UpdateCheckResult
}

/** The last answer and when it was fetched; survives a relaunch so the day gate works offline. */
interface UpdateMemory {
    fun lastCheckMs(): Long?
    fun lastInfo(): UpdateInfo?
    fun save(info: UpdateInfo, atMs: Long)
}

class PrefsUpdateMemory(context: Context) : UpdateMemory {
    private val prefs = context.applicationContext.getSharedPreferences("aron-update", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun lastCheckMs(): Long? = prefs.getLong("at", -1).takeIf { it >= 0 }

    /** The minimum is also kept on its own, so an unreadable answer can never lift the day gate. */
    override fun lastInfo(): UpdateInfo? {
        val min = prefs.getInt("min", 1)
        val info = prefs.getString("info", null)?.let { runCatching { json.decodeFromString(UpdateCheckDto.serializer(), it).toInfo() }.getOrNull() }
        return info?.copy(minVersionCode = maxOf(info.minVersionCode, min))
            ?: if (min > 1) UpdateInfo(updateAvailable = false, blocked = false, minVersionCode = min, latest = null) else null
    }

    override fun save(info: UpdateInfo, atMs: Long) {
        prefs.edit().putString("info", json.encodeToString(UpdateCheckDto.serializer(), info.toDto())).putLong("at", atMs)
            .putInt("min", maxOf(info.minVersionCode, prefs.getInt("min", 1))).commit()
    }
}

/**
 * The update check of F-SYS-020: at login, and on foreground at most every 12 h; offline it keeps the last answer and never
 * delays anything. The day gate uses the highest minimum ever seen, so going offline cannot lift a forced minimum.
 */
class UpdateManager(
    private val source: UpdateSource,
    private val memory: UpdateMemory,
    private val flavour: String,
    private val installedCode: Int,
    private val supportedAbis: List<String>,
    private val nowMs: () -> Long,
) {
    /** Checks when due; returns the state to show (from the cache when offline or not due). */
    suspend fun check(atLogin: Boolean): UpdateState {
        if (UpdatePolicy.shouldCheck(memory.lastCheckMs(), nowMs(), atLogin)) {
            val r = source.check(flavour, installedCode, UpdatePolicy.preferredAbi(supportedAbis))
            if (r is UpdateCheckResult.Ok) {
                val prev = memory.lastInfo()
                // The minimum may only rise (docs/24 s9.5); never let a stale or rolled-back answer lower it here.
                val info = r.info.copy(minVersionCode = maxOf(r.info.minVersionCode, prev?.minVersionCode ?: 1))
                memory.save(info, nowMs())
            }
        }
        return state()
    }

    fun state(): UpdateState = memory.lastInfo()?.let { UpdatePolicy.evaluate(installedCode, it, supportedAbis) } ?: UpdateState.Current

    /**
     * May a day start? [dayOpen]: today's day is already open on this phone. [serverSaidTooOld] is the session's
     * `updateRequired` flag from a 426 answer (android-core), which counts like a minimum above this build.
     */
    fun dayGate(dayOpen: Boolean, serverSaidTooOld: Boolean = false, finishOfflineDayBeforeForce: Boolean = true): DayGate {
        val min = maxOf(memory.lastInfo()?.minVersionCode ?: 1, if (serverSaidTooOld) installedCode + 1 else 1)
        return UpdatePolicy.dayGate(installedCode, min, dayOpen, finishOfflineDayBeforeForce)
    }
}

internal fun UpdateInfo.toDto() = UpdateCheckDto(
    updateAvailable = updateAvailable, blocked = blocked, minVersionCode = minVersionCode,
    latest = latest?.let { AppReleaseDto(it.versionName, it.versionCode, it.abi, it.sha256, it.sizeBytes, it.downloadUrl, it.signingCertSha256, "published", it.notesEn, it.notesBn) },
    promptPolicy = promptPolicy, wifiOnly = wifiOnly,
)
