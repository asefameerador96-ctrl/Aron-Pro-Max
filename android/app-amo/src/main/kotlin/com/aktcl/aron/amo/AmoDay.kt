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
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.LockAcks
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.testTag
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
    /**
     * The parent of the day's own scopes (the print scope, the visit flow's config check). Null on the phone: each scope is
     * its own root, as before, and lives as long as the day. A test passes its own job, so it can let the day's work finish,
     * cancel it and join it before it closes the database (CI run 41750277921).
     */
    parentJob: kotlinx.coroutines.Job? = null,
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
    /** Fixed client uuids of the forms that queue a record, across a kill ([FormDrafts]). */
    val drafts: FormDrafts by lazy { FormDrafts(context, userId) }
    val api: AmoApi by lazy { AmoApi(components.apiClient, context, userId) }
    /** D-AMO-SHELL-10: the Home target cards (`GET /v1/supervisor/call-targets`) with a kept copy per month in Room. */
    val callTargets: com.aktcl.aron.feature.amo.CallTargetsFeed by lazy {
        com.aktcl.aron.feature.amo.CachedCallTargets(
            fetch = { month ->
                when (val r = api.callTargets(month)) {
                    is AmoFetch.Fresh -> com.aktcl.aron.feature.amo.CallTargetsFetch.Fresh(r.json)
                    AmoFetch.Gone -> com.aktcl.aron.feature.amo.CallTargetsFetch.Gone
                    else -> com.aktcl.aron.feature.amo.CallTargetsFetch.Unavailable
                }
            },
            store = RoomCallTargetsStore(db.referenceDao()),
        )
    }
    val capture = CaptureRepository(db) { iso(clock.nowMs()) }
    val reference = ReferenceRepository(db)

    private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
    fun iso(ms: Long): String = isoMillis.format(Instant.ofEpochMilli(ms))
    fun businessDate(): String = BusinessDate.of(clock.nowMs()).toString()
    fun dhakaMinutesNow(): Int = ((clock.nowMs() + BusinessDate.DHAKA_OFFSET_MS) / 60_000L).mod(24 * 60L).toInt()
    fun requestSync() = scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE)
    /** D-NIGHT-DAYCLOSE-01, contract 1.9 row 4: the Sales Submit digest (`GET /v1/sync/totals`), read when the screen asks. */
    val submitDigests = com.aktcl.aron.core.sync.SubmitDigestSource(
        userId, com.aktcl.aron.core.sync.SessionUploadAuth(components.session), com.aktcl.aron.core.sync.SyncTotalsDigestApi(components.apiClient),
        nowMs = { components.clock.elapsedRealtimeMs() },
    )

    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Route a control or joint call is on, set by those screens; null otherwise. */
    @Volatile var routeId: Long? = null

    @Volatile private var bundleVersion: String? = null
    @Volatile private var configVersion: Long = 0

    /**
     * F-SYS-006 (D-AMO-BUNDLE-01): the day's bundle into this user's Room database (SKUs, zone routes and outlets, the
     * supervisor section), as the SR app does at day start. Without it the Stock, Sale and zone screens read an empty
     * database. One run at a time (the downloader's own lock); offline it changes nothing and the day never waits for it.
     * Run it where the screens run [reload] (the main thread): it re-reads what the day holds when rows were applied.
     */
    suspend fun downloadBundle(downloaders: com.aktcl.aron.core.sync.BundleDownloaders): com.aktcl.aron.core.sync.BundleOutcome {
        val outcome = try {
            // The download itself runs in the downloaders' own scope (off the main thread, one run per user, not cancelled by a
            // rotation); only the reads below run here, where the screens run them.
            downloaders.ensureDay(userId, businessDate()).outcome
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            com.aktcl.aron.core.sync.BundleOutcome.FAILED
        }
        if (outcome == com.aktcl.aron.core.sync.BundleOutcome.APPLIED || outcome == com.aktcl.aron.core.sync.BundleOutcome.PREFETCH_PROMOTED) {
            try { reload() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            forgetStock() // a Stock list read while Room was empty must be read again (the SR app does this on every Stock tap)
            try { deviceRuntime?.refreshDayConfig(userId, db) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            _bundleEpoch.value = _bundleEpoch.value + 1
        }
        return outcome
    }

    private val _bundleEpoch = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Counts the bundles applied to Room since this day opened; a screen that read Room once keys its read on it (Stock does). */
    val bundleEpoch: kotlinx.coroutines.flow.StateFlow<Int> = _bundleEpoch

    /** Re-reads the bundle stamp the records carry; cheap, called when the day opens and after a bundle change. */
    suspend fun reload() {
        loadPrintConfig()
        refreshZone()
        bundleVersion = reference.bundleVersion()
        configVersion = db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L
        updateBaseMaxM = runCatching { reference.config("cfg.geo.update_base_max_distance_m", iso(clock.nowMs())) }.getOrNull()
            ?.trim('"')?.toIntOrNull()?.takeIf { it in 20..500 } ?: com.aktcl.aron.feature.outlet.OutletRequests.DEFAULT_UPDATE_BASE_MAX_M
        updateBaseMaxMoveM = runCatching { reference.config("cfg.geo.update_base_max_move_m", iso(clock.nowMs())) }.getOrNull()
            ?.trim('"')?.toIntOrNull()?.takeIf { it in 50..20000 } ?: com.aktcl.aron.feature.outlet.OutletRequests.DEFAULT_UPDATE_BASE_MAX_MOVE_M
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
            // N-035 breadcrumbs (D-MDM-BREADCRUMB-01): starts or ends the day's window; a no-op while breadcrumbs are off.
            runCatching { deviceRuntime?.onAttendanceCommitted(e.kind, userId) }
            if (e.kind == "check_in") runCatching { deviceRuntime?.wantEvidenceAtCheckIn() }
            runCatching { scheduler.requestSync(userId, if (e.kind == "check_in") SyncTrigger.WRITE_DEBOUNCE else SyncTrigger.CHECKOUT) }
        },
        routeIdOf = { routeId }, nowIso = { iso(clock.nowMs()) }, dhakaMinutesNow = ::dhakaMinutesNow,
        checkoutEarliestMinutes = { deviceRuntime?.dayConfig?.checkoutEarliestMinutes ?: DayConfig.DEFAULT_CHECKOUT_MINUTES },
        addressResolver = { lat, lng -> addressResolver.displayText(context, lat, lng) },
        // D-NIGHT-CHECKOUT-01: the AMO's supervisor-day Sales Submit, committed on this phone, before check-out.
        salesSubmitted = { daySubmitted() },
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

    private suspend fun stampedFixUuid(photoUuid: String, requestUuid: String): String? {
        val m = media ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            m.store.get(photoUuid)?.takeIf { it.ref?.refClientUuid == requestUuid }?.stamp?.fixClientUuid
        }
    }

    /** True when an outlet request with this client uuid is already stored on the phone (A4: an act that finished before a kill). */
    suspend fun requestStored(uuid: String): Boolean = db.captureDao().outletRequestCount(uuid) > 0

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
            // tso-manual A3 (D-TSO-MAN-31): no control, joint or sale call on a route closed by its zone's Final Submit
            // ([routeLocked]); the visit flow shows the refusal as a failed commit and nothing is stored
            FinalSubmitLock.requireCallOpen(db, v.meta)
            v.geoForcePhotoUuid?.let { claimPhoto(it, "force_sale", "visit", v.clientUuid, f) }
            capture.recordVisitOpen(v, f); runCatching { requestSync() }
        },
        session = visitSession, settings = { GeoSettings.DEFAULT }, nowIso = { iso(clock.nowMs()) },
        nextSequenceNo = { nextSequence++ },
        background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob(parentJob) + kotlinx.coroutines.Dispatchers.Default),
    )

    val outletRequests = OutletRequests(
        committer = { draft -> writer.commitOutletRequest(draft) },
        updateBaseMaxM = { updateBaseMaxM },
        updateBaseMaxMoveM = { updateBaseMaxMoveM },
    )

    /** `cfg.geo.update_base_max_distance_m` (default 100): how far the chosen Update Base point may be from the AMO. */
    @Volatile var updateBaseMaxMoveM: Int = com.aktcl.aron.feature.outlet.OutletRequests.DEFAULT_UPDATE_BASE_MAX_MOVE_M
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

    suspend fun closeVisit(): DayRefusal? = closeLock.withLock {
        // the lock makes a double tap wait; the second run finds the session already closed
        val v = visitSession.current.value ?: return@withLock null
        // tso-manual A3 (D-A3-22): on a route closed by its zone's Final Submit the call ends on the phone (the server would
        // refuse its close day_final_submitted), so leaving a control or joint call never throws; X4a follow-up (R-W-23): the
        // same when the AMO's supervisor-day Sales Submit stands. The answer says why the call ended without a close (the
        // host shows the matching text), or null when a close was queued.
        AmoCallLock.endedBy(capture, visitSession, metaProvider.meta(v.routeId))?.let { routeId = null; return@withLock it }
        val date = businessDate()
        val memo = db.captureDao().memosOn(date).filter { it.visitClientUuid == v.visitUuid }
        val superseded = memo.mapNotNull { it.supersedesClientUuid }.toSet()
        val live = memo.filter { it.clientUuid !in superseded }.maxByOrNull { it.committedAt }
        val audited = db.captureDao().amoRecordsOfFamily(v.visitUuid).isNotEmpty() || db.captureDao().surveyResponsesOf(v.visitUuid).isNotEmpty()
        val why = when (com.aktcl.aron.feature.amo.AmoCallOutcome.of(live?.lineCount, audited)) {
            com.aktcl.aron.feature.amo.AmoCallOutcome.SOLD -> sale.closeVisit(com.aktcl.aron.feature.sale.domain.VisitOutcomeCode.SOLD)
            com.aktcl.aron.feature.amo.AmoCallOutcome.AUDIT_ONLY -> sale.closeVisit(com.aktcl.aron.feature.sale.domain.VisitOutcomeCode.ZERO_SALE)
            com.aktcl.aron.feature.amo.AmoCallOutcome.ABANDONED -> closeAbandoned(v)
        }
        routeId = null
        why
    }

    /**
     * tso-manual X4a fix round (R-W-23, checker low): the control call host's entry guard, as the SR sale host's. When the call
     * in progress no longer takes its records (its route-day closed by the zone's Final Submit, or the AMO's supervisor-day
     * Sales Submit standing), it ends on the phone now with no close written and the answer says why; the host goes to the
     * outlet list and shows the text. Null leaves the call as it is.
     */
    suspend fun endCallIfClosed(): DayRefusal? = closeLock.withLock {
        val v = visitSession.current.value ?: return@withLock null
        AmoCallLock.endedBy(capture, visitSession, metaProvider.meta(v.routeId))?.also { routeId = null }
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
        refreshLock()
    }

    // ---- tso-manual A3: the route-day lock after the zone's Final Submit (D-TSO-MAN-31)
    private val _dayLock = MutableStateFlow<AmoDayLock?>(null)

    /** The zone routes closed by Final Submit today and the day's records that met the lock on the server; null when neither applies. */
    val dayLock: StateFlow<AmoDayLock?> = _dayLock.asStateFlow()

    /**
     * Re-reads which of the zone's routes are closed by Final Submit today (held day state: bundle or delta, which the push
     * `day_final_submitted` pulls; or the last sync answer) and the acks of records that met the lock. Local reads only.
     * A new call on a closed route is refused by the visit flow's committer ([routeLocked]) and a call in progress ends on
     * the phone ([AmoCallLock]); the AMO's routeless records (supervisor day, attendance) stay open.
     */
    suspend fun refreshLock() {
        val date = businessDate()
        // owner decision 2026-11-04: closed routes count (and the banner shows) only while the late-sale policy locks
        // not runCatching: a refreshLock cancelled mid-read (a banner or dialog effect that left composition) must not go on to write the default over the shared lock (D-AMO-CALLS-37)
        val closed = com.aktcl.aron.core.common.orDefaultKeepingCancellation(emptyList()) { FinalSubmitLock.lockedRoutes(db, date, zoneRoutes.keys.sorted(), iso(clock.nowMs())) }
        val acks = com.aktcl.aron.core.common.orDefaultKeepingCancellation(LockAcks(0, 0)) { FinalSubmitLock.metLock(db, date) }
        _dayLock.value = if (closed.isNotEmpty() || acks.any) AmoDayLock(closed, acks) else null
    }

    /**
     * True when the phone locks [routeId]'s route-day today: closed by its zone's Final Submit while the late-sale policy
     * locks (quarantine or reject; owner decision 2026-11-04, [FinalSubmitLock.locks]). A control, joint or sale call on it
     * cannot start then (the visit flow's committer refuses it through [FinalSubmitLock.requireCallOpen], the same rule).
     */
    suspend fun routeLocked(routeId: Long): Boolean = FinalSubmitLock.locks(db, businessDate(), routeId, iso(clock.nowMs()))

    private suspend fun closeAbandoned(v: com.aktcl.aron.feature.outlet.OpenVisit): DayRefusal? {
        // tso-manual X4a (R-W-11): a lock that lands after closeVisit's check ends the call on the phone (AmoLockGate.closeCall);
        // follow-up (R-W-23): so does a refusal of the supervisor day's Sales Submit
        var why: DayRefusal? = null
        val queued = AmoLockGate.closeCall(visitSession, { why = it }) {
            capture.recordVisitClose(
                com.aktcl.aron.core.database.entity.VisitCloseEntity(
                    clientUuid = com.aktcl.aron.core.common.ClientIds.newUuid(), meta = metaProvider.meta(v.routeId), visitClientUuid = v.visitUuid, outcomeCode = "abandoned",
                    callStartedAt = null, callDeclined = false, endedAt = iso(clock.nowMs()), isZeroSale = true,
                ),
            )
        }
        if (queued) runCatching { requestSync() }
        return why
    }

    /** After a relaunch: a committed visit with no close is the call in progress. */
    suspend fun restoreOpenVisit() {
        if (visitSession.current.value != null) return
        // tso-manual A3: a call on a route closed by Final Submit is not restored (it cannot go on; D-A3-22)
        val last = FinalSubmitLock.callToRestore(db, businessDate(), iso(clock.nowMs())) { it.geoAction != "blocked" && it.visitKind.startsWith("amo_") } ?: return
        // tso-manual X4a follow-up (R-W-23): nor a call whose close the supervisor day's Sales Submit would refuse
        if (runCatching { capture.dayRefusal(last.meta, "visit_close") }.getOrNull() != null) return
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

    /** The stock slip of one Save, from the stored rows only (quantities as counted: packets, boxes, dozens, with their unit word). */
    suspend fun stockSlip(movements: List<com.aktcl.aron.core.database.entity.StockMovementEntity>): com.aktcl.aron.core.printing.doc.StockSlipPrint {
        val skus = reference.skus().associateBy { it.skuId }
        return com.aktcl.aron.core.printing.doc.StockSlipPrint(
            printedAtEpochMs = clock.nowMs(), sr = userName, route = routeName(), distributor = "",
            lines = movements.sortedBy { it.skuId }.map { com.aktcl.aron.core.printing.doc.StockSlipLine(skus[it.skuId]?.categoryCode.orEmpty(), skus[it.skuId]?.shortName ?: it.skuId.toString(), it.qtyBase, it.qtyEntered, com.aktcl.aron.feature.memo.domain.PrintMapping.unitKeyOf(skus[it.skuId]?.categoryCode.orEmpty(), it.unitEntered), com.aktcl.aron.rules.EntryUnits.baseLabelKey(skus[it.skuId]?.categoryCode.orEmpty())) },
        )
    }

    /** True while any stock row of today is not on a printed slip (Sales Submit warns on this). */
    suspend fun slipNotPrinted(): Boolean = db.captureDao().stockOn(businessDate()).any { !it.slipPrinted }

    /** Outlives every screen: a print and its readable-question answer must finish even if the SR leaves Stock. */
    private val printScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob(parentJob) + kotlinx.coroutines.Dispatchers.Default)
    private val _stockAttempt = MutableStateFlow<com.aktcl.aron.core.printing.flow.PrintAttempt?>(null)
    val stockAttempt: StateFlow<com.aktcl.aron.core.printing.flow.PrintAttempt?> = _stockAttempt.asStateFlow()

    /** Today's stock rows not yet on a printed slip, read from Room (survives a kill and relaunch). */
    suspend fun unprintedStock() = db.captureDao().stockOn(businessDate()).filter { !it.slipPrinted }

    /**
     * The Save the Print tap would print next: the oldest unprinted one. tso-manual X4a fix round (R-W-22, "no stock slip
     * prints after the lock"): a Save on a route-day the zone's Final Submit closed (a Save made in a call on that route) is
     * left out, also one stored before the lock; it stays unprinted and prints once the day reopens. A routeless Save (outside
     * a call) never meets the lock. Empty when nothing may print.
     */
    suspend fun printableStockSave(): List<com.aktcl.aron.core.database.entity.StockMovementEntity> {
        val closed = HashMap<Pair<Long?, String>, Boolean>()
        val open = unprintedStock().filter { r ->
            !closed.getOrPut(r.meta.routeId to r.meta.businessDate) { capture.dayRefusal(r.meta, "stock_movement") != null }
        }
        return StockSlips.oldestUnprintedSave(open)
    }

    /** True when the Print tap has a slip it may print ([printableStockSave]); the Stock screen draws Print only then. */
    suspend fun stockSlipPrintable(): Boolean = printableStockSave().isNotEmpty()

    /** Prints one slip for every unprinted stock row of today; the confirmation question is answered by [answerStockPrint]. */
    fun printUnprintedStock() {
        printScope.launch {
            if (_stockAttempt.value != null) return@launch
            // One slip covers exactly one Save (the ledger flips the rows of the named row's Save); the next Save prints on the next tap.
            // X4a fix round (R-W-22): never the slip of a Save on a route-day closed by Final Submit, even if the button was drawn.
            val save = printableStockSave()
            if (save.isEmpty()) return@launch
            _stockAttempt.value = com.aktcl.aron.feature.sale.domain.guardedPrint { printing.printStockSlip(StockSlips.slipUuid(save), stockSlip(save)) }
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
        printScope.launch { if (_summaryAttempt.value == null) _summaryAttempt.value = com.aktcl.aron.feature.sale.domain.guardedPrint { sale.printSummary(b) } }
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
    /**
     * One Save of the Stock screen (F-SR-013/014) with the open call's route, or none outside a call: tso-manual X4a (R-W-22),
     * a Save the zone's Final Submit refuses stores and queues nothing and says why (the screen shows the lock text; no slip
     * is offered for it). Outside a call the movement has no route and never meets the lock, as on the server.
     */
    suspend fun saveStock(load: StockLoad): com.aktcl.aron.feature.stock.StockSaveResult =
        com.aktcl.aron.feature.stock.StockSaving.save(load, ::currentMs, stockMeta()) { capture.recordStock(it) }

    /**
     * tso-manual X4a fix round (checker low): the meta of the AMO's stock movement: the open call's route, none outside a
     * call. Never [routeId] alone: the outlet pick sets it before the fix is read, and backing out of the check step leaves
     * it set with no call open.
     */
    internal fun stockMeta(): CaptureMeta = metaProvider.meta(0L).copy(routeId = visitSession.current.value?.routeId?.takeIf { it != 0L })

    suspend fun stockLoad(): StockLoad = stock ?: run {
        val loaded = db.captureDao().stockBalanceOn(businessDate()).associate { it.skuId to it.qtyBase }
        StockLoad(reference.skus(), loaded, guardStore = PrefsGuardStore(context, userId)).also { stock = it }
    }
    fun forgetStock() { stock = null }

    // ---- AMO records (amo_record): assessments, price checks, verifications, tasks, reviews
    private fun newUuid() = com.aktcl.aron.core.common.ClientIds.newUuid()

    suspend fun saveJointCall(visitUuid: String, rubric: com.aktcl.aron.feature.amo.JointRubric, state: com.aktcl.aron.feature.amo.JointState, assessedUserId: Long?, defaultStar: Int? = 1) = writer.saveJointCall(visitUuid, rubric, state, assessedUserId, defaultStar)

    /** `cfg.amo.joint_call_default_star`: 1 preselects star 1 on every item (the vendor's behaviour, default); "none" makes the AMO rate each item. */
    suspend fun jointDefaultStar(): Int? = com.aktcl.aron.feature.amo.jointDefaultStar(runCatching { reference.config("cfg.amo.joint_call_default_star", iso(clock.nowMs())) }.getOrNull())

    /** `cfg.amo.price_compliance_tile` (default false): the Price compliance tile of the Control Call menu (D-AMO-MAN-14). */
    suspend fun priceComplianceTile(): Boolean = com.aktcl.aron.core.common.orNullKeepingCancellation { reference.config("cfg.amo.price_compliance_tile", iso(clock.nowMs())) }?.trim('"')?.equals("true", ignoreCase = true) == true

    /** Outlets with a call (any AMO visit kind) opened on today's business date: the brown badge of the retailer list. Local read. */
    suspend fun calledTodayOutletIds(): Set<Long> = db.captureDao().visitsOn(businessDate()).map { it.outletId }.toSet()

    /**
     * Previous sale data (24, 26): every sale to the outlet on [date], any SR, from the server (a kept copy while offline); with neither
     * (offline and never read, or the server refuses) this AMO's own live memos from this phone. The sale itself never waits on this.
     */
    suspend fun saleHistory(outletId: Long, date: String): com.aktcl.aron.feature.amo.SaleHistory {
        val fetch = runCatching { api.salesByDate(outletId, date) }.getOrDefault(AmoFetch.Unavailable)
        when (fetch) {
            is AmoFetch.Fresh -> com.aktcl.aron.feature.amo.PreviousSales.parseServer(fetch.json, date)?.let { return com.aktcl.aron.feature.amo.PreviousSales.withCategories(it, skuCategoryMap()) }
            is AmoFetch.Cached -> com.aktcl.aron.feature.amo.PreviousSales.parseServer(fetch.json, date, com.aktcl.aron.feature.amo.SaleHistorySource.KEPT_COPY)?.let { return com.aktcl.aron.feature.amo.PreviousSales.withCategories(it, skuCategoryMap()) }
            else -> Unit
        }
        val memos = runCatching { com.aktcl.aron.feature.memo.ui.RoomMemoStore(db).memos(date) }.getOrDefault(emptyList()).filter { it.outletId == outletId }
            .map { m -> com.aktcl.aron.feature.amo.LocalMemo(m.memoUuid, m.supersedesUuid, m.lines.map { com.aktcl.aron.feature.amo.PreviousSales.LocalMemoLine(it.skuId, it.qtyBase, it.grossMtk) }) }
        return com.aktcl.aron.feature.amo.PreviousSales.fromLocal(date, memos, runCatching { skuInfo() }.getOrDefault(emptyMap()))
    }

    /** The open call's retailer ("name (code-phone-cluster)") and route name for the assessment header cards. */
    suspend fun callHeader(): Pair<String?, String?> {
        val v = visitSession.current.value ?: return null to null
        val o = db.referenceDao().outlet(v.outletId)
        val r = routes().firstOrNull { it.route.routeId == v.routeId }?.route
        return o?.let { com.aktcl.aron.feature.outlet.OutletPicker.label(it) } to (r?.displayLabel ?: r?.name)
    }

    /** The open call's outlet for the sale screens (screenshots 24, 25): the retailer pill label, the name and code (digits are localised by the screen), the route name and the cluster. */
    data class SaleHeader(val pill: String, val name: String, val code: String, val route: String?, val cluster: String?)
    suspend fun saleHeader(): SaleHeader? {
        val v = visitSession.current.value ?: return null
        val o = db.referenceDao().outlet(v.outletId) ?: return null
        val r = routes().firstOrNull { it.route.routeId == v.routeId }?.route
        return SaleHeader(
            com.aktcl.aron.feature.outlet.OutletPicker.label(o), o.name, o.code,
            (r?.displayLabel ?: r?.name)?.takeIf { it.isNotBlank() }, o.clusterName.takeIf { it.isNotBlank() },
        )
    }

    /** A memo's outlet label and route name for the memo detail header (screenshots 42, 43); null parts when the outlet is not on the phone. */
    suspend fun memoHeader(outletId: Long): Pair<String?, String?> {
        val o = db.referenceDao().outlet(outletId) ?: return null to null
        val r = routes().firstOrNull { it.route.routeId == o.routeId }?.route
        return com.aktcl.aron.feature.outlet.OutletPicker.label(o) to (r?.displayLabel ?: r?.name)?.takeIf { it.isNotBlank() }
    }

    /** The AMO's record writes (joint call, assessment, survey, price, verification, exceptions, tasks), testable on their own. */
    private val writer: AmoRecordWriter by lazy {
        AmoRecordWriter(
            db, capture, metaProvider, { visitSession.current.value?.routeId ?: 0L }, ::newUuid, { runCatching { requestSync() } },
            { purpose -> fixSource.readFix(purpose) }, { photo, purpose, refType, refUuid, fix -> claimPhoto(photo, purpose, refType, refUuid, fix) },
            { photo, requestUuid -> stampedFixUuid(photo, requestUuid) },
        )
    }

    // ---- Verify SR outlet requests (F-AMO-022/023/024)

    suspend fun correctStock(skuId: Long, right: Long, reasonCode: String, unit: String): Boolean = writer.correctStock(businessDate(), skuId, right, reasonCode, unit)

    suspend fun decidedRequests(): Set<String> = writer.decidedRequests()

    /** Pending requests of the SRs in the zone (bundle), minus those decided on this phone. */
    suspend fun pendingVerifications(): List<com.aktcl.aron.feature.amo.PendingRequest> =
        com.aktcl.aron.feature.amo.OutletVerify.pending(runCatching { reference.section("supervisor") }.getOrNull(), userId, decidedRequests())

    suspend fun subChannels(): List<com.aktcl.aron.feature.amo.SubChannel> =
        com.aktcl.aron.feature.amo.OutletVerify.subChannels(runCatching { reference.section("code_lists") }.getOrNull())

    suspend fun decideRequest(
        recordUuid: String, requestUuid: String, verify: Boolean, subChannelId: Long?, geoClass: String?, note: String?, photoUuid: String?,
        capturedFix: com.aktcl.aron.feature.outlet.FixReading? = null, routeId: Long? = null, clusterId: Long? = null,
    ): Boolean = writer.decideRequest(recordUuid, requestUuid, verify, subChannelId, geoClass, note, photoUuid, capturedFix, routeId, clusterId)

    suspend fun refusedDecisionCode(requestUuid: String): String? = writer.refusedDecisionCode(requestUuid)
    suspend fun refusedDecision(requestUuid: String) = writer.refusedDecision(requestUuid)

    /** The verification form's save: see [decideFromForm]. */
    suspend fun decideFromForm(draftKey: String, requestUuid: String, currentRecordUuid: String, c: VerifyChoice) = writer.decideFromForm(drafts, draftKey, requestUuid, currentRecordUuid, c)

    // ---- Exceptions (F-AMO-038): risk signals of the zone, reviewed offline

    private suspend fun decidedSignals(): Set<Long> = writer.decidedSignals()

    /** Today's open signals of the zone that this phone has not reviewed yet (local read). */
    suspend fun openSignals(): List<com.aktcl.aron.feature.amo.RiskSignalRow> =
        com.aktcl.aron.feature.amo.Exceptions.open(runCatching { reference.section("supervisor") }.getOrNull(), businessDate(), decidedSignals())

    suspend fun reviewSignal(recordUuid: String, signalId: Long, action: String, note: String?): Boolean = writer.reviewSignal(recordUuid, signalId, action, note)

    /** `cfg.map.provider` and `cfg.map.tile_cache_mb` (defaults when the registry has none). */
    suspend fun mapSettings(): com.aktcl.aron.core.map.MapSettings {
        suspend fun cfg(k: String) = runCatching { reference.config(k, iso(clock.nowMs())) }.getOrNull()?.trim('"')
        return com.aktcl.aron.core.map.MapSettings.of(cfg("cfg.map.provider"), cfg("cfg.map.tile_cache_mb")?.toIntOrNull())
    }

    /** Every SKU of the bundle with its category, withdrawn ones too (a previous-sale line of a SKU since withdrawn still has its category). */
    suspend fun skuCategoryMap(): Map<Long, String> = runCatching { db.referenceDao().skuCategories().associate { it.skuId to it.categoryCode } }.getOrDefault(emptyMap())

    suspend fun skuInfo(): Map<Long, com.aktcl.aron.feature.amo.SkuInfo> =
        db.referenceDao().activeSkus().associate { it.skuId to com.aktcl.aron.feature.amo.SkuInfo(it.name, it.nameBn, it.categoryCode, com.aktcl.aron.rules.EntryUnits.resolve(it.baseUnit, it.basePerPack, it.entryUnitDefault)) }

    // ---- Task Delegation (F-AMO-018/019)

    suspend fun teamSrs(): List<com.aktcl.aron.feature.amo.TeamSr> = com.aktcl.aron.feature.amo.TaskDelegation.team(runCatching { reference.section("supervisor") }.getOrNull())

    suspend fun taskTypes(): List<com.aktcl.aron.feature.amo.TaskType> = com.aktcl.aron.feature.amo.TaskDelegation.types(runCatching { reference.section("code_lists") }.getOrNull())

    /** The tasks this AMO assigned on this phone (queued or sent), as list rows; an acked one stays until the server list has it. */
    suspend fun localTasks(): List<com.aktcl.aron.feature.amo.AssignedTask> = db.captureDao().amoRecordsOfType("task").mapNotNull { r ->
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(r.payloadJson) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val state = db.outboxDao().byClientUuid(r.clientUuid)?.state
        val refused = state == com.aktcl.aron.core.database.entity.OutboxState.REJECTED || state == com.aktcl.aron.core.database.entity.OutboxState.QUARANTINED
        com.aktcl.aron.feature.amo.AssignedTask(
            r.clientUuid, s("task_type_code").orEmpty(), s("title").orEmpty(), s("outlet_id")?.toLongOrNull(), s("assignee_user_id")?.toLongOrNull() ?: return@mapNotNull null,
            "ongoing", s("due_date"), null, null, r.meta.capturedAt, description = s("description")?.takeIf { it.isNotBlank() },
            waiting = state == com.aktcl.aron.core.database.entity.OutboxState.PENDING || state == com.aktcl.aron.core.database.entity.OutboxState.IN_FLIGHT, rejected = refused,
        )
    }

    suspend fun assignTask(recordUuid: String, typeCode: String, assigneeId: Long, outletId: Long?, title: String, description: String?, dueDate: String?): Boolean =
        writer.assignTask(recordUuid, typeCode, assigneeId, outletId, title, description, dueDate)

    // ---- Survey (F-AMO-043): the SR's POSM survey component without points

    /** The bundle's survey with its questions, or null when there is none or this visit already answered it. */
    suspend fun pendingSurvey(visitUuid: String): Pair<com.aktcl.aron.core.database.entity.SurveyEntity, List<com.aktcl.aron.core.database.entity.SurveyQuestionEntity>>? {
        if (db.captureDao().surveyResponsesOf(visitUuid).isNotEmpty()) return null
        val survey = db.referenceDao().surveys().firstOrNull() ?: return null
        val questions = db.referenceDao().surveyQuestions(survey.surveyId)
        return if (questions.isEmpty()) null else survey to questions
    }

    suspend fun saveSurvey(visitUuid: String, survey: com.aktcl.aron.core.database.entity.SurveyEntity, rows: List<com.aktcl.aron.feature.outlet.SurveyResponseRow>) = writer.saveSurvey(visitUuid, survey, rows)

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

    suspend fun savePriceCompliance(visitUuid: String, rows: List<com.aktcl.aron.feature.amo.PriceRow>, typed: Map<Long, String>, toleranceBp: Int) = writer.savePriceCompliance(visitUuid, rows, typed, toleranceBp)

    /** SKUs already recorded for the open call, so the screen can show them as done. */
    suspend fun pricedSkus(visitUuid: String): Set<Long> = db.captureDao().amoRecordsOfFamily(visitUuid).filter { it.recordType == "price_compliance_check" }
        .mapNotNull { runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it.payloadJson) as kotlinx.serialization.json.JsonObject)["sku_id"].toString().toLong() }.getOrNull() }.toSet()

    suspend fun saveAssessment(visitUuid: String, brands: List<com.aktcl.aron.feature.amo.AmoBrand>, state: com.aktcl.aron.feature.amo.PerfState) = writer.saveAssessment(visitUuid, brands, state)
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

