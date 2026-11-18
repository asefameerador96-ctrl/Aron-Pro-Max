package com.aktcl.aron.feature.attendance

import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.FixReading
import com.aktcl.aron.feature.outlet.LocationFixSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-011 and F-SR-012: airplane mode throughout (no network port is used by check-in or check-out). */
class AttendanceFlowTest {
    private var minutes = 9 * 60
    private var n = 0
    private val committed = mutableListOf<Pair<AttendanceEventEntity, GeoFixEntity>>()
    private var fixResult = FixReading("ok", 23.793812, 90.404112, 9.0, false)
    private val fixes = object : LocationFixSource { override suspend fun readFix(purpose: String, refreshCount: Int) = fixResult }
    private val meta = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", "2026-10-07T04:00:00.000Z", 1, 1, 0, true, r, null, "2026-10-07:1", false, 5) }

    private fun flow(resolver: suspend (Double, Double) -> String? = { _, _ -> null }) = AttendanceFlow(
        fixes, meta, { e, f -> committed += e to f }, { null }, { "2026-10-07T0${++n}:00:00.000Z" }, { minutes },
        newUuid = { "00000000-0000-4000-8000-%012d".format(++n) }, addressResolver = resolver,
    )

    @Test fun offlineCheckInStoresOneFixTheTimeAndMockFlagAndDisablesTheButton() = runTest {
        fixResult = fixResult.copy(isMock = true)
        val f = flow()
        assertTrue(f.checkIn() is AttendanceResult.Done)
        assertEquals(1, committed.size)
        val (e, gf) = committed.single()
        assertEquals("check_in", e.kind); assertEquals("attendance_in", gf.purpose); assertTrue(gf.isMock)
        assertEquals(gf.clientUuid, e.fixClientUuid)
        val s = f.state.value
        assertFalse(s.checkInEnabled); assertFalse(s.showCheckInPrompt); assertTrue(s.lastFixMock)
        assertEquals("23.793812, 90.404112", s.addressText) // coordinates offline
        assertNull(s.checkedOutAt)
        assertTrue(f.checkIn() is AttendanceResult.Ignored) // second press does nothing
        assertEquals(1, committed.size)
    }

    /**
     * D-OWNER-PREREQ-01 rule 1: across Dhaka midnight the in-memory state must follow the STORED events of the new business date. `tick()` only
     * re-uses the old copy (it kept yesterday's check-in), so the SR boundary, the resume and the AMO resume now call `restore()` with the new
     * date's (empty) rows: yesterday's check-in stops reading as today's, and check-in is offered again.
     */
    @Test fun restoringWithTheNewDatesEmptyRowsClearsYesterdaysCheckIn() = runTest {
        val f = flow()
        f.checkIn()
        assertTrue(f.state.value.checkedInAt != null)
        f.tick() // the old behaviour at the boundary: still "checked in"
        assertTrue(f.state.value.checkedInAt != null)
        f.restore(emptyList()) // the new business date has no attendance
        assertNull(f.state.value.checkedInAt); assertTrue(f.state.value.checkInEnabled); assertFalse(f.state.value.checkOutEnabled)
        assertTrue(f.checkIn() is AttendanceResult.Done) // and a new check-in is possible
    }

    @Test fun onlineAddressReplacesCoordinatesButNeverBlocksTheCommit() = runTest {
        val f = flow { _, _ -> "Gulshan, Dhaka" }
        f.checkIn()
        assertEquals("Gulshan, Dhaka", f.state.value.addressText)
        val g = flow { _, _ -> error("geocoder down") }
        g.checkIn()
        assertEquals("23.793812, 90.404112", g.state.value.addressText)
    }

    @Test fun failedFixStillRecordsTheCheckInWithNoCoordinates() = runTest {
        fixResult = FixReading("timeout", null, null, null, false)
        val f = flow(); f.checkIn()
        assertEquals("timeout", committed.single().second.fixStatus)
        assertNull(f.state.value.addressText)
    }

