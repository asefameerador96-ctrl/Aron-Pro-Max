package com.aktcl.aron.feature.memo.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.feature.memo.domain.DueCollectionDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-LEAD-PREREQ-04 ruling 5 (step 3 S5): which date and which visit a Mark Paid takes. Inside the OPEN call it carries the VISIT's date, whatever its age (also after two
 * Dhaka midnights); outside a call it belongs to today and is never attached to an unclosed visit of an earlier day (the step-2 regression: a blocked or forgotten visit of
 * yesterday used to lock a legitimate Mark Paid on the new day).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DueCollectionVisitDateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-07T04:00:00.000Z" }
    private var today = "2026-10-07"
    @Before fun setUp() { today = "2026-10-07" }
    @After fun tearDown() { db.close() }

    private fun meta(date: String, route: Long? = 10231) = CaptureMeta(date, date + "T04:31:07.120Z", 1, 1, 0, true, route, null, "b", false, 1)

    private fun visit(date: String, outlet: Long = 1): String = runBlocking {
        val u = ClientIds.newUuid(); val f = ClientIds.newUuid()
        val fix = GeoFixEntity(f, u, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(u, meta(date), "sr_call", outlet, date + "T04:31:07.120Z", 1, true, null, f, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        u
    }

    private fun writer(openCall: String?) = VisitDueCollectionWriter(db, repo, { rid -> meta(today, rid ?: 10231) }, { today }, openCall = { openCall })
    private fun draft() = DueCollectionDraft(1, ClientIds.newUuid(), "sr-x", "2026-10-05", 10_000, true, 10_000)
    private fun lastCollection() = runBlocking { db.captureDao().dueCollectionsOn(today).single() }

    @Test fun anUnclosedVisitOfAnEarlierDayIsNeverAttachedOutsideACall() = runBlocking {
        visit("2026-10-06")                                   // yesterday's visit, never closed (blocked, or lost)
        writer(openCall = null).write(draft())
        val c = lastCollection()
        assertEquals("a Mark Paid outside a call belongs to today", today, c.meta.businessDate)
        assertNull("and is not attached to yesterday's visit", c.visitClientUuid)
    }

    @Test fun insideTheOpenCallTheCollectionCarriesTheVisitsDateEvenAfterTwoMidnights() = runBlocking {
        today = "2026-10-08"
        val v = visit("2026-10-06")                           // the call opened two days ago and is still open
        writer(openCall = v).write(draft())
        val c = runBlocking { db.captureDao().dueCollectionsOn("2026-10-06").single() }
        assertEquals("the visit's date, not the clock's", "2026-10-06", c.meta.businessDate)
        assertEquals(v, c.visitClientUuid)
    }

    @Test fun aVisitOfTodayStillTakesTheCollection() = runBlocking {
        val v = visit(today)
        writer(openCall = null).write(draft())
        assertEquals(v, lastCollection().visitClientUuid)
    }
}
