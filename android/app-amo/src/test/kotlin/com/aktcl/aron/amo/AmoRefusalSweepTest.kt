package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.SurveyEntity
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.feature.amo.AmoBrand
import com.aktcl.aron.feature.amo.AmoVisitKind
import com.aktcl.aron.feature.amo.JointRubric
import com.aktcl.aron.feature.amo.JointState
import com.aktcl.aron.feature.amo.PerfState
import com.aktcl.aron.feature.amo.PriceRow
import com.aktcl.aron.feature.amo.RubricCriterion
import com.aktcl.aron.feature.outlet.AnswerType
import com.aktcl.aron.feature.outlet.SurveyAnswer
import com.aktcl.aron.feature.outlet.SurveyQuestion
import com.aktcl.aron.feature.outlet.SurveyResponseRow
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.feature.stock.StockLoad
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
 * tso-manual X4a follow-up (R-W-22, R-W-23), on a real [AmoDay] ([AmoLockFixture]): the AMO's stock Saves (the issue and the
 * correction) meet the zone's Final Submit lock in a call on a closed route and nothing is queued or printed; and every
 * capture the AMO's own supervisor-day Sales Submit refuses (a new control, joint or sale call, the sale, the close, the
 * call steps, an edit, a skip) is named, worded by its host and never thrown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmoRefusalSweepTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
        // owner decision 2026-11-04: the phone locks only under quarantine or reject (default accept_and_flag); this class tests the lock
        f = AmoLockFixture(app, latePolicy = "quarantine")
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

    private fun stockRows() = runBlocking { f.db.captureDao().stockOn(f.date) }
    private fun queued(type: String) = runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == type }.sumOf { it.count }
    private fun load(): StockLoad = runBlocking { f.day.stockLoad() }

    /** The AMO in a control call on [routeId] (opened before anything closed the day). */
    private fun inCall(routeId: Long = f.route, outletId: Long = 50001, seq: Int = 1) =
        f.controlCall(outletId = outletId, routeId = routeId, seq = seq).also { f.day.visitSession.restore(it); f.day.routeId = it.routeId }

    private fun printTapPrintsNothing() {
        f.day.printUnprintedStock()
        // joins the print launch (and Room's executor) instead of sleeping 300 ms: a print that was going to start has started or finished by now
        assertTrue("the print launch settled", f.work.awaitSettled())
        assertNull("no stock slip print started", f.day.stockAttempt.value)
        assertEquals("no print_event queued", 0, queued("print_event"))
    }

    // ---------------- R-W-22 ----------------

    @Test fun aStockSaveInACallOnAFinalRouteIsRefusedNothingQueuedNothingPrinted() {
        f.catalogOnly()
        inCall()
        f.finalSubmitted() // already there when the AMO opens Stock
        val l = load()
        l.setEntered(105, 3)
        val r = runBlocking { f.day.saveStock(l) }
        assertEquals(DayRefusal.FINAL_SUBMIT, r.refused); assertNull(r.message)
        assertTrue(stockRows().isEmpty()); assertEquals(0, queued("stock_movement"))
        assertFalse(runBlocking { f.day.slipNotPrinted() })
        printTapPrintsNothing()
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun aLockThatArrivesWhileTheAmoTypesRefusesTheSaveAndOutsideACallStockStillSaves() {
        f.catalogOnly()
        inCall()
        val l = load()
        l.setEntered(105, 2)
        f.finalSubmitted() // the push lands while the AMO types
        assertEquals(DayRefusal.FINAL_SUBMIT, runBlocking { f.day.saveStock(l) }.refused)
        assertTrue(stockRows().isEmpty()); assertEquals(0, queued("stock_movement"))
        printTapPrintsNothing()
        // the call ends: the AMO's own stock has no route and never meets the lock (as on the server)
        f.day.visitSession.close(); f.day.routeId = null
        val r = runBlocking { f.day.saveStock(l) }
        assertTrue(r.toString(), r.saved)
        assertNull(stockRows().single().meta.routeId)
        assertEquals(1, queued("stock_movement"))
    }

    @Test fun aStockCorrectionInACallOnAFinalRouteIsRefusedByNameAndNothingIsStored() {
        f.catalogOnly()
        inCall()
        f.finalSubmitted()
        val e = runCatching { runBlocking { f.day.correctStock(105, 5, "count_error", "dozen") } }.exceptionOrNull()
        assertEquals("the host words it as the lock", DayRefusal.FINAL_SUBMIT, DayRefusal.of(e))
        assertTrue(stockRows().isEmpty()); assertEquals(0, queued("stock_movement"))
        f.day.visitSession.close(); f.day.routeId = null
        assertTrue("outside a call it corrects", runBlocking { f.day.correctStock(105, 5, "count_error", "dozen") })
        assertEquals(1, queued("stock_movement"))
    }

    /**
     * Fix round (checker medium, R-W-22 "no stock slip prints after the lock"): the slip of a Save stored in a call before
     * that route's Final Submit is not printed after it (not offered, the tap prints nothing, it stays unprinted); the slip
     * of the AMO's own routeless Save still prints.
     */
    @Test fun aSlipOfASaveStoredInACallBeforeTheLockDoesNotPrintAfterIt() {
        f.catalogOnly()
        inCall()
        val l = load()
        l.setEntered(105, 4)
        assertTrue(runBlocking { f.day.saveStock(l) }.saved)
        assertTrue("before the lock its slip may print", runBlocking { f.day.stockSlipPrintable() })
        f.finalSubmitted()
        assertFalse("Print is not drawn after the lock", runBlocking { f.day.stockSlipPrintable() })
        printTapPrintsNothing()
        assertTrue("the Save stays unprinted", runBlocking { f.day.slipNotPrinted() })
        // outside the call the AMO's own Save has no route: its slip prints, the locked one stays unprinted
        f.day.visitSession.close(); f.day.routeId = null
        l.setEntered(105, 1)
        assertTrue(runBlocking { f.day.saveStock(l) }.saved)
        assertTrue(runBlocking { f.day.stockSlipPrintable() })
        assertTrue(runBlocking { f.day.printableStockSave() }.all { it.meta.routeId == null })
        f.day.printUnprintedStock()
        AmoLockFixture.await("the routeless slip print starts") { f.day.stockAttempt.value != null }
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    /**
     * Fix round (checker low): the outlet pick sets the day's route before the fix is read, and backing out of the check step
     * leaves it set with no call open. The AMO's own stock (a Save, a correction) then carries no route, so that route's
     * Final Submit never refuses it.
     */
    @Test fun aRouteLeftBehindByABackedOutCallNeverTagsTheAmosOwnStock() {
        f.catalogOnly()
        f.day.routeId = f.route // the outlet pick took the route, then the AMO backed out of the check step: no call is open
        f.finalSubmitted()
        val l = load()
        l.setEntered(105, 2)
        val r = runBlocking { f.day.saveStock(l) }
        assertTrue(r.toString(), r.saved); assertNull(r.refused)
        assertNull(stockRows().single().meta.routeId)
        assertTrue("the correction too", runBlocking { f.day.correctStock(105, 5, "count_error", "dozen") })
        assertTrue(stockRows().all { it.meta.routeId == null })
        assertEquals(2, queued("stock_movement"))
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    // ---------------- R-W-23 ----------------

    /**
     * Fix round (checker low, R-W-23): the control call host's entry guard ([AmoDay.endCallIfClosed]), as the SR sale host's.
     * Back to a call whose route-day no longer takes its records (the Final Submit lock, or the supervisor-day submit): it
     * ends on the phone with no close written and says why; on an open day the call stays.
     */
    @Test fun aCallWhoseRouteDayClosedEndsAtTheControlCallsEntryAndSaysWhy() {
        val v = inCall()
        assertNull("an open day leaves the call as it is", runBlocking { f.day.endCallIfClosed() })
        assertEquals(v.visitUuid, f.day.visitSession.current.value?.visitUuid)
        f.finalSubmitted()
        assertEquals(DayRefusal.FINAL_SUBMIT, runBlocking { f.day.endCallIfClosed() })
        assertNull("the call ended on the phone", f.day.visitSession.current.value)
        assertNull(f.day.routeId)
        f.reopened()
        inCall(outletId = 50002, seq = 2)
        f.supervisorDaySubmitted()
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.endCallIfClosed() })
        assertNull(f.day.visitSession.current.value)
        assertTrue("no visit_close written", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    /** Fix round (checker low): the supervisor-day submit after the zone's Final Submit, with a call open on that route, is queued. */
    @Test fun theSupervisorDaySubmitAfterTheFinalSubmitIsQueued() {
        inCall()
        f.finalSubmitted()
        runBlocking { f.day.sale.submitWriter().queue(f.date, com.aktcl.aron.feature.dayclose.SubmitGate(true, emptyList(), null), emptyList()) }
        assertEquals(1, queued("day_submit"))
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun aNewCallAfterTheSupervisorDaySubmitIsRefusedByNameBeforeAFix() {
        f.supervisorDaySubmitted()
        var why: DayRefusal? = null
        assertFalse(runBlocking { f.day.openCall(f.outlet(), AmoVisitKind.CONTROL_CALL) { why = it } })
        assertEquals(DayRefusal.SALES_SUBMIT, why)
        assertEquals("no fix read", 0, f.fixReads)
        assertNull("the day took no route", f.day.routeId)
        assertFalse(runBlocking { f.day.routeLocked(f.route) })
        assertTrue(runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
    }

    @Test fun aSupervisorDaySubmitThatLandsWhileTheFixIsReadIsWordedNotARetry() {
        f.duringFix = { f.supervisorDaySubmitted() }
        assertTrue(runBlocking { f.day.openCall(f.outlet(), AmoVisitKind.JOINT_CALL) })
        val st = f.day.visitFlow.state.value
        assertTrue(st.toString(), st is VisitUiState.CommitFailed)
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.commitRefusal(st) })
        assertFalse(runBlocking { f.day.refusedByLock(st) })
        assertTrue(runBlocking { f.db.captureDao().visitsOn(f.date) }.isEmpty())
    }

    @Test fun aSaleRefusedByTheSupervisorDaySubmitTakesNoNumberAndOkEndsTheCall() {
        val v = inCall()
        f.refusedSale(v) { f.supervisorDaySubmitted() }
        assertEquals(v.visitUuid, f.day.sale.submitRefused.value); assertNull(f.day.sale.lockRefused.value)
        assertTrue(runBlocking { f.db.captureDao().memosOn(f.date) }.isEmpty())
        assertNull("no memo number taken", runBlocking { f.db.referenceDao().memoCounter(f.date) })
        var ended: Pair<Any?, String?>? = null
        var menu = 0
        runBlocking { f.day.sale.afterSaleRefusal(onCallEnded = { ended = f.day.visitSession.current.value to f.day.sale.submitRefused.value }, onDone = { menu++ }) }
        assertEquals("back to the outlet list with the call ended, the refusal cleared after", null to v.visitUuid, ended)
        assertEquals(0, menu)
        assertNull(f.day.sale.submitRefused.value); assertNull(f.day.routeId)
        assertTrue(runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun leavingACallAfterTheSupervisorDaySubmitEndsItOnThePhoneAndSaysWhy() {
        val sold = inCall()
        val audit = f.controlCall(outletId = 50002, seq = 2)
        f.supervisorDaySubmitted()
        // the sale's own close (AmoSaleKit.closeVisit) and leaving the call menu (AmoDay.closeVisit)
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.sale.closeVisit(VisitOutcomeCode.SOLD) })
        assertNull(f.day.visitSession.current.value)
        f.day.visitSession.restore(audit); f.day.routeId = audit.routeId
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.closeVisit() })
        assertNull(f.day.visitSession.current.value); assertNull(f.day.routeId)
        assertTrue("no visit_close written", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
        assertEquals(sold.routeId, f.route)
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun aCallTheSupervisorDaySubmitClosedIsNotRestoredAfterARelaunch() {
        val v = f.controlCall()
        runBlocking { f.day.restoreOpenVisit() }
        assertEquals(v.visitUuid, f.day.visitSession.current.value?.visitUuid)
        f.day.visitSession.close() // a kill and relaunch
        f.supervisorDaySubmitted()
        runBlocking { f.day.restoreOpenVisit() }
        assertNull(f.day.visitSession.current.value)
    }

    @Test fun theControlAndJointCallStepsWordASaveTheSupervisorDaySubmitRefused() {
        val v = inCall()
        val brands = listOf(AmoBrand(1, "A", null), AmoBrand(2, "B", null))
        val rubric = JointRubric(1, 1, listOf(RubricCriterion(1, "A", null, "score_1_5", true), RubricCriterion(2, "B", null, "score_1_5", true)))
        val survey = SurveyEntity(1, 1, "S", null)
        val rows = listOf(SurveyResponseRow(ClientIds.newUuid(), v.visitUuid, 1, 1, SurveyQuestion(1, AnswerType.BOOL, "q1", true, false), SurveyAnswer(bool = true)))
        val prices = listOf(PriceRow(1, "A", "10", "retail", 120_000))
        f.supervisorDaySubmitted()
        val outs = listOf(
            runBlocking { f.day.saveCallStep { f.day.saveAssessment(v.visitUuid, brands, PerfState(present = setOf(1L, 2L), oos = setOf(2L), posm = true)) } },
            runBlocking { f.day.saveCallStep { f.day.saveJointCall(v.visitUuid, rubric, JointState(mapOf(1L to 4, 2L to 5)), 7L) } },
            runBlocking { f.day.saveCallStep { f.day.saveSurvey(v.visitUuid, survey, rows) } },
            runBlocking { f.day.saveCallStep { f.day.savePriceCompliance(v.visitUuid, prices, mapOf(1L to "120"), 500) } },
        )
        outs.forEach { assertEquals(StepSave(false, DayRefusal.SALES_SUBMIT), it) }
        assertTrue("nothing stored", runBlocking { f.db.captureDao().amoRecordsOfFamily(v.visitUuid) }.isEmpty())
        assertTrue(runBlocking { f.db.captureDao().surveyResponsesOf(v.visitUuid) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }

    @Test fun onAnOpenDayACallStepSavesAsBefore() {
        val v = inCall()
        val out = runBlocking { f.day.saveCallStep { f.day.savePriceCompliance(v.visitUuid, listOf(PriceRow(1, "A", "10", "retail", 120_000)), mapOf(1L to "120"), 500) } }
        assertEquals(StepSave(true, null), out)
        assertEquals(1, queued("price_compliance_check"))
        // and a failure that is no refusal is not worded as one
        assertEquals(StepSave(false, null), runBlocking { f.day.saveCallStep { error("disk full") } })
    }

    @Test fun anEditAndASkipAfterTheSupervisorDaySubmitAreRefusedByName() {
        val memo = f.creditMemo(f.controlCall())
        f.supervisorDaySubmitted()
        assertEquals(DayRefusal.SALES_SUBMIT, runBlocking { f.day.sale.editRefusal(memo) })
        assertFalse(runBlocking { f.day.sale.editLocked(memo) })
        var why: DayRefusal? = null
        assertFalse(runBlocking { f.day.sale.skipOutlet(f.outlet(id = 50003), VisitOutcomeCode.CLOSED) { why = it } })
        assertEquals(DayRefusal.SALES_SUBMIT, why)
        assertTrue(runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.isEmpty())
        assertEquals(listOf<Throwable>(), crashes.toList())
    }
}
