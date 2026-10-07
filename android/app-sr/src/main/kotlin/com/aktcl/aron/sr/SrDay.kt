package com.aktcl.aron.sr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.BundleOutcome
import com.aktcl.aron.core.sync.BundleDownloaders
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import com.aktcl.aron.feature.attendance.AttendanceFlow
import com.aktcl.aron.feature.home.BundleFreshness
import com.aktcl.aron.feature.outlet.CapturedPhoto
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.FixManagerSource
import com.aktcl.aron.feature.outlet.ForceSaleController
import com.aktcl.aron.feature.outlet.GeoPhotoCapture
import com.aktcl.aron.feature.outlet.GeoSettings
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.OutletRequests
import com.aktcl.aron.feature.outlet.PhotoPipeline
import com.aktcl.aron.feature.outlet.VisitFlow
import com.aktcl.aron.feature.outlet.VisitOutlet
import com.aktcl.aron.feature.outlet.VisitSession
import com.aktcl.aron.feature.outlet.toEntities
import com.aktcl.aron.feature.stock.StockLoad
import com.aktcl.aron.feature.tasks.RoomTaskStore
import com.aktcl.aron.feature.tasks.TaskBoard
import com.aktcl.aron.rules.BusinessDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter

/** What Home and the picker read; a new value is published whenever the bundle, the date or the route changes. */
data class DayData(
    val businessDate: String,
    val route: RouteEntity?,
    /** Every route of the date's bundle (the SR picks among the planned ones when there are several). */
    val routes: List<RouteEntity>,
    val outlets: List<OutletEntity>,
    val freshness: BundleFreshness,
    val downloading: Boolean,
)

