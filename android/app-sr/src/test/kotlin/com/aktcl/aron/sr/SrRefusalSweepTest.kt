package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.feature.outlet.VisitSession
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.feature.stock.StockLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * tso-manual X4a follow-up (R-W-22, R-W-23), on a real [SrDay] ([SrLockFixture]): the stock Save meets the zone's Final
 * Submit lock (already there, or landing while the rep types) and nothing is queued or printed; and every capture the SR's
 * own Sales Submit refuses (a skip, a new call, a sale, the close, an edit) is named, worded by its host and never thrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrRefusalSweepTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
        f = SrLockFixture(context)
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
        f.close()
        Dispatchers.resetMain()
    }

    private fun stockRows() = runBlocking { f.db.captureDao().stockOn(f.date) }
    private fun queued(type: String) = runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == type }.sumOf { it.count }
    private fun load(): StockLoad = runBlocking { f.day.stockLoad() }
    private fun entered(l: StockLoad) = l.rows.first { it.sku.skuId == 105L }.entered

    /** The Print tap, were it drawn: it prints the oldest unprinted Save, so with none it must not start a print. */
    private fun printTapPrintsNothing() {
        f.day.printUnprintedStock()
        runBlocking { delay(300) } // the print runs on the day's print scope; a slip would set the attempt at once
        assertNull("no stock slip print started", f.day.stockAttempt.value)
        assertEquals("no print_event queued", 0, queued("print_event"))
    }

    // ---------------- R-W-22: stock_movement is locked on the phone ----------------

    @Test fun aStockSaveWithTheLockAlreadyThereIsRefusedNothingQueuedNothingPrinted() {
        f.catalogOnly()
        f.finalSubmitted()
        val l = load()
        l.setEntered(105, 3)
        val r = runBlocking { f.day.saveStock(l) }
        assertEquals(DayRefusal.FINAL_SUBMIT, r.refused)
        assertNull("no saved or failed message: the lock text shows", r.message)
        assertFalse(r.saved)
        assertTrue("nothing stored", stockRows().isEmpty())
        assertEquals("nothing queued", 0, queued("stock_movement"))
        assertFalse("no slip is offered", runBlocking { f.day.slipNotPrinted() })
        printTapPrintsNothing()
        assertEquals("the typed quantity stays on screen", 3L, entered(l))
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun aLockThatArrivesWhileTheRepTypesRefusesTheSaveAndTheSameValuesSaveOnceReopened() {
        f.catalogOnly()
        val l = load()
        l.setEntered(105, 2)
        f.finalSubmitted() // the push lands while the SR is typing the issue
        val r = runBlocking { f.day.saveStock(l) }
        assertEquals(DayRefusal.FINAL_SUBMIT, r.refused)
        assertTrue(stockRows().isEmpty()); assertEquals(0, queued("stock_movement"))
        printTapPrintsNothing()
        // the refusal gave the re-save guard back: once the day reopens the same two dozen save, once
        f.reopened()
        val again = runBlocking { f.day.saveStock(l) }
        assertTrue(again.toString(), again.saved); assertNull(again.refused)
        assertEquals(listOf(2L), stockRows().map { it.qtyEntered })
        assertEquals(1, queued("stock_movement"))
        assertTrue("its slip is offered", runBlocking { f.day.slipNotPrinted() })
    }

    @Test fun onAnOpenDayTheStockSaveIsQueuedAsBefore() {
        f.catalogOnly()
        val l = load()
        l.setEntered(105, 4)
        val r = runBlocking { f.day.saveStock(l) }
        assertTrue(r.saved); assertNull(r.refused)
        assertEquals(f.route, stockRows().single().meta.routeId)
        assertEquals(1, queued("stock_movement"))
        // the SR's own Sales Submit does not refuse stock (D-X4a-13: the server accepts it; the phone always did)
        f.salesSubmitted()
        l.setEntered(105, 1)
        assertTrue(runBlocking { f.day.saveStock(l) }.saved)
        assertEquals(2, queued("stock_movement"))
    }

    // ---------------- R-W-23: no capture refusal escapes a host ----------------

    @Test fun aSkipAfterTheSrsOwnSalesSubmitIsNamedNotThrown() {
        f.salesSubmitted()
        var why: DayRefusal? = null
        assertFalse(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50003), VisitOutcomeCode.CLOSED) { why = it } })
        assertEquals(DayRefusal.SALES_SUBMIT, why)
        assertTrue("nothing stored", runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.isEmpty())
        // the lock's refusal is named as the lock (another route, closed by its zone's Final Submit)
        f.finalSubmitted(f.other)
        assertFalse(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50004, routeId = f.other), VisitOutcomeCode.CLOSED) { why = it } })
        assertEquals(DayRefusal.FINAL_SUBMIT, why)
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun aNewCallAfterTheSalesSubmitIsRefusedByNameBeforeAFix() {
        f.salesSubmitted()
        var why: DayRefusal? = null
        assertFalse(runBlocking { f.day.sale.startCall(f.outlet()) { why = it } })
        assertEquals(DayRefusal.SALES_SUBMIT, why)
        assertEquals("no fix read", 0, f.fixReads)
        assertTrue(runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
        assertFalse("not the Final Submit lock", runBlocking { f.day.sale.callLocked(f.route) })
        // a route of the bundle the SR has not submitted still opens
        assertTrue(runBlocking { f.day.sale.startCall(f.outlet(id = 50002, routeId = f.other)) })
        assertTrue(f.day.visitFlow.state.value.toString(), f.day.visitFlow.state.value is VisitUiState.Open)
    }

    @Test fun aSalesSubmitThatLandsWhileTheFixIsReadIsWordedNotARetry() {
        f.duringFix = { f.salesSubmitted() }
        assertTrue("the tap was before the submit", runBlocking { f.day.sale.startCall(f.outlet()) })
        val st = f.day.visitFlow.state.value
        assertTrue(st.toString(), st is VisitUiState.CommitFailed)
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.sale.commitRefusal(st) })
        assertFalse(runBlocking { f.day.sale.refusedByLock(st) })
        assertTrue("nothing stored", runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
    }

    @Test fun aSaleRefusedByTheSalesSubmitTakesNoNumberAndOkEndsTheCall() {
        val v = f.openCall()
        f.day.visitSession.restore(v)
        f.refusedSale(v) { f.salesSubmitted() } // the SR submitted the day while this call was open, then saved its sale
        assertEquals("the refusal names this call", v.visitUuid, f.day.sale.submitRefused.value)
        assertNull("not the lock", f.day.sale.lockRefused.value)
        assertEquals(DayRefusal.SALES_SUBMIT, f.day.sale.saveRefusal(v.visitUuid))
        assertTrue("no memo", runBlocking { f.db.captureDao().memosOn(f.date) }.isEmpty())
        assertNull("no memo number taken", runBlocking { f.db.referenceDao().memoCounter(f.date) })
        var atDone: Pair<Any?, String?>? = null
        runBlocking { f.day.sale.afterSaleRefusal { atDone = f.day.visitSession.current.value to f.day.sale.submitRefused.value } }
        assertEquals("the call had ended and the refusal still stood when Home was shown", null to v.visitUuid, atDone)
        assertNull("cleared last", f.day.sale.submitRefused.value)
        assertTrue("no close the route-day would refuse", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun leavingACallAfterTheSalesSubmitEndsItOnThePhoneAndSaysWhy() {
        val sold = f.openCall()
        val other = f.openCall(outletId = 50002, seq = 2)
        f.salesSubmitted()
        // the close after Review, and the No-sale outcomes (SrSaleKit.closeVisit)
        f.day.visitSession.restore(sold)
        f.day.sale.callStarted[sold.visitUuid] = f.meta().capturedAt
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.sale.openCallRefusal() })
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.sale.closeVisit(VisitOutcomeCode.SOLD) })
        assertNull("the call ended on the phone", f.day.visitSession.current.value)
        assertFalse(f.day.sale.callStarted.containsKey(sold.visitUuid))
        // the start prompt's No and back (SrDay.closeVisitAbandoned)
        f.day.visitSession.restore(other)
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.closeVisitAbandoned() })
        assertNull(f.day.visitSession.current.value)
        assertTrue("no visit_close written", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun aCallOnASubmittedRouteDayIsNotRestoredAfterARelaunch() {
        val v = f.openCall()
        runBlocking { f.day.restoreOpenVisit() }
        assertEquals("before the submit the call comes back", v.visitUuid, f.day.visitSession.current.value?.visitUuid)
        f.day.visitSession.close() // a kill and relaunch
        f.salesSubmitted()
        runBlocking { f.day.restoreOpenVisit() }
        assertNull("not restored: its sale and close would be refused", f.day.visitSession.current.value)
    }

    @Test fun aSalesSubmitThatLandsDuringTheCloseWriteEndsTheCallWithoutAThrow() {
        val v = f.openCall()
        val session = VisitSession().also { it.restore(v) }
        var why: DayRefusal? = null
        val queued = runBlocking {
            SrLockGate.closeCall(session, { why = it }) { o ->
                f.salesSubmitted() // after the host's check, before the write
                f.day.capture.recordVisitClose(VisitCloseEntity(ClientIds.newUuid(), f.meta(), o.visitUuid, "sold", null, false, f.meta().capturedAt, false))
            }
        }
        assertFalse(queued)
        assertEquals(DayRefusal.SALES_SUBMIT, why)
        assertNull(session.current.value)
        assertTrue(runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
    }

    @Test fun anEditAfterTheSalesSubmitIsRefusedByNameAndTheLockWinsOverIt() {
        val v = f.openCall()
        val memo = f.creditMemo(v)
        assertNull(runBlocking { f.day.sale.editRefusal(memo) })
        f.salesSubmitted()
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.sale.editRefusal(memo) })
        assertFalse("not the lock", runBlocking { f.day.sale.editLocked(memo) })
        f.finalSubmitted()
        assertEquals("the Final Submit lock is named first", DayRefusal.FINAL_SUBMIT, runBlocking { f.day.sale.editRefusal(memo) })
    }
}
