package com.aktcl.aron.sr

import android.content.Context
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
import org.junit.Assert.assertTrue
import java.io.File

/**
 * tso-manual X4a (fix round, checker medium "wiring"): a real [SrDay], and so the real [SrSaleKit] with the wiring the
 * screens use, on an in-memory database. Nothing reaches the network: the session's API origin is never called, the
 * location source answers at the outlet (counted, and [duringFix] runs while the fix is read, so a test can land the lock
 * then), and no printer is saved. The business date is the day's own (trusted clock, the wall clock here).
 *
 * CI run 41750277921: no work of a test outlives this fixture's database. The day's own scopes, Room's executor and the
 * printer manager belong to [work]; a test makes its view models through [memoViewModel] and [saleViewModel] (never
 * `day.sale.new…ViewModel()` directly) and hands a screen it shows to [own]. [close] lets that work finish, cancels and
 * joins it, drains Room, and only then closes the database; a test restores its exception catchers after [close].
 */
internal class SrLockFixture(
    context: Context, latePolicy: String?, checkoutMinutes: Int? = null,
    /** A transport factory for a SAVED printer (the printer assist tests); null: no printer is saved, as before. */
    printerTransports: com.aktcl.aron.core.printing.bt.PrinterTransportFactory? = null,
) : AutoCloseable {
    val work = TestWork()
    val db: AronDatabase = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries()
        .setQueryExecutor(work.roomExecutor).setTransactionExecutor(work.roomExecutor).build()
    private val dir = File(context.cacheDir, "x4a-sr-day-" + ClientIds.newUuid()).apply { mkdirs() }
    private var closed = false
    val route = 10231L
    val other = 10232L

    /** Location reads of this day (a call refused before its fix reads none). */
    @Volatile var fixReads = 0

    /** Runs inside each location read: a test lands the Final Submit lock here ("the lock arrives while the fix is read"). */
    @Volatile var duringFix: () -> Unit = {}

    private val components = SessionComponents(
        origin = ApiOrigin.parse("https://api.aron.test"), appVersion = "1.0.0+1", client = "app_sr", storageDir = dir,
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
                    RawLocation(LAT, LNG, 8.0, provider = "fused", timeMs = System.currentTimeMillis(), elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(), isMock = false),
                )
            }
        },
        object : LocationAccess { override fun state(requirePrecise: Boolean) = LocationAccessState.OK },
        { FixDeviceState(deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false) },
        components.trustedClock,
    )

    private val printerManager = PrinterManager(printerTransports ?: com.aktcl.aron.core.printing.bt.PrinterTransportFactory { error("no printer in this test") }, object : SavedPrinterStore {
        override fun load(): SavedPrinter? = if (printerTransports != null) SavedPrinter("00:11:22:33:44:55", "MP-58N") else null
        override fun save(printer: SavedPrinter?) = Unit
    }, work.printerScope)

    val day = SrDay(7L, context, db, components, SyncScheduler.None, fixManager, printerManager, "sr", memoNumbering = MemoNumbering("sr334001", 0, 500), parentJob = work.dayJob, checkoutMinutes = checkoutMinutes?.let { m -> { m } })
        .also { it.routeId = route } // reload() would read it from the bundle

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

    fun meta(routeId: Long = route): CaptureMeta = day.metaProvider.meta(routeId)

    /** The memo screen's model on this day ([SrSaleKit.newMemoViewModel]), held by [work]: cleared and joined by [close]. */
    fun memoViewModel() = work.viewModel { day.sale.newMemoViewModel() }

    /** The sale screen's model on this day ([SrSaleKit.newSaleViewModel]), held by [work]: cleared and joined by [close]. */
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

    /** The zone's Final Submit as the phone learns it (the push pulls the delta): the route section's day_state. */
    fun finalSubmitted(routeId: Long = route) = runBlocking {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId",
            """{"route_id":$routeId,"targets":[],"day_state":{"route_id":$routeId,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"${day.iso(day.currentMs())}"}}""")))
    }

    /** tso-manual X4a follow-up: the zone's Final Submit voided (the next delta's day_state is open again): the lock is gone. */
    fun reopened(routeId: Long = route) = runBlocking {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$routeId",
            """{"route_id":$routeId,"targets":[],"day_state":{"route_id":$routeId,"business_date":"$date","state":"open","submit_cycle":0,"submit_voided":false}}""")))
    }

    /** The SR's own Sales Submit of the route-day (no Final Submit). */
    fun salesSubmitted(routeId: Long = route) = runBlocking {
        day.capture.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), meta(routeId), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
    }

    fun outlet(id: Long = 50001, routeId: Long = route) = OutletEntity(
        outletId = id, routeId = routeId, code = "DHK-$id", name = "Rahim Store $id", nameBn = null, nameSortKey = "rahim store $id", ownerName = "x",
        contactNumber = "01712345678", lat = LAT, lng = LNG, locationConfirmed = true, provisionalLat = null, provisionalLng = null,
        clusterId = 1, clusterName = "C", channel = "g", subChannelId = null, geoClass = null, status = "active", priceType = "outlet",
        outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = 1, openDueMtk = 0, openDueAsOf = null,
        programmeFlagsJson = "[]", pendingRequest = false,
    )

    /** A call opened before the lock through the production writer; the returned visit is what the session holds. */
    fun openCall(outletId: Long = 50001, routeId: Long = route, seq: Int = 1): OpenVisit = runBlocking {
        val u = ClientIds.newUuid()
        val m = meta(routeId)
        val f = GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", LAT, LNG, 8.0, provider = "fused", fixTime = m.capturedAt, fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false,
            adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        day.capture.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = m, visitKind = "sr_call", outletId = outletId, openedAt = m.capturedAt, sequenceNo = seq, planned = true,
                fixClientUuid = f.clientUuid, geoVerdict = "in_range", geoDistanceM = 10.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50,
                geoLocationBasis = "master", geoOutletLat = LAT, geoOutletLng = LNG, geoAction = "sale_allowed",
            ),
            f,
        )
        OpenVisit(u, outletId, routeId, "in_range", "sale_allowed", true, false, null, m.capturedAt)
    }

    /** A credit memo of 84 Tk with 50 Tk due on [v] (three dozen matches), stored before the lock; returns its uuid. */
    fun creditMemo(v: OpenVisit): String = runBlocking {
        val m = meta(v.routeId)
        val memo = ClientIds.newUuid()
        day.capture.recordSale(SaleCapture(
            MemoEntity(memo, m, v.visitUuid, v.outletId, "sr334001-x4a-" + memo.take(4), "sale", m.capturedAt, date, "outlet", 84_000, 0, 0, 0, 0, 84_000, 34_000, 50_000, true, null, 1, 0, 0),
            listOf(MemoLineEntity(ClientIds.newUuid(), m, memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
        memo
    }

    /** A zero-money memo with one QC line on [v]: "QC entered for the outlet" for the SRM-B-17 gate. */
    fun qcLineOn(v: OpenVisit) = runBlocking {
        val m = meta(v.routeId)
        val memo = ClientIds.newUuid()
        day.capture.recordSale(SaleCapture(
            MemoEntity(memo, m, v.visitUuid, v.outletId, "sr334001-qc-" + memo.take(4), "sale", m.capturedAt, date, "outlet", 0, 0, 0, 0, 0, 0, 0, 0, false, null, 0, 0, 1),
            emptyList(),
            qcLines = listOf(com.aktcl.aron.core.database.entity.QcLineEntity(ClientIds.newUuid(), m, v.visitUuid, memo, true, 105, "f", "g", 1, 1_000, 1_000)),
        ))
    }

    /** One priced SKU (matches, by the dozen) in the bundle, nothing issued yet (the Stock screen's catalog). */
    fun catalogOnly() = runBlocking {
        db.referenceDao().insertSkus(listOf(SkuEntity(105, "S105", 105, "match", "n105", "Aster105", null, "dozen", 10, "base", null, "1.000", 105, "active", 1)))
        db.referenceDao().insertPrices(listOf(PriceEntity(1, 105, "outlet", 28_000, 1, "2026-09-01", null)))
    }

    /** One priced SKU (matches, by the dozen) with twenty dozen issued today, so the kit's own sale can be drafted. */
    fun stockedCatalog() = runBlocking {
        catalogOnly()
        day.capture.recordStock(listOf(StockMovementEntity(ClientIds.newUuid(), meta(), "issue", 105, 20, "dozen", 1, 20, null, false)))
    }

    /**
     * The kit's own sale on [v] (two dozen), saved after the lock reached the phone: the production Save and Print of
     * [SrSaleKit.newSaleViewModel] stops at SaveFailed. Returns the view model. tso-manual X4a follow-up: [closeDay] is how
     * the route-day closed while the sale was open (default the zone's Final Submit; the SR's own Sales Submit for R-W-23).
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

        /** Polls [cond] until true (10 s hang guard); idles the main looper each poll so Main work posted by the Looper-backed Main runs (D-QA-CI-16). */
        fun await(what: String, cond: () -> Boolean) = runBlocking {
            assertTrue(what, withTimeoutOrNull(10_000) {
                while (true) {
                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
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