    @Test fun checkOutNeedsCheckInAndSeventeenHundredDhakaTime() = runTest {
        val f = flow()
        assertTrue(f.checkOut() is AttendanceResult.Ignored)
        f.checkIn()
        assertFalse(f.state.value.checkOutEnabled)
        assertTrue(f.checkOut() is AttendanceResult.Ignored)
        minutes = 16 * 60 + 59; f.tick(); assertFalse(f.state.value.checkOutEnabled)
        minutes = 17 * 60; f.tick(); assertTrue(f.state.value.checkOutEnabled)
        assertTrue(f.checkOut() is AttendanceResult.Done)
        assertEquals("check_out", committed.last().first.kind)
        assertEquals("attendance_out", committed.last().second.purpose)
        assertFalse(f.state.value.checkOutEnabled)
        assertTrue(f.checkOut() is AttendanceResult.Ignored)
        assertEquals(2, committed.size)
    }

    @Test fun restoreAfterKillRebuildsTheScreenFromStoredEvents() = runTest {
        val f = flow(); f.checkIn()
        val stored = committed.map { it.first }
        val g = flow(); g.restore(stored)
        assertFalse(g.state.value.checkInEnabled)
        assertTrue(g.checkIn() is AttendanceResult.Ignored)
        minutes = 18 * 60; g.tick(); assertTrue(g.state.value.checkOutEnabled)
        g.restore(stored + committed.map { it.first.copy(kind = "check_out", meta = it.first.meta.copy(capturedAt = "2026-10-07T12:00:00.000Z")) })
        assertFalse(g.state.value.checkOutEnabled)
    }

    @Test fun aFailedSaveReportsFailedAndLeavesTheButtonsAlone() = runTest {
        val f = AttendanceFlow(fixes, meta, { _, _ -> error("disk full") }, { null }, { "t" }, { minutes }, newUuid = { "00000000-0000-4000-8000-%012d".format(++n) })
        assertEquals(AttendanceResult.Failed, f.checkIn())
        assertTrue(f.state.value.checkInEnabled); assertFalse(f.state.value.busy); assertNull(f.state.value.checkedInAt)
    }

    @Test fun refreshIsDisplayOnlyAndFillsTheLocationCard() = runTest {
        fixResult = FixReading("ok", 23.792580, 90.407830, 64.4, false)
        val f = flow { _, _ -> "Gulshan, Dhaka" }
        f.refreshFix()
        val s = f.state.value
        assertEquals(0, committed.size) // nothing stored, nothing committed
        assertEquals("23.792580, 90.407830", s.coordinatesText); assertEquals(64, s.fixAccuracyM); assertFalse(s.fixFailed)
        assertEquals("Gulshan, Dhaka", s.addressText); assertFalse(s.readingFix)
        assertTrue(s.checkInEnabled) // the buttons never depend on the refresh
        fixResult = FixReading("timeout", null, null, null, false)
        f.refreshFix()
        assertTrue(f.state.value.fixFailed); assertEquals("23.792580, 90.407830", f.state.value.coordinatesText) // the last good reading stays
        f.checkIn()
        assertEquals("attendance_in", committed.single().second.purpose) // the refresh never changes the event's purpose
    }