/**
 * The AMO's view of the Final Submit lock (tso-manual A3, D-TSO-MAN-31): the zone routes whose route-day is closed today
 * ([closedRouteIds], in route id order) and the day's records the server held for review or refused ([acks]).
 */
data class AmoDayLock(val closedRouteIds: List<Long>, val acks: LockAcks)

/**
 * tso-manual A3 (D-TSO-MAN-31, D-A3-22): the AMO's call in progress on a route closed by its zone's Final Submit ends on the
 * phone: the server refuses a `visit_close` captured after the lock, so none is queued and nothing throws. Without the day
 * object, so tests run it on a plain database.
 */
internal object AmoCallLock {
    /**
     * Ends the open call on the phone when its close with [meta] would be refused (no close queued) and says why: the zone's
     * Final Submit closed its route-day, or, X4a follow-up (R-W-23), the AMO's supervisor-day Sales Submit stands (the rule
     * CaptureRepository.recordVisitClose applies). Null leaves the call as it was. X4a fix round (checker low): judged by
     * [capture], the day's own repository on trusted time ([AmoDay.capture]), so the guard reads the late-sale policy at the
     * same instant the write will (never the device's wall clock).
     */
    suspend fun endedBy(capture: CaptureRepository, session: VisitSession, meta: CaptureMeta): DayRefusal? {
        if (session.current.value == null) return null
        val why = runCatching { capture.dayRefusal(meta, "visit_close") }.getOrNull() ?: return null
        session.close()
        return why
    }

