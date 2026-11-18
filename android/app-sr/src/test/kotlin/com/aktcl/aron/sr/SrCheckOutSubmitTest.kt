package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.feature.attendance.AttendanceResult
import com.aktcl.aron.feature.attendance.OpenWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * D-OWNER-PREREQ-01 rule 3 on a real [SrDay] (the production wiring): the check-out submits the day by itself, in one transaction,
 * with no "Sales Submit first"; an open call or memo edit refuses it; a manual submit that already stands is not doubled; and a day the
 * rep forgot to check out of is closed by the phone at the next start, before a new check-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrCheckOutSubmitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(ArrayList())
    private var previousDefault: Thread.UncaughtExceptionHandler? = null
    private var previousOwn: Thread.UncaughtExceptionHandler? = null

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        val catcher = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        previousDefault = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler(catcher)
        previousOwn = Thread.currentThread().uncaughtExceptionHandler; Thread.currentThread().uncaughtExceptionHandler = catcher
        f = SrLockFixture(context, latePolicy = null, checkoutMinutes = 0) // the earliest check-out time is midnight: the 17:00 rule is not what these tests are about
    }

    @After fun tearDown() {
        try {
            f.close()
            assertEquals("nothing escaped by the end of the test", listOf<Throwable>(), crashes.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault); Thread.currentThread().uncaughtExceptionHandler = previousOwn
            Dispatchers.resetMain()
        }
    }

    private fun stock() = StockMovementEntity(ClientIds.newUuid(), f.meta(), "issue", 100, 10, "pack", 10, 100, null, false)
    private fun outbox(): List<String> = runBlocking { f.db.outboxDao().nextPending(100).map { it.recordType } }

    @Test fun checkOutSubmitsTheDayByItselfWithNoSalesSubmitFirst() = runBlocking {
        f.day.capture.recordStock(listOf(stock()))
        f.day.attendance.restore(f.day.attendanceToday())
        assertTrue("the check-out is offered with no Sales Submit made", f.day.attendance.state.value.checkOutEnabled)
        assertTrue(f.day.attendance.checkOut() is AttendanceResult.Done)
        val submits = f.db.captureDao().daySubmitsOn(f.date)
        assertEquals("one route-day with captures, one submit", 1, submits.size)
        assertEquals("route_day", submits.single().scope); assertEquals(f.route, submits.single().meta.routeId)
        assertTrue("pending_count is the real number, not a constant 0", submits.single().pendingCount >= 2) // the check-in and the stock row are still unsent
        assertEquals(1, f.db.captureDao().attendanceOn(f.date).count { it.kind == "check_out" })
        val order = outbox()
        assertTrue("the submit is queued before the check-out: $order", order.indexOf("day_submit") < order.lastIndexOf("attendance_event"))
        assertEquals("the day is now closed on the phone", AttendanceRefusal.CHECKED_OUT, f.day.capture.attendanceRefusal(f.date))
    }

    @Test fun aSecondPressWritesNothingMore() = runBlocking {
        f.day.capture.recordStock(listOf(stock()))
        f.day.attendance.restore(f.day.attendanceToday())
        f.day.attendance.checkOut()
        assertTrue(f.day.attendance.checkOut() is AttendanceResult.Ignored)
        assertEquals(1, f.db.captureDao().daySubmitsOn(f.date).size); assertEquals(1, outbox().count { it == "day_submit" })
    }

    @Test fun aManualSalesSubmitThatStandsIsNotDoubled() = runBlocking {
        f.day.capture.recordStock(listOf(stock()))
        f.salesSubmitted()
        f.day.attendance.restore(f.day.attendanceToday())
        assertTrue(f.day.attendance.checkOut() is AttendanceResult.Done)
        assertEquals("the rep's own submit is the only one; the check-out wrote only itself", 1, f.db.captureDao().daySubmitsOn(f.date).size)
        assertEquals(1, f.db.captureDao().attendanceOn(f.date).count { it.kind == "check_out" })
    }

    @Test fun aDayWithNoCapturesCheckOutWritesNoSubmit() = runBlocking {
        f.day.attendance.restore(f.day.attendanceToday())
        assertTrue(f.day.attendance.checkOut() is AttendanceResult.Done)
        assertEquals(0, f.db.captureDao().daySubmitsOn(f.date).size)
    }

    @Test fun anOpenCallRefusesTheCheckOutAndWritesNothing() = runBlocking {
        val v = f.openCall(); f.day.visitSession.restore(v)
        f.day.attendance.restore(f.day.attendanceToday())
        val r = f.day.attendance.checkOut()
        assertEquals(AttendanceResult.Refused(OpenWork.OPEN_CALL), r)
        assertEquals(OpenWork.OPEN_CALL, f.day.attendance.state.value.checkOutBlockedBy)
        assertEquals(0, f.db.captureDao().daySubmitsOn(f.date).size)
        assertEquals(0, f.db.captureDao().attendanceOn(f.date).count { it.kind == "check_out" })
        // closing the call opens the way (the real close: its visit_close is stored; D-AGATE-22 item 6 makes the check-out look at the STORED call too, not only the session)
        f.day.sale.closeCall()
        assertTrue(f.day.attendance.checkOut() is AttendanceResult.Done)
    }

    /** A rep who forgot to check out: the next start closes that day (its date, its submit) BEFORE a new check-in. */
    @Test fun aForgottenDayIsClosedAtTheNextStartBeforeANewCheckIn() = runBlocking {
        val yesterday = java.time.LocalDate.parse(f.date).minusDays(1).toString()
        // yesterday: a check-in and a stock row, no submit, no check-out (stored through an ungated repository, as that day's own writes)
        val plain = com.aktcl.aron.core.database.repo.CaptureRepository(f.db) { f.day.iso(f.day.currentMs()) }
        val u = ClientIds.newUuid(); val fx = com.aktcl.aron.feature.outlet.FixReading("ok", 23.79, 90.40, 8.0, false).toEntity(ClientIds.newUuid(), u, "attendance_in", 0)
        plain.recordAttendance(AttendanceEventEntity(u, f.meta(0).copy(routeId = null, businessDate = yesterday), "check_in", fx.clientUuid, null), fx)
        plain.recordStock(listOf(stock().copy(meta = f.meta().copy(businessDate = yesterday))))
        f.db.openHelper.writableDatabase.execSQL("DELETE FROM attendance_event WHERE business_date = '${f.date}'") // today: not checked in yet
        assertEquals(listOf(yesterday), plain.openAttendanceDates())
        assertEquals(listOf(yesterday), f.day.closeForgottenDays())
        assertEquals("yesterday has its submit and its check-out", 1, f.db.captureDao().daySubmitsOn(yesterday).size)
        val out = f.db.captureDao().attendanceOn(yesterday).single { it.kind == "check_out" }
        assertEquals(yesterday, out.meta.businessDate)
        assertEquals("the phone's own check-out: one location reading was asked and stored (D-LEAD-PREREQ-04 ruling 2)", "ok", f.db.query("SELECT fix_status FROM geo_fix WHERE client_uuid = '${out.fixClientUuid}'", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(emptyList<String>(), plain.openAttendanceDates())
        // idempotent: a second start finds nothing to close, and today is still "not checked in" (the rep checks in as usual)
        assertEquals(emptyList<String>(), f.day.closeForgottenDays())
        assertEquals(1, f.db.captureDao().daySubmitsOn(yesterday).size)
        assertEquals(AttendanceRefusal.NOT_CHECKED_IN, f.day.capture.attendanceRefusal(f.date))
        assertNull(f.db.captureDao().attendanceOn(f.date).firstOrNull())
    }

    /**
     * D-LEAD-PREREQ-03 ruling 1 (round-3 B1): the automatic close of a day never runs under an open call, with the clock really moved past Dhaka midnight
     * (not by deleting rows); the open day's screens stay open and its check-in is still what the Attendance screen restores; when the call ends the close runs.
     */
    @Test fun theAutomaticCloseWaitsForAnOpenCallAcrossMidnightThenRuns() = runBlocking {
        val d0 = f.date // d0 follows the (moved) clock: keep the day of the check-in
        val date = java.time.LocalDate.parse(d0)
        val v = f.openCall(); f.day.visitSession.restore(v)
        f.trustAt(date.plusDays(1).atTime(0, 5).toInstant(java.time.ZoneOffset.ofHours(6)).toEpochMilli())
        assertEquals(date.plusDays(1).toString(), f.day.businessDate())
        assertEquals("nothing is closed under the open call", emptyList<String>(), f.day.closeForgottenDays())
        assertEquals(0, f.db.captureDao().daySubmitsOn(d0).size)
        assertEquals(0, f.db.captureDao().attendanceOn(d0).count { it.kind == "check_out" })
        f.day.refreshDayGate()
        assertEquals("only the open call's screens stay open (D-LEAD-PREREQ-04 ruling 4): no midnight check-in is asked for, every other screen names the reason",
            AttendanceRefusal.EARLIER_CALL_OPEN, f.day.dayGate.value)
        assertTrue("the Attendance screen restores yesterday's open check-in", f.day.attendanceForScreen().any { it.kind == "check_in" && it.meta.businessDate == d0 })
        f.day.visitSession.close()
        assertEquals("when the call ends the close runs", listOf(d0), f.day.closeForgottenDays())
        assertEquals(1, f.db.captureDao().attendanceOn(d0).count { it.kind == "check_out" })
        f.day.refreshDayGate()
        assertEquals(AttendanceRefusal.NOT_CHECKED_IN, f.day.dayGate.value)
    }

    /** The boundary timer reads `cfg.day.checkout_earliest_time`, not a constant 17:00 (D-AGATE-06). */
    @Test fun theBoundaryTimerFollowsTheConfiguredCheckOutTime() {
        val date = java.time.LocalDate.parse(f.date)
        f.trustAt(date.atTime(10, 0).toInstant(java.time.ZoneOffset.ofHours(6)).toEpochMilli())
        val at11 = SrLockFixture(context, latePolicy = null, checkoutMinutes = 11 * 60)
        val at17 = SrLockFixture(context, latePolicy = null, checkoutMinutes = 17 * 60)
        try {
            at11.trustAt(date.atTime(10, 0).toInstant(java.time.ZoneOffset.ofHours(6)).toEpochMilli())
            at17.trustAt(date.atTime(10, 0).toInstant(java.time.ZoneOffset.ofHours(6)).toEpochMilli())
            val h = 3_600_000L
            assertTrue("11:00 configured, 10:00 now: about an hour (was always 7 h)", at11.day.millisToNextBoundary() in (h - 5_000)..(h + 5_000))
            assertTrue("17:00 configured: about seven hours", at17.day.millisToNextBoundary() in (7 * h - 5_000)..(7 * h + 5_000))
        } finally { at11.close(); at17.close() }
    }
}
