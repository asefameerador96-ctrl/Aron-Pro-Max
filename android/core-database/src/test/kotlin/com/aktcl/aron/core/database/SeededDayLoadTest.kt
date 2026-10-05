package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** N-016: "a seeded SR day loads from a fixture in under a second" (parse, apply in one transaction, read the route of the day). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SeededDayLoadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture() = javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun aSeededSrDayLoadsInUnderASecond() = runTest {
        val name = "seed-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, AronDatabase::class.java, name).build()
        val repo = ReferenceRepository(db)
        db.openHelper.writableDatabase // open outside the timed part, as the app does at login
        val text = fixture()

        val started = System.nanoTime()
        repo.apply(BundleReference.parse(text))
        val day = repo.routesOfDay("2026-10-05")
        val skus = repo.skus()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("seeded day took $elapsedMs ms", elapsedMs < 1_000)
        assertEquals(listOf(10231L, 10232L), day.map { it.route.routeId })
        assertTrue(day.first().route.plannedToday)
        assertEquals(120, day.first().outlets.size)
        assertEquals(80, day[1].outlets.size)
        assertEquals(60, skus.size)
        // Visit order: sequenced outlets first by sequence, then the rest by sort key.
        val first = day.first().outlets
        assertEquals(2, first.first().visitSequence)
        assertTrue(first.takeWhile { it.visitSequence != null }.zipWithNext().all { (a, b) -> a.visitSequence!! < b.visitSequence!! })
        assertEquals("দোকান 50000", first.first { it.outletId == 50000L }.nameBn)
        assertEquals("2026-10-05:3", repo.bundleVersion())
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun applyingABundleNeverTouchesCapturesOrTheOutbox() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        val ref = ReferenceRepository(db)
        ref.apply(BundleReference.parse(fixture()))
        val (visit, fix) = TestRows.visit()
        CaptureRepository(db).recordVisitOpen(visit, fix)
        ref.apply(BundleReference.parse(fixture()))
        assertEquals(1, db.outboxDao().countInState("pending"))
        assertEquals(200, db.referenceDao().outletCount())
        db.close()
    }
}
