package com.aktcl.aron.amo

import android.content.Context
import androidx.room.withTransaction
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.core.sync.device.DayConfig
import com.aktcl.aron.core.sync.device.DeviceRuntime
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import com.aktcl.aron.feature.attendance.AttendanceFlow
import com.aktcl.aron.feature.outlet.CapturedPhoto
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.ForceSaleController
import com.aktcl.aron.feature.outlet.GeoPhotoCapture
import com.aktcl.aron.feature.outlet.GeoSettings
import com.aktcl.aron.feature.outlet.OutletRequests
import com.aktcl.aron.feature.outlet.PhotoPipeline
import com.aktcl.aron.feature.outlet.VisitFlow
import com.aktcl.aron.feature.outlet.VisitSession
import com.aktcl.aron.feature.outlet.toEntities
import com.aktcl.aron.feature.outlet.FixManagerSource
import com.aktcl.aron.rules.BusinessDate
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * What one logged-in AMO needs for the day, on the user's own database (the AMO twin of the SR's day object, thin on
 * purpose: it grows with the AMO rows and reuses the feature-module flows). The AMO has no route of its own, so records
 * carry no route id until a control or joint call names one. Nothing here waits for the network.
 */
class AmoDay(
    val userId: Long,
    private val context: Context,
    private val db: AronDatabase,
    components: SessionComponents,
    private val scheduler: SyncScheduler,
    fixManager: FixManager,
    private val deviceRuntime: DeviceRuntime? = null,
) {
    private val clock = components.trustedClock
    val capture = CaptureRepository(db) { iso(clock.nowMs()) }
    val reference = ReferenceRepository(db)

    private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
    fun iso(ms: Long): String = isoMillis.format(Instant.ofEpochMilli(ms))
    fun businessDate(): String = BusinessDate.of(clock.nowMs()).toString()
    fun dhakaMinutesNow(): Int = ((clock.nowMs() + BusinessDate.DHAKA_OFFSET_MS) / 60_000L).mod(24 * 60L).toInt()
    fun requestSync() = scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE)

    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Route a control or joint call is on, set by those screens; null otherwise. */
    @Volatile var routeId: Long? = null

    @Volatile private var bundleVersion: String? = null
    @Volatile private var configVersion: Long = 0

    /** Re-reads the bundle stamp the records carry; cheap, called when the day opens and after a bundle change. */
    suspend fun reload() {
        bundleVersion = reference.bundleVersion()
        configVersion = db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L
        overrideMax = runCatching { reference.config("cfg.amo.manual_override_max_per_day", iso(clock.nowMs())) }.getOrNull()
            ?.trim('"')?.toIntOrNull()?.takeIf { it in 0..100 } ?: com.aktcl.aron.feature.amo.ManualOverrideCap.DEFAULT_MAX
    }

    val metaProvider = CaptureMetaProvider { rid ->
        val now = clock.nowMs()
        CaptureMeta(
            businessDate = BusinessDate.of(now).toString(), capturedAt = iso(now), capturedElapsedMs = clock.elapsedRealtimeMs(),
            bootCount = clock.bootCountNow(), clockOffsetMs = clock.clockOffsetMs(), capturedOffline = !online(),
            routeId = rid.takeIf { it != 0L } ?: routeId, actingForUserId = null, bundleVersion = bundleVersion,
            bundleStale = false, configVersion = configVersion,
        )
    }

    /** Attendance and requests always take their own fix (no reuse). */
    val fixSource = FixManagerSource(fixManager)

    private val addressResolver = com.aktcl.aron.core.map.AddressResolver(
        online = ::online,
        backend = com.aktcl.aron.core.map.AndroidGeocodeBackend(context),
        language = { com.aktcl.aron.core.ui.AppLocale.current(context) },
        last = com.aktcl.aron.core.map.PrefsLastAddressStore(context, userId),
    )

    /** F-AMO-003: the SR's attendance flow, unchanged (check-in/out events, DPC hooks, integrity evidence, address offline as coordinates). */
    val attendance = AttendanceFlow(
        fixes = fixSource, metaProvider = metaProvider,
        committer = { e, f ->
            capture.recordAttendance(e, f)
            runCatching {
                val policy = DeviceOwnerPolicy.get(context)
                if (e.kind == "check_in") policy.onCheckInCommitted() else policy.onCheckOutCommitted()
            }
            if (e.kind == "check_in") runCatching { deviceRuntime?.wantEvidenceAtCheckIn() }
            runCatching { scheduler.requestSync(userId, if (e.kind == "check_in") SyncTrigger.WRITE_DEBOUNCE else SyncTrigger.CHECKOUT) }
        },
        routeIdOf = { routeId }, nowIso = { iso(clock.nowMs()) }, dhakaMinutesNow = ::dhakaMinutesNow,
        checkoutEarliestMinutes = { deviceRuntime?.dayConfig?.checkoutEarliestMinutes ?: DayConfig.DEFAULT_CHECKOUT_MINUTES },
        addressResolver = { lat, lng -> addressResolver.displayText(context, lat, lng) },
    )

    suspend fun attendanceToday() = db.captureDao().attendanceOn(businessDate())

    /** "HH:mm" of the configured earliest check-out, for the screen's hint. */
    fun checkoutAtLabel(): String {
        val m = deviceRuntime?.dayConfig?.checkoutEarliestMinutes ?: DayConfig.DEFAULT_CHECKOUT_MINUTES
        return "%02d:%02d".format(m / 60, m % 60)
    }

    // ---- photos (F-SYS-030): the camera and this user's queue, attached by the shell once the queue is open
    @Volatile var photoPipeline: PhotoPipeline = NoCameraPipeline
    @Volatile var media: com.aktcl.aron.core.media.MediaComponents? = null
        private set

    suspend fun attachMedia(m: com.aktcl.aron.core.media.MediaComponents) {
        media = m
        photoPipeline = MediaPhotoPipeline(m)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { m.resume() }
    }

    /** Claims a photo for the record about to be committed so the media worker uploads it after the record's ack; idempotent per record. */
    private suspend fun claimPhoto(photoUuid: String, purpose: String, refType: String, refUuid: String, fix: com.aktcl.aron.core.database.entity.GeoFixEntity?) {
        val m = media ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val item = m.store.get(photoUuid) ?: return@withContext
            val owner = item.ref
            if (owner != null && owner.refClientUuid != refUuid) return@withContext
            m.attach(
                photoUuid, com.aktcl.aron.core.media.MediaRef(purpose, refType, refUuid),
                fix?.let { com.aktcl.aron.core.media.PhotoStamp(it.lat, it.lng, it.accuracyM, it.isMock, it.clientUuid) },
            )
        }
    }

    fun newCapture(purpose: String) = GeoPhotoCapture(fixSource, object : PhotoPipeline {
        override suspend fun captureAndCompress(photoUuid: String) = photoPipeline.captureAndCompress(photoUuid)
        override suspend fun discard(photoUuid: String) = photoPipeline.discard(photoUuid)
    }, purpose)

    // ---- calls: the SR's visit flow, geo gate and Force Sale (Manual Override) on the AMO's own visit kinds
    val visitSession = VisitSession()
    @Volatile private var nextSequence = 1
    suspend fun nextSequenceFromStore() { nextSequence = db.captureDao().visitsOn(businessDate()).size + 1 }

    val visitFlow: VisitFlow = VisitFlow(
        fixes = fixSource, metaProvider = metaProvider,
        committer = { v, f ->
            v.geoForcePhotoUuid?.let { claimPhoto(it, "force_sale", "visit", v.clientUuid, f) }
            capture.recordVisitOpen(v, f); runCatching { requestSync() }
        },
        session = visitSession, settings = { GeoSettings.DEFAULT }, nowIso = { iso(clock.nowMs()) },
        nextSequenceNo = { nextSequence++ },
    )

    private val requestFixUuids = java.util.concurrent.ConcurrentHashMap<String, String>()
    val outletRequests = OutletRequests(
        committer = { draft ->
            val fix = draft.fix ?: fixSource.readFix("outlet_capture")
            val fixUuid = requestFixUuids.getOrPut(draft.requestUuid) { com.aktcl.aron.core.common.ClientIds.newUuid() }
            val (entity, fixEntity) = draft.toEntities(metaProvider.meta(0L), fix, fixUuid)
            draft.photoUuids.forEach { claimPhoto(it, "outlet_capture", "outlet_change_request", entity.clientUuid, fixEntity) }
            capture.recordOutletRequest(entity, fixEntity)
            runCatching { requestSync() }
        },
    )

    fun forceSaleController(capture: GeoPhotoCapture, locationGranted: () -> Boolean) = ForceSaleController(visitFlow, capture, outletRequests, locationGranted)

    /** The zone's routes with their outlets for the date (the zone-wide bundle, local only). */
    suspend fun routes() = reference.routesOfDay(businessDate()).ifEmpty { reference.businessDate()?.let { reference.routesOfDay(it) }.orEmpty() }

    /** Daily Manual Override limit: `cfg.amo.manual_override_max_per_day` when the registry carries it, else the assumed default (MQ-14). */
    fun manualOverrideMax(): Int = overrideMax
    @Volatile private var overrideMax: Int = com.aktcl.aron.feature.amo.ManualOverrideCap.DEFAULT_MAX

    /** Today's visits, for the Manual Override daily limit. */
    suspend fun visitsToday() = db.captureDao().visitsOn(businessDate())

    /** Ends the call in progress: a call with no memo closes `abandoned` (docs/24 s4.2). */
    suspend fun closeVisit(outcome: String = "abandoned") {
        val v = visitSession.current.value ?: return
        capture.recordVisitClose(
            com.aktcl.aron.core.database.entity.VisitCloseEntity(
                clientUuid = com.aktcl.aron.core.common.ClientIds.newUuid(), meta = metaProvider.meta(v.routeId), visitClientUuid = v.visitUuid, outcomeCode = outcome,
                callStartedAt = null, callDeclined = false, endedAt = iso(clock.nowMs()), isZeroSale = true,
            ),
        )
        visitSession.close(); routeId = null; runCatching { requestSync() }
    }

    /** After a relaunch: a committed visit with no close is the call in progress. */
    suspend fun restoreOpenVisit() {
        if (visitSession.current.value != null) return
        val date = businessDate()
        val closed = db.captureDao().visitClosesOn(date).map { it.visitClientUuid }.toSet()
        val last = db.captureDao().visitsOn(date).filter { it.clientUuid !in closed && it.geoAction != "blocked" && it.visitKind.startsWith("amo_") }.maxByOrNull { it.sequenceNo } ?: return
        visitSession.restore(
            com.aktcl.aron.feature.outlet.OpenVisit(
                visitUuid = last.clientUuid, outletId = last.outletId, routeId = last.meta.routeId ?: 0L, geoVerdict = last.geoVerdict,
                geoAction = last.geoAction, geoValidated = last.geoVerdict == "in_range", photoValidated = last.geoForcePhotoUuid != null,
                forceReasonCode = last.geoForceReasonCode, openedAtIso = last.openedAt,
            ),
        )
    }

    // ---- AMO records (amo_record): assessments, price checks, verifications, tasks, reviews
    private fun newUuid() = com.aktcl.aron.core.common.ClientIds.newUuid()

    /** Joint Call assessment (F-AMO-011): one assessment and its answers, family = the visit; a second Save for the visit writes nothing. */
    suspend fun saveJointCall(visitUuid: String, rubric: com.aktcl.aron.feature.amo.JointRubric, state: com.aktcl.aron.feature.amo.JointState, assessedUserId: Long?) {
        if (db.captureDao().amoRecordsOfFamily(visitUuid).any { it.recordType == "call_assessment" }) return
        val planned = com.aktcl.aron.feature.amo.JointCall.records(visitUuid, rubric, state, assessedUserId, ::newUuid)
        db.withTransaction {
            for (p in planned) capture.recordAmo(
                com.aktcl.aron.core.database.entity.AmoRecordEntity(p.clientUuid, metaProvider.meta(visitSession.current.value?.routeId ?: 0L), p.type, p.familyUuid, p.rank, p.payload.toString()),
            )
        }
        runCatching { requestSync() }
    }

    /** SR Perf. Assessment (F-AMO-007/008/009): the check and its brand lines, all or nothing, family = the visit. A second Save for the visit writes nothing. */
    suspend fun saveAssessment(visitUuid: String, brands: List<com.aktcl.aron.feature.amo.AmoBrand>, state: com.aktcl.aron.feature.amo.PerfState) {
        if (db.captureDao().amoRecordsOfFamily(visitUuid).any { it.recordType == "distribution_check" }) return
        val planned = com.aktcl.aron.feature.amo.PerfAssessment.records(visitUuid, brands, state, ::newUuid)
        db.withTransaction {
            for (p in planned) capture.recordAmo(
                com.aktcl.aron.core.database.entity.AmoRecordEntity(p.clientUuid, metaProvider.meta(visitSession.current.value?.routeId ?: 0L), p.type, p.familyUuid, p.rank, p.payload.toString()),
            )
        }
        runCatching { requestSync() }
    }
}

/** F-SYS-030 camera, compression and queue behind the outlet lane's [PhotoPipeline]; null when cancelled or no space. */
class MediaPhotoPipeline(private val media: com.aktcl.aron.core.media.MediaComponents) : PhotoPipeline {
    override suspend fun captureAndCompress(photoUuid: String): CapturedPhoto? =
        media.capture(photoUuid)?.let { CapturedPhoto(photoUuid, it.thumbnailPath, it.item.bytes.toLong()) }
    override suspend fun discard(photoUuid: String) { media.discard(photoUuid) }
}

/** No camera yet: photo-gated steps stay incomplete. */
object NoCameraPipeline : PhotoPipeline {
    override suspend fun captureAndCompress(photoUuid: String): CapturedPhoto? = null
    override suspend fun discard(photoUuid: String) = Unit
}
