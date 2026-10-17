package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.AmoRecordEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.record.AmoPayloads
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.LocalPurge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Idempotency across the purge (all capture types, not only the AMO's): a record that was acked and then purged from the phone must
 * not be captured or queued again when the same client UUID is replayed (a retried tap, a duplicated upload). Property style: random
 * types and uuids over many rounds; after purge + replay there is exactly zero outbox rows and zero capture rows for the uuid, and an
 * acked (not yet purged) record is never put back to pending by a replay.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReplayAfterPurgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    @After fun tearDown() = db.close()

    private fun scalar(sql: String, vararg args: String): Int = db.openHelper.readableDatabase.query(sql, args).use { it.moveToFirst(); it.getInt(0) }
    private fun outboxRows(uuid: String) = scalar("SELECT COUNT(*) FROM outbox WHERE client_uuid = ?", uuid)
    private fun captureRows(uuid: String) = LocalPurge.CAPTURE_TABLES.sumOf { scalar("SELECT COUNT(*) FROM $it WHERE client_uuid = ?", uuid) }
    private fun ack() = db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'acked', acked_at = '2026-10-05T06:00:00.000Z'")
    private suspend fun purge() = LocalPurge(db).purge("2026-10-13", "2026-10-13T06:00:00.000Z")

    /** One way to capture a record with a given uuid; each also returns the uuids of the records it writes (for the sale: memo, lines...). */
    private class Kind(val name: String, val write: suspend (CaptureRepository, String) -> Unit)

    private val kinds = listOf(
        Kind("attendance") { r, u -> val (e, f) = TestRows.attendance(u); r.recordAttendance(e, f) },
        Kind("visit") { r, u -> val (v, f) = TestRows.visit(u); r.recordVisitOpen(v, f) },
        Kind("stock") { r, u -> r.recordStock(listOf(TestRows.stock(uuid = u))) },
        Kind("skip") { r, u -> r.recordVisitSkip(VisitSkipEntity(u, TestRows.meta(), 50002, "shop_closed")) },
        Kind("due") { r, u ->
            r.recordDueCollection(DueCollectionEntity(u, TestRows.meta(), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000))
        },
        Kind("outlet_request") { r, u ->
            val f = TestRows.fix(u, "outlet_capture")
            r.recordOutletRequest(OutletChangeRequestEntity(u, TestRows.meta(routeId = null), "info", 50001, """{"name":"Rahim Store"}""", f.clientUuid), f)
        },
        Kind("amo_task") { r, u -> r.recordAmo(AmoRecordEntity(u, TestRows.meta(), "task", u, 0, AmoPayloads.task("visit_followup", 7, null, "T", null, null, null).toString())) },
    )

    @Test fun aReplayOfAPurgedRecordIsNeverCapturedOrQueuedAgain() = runBlocking {
        val rnd = Random(20261018)
        repeat(40) { round ->
            val kind = kinds[rnd.nextInt(kinds.size)]
            val u = ClientIds.newUuid()
            kind.write(repo, u)
            assertTrue("${kind.name} round $round: queued once", outboxRows(u) >= 1)
            ack()
            // an acked record that is replayed before the purge stays acked: never pending again
            runCatching { kind.write(repo, u) }
            assertEquals("${kind.name} round $round: replay of an acked record did not queue it again", 0, scalar("SELECT COUNT(*) FROM outbox WHERE client_uuid = ? AND state = 'pending'", u))
            purge()
            assertEquals("${kind.name}: purged outbox row", 0, outboxRows(u))
            assertEquals("${kind.name}: purged capture row", 0, captureRows(u))
            // the replay after the purge writes nothing at all
            kind.write(repo, u)
            assertEquals("${kind.name} round $round: no outbox row after a replay of a purged uuid", 0, outboxRows(u))
            assertEquals("${kind.name} round $round: no capture row after a replay of a purged uuid", 0, captureRows(u))
        }
    }

    @Test fun aReplayedSaleAndItsCloseAfterThePurgeAreNotCapturedAgain() = runBlocking {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val sale = TestRows.sale(visit.clientUuid)
        repo.recordSale(sale)
        val close = TestRows.close(visit.clientUuid)
        repo.recordVisitClose(close)
        ack()
        assertEquals(1, purge().byTable["memo"])
        assertEquals("a replayed memo is not captured again", Unit, repo.recordSale(sale))
        repo.recordVisitClose(close)
        assertEquals(0, scalar("SELECT COUNT(*) FROM memo"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM outbox"))
        assertNull(db.outboxDao().byClientUuid(sale.memo.clientUuid))
    }

    @Test fun aReplayedDaySubmitAfterThePurgeIsNotQueuedAgain() = runBlocking {
        val s = DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false)
        repo.recordDaySubmit(s)
        ack()
        purge()
        repo.recordDaySubmit(s)
        assertEquals(0, outboxRows(s.clientUuid))
        assertEquals(0, scalar("SELECT COUNT(*) FROM day_submit"))
    }

    @Test fun tombstonesAreKeptSixtyDaysThenForgotten() = runBlocking {
        val u = ClientIds.newUuid()
        kinds[0].write(repo, u)
        ack()
        purge()
        assertEquals(1, scalar("SELECT COUNT(*) FROM purged_uuid WHERE client_uuid = ?", u))
        LocalPurge(db).purge("2026-12-20", "2026-12-20T06:00:00.000Z")
        assertEquals("older than 60 days: forgotten (the server dedupes it)", 0, scalar("SELECT COUNT(*) FROM purged_uuid WHERE client_uuid = ?", u))
    }

    private companion object {
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }
}
