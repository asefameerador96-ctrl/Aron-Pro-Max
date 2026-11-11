package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.core.database.repo.FinalSubmitLock
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.sr.SrLockFixture.Companion.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * is accepted and flagged by the server, never refused, so the SR phone's day lock is off by default. With the late-sale
 * policy absent from the bundle and with `accept_and_flag`, a route-day whose zone is final-submitted takes a call, a sale,
 * a memo edit, a skip, a due collection, the close and stock on the real [SrDay] ([SrLockFixture]); the stock slip prints,
 * Mark Paid is drawn and no lock banner shows. The SR's own Sales Submit still refuses as X4a built it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrLatePolicyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
    }

    @After fun tearDown() {
        // every test closed its fixtures (their work stopped, then the database closed) before the catchers go
        Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
        Dispatchers.resetMain()
    }

    /**
     * Runs [block] on a fresh real day for each policy that must leave the phone unlocked: none in the bundle, accept_and_flag.
     * "Nothing escaped" is read after the fixture closed (CI run 41750277921): the work the block started has finished, been
     * cancelled and joined before the database closed, so what it threw is counted here and none of it reaches the next policy.
     */
    private fun forEachOpenPolicy(block: (SrLockFixture) -> Unit) {
        for (policy in listOf(null, "accept_and_flag")) {
            // each policy on a fresh phone: the stock Save's re-save guard lives in the app's preferences, not the database
            context.getSharedPreferences("aron-stock-guard-7", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            try {
                SrLockFixture(context, latePolicy = policy).use(block)
                assertEquals("policy $policy: nothing escaped: " + crashes.joinToString { it.stackTraceToString().take(1500) }, listOf<Throwable>(), crashes.toList())
            } catch (e: AssertionError) {
                throw AssertionError("late policy $policy: ${e.message}", e)
            }
        }
    }

    private fun queued(f: SrLockFixture, type: String) = runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == type }.sumOf { it.count }

    @Test fun noLockBannerNoCheckOutGateAndANewCallStartsOnTheFinalRoute() = forEachOpenPolicy { f ->
        f.finalSubmitted()
        assertTrue("the phone learned the Final Submit", runBlocking { FinalSubmitLock.isFinal(f.db, f.date, f.route) })
        runBlocking { f.day.refreshLock() }
        assertNull("no lock banner", f.day.dayLock.value)
        assertFalse("the check-out gate is not opened by the Final Submit", runBlocking { f.day.finalLocked() })
        assertNull(runBlocking { f.day.sale.newCallRefusal(f.route) })
        assertFalse(runBlocking { f.day.sale.callLocked(f.route) })
        // the picker's tap reads a fix and the visit flow commits the call
        assertTrue(runBlocking { f.day.sale.startCall(f.outlet()) })
        assertEquals(1, f.fixReads)
        assertTrue(f.day.visitFlow.state.value.toString(), f.day.visitFlow.state.value is VisitUiState.Open)
        assertFalse(runBlocking { f.day.sale.refusedByLock(f.day.visitFlow.state.value) })
        val visit = runBlocking { f.db.captureDao().visitsOn(f.date) }.single()
        assertEquals(1, queued(f, "visit"))
        // SaleHost's entry guard (D-X4a-17) leaves the call open, and a relaunch restores it
        assertNull(runBlocking { f.day.openCallRefusal() })
        assertNull(runBlocking { f.day.endCallIfClosed() })
        assertEquals(visit.clientUuid, f.day.visitSession.current.value?.visitUuid)
        assertEquals(visit.clientUuid, runBlocking { FinalSubmitLock.callToRestore(f.db, f.date) { true } }?.clientUuid)
    }

    @Test fun aSaleSavesWithItsNumberAndPrintsAndTheMemoCanBeEdited() = forEachOpenPolicy { f ->
        val v = f.openCall()
        f.day.visitSession.restore(v)
        val vm = f.savedSale(v) // the Final Submit lands while the sale is open
        val memo = runBlocking { f.db.captureDao().memosOn(f.date) }.single()
        assertEquals(1, runBlocking { f.db.referenceDao().memoCounter(f.date) })
        assertNull(f.day.sale.lockRefused.value); assertNull(f.day.sale.submitRefused.value)
        // "Yes" on print: the print chain runs (no printer is saved here, so the attempt fails and says so)
        vm.viewModelScopeForDialogs().launch { vm.saveAndPrint.onPrintAnswer(true) }
        await("the print was attempted") { vm.saveAndPrint.step.value is SaveAndPrint.Step.PrintFailed }
        await("its print_event is queued") { queued(f, "print_event") == 1 }
        // the memo detail draws Edit
        assertNull(runBlocking { f.day.sale.editRefusal(memo.clientUuid) })
        assertFalse(runBlocking { f.day.sale.editLocked(memo.clientUuid) })
    }

    @Test fun markPaidIsDrawnOnTheFinalDayAndCollects() = forEachOpenPolicy { f ->
        val v = f.openCall()
        val memo = f.creditMemo(v)
        f.finalSubmitted()
        val vm = f.memoViewModel()
        vm.open(memo)
        await("Mark Paid is drawn on the final day") { vm.state.value.canMarkPaid }
        assertFalse(vm.state.value.dayLocked)
        vm.markPaid()
        await("collected") { runBlocking { f.db.captureDao().dueCollectionsOn(f.date) }.size == 1 }
        assertEquals(f.route, runBlocking { f.db.captureDao().dueCollectionsOn(f.date) }.single().meta.routeId)
        assertEquals(1, queued(f, "due_collection"))
        assertFalse(vm.state.value.lockRefused); assertFalse(vm.state.value.submitRefused)
    }

    @Test fun aSkipAndTheCloseAreQueuedOnTheFinalDay() = forEachOpenPolicy { f ->
        val v = f.openCall()
        f.day.visitSession.restore(v)
        f.day.sale.callStarted[v.visitUuid] = f.meta().capturedAt
        f.finalSubmitted()
        var why: DayRefusal? = null
        assertTrue(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50003), VisitOutcomeCode.CLOSED) { why = it } })
        assertNull(why)
        assertEquals(1, runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.size)
        assertNull("a close was written, not ended on the phone", runBlocking { f.day.sale.closeVisit(VisitOutcomeCode.SOLD) })
        assertEquals(listOf(v.visitUuid), runBlocking { f.db.captureDao().visitClosesOn(f.date) }.map { it.visitClientUuid })
        assertEquals(1, queued(f, "visit_close"))
    }

    @Test fun stockSavesOnTheFinalDayAndItsSlipPrints() = forEachOpenPolicy { f ->
        f.catalogOnly()
        f.finalSubmitted()
        val l = runBlocking { f.day.stockLoad() }
        l.setEntered(105, 3)
        val r = runBlocking { f.day.saveStock(l) }
        assertTrue(r.toString(), r.saved); assertNull(r.refused)
        assertEquals(f.route, runBlocking { f.db.captureDao().stockOn(f.date) }.single().meta.routeId)
        assertEquals(1, queued(f, "stock_movement"))
        assertTrue("its slip is offered", runBlocking { f.day.slipNotPrinted() })
        assertTrue("Print is drawn", runBlocking { f.day.stockSlipPrintable() })
        f.day.printUnprintedStock()
        await("the slip print starts") { f.day.stockAttempt.value != null }
        assertNotNull(f.day.stockAttempt.value)
    }

    /**
     * X4a fix round (checker low): the call guards (SaleHost's entry guard, the sale screens' close pre-check, leaving the
     * call, the relaunch check) read the late-sale policy at the day's trusted time, the instant the writes use, never at
     * the phone's wall clock. Here the phone's clock runs ten minutes ahead of trusted time and an admin's quarantine is
     * scheduled to start between the two: at trusted time the policy is still accept_and_flag, so the call stays open, is
     * restored after a relaunch and its close is written.
     */
    @Test fun theCallGuardsReadThePolicyAtTrustedTimeNotAtTheWallClock() {
        SrLockFixture(context, latePolicy = "accept_and_flag").use { f ->
            val wall = System.currentTimeMillis()
            f.trustAt(wall - 10 * 60_000L) // trusted time: ten minutes behind the phone's own clock
            f.schedulePolicy("quarantine", fromIso = f.day.iso(wall - 5 * 60_000L))
            f.finalSubmitted()
            assertFalse("accept_and_flag at trusted time", runBlocking { FinalSubmitLock.lockApplies(f.db, f.day.iso(f.day.currentMs())) })
            assertTrue("quarantine at the wall clock", runBlocking { FinalSubmitLock.lockApplies(f.db, f.day.iso(System.currentTimeMillis())) })
            val v = f.openCall()
            f.day.visitSession.restore(v)
            assertNull("SaleHost's entry guard", runBlocking { f.day.openCallRefusal() })
            assertNull("the sale screens' close pre-check", runBlocking { f.day.endCallIfClosed() })
            assertEquals(v.visitUuid, f.day.visitSession.current.value?.visitUuid)
            // a relaunch restores the call
            f.day.visitSession.close()
            runBlocking { f.day.restoreOpenVisit() }
            assertEquals("restored after a relaunch", v.visitUuid, f.day.visitSession.current.value?.visitUuid)
            // leaving the call writes its close: the guard and the write agree
            assertNull(runBlocking { f.day.closeVisitAbandoned() })
            assertEquals(listOf(v.visitUuid), runBlocking { f.db.captureDao().visitClosesOn(f.date) }.map { it.visitClientUuid })
            assertEquals(1, queued(f, "visit_close"))
            assertEquals(listOf<Throwable>(), crashes.toList())
        }
        assertEquals("nothing escaped after the fixture closed", listOf<Throwable>(), crashes.toList())
    }

    @Test fun theSrsOwnSalesSubmitStillRefusesAsBuilt() = forEachOpenPolicy { f ->
        f.finalSubmitted()
        f.salesSubmitted()
        var why: DayRefusal? = null
        assertFalse(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50003), VisitOutcomeCode.CLOSED) { why = it } })
        assertEquals("the Sales Submit's own words, not the lock's", DayRefusal.SALES_SUBMIT, why)
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.sale.newCallRefusal(f.route) })
        assertTrue(runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.isEmpty())
    }
}
