package com.aktcl.aron.sr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.WorkManager
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.session.SessionState
import com.aktcl.aron.core.sync.SyncEngine
import com.aktcl.aron.core.system.support.SupportController
import com.aktcl.aron.core.system.support.SupportHttpApi
import com.aktcl.aron.core.system.support.SupportInput
import com.aktcl.aron.core.system.support.SupportQueue
import com.aktcl.aron.core.system.support.SupportRuntime
import com.aktcl.aron.core.system.support.SupportStatus
import com.aktcl.aron.core.system.support.SupportUploader
import com.aktcl.aron.core.system.update.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The app shell's wiring of core-system for the SR (docs/requests/android-sys-app-wiring.md item 5): the update
 * PDA to Support (F-SR-006; the update flow of F-SR-004 is android-core's UpdateShell). One per process (a Hilt singleton); [install] in
 * Application.onCreate. Nothing here waits for the network: every screen reads the last answer or the local queue.
 */
class SystemShell(context: Context, private val components: SessionComponents, private val databases: UserDatabases) {
    private val app = context.applicationContext

    /** The last `cfg.support.pda_upload_wifi_only` read from a user's bundle; the registry default (Wi-Fi only) until then. */
    @Volatile private var supportWifiOnly: Boolean = true

    /** Application.onCreate: the support worker finds the signed-in user's uploader and the Wi-Fi rule. */
    fun install() {
        SupportRuntime.wiring = SupportRuntime.Wiring(
            uploader = {
                val s = components.session.state.value as? SessionState.Active
                s?.let { SupportUploader(SupportQueue.forUser(app.filesDir, it.user.userId), SupportHttpApi(components.apiClient, components.okHttp), components.clock::nowMs) }
            },
            wifiOnly = { supportWifiOnly },
        )
    }

    fun network(): NetworkStatus {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetworkStatus.OFFLINE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkStatus.WIFI
            else -> NetworkStatus.MOBILE
        }
    }

    /** The PDA to Support controller of [userId] over that user's own database. */
    suspend fun support(userId: Long, versionName: String): SupportShell {
        val db = databases.of(userId)
        val ref = ReferenceRepository(db)
        val nowIso = SyncEngine.iso(components.clock.nowMs())
        suspend fun v(key: String): JsonPrimitive? = runCatching { ref.config(key, nowIso)?.let { Json.parseToJsonElement(it) as? JsonPrimitive } }.getOrNull()
        supportWifiOnly = v("cfg.support.pda_upload_wifi_only")?.booleanOrNull ?: true
        val maxMb = v("cfg.support.max_upload_mb")?.intOrNull ?: 20
        val key = v("cfg.support.public_key_spki")?.contentOrNull?.takeIf { it.isNotBlank() }
        val queue = SupportQueue.forUser(app.filesDir, userId)
        val controller = SupportController(
            queue, { supportInput(userId, db, versionName) }, { key }, { maxMb },
            { SupportRuntime.schedule(WorkManager.getInstance(app), supportWifiOnly) }, components.clock::nowMs,
        )
        return SupportShell(controller, versionName, db, supportWifiOnly)
    }

    inner class SupportShell internal constructor(
        private val controller: SupportController, val versionName: String, private val db: AronDatabase, private val wifiOnly: Boolean,
    ) {
        /** Queues the file (offline too) and returns what the screen shows next. */
        suspend fun send(): SupportStatus {
            val r = controller.send()
            return if (r is SupportStatus.Queued) status() else r
        }

        suspend fun status(): SupportStatus {
            val n = network()
            return controller.status(online = n != NetworkStatus.OFFLINE, onWifi = n == NetworkStatus.WIFI, wifiOnly = wifiOnly) { ms ->
                HHMM.format(java.time.Instant.ofEpochMilli(ms))
            }
        }

        suspend fun lastSyncText(): String? = withContext(Dispatchers.IO) { db.outboxDao().lastAckedAtAny() }
    }

    private suspend fun supportInput(userId: Long, db: AronDatabase, versionName: String): SupportInput = withContext(Dispatchers.IO) {
        val dao = db.outboxDao()
        val now = components.clock.nowMs()
        val since = SyncEngine.iso(now - RESYNC_WINDOW_MS)
        SupportInput(
            appVersion = versionName,
            schemaVersion = db.openHelper.readableDatabase.version,
            userId = userId,
            deviceUuid = components.deviceIdentity.deviceUuid,
            lastSyncAt = dao.lastAckedAtAny(),
            counts = dao.countsByState().associate { it.recordType to it.count },
            unsentPayloads = dao.unsentPayloads(MAX_UNSENT),
            recentAckedPayloads = dao.recentAckedPayloads(since, MAX_ACKED),
            // No on-device log ring buffer exists yet (docs/24 s5.8); the file then carries counts and payloads only.
            logLines = emptyList(),
            createdAt = SyncEngine.iso(now),
        )
    }

    private companion object {
        const val RESYNC_WINDOW_MS = 3L * 24 * 3600 * 1000
        const val MAX_UNSENT = 5_000
        const val MAX_ACKED = 2_000
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.ofHours(6))
    }
}
