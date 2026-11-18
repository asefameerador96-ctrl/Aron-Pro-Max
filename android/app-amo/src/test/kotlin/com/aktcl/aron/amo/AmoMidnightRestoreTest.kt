package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.database.repo.closedByPhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * D-LEAD-PREREQ-04 rulings 1 to 4 (step 3 S1 to S3) for the AMO through [AmoDay.resumeDay], the one function MainActivity calls at start (restoring the call BEFORE
 * any close, which the AMO did not do at all), on resume, at the midnight timer and when a call ends. The clock is moved; the call is read from Room, not put in the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AmoMidnightRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture
    private lateinit var d0: String

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = AmoLockFixture(app, latePolicy = null); d0 = f.date }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun at(date: String, h: Int, m: Int) = LocalDate.parse(date).atTime(h, m).toInstant(ZoneOffset.ofHours(6)).toEpochMilli()
    private fun next() = LocalDate.parse(d0).plusDays(1).toString()
    private fun checkOuts(date: String) = runBlocking { f.db.captureDao().attendanceOn(date).filter { it.kind == "check_out" } }

    @Test fun aControlCallOpenedOnDIsRestoredAtStartAfterMidnightAndTheDayIsNotClosedUnderIt() = runBlocking {
        val v = f.controlCall()
        assertNull(f.day.visitSession.current.value)
        f.trustAt(at(next(), 0, 30))
        f.day.resumeDay()
        assertEquals(v.visitUuid, f.day.visitSession.current.value?.visitUuid)
        assertTrue("nothing is closed under the call", checkOuts(d0).isEmpty())
        assertEquals(AttendanceRefusal.EARLIER_CALL_OPEN, f.day.dayGate.value)
    }

    @Test fun anEarlierDaysCallPastTheLimitIsEndedAsAbandonedOnItsOwnDateThenTheDayCloses() = runBlocking {
        val v = f.controlCall(); val memo = f.creditMemo(v)
        f.trustAt(at(next(), 8, 0))
        f.day.resumeDay()
        assertNull(f.day.visitSession.current.value)
        val close = f.db.captureDao().visitClosesOn(d0).single { it.visitClientUuid == v.visitUuid }
        assertEquals("D-AGATE-15 T2: a call holding a saved sale is closed as sold, never abandoned", "sold", close.outcomeCode); assertFalse(close.isZeroSale)
        assertEquals(d0, close.meta.businessDate)
        assertNotNull("the saved memo is untouched", f.db.captureDao().memo(memo))
        assertEquals(1, checkOuts(d0).size)
        assertEquals(d0, f.day.rolloverNotice.value.abandonedDate)
    }

    @Test fun theForgottenDayIsClosedByThePhoneWithOneReadingAndMarked() = runBlocking {
        val reads = f.fixReads
        f.trustAt(at(next(), 10, 0))
        f.day.resumeDay()
        val out = checkOuts(d0).single()
        assertEquals(reads + 1, f.fixReads)
        assertTrue(out.closedByPhone())
        assertEquals("ok", f.db.captureDao().fix(out.fixClientUuid)!!.fixStatus)
        assertEquals(listOf(d0), f.day.rolloverNotice.value.closedDates)
    }

    /** D-LEAD-PREREQ-04 ruling 5 (step 3 S5): the AMO's joint-call assessment, survey, price and distribution records of a call across midnight take the VISIT's date. */
    @Test fun theRecordsOfACallAcrossMidnightTakeTheVisitsDate() = runBlocking {
        val v = f.controlCall(); f.day.visitSession.restore(v)
        f.trustAt(at(next(), 0, 5))
        val survey = com.aktcl.aron.core.database.entity.SurveyEntity(1, 1, "S", null)
        val q = com.aktcl.aron.feature.outlet.SurveyQuestion(1, com.aktcl.aron.feature.outlet.AnswerType.BOOL, "q1", true, false)
        f.day.saveSurvey(v.visitUuid, survey, listOf(com.aktcl.aron.feature.outlet.SurveyResponseRow(com.aktcl.aron.core.common.ClientIds.newUuid(), v.visitUuid, 1, 1, q, com.aktcl.aron.feature.outlet.SurveyAnswer(bool = true))))
        assertEquals(d0, f.db.captureDao().surveyResponsesOf(v.visitUuid).single().meta.businessDate)
    }

    /** D-AGATE-15 T2: the AMO's phone end is the SAME close as the rep's: a survey answer without a memo is an audit-only call, an empty call is abandoned. */
    @Test fun theLimitClassifiesACallByWhatItHolds() = runBlocking {
        val audited = f.controlCall(seq = 1)
        val empty = f.controlCall(outletId = 50002, seq = 2)
        val survey = com.aktcl.aron.core.database.entity.SurveyEntity(1, 1, "S", null)
        val q = com.aktcl.aron.feature.outlet.SurveyQuestion(1, com.aktcl.aron.feature.outlet.AnswerType.BOOL, "q1", true, false)
        f.day.visitSession.restore(audited)
        f.day.saveSurvey(audited.visitUuid, survey, listOf(com.aktcl.aron.feature.outlet.SurveyResponseRow(com.aktcl.aron.core.common.ClientIds.newUuid(), audited.visitUuid, 1, 1, q, com.aktcl.aron.feature.outlet.SurveyAnswer(bool = true))))
        f.day.visitSession.close()
        f.trustAt(at(next(), 8, 0))
        repeat(2) { f.day.restoreOpenVisit(); f.day.abandonStaleCall() }
        val got = f.db.captureDao().visitClosesOn(d0).associate { it.visitClientUuid to it }
        assertEquals("zero_sale_stock_ok", got[audited.visitUuid]!!.outcomeCode)
        assertEquals("abandoned", got[empty.visitUuid]!!.outcomeCode)
    }

    /** D-AGATE-15 T1: an UNSAVED memo edit open across midnight never holds the day open (see the SR test). */
    @Test fun anUnsavedMemoEditAcrossMidnightIsDroppedAndTheDayCloses() = runBlocking {
        val v = f.controlCall(); val memo = f.creditMemo(v); f.stockedCatalog()
        f.day.visitSession.restore(v); f.day.closeVisit()
        f.day.sale.prepare(); f.day.sale.beginEdit(memo, com.aktcl.aron.feature.sale.domain.EditReason.WRONG_SKU)
        AmoLockFixture.await("edit open") { f.day.sale.flow.state.value.draft?.edit != null }
        f.day.resumeDay()
        assertNotNull("the same day: the edit stays", f.day.sale.flow.state.value.draft?.edit)
        f.trustAt(at(next(), 0, 30)); f.day.resumeDay()
        assertNull("the unsaved edit was dropped", f.day.sale.flow.state.value.draft?.edit)
        assertTrue(f.day.rolloverNotice.value.editDropped)
        assertNotNull("the saved memo is untouched", f.db.captureDao().memo(memo))
        assertEquals(1, checkOuts(d0).size)
        assertEquals(AttendanceRefusal.NOT_CHECKED_IN, f.day.dayGate.value)
    }

    /** D-AGATE-15 mutex: concurrent resumes write one close and take one location attempt. */
    @Test fun twoCallersOfResumeDayWriteOneCloseAndTakeOneLocationAttempt() = runBlocking {
        val v = f.controlCall()
        val reads = f.fixReads
        f.trustAt(at(next(), 8, 0))
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            listOf(async { f.day.resumeDay() }, async { f.day.resumeDay() }, async { f.day.resumeDay() }).awaitAll()
        }
        assertEquals(1, f.db.captureDao().visitClosesOn(d0).count { it.visitClientUuid == v.visitUuid })
        assertEquals(reads + 1, f.fixReads)
        assertEquals(1, checkOuts(d0).size)
    }

    /** D-AGATE-15: the timer also fires at 04:00. */
    @Test fun theTimerAlsoFiresAt0400() {
        f.trustAt(at(next(), 3, 0))
        assertTrue(f.day.millisToNextMidnight() in (3_600_000L - 5_000)..(3_600_000L + 5_000))
    }

    /** D-AGATE-15 lows: after midnight, the call's end-check and an in-call stock movement use the CALL's date (D), never the clock's (D+1). */
    @Test fun endCallIfClosedAndStockMetaUseTheCallsDateAfterMidnight() = runBlocking {
        val v = f.controlCall(); f.day.visitSession.restore(v)
        f.supervisorDaySubmitted()                         // the supervisor day of D stands
        f.trustAt(at(next(), 0, 30))
        assertEquals("an in-call stock movement carries the call's date", d0, f.day.stockMeta().businessDate)
        assertNotNull("D's submit closes the call; D+1's clock date would have found nothing", f.day.sale.endCallIfClosed())
        assertNull(f.day.visitSession.current.value)
    }
    /** D-AGATE-15: the notice survives a process kill: rebuilt from the phone's check-out and the phone's call end, shown once, gone after the rep has seen it. */
    @Test fun theRolloverNoticeIsRebuiltAfterAKillAndShownOnce() = runBlocking {
        val v = f.controlCall()
        f.trustAt(at(next(), 8, 0))
        f.day.resumeDay()
        assertEquals(listOf(d0), f.day.rolloverNotice.value.closedDates)
        // the process dies before the screen: the in-memory notice is gone, the records are in Room
        f.day.rolloverNotice.value = com.aktcl.aron.feature.attendance.RolloverNotice()
        f.day.resumeDay()
        val n = f.day.rolloverNotice.value
        assertEquals("the closed day is told again", listOf(d0), n.closedDates)
        assertEquals("and the call the phone ended", d0, n.abandonedDate); assertEquals(v.outletId, n.abandonedOutletId)
        f.day.dismissRolloverNotice()
        f.day.resumeDay()
        assertFalse("told once: after the rep has seen it, a later start does not show it again", f.day.rolloverNotice.value.any)
    }


    // ---- D-AGATE-19: the AMO marker paths (the SR ones exist in SrMidnightRestoreTest; the AMO code is the same shape and is asserted here against Room)

    /** The rep's own close after midnight writes no marker and is never told as the phone's. */
    @Test fun theRepsOwnCloseAfterMidnightIsNeverToldAsThePhones() = runBlocking {
        val v = f.controlCall(); f.creditMemo(v)
        f.day.visitSession.restore(v)
        f.trustAt(at(next(), 0, 6))
        f.day.resumeDay()
        assertNotNull("held open before 04:00", f.day.visitSession.current.value)
        f.day.closeVisit() // the rep's own close
        f.day.resumeDay()
        assertNull(f.day.rolloverNotice.value.abandonedDate)
        assertTrue(f.day.capture.phoneMarks().none { it.kind == "call_ended" })
    }

    /** The marker shares the close's transaction: a close that fails stores no marker and tells nothing. */
    @Test fun aPhoneCloseThatFailsLeavesNoMarkerAndNoNotice() = runBlocking {
        val v = f.controlCall()
        f.day.visitSession.restore(v)
        f.db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF"); f.db.openHelper.writableDatabase.execSQL("DELETE FROM visit WHERE client_uuid = '${v.visitUuid}'")
        f.trustAt(at(next(), 8, 0))
        assertFalse(f.day.abandonStaleCall())
        assertNotNull("the call stays until a close is stored", f.day.visitSession.current.value)
        assertTrue(f.day.capture.phoneMarks().isEmpty()); assertNull(f.day.rolloverNotice.value.abandonedDate)
    }

    /** The route-day refuses the phone's close: no close is written, the notice is still stored (the rep is told the call was ended). */
    @Test fun aPhoneEndTheRouteDayRefusedStillStoresTheNotice() = runBlocking {
        val v = f.controlCall()
        f.day.visitSession.restore(v) // in the session when the lock lands (a start never restores a call the route-day refuses)
        f.supervisorDaySubmitted() // the AMO's own supervisor-day Sales Submit stands
        f.trustAt(at(next(), 8, 0))
        f.day.resumeDay()
        assertNull(f.day.visitSession.current.value)
        assertTrue(f.db.captureDao().visitClosesOn(d0).none { it.visitClientUuid == v.visitUuid })
        assertTrue(f.day.capture.phoneMarks().any { it.kind == "call_ended" && it.outletId == v.outletId })
        assertEquals(d0, f.day.rolloverNotice.value.abandonedDate)
    }

    /** DAY_CLOSED and EDIT_DROPPED markers: stored with what they describe, rebuilt after a kill, told once. */
    @Test fun theDayClosedAndEditDroppedMarkersAreStoredAndSurviveAKill() = runBlocking {
        val v = f.controlCall(); val memo = f.creditMemo(v); f.stockedCatalog()
        f.day.visitSession.restore(v); f.day.closeVisit()
        f.day.sale.prepare(); f.day.sale.beginEdit(memo, com.aktcl.aron.feature.sale.domain.EditReason.WRONG_SKU)
        AmoLockFixture.await("edit open") { f.day.sale.flow.state.value.draft?.edit != null }
        f.trustAt(at(next(), 0, 30)); f.day.resumeDay()
        val kinds = f.day.capture.phoneMarks().map { it.kind }
        assertTrue("edit_dropped stored: $kinds", "edit_dropped" in kinds); assertTrue("day_closed stored: $kinds", "day_closed" in kinds)
        f.day.rolloverNotice.value = com.aktcl.aron.feature.attendance.RolloverNotice() // the process dies before the screen
        f.day.resumeDay()
        assertTrue("told again from the stored markers", f.day.rolloverNotice.value.editDropped); assertEquals(listOf(d0), f.day.rolloverNotice.value.closedDates)
        f.day.dismissRolloverNotice(); f.day.resumeDay()
        assertFalse("told once", f.day.rolloverNotice.value.any)
    }

    /** An unsaved sale draft dropped at the limit: the notice is stored (before the drop), survives a kill, and nothing is told when nothing was dropped. */
    @Test fun theDroppedDraftNoticeSurvivesAKillAndNothingIsToldWhenNothingWasDropped() = runBlocking {
        val v = f.controlCall(); f.stockedCatalog()
        f.day.visitSession.restore(v)
        val vm = f.saleViewModel(); f.day.sale.prepare(); vm.start(f.day.sale.saleVisit(v))
        AmoLockFixture.await("drafted") { f.day.sale.flow.state.value.draft?.visitUuid == v.visitUuid }
        vm.setQuantity(105, 2, com.aktcl.aron.rules.QtyUnit.DOZEN)
        AmoLockFixture.await("two dozen") { f.day.sale.flow.state.value.draft?.lines?.isNotEmpty() == true }
        f.trustAt(at(next(), 4, 0))
        f.day.resumeDay()
        assertTrue(f.day.rolloverNotice.value.draftDropped)
        f.day.rolloverNotice.value = com.aktcl.aron.feature.attendance.RolloverNotice()
        f.day.resumeDay()
        assertTrue("told again from the stored marker", f.day.rolloverNotice.value.draftDropped)
        f.day.dismissRolloverNotice(); f.day.resumeDay()
        assertFalse(f.day.rolloverNotice.value.any)
        assertFalse(f.day.dropStaleEdit()); assertFalse(f.day.rolloverNotice.value.editDropped)
    }

    /** Low 1 on the AMO: the phone ended the call while the sale screen was open; the Save then is refused inside the close lock and stores no memo. */
    @Test fun aSaveAfterThePhoneClosedTheCallIsRefusedAndStoresNoMemo() = runBlocking {
        val v = f.controlCall(); f.day.visitSession.restore(v)
        val vm = f.refusedSale(v) { runBlocking { f.day.closeVisit(phoneEnd = true) } }
        assertEquals(com.aktcl.aron.core.printing.flow.SaveAndPrint.Step.SaveFailed, vm.saveAndPrint.step.value)
        assertTrue(f.db.captureDao().memosOn(d0).none { it.visitClientUuid == v.visitUuid })
        assertEquals("abandoned", f.db.captureDao().visitClosesOn(d0).single { it.visitClientUuid == v.visitUuid }.outcomeCode)
        assertEquals(listOf<com.aktcl.aron.feature.sale.domain.ReviewProblem>(com.aktcl.aron.feature.sale.domain.ReviewProblem.CallClosed), f.day.sale.flow.state.value.refusal)
    }

    /** Lead item 1 on the AMO: a retry of a Save that already landed returns its memo after the call was closed. */
    @Test fun aRetryOfASaveThatAlreadyLandedReturnsItsMemoAfterTheCallWasClosed() = runBlocking {
        val v = f.controlCall(); f.day.visitSession.restore(v)
        f.savedSale(v) { }
        val memo = f.db.captureDao().memosOn(d0).single { it.visitClientUuid == v.visitUuid }
        f.day.closeVisit(phoneEnd = true)
        assertEquals("the same memo", memo.clientUuid, f.day.sale.commitSale().memoClientUuid)
        assertEquals(1, f.db.captureDao().memosOn(d0).count { it.visitClientUuid == v.visitUuid })
    }
}
