package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.amo.AmoLockFixture.Companion.await
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.feature.amo.AmoVisitKind
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * Owner decision 2026-11-04 (D-TSO-MAN-49; build contract s10 R-W-11, R-W-22, R-W-23): a sale after the TSO's Final Submit
 * is accepted and flagged by the server, never refused, so the AMO phone's day lock is off by default. With the late-sale
 * policy absent from the bundle and with `accept_and_flag`, a route whose zone is final-submitted takes a control call, a
 * sale, a memo edit, a skip, a due collection, the close and stock in the call on the real [AmoDay] ([AmoLockFixture]);
 * the stock slip prints, Mark Paid is drawn and no lock banner shows. The supervisor-day Sales Submit still refuses as X4a
 * built it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmoLatePolicyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
        Dispatchers.resetMain()
    }

    /** Runs [block] on a fresh real day for each policy that must leave the phone unlocked: none in the bundle, accept_and_flag. */
    private fun forEachOpenPolicy(block: (AmoLockFixture) -> Unit) {
        for (policy in listOf(null, "accept_and_flag")) {
            // each policy on a fresh phone: the stock Save's re-save guard lives in the app's preferences, not the database
            app.getSharedPreferences("aron-stock-guard-9", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            val f = AmoLockFixture(app, latePolicy = policy)
            try {
                block(f)
                assertEquals("policy $policy: nothing escaped", listOf<Throwable>(), crashes.toList())
            } catch (e: AssertionError) {
                throw AssertionError("late policy $policy: ${e.message}", e)
            } finally {
                f.close()
            }
        }
    }

    private fun queued(f: AmoLockFixture, type: String) = runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == type }.sumOf { it.count }

    /** The AMO in a control call on [routeId] (opened before the Final Submit). */
    private fun inCall(f: AmoLockFixture, routeId: Long = f.route, outletId: Long = 50001, seq: Int = 1) =
        f.controlCall(outletId = outletId, routeId = routeId, seq = seq).also { f.day.visitSession.restore(it); f.day.routeId = it.routeId }

    @Test fun noLockBannerAndAControlCallStartsOnTheFinalRoute() = forEachOpenPolicy { f ->
        f.zone()
        f.finalSubmitted()
        assertTrue("the phone learned the Final Submit", runBlocking { FinalSubmitLock.isFinal(f.db, f.date, f.route) })
        runBlocking { f.day.refreshLock() }
        assertNull("no lock banner", f.day.dayLock.value)
        assertFalse(runBlocking { f.day.routeLocked(f.route) })
        assertNull(runBlocking { f.day.newCallRefusal(f.route) })
        // the outlet pick reads a fix and the visit flow commits the call
        assertTrue(runBlocking { f.day.openCall(f.outlet(), AmoVisitKind.CONTROL_CALL) })
        assertEquals(1, f.fixReads); assertEquals(f.route, f.day.routeId)
        assertTrue(f.day.visitFlow.state.value.toString(), f.day.visitFlow.state.value is VisitUiState.Open)
        assertFalse(runBlocking { f.day.refusedByLock(f.day.visitFlow.state.value) })
        val visit = runBlocking { f.db.captureDao().visitsOn(f.date) }.single()
        // ControlCallHost's entry guard (D-X4a-22) leaves the call open, and a relaunch restores it
        assertNull(runBlocking { f.day.endCallIfClosed() })
        assertNull(runBlocking { f.day.sale.endCallIfClosed() })
        assertEquals(visit.clientUuid, f.day.visitSession.current.value?.visitUuid)
        assertEquals(visit.clientUuid, runBlocking { FinalSubmitLock.callToRestore(f.db, f.date) { true } }?.clientUuid)
    }

    @Test fun aSaleInTheCallSavesWithItsNumberAndPrintsAndTheMemoCanBeEdited() = forEachOpenPolicy { f ->
        val v = inCall(f)
        val vm = f.savedSale(v) // the Final Submit lands while the sale is open
        val memo = runBlocking { f.db.captureDao().memosOn(f.date) }.single()
        assertEquals(1, runBlocking { f.db.referenceDao().memoCounter(f.date) })
        assertNull(f.day.sale.lockRefused.value); assertNull(f.day.sale.submitRefused.value)
        vm.viewModelScopeForDialogs().launch { vm.saveAndPrint.onPrintAnswer(true) }
        await("the print was attempted") { vm.saveAndPrint.step.value is SaveAndPrint.Step.PrintFailed }
        await("its print_event is queued") { queued(f, "print_event") == 1 }
        assertNull(runBlocking { f.day.sale.editRefusal(memo.clientUuid) })
        assertFalse(runBlocking { f.day.sale.editLocked(memo.clientUuid) })
    }

    @Test fun markPaidIsDrawnOnTheFinalRouteAndCollects() = forEachOpenPolicy { f ->
        val memo = f.creditMemo(f.controlCall())
        f.finalSubmitted()
        val vm = f.day.sale.newMemoViewModel()
        vm.open(memo)
        await("Mark Paid is drawn on the final route") { vm.state.value.canMarkPaid }
        assertFalse(vm.state.value.dayLocked)
        vm.markPaid()
        await("collected") { runBlocking { f.db.captureDao().dueCollectionsOn(f.date) }.size == 1 }
        assertEquals("the call's route", f.route, runBlocking { f.db.captureDao().dueCollectionsOn(f.date) }.single().meta.routeId)
        assertEquals(1, queued(f, "due_collection"))
        assertFalse(vm.state.value.lockRefused); assertFalse(vm.state.value.submitRefused)
    }

    @Test fun aSkipAndTheClosesAreQueuedOnTheFinalRoute() = forEachOpenPolicy { f ->
        val v = inCall(f)
        val second = f.controlCall(outletId = 50002, seq = 2)
        f.finalSubmitted()
        var why: DayRefusal? = null
        assertTrue(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50003), VisitOutcomeCode.CLOSED) { why = it } })
        assertNull(why)
        assertEquals(1, runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.size)
        assertNull("the sale's close was written", runBlocking { f.day.sale.closeVisit(VisitOutcomeCode.SOLD) })
        f.day.visitSession.restore(second); f.day.routeId = second.routeId
        assertNull("the control call's abandoned close was written", runBlocking { f.day.closeVisit() })
        assertEquals(setOf(v.visitUuid, second.visitUuid), runBlocking { f.db.captureDao().visitClosesOn(f.date) }.map { it.visitClientUuid }.toSet())
        assertEquals(2, queued(f, "visit_close"))
    }

    @Test fun stockInTheCallSavesOnTheFinalRouteAndItsSlipPrints() = forEachOpenPolicy { f ->
        f.catalogOnly()
        inCall(f)
        f.finalSubmitted()
        val l = runBlocking { f.day.stockLoad() }
        l.setEntered(105, 3)
        val r = runBlocking { f.day.saveStock(l) }
        assertTrue(r.toString(), r.saved); assertNull(r.refused)
        assertEquals(f.route, runBlocking { f.db.captureDao().stockOn(f.date) }.single().meta.routeId)
        assertTrue("the correction in the call too", runBlocking { f.day.correctStock(105, 5, "count_error", "dozen") })
        assertEquals(2, queued(f, "stock_movement"))
        assertTrue("Print is drawn", runBlocking { f.day.stockSlipPrintable() })
        assertTrue(runBlocking { f.day.printableStockSave() }.all { it.meta.routeId == f.route })
        f.day.printUnprintedStock()
        await("the slip print starts") { f.day.stockAttempt.value != null }
        assertNotNull(f.day.stockAttempt.value)
    }

    @Test fun theSupervisorDaySubmitStillRefusesAsBuilt() = forEachOpenPolicy { f ->
        f.finalSubmitted()
        f.supervisorDaySubmitted()
        var why: DayRefusal? = null
        assertFalse(runBlocking { f.day.openCall(f.outlet(), AmoVisitKind.CONTROL_CALL) { why = it } })
        assertEquals("the submit's own words, not the lock's", DayRefusal.SALES_SUBMIT, why)
        assertEquals(0, f.fixReads)
        assertTrue(runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
    }
}
