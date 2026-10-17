package com.aktcl.aron.amo

import android.content.Context
import androidx.room.withTransaction
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.feature.outlet.VisitOutlet
import com.aktcl.aron.feature.stock.StockLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    val printerManager: com.aktcl.aron.core.printing.bt.PrinterManager,
    private val userName: String,
    /** The phone's memo numbering (username, bind ordinal, block size) from the login; null only in previews and tests. */
    val memoNumbering: com.aktcl.aron.core.database.repo.MemoNumbering? = null,
    private val deviceRuntime: DeviceRuntime? = null,
) {
    internal val database: AronDatabase get() = db
    val userDisplayName: String get() = userName
    fun currentMs(): Long = clock.nowMs()
    /** Sale, memo, summary and Sales Submit of the SR modules on this user's database (F-AMO-006: the SR sale flow against the AMO's own stock). */
    val sale: AmoSaleKit by lazy { AmoSaleKit(this, context, scheduler, ::online) }

    /** Every outlet of the zone's routes (local bundle); names for memos and prints. Reloaded by [reload]. */
    @Volatile var zoneOutlets: List<com.aktcl.aron.core.database.entity.OutletEntity> = emptyList()
    @Volatile private var zoneRoutes: Map<Long, String> = emptyMap()
    /** Name of the route the open call is on (prints), or empty. */
    fun routeName(): String = zoneRoutes[visitSession.current.value?.routeId ?: routeId ?: 0L].orEmpty()

    private val clock = components.trustedClock
    val api: AmoApi by lazy { AmoApi(components.apiClient, context, userId) }
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
        loadPrintConfig()
        refreshZone()
        bundleVersion = reference.bundleVersion()
        configVersion = db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L
        updateBaseMaxM = runCatching { reference.config("cfg.geo.update_base_max_distance_m", iso(clock.nowMs())) }.getOrNull()
            ?.trim('"')?.toIntOrNull()?.takeIf { it in 10..1000 } ?: com.aktcl.aron.feature.outlet.OutletRequests.DEFAULT_UPDATE_BASE_MAX_M
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
        updateBaseMaxM = { updateBaseMaxM },
    )

    /** `cfg.geo.update_base_max_distance_m` (default 100): how far the chosen Update Base point may be from the AMO. */
    @Volatile var updateBaseMaxM: Int = com.aktcl.aron.feature.outlet.OutletRequests.DEFAULT_UPDATE_BASE_MAX_M

    // Outlet request in progress (kept here so a language switch does not lose the chosen kind and outlet).
    @Volatile var requestKind: com.aktcl.aron.feature.outlet.OutletRequestKind = com.aktcl.aron.feature.outlet.OutletRequestKind.NEW
    @Volatile var requestOutlet: OutletEntity? = null

    /** The bundle's own requests (30 days, status, reason) merged with those still only on this phone. Local reads only. */
    suspend fun ownRequests(): List<com.aktcl.aron.feature.outlet.OwnRequestRow> {
        val names = zoneOutlets.associate { it.outletId to it.name }
        val local = db.captureDao().outletRequests().map { r ->
            val proposed = runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(r.proposedJson) as? kotlinx.serialization.json.JsonObject)?.get("name") }.getOrNull()
            val name = (proposed as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: r.outletId?.let(names::get)
            val ob = db.outboxDao().byClientUuid(r.clientUuid)
            com.aktcl.aron.feature.outlet.LocalRequest(r.clientUuid, r.requestType, name, r.meta.capturedAt, ob?.state, ob?.lastCode)
        }
        val since = iso(clock.nowMs() - 30L * 24 * 3600 * 1000)
        return com.aktcl.aron.feature.outlet.OwnRequests.rows(reference.section("my_outlet_requests"), local, since)
    }

    fun forceSaleController(capture: GeoPhotoCapture, locationGranted: () -> Boolean) = ForceSaleController(visitFlow, capture, outletRequests, locationGranted)

    /** The zone's routes with their outlets for the date (the zone-wide bundle, local only). */
    suspend fun routes() = reference.routesOfDay(businessDate()).ifEmpty { reference.businessDate()?.let { reference.routesOfDay(it) }.orEmpty() }

    /** Daily Manual Override limit: `cfg.amo.manual_override_max_per_day` when the registry carries it, else the assumed default (MQ-14). */
    fun manualOverrideMax(): Int = overrideMax
    @Volatile private var overrideMax: Int = com.aktcl.aron.feature.amo.ManualOverrideCap.DEFAULT_MAX

    /** Today's visits, for the Manual Override daily limit. */
    suspend fun visitsToday() = db.captureDao().visitsOn(businessDate())

    /**
     * Ends the call in progress (docs/24 s4.2). The outcome follows what the call produced: a memo means `sold`; no memo but an
     * audit record (assessment, price check, survey) means a real call with no sale, `zero_sale_stock_ok`; nothing at all means
     * `abandoned`, which does not count as a visit (D-AMO-CLOSE-01).
     */
    private val closeLock = kotlinx.coroutines.sync.Mutex()

    suspend fun closeVisit() = closeLock.withLock {
        // the lock makes a double tap wait; the second run finds the session already closed
        val v = visitSession.current.value ?: return@withLock
        val date = businessDate()
        val memo = db.captureDao().memosOn(date).filter { it.visitClientUuid == v.visitUuid }
        val superseded = memo.mapNotNull { it.supersedesClientUuid }.toSet()
        val live = memo.filter { it.clientUuid !in superseded }.maxByOrNull { it.committedAt }
        val audited = db.captureDao().amoRecordsOfFamily(v.visitUuid).isNotEmpty() || db.captureDao().surveyResponsesOf(v.visitUuid).isNotEmpty()
        when (com.aktcl.aron.feature.amo.AmoCallOutcome.of(live?.lineCount, audited)) {
            com.aktcl.aron.feature.amo.AmoCallOutcome.SOLD -> sale.closeVisit(com.aktcl.aron.feature.sale.domain.VisitOutcomeCode.SOLD)
            com.aktcl.aron.feature.amo.AmoCallOutcome.AUDIT_ONLY -> sale.closeVisit(com.aktcl.aron.feature.sale.domain.VisitOutcomeCode.ZERO_SALE)
            com.aktcl.aron.feature.amo.AmoCallOutcome.ABANDONED -> closeAbandoned(v)
        }
        routeId = null
    }

    /** True once today's supervisor day is submitted: no new call starts until the server reopens it (the same fact the Submit screen reads). */
    suspend fun daySubmitted(): Boolean = com.aktcl.aron.feature.dayclose.RoomDaySource(db).alreadySubmitted(businessDate())

    /** True when the open call already has a live memo with lines (a second sale on one call is refused; edit the memo instead). */
    suspend fun callHasSale(): Boolean {
        val v = visitSession.current.value ?: return false
        val memo = db.captureDao().memosOn(businessDate()).filter { it.visitClientUuid == v.visitUuid }
        val superseded = memo.mapNotNull { it.supersedesClientUuid }.toSet()
        return memo.any { it.clientUuid !in superseded && it.lineCount > 0 }
    }

    /** Re-reads the zone's outlets and route names (names on memos and prints); cheap, local. */
    suspend fun refreshZone() {
        runCatching { routes() }.getOrNull()?.let { days ->
            zoneOutlets = days.flatMap { it.outlets }; zoneRoutes = days.associate { it.route.routeId to it.route.name }
        }
    }

    private suspend fun closeAbandoned(v: com.aktcl.aron.feature.outlet.OpenVisit) {
        capture.recordVisitClose(
            com.aktcl.aron.core.database.entity.VisitCloseEntity(
                clientUuid = com.aktcl.aron.core.common.ClientIds.newUuid(), meta = metaProvider.meta(v.routeId), visitClientUuid = v.visitUuid, outcomeCode = "abandoned",
                callStartedAt = null, callDeclined = false, endedAt = iso(clock.nowMs()), isZeroSale = true,
            ),
        )
        visitSession.close(); runCatching { requestSync() }
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
            reprintMax = { printConfig.reprintMax }, confirmAfterPrint = { printConfig.confirmAfterPrint },
        )
    }

    /** The last `cfg.memo.reprint_max` and `cfg.print.confirm_after_print` read from the stored config (contract defaults until the first read). */
    @Volatile private var printConfig = PrintConfig()

    suspend fun loadPrintConfig() {
        val now = com.aktcl.aron.core.sync.SyncEngine.iso(clock.nowMs())
        printConfig = PrintConfig.from(
            runCatching { reference.config("cfg.memo.reprint_max", now) }.getOrNull(),
            runCatching { reference.config("cfg.print.confirm_after_print", now) }.getOrNull(),
        )
    }

    /** Finishes jobs a killed process left (paper out becomes printed, else failed); once per process, before any print. */
    private var printingRecovered = false

    suspend fun recoverPrinting() {
        if (printingRecovered) return
        printingRecovered = true
        runCatching { printing.recover() }
    }

    /** The stock slip of one Save, from the stored rows only (quantities as entered in the base unit). */
    suspend fun stockSlip(movements: List<com.aktcl.aron.core.database.entity.StockMovementEntity>): com.aktcl.aron.core.printing.doc.StockSlipPrint {
        val skus = reference.skus().associateBy { it.skuId }
        return com.aktcl.aron.core.printing.doc.StockSlipPrint(
            printedAtEpochMs = clock.nowMs(), sr = userName, route = routeName(), distributor = "",
            lines = movements.sortedBy { it.skuId }.map { com.aktcl.aron.core.printing.doc.StockSlipLine(skus[it.skuId]?.categoryCode.orEmpty(), skus[it.skuId]?.shortName ?: it.skuId.toString(), it.qtyBase) },
        )
    }

    /** True while any stock row of today is not on a printed slip (Sales Submit warns on this). */
    suspend fun slipNotPrinted(): Boolean = db.captureDao().stockOn(businessDate()).any { !it.slipPrinted }

    /** Outlives every screen: a print and its readable-question answer must finish even if the SR leaves Stock. */
    private val printScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val _stockAttempt = MutableStateFlow<com.aktcl.aron.core.printing.flow.PrintAttempt?>(null)
    val stockAttempt: StateFlow<com.aktcl.aron.core.printing.flow.PrintAttempt?> = _stockAttempt.asStateFlow()

    /** Today's stock rows not yet on a printed slip, read from Room (survives a kill and relaunch). */
    suspend fun unprintedStock() = db.captureDao().stockOn(businessDate()).filter { !it.slipPrinted }

    /** Prints one slip for every unprinted stock row of today; the confirmation question is answered by [answerStockPrint]. */
    fun printUnprintedStock() {
        printScope.launch {
            if (_stockAttempt.value != null) return@launch
            // One slip covers exactly one Save (the ledger flips the rows of the named row's Save); the next Save prints on the next tap.
            val save = StockSlips.oldestUnprintedSave(unprintedStock())
            if (save.isEmpty()) return@launch
            runCatching { printing.printStockSlip(StockSlips.slipUuid(save), stockSlip(save)) }.onSuccess { _stockAttempt.value = it }
        }
    }

    fun answerStockPrint(a: com.aktcl.aron.core.printing.flow.PrintAttempt.AwaitingConfirmation, readable: Boolean) {
        // A ledger error keeps the attempt, so the answer can be given again; it never crashes the app.
        printScope.launch { runCatching { printing.confirm(a, readable) }.onSuccess { _stockAttempt.value = null } }
    }

    fun closeStockAttempt() { _stockAttempt.value = null }

    /** The Summary print result lives here, not in the screen: leaving Summary while "readable?" is open keeps the question. */
    private val _summaryAttempt = MutableStateFlow<com.aktcl.aron.core.printing.flow.PrintAttempt?>(null)
    val summaryAttempt: StateFlow<com.aktcl.aron.core.printing.flow.PrintAttempt?> = _summaryAttempt.asStateFlow()

    fun printSummary(b: SummaryBundle) {
        printScope.launch { if (_summaryAttempt.value == null) _summaryAttempt.value = sale.printSummary(b) }
    }

    fun answerSummaryPrint(a: com.aktcl.aron.core.printing.flow.PrintAttempt.AwaitingConfirmation, readable: Boolean) {
        printScope.launch { runCatching { printing.confirm(a, readable) }.onSuccess { _summaryAttempt.value = null } }
    }

    fun closeSummaryAttempt() { _summaryAttempt.value = null }

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

    // ---- Verify SR outlet requests (F-AMO-022/023/024)

    /** Request uuids this phone has already decided (queued or sent), so they leave the pending list at once. */
    suspend fun decidedRequests(): Set<String> = db.captureDao().amoRecordsOfType("outlet_request_verification").filter { r ->
        // A decision the server refused (rejected, quarantined) does not hide the request: the AMO can decide it again.
        db.outboxDao().byClientUuid(r.clientUuid)?.state.let { it != com.aktcl.aron.core.database.entity.OutboxState.REJECTED && it != com.aktcl.aron.core.database.entity.OutboxState.QUARANTINED }
    }.mapNotNull {
        runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson) as kotlinx.serialization.json.JsonObject)["request_uuid"]?.let { v -> (v as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull()
    }.toSet()

    /** Pending requests of the SRs in the zone (bundle), minus those decided on this phone. */
    suspend fun pendingVerifications(): List<com.aktcl.aron.feature.amo.PendingRequest> =
        com.aktcl.aron.feature.amo.OutletVerify.pending(runCatching { reference.section("supervisor") }.getOrNull(), userId, decidedRequests())

    suspend fun subChannels(): List<com.aktcl.aron.feature.amo.SubChannel> =
        com.aktcl.aron.feature.amo.OutletVerify.subChannels(runCatching { reference.section("code_lists") }.getOrNull())

    /**
     * Queues the AMO's decision on one request: `verified` (Save) or `discarded` (Cancel on a request they reject), with a fresh fix read
     * now (the server applies the 100 m, move-limit and mock guards). [recordUuid] is fixed when the form opens, so a double tap or a replay
     * queues one record. Returns false when the phone has no usable fix (nothing is queued).
     */
    suspend fun decideRequest(recordUuid: String, requestUuid: String, verify: Boolean, subChannelId: Long?, geoClass: String?, note: String?, photoUuid: String?): Boolean {
        if (requestUuid in decidedRequests()) return true // already decided on this phone: nothing to queue twice
        val reading = fixSource.readFix("outlet_verification")
        if (!reading.isOk || reading.isMock) return false
        val fixEntity = reading.toEntity(newUuid(), recordUuid, "outlet_verification", 0)
        val fix = com.aktcl.aron.core.database.record.RecordMapping.json.encodeToJsonElement(
            com.aktcl.aron.contract.GeoFix.serializer(), com.aktcl.aron.core.database.record.RecordMapping.fix(fixEntity),
        ) as kotlinx.serialization.json.JsonObject
        val payload = com.aktcl.aron.core.database.record.AmoPayloads.outletRequestVerification(
            requestUuid, if (verify) "verified" else "discarded", subChannelId, geoClass, fix, photoUuid, note,
        )
        photoUuid?.let { runCatching { claimPhoto(it, "outlet_verification", "outlet_request_verification", recordUuid, fixEntity) } }
        try {
            capture.recordAmo(
                com.aktcl.aron.core.database.entity.AmoRecordEntity(recordUuid, metaProvider.meta(0L), "outlet_request_verification", recordUuid, 0, payload.toString()),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A concurrent second call with the same record uuid hit the primary key: the decision is queued once.
            if (requestUuid !in decidedRequests()) throw e
        }
        runCatching { requestSync() }
        return true
    }

    // ---- Exceptions (F-AMO-038): risk signals of the zone, reviewed offline

    private suspend fun decidedSignals(): Set<Long> = db.captureDao().amoRecordsOfType("risk_review").filter { r ->
        // A review the server refused (rejected, quarantined) does not hide the signal: it can be reviewed again.
        db.outboxDao().byClientUuid(r.clientUuid)?.state.let { it != com.aktcl.aron.core.database.entity.OutboxState.REJECTED && it != com.aktcl.aron.core.database.entity.OutboxState.QUARANTINED }
    }.mapNotNull {
        runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson) as kotlinx.serialization.json.JsonObject)["signal_id"]?.let { v -> (v as kotlinx.serialization.json.JsonPrimitive).content.toLong() } }.getOrNull()
    }.toSet()

    /** Today's open signals of the zone that this phone has not reviewed yet (local read). */
    suspend fun openSignals(): List<com.aktcl.aron.feature.amo.RiskSignalRow> =
        com.aktcl.aron.feature.amo.Exceptions.open(runCatching { reference.section("supervisor") }.getOrNull(), businessDate(), decidedSignals())

    /** Queues one review event for [signalId]; a signal already reviewed on this phone queues nothing (so a retry never doubles it). */
    suspend fun reviewSignal(recordUuid: String, signalId: Long, action: String, note: String?): Boolean {
        if (signalId in decidedSignals()) return false
        val payload = com.aktcl.aron.core.database.record.AmoPayloads.riskReview(signalId, action, note)
        val queued = capture.recordAmo(
            com.aktcl.aron.core.database.entity.AmoRecordEntity(recordUuid, metaProvider.meta(0L), "risk_review", recordUuid, 0, payload.toString()),
        )
        runCatching { requestSync() }
        return queued
    }

    // ---- Survey (F-AMO-043): the SR's POSM survey component without points

    /** The bundle's survey with its questions, or null when there is none or this visit already answered it. */
    suspend fun pendingSurvey(visitUuid: String): Pair<com.aktcl.aron.core.database.entity.SurveyEntity, List<com.aktcl.aron.core.database.entity.SurveyQuestionEntity>>? {
        if (db.captureDao().surveyResponsesOf(visitUuid).isNotEmpty()) return null
        val survey = db.referenceDao().surveys().firstOrNull() ?: return null
        val questions = db.referenceDao().surveyQuestions(survey.surveyId)
        return if (questions.isEmpty()) null else survey to questions
    }

    /** Writes the confirmed answers all or nothing (one per question per visit; a kill mid-way keeps none) and claims the photos for them. */
    suspend fun saveSurvey(visitUuid: String, survey: com.aktcl.aron.core.database.entity.SurveyEntity, rows: List<com.aktcl.aron.feature.outlet.SurveyResponseRow>) {
        if (rows.isEmpty() || db.captureDao().surveyResponsesOf(visitUuid).isNotEmpty()) return
        val meta = metaProvider.meta(visitSession.current.value?.routeId ?: 0L)
        db.withTransaction {
            for (r in rows) {
                val a = r.answer
                capture.recordSurveyResponse(
                    com.aktcl.aron.core.database.entity.SurveyResponseEntity(
                        r.clientUuid, meta, visitUuid, survey.surveyId, survey.version, r.question.questionId,
                        r.question.answerType.name.lowercase(), a.bool, a.num, a.optionCode, a.text, a.photoUuid,
                    ),
                )
            }
        }
        for (r in rows) r.answer.photoUuid?.let { runCatching { claimPhoto(it, "survey", "survey_response", r.clientUuid, null) } }
        runCatching { requestSync() }
    }

    // ---- Price compliance (F-AMO-035)

    /** Tolerance in basis points: `cfg.amo.price_tolerance_bp` when the registry carries it, else the assumed default. */
    suspend fun priceToleranceBp(): Int = runCatching { reference.config("cfg.amo.price_tolerance_bp", iso(clock.nowMs())) }.getOrNull()
        ?.trim('"')?.toIntOrNull()?.takeIf { it in 0..5000 } ?: com.aktcl.aron.feature.amo.PriceCompliance.DEFAULT_TOLERANCE_BP

    /** SKUs with a list price today for the open call's outlet price type. */
    suspend fun priceRows(bn: Boolean): List<com.aktcl.aron.feature.amo.PriceRow> {
        val outlet = visitSession.current.value?.outletId?.let { db.referenceDao().outlet(it) } ?: return emptyList()
        val date = businessDate()
        val skus = reference.skus()
        val prices = skus.associate { it.skuId to reference.priceOn(it.skuId, outlet.priceType, date) }
        return com.aktcl.aron.feature.amo.PriceCompliance.rows(skus, { prices[it.skuId] }, outlet.priceType, bn)
    }

    /** One `price_compliance_check` per typed SKU, all or nothing. A SKU already recorded for the visit is skipped, so a later Save adds only new SKUs and a double tap writes nothing twice. */
    suspend fun savePriceCompliance(visitUuid: String, rows: List<com.aktcl.aron.feature.amo.PriceRow>, typed: Map<Long, String>, toleranceBp: Int) {
        db.withTransaction {
            val done = db.captureDao().amoRecordsOfFamily(visitUuid).filter { it.recordType == "price_compliance_check" }
                .mapNotNull { runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson) as kotlinx.serialization.json.JsonObject)["sku_id"].toString().toLong() }.getOrNull() }.toSet()
            val planned = com.aktcl.aron.feature.amo.PriceCompliance.records(visitUuid, rows.filter { it.skuId !in done }, typed, toleranceBp, ::newUuid)
            for (p in planned) capture.recordAmo(
                com.aktcl.aron.core.database.entity.AmoRecordEntity(p.clientUuid, metaProvider.meta(visitSession.current.value?.routeId ?: 0L), "price_compliance_check", p.familyUuid, p.rank, p.payload.toString()),
            )
        }
        runCatching { requestSync() }
    }

    /** SKUs already recorded for the open call, so the screen can show them as done. */
    suspend fun pricedSkus(visitUuid: String): Set<Long> = db.captureDao().amoRecordsOfFamily(visitUuid).filter { it.recordType == "price_compliance_check" }
        .mapNotNull { runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson) as kotlinx.serialization.json.JsonObject)["sku_id"].toString().toLong() }.getOrNull() }.toSet()

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
