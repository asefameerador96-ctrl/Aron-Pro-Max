package com.aktcl.aron.amo

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.ConfigValueEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.PriceEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.SaleCapture
import com.aktcl.aron.core.geo.FixDeviceState
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.geo.FixPriority
import com.aktcl.aron.core.geo.LocationAccess
import com.aktcl.aron.core.geo.LocationAccessState
import com.aktcl.aron.core.geo.LocationSource
import com.aktcl.aron.core.geo.RawLocation
import com.aktcl.aron.core.geo.SourceResult
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.core.session.PasswordVerifier
import com.aktcl.aron.core.session.SecretCipher
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.sale.ui.SaleViewModel
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertTrue
import java.io.File

/**
 * tso-manual X4a (fix round, checker high 1 and medium "wiring"): a real [AmoDay], and so the real [AmoSaleKit] with the
 * wiring the screens use, on an in-memory database. The AMO is outside a call unless a test opens one (no route of its
 * own). Nothing reaches the network; the location source answers at the outlet (counted, and [duringFix] runs while the fix
 * is read); no printer is saved. The business date is the day's own (trusted clock, the wall clock here).
 *
 * CI run 41750277921: no work of a test outlives this fixture's database. The day's own scopes, Room's executor and the
 * printer manager belong to [work]; a test makes its view models through [memoViewModel] and [saleViewModel] (never
 * `day.sale.new…ViewModel()` directly) and hands a screen it shows to [own]. [close] lets that work finish, cancels and
 * joins it, drains Room, and only then closes the database; a test restores its exception catchers after [close].
 */
internal class AmoLockFixture(context: Context, latePolicy: String?) : AutoCloseable {
    val work = TestWork()
    val db: AronDatabase = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries()
        .setQueryExecutor(work.roomExecutor).setTransactionExecutor(work.roomExecutor).build()
    private val dir = File(context.cacheDir, "x4a-amo-day-" + ClientIds.newUuid()).apply { mkdirs() }
    private var closed = false
    val route = 10231L
    val other = 10232L

    /** Location reads of this day (a call refused before its fix reads none). */
    @Volatile var fixReads = 0

    /** Runs inside each location read: a test lands the Final Submit lock here ("the lock arrives while the fix is read"). */
    @Volatile var duringFix: () -> Unit = {}

    /** Where the phone is, as an offset from the outlet in degrees of latitude (0.0 = at the outlet; 0.01 = about 1.1 km away), and the location switch. */
    @Volatile var phoneOffsetLat = 0.0
    @Volatile var locationAccess = LocationAccessState.OK

