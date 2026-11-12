package com.aktcl.aron.sr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.rules.JourneyCallStatus
import com.aktcl.aron.rules.JourneyTimeline
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SRM-B-82 to 85: the Sales Journey is built from the phone's own visit and close rows, offline, synced or not. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class JourneyCallsReadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private val date = "2026-10-05"
    private fun meta() = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
    private val repo = CaptureRepository(db) { "2026-10-05T10:00:00.000Z" }
    private val reads = RoomSaleReads(db, ReferenceRepository(db))

    private suspend fun call(outlet: Long, openedAt: String, outcome: String?, endedAt: String? = null, lat: Double = 23.78, lng: Double = 90.40): String {
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", lat, lng, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", outlet, openedAt, 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", lat, lng, "sale_allowed"), fix)
        if (outcome != null) repo.recordVisitClose(VisitCloseEntity(ClientIds.newUuid(), meta(), visit, outcome, null, false, endedAt!!, false))
        return visit
    }

    @Test fun callsComeFromTheStoredRowsInTimeOrderWithStatusDurationGapAndDistance() = runTest {
        val a = call(1, "2026-10-05T03:53:35.000Z", "sold", "2026-10-05T03:59:02.000Z", 23.7800, 90.4000)
        val b = call(2, "2026-10-05T04:06:55.000Z", "closed", "2026-10-05T04:28:54.000Z", 23.7809, 90.4000)
        val c = call(1, "2026-10-05T04:29:04.000Z", "sold", "2026-10-05T04:34:16.000Z", 23.7800, 90.4000)
        val abandoned = call(3, "2026-10-05T04:35:00.000Z", "abandoned", "2026-10-05T04:35:30.000Z")
        val open = call(4, "2026-10-05T04:40:00.000Z", null, null, 23.7818, 90.4000)

        val calls = reads.journeyCalls(date)
        assertEquals("an abandoned call is not a call", 4, calls.size)
        val rows = JourneyTimeline.build(calls.map { it.input })
        assertEquals(listOf(a, b, c, open), rows.map { it.input.visitUuid })
        assertEquals(listOf(JourneyCallStatus.NEW_SALE, JourneyCallStatus.NO_SALE, JourneyCallStatus.REPEAT_SALE, JourneyCallStatus.IN_PROGRESS), rows.map { it.status })
        assertEquals(327L, rows[0].durationS) // 9:53:35 AM - 9:59:02 AM in Dhaka
        assertEquals(7 * 60 + 53L, rows[0].gapToNextS)
        assertNull(rows[0].distanceFromPrevM) // the first call has no distance
        assertNotNull(rows[1].distanceFromPrevM); assertNull(rows[3].durationS) // an open call shows only its start
        assertNull(rows[3].gapToNextS)
        assertEquals(rows.mapNotNull { it.distanceFromPrevM }.sum(), rows.last().runningM, 1e-9)
    }

    @Test fun anEmptyDayIsAnEmptyListAndNeverACrash() = runTest {
        assertEquals(emptyList<Any>(), reads.journeyCalls(date))
    }
}
