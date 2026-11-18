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
}
