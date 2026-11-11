package com.aktcl.aron.sr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.RoomPrintLedger
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.flow.MemoPrinting
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.feature.memo.domain.PrintMapping
import com.aktcl.aron.feature.memo.domain.PrintNames
import com.aktcl.aron.feature.memo.ui.RoomMemoStore
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.VisitSession
import com.aktcl.aron.feature.sale.domain.FileDraftStore
import com.aktcl.aron.feature.sale.domain.MemoPrintSource
import com.aktcl.aron.feature.sale.domain.SaleCommitStep
import com.aktcl.aron.feature.sale.domain.SaleCommitter
import com.aktcl.aron.feature.sale.domain.SaleFlow
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.domain.SaleVisit
import com.aktcl.aron.feature.sale.domain.VisitCloser
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.feature.sale.domain.VisitToClose
import com.aktcl.aron.feature.sale.ui.SaleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * tso-manual X4a (R-W-11, A3 ask 11, D-TSO-MAN-31): the zone's Final Submit reaches the SR phone while a call is open. The
 * sale's Save, a skip and the close then meet the lock that CaptureRepository enforces, through the production pieces
 * the SR kit wires (SaleFlow, SaleCommitter with memo numbering, [SrLockGate], [VisitCloser], [SrCallLock]) on a real
 * database. Nothing crashes, no memo the server would refuse is stored or printed, no number is burned, no close or skip
 * is queued, and a relaunch does not take the rep back into the call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrDayLockSaleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    /** CI run 41750277921: the view models, the printer scope and Room's executor of this class stop before the database closes. */
    private val work = TestWork()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries()
        .setQueryExecutor(work.roomExecutor).setTransactionExecutor(work.roomExecutor).build()
    private val date = "2026-10-26"
    private val route = 10231L
    private val now = "2026-10-26T12:00:00.000Z"
    private val repo = CaptureRepository(db) { now }
    private val printerScope = work.printerScope
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        // owner decision 2026-11-04: the phone locks only under quarantine or reject (default accept_and_flag); this class tests the lock
        latePolicyOn(db, "quarantine")
        // an exception that leaves a view model's coroutine ends here (on a phone it would kill the process)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
    }

    @After fun tearDown() {
        // the work stops (finished, cancelled, joined, Room drained) while the catchers still listen; then the database closes
        try {
            work.stop()
            db.close()
            assertEquals("nothing escaped by the end of the test", listOf<Throwable>(), crashes.toList())
        } finally {
            work.roomExecutor.shutdown()
            Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
            Dispatchers.resetMain()
        }
    }

    private fun meta(routeId: Long? = route) = CaptureMeta(
        businessDate = date, capturedAt = now, capturedElapsedMs = 1_000, bootCount = 3, clockOffsetMs = 0L, capturedOffline = true,
        routeId = routeId, bundleVersion = "$date:1", configVersion = 1L,
    )

    /** The zone's Final Submit as the phone learns it: the delta's route section with day_state final_submitted. */
    private fun finalSubmitted() = runBlocking {
        db.referenceDao().insertSections(listOf(BundleSectionEntity("route.$route",
            """{"route_id":$route,"targets":[],"day_state":{"route_id":$route,"business_date":"$date","state":"final_submitted","submit_cycle":0,"submit_voided":false,"final_submitted_at":"2026-10-26T11:30:00.000Z"}}""")))
    }

    private fun openVisit(seq: Int = 1): OpenVisit = runBlocking {
        val u = ClientIds.newUuid()
        val f = GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", 23.79, 90.40, 8.0, provider = "fused", fixTime = now, fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false,
            adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        repo.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = meta(), visitKind = "sr_call", outletId = 50001, openedAt = now, sequenceNo = seq, planned = true,
                fixClientUuid = f.clientUuid, geoVerdict = "in_range", geoDistanceM = 10.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50,
                geoLocationBasis = "master", geoOutletLat = 23.79, geoOutletLng = 90.40, geoAction = "sale_allowed",
            ),
            f,
        )
        OpenVisit(u, 50001, route, "in_range", "sale_allowed", true, false, null, now)
    }

    @Volatile private var loads = 0
    private val names = PrintNames({ "S$it" }, { "O$it" }, "sr", "r")
    private val sku = SaleSku(1, "MAXR20", "cigarette", "MaxR 20S", null, "stick", 20, 7_935, 1, "2026-09-01", entryUnitDefault = "pack")

    /** The SR kit's sale chain on this database: a draft of two packets on [v], and its Save and Print as SrSaleKit wires it. */
    private fun sale(v: OpenVisit, refused: MutableStateFlow<Boolean>): Pair<SaleViewModel, SaleFlow> = runBlocking {
        val committer = SaleCommitter(
            repo, numbers = { _, _ -> error("numbered through MemoNumbering") }, metaSource = { _, rid -> meta(rid) }, nowIso = { now },
            findMemo = { db.captureDao().memo(it) }, numbering = MemoNumbering("sr334001", 0, 500),
        )
        val flow = SaleFlow(FileDraftStore(File(context.cacheDir, "x4a-drafts-" + ClientIds.newUuid())), { _, _ -> listOf(sku) }, committer)
        flow.start(SaleVisit(v.visitUuid, v.outletId, v.routeId, date, "outlet"))
        flow.setQuantity(sku.skuId, 2, sku.entry.unit)
        // as SrSaleKit's printSource: the stored memo through the production memo store; counted, so a refused sale shows none
        val step = SaleCommitStep(flow, MemoPrintSource { uuid -> loads++; PrintMapping.memo(RoomMemoStore(db).memos(date).first { it.memoUuid == uuid }, names) })
        work.viewModel { SaleViewModel(flow, SrLockGate.saveAndPrint(step::invoke, printing()) { refused.value = true }) } to flow
    }

    /** MemoPrinting on this database's ledger; no printer and no renderer: anything that tried to print would fail the test. */
    private fun printing() = MemoPrinting(
        PrinterManager({ error("nothing prints in this test") }, object : SavedPrinterStore {
            override fun load(): SavedPrinter? = null
            override fun save(printer: SavedPrinter?) = Unit
        }, printerScope),
        { error("nothing is rendered in this test") }, RoomPrintLedger(db, { it ?: meta(null) }, { now }), ClientIds::newUuid, { 0L },
    )

    private fun await(what: String, cond: () -> Boolean) = runBlocking {
        assertTrue(what, withTimeoutOrNull(10_000) { while (true) { MainLooperImmediate.idle(); if (cond()) break; delay(10) }; true } == true)
    }

    /** "Yes" on "Save?" from the dialogs' scope (the view model's), as SaveAndPrintDialogs does. */
    private fun saveFromTheDialog(vm: SaleViewModel) {
        vm.onPrintTapped()
        vm.viewModelScopeForDialogs().launch { vm.saveAndPrint.onSaveAnswer(true) }
    }

    @Test fun beforeTheLockTheSaleSavesAndAsksToPrint() {
        val refused = MutableStateFlow(false)
        val (vm, _) = sale(openVisit(), refused)
        saveFromTheDialog(vm)
        await("asks to print") { vm.saveAndPrint.step.value == SaveAndPrint.Step.AskPrint }
        assertFalse(refused.value)
        assertEquals(1, runBlocking { db.captureDao().memosOn(date) }.size)
    }

    @Test fun aSaleSavedAfterTheLockArrivedMidCallIsRefusedAndNothingIsStoredOrPrinted() {
        val v = openVisit()
        val refused = MutableStateFlow(false)
        val (vm, _) = sale(v, refused) // the SR is on Review
        finalSubmitted() // the push pulled the delta while the call was open
        saveFromTheDialog(vm)
        await("the save stops before the print step") { vm.saveAndPrint.step.value == SaveAndPrint.Step.SaveFailed }
        assertTrue("the host shows the lock text", refused.value)
        assertNull(vm.saveAndPrint.memo)
        assertEquals("nothing was loaded for print", 0, loads)
        runBlocking { vm.saveAndPrint.onPrintAnswer(true) } // nothing to print: the print step is never reached
        assertEquals(SaveAndPrint.Step.SaveFailed, vm.saveAndPrint.step.value)
        runBlocking {
            assertTrue("no memo stored", db.captureDao().memosOn(date).isEmpty())
            assertNull("no memo number taken", db.referenceDao().memoCounter(date))
            // only the call's own record is queued: no memo, no lines, no print_event
            assertEquals(mapOf("visit" to 1), db.outboxDao().committedCounts(date).associate { it.recordType to it.count })
        }
        assertEquals(listOf<Throwable>(), crashes.toList())

        // the lock text's OK ends the call on the phone (SrDay.endCallIfDayFinal): no visit_close the server would refuse
        val session = VisitSession().also { it.restore(v) }
        assertTrue(runBlocking { SrCallLock.endsOnPhone(repo, session, meta()) })
        assertNull(session.current.value)
        assertTrue(runBlocking { db.captureDao().visitClosesOn(date) }.isEmpty())
        // a relaunch: the call is not restored (no loop back into a sale that cannot be saved) and a new call is refused
        repeat(2) { assertNull(runBlocking { FinalSubmitLock.callToRestore(db, date) { true } }) }
        assertTrue(runBlocking { FinalSubmitLock.isFinal(db, date, route) })
    }

    @Test fun aCloseWhoseLockLandsDuringTheWriteEndsTheCallOnThePhone() {
        val closer = VisitCloser(repo, { _, rid -> meta(rid) }, { now })
        fun close(s: VisitSession) = runBlocking { SrLockGate.closeCall(s) { o -> closer.close(VisitToClose(o.visitUuid, o.routeId, null), VisitOutcomeCode.SOLD, date) } }
        // before the lock the gate writes the close, as the kit always did
        val first = VisitSession().also { it.restore(openVisit(seq = 1)) }
        assertTrue(close(first))
        assertNull(first.current.value)
        assertEquals(listOf("sold"), runBlocking { db.captureDao().visitClosesOn(date) }.map { it.outcomeCode })
        // the lock lands after the kit's endCallIfDayFinal check and before the write: the call ends, no close, no throw
        val second = VisitSession().also { it.restore(openVisit(seq = 2)) }
        finalSubmitted()
        assertFalse("no close queued", close(second))
        assertNull("the call ended", second.current.value)
        assertEquals(1, runBlocking { db.captureDao().visitClosesOn(date) }.size)
    }

    @Test fun aSkipAfterTheLockIsRefusedAndNothingIsStored() {
        finalSubmitted()
        val queued = runBlocking { SrLockGate.skip { repo.recordVisitSkip(VisitSkipEntity(ClientIds.newUuid(), meta(), 50002, VisitOutcomeCode.CLOSED.wire)) } }
        assertFalse(queued)
        assertTrue(runBlocking { db.captureDao().visitSkipsOn(date) }.isEmpty())
        assertEquals(0, runBlocking { db.outboxDao().unsentCount() })
    }
}