    private val components = SessionComponents(
        origin = ApiOrigin.parse("https://api.aron.test"), appVersion = "1.0.0+1", client = "app_amo", storageDir = dir,
        cipher = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(blob: ByteArray) = blob
        },
        verifier = object : PasswordVerifier {
            override fun create(password: String) = password
            override fun matches(password: String, encoded: String) = password == encoded
        },
    )

    private val fixManager = FixManager(
        object : LocationSource {
            override suspend fun currentLocation(priority: FixPriority, timeoutMs: Long): SourceResult {
                fixReads++
                duringFix()
                return SourceResult.Located(
                    RawLocation(LAT + phoneOffsetLat, LNG, 8.0, provider = "fused", timeMs = System.currentTimeMillis(), elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(), isMock = false),
                )
            }
        },
        object : LocationAccess { override fun state(requirePrecise: Boolean) = locationAccess },
        { FixDeviceState(deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false) },
        components.trustedClock,
    )

    private val printerManager = PrinterManager({ error("no printer in this test") }, object : SavedPrinterStore {
        override fun load(): SavedPrinter? = null
        override fun save(printer: SavedPrinter?) = Unit
    }, work.printerScope)

    val day = AmoDay(9L, context, db, components, SyncScheduler.None, fixManager, printerManager, "amo", memoNumbering = MemoNumbering("amo334001", 0, 500), parentJob = work.dayJob)

    val date: String get() = day.businessDate()

    /**
     * Owner decision 2026-11-04 (D-TSO-MAN-49): the late-sale policy the bundle carries (`cfg.day.late_sync_after_final_policy`,
     * in the device bundle since V0142); null: the bundle carries no such key. The phone locks a final route-day only under
     * quarantine or reject, so the X4a lock tests name one of them; accept_and_flag and null prove that nothing changes.
     */
    fun setLatePolicy(policy: String?) = latePolicyOn(db, policy)

    /**
     * X4a fix round (checker low): the trusted clock as one server answer sets it ([serverMs] now), so a test can run the
     * phone's own wall clock ahead of or behind the day's trusted time.
     */
    fun trustAt(serverMs: Long) = components.trustedClock.record(com.aktcl.aron.rules.TimeAnchor(0, serverMs, SystemClock.elapsedRealtime()))

    /** X4a fix round: an admin's late-sale policy scheduled from [fromIso] (a bundle's scheduled row), next to the resolved one. */
    fun schedulePolicy(policy: String, fromIso: String) = runBlocking {
        db.referenceDao().insertConfig(listOf(ConfigValueEntity(
            key = FinalSubmitLock.KEY_LATE_POLICY, valueJson = "\"$policy\"", scopeType = "global", scopeId = null, effectiveFrom = fromIso,
            effectiveTo = null, configVersion = 2, requiresAck = false, scheduled = true,
        )))
    }

    init { setLatePolicy(latePolicy) }

    fun meta(routeId: Long? = route): CaptureMeta = day.metaProvider.meta(routeId ?: 0L)

    /** The memo screen's model on this day ([AmoSaleKit.newMemoViewModel]), held by [work]: cleared and joined by [close]. */
    fun memoViewModel() = work.viewModel { day.sale.newMemoViewModel() }

    /** The sale screen's model on this day ([AmoSaleKit.newSaleViewModel]), held by [work]: cleared and joined by [close]. */
    fun saleViewModel() = work.viewModel { day.sale.newSaleViewModel() }

    /** A screen the test shows (its ActivityScenario): destroyed by [close] before anything else stops. */
    fun <T : AutoCloseable> own(screen: T): T = work.own(screen)

    /**
     * Stops the test's work ([TestWork.stop]: what is in flight finishes, then everything is cancelled and joined, and Room is
     * drained), then closes the database. If the work did not stop, the database stays open and the test fails with that.
     */
    override fun close() {
        if (closed) return
        closed = true
        try {
            work.stop()
            db.close()
        } finally {
            work.roomExecutor.shutdown()
            dir.deleteRecursively()
        }
    }

    /** The zone's Final Submit of [routeId] as the phone learns it (the push pulls the delta): the route section's day_state. */
    fun finalSubmitted(routeId: Long = route) = runBlocking {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId",
            """{"route_id":$routeId,"targets":[],"day_state":{"route_id":$routeId,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"${day.iso(day.currentMs())}"}}""")))
    }

    /** tso-manual X4a follow-up: the zone's Final Submit voided (the next delta's day_state is open again): the lock is gone. */
    fun reopened(routeId: Long = route) = runBlocking {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId",
            """{"route_id":$routeId,"targets":[],"day_state":{"route_id":$routeId,"business_date":"$date","state":"open","submit_cycle":0,"submit_voided":false}}""")))
    }

    /** The AMO's own supervisor-day Sales Submit (no route), as RoomDaySubmitWriter writes it. */
    fun supervisorDaySubmitted() = runBlocking {
        day.capture.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), meta(null).copy(routeId = null), "supervisor_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
    }

    fun outlet(id: Long = 50001, routeId: Long = route) = OutletEntity(
        outletId = id, routeId = routeId, code = "DHK-$id", name = "Rahim Store $id", nameBn = null, nameSortKey = "rahim store $id", ownerName = "x",
        contactNumber = "01712345678", lat = LAT, lng = LNG, locationConfirmed = true, provisionalLat = null, provisionalLng = null,
        clusterId = 1, clusterName = "C", channel = "g", subChannelId = null, geoClass = null, status = "active", priceType = "outlet",
        outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = 1, openDueMtk = 0, openDueAsOf = null,
        programmeFlagsJson = "[]", pendingRequest = false,
    )

    /** The zone's two routes of today with one outlet each in the held bundle, and the day reloaded on them. */
    fun zone() = runBlocking {
        fun r(id: Long) = RouteEntity(id, "R$id", "Route $id", null, 7, "daily", null, 127, id.toInt(), "active", "own", null, true, 1, 1, date, "$date:1")
        db.referenceDao().insertRoutes(listOf(r(route), r(other)))
        db.referenceDao().insertOutlets(listOf(outlet(50001, route), outlet(50002, other)))
        day.refreshZone()
    }

    /** A control call opened through the production writer on [routeId]; the returned visit is what the session would hold. */
    fun controlCall(outletId: Long = 50001, routeId: Long = route, seq: Int = 1): OpenVisit = runBlocking {
        val u = ClientIds.newUuid()
        val m = meta(routeId)
        val f = GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", LAT, LNG, 8.0, provider = "fused", fixTime = m.capturedAt, fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false,
            adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        day.capture.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = m, visitKind = "amo_control_call", outletId = outletId, openedAt = m.capturedAt, sequenceNo = seq, planned = true,
                fixClientUuid = f.clientUuid, geoVerdict = "in_range", geoDistanceM = 10.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50,
                geoLocationBasis = "master", geoOutletLat = LAT, geoOutletLng = LNG, geoAction = "sale_allowed",
            ),
            f,
        )
        OpenVisit(u, outletId, routeId, "in_range", "sale_allowed", true, false, null, m.capturedAt)
    }

    /** A credit memo of 84 Tk with 50 Tk due sold in the call [v] (three dozen matches); returns its uuid. */
    fun creditMemo(v: OpenVisit): String = runBlocking {
        val m = meta(v.routeId)
        val memo = ClientIds.newUuid()
        day.capture.recordSale(SaleCapture(
            MemoEntity(memo, m, v.visitUuid, v.outletId, "amo334001-x4a-" + memo.take(4), "sale", m.capturedAt, date, "outlet", 84_000, 0, 0, 0, 0, 84_000, 34_000, 50_000, true, null, 1, 0, 0),
            listOf(MemoLineEntity(ClientIds.newUuid(), m, memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
        memo
    }

    /** One priced SKU (matches, by the dozen) in the bundle, nothing issued yet (the Stock screen's catalog). */
    fun catalogOnly() = runBlocking {
        db.referenceDao().insertSkus(listOf(SkuEntity(105, "S105", 105, "match", "n105", "Aster105", null, "dozen", 10, "base", null, "1.000", 105, "active", 1)))
        db.referenceDao().insertPrices(listOf(PriceEntity(1, 105, "outlet", 28_000, 1, "2026-09-01", null)))
    }

    /** One priced SKU (matches, by the dozen) with twenty dozen issued to the AMO today, so the kit's own sale can be drafted. */
    fun stockedCatalog() = runBlocking {
        catalogOnly()
        day.capture.recordStock(listOf(StockMovementEntity(ClientIds.newUuid(), meta(null), "issue", 105, 20, "dozen", 1, 20, null, false)))
    }

    /**
     * The kit's own sale in the call [v] (two dozen), saved after the lock reached the phone: SaveFailed, nothing stored.
     * tso-manual X4a follow-up: [closeDay] is how the day closed while the sale was open (default the zone's Final Submit of
     * the call's route; the AMO's supervisor-day Sales Submit for R-W-23).
     */
    fun refusedSale(v: OpenVisit, closeDay: () -> Unit = { finalSubmitted(v.routeId) }): SaleViewModel {
        stockedCatalog()
        val vm = saleViewModel()
        runBlocking { day.sale.prepare() }
        vm.start(day.sale.saleVisit(v))
        await("the sale is drafted") { day.sale.flow.state.value.draft?.visitUuid == v.visitUuid }
        vm.setQuantity(105, 2, QtyUnit.DOZEN)
        await("two dozen on the draft") { day.sale.flow.state.value.draft?.lines?.isNotEmpty() == true }
        closeDay()
        vm.onPrintTapped()
        vm.viewModelScopeForDialogs().launch { vm.saveAndPrint.onSaveAnswer(true) }
        await("the save stops before the print step") { vm.saveAndPrint.step.value == SaveAndPrint.Step.SaveFailed }
        return vm
    }

    /**
     * Owner decision 2026-11-04 (D-TSO-MAN-49): the kit's own sale on [v] (two dozen) with [closeDay] landing while it is
     * open, as [refusedSale], where the lock does not apply (accept_and_flag, or no policy in the bundle): the Save stores
     * the memo with its number and the flow asks to print. Returns the view model.
     */
    fun savedSale(v: OpenVisit, closeDay: () -> Unit = { finalSubmitted(v.routeId) }): SaleViewModel {
        stockedCatalog()
        val vm = saleViewModel()
        runBlocking { day.sale.prepare() }
        vm.start(day.sale.saleVisit(v))
        await("the sale is drafted") { day.sale.flow.state.value.draft?.visitUuid == v.visitUuid }
        vm.setQuantity(105, 2, QtyUnit.DOZEN)
        await("two dozen on the draft") { day.sale.flow.state.value.draft?.lines?.isNotEmpty() == true }
        closeDay()
        vm.onPrintTapped()
        vm.viewModelScopeForDialogs().launch { vm.saveAndPrint.onSaveAnswer(true) }
        await("the sale is saved and asks to print") { vm.saveAndPrint.step.value == SaveAndPrint.Step.AskPrint }
        return vm
    }

    companion object {
        const val LAT = 23.79
        const val LNG = 90.40
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""

        /**
         * Polls [cond] until true (10 s hang guard). With the Looper-backed Main (MainLooperImmediate, D-QA-CI-11) Main work resumed from a Room IO
         * thread is POSTED to the main looper, which a blocked test thread never runs, so each poll idles it first (as TestWork.waitUntil does).
         */
        fun await(what: String, cond: () -> Boolean) = runBlocking {
            assertTrue(what, withTimeoutOrNull(10_000) {
                while (true) {
                    if (Looper.myLooper() == Looper.getMainLooper()) shadowOf(Looper.getMainLooper()).idle()
                    if (cond()) break
                    delay(10)
                }
                true
            } == true)
        }
    }
}

/**
 * Owner decision 2026-11-04 (D-TSO-MAN-49): writes the late-sale policy [policy] into [db]'s held config as the bundle
 * stores it (a global resolved value, JSON text); null removes the key, as a bundle without it.
 */
internal fun latePolicyOn(db: AronDatabase, policy: String?) {
    runBlocking<Unit> {
        db.referenceDao().deleteConfigKey(FinalSubmitLock.KEY_LATE_POLICY)
        if (policy != null) db.referenceDao().insertConfig(listOf(ConfigValueEntity(
            key = FinalSubmitLock.KEY_LATE_POLICY, valueJson = "\"$policy\"", scopeType = "global", scopeId = null, effectiveFrom = null,
            effectiveTo = null, configVersion = 1, requiresAck = false, scheduled = false,
        )))
    }
}
