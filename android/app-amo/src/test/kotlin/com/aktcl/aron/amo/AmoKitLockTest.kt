package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.amo.AmoLockFixture.Companion.await
import com.aktcl.aron.feature.amo.AmoVisitKind
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
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
 * tso-manual X4a fix round (checker high 1, medium "wiring", low "lockRefused shared"): the AMO kit as the screens use it,
 * on a real [AmoDay]. Mark Paid on the memo screen outside a call (the AMO has no route then) collects on the route of the
 * call the memo was sold in, is hidden when that route is closed by Final Submit or the supervisor day is submitted, and
 * never crashes; the outlet pick, the check step's refused commit, the closes, the skip and the edit check meet the lock
 * before anything is read or stored; a refused sale belongs to its own call and OK ends that call before it is cleared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmoKitLockTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture
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
        f = AmoLockFixture(app, latePolicy = "reject")
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

    @Test fun markPaidOutsideACallCollectsOnTheCallsRouteAndNeverCrashes() {
        val v = f.controlCall()
        val memo = f.creditMemo(v)
        assertNull("outside a call: the AMO has no route of its own", f.day.routeId)
        val vm = f.memoViewModel()
        vm.open(memo)
        await("Mark Paid is drawn on the open route") { vm.state.value.canMarkPaid }
        assertFalse(vm.state.value.dayLocked)
        vm.markPaid()
        await("collected") { collections().size == 1 }
        assertEquals("nothing escaped the view model", listOf<Throwable>(), crashes.toList())
        val due = collections().single()
        assertEquals("the call's route", f.route, due.meta.routeId); assertEquals(v.visitUuid, due.visitClientUuid)
        assertEquals(1, runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == "due_collection" }.sumOf { it.count })
        await("the button goes") { !vm.state.value.canMarkPaid }
    }

    @Test fun markPaidOutsideACallIsHiddenWhenTheMemosRouteIsFinalAndATapItBeatIsWorded() {
        val v = f.controlCall()
        val memo = f.creditMemo(v)
        val drawn = f.memoViewModel()
        drawn.open(memo)
        await("Mark Paid is drawn before the lock") { drawn.state.value.canMarkPaid }
        f.finalSubmitted() // the push lands while the memo is on screen
        drawn.markPaid()
        await("the lock text shows") { drawn.state.value.lockRefused }
        assertEquals(listOf<Throwable>(), crashes.toList())
        assertTrue("no collection stored", collections().isEmpty())
        assertFalse(drawn.state.value.canMarkPaid); assertTrue(drawn.state.value.dayLocked)
        // opened after the lock: never drawn, though the AMO itself has no route outside a call
        val later = f.memoViewModel()
        later.open(memo)
        await("the memo is open") { later.state.value.selected?.memo?.memoUuid == memo }
        assertFalse("Mark Paid hidden on the final route", later.state.value.canMarkPaid)
        assertTrue(later.state.value.dayLocked)
        // a memo of the zone's other route, still open, keeps Mark Paid
        val onOther = f.creditMemo(f.controlCall(outletId = 50002, routeId = f.other, seq = 2))
        val third = f.memoViewModel()
        third.open(onOther)
        await("Mark Paid on the open route") { third.state.value.canMarkPaid }
    }

    @Test fun theSupervisorDaySubmitHidesMarkPaidAndATapItBeatIsWorded() {
        val memo = f.creditMemo(f.controlCall())
        val drawn = f.memoViewModel()
        drawn.open(memo)
        await("Mark Paid is drawn") { drawn.state.value.canMarkPaid }
        f.supervisorDaySubmitted()
        drawn.markPaid()
        await("the refusal is worded") { drawn.state.value.submitRefused }
        assertEquals(listOf<Throwable>(), crashes.toList())
        assertTrue(collections().isEmpty())
        assertFalse(drawn.state.value.canMarkPaid)
    }

    @Test fun theClosesOnAFinalRouteEndTheCallWithNoCloseAndNeverThrow() {
        val v = f.controlCall()
        val second = f.controlCall(outletId = 50002, seq = 2) // both calls opened before the lock
        f.day.visitSession.restore(v); f.day.routeId = v.routeId
        f.finalSubmitted()
        runBlocking { f.day.sale.closeVisit(VisitOutcomeCode.SOLD) } // the sale's own close
        assertNull(f.day.visitSession.current.value)
        // leaving a control call with nothing in it (AmoDay.closeVisit, its abandoned close) on the same closed route
        f.day.visitSession.restore(second); f.day.routeId = second.routeId
        runBlocking { f.day.closeVisit() }
        assertNull(f.day.visitSession.current.value); assertNull(f.day.routeId)
        assertTrue("no visit_close the server would refuse", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun theOutletPickRefusesACallOnAFinalRouteBeforeReadingAFix() {
        f.finalSubmitted()
        assertFalse("the host shows the lock text", runBlocking { f.day.openCall(f.outlet(), AmoVisitKind.CONTROL_CALL) })
        assertEquals("no fix read", 0, f.fixReads)
        assertNull("the day took no route", f.day.routeId)
        assertTrue(runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
        // the zone's other route is open: the call starts on it
        assertTrue(runBlocking { f.day.openCall(f.outlet(id = 50002, routeId = f.other), AmoVisitKind.CONTROL_CALL) })
        assertEquals(1, f.fixReads); assertEquals(f.other, f.day.routeId)
        assertTrue(f.day.visitFlow.state.value.toString(), f.day.visitFlow.state.value is VisitUiState.Open)
        assertFalse(runBlocking { f.day.refusedByLock(f.day.visitFlow.state.value) })
    }

    @Test fun aLockThatLandsWhileTheFixIsReadIsShownAsTheLockNotARetry() {
        f.duringFix = { f.finalSubmitted() }
        assertTrue("the pick was before the lock", runBlocking { f.day.openCall(f.outlet(), AmoVisitKind.JOINT_CALL) })
        val st = f.day.visitFlow.state.value
        assertTrue(st.toString(), st is VisitUiState.CommitFailed)
        assertTrue("the check step shows the lock text", runBlocking { f.day.refusedByLock(st) })
        assertTrue("nothing stored", runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
    }

    @Test fun theEditCheckAndTheSkipMeetTheLockOnTheirRoute() {
        val memo = f.creditMemo(f.controlCall())
        assertFalse(runBlocking { f.day.sale.editLocked(memo) })
        f.finalSubmitted()
        assertTrue("Edit is disabled with the lock text", runBlocking { f.day.sale.editLocked(memo) })
        assertFalse(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50003), VisitOutcomeCode.CLOSED) })
        assertTrue(runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.isEmpty())
        assertTrue(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50004, routeId = f.other), VisitOutcomeCode.CLOSED) })
    }

    @Test fun aRefusedSaleBelongsToItsCallAndOkEndsTheCallBeforeTheRefusalClears() {
        val v = f.controlCall()
        f.day.visitSession.restore(v); f.day.routeId = v.routeId
        f.refusedSale(v)
        assertEquals("the refusal names this call", v.visitUuid, f.day.sale.lockRefused.value)
        assertTrue(runBlocking { f.db.captureDao().memosOn(f.date) }.isEmpty())
        var ended: Pair<Any?, String?>? = null
        var menu = 0
        runBlocking { f.day.sale.afterSaleRefusal(onCallEnded = { ended = f.day.visitSession.current.value to f.day.sale.lockRefused.value }, onDone = { menu++ }) }
        assertEquals("back to the outlet list with the call ended, the refusal cleared after", null to v.visitUuid, ended)
        assertEquals(0, menu)
        assertNull(f.day.sale.lockRefused.value); assertNull(f.day.routeId)
        assertTrue(runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }
}
