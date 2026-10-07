package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.LocalPurge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-028: synced families older than 7 business days go; unsynced, rejected or quarantined ones never do. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LocalPurgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    private var memoSeq = 0

    @After fun tearDown() = db.close()

    /** A visit with its fix, a sale (memo, lines) and the close: rows of business date 2026-10-05. */
    private suspend fun visitFamily(): String {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        repo.recordSale(TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-" + (100 + memoSeq++)))
        repo.recordVisitClose(TestRows.close(visit.clientUuid))
        return visit.clientUuid
    }

    private fun sql(q: String, vararg args: Any) = db.openHelper.writableDatabase.execSQL(q, args)
    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
    private fun familyOf(uuid: String): String = db.openHelper.readableDatabase.query("SELECT family_uuid FROM outbox WHERE client_uuid = ?", arrayOf(uuid)).use { it.moveToFirst(); it.getString(0) }
    private fun ackFamily(uuid: String) = sql("UPDATE outbox SET state = 'acked', acked_at = '2026-10-05T06:00:00.000Z' WHERE family_uuid = ?", familyOf(uuid))
    private suspend fun visit(uuid: String) = db.captureDao().visit(uuid)

    @Test
    fun anEightDayOldSyncedFamilyIsPurgedAndAnUnsyncedOneIsKept() = runBlocking {
        val synced = visitFamily()
        val unsynced = visitFamily()
        ackFamily(synced)
        val r = LocalPurge(db).purge("2026-10-13", "2026-10-13T06:00:00.000Z")
        assertNull(visit(synced))
        assertNotNull(visit(unsynced))
        assertEquals(true, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox WHERE family_uuid = ?", arrayOf(familyOf(unsynced))).use { it.moveToFirst(); it.getInt(0) } > 0)
        assertEquals("the unsynced visit keeps its fix", 1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM geo_fix WHERE owner_client_uuid = ?", arrayOf(unsynced)).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM geo_fix WHERE owner_client_uuid = ?", arrayOf(synced)).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, count("memo"))
        assertEquals(r.byTable["visit"], 1)
        assertEquals("second run: nothing more", 0, LocalPurge(db).purge("2026-10-13", "2026-10-13T06:00:00.000Z").total)
    }

    @Test
    fun aSevenDayOldSyncedFamilyStays() = runBlocking {
        val synced = visitFamily()
        ackFamily(synced)
        assertEquals(0, LocalPurge(db).purge("2026-10-12", "2026-10-12T06:00:00.000Z").total)
        assertNotNull(visit(synced))
    }

    @Test
    fun oneRejectedQuarantinedOrPendingRowKeepsTheWholeFamily() = runBlocking {
        for (state in listOf("rejected", "quarantined", "pending", "in_flight")) {
            val v = visitFamily()
            ackFamily(v)
            sql("UPDATE outbox SET state = ? WHERE client_uuid = (SELECT MAX(client_uuid) FROM outbox WHERE family_uuid = ? AND client_uuid <> ?)", state, familyOf(v), v)
        }
        val before = count("outbox")
        assertEquals(0, LocalPurge(db).purge("2026-10-30", "2026-10-30T06:00:00.000Z").total)
        assertEquals(before, count("outbox"))
        assertEquals(4, count("visit"))
    }

    @Test
    fun countersMetaAndReferenceDataAreNeverTouched() = runBlocking {
        val v = visitFamily()
        ackFamily(v)
        db.referenceDao().putMeta(SyncMetaEntity("consent.location_notice.v1", "2026-10-05T04:00:00.000Z"))
        val counters = count("memo_counter")
        LocalPurge(db).purge("2026-10-30", "2026-10-30T06:00:00.000Z")
        assertEquals(counters, count("memo_counter"))
        assertEquals("2026-10-05T04:00:00.000Z", db.referenceDao().meta("consent.location_notice.v1"))
        assertEquals(0, count("outbox"))
        assertEquals(0, count("geo_fix"))
    }

    /** Checker: an old business date acked just now stays (outbox_keep_days after the ack; a server restore may need it). */
    @Test
    fun anOldFamilyAckedRecentlyStaysUntilTheKeepWindowPasses() = runBlocking {
        val v = visitFamily()
        sql("UPDATE outbox SET state = 'acked', acked_at = '2026-10-13T05:00:00.000Z' WHERE family_uuid = ?", familyOf(v))
        assertEquals(0, LocalPurge(db).purge("2026-10-13", "2026-10-13T06:00:00.000Z").total)
        assertEquals(0, LocalPurge(db).purge("2026-10-15", "2026-10-15T06:00:00.000Z").total) // default keep 3 days (db V0055)
        assertEquals(1, LocalPurge(db).purge("2026-10-17", "2026-10-17T06:00:00.000Z").byTable["visit"])
    }

    /** A family is as young as its newest row: an old visit whose reprint (or void) is recent stays. */
    @Test
    fun aFamilyWithARecentRowStays() = runBlocking {
        val v = visitFamily()
        ackFamily(v)
        sql("UPDATE outbox SET business_date = '2026-10-12' WHERE client_uuid = (SELECT MAX(client_uuid) FROM outbox WHERE family_uuid = ? AND client_uuid <> ?)", familyOf(v), v)
        assertEquals(0, LocalPurge(db).purge("2026-10-13", "2026-10-13T06:00:00.000Z").total)
    }

    /** A capture row whose outbox row is gone belonged to a family purged whole: it goes once old enough, not before. */
    @Test
    fun aCaptureRowWithoutItsOutboxRowGoesOnlyWhenOld() = runBlocking {
        val v = visitFamily()
        sql("DELETE FROM outbox WHERE family_uuid = ?", familyOf(v))
        assertEquals(0, LocalPurge(db).purge("2026-10-12", "2026-10-12T06:00:00.000Z").total)
        assertNotNull(visit(v))
        LocalPurge(db).purge("2026-10-13", "2026-10-13T06:00:00.000Z")
        assertNull(visit(v))
        assertEquals(0, count("geo_fix"))
    }
}