/** Everything one logged-in SR needs for the day, built on the user's own database. No screen waits for the network. */
class SrDay(
    val userId: Long,
    private val context: Context,
    private val db: AronDatabase,
    private val components: SessionComponents,
    private val scheduler: SyncScheduler,
    fixManager: FixManager,
    val printerManager: com.aktcl.aron.core.printing.bt.PrinterManager,
    private val userName: String,
    /** Geo, integrity and DPC wiring (core-sync); null in previews and tests. */
    private val deviceRuntime: com.aktcl.aron.core.sync.device.DeviceRuntime? = null,
    /** F-SYS-092 resume config check; null in previews and tests. */
    private val resumeConfigCheck: com.aktcl.aron.core.sync.ResumeConfigCheck? = null,
) {
    /** Work started from the day that nobody waits for (the resume config check, settings reloads). */
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /**
     * R9: launches the conditional config check and returns at once; a visit or a sale never waits for it. Also bound as
     * the VisitFlow's [com.aktcl.aron.feature.outlet.ConfigCheck], so even an awaiting caller only pays a launch.
     */
    fun launchConfigCheck() {
        val check = resumeConfigCheck ?: return
        background.launch {
            val r = runCatching { check.checkOnResume(userId) }.getOrNull()
            if (r == com.aktcl.aron.core.sync.ConfigCheckResult.APPLIED) {
                deviceRuntime?.refreshDayConfig(userId, db)
                // reload() writes the day's plain fields; it runs where the screens run it, on the main thread.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { runCatching { reload() } }
            }
        }
    }

    /** F-SYS-007: on foreground, a bundle delta at most every 30 min; the day reloads only when rows changed. Never awaited. */
    fun launchDeltaRefresh(downloaders: BundleDownloaders) {
        background.launch {
            val r = runCatching { downloaders.of(userId).refreshOnForeground() }.getOrNull()
            if (r?.outcome == BundleOutcome.APPLIED) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { runCatching { reload() } }
        }
    }

    /** Reloads what device code reads synchronously (cfg.geo.* for fixes, the DPC calendar) from this user's bundle. */
    fun launchDayConfigRefresh() { deviceRuntime?.let { rt -> background.launch { rt.refreshDayConfig(userId, db) } } }

    private val clock = components.trustedClock
    val capture = CaptureRepository(db) { iso(clock.nowMs()) }
    val reference = ReferenceRepository(db)
    val visitSession = VisitSession()

    private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
    fun iso(ms: Long): String = isoMillis.format(Instant.ofEpochMilli(ms))
    fun currentMs(): Long = clock.nowMs()
    fun requestSync() = scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE)
    fun businessDate(): String = BusinessDate.of(clock.nowMs()).toString()
    /** Milliseconds until the next 17:00 or the next midnight on Dhaka trusted time (at least one second). */
    fun millisToNextBoundary(): Long {
        val dayMs = 24L * 3_600_000L
        val now = (clock.nowMs() + BusinessDate.DHAKA_OFFSET_MS).mod(dayMs)
        val seventeen = 17L * 3_600_000L
        val next = if (now < seventeen) seventeen else dayMs
        return (next - now + 1_000L).coerceAtLeast(1_000L)
    }
    fun dhakaMinutesNow(): Int = ((clock.nowMs() + BusinessDate.DHAKA_OFFSET_MS) / 60_000L).mod(24 * 60L).toInt()

    @Volatile var routeId: Long? = null
    @Volatile var actingForUserId: Long? = null
    @Volatile private var freshness: BundleFreshness = BundleFreshness.Missing
    @Volatile private var bundleVersion: String? = null
    @Volatile private var configVersion: Long = 0

    private val data = MutableStateFlow(DayData(businessDate(), null, emptyList(), emptyList(), BundleFreshness.Missing, false))
    val dayData: StateFlow<DayData> = data.asStateFlow()

    /** Re-reads the bundle stamp, the route, the outlets and the freshness from Room; cheap and safe to call every minute. */
    suspend fun reload() {
        val date = businessDate()
        bundleVersion = reference.bundleVersion()
        val bundleDate = reference.businessDate()
        configVersion = db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L
        freshness = BundleFreshness.of(bundleDate?.let(LocalDate::parse), LocalDate.parse(date))
        val routes = reference.routesOfDay(date).ifEmpty { bundleDate?.let { reference.routesOfDay(it) }.orEmpty() }
        val planned = routes.filter { it.route.plannedToday }
        val chosen = chosenRouteId()?.let { id -> planned.firstOrNull { it.route.routeId == id } }
        val route = chosen ?: planned.singleOrNull() ?: planned.minByOrNull { it.route.sequenceNo ?: Int.MAX_VALUE } ?: routes.firstOrNull()
        routeId = route?.route?.routeId
        val outlets = if (planned.size > 1 && route != null) route.outlets else routes.flatMap { it.outlets }
        data.value = DayData(date, route?.route, routes.map { it.route }, outlets, freshness, data.value.downloading)
    }

    private val routePrefs = context.getSharedPreferences("aron-route-$userId", Context.MODE_PRIVATE)
    /** The route the SR picked for today's date (F-SR-065), or null. */
    fun chosenRouteId(): Long? = routePrefs.getLong("route-" + businessDate(), -1L).takeIf { it > 0 }
    suspend fun chooseRoute(id: Long) { routePrefs.edit().putLong("route-" + businessDate(), id).apply(); reload() }

    // Outlet request in progress (kept here so a language switch does not lose the chosen kind and outlet).
    @Volatile var requestKind: com.aktcl.aron.feature.outlet.OutletRequestKind = com.aktcl.aron.feature.outlet.OutletRequestKind.NEW
    @Volatile var requestOutlet: OutletEntity? = null

    /** Placeholder end of a call until the sale screens exist: no memo, so the visit closes `abandoned` (docs/24 s4.2). */
    suspend fun closeVisitAbandoned() {
        val v = visitSession.current.value ?: return
        val meta = metaProvider.meta(v.routeId)
        val now = iso(clock.nowMs())
        capture.recordVisitClose(
            com.aktcl.aron.core.database.entity.VisitCloseEntity(
                clientUuid = com.aktcl.aron.core.common.ClientIds.newUuid(), meta = meta, visitClientUuid = v.visitUuid, outcomeCode = "abandoned",
                callStartedAt = null, callDeclined = false, endedAt = now, isZeroSale = true,
            ),
        )
        visitSession.close(); runCatching { requestSync() }
    }

    /** Day start: the first bundle download (resumable, F-SYS-006) in the background; the day never waits for it. */
    suspend fun downloadBundle(downloaders: BundleDownloaders) {
        data.value = data.value.copy(downloading = true)
        try {
            runCatching { downloaders.of(userId).download(businessDate()) }
                .onSuccess { if (it.outcome == BundleOutcome.APPLIED || it.outcome == BundleOutcome.UNCHANGED) { reload(); deviceRuntime?.refreshDayConfig(userId, db) } }
        } finally {
            data.value = data.value.copy(downloading = false)
        }
    }

    /** After a relaunch or a language switch: a committed visit with no close is the call in progress (R8). */
    suspend fun restoreOpenVisit() {
        if (visitSession.current.value != null) return
        val date = businessDate()
        val closed = db.captureDao().visitClosesOn(date).map { it.visitClientUuid }.toSet()
        val last = db.captureDao().visitsOn(date).filter { it.clientUuid !in closed && it.geoAction != "blocked" }.maxByOrNull { it.sequenceNo } ?: return
        visitSession.restore(
            OpenVisit(
                visitUuid = last.clientUuid, outletId = last.outletId, routeId = last.meta.routeId ?: 0L, geoVerdict = last.geoVerdict,
                geoAction = last.geoAction, geoValidated = last.geoVerdict == "in_range", photoValidated = last.geoForcePhotoUuid != null,
                forceReasonCode = last.geoForceReasonCode, openedAtIso = last.openedAt,
            ),
        )
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    val metaProvider = CaptureMetaProvider { rid ->
        val now = clock.nowMs()
        CaptureMeta(
            businessDate = BusinessDate.of(now).toString(), capturedAt = iso(now), capturedElapsedMs = clock.elapsedRealtimeMs(),
            bootCount = clock.bootCountNow(), clockOffsetMs = clock.clockOffsetMs(), capturedOffline = !online(),
            routeId = rid.takeIf { it != 0L } ?: routeId, actingForUserId = actingForUserId, bundleVersion = bundleVersion,
            bundleStale = freshness.markRecordsStale, configVersion = configVersion,
        )
    }

    /** Fix reuse (D-74) is limited to the visit cycle of the day; attendance and requests always take their own fix. */
    val fixSource = FixManagerSource(fixManager) { purpose -> if (purpose == "visit_open") "outlets-" + businessDate() else null }

    val attendance = AttendanceFlow(
        fixes = fixSource, metaProvider = metaProvider,
        committer = { e, f ->
            capture.recordAttendance(e, f)
            // The record is safe in Room; a failing hook must never undo it or leave the screen half-updated.
            runCatching {
                val policy = DeviceOwnerPolicy.get(context)
                if (e.kind == "check_in") policy.onCheckInCommitted() else policy.onCheckOutCommitted()
            }
            // Play Integrity evidence at check-in (docs/24 s8.7): collected by the next sync run, never awaited here.
            if (e.kind == "check_in") runCatching { deviceRuntime?.wantEvidenceAtCheckIn() }
            runCatching { scheduler.requestSync(userId, if (e.kind == "check_in") SyncTrigger.WRITE_DEBOUNCE else SyncTrigger.CHECKOUT) }
        },
        routeIdOf = { routeId }, nowIso = { iso(clock.nowMs()) }, dhakaMinutesNow = ::dhakaMinutesNow,
    )

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
        configCheck = com.aktcl.aron.feature.outlet.ConfigCheck { launchConfigCheck() },
    )

    // ---- printing (F-SR-015): the ledger is Room, the renderer is built once (font parsing is the expensive part)
    private val renderer: com.aktcl.aron.core.printing.PaperRenderer by lazy {
        val labels = com.aktcl.aron.core.printing.AndroidPrintLabels(context, AppLanguage.BN)
        fun font(id: Int) = context.resources.openRawResource(id).use { it.readBytes() }
        com.aktcl.aron.core.printing.PaperRenderer(
            com.aktcl.aron.core.printing.render.PrintFonts(font(com.aktcl.aron.core.ui.R.font.noto_sans_bengali_regular), font(com.aktcl.aron.core.ui.R.font.noto_sans_bengali_bold)),
            com.aktcl.aron.core.printing.TemplateSet(emptyList(), labels), labels,
        )
    }

    val printing: com.aktcl.aron.core.printing.flow.MemoPrinting by lazy {
        com.aktcl.aron.core.printing.flow.MemoPrinting(
            printerManager, { renderer }, com.aktcl.aron.core.database.repo.RoomPrintLedger(db, { base -> base ?: metaProvider.meta(0L) }),
            com.aktcl.aron.core.common.ClientIds::newUuid, clock::nowMs,
        )
    }

    /** Finishes jobs a killed process left (paper out becomes printed, else failed); once per process, before any print. */
    suspend fun recoverPrinting() { runCatching { printing.recover() } }

    /** The stock slip of one Save, from the stored rows only (quantities as entered in the base unit). */
    suspend fun stockSlip(movements: List<com.aktcl.aron.core.database.entity.StockMovementEntity>): com.aktcl.aron.core.printing.doc.StockSlipPrint {
        val skus = reference.skus().associateBy { it.skuId }
        return com.aktcl.aron.core.printing.doc.StockSlipPrint(
            printedAtEpochMs = clock.nowMs(), sr = userName, route = data.value.route?.name.orEmpty(), distributor = "",
            lines = movements.sortedBy { it.skuId }.map { com.aktcl.aron.core.printing.doc.StockSlipLine(skus[it.skuId]?.categoryCode.orEmpty(), skus[it.skuId]?.shortName ?: it.skuId.toString(), it.qtyBase) },
        )
    }

    /** True while any stock row of today is not on a printed slip (Sales Submit warns on this). */
    suspend fun slipNotPrinted(): Boolean = db.captureDao().stockOn(businessDate()).any { !it.slipPrinted }

    suspend fun attendanceToday() = db.captureDao().attendanceOn(businessDate())

    fun visitOutlet(o: OutletEntity) = VisitOutlet(
        outletId = o.outletId, routeId = o.routeId, name = o.name, code = o.code, lat = o.lat, lng = o.lng,
        locationBasis = if (o.lat == null || o.lng == null) "none" else if (o.locationConfirmed) "master" else "provisional",
        radiusM = o.radiusM, maxAccuracyM = o.maxAccuracyM,
    )

    // ---- stock: one StockLoad per day and screen visit; the re-save guard survives a kill (SharedPreferences)
    private var stock: StockLoad? = null
    suspend fun stockLoad(): StockLoad = stock ?: run {
        val loaded = db.captureDao().stockBalanceOn(businessDate()).associate { it.skuId to it.qtyBase }
        StockLoad(reference.skus(), loaded, guardStore = PrefsGuardStore(context, userId)).also { stock = it }
    }
    fun forgetStock() { stock = null }

    // ---- tasks (Room, F-SR-046/047)
    val taskBoard = TaskBoard(
        RoomTaskStore(
            reference, capture, outletName = { id -> data.value.outlets.firstOrNull { it.outletId == id }?.name },
            metaFor = { metaProvider.meta(0L) },
            syncedAtIso = { db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_SERVER_TIME) },
        ),
        { iso(clock.nowMs()) },
    )

    // ---- outlet requests (F-SR-037/038/039/076, N-040): one Room commit with the request's own fix
    val outletRequests = OutletRequests(
        committer = { draft ->
            val fix = draft.fix ?: fixSource.readFix("outlet_capture")
            val (entity, fixEntity) = draft.toEntities(metaProvider.meta(0L), fix)
            draft.photoUuids.forEach { claimPhoto(it, "outlet_capture", "outlet_change_request", entity.clientUuid, fixEntity) }
            capture.recordOutletRequest(entity, fixEntity)
            runCatching { requestSync() }
        },
    )

    /** The camera pipeline of F-SYS-030; [attachMedia] replaces the placeholder once the user's media queue is open. */
    @Volatile var photoPipeline: PhotoPipeline = NoCameraPipeline

    /** This user's camera and photo queue (core-media); null in previews and tests (no camera, nothing to claim). */
    @Volatile var media: com.aktcl.aron.core.media.MediaComponents? = null
        private set

    /** Shell wiring (MainActivity): the camera, then photos a killed process left are re-armed for upload. */
    suspend fun attachMedia(m: com.aktcl.aron.core.media.MediaComponents) {
        media = m
        photoPipeline = MediaPhotoPipeline(m)
        m.resume()
    }

    /**
     * Claims a photo for the record about to be committed (F-SYS-030: before the commit, so the media worker uploads it
     * after the record's ack). Idempotent for the same record. A photo that already belongs to another record (the Force
     * Sale shot reused by its location request) stays with the first record; the request still names it.
     */
    private suspend fun claimPhoto(photoUuid: String, purpose: String, refType: String, refUuid: String, fix: com.aktcl.aron.core.database.entity.GeoFixEntity?) {
        val m = media ?: return
        val item = m.store.get(photoUuid) ?: return
        val owner = item.ref
        if (owner != null && owner.refClientUuid != refUuid) return
        m.attach(
            photoUuid, com.aktcl.aron.core.media.MediaRef(purpose, refType, refUuid),
            fix?.let { com.aktcl.aron.core.media.PhotoStamp(it.lat, it.lng, it.accuracyM, it.isMock, it.clientUuid) },
        )
    }

    fun newCapture(purpose: String) = GeoPhotoCapture(fixSource, object : PhotoPipeline {
        override suspend fun captureAndCompress(photoUuid: String) = photoPipeline.captureAndCompress(photoUuid)
        override suspend fun discard(photoUuid: String) = photoPipeline.discard(photoUuid)
    }, purpose)

    fun forceSaleController(capture: GeoPhotoCapture, locationGranted: () -> Boolean) =
        ForceSaleController(visitFlow, capture, outletRequests, locationGranted)
}

