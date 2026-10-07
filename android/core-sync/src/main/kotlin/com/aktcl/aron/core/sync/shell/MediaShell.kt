package com.aktcl.aron.core.sync.shell

import android.content.Context
import androidx.work.WorkManager
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.MediaMetaCapture
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.media.MediaComponents
import com.aktcl.aron.core.media.MediaConfig
import com.aktcl.aron.core.media.MediaHttpApi
import com.aktcl.aron.core.media.MediaItem
import com.aktcl.aron.core.media.MediaMetaSink
import com.aktcl.aron.core.media.MediaRuntime
import com.aktcl.aron.core.media.MediaState
import com.aktcl.aron.core.media.MediaStore
import com.aktcl.aron.core.media.MediaUploadAuth
import com.aktcl.aron.core.media.MediaUploader
import com.aktcl.aron.core.media.MediaWorkScheduler
import com.aktcl.aron.core.media.OutboxRecordProbe
import com.aktcl.aron.core.media.WifiOnlySetting
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.SyncEngine
import com.aktcl.aron.core.sync.SyncReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The app shells' wiring of core-media (android-sys F-SYS-010, F-SYS-030, F-SYS-037; docs/requests/android-sys-app-wiring.md
 * items 1 and 2). One per process (a Hilt singleton in each app module); [install] in Application.onCreate.
 *
 * Every user with photos on the phone uploads, signed in or not (docs/24 s5.3): the SAS call carries that user's upload
 * grant, which survives logout. The upload job is its own WorkManager work and never part of the record sync.
 */
class MediaShell(context: Context, private val components: SessionComponents, private val databases: UserDatabases) {
    private val app = context.applicationContext

    /** The last `cfg.media.*` read from a user's bundle; the contract defaults until the first read. */
    @Volatile var config: MediaConfig = MediaConfig()
        private set

    /** The rep's "photos on Wi-Fi only" switch (per phone); unset follows `cfg.media.wifi_only_default`. */
    val wifiOnly = WifiOnlySetting(app) { config.wifiOnlyDefault }

    /** Same trusted clock as the uploader (F-SYS-037: the fallback deadline and the uploader must agree). */
    val scheduler: MediaWorkScheduler by lazy {
        MediaWorkScheduler(WorkManager.getInstance(app), wifiOnly::wifiOnly, components.clock::nowMs) { config }
    }

    /** Application.onCreate: the media worker finds its users and uploaders; photos left by an earlier process go out. */
    fun install() {
        MediaRuntime.wiring = MediaRuntime.Wiring(users = { databases.knownUserIds() }, uploader = ::uploader, scheduler = { scheduler })
        scheduler.requestUpload()
    }

    /** The signed-in user's camera and queue (SrDay); call `resume()` on it once at start. */
    suspend fun componentsFor(userId: Long, businessDate: () -> String): MediaComponents {
        config = readConfig(databases.of(userId))
        return MediaComponents(app, userId, components.clock, businessDate, { config }, scheduler = scheduler)
    }

    /** Core-sync hook (SessionSyncRunner.afterRun): records were acked, so photos waiting for them may go now. */
    fun afterSync(report: SyncReport) {
        if (report.acked > 0) scheduler.requestUpload()
    }

    /** Photos of [userId] the server does not have yet (the TSO logout counts them). */
    suspend fun unsentPhotos(userId: Long): Int =
        MediaStore.forUser(app.filesDir, userId).all().count { it.state == MediaState.ATTACHED || it.state == MediaState.UPLOADED }

    /** Null when [userId] has no media queue: the worker then never opens that user's database. */
    internal suspend fun uploader(userId: Long): MediaUploader? {
        val store = MediaStore.forUser(app.filesDir, userId)
        if (!store.dir.isDirectory) return null
        val db = databases.of(userId)
        val cfg = readConfig(db).also { config = it }
        return MediaUploader(
            store,
            MediaHttpApi(components.apiClient, components.okHttp, uploadAuth(userId)),
            OutboxRecordProbe({ db.outboxDao().byClientUuid(it)?.state }, { db.captureDao().photoOwnerExists(it) }),
            metaSink(db),
            components.clock,
            { cfg },
            wifiOnly::wifiOnly,
        )
    }

