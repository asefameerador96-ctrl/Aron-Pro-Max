package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.sr.SrLockFixture.Companion.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
 * tso-manual X4a fix round (checker high 2, medium "wiring", low "lockRefused shared"): the SR kit as the screens use it,
 * on a real [SrDay]. Mark Paid is hidden whenever its collection would be refused (the Final Submit lock, and the SR's own
 * Sales Submit) and a tap the lock beats is worded, never a crash; the picker's tap, the visit screen's refused commit,
 * the close, the skip and the edit check meet the lock before anything is read or stored; a refused sale belongs to its
 * own call and the lock text's OK ends that call before the refusal is cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrKitLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        // an exception that leaves a view model's coroutine ends here (on a phone it would kill the process)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
        // owner decision 2026-11-04: the phone locks only under quarantine or reject (default accept_and_flag); this class tests the lock
        f = SrLockFixture(context, latePolicy = "reject")
    }

    @After fun tearDown() {
        // CI run 41750277921: the fixture's work stops (finished, cancelled, joined, Room drained) and the database closes
        // while the catchers still listen; what that work threw fails this test, not a later one
        try {
            f.close()
            assertEquals("nothing escaped by the end of the test", listOf<Throwable>(), crashes.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
            Dispatchers.resetMain()
        }
    }

    private fun collections() = runBlocking { f.db.captureDao().dueCollectionsOn(f.date) }

    @Test fun markPaidIsHiddenAfterTheSrsOwnSalesSubmitAndATapItBeatIsWordedNotACrash() {
        val v = f.openCall()
        val memo = f.creditMemo(v)
        val drawn = f.memoViewModel()
        drawn.open(memo)
        await("Mark Paid is drawn on the open day") { drawn.state.value.canMarkPaid }
        f.salesSubmitted() // the SR submits the day; this memo screen was drawn before
        drawn.markPaid()
        await("the refusal is worded") { drawn.state.value.submitRefused }
        assertEquals("nothing escaped the view model", listOf<Throwable>(), crashes.toList())
        assertTrue("no collection stored", collections().isEmpty())
        assertFalse(drawn.state.value.canMarkPaid); assertTrue(drawn.state.value.dayLocked); assertFalse(drawn.state.value.lockRefused)
        assertEquals(50_000, drawn.state.value.remainingDueMtk)
        drawn.dismissLockRefusal()
        assertFalse(drawn.state.value.submitRefused)
        // a memo screen opened after the submit never draws Mark Paid
        val later = f.memoViewModel()
        later.open(memo)
        await("the memo is open") { later.state.value.selected?.memo?.memoUuid == memo }
        assertFalse("Mark Paid hidden after Sales Submit", later.state.value.canMarkPaid)
        assertTrue(later.state.value.dayLocked)
    }

    @Test fun markPaidOnAnOpenDayStoresOneCollectionOnTheVisitsRoute() {
        val v = f.openCall()
        val memo = f.creditMemo(v)
        val vm = f.memoViewModel()
        vm.open(memo)
        await("Mark Paid is drawn") { vm.state.value.canMarkPaid }
        assertFalse(vm.state.value.dayLocked)
        vm.markPaid()
        await("collected") { collections().size == 1 }
        val due = collections().single()
        assertEquals(f.route, due.meta.routeId); assertEquals(v.visitUuid, due.visitClientUuid); assertEquals(50_000, due.amountMtk)
        await("the button goes") { !vm.state.value.canMarkPaid }
        assertFalse(vm.state.value.lockRefused); assertFalse(vm.state.value.submitRefused)
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun markPaidIsHiddenOnAFinalDayAndAReprintStillGoesToThePrintLedger() {
        val v = f.openCall()
        val memo = f.creditMemo(v)
        f.finalSubmitted()
        val vm = f.memoViewModel()
        vm.open(memo)
        await("the memo is open") { vm.state.value.selected?.memo?.memoUuid == memo }
        assertFalse("Mark Paid hidden on a final day", vm.state.value.canMarkPaid)
        assertTrue(vm.state.value.dayLocked)
        // the reprint goes through MemoPrintingReprinter and the day's RoomPrintLedger: never refused by the lock; with no
        // printer saved the attempt fails and its print_event is still queued for the server
        vm.reprint()
        await("the reprint ended") { vm.state.value.lastPrint != null }
        assertTrue(vm.state.value.lastPrint.toString(), vm.state.value.lastPrint is PrintAttempt.Failed)
        val prints = runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == "print_event" }
        assertEquals("one print_event queued after the lock", 1, prints.sumOf { it.count })
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun closeVisitOnAFinalRouteEndsTheCallWithNoCloseAndNeverThrows() {
        val v = f.openCall()
        f.day.visitSession.restore(v)
        f.day.sale.callStarted[v.visitUuid] = f.meta().capturedAt
        f.finalSubmitted()
        runBlocking { f.day.sale.closeVisit(VisitOutcomeCode.SOLD) }
        assertNull("the call ended on the phone", f.day.visitSession.current.value)
        assertTrue("no visit_close the server would refuse", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertFalse(f.day.sale.callStarted.containsKey(v.visitUuid))
        // an open route still closes as before
        val open = f.openCall(outletId = 50002, routeId = f.other, seq = 2)
        f.day.visitSession.restore(open)
        runBlocking { f.day.sale.closeVisit(VisitOutcomeCode.SOLD) }
        assertEquals(listOf(open.visitUuid), runBlocking { f.db.captureDao().visitClosesOn(f.date) }.map { it.visitClientUuid })
    }

    @Test fun thePickerRefusesANewCallOnAFinalRouteBeforeReadingAFix() {
        f.finalSubmitted()
        assertTrue(runBlocking { f.day.sale.callLocked(f.route) })
        assertFalse("the picker shows the lock text", runBlocking { f.day.sale.startCall(f.outlet()) })
        assertEquals("no fix read", 0, f.fixReads)
        assertTrue(runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
        // another route of the zone is open: the call starts (the visit flow reads its fix and commits)
        assertFalse(runBlocking { f.day.sale.callLocked(f.other) })
        assertTrue(runBlocking { f.day.sale.startCall(f.outlet(id = 50002, routeId = f.other)) })
        assertEquals(1, f.fixReads)
        assertTrue(f.day.visitFlow.state.value.toString(), f.day.visitFlow.state.value is VisitUiState.Open)
        assertFalse(runBlocking { f.day.sale.refusedByLock(f.day.visitFlow.state.value) })
    }

    @Test fun aLockThatLandsWhileTheFixIsReadIsShownAsTheLockNotARetry() {
        f.duringFix = { f.finalSubmitted() } // the push lands between the tap and the commit
        assertTrue("the tap was before the lock", runBlocking { f.day.sale.startCall(f.outlet()) })
        val st = f.day.visitFlow.state.value
        assertTrue(st.toString(), st is VisitUiState.CommitFailed)
        assertTrue("the visit screen shows the lock text", runBlocking { f.day.sale.refusedByLock(st) })
        assertTrue("nothing stored", runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
        assertNull(f.day.visitSession.current.value)
    }

    @Test fun theEditCheckAndTheSkipMeetTheLockOnTheirRoute() {
        val v = f.openCall()
        val memo = f.creditMemo(v)
        assertFalse(runBlocking { f.day.sale.editLocked(memo) })
        f.finalSubmitted()
        assertTrue("Edit is disabled with the lock text", runBlocking { f.day.sale.editLocked(memo) })
        assertFalse("the skip is refused", runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50003), VisitOutcomeCode.CLOSED) })
        assertTrue(runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.isEmpty())
        assertTrue(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50004, routeId = f.other), VisitOutcomeCode.CLOSED) })
        assertEquals(1, runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.size)
    }

    @Test fun aRefusedSaleBelongsToItsCallAndOkEndsTheCallBeforeTheRefusalClears() {
        val v = f.openCall()
        f.day.visitSession.restore(v)
        f.refusedSale(v)
        assertEquals("the refusal names this call", v.visitUuid, f.day.sale.lockRefused.value)
        assertTrue(runBlocking { f.db.captureDao().memosOn(f.date) }.isEmpty())
        assertNull("no memo number taken", runBlocking { f.db.referenceDao().memoCounter(f.date) })
        var atDone: Pair<Any?, String?>? = null
        runBlocking { f.day.sale.afterSaleRefusal { atDone = f.day.visitSession.current.value to f.day.sale.lockRefused.value } }
        assertEquals("the call had ended and the refusal still stood when Home was shown", null to v.visitUuid, atDone)
        assertNull("cleared last", f.day.sale.lockRefused.value)
        assertTrue(runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }
}
