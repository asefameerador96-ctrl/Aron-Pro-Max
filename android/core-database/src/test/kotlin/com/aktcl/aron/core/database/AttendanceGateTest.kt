package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.repo.AttendanceGate
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.database.repo.AttendanceRefusedException
import com.aktcl.aron.core.database.repo.CaptureRepository
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
 * D-OWNER-PREREQ-01 rule 1 (check-in gate), D-LEAD-PREREQ-02: every operational write is refused INSIDE its transaction unless the
 * record's own business date has an open check-in (stored attendance, never an in-memory copy), and again after the check-out.
 * Each refused write is proven to leave nothing behind; each exemption is proven one by one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AttendanceGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var gated: CaptureRepository   // the SR / AMO day object's repository: requireCheckIn = true
    private val now = "2026-10-05T04:36:00.000Z"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        gated = CaptureRepository(db, requireCheckIn = true) { now }
    }
    @After fun tearDown() = db.close()

    private fun count(table: String): Int = db.query("SELECT COUNT(*) FROM `$table`", null).use { it.moveToFirst(); it.getInt(0) }
    private suspend fun checkIn(date: String = "2026-10-05", at: String = "2026-10-05T04:00:00.000Z", fixOk: Boolean = true) {
        val (e, f) = TestRows.attendance()
        gated.recordAttendance(e.copy(meta = e.meta.copy(businessDate = date, capturedAt = at)), if (fixOk) f else f.copy(fixStatus = "timeout", lat = null, lng = null, accuracyM = null))
    }
    private suspend fun checkOut(date: String = "2026-10-05", at: String = "2026-10-05T11:30:00.000Z") {
        val u = ClientIds.newUuid(); val f = TestRows.fix(u, "attendance_out")
        gated.recordAttendance(AttendanceEventEntity(u, TestRows.meta(routeId = null).copy(businessDate = date, capturedAt = at), "check_out", f.clientUuid, null), f)
    }
    private fun refused(why: AttendanceRefusal, block: suspend () -> Unit) {
        val e = assertThrows(AttendanceRefusedException::class.java) { kotlinx.coroutines.runBlocking { block() } }
        assertEquals(why, e.why)
    }

    // ---- the pure decision

    @Test fun theDecisionIsReadFromStoredAttendanceByBusinessDate() = runTest {
        assertEquals(AttendanceRefusal.NOT_CHECKED_IN, gated.attendanceRefusal("2026-10-05"))
        checkIn()
        assertNull(gated.attendanceRefusal("2026-10-05"))
        assertEquals("yesterday's check-in is not today's", AttendanceRefusal.NOT_CHECKED_IN, gated.attendanceRefusal("2026-10-06"))
        checkOut()
        assertEquals(AttendanceRefusal.CHECKED_OUT, gated.attendanceRefusal("2026-10-05"))
        assertTrue(AttendanceGate.refusal(emptyList()) == AttendanceRefusal.NOT_CHECKED_IN)
    }

    // ---- every operational write, one by one: refused without a check-in, and nothing is left behind

    @Test fun stockIsRefusedWithoutACheckInAndLeavesNothing() = runTest {
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordStock(listOf(TestRows.stock())) }
        assertEquals(0, count("stock_movement")); assertEquals(0, count("outbox"))
    }

    @Test fun aVisitOpenIsRefusedWithoutACheckIn() = runTest {
        val (v, f) = TestRows.visit()
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordVisitOpen(v, f) }
        assertEquals(0, count("visit")); assertEquals(0, count("geo_fix"))
    }

    @Test fun aSaleAndANumberedSaleAreRefusedWithoutACheckInAndTakeNoMemoNumber() = runTest {
        // the visit exists (opened through an UNGATED repository, as a call restored from before the gate would be)
        val plain = CaptureRepository(db) { now }
        val (v, f) = TestRows.visit(); plain.recordVisitOpen(v, f)
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordSale(TestRows.sale(v.clientUuid)) }
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordNumberedSale(TestRows.sale(v.clientUuid), com.aktcl.aron.core.database.repo.MemoNumbering("sr334001", 0)) }
        assertEquals(0, count("memo")); assertEquals(0, count("memo_line"))
        assertNull("no memo number was taken", db.referenceDao().memoCounter("2026-10-05"))
    }

    @Test fun aDueCollectionAndASkipAreRefusedWithoutACheckIn() = runTest {
        val skip = VisitSkipEntity(ClientIds.newUuid(), TestRows.meta(), 50002, "closed")
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordVisitSkip(skip) }
        assertEquals(0, count("visit_skip"))
        val due = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000)
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordDueCollection(due) }
        assertEquals(0, count("due_collection"))
        assertFalse("Mark Paid is not offered either", gated.acceptsDueCollection(TestRows.meta()))
    }

    @Test fun anOutletRequestIsRefusedWithoutACheckIn() = runTest {
        val f = TestRows.fix("x")
        val req = OutletChangeRequestEntity(ClientIds.newUuid(), TestRows.meta(routeId = null), "info", 50001, """{"name":"Rahim Store"}""", f.clientUuid)
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordOutletRequest(req, f.copy(ownerClientUuid = req.clientUuid)) }
        assertEquals(0, count("outlet_change_request"))
    }

    @Test fun anAmoRecordATaskResolveAndAClusterAreRefusedWithoutACheckIn() = runTest {
        val u = ClientIds.newUuid()
        val rec = com.aktcl.aron.core.database.entity.AmoRecordEntity(
            u, TestRows.meta(routeId = null), "task", u, 0,
            com.aktcl.aron.core.database.record.AmoPayloads.task("visit_followup", 1, null, "t", null, null, null).toString(),
        )
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordAmo(rec) }
        assertEquals(0, count("amo_record"))
        val ev = com.aktcl.aron.core.database.entity.TaskEventEntity(ClientIds.newUuid(), TestRows.meta(routeId = null), ClientIds.newUuid(), "resolved")
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.resolveTask(ev, now) }
        val f = TestRows.fix("c")
        val cluster = com.aktcl.aron.core.database.entity.ClusterCreateEntity(ClientIds.newUuid(), "Cluster A", 10231, "Route", now, "2026-10-05", f.clientUuid)
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordClusterCreate(cluster, f.copy(ownerClientUuid = cluster.clientUuid)) }
        assertEquals(0, count("cluster_create"))
        // after the check-in the AMO record passes
        checkIn()
        assertTrue(gated.recordAmo(rec))
    }

    // ---- after the check-in the same writes pass; after the check-out they are refused again

    @Test fun afterCheckInTheWritesPassAndAfterCheckOutTheyAreRefusedAgain() = runTest {
        checkIn()
        gated.recordStock(listOf(TestRows.stock()))
        val (v, f) = TestRows.visit(); gated.recordVisitOpen(v, f)
        assertEquals(1, count("stock_movement")); assertEquals(1, count("visit"))
        // the CLOSE of the call already open is exempt even after the check-out (the rep must be able to end it)
        checkOut()
        refused(AttendanceRefusal.CHECKED_OUT) { gated.recordStock(listOf(TestRows.stock())) }
        refused(AttendanceRefusal.CHECKED_OUT) { val (v2, f2) = TestRows.visit(seq = 2); gated.recordVisitOpen(v2, f2) }
        refused(AttendanceRefusal.CHECKED_OUT) { gated.recordSale(TestRows.sale(v.clientUuid)) }
        assertEquals(1, count("stock_movement")); assertEquals(1, count("visit")); assertEquals(0, count("memo"))
    }

    /** Across Dhaka midnight: a check-in of the 5th does not open the 6th, because the gate reads the RECORD's own business date. */
    @Test fun aRecordOfTheNextBusinessDateNeedsItsOwnCheckIn() = runTest {
        checkIn(date = "2026-10-05")
        val next = TestRows.stock().copy(meta = TestRows.meta().copy(businessDate = "2026-10-06", capturedAt = "2026-10-05T18:10:00.000Z"))
        refused(AttendanceRefusal.NOT_CHECKED_IN) { gated.recordStock(listOf(next)) }
        checkIn(date = "2026-10-06", at = "2026-10-05T18:30:00.000Z")
        gated.recordStock(listOf(next.copy(clientUuid = ClientIds.newUuid())))
        assertEquals(1, count("stock_movement"))
    }

    /** A check-in with no GPS fix still opens the day (and is flagged by its fix status, not refused). */
    @Test fun aCheckInWithNoFixStillOpensTheDay() = runTest {
        checkIn(fixOk = false)
        assertNull(gated.attendanceRefusal("2026-10-05"))
        gated.recordStock(listOf(TestRows.stock()))
        assertEquals(1, count("stock_movement"))
        assertEquals("timeout", db.query("SELECT fix_status FROM geo_fix WHERE purpose = 'attendance_in'", null).use { it.moveToFirst(); it.getString(0) })
    }

    // ---- the exemptions, one by one (a gated repository, no check-in at all)

    @Test fun theCheckInItselfAndTheCheckOutAreNeverGated() = runTest {
        checkIn()      // no check-in before it: it is what opens the day
        checkOut()     // and the check-out is a write on a checked-in day, never refused by this gate
        assertEquals(2, count("attendance_event"))
    }

    @Test fun theCloseOfACallAlreadyOpenIsExemptEvenAfterCheckOut() = runTest {
        val plain = CaptureRepository(db) { now }
        val (v, f) = TestRows.visit(); plain.recordVisitOpen(v, f)
        checkIn(); checkOut() // the day is over, a call is still open
        gated.recordVisitClose(TestRows.close(v.clientUuid))
        assertEquals(1, count("visit_close"))
    }

    @Test fun salesSubmitIsExemptWithoutACheckIn() = runTest {
        gated.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
        assertEquals(1, count("day_submit"))
    }

    @Test fun breadcrumbsAndDeviceRecordsAreExempt() = runTest {
        val u = ClientIds.newUuid(); val f = TestRows.fix(u, "breadcrumb")
        gated.recordBreadcrumb(u, TestRows.meta(routeId = null), f)
        assertEquals(1, count("geo_fix"))
    }

    private companion object {
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }

    /** D-LEAD-PREREQ-03 ruling 7: ANY stored check-out makes the date checked out, whatever the clock order (a check-out captured on an earlier clock than the check-in). */
    @Test fun aStoredCheckOutClosesTheDateWhateverTheClockOrder() = runTest {
        checkIn(at = "2026-10-05T10:00:00.000Z")
        checkOut(at = "2026-10-05T09:00:00.000Z") // captured on an earlier clock than the check-in
        assertEquals(AttendanceRefusal.CHECKED_OUT, gated.attendanceRefusal("2026-10-05"))
        refused(AttendanceRefusal.CHECKED_OUT) { gated.recordStock(listOf(TestRows.stock())) }
    }

    /** Round-3 low: a retry of a memo already saved is not a new write: after the day closed it does NOT become a new check-in refusal (it behaves as the replay it is). */
    @Test fun aReplayOfASavedMemoIsNotRefusedByTheGateAfterCheckOut() = runTest {
        checkIn()
        val (v, f) = TestRows.visit(); gated.recordVisitOpen(v, f)
        val sale = TestRows.sale(v.clientUuid)
        gated.recordSale(sale)
        checkOut()
        val thrown = runCatching { gated.recordSale(sale) }.exceptionOrNull()
        assertFalse("the replay is not judged as a new write: $thrown", thrown is AttendanceRefusedException)
        assertEquals("and it wrote nothing more", 1, count("memo"))
    }
}