    /** True when [endedBy] ended the call on the phone. */
    suspend fun endsOnPhone(capture: CaptureRepository, session: VisitSession, meta: CaptureMeta): Boolean = endedBy(capture, session, meta) != null
}

/**
 * The banner of the AMO day after a Final Submit in the zone (D-TSO-MAN-31): how many routes are closed (no new call; sales, edits and
 * collections on them are not accepted; printing still works) and how many records met the lock. It refreshes itself when
 * a push pull lands, only while on screen (no timer). Nothing shows while every route is open.
 */
@androidx.compose.runtime.Composable
fun AmoDayLockBanner(day: AmoDay, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val lock = day.dayLock.collectAsState().value
    androidx.compose.runtime.LaunchedEffect(day) {
        day.refreshLock()
        com.aktcl.aron.core.sync.push.PushRuntime.pulled.collect { day.refreshLock() }
    }
    AmoDayLockBannerContent(lock, modifier)
}

/**
 * tso-manual A3 (ask 1): the lock banner for the AMO Home's banner list (`AmoApp(banners = ...)`): one Warning banner with
 * the title and the lines of [AmoDayLockBanner], refreshed the same way; empty while every route is open or before the day exists.
 */
@androidx.compose.runtime.Composable
fun amoDayLockBanners(day: AmoDay?): List<com.aktcl.aron.feature.home.RoleBanner> {
    val d = day ?: return emptyList()
    val lock = d.dayLock.collectAsState().value
    androidx.compose.runtime.LaunchedEffect(d) {
        d.refreshLock()
        com.aktcl.aron.core.sync.push.PushRuntime.pulled.collect { d.refreshLock() }
    }
    return AmoDayLockWords.banners(amoDayLockText(lock)) // tso-manual X4a: the same words, checked by AmoDayLockWordsTest
}

