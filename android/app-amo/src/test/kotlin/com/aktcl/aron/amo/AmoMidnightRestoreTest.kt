package com.aktcl.aron.amo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.database.repo.closedByPhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertEquals("abandoned", close.outcomeCode); assertEquals(d0, close.meta.businessDate)
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
}
