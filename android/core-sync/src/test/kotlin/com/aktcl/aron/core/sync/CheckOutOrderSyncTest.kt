package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * D-OWNER-PREREQ-01 rule 3 through the REAL sync engine and a fake ingest server: whatever retries, isolation or kills do, the server
 * never receives the day's submit before an earlier record of that date is final, nor the check-out before the submit.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckOutOrderSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()
    private lateinit var db: AronDatabase
    private lateinit var api: SyncBatchApi

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_194_400_000L
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }
    private object Auth : UploadAuth {
        override suspend fun token(userId: Long): String? = "upload-1"
        override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
    }

    @Before fun setUp() {
        server.dispatcher = fake; server.start()
        context.deleteDatabase(AronDatabase.fileName(USER)); db = AronDatabase.open(context, USER, null)
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        api = SyncBatchApi(AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE }), null)
    }
    @After fun tearDown() { db.close(); server.close() }

    private fun engine(policy: SyncPolicy = SyncPolicy()) = SyncEngine(USER, db, api, Auth, { DEVICE }, "1.0.3+10003", Clock, policy, random = Random(7))
    private val repo get() = CaptureRepository(db) { "2026-10-05T11:30:00.000Z" }

    /** check-in, a stock issue, one sale with its close, then the check-out that submits the day: the day as the check-out leaves it. */
    private suspend fun theDay(): Map<String, String> {
        val (ci, cif) = TestRows.attendance(); repo.recordAttendance(ci, cif)
        val stock = TestRows.stock(); repo.recordStock(listOf(stock))
        val (v, f) = TestRows.visit(); repo.recordVisitOpen(v, f)
        val s = TestRows.sale(v.clientUuid); repo.recordSale(s); repo.recordVisitClose(TestRows.close(v.clientUuid))
        val u = ClientIds.newUuid(); val of = TestRows.fix(u, "attendance_out")
        repo.recordCheckOut(AttendanceEventEntity(u, TestRows.meta(routeId = null), "check_out", of.clientUuid, null), of) {
            listOf(DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
        }
        return mapOf("stock" to stock.clientUuid, "memo" to s.memo.clientUuid, "visit" to v.clientUuid, "checkout" to u)
    }

    /** The wire order: every record the server received, request by request. */
    private fun wire(): List<String> = fake.requests.flatMap { r -> r.body?.get("records")?.jsonArray.orEmpty().map { it.jsonObject["type"]!!.jsonPrimitive.content + ":" + it.jsonObject["client_uuid"]!!.jsonPrimitive.content } }
    private fun types(w: List<String>) = w.map { it.substringBefore(':') }

    @Test fun aNormalDrainSendsTheTailLastAndInOrder() = runBlocking {
        val ids = theDay()
        assertEquals(com.aktcl.aron.core.sync.SyncStop.DRAINED, engine().run(SyncTrigger.CHECKOUT).stop)
        val t = types(wire())
        assertEquals("one of each tail record, submit before check-out", 1, t.count { it == "day_submit" })
        assertTrue("the submit is after every record that is not the check-out", t.indexOf("day_submit") > t.indexOfLast { it !in setOf("day_submit", "attendance_event") })
        val checkOutAt = wire().indexOfFirst { it.endsWith(ids.getValue("checkout")) }
        assertTrue("the check-out is after the submit", checkOutAt > t.indexOf("day_submit"))
        assertTrue("every row acked", runBlocking { db.openHelper.readableDatabase.query("SELECT state FROM outbox").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.all { it == OutboxState.ACKED } } })
    }

    /** An earlier row is rejected as retryable twice: neither the submit nor the check-out is on the wire until that row is final. */
    @Test fun aRetriedEarlierRowKeepsTheSubmitAndTheCheckOutOffTheWire() = runBlocking {
        val ids = theDay()
        fake.rejectRetryable[ids.getValue("stock")] = 2
        engine().run(SyncTrigger.CHECKOUT)
        var t = types(wire())
        assertTrue("after the first run the stock row is still pending: no submit yet: $t", "day_submit" !in t)
        assertTrue("and no check-out", wire().none { it.endsWith(ids.getValue("checkout")) })
        engine().run(SyncTrigger.MANUAL)   // second reject
        assertTrue("day_submit" !in types(wire()))
        engine().run(SyncTrigger.MANUAL)   // accepted now; every earlier record final, the submit and then the check-out go
        t = types(wire())
        assertEquals(1, t.count { it == "day_submit" })
        val stockAt = wire().indexOfLast { it.endsWith(ids.getValue("stock")) }
        assertTrue("the submit is after the stock row's accepted send", t.indexOf("day_submit") > stockAt)
        assertTrue(wire().indexOfFirst { it.endsWith(ids.getValue("checkout")) } > t.indexOf("day_submit"))
    }

    /** An earlier row rejected for good is FINAL, so it releases the submit (the day is not held by what can never be sent). */
    @Test fun anEarlierRowRejectedForGoodReleasesTheSubmit() = runBlocking {
        val ids = theDay()
        fake.rejectFinal += ids.getValue("stock")
        engine().run(SyncTrigger.CHECKOUT)
        assertTrue("the submit went after the final rejection", "day_submit" in types(wire()))
        assertEquals(OutboxState.REJECTED, db.outboxDao().byClientUuid(ids.getValue("stock"))!!.state)
    }

    /** A kill between any two sends and a relaunch: the order on the server is unchanged. */
    @Test fun aKillBetweenSendsAndARelaunchKeepsTheOrder() = runBlocking {
        theDay()
        val killer = KillAfterFirst(api)
        runCatching { SyncEngine(USER, db, killer, Auth, { DEVICE }, "1.0.3+10003", Clock, SyncPolicy(), random = Random(7)).run(SyncTrigger.CHECKOUT) }
        db.close(); db = AronDatabase.open(context, USER, null)
        engine().run(SyncTrigger.FOREGROUND)
        val t = types(wire())
        assertEquals(1, t.count { it == "day_submit" })
        assertTrue(t.indexOf("day_submit") > t.indexOfLast { it !in setOf("day_submit", "attendance_event") })
        assertTrue("the check-out is the last record of the day", t.last() == "attendance_event")
        // nothing doubled on the server
        // nothing doubled on the server: the server stored exactly one record per distinct uuid the phone ever sent (a resent uuid is answered, not stored again),
        // and every record the phone holds is there once. (The old assertion compared a map's size with its own key set: always true.)
        val sentUuids = wire().map { it.substringAfter(':') }.toSet()
        assertEquals(sentUuids, fake.registry.keys.toSet())
        assertEquals(sentUuids.size, fake.registry.size)
        assertEquals("every record of the phone reached the server exactly once", db.query("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getInt(0) }, fake.registry.size)
    }

    private class KillAfterFirst(private val d: BatchSender) : BatchSender {
        var n = 0
        override suspend fun send(token: String, deviceUuid: String, batchUuid: String, gzipBody: ByteArray, headers: BatchHeaders): com.aktcl.aron.core.network.ApiResult<com.aktcl.aron.contract.SyncBatchResponse> {
            n++
            val r = d.send(token, deviceUuid, batchUuid, gzipBody, headers)
            if (n == 1) throw RuntimeException("killed after the server answered")
            return r
        }
    }

    private companion object {
        const val USER = 334002L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
        const val MONEY = """{"active_memo_count":1,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }
}
