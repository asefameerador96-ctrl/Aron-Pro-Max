package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.database.repo.closedByPhone
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        assertEquals("abandoned", close.outcomeCode); assertEquals("dated with the VISIT's date", d0, close.meta.businessDate)
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

    private fun day() = f.day
}