    private fun uploadAuth(userId: Long) = object : MediaUploadAuth {
        override suspend fun token(): String? = components.session.uploadAccessToken(userId)
        override suspend fun refresh(rejected: String?): Boolean = components.session.refresh(userId, Grant.UPLOAD, rejected)
    }

    /**
     * `media_meta` into the user's outbox (android-sys-media-meta). True once queued, or already queued by a run that was
     * killed after the insert. A fix row that is not on the phone (never stored, or purged) is sent as `fix: null`
     * rather than holding the photo forever.
     */
    internal fun metaSink(db: AronDatabase) = MediaMetaSink { item -> enqueueMeta(db, item) }

    private suspend fun enqueueMeta(db: AronDatabase, item: MediaItem): Boolean {
        if (item.ref == null || item.blobPath == null) return false
        val reference = ReferenceRepository(db)
        val clock = components.trustedClock
        val now = components.clock.nowMs()
        val meta = CaptureMeta(
            businessDate = item.businessDate,
            capturedAt = SyncEngine.iso(now),
            capturedElapsedMs = clock.elapsedRealtimeMs(),
            bootCount = clock.bootCountNow(),
            clockOffsetMs = clock.clockOffsetMs(),
            capturedOffline = false,
            routeId = null,
            bundleVersion = reference.bundleVersion(),
            configVersion = reference.configVersionHeld(),
        )
        val fixStored = item.stamp?.fixClientUuid?.let { db.captureDao().fix(it) != null } ?: false
        val capture = mediaMetaCapture(item, meta, fixStored) ?: return false
        CaptureRepository(db) { SyncEngine.iso(components.clock.nowMs()) }.recordMediaMeta(capture)
        return true
    }

    private suspend fun readConfig(db: AronDatabase): MediaConfig {
        val ref = ReferenceRepository(db)
        val nowIso = SyncEngine.iso(components.clock.nowMs())
        suspend fun v(key: String) = runCatching { ref.config(key, nowIso)?.let { Json.parseToJsonElement(it) as? JsonPrimitive } }.getOrNull()
        val d = MediaConfig()
        return MediaConfig(
            longEdgePx = v("cfg.media.long_edge_px")?.intOrNull ?: d.longEdgePx,
            jpegQuality = v("cfg.media.jpeg_quality")?.intOrNull ?: d.jpegQuality,
            photoMaxKb = v("cfg.media.photo_max_kb")?.intOrNull ?: d.photoMaxKb,
            wifiOnlyDefault = v("cfg.media.wifi_only_default")?.booleanOrNull ?: d.wifiOnlyDefault,
            evidenceMobileFallbackH = v("cfg.media.evidence_mobile_fallback_h")?.intOrNull ?: d.evidenceMobileFallbackH,
            localKeepDays = v("cfg.media.local_keep_days")?.intOrNull ?: d.localKeepDays,
            pendingPhotoGraceDays = v("cfg.media.pending_photo_grace_days")?.intOrNull ?: d.pendingPhotoGraceDays,
        )
    }
}

/**
 * The `media_meta` record of an uploaded photo (android-sys-media-meta): the record's client_uuid is the media uuid, the
 * envelope's business date is the photo's. Null before the photo is claimed and uploaded. A fix row that is not on the
 * phone ([fixStored] false) is sent as `fix: null` rather than holding the photo forever.
 */
internal fun mediaMetaCapture(item: MediaItem, meta: CaptureMeta, fixStored: Boolean): MediaMetaCapture? {
    val ref = item.ref ?: return null
    val blobPath = item.blobPath ?: return null
    return MediaMetaCapture(
        mediaUuid = item.mediaUuid, meta = meta.copy(businessDate = item.businessDate), purpose = ref.purpose, refType = ref.refType,
        refClientUuid = ref.refClientUuid, sha256 = item.sha256, phash = item.phash, bytes = item.bytes, width = item.width,
        height = item.height, blobPath = blobPath, takenAt = item.takenAt,
        fixClientUuid = item.stamp?.fixClientUuid?.takeIf { fixStored },
    )
}
