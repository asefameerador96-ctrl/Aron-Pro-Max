package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.DayRefusal
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-OWNER-PREREQ-01 rule 3 (check-out submits the day) on the real Room database: ONE local transaction for the submit(s) and the
 * check-out, the day of the OPEN CHECK-IN (also after midnight), the real pending count, and the outbox order: the submit is held
 * until every earlier record of its date is final, the check-out until the submit is, whatever retries or isolation do.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckOutSubmitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository
    private val D = "2026-10-05"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T11:30:00.000Z" }
    }
    @After fun tearDown() = db.close()

    private fun count(table: String): Int = db.query("SELECT COUNT(*) FROM `$table`", null).use { it.moveToFirst(); it.getInt(0) }
    private suspend fun checkIn(date: String = D) {
        val (e, f) = TestRows.attendance()
        repo.recordAttendance(e.copy(meta = e.meta.copy(businessDate = date, capturedAt = "2026-10-05T04:00:00.000Z")), f)
    }
    private fun checkOutEvent(date: String = D, at: String = "2026-10-05T11:30:00.000Z"): Pair<AttendanceEventEntity, com.aktcl.aron.core.database.entity.GeoFixEntity> {
        val u = ClientIds.newUuid(); val f = TestRows.fix(u, "attendance_out")
        return AttendanceEventEntity(u, TestRows.meta(routeId = null).copy(businessDate = date, capturedAt = at), "check_out", f.clientUuid, null) to f
    }
    private fun submit(date: String = D, route: Long? = 10231, cycle: Int = 1, scope: String = "route_day") =
        DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(routeId = route).copy(businessDate = date), scope, cycle, "{}", MONEY, 0, 0, 0, false, 0, 0, false)

    // ---- one transaction

    @Test fun theSubmitsAndTheCheckOutAreWrittenTogetherSubmitsFirst() = runTest {
        checkIn()
        repo.recordStock(listOf(TestRows.stock()))
        val (e, f) = checkOutEvent()
        repo.recordCheckOut(e, f) { listOf(submit(route = 10231), submit(route = 10232)) }
        assertEquals(2, count("day_submit")); assertEquals(2, count("attendance_event"))
        val order = db.outboxDao().nextPending(100).map { it.recordType }
        assertEquals("both submits before the check-out in commit order", listOf("attendance_event", "stock_movement", "day_submit", "day_submit", "attendance_event"), order)
        assertEquals(1, db.captureDao().attendanceOn(D).count { it.kind == "check_out" })
    }

    /** A kill (or a failure) between ANY two steps leaves all of it or nothing. */
    @Test fun aFailureAtAnyStepLeavesNothingBehind() = runTest {
        checkIn()
        val before = listOf("day_submit", "attendance_event", "geo_fix", "outbox").map(::count)
        // step 1 fails: the second submit is invalid (a cycle clash), after the first was written
        val (e1, f1) = checkOutEvent()
        val dup = submit(route = 10231, cycle = 1)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { repo.recordCheckOut(e1, f1) { listOf(dup, dup.copy(clientUuid = ClientIds.newUuid())) } } }
        assertEquals(before, listOf("day_submit", "attendance_event", "geo_fix", "outbox").map(::count))
        // step 2 fails: the submit was written, then the fix row clashes (a stored geo_fix with the same uuid)
        val (e2, f2) = checkOutEvent()
        repo.recordBreadcrumb(f2.clientUuid, TestRows.meta(routeId = null), TestRows.fix(f2.clientUuid, "breadcrumb", uuid = f2.clientUuid).copy(ownerClientUuid = f2.clientUuid))
        val mid = listOf("day_submit", "attendance_event", "outbox").map(::count)
        assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { repo.recordCheckOut(e2, f2) { listOf(submit()) } } }
        assertEquals("the submit written before the clash was rolled back", mid, listOf("day_submit", "attendance_event", "outbox").map(::count))
        // and a clean retry of the SAME press afterwards writes everything once
        val (e3, f3) = checkOutEvent()
        repo.recordCheckOut(e3, f3) { listOf(submit()) }
        assertEquals(1, count("day_submit")); assertEquals(2, count("attendance_event"))
    }

    @Test fun aCheckOutNeedsAnOpenCheckInAndIsNeverWrittenTwice() = runTest {
        val (e0, f0) = checkOutEvent()
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { repo.recordCheckOut(e0, f0) { listOf(submit()) } } }
        assertEquals(0, count("day_submit"))
        checkIn()
        val (e, f) = checkOutEvent(); repo.recordCheckOut(e, f) { listOf(submit()) }
        val (e2, f2) = checkOutEvent()
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { repo.recordCheckOut(e2, f2) { listOf(submit(cycle = 2)) } } }
        assertEquals(1, count("day_submit")); assertEquals(2, count("attendance_event"))
    }

    // ---- the day of the open check-in, also after midnight

    @Test fun aCheckOutAfterMidnightClosesTheDayOfTheOpenCheckInAndItsSubmits() = runTest {
        checkIn(D)
        repo.recordStock(listOf(TestRows.stock()))
        // it is now 00:20 on the 6th (capture time), the check-in was on the 5th
        val (e, f) = checkOutEvent(date = D, at = "2026-10-05T18:20:00.000Z")
        repo.recordCheckOut(e, f) { date -> assertEquals("the builder is told the open check-in's day", D, date); listOf(submit(date = date)) }
        assertEquals(setOf(D), db.captureDao().daySubmitsOn(D).map { it.meta.businessDate }.toSet())
        assertEquals(D, db.captureDao().attendanceOn(D).single { it.kind == "check_out" }.meta.businessDate)
        assertEquals(emptyList<String>(), repo.openAttendanceDates()) // nothing left open
        // submits of ANOTHER date than the check-in's are refused
        checkIn("2026-10-06")
        val (e2, f2) = checkOutEvent(date = "2026-10-06")
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.recordCheckOut(e2, f2) { listOf(submit(date = D, cycle = 5)) } } }
    }

    @Test fun aForgottenDayIsFoundAsAnOpenDate() = runTest {
        checkIn("2026-10-03"); checkIn("2026-10-05")
        val (e, f) = checkOutEvent(date = "2026-10-05"); repo.recordCheckOut(e, f)
        assertEquals(listOf("2026-10-03"), repo.openAttendanceDates())
    }

    // ---- pending_count is the real number

    @Test fun pendingCountIsTheRowsStillWaitingAtThatMoment() = runTest {
        checkIn(); repo.recordStock(listOf(TestRows.stock(), TestRows.stock(skuId = 101)))
        val waiting = db.outboxDao().unsentOn(D)
        assertEquals(3, waiting) // the check-in and two stock rows, none acked
        val (e, f) = checkOutEvent(); repo.recordCheckOut(e, f) { listOf(submit()) }
        assertEquals(waiting, db.captureDao().daySubmitsOn(D).single().pendingCount)
        // after the check-in is acked the number follows
        val (e2, f2) = checkOutEvent(date = "2026-10-06"); checkIn("2026-10-06")
        val rows = db.outboxDao().nextPending(100)
        db.outboxDao().applyAck(rows.first { it.recordType == "attendance_event" && it.businessDate == "2026-10-06" }.clientUuid, "acked", null, null, "2026-10-06T05:00:00.000Z")
        repo.recordCheckOut(e2, f2) { listOf(submit(date = "2026-10-06", cycle = 1)) }
        assertEquals(0, db.captureDao().daySubmitsOn("2026-10-06").single().pendingCount)
    }

    // ---- the outbox order: the submit waits for every earlier record, the check-out for the submit

    private suspend fun seedDay(): Triple<String, String, String> {
        checkIn(); repo.recordStock(listOf(TestRows.stock()))
        val (e, f) = checkOutEvent(); repo.recordCheckOut(e, f) { listOf(submit()) }
        val rows = db.outboxDao().nextPending(100)
        return Triple(rows.first { it.recordType == "stock_movement" }.clientUuid, rows.first { it.recordType == "day_submit" }.clientUuid, rows.last { it.recordType == "attendance_event" }.clientUuid)
    }
    private suspend fun sendable(excluded: List<String> = listOf("none")) = db.outboxDao().nextSendable(100, 10, excluded).map { it.recordType }

    @Test fun theSubmitIsHeldUntilEveryEarlierRecordIsFinalThenTheCheckOutAfterIt() = runTest {
        val (stock, sub, out) = seedDay()
        val checkIn = db.outboxDao().nextPending(100).first { it.recordType == "attendance_event" }.clientUuid
        assertEquals("the check-in and the stock go; the submit and the check-out wait", listOf("attendance_event", "stock_movement"), sendable())
        db.outboxDao().applyAck(checkIn, "acked", null, null, "t")
        assertEquals(listOf("stock_movement"), sendable()) // still waiting for the stock row
        db.outboxDao().applyAck(stock, "acked", null, null, "t")
        assertEquals("every earlier record is final: the submit is sent, the check-out still waits", listOf("day_submit"), sendable())
        db.outboxDao().applyAck(sub, "acked", null, null, "t")
        assertEquals("the submit is final: now the check-out", listOf("attendance_event"), sendable())
        db.outboxDao().applyAck(out, "acked", null, null, "t")
        assertEquals(emptyList<String>(), sendable())
    }

    /** A failed and retried row (back to pending with attempts) keeps the submit waiting; rejected is final and releases it. */
    @Test fun aFailedAndRetriedRowHoldsTheSubmitUntilItIsFinal() = runTest {
        val (stock, sub, _) = seedDay()
        val checkIn = db.outboxDao().nextPending(100).first { it.recordType == "attendance_event" }.clientUuid
        db.outboxDao().applyAck(checkIn, "acked", null, null, "t")
        // the stock row failed twice with a retryable code and went back to pending
        db.outboxDao().markInFlight("b1", listOf(db.outboxDao().byClientUuid(stock)!!.seq))
        db.outboxDao().applyAck(stock, "pending", "scope_stale", null, "t"); db.outboxDao().countFailure(stock); db.outboxDao().countFailure(stock)
        assertEquals("a retry is not final", listOf("stock_movement"), sendable())
        // a bisect-isolated family: this run leaves it out, and the submit STILL waits for it
        val family = db.outboxDao().byClientUuid(stock)!!.familyUuid
        assertEquals(emptyList<String>(), sendable(excluded = listOf(family)))
        // it is rejected for good (retry_exhausted): final, so the submit goes
        db.outboxDao().applyAck(stock, "rejected", "retry_exhausted", null, "t")
        assertEquals(listOf("day_submit"), sendable())
        assertEquals(sub, db.outboxDao().nextSendable(10, 10, listOf("none")).single().clientUuid)
    }

    @Test fun aRowInFlightAlsoHoldsTheSubmitAndAnotherDatesRowsDoNot() = runTest {
        val (stock, _, _) = seedDay()
        val checkIn = db.outboxDao().nextPending(100).first { it.recordType == "attendance_event" }.clientUuid
        db.outboxDao().applyAck(checkIn, "acked", null, null, "t")
        db.outboxDao().markInFlight("b2", listOf(db.outboxDao().byClientUuid(stock)!!.seq)) // sent, not yet answered
        assertEquals("in flight is not final: nothing of the day's tail goes", emptyList<String>(), sendable())
        db.outboxDao().applyAck(stock, "acked", null, null, "t")
        // a stuck row of ANOTHER business date does not hold this day's submit
        db.outboxDao().insert(listOf(OutboxEntity(clientUuid = ClientIds.newUuid(), recordType = "stock_movement", familyUuid = ClientIds.newUuid(), rank = 0, businessDate = "2026-10-04", payloadJson = "{}", payloadSha256 = "x", createdAt = "t")))
        assertTrue("day_submit" in sendable())
    }

    // ---- a refused submit is not a trap

    @Test fun aSubmitTheServerRefusedOrHoldsDoesNotLockTheDay() = runTest {
        checkIn()
        val (e, f) = checkOutEvent(); repo.recordCheckOut(e, f) { listOf(submit()) }
        val sub = db.outboxDao().nextPending(100).first { it.recordType == "day_submit" }.clientUuid
        val meta = TestRows.meta()
        assertEquals("the submit stands: the route-day is locked", DayRefusal.SALES_SUBMIT, repo.dayRefusal(meta, "memo"))
        assertTrue(repo.submitStands(D))
        db.outboxDao().applyAck(sub, "acked", null, null, "t")
        assertEquals("an ACCEPTED submit stays a lock", DayRefusal.SALES_SUBMIT, repo.dayRefusal(meta, "memo"))
        // a fresh day for the refusal cases
    }

    @Test fun aRejectedSubmitLiftsTheLockAndAQuarantinedOneToo() = runTest {
        for (state in listOf("rejected", "quarantined")) {
            val date = if (state == "rejected") "2026-10-07" else "2026-10-08"
            checkIn(date)
            val (e, f) = checkOutEvent(date = date); repo.recordCheckOut(e, f) { listOf(submit(date = date)) }
            val sub = db.outboxDao().nextPending(100).first { it.recordType == "day_submit" && it.businessDate == date }.clientUuid
            val meta = TestRows.meta().copy(businessDate = date)
            assertEquals(DayRefusal.SALES_SUBMIT, repo.dayRefusal(meta, "memo"))
            db.outboxDao().applyAck(sub, state, if (state == "rejected") "schema_invalid" else "payload_conflict", null, "t")
            assertNull("$state: the lock lifts, the rep is not trapped", repo.dayRefusal(meta, "memo"))
            assertFalse(repo.submitStands(date))
            // and he can submit again: the next cycle
            repo.recordDaySubmit(submit(date = date, cycle = 2))
            assertEquals(2, db.captureDao().daySubmitsFor(date, 10231).size)
        }
    }

    private companion object {
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }
}
