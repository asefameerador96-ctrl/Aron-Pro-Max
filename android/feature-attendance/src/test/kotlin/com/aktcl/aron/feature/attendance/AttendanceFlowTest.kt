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
    private val fixes = object : LocationFixSource { override suspend fun readFix(purpose: String) = fixResult }
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
}
