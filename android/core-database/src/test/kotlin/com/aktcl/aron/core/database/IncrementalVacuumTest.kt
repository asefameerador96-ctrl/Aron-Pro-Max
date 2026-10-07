package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.LocalPurge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-028 follow-up: new user files are auto_vacuum INCREMENTAL, so the purge gives pages back; old files are never rewritten. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class IncrementalVacuumTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<AronDatabase>()

    @After fun tearDown() {
        opened.forEach { it.close() }
        listOf(901L, 902L).forEach { context.deleteDatabase(AronDatabase.fileName(it)) }
    }

    private fun pragma(db: AronDatabase, name: String): Long =
        db.openHelper.writableDatabase.query("PRAGMA $name").use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun aNewFileIsIncrementalAndThePurgeReturnsItsPages() = runBlocking {
        val db = AronDatabase.open(context, 901, null).also { opened += it }
        assertEquals(2L, pragma(db, "auto_vacuum")) // 2 = INCREMENTAL
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        repeat(150) { i ->
            val (visit, fix) = TestRows.visit()
            repo.recordVisitOpen(visit, fix)
            repo.recordSale(TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-" + (100 + i)))
            repo.recordVisitClose(TestRows.close(visit.clientUuid))
        }
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'acked', acked_at = '2026-10-05T06:00:00.000Z'")
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val before = pragma(db, "page_count")
        assertTrue(LocalPurge(db).purge("2026-10-13", "2026-10-13T06:00:00.000Z").total > 0)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        assertEquals("no free page left in the file", 0L, pragma(db, "freelist_count"))
        assertTrue("the file shrank: $before -> ${pragma(db, "page_count")}", pragma(db, "page_count") < before)
    }

    /** Only proves that reopening an older file keeps its data; the mode rule is SQLite's (NONE stays, FULL switches). */
    @Test
    fun aFileCreatedBeforeKeepsItsData() = runBlocking {
        // A pilot phone's file from before this change: created without the factory wrapper.
        val old = Room.databaseBuilder(context, AronDatabase::class.java, AronDatabase.fileName(902)).build()
        val (visit, fix) = TestRows.visit()
        CaptureRepository(old) { "2026-10-05T04:36:00.000Z" }.recordVisitOpen(visit, fix)
        // NONE (SQLCipher's default) can only change by a VACUUM, which never runs here; FULL (the framework default)
        // switches to INCREMENTAL in place, as SQLite allows without a rewrite.
        val oldMode = pragma(old, "auto_vacuum")
        old.close()
        val reopened = AronDatabase.open(context, 902, null).also { opened += it }
        assertEquals("never rewritten on open", if (oldMode == 0L) 0L else 2L, pragma(reopened, "auto_vacuum"))
        assertEquals(visit.clientUuid, reopened.captureDao().visit(visit.clientUuid)!!.clientUuid)
    }
}