    /**
     * D-LEAD-PREREQ-04 ruling 2 (step 3 S2) withdraws ruling 3 of D-LEAD-PREREQ-03: after Dhaka midnight there is no rep check-out for the earlier day; the phone closes it.
     * The old path was unreachable in the app (the phone's own close always came first) and is removed: yesterday's open check-in shows no check-out button at any
     * hour and a press writes nothing.
     */
    @Test fun anOpenCheckInOfAnEarlierDayOffersNoRepCheckOutAtAnyHour() = runTest {
        var today = "2026-10-07"
        val m = CaptureMetaProvider { r -> CaptureMeta(today, today + "T04:00:00.000Z", 1, 1, 0, true, r, null, "b", false, 5) }
        val f = AttendanceFlow(fixes, m, { e, fx -> committed += e to fx }, { null }, { "2026-10-07T0${++n}:00:00.000Z" }, { minutes }, newUuid = { "00000000-0000-4000-8000-%012d".format(++n) })
        minutes = 10 * 60
        assertTrue(f.checkIn() is AttendanceResult.Done)
        val checkIn = committed.single().first
        for (m2 in listOf(10, 9 * 60, 17 * 60, 23 * 60)) {
            today = "2026-10-08"; minutes = m2
            f.restore(listOf(checkIn))
            assertTrue("yesterday's check-in is shown", f.state.value.checkedInAt != null)
            assertTrue("no check-out button at minute $m2 of the next day", !f.state.value.checkOutEnabled && !f.state.value.checkOutTimeReached)
            assertTrue("a press is ignored", f.checkOut() is AttendanceResult.Ignored)
        }
        assertEquals("nothing but the check-in was written", 1, committed.size)
        // the same day still keeps its earliest-time rule: a fresh check-in at 09:00 cannot check out at 10:00
        today = "2026-10-08"; minutes = 9 * 60
        val g = AttendanceFlow(fixes, m, { e, fx -> committed += e to fx }, { null }, { "t" }, { minutes }, newUuid = { "00000000-0000-4000-8000-%012d".format(++n) })
        g.checkIn(); minutes = 10 * 60
        assertTrue(g.checkOut() is AttendanceResult.Ignored)
    }

    /**
     * D-AGATE-22 item 1: the check-out is stamped when the rep PRESSED the button, before the location read. A press at 23:59:45 whose fix comes back at 00:00:10 is stored with the
     * time of the press (the day of the open check-in), not the time the fix returned.
     */
    @Test fun aCheckOutIsStampedAtThePressNotAfterTheFix() = runTest {
        var stamp = "2026-10-07T17:59:45.000Z" // 23:59:45 Dhaka
        val m = CaptureMetaProvider { r -> CaptureMeta("2026-10-07", stamp, 1, 1, 0, true, r, null, "b", false, 5) }
        val slow = object : LocationFixSource { override suspend fun readFix(purpose: String, refreshCount: Int): FixReading { stamp = "2026-10-07T18:00:10.000Z"; return fixResult } } // 00:00:10 next day
        minutes = 9 * 60
        val f = AttendanceFlow(slow, m, { e, fx -> committed += e to fx }, { null }, { "t" }, { minutes }, newUuid = { "00000000-0000-4000-8000-%012d".format(++n) })
        f.checkIn()
        minutes = 23 * 60 + 59; stamp = "2026-10-07T17:59:45.000Z"
        assertTrue(f.checkOut() is AttendanceResult.Done)
        val out = committed.last().first
        assertEquals("check_out", out.kind)
        assertEquals("the stamp is the press", "2026-10-07T17:59:45.000Z", out.meta.capturedAt)
        assertEquals("2026-10-07", out.meta.businessDate)
        assertEquals("D-AGATE-25: the screen shows the STORED press time, not the time the fix came back", "2026-10-07T17:59:45.000Z", f.state.value.checkedOutAt)
    }

    /** D-AGATE-22 (item 6): the transaction found work opened in the gap: the press is refused with the open-work answer, nothing is shown as checked out, and the buttons stay. */
    @Test fun aCheckOutRefusedInsideItsTransactionIsAnOpenWorkRefusalAndLeavesTheDayOpen() = runTest {
        minutes = 9 * 60
        var gap = false
        val f = AttendanceFlow(fixes, meta, { e, fx -> if (e.kind == "check_out" && gap) throw OpenWorkAtCheckOutException(OpenWork.OPEN_CALL); committed += e to fx }, { null }, { "t" }, { minutes }, newUuid = { "00000000-0000-4000-8000-%012d".format(++n) })
        f.checkIn(); minutes = 17 * 60 + 5; gap = true
        val r = f.checkOut()
        assertTrue(r is AttendanceResult.Refused && r.open == OpenWork.OPEN_CALL)
        assertEquals(OpenWork.OPEN_CALL, f.state.value.checkOutBlockedBy)
        assertNull("not checked out", f.state.value.checkedOutAt)
        assertEquals("only the check-in was written", 1, committed.size)
        gap = false // the call was closed: the same press goes through
        assertTrue(f.checkOut() is AttendanceResult.Done)
    }
}
