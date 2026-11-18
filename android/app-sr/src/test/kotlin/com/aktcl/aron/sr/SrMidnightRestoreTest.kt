package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.database.repo.closedByPhone
import com.aktcl.aron.rules.QtyUnit
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
 * D-LEAD-PREREQ-04 rulings 1 to 4 (step 3 S1 to S3) through the PRODUCTION start path of a real [SrDay] ([SrDay.resumeDay], which SrApp calls at start, on resume, at the
 * Dhaka-midnight timer and when an open call ends), with the clock moved ([SrLockFixture.trustAt]) and the call restored from Room, not placed in the session by the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrMidnightRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = SrLockFixture(context, latePolicy = null, checkoutMinutes = 0); d0 = f.date }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun at(date: String, h: Int, m: Int) = LocalDate.parse(date).atTime(h, m).toInstant(ZoneOffset.ofHours(6)).toEpochMilli()
    private lateinit var d0: String
    private fun next() = LocalDate.parse(d0).plusDays(1).toString()
    private fun checkOuts(date: String) = runBlocking { f.db.captureDao().attendanceOn(date).filter { it.kind == "check_out" } }

    /** Kill at 23:50, relaunch at 00:30: the call is restored from the day of the open check-in and the day is NOT closed under it (the review's scenario). */
    @Test fun aCallOpenedOnDIsRestoredAfterMidnightAndTheDayIsNotClosedUnderIt() = runBlocking {
        val v = f.openCall()               // stored through the production writer; the session is EMPTY, as after a kill
        assertNull(f.day.visitSession.current.value)
        f.trustAt(at(next(), 0, 30))
        f.day.resumeDay()
        assertEquals("the open call is back in the session", v.visitUuid, f.day.visitSession.current.value?.visitUuid)
        assertTrue("nothing is closed under it", checkOuts(d0).isEmpty())
        assertEquals(0, f.db.captureDao().daySubmitsOn(d0).size)
        assertEquals("only that call's screens work; every other tile says why", AttendanceRefusal.EARLIER_CALL_OPEN, f.day.dayGate.value)
        assertEquals("the call keeps the visit's date", d0, f.day.visitDate(f.day.visitSession.current.value!!))
    }

    /** Kill after a saved sale, relaunch at 08:00 (past the 04:00 limit): the call ends as abandoned on D's date, the saved sale is untouched, then the day closes. */
    @Test fun aSavedSaleSurvivesTheLimitAndTheDayThenCloses() = runBlocking {
        val v = f.openCall(); val memo = f.creditMemo(v)
        f.trustAt(at(next(), 8, 0))
        f.day.resumeDay()
        assertNull("the call was ended by the phone", f.day.visitSession.current.value)
        val close = f.db.captureDao().visitClosesOn(d0).single { it.visitClientUuid == v.visitUuid }
        assertEquals("D-AGATE-15 T2: a call holding a saved sale is closed as sold, never abandoned", "sold", close.outcomeCode); assertFalse(close.isZeroSale)
        assertEquals("dated with the VISIT's date", d0, close.meta.businessDate)
        assertTrue("flagged: the end is stamped by the phone on a later date than the visit's", close.endedAt!!.startsWith(next()))
        assertNotNull("the saved memo is untouched", f.db.captureDao().memo(memo))
        assertEquals("the day closed after it", 1, checkOuts(d0).size)
        assertEquals(1, f.db.captureDao().daySubmitsOn(d0).size)
        val n = f.day.rolloverNotice.value
        assertEquals(d0, n.abandonedDate); assertTrue(d0 in n.closedDates); assertFalse("no unsaved draft existed", n.draftDropped)
    }

    /** Before the limit an unsaved draft is kept; at the limit it is dropped with a plain notice and the day closes. */
    @Test fun anUnsavedDraftIsDroppedOnlyAtTheLimit() = runBlocking {
        val v = f.openCall(); f.stockedCatalog()
        val vm = f.saleViewModel(); day().sale.prepare(); vm.start(day().sale.saleVisit(v))
        SrLockFixture.await("drafted") { day().sale.flow.state.value.draft?.visitUuid == v.visitUuid }
        vm.setQuantity(105, 2, QtyUnit.DOZEN)
        SrLockFixture.await("two dozen") { day().sale.flow.state.value.draft?.lines?.isNotEmpty() == true }
        f.trustAt(at(next(), 3, 59))
        f.day.resumeDay()
        assertNotNull("before 04:00 the call and its draft stay", f.day.visitSession.current.value)
        assertTrue(checkOuts(d0).isEmpty())
        f.trustAt(at(next(), 4, 0))
        f.day.resumeDay()
        assertNull(f.day.visitSession.current.value)
        assertTrue("the draft was dropped, with a notice", f.day.rolloverNotice.value.draftDropped)
        assertEquals("and the day closed", 1, checkOuts(d0).size)
    }

    /** A day left open with no call: the phone closes it with ONE location attempt, stores the reading, marks the check-out as the phone's, and tells the rep. */
    @Test fun theForgottenDayIsClosedByThePhoneWithAReadingAndTheRepIsTold() = runBlocking {
        val reads = f.fixReads
        f.trustAt(at(next(), 10, 0))
        f.day.resumeDay()
        val out = checkOuts(d0).single()
        assertEquals("one on-demand attempt", reads + 1, f.fixReads)
        assertTrue("always marked as closed by the phone", out.closedByPhone())
        val fix = f.db.captureDao().fix(out.fixClientUuid)!!
        assertEquals("the reading is stored when obtained", "ok", fix.fixStatus); assertNotNull(fix.lat)
        assertEquals(listOf(d0), f.day.rolloverNotice.value.closedDates)
        f.day.resumeDay()
        assertEquals("idempotent: not closed twice", 1, checkOuts(d0).size)
        assertFalse("a check-out captured on its own date is the rep's, not marked", out.copy(meta = out.meta.copy(capturedAt = d0 + "T10:00:00.000Z")).closedByPhone())
    }

    /**
     * D-LEAD-PREREQ-04 ruling 5 (step 3 S5): the survey and the content view of a call that crosses midnight take the VISIT's date (they used the clock's date, were refused after
     * midnight, and the survey was reported as saved). A Save the gate refuses is NOT reported as saved: the result says so and nothing is stored.
     */
    @Test fun aSurveyAndAContentViewOfACallAcrossMidnightTakeTheVisitsDateAndARefusalIsNeverReportedAsSaved() = runBlocking {
        val v = f.openCall(); f.day.visitSession.restore(v)
        f.trustAt(at(next(), 0, 5))
        val survey = com.aktcl.aron.core.database.entity.SurveyEntity(1, 1, "S", null)
        val q = com.aktcl.aron.feature.outlet.SurveyQuestion(1, com.aktcl.aron.feature.outlet.AnswerType.BOOL, "q1", true, false)
        fun rows() = listOf(com.aktcl.aron.feature.outlet.SurveyResponseRow(com.aktcl.aron.core.common.ClientIds.newUuid(), v.visitUuid, 1, 1, q, com.aktcl.aron.feature.outlet.SurveyAnswer(bool = true)))
        val ok = f.day.saveSurveyResult(v.visitUuid, survey, rows())
        assertNull(ok.dayRefusal); assertNull("stored: no attendance refusal", ok.attendance)
        assertEquals("the survey carries the visit's date", d0, f.db.captureDao().surveyResponsesOf(v.visitUuid).single().meta.businessDate)
        val item = com.aktcl.aron.core.database.entity.ContentItemEntity(7, 1, "av", "t", null, "u", "s", 1, 5, d0, next(), 1, true)
        f.day.logContentView(v.visitUuid, item, com.aktcl.aron.feature.outlet.ContentOutcome.VIEWED, null, 1000L, 1)
        assertEquals("the content view carries the visit's date", d0, f.db.captureDao().contentViewsOf(v.visitUuid).single().meta.businessDate)
        // the call ends and the phone closes D; a survey saved after that is refused by the gate and is NOT reported as saved
        f.day.closeVisitAbandoned(); f.day.resumeDay()
        assertEquals("D was closed by the phone", 1, checkOuts(d0).size)
        val q2 = com.aktcl.aron.feature.outlet.SurveyQuestion(2, com.aktcl.aron.feature.outlet.AnswerType.BOOL, "q2", true, false) // a NEW question: an answered one is an idempotent replay
        val refused = f.day.saveSurveyResult(v.visitUuid, survey, listOf(com.aktcl.aron.feature.outlet.SurveyResponseRow(com.aktcl.aron.core.common.ClientIds.newUuid(), v.visitUuid, 1, 1, q2, com.aktcl.aron.feature.outlet.SurveyAnswer(bool = true))))
        assertNotNull("a refusal comes back, never silence", refused.attendance)
        assertEquals("nothing more was stored", 1, f.db.captureDao().surveyResponsesOf(v.visitUuid).size)
    }


    /** D-AGATE-15 T2: the phone's 04:00 end classifies as the rep's close does: a zero-sale (QC only) memo or a survey answer is a real call without a sale, nothing is abandoned. */
    @Test fun theLimitClassifiesACallByWhatItHolds() = runBlocking {
        val qcCall = f.openCall(seq = 1); f.qcLineOn(qcCall)
        val surveyCall = f.openCall(outletId = 50002, seq = 2)
        val emptyCall = f.openCall(outletId = 50003, seq = 3)
        val survey = com.aktcl.aron.core.database.entity.SurveyEntity(1, 1, "S", null)
        val q = com.aktcl.aron.feature.outlet.SurveyQuestion(1, com.aktcl.aron.feature.outlet.AnswerType.BOOL, "q1", true, false)
        f.day.saveSurveyResult(surveyCall.visitUuid, survey, listOf(com.aktcl.aron.feature.outlet.SurveyResponseRow(com.aktcl.aron.core.common.ClientIds.newUuid(), surveyCall.visitUuid, 1, 1, q, com.aktcl.aron.feature.outlet.SurveyAnswer(bool = true))))
        // one call at a time is open in a session; each is restored from Room and ended at the limit, in turn
        f.trustAt(at(next(), 8, 0))
        val got = mutableMapOf<String, String>()
        repeat(3) {
            f.day.restoreOpenVisit(); f.day.abandonStaleCall()
        }
        f.db.captureDao().visitClosesOn(d0).forEach { got[it.visitClientUuid] = it.outcomeCode }
        assertEquals("zero_sale_stock_ok", got[qcCall.visitUuid]); assertEquals("a survey and no memo: what the back press writes", "abandoned", got[surveyCall.visitUuid]); assertEquals("abandoned", got[emptyCall.visitUuid])
    }

    /** D-AGATE-15 T1: an UNSAVED memo edit open across midnight never holds the day: dropped with a notice, the saved memo untouched, the day closes, the gate follows today. */
    @Test fun anUnsavedMemoEditAcrossMidnightIsDroppedAndTheDayCloses() = runBlocking {
        val v = f.openCall(); val memo = f.creditMemo(v); f.stockedCatalog()
        f.day.visitSession.restore(v); f.day.closeVisitAbandoned()  // the call is over; only the memo EDIT is open (opened from the Memo list)
        f.day.sale.prepare(); f.day.sale.beginEdit(memo, com.aktcl.aron.feature.sale.domain.EditReason.WRONG_SKU)
        SrLockFixture.await("edit open") { day().sale.flow.state.value.draft?.edit != null }
        f.day.resumeDay() // the same day: nothing is dropped
        assertNotNull("the same day: the edit stays", f.day.sale.flow.state.value.draft?.edit)
        f.trustAt(at(next(), 0, 30)); f.day.resumeDay()
        assertNull("the unsaved edit was dropped", f.day.sale.flow.state.value.draft?.edit)
        assertTrue("with a notice", f.day.rolloverNotice.value.editDropped)
        assertNotNull("the saved memo is untouched", f.db.captureDao().memo(memo))
        assertEquals("the day closed, so check-in for the new day is possible", 1, checkOuts(d0).size)
        assertEquals(AttendanceRefusal.NOT_CHECKED_IN, f.day.dayGate.value)
    }

    /** D-AGATE-15 mutex: start and ON_RESUME run resumeDay at once; one close of the call and ONE location attempt for the closed day. */
    @Test fun twoCallersOfResumeDayWriteOneCloseAndTakeOneLocationAttempt() = runBlocking {
        val v = f.openCall()
        val reads = f.fixReads
        f.trustAt(at(next(), 8, 0))
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            listOf(async { f.day.resumeDay() }, async { f.day.resumeDay() }, async { f.day.resumeDay() }).awaitAll()
        }
        assertEquals("one close for the visit", 1, f.db.captureDao().visitClosesOn(d0).count { it.visitClientUuid == v.visitUuid })
        assertEquals("one location attempt", reads + 1, f.fixReads)
        assertEquals(1, checkOuts(d0).size)
    }

    /** D-AGATE-15: the timer also fires at 04:00, the limit of an earlier call kept on screen. */
    @Test fun aTimerFiresAt0400WhileACallIsOnScreen() {
        // the next boundary after 03:00 Dhaka is 04:00, not 17:00 or midnight
        f.trustAt(at(next(), 3, 0))
        val ms = f.day.millisToNextBoundary()
        assertTrue("about an hour", ms in (3_600_000L - 5_000)..(3_600_000L + 5_000))
    }

    /** D-AGATE-15: the notice survives a process kill: rebuilt from the phone's check-out and the phone's call end, shown once, gone after the rep has seen it. */
    @Test fun theRolloverNoticeIsRebuiltAfterAKillAndShownOnce() = runBlocking {
        val v = f.openCall()
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

    /** Lead ruling: for each kind of call content the automatic end writes what the rep's own way out of that call writes (outcome and is_zero_sale). */
    @Test fun theAutomaticEndWritesWhatTheRepsOwnWayOutWritesForEveryKindOfCall() = runBlocking {
        val survey = com.aktcl.aron.core.database.entity.SurveyEntity(1, 1, "S", null)
        val q = com.aktcl.aron.feature.outlet.SurveyQuestion(1, com.aktcl.aron.feature.outlet.AnswerType.BOOL, "q1", true, false)
        suspend fun call(kind: String, outlet: Long, seq: Int): com.aktcl.aron.feature.outlet.OpenVisit {
            val v = f.openCall(outletId = outlet, seq = seq)
            when (kind) {
                "sold" -> f.creditMemo(v)
                "zero" -> f.qcLineOn(v)
                "survey" -> f.day.saveSurveyResult(v.visitUuid, survey, listOf(com.aktcl.aron.feature.outlet.SurveyResponseRow(com.aktcl.aron.core.common.ClientIds.newUuid(), v.visitUuid, 1, 1, q, com.aktcl.aron.feature.outlet.SurveyAnswer(bool = true))))
            }
            return v
        }
        val kinds = listOf("sold", "zero", "survey", "empty")
        val rep = kinds.mapIndexed { i, k -> k to call(k, 50001L + i, i + 1) }
        val auto = kinds.mapIndexed { i, k -> k to call(k, 50011L + i, i + 5) }
        // the rep's own way out of each call: the sale screen's close (sold / zero sale) or the back press (abandoned)
        rep.forEach { (k, v) ->
            f.day.visitSession.restore(v)
            when (k) { "sold" -> f.day.sale.closeVisit(com.aktcl.aron.feature.sale.domain.VisitOutcomeCode.SOLD); "zero" -> f.day.sale.closeVisit(com.aktcl.aron.feature.sale.domain.VisitOutcomeCode.ZERO_SALE); else -> f.day.closeVisitAbandoned() }
        }
        f.trustAt(at(next(), 8, 0))
        repeat(kinds.size) { f.day.restoreOpenVisit(); f.day.abandonStaleCall() }
        val closes = f.db.captureDao().visitClosesOn(d0).associateBy { it.visitClientUuid }
        kinds.indices.forEach { i ->
            val a = closes.getValue(auto[i].second.visitUuid); val r = closes.getValue(rep[i].second.visitUuid)
            assertEquals(kinds[i] + " outcome", r.outcomeCode, a.outcomeCode); assertEquals(kinds[i] + " is_zero_sale", r.isZeroSale, a.isZeroSale)
        }
    }

    private fun day() = f.day
}