/** F-SYS-030 camera, compression and queue behind the outlet lane's [PhotoPipeline]; null when cancelled or no space. */
class MediaPhotoPipeline(private val media: com.aktcl.aron.core.media.MediaComponents) : PhotoPipeline {
    override suspend fun captureAndCompress(photoUuid: String): CapturedPhoto? =
        media.capture(photoUuid)?.let { CapturedPhoto(photoUuid, it.thumbnailPath, it.item.bytes.toLong()) }
    override suspend fun discard(photoUuid: String) { media.discard(photoUuid) }
}

/** Placeholder until F-SYS-030 (photo capture and compression) is on INT: no photo, so photo-gated steps stay incomplete. */
object NoCameraPipeline : PhotoPipeline {
    override suspend fun captureAndCompress(photoUuid: String): CapturedPhoto? = null
    override suspend fun discard(photoUuid: String) = Unit
}

/** Re-save guard of the stock screen kept across a kill and relaunch. */
class PrefsGuardStore(context: Context, userId: Long) : StockLoad.GuardStore {
    private val prefs = context.getSharedPreferences("aron-stock-guard-$userId", Context.MODE_PRIVATE)
    override fun load(): Pair<String, Long>? = prefs.getString("sig", null)?.let { it to prefs.getLong("at", 0L) }
    override fun save(signature: String, atMs: Long) { prefs.edit().putString("sig", signature).putLong("at", atMs).apply() }
}
