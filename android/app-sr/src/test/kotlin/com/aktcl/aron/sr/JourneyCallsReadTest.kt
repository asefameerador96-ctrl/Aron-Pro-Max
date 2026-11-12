package com.aktcl.aron.sr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
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

    private var memoNo = 0
    private suspend fun memo(visit: String, outlet: Long, kind: String, supersedes: String? = null): String {
        val id = ClientIds.newUuid()
        db.captureDao().insertMemo(
            MemoEntity(
                id, meta(), visit, outlet, "sr1-261005-${++memoNo}", kind, "2026-10-05T04:35:00.000Z", date, "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000, 0, true, null,
                if (kind == "sale") 1 else 0, 0, 0, supersedesClientUuid = supersedes, editReasonCode = supersedes?.let { "wrong_qty" },
            ),
        )
        return id
    }

    @Test fun callsComeFromTheStoredRowsInTimeOrderWithStatusDurationGapAndDistance() = runTest {
        val a = call(1, "2026-10-05T03:53:35.000Z", "sold", "2026-10-05T03:59:02.000Z", 23.7800, 90.4000); memo(a, 1, "sale")
        val b = call(2, "2026-10-05T04:06:55.000Z", "closed", "2026-10-05T04:28:54.000Z", 23.7809, 90.4000)
        val c = call(1, "2026-10-05T04:29:04.000Z", "sold", "2026-10-05T04:34:16.000Z", 23.7800, 90.4000); memo(c, 1, "sale")
        val abandoned = call(3, "2026-10-05T04:35:00.000Z", "abandoned", "2026-10-05T04:35:30.000Z")
        val open = call(4, "2026-10-05T04:40:00.000Z", null, null, 23.7818, 90.4000)

        val calls = reads.journeyCalls(date)
        assertEquals("an abandoned call stays on the journey, as on the server", 5, calls.size)
        val rows = JourneyTimeline.build(calls.map { it.input })
        assertEquals(listOf(a, b, c, abandoned, open), rows.map { it.input.visitUuid })
        assertEquals(
            listOf(JourneyCallStatus.NEW_SALE, JourneyCallStatus.NO_SALE, JourneyCallStatus.REPEAT_SALE, JourneyCallStatus.NO_SALE, JourneyCallStatus.IN_PROGRESS),
            rows.map { it.status },
        )
        assertEquals(327L, rows[0].durationS) // 9:53:35 AM - 9:59:02 AM in Dhaka
        assertEquals(7 * 60 + 53L, rows[0].gapToNextS)
        assertNull(rows[0].distanceFromPrevM) // the first call has no distance
        assertNotNull(rows[1].distanceFromPrevM); assertNull(rows[4].durationS) // an open call shows only its start
        assertNull(rows[4].gapToNextS)
        assertEquals(rows.last().runningM, rows.mapNotNull { it.distanceFromPrevM }.fold(0.0) { t, d -> kotlin.math.floor((t + d) * 100.0 + 0.5) / 100.0 }, 0.0)
    }

    @Test fun aZeroSaleOrQcOnlyMemoIsNotASaleSoALaterRealSaleIsNew() = runTest {
        val z = call(1, "2026-10-05T04:00:00.000Z", "zero_sale_stock_ok", "2026-10-05T04:05:00.000Z"); memo(z, 1, "zero_sale")
        val real = call(1, "2026-10-05T04:10:00.000Z", "sold", "2026-10-05T04:15:00.000Z"); memo(real, 1, "sale")
        val rows = JourneyTimeline.build(reads.journeyCalls(date).map { it.input })
        assertEquals(listOf(JourneyCallStatus.NO_SALE, JourneyCallStatus.NEW_SALE), rows.map { it.status }) // not Repeat: the first call sold nothing
    }

    @Test fun aSupersededMemoDoesNotCountAndAnEditMarksTheCallEdited() = runTest {
        val v = call(1, "2026-10-05T04:00:00.000Z", "sold", "2026-10-05T04:05:00.000Z")
        val original = memo(v, 1, "sale"); memo(v, 1, "sale", supersedes = original) // the edit supersedes the original
        val other = call(2, "2026-10-05T04:10:00.000Z", "sold", "2026-10-05T04:15:00.000Z"); memo(other, 2, "sale")
        val rows = JourneyTimeline.build(reads.journeyCalls(date).map { it.input })
        assertEquals(listOf(JourneyCallStatus.EDITED_SALE, JourneyCallStatus.NEW_SALE), rows.map { it.status })
        assertEquals(true, rows[0].input.sold) // the active edit memo is a sale
    }

    @Test fun anEmptyDayIsAnEmptyListAndNeverACrash() = runTest {
        assertEquals(emptyList<Any>(), reads.journeyCalls(date))
    }
}
