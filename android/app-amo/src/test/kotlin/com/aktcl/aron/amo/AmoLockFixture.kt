package com.aktcl.aron.amo

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * tso-manual X4a (fix round, checker high 1 and medium "wiring"): a real [AmoDay], and so the real [AmoSaleKit] with the
 * wiring the screens use, on an in-memory database. The AMO is outside a call unless a test opens one (no route of its
 * own). Nothing reaches the network; the location source answers at the outlet (counted, and [duringFix] runs while the fix
 * is read); no printer is saved. The business date is the day's own (trusted clock, the wall clock here).
 */
internal class AmoLockFixture(context: Context) {
    val db: AronDatabase = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val dir = File(context.cacheDir, "x4a-amo-day-" + ClientIds.newUuid()).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob())
    val route = 10231L
    val other = 10232L

    /** Location reads of this day (a call refused before its fix reads none). */
    @Volatile var fixReads = 0

    /** Runs inside each location read: a test lands the Final Submit lock here ("the lock arrives while the fix is read"). */
    @Volatile var duringFix: () -> Unit = {}

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
                    RawLocation(LAT, LNG, 8.0, provider = "fused", timeMs = System.currentTimeMillis(), elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(), isMock = false),
                )
            }
        },
        object : LocationAccess { override fun state(requirePrecise: Boolean) = LocationAccessState.OK },
        { FixDeviceState(deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false) },
        components.trustedClock,
    )

    private val printerManager = PrinterManager({ error("no printer in this test") }, object : SavedPrinterStore {
        override fun load(): SavedPrinter? = null
        override fun save(printer: SavedPrinter?) = Unit
    }, scope)

    val day = AmoDay(9L, context, db, components, SyncScheduler.None, fixManager, printerManager, "amo", memoNumbering = MemoNumbering("amo334001", 0, 500))

    val date: String get() = day.businessDate()

    fun meta(routeId: Long? = route): CaptureMeta = day.metaProvider.meta(routeId ?: 0L)

    fun close() {
        scope.cancel()
        db.close()
        dir.deleteRecursively()
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
        val vm = day.sale.newSaleViewModel()
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

    companion object {
        const val LAT = 23.79
        const val LNG = 90.40
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""

        fun await(what: String, cond: () -> Boolean) = runBlocking {
            assertTrue(what, withTimeoutOrNull(10_000) { while (!cond()) delay(10); true } == true)
        }
    }
}