/** The words of [lock] as one text: the title (when a route is closed), then each line; null when nothing applies. */
@androidx.compose.runtime.Composable
fun amoDayLockText(lock: AmoDayLock?): String? =
    AmoDayLockWords.text(lock, androidx.compose.ui.platform.LocalContext.current.resources, com.aktcl.aron.core.ui.LocalAppLanguage.current)

@androidx.compose.runtime.Composable
private fun amoLockLines(l: AmoDayLock): List<String> = buildList {
    val closed = l.closedRouteIds.size
    if (closed > 0) add(androidx.compose.ui.res.stringResource(R.string.amo_lock_body, com.aktcl.aron.core.ui.localizedNumber(closed.toLong())))
    if (l.acks.held > 0) add(androidx.compose.ui.res.stringResource(R.string.amo_lock_held, com.aktcl.aron.core.ui.localizedNumber(l.acks.held.toLong())))
    if (l.acks.refused > 0) add(androidx.compose.ui.res.stringResource(R.string.amo_lock_refused, com.aktcl.aron.core.ui.localizedNumber(l.acks.refused.toLong())))
}

/** What [AmoDayLockBanner] draws for [lock] (null: nothing). */
@androidx.compose.runtime.Composable
fun AmoDayLockBannerContent(lock: AmoDayLock?, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val l = lock ?: return
    val closed = l.closedRouteIds.size
    val body = amoLockLines(l).joinToString(separator = AMO_LINE_BREAK)
    com.aktcl.aron.core.ui.AronBanner(
        body, modifier.then(androidx.compose.ui.Modifier.testTag(AmoDayLockTags.BANNER)), kind = com.aktcl.aron.core.ui.BannerKind.Warning,
        title = if (closed > 0) androidx.compose.ui.res.stringResource(R.string.amo_lock_title) else null,
    )
}

object AmoDayLockTags { const val BANNER = "amo_day_lock_banner" }

private const val AMO_LINE_BREAK = "\n"

