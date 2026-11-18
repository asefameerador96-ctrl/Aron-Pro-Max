package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.entity.PhoneMarkEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * D-AGATE-24 (item 10): the phone's close of a forgotten day that the server answers `rejected` / `attendance_duplicate` (it already holds a close for the day) is acked on the phone
 * (nothing shown) but is NOT in the server's registry, which is what the server's digest counts. The phone's digest leaves that row out, so the daily reconcile agrees at once: no
 * resend of the bucket, no confirming digest owed, no purge hold. The rep's own refused duplicate (no closed_by) is not this case.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhoneCloseDuplicateDigestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()
    private lateinit var db: AronDatabase
    private lateinit var client: AronApiClient

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_194_400_000L // 2026-10-05T10:00:00Z
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }

    @Before fun setUp() {
        server.dispatcher = fake; server.start()
        context.deleteDatabase(AronDatabase.fileName(USER)); db = AronDatabase.open(context, USER, null)
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
    }
    @After fun tearDown() { db.close(); server.close() }

    private fun engine() = SyncEngine(
        USER, db, SyncBatchApi(client, null), SyncEngineTest.FakeAuth(), { DEVICE }, "1.0.3+10003", Clock, random = Random(3),
        generationApi = SyncGenerationApi(client), generationHint = { null }, digestApi = SyncDigestApi(client),
    )

    private val repo get() = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    /** A check-in and a check-out closed by the phone (its DAY_CLOSED mark in the same transaction, so the payload says closed_by = phone) or by the rep. */
    private suspend fun day(byPhone: Boolean): String {
        db.referenceDao().putMeta(SyncMetaEntity(SyncEngine.KEY_LAST_SUCCESS, "2026-10-04T10:00:00.000Z")) // an upgraded database: its first date is digested
        val (ci, cif) = TestRows.attendance(); repo.recordAttendance(ci, cif)
        val u = ClientIds.newUuid(); val of = TestRows.fix(u, "attendance_out")
        val marks = if (byPhone) listOf(PhoneMarkEntity(kind = PhoneMarkEntity.DAY_CLOSED, businessDate = "2026-10-05", createdAt = "2026-10-05T10:00:00.000Z")) else emptyList()
        repo.recordCheckOut(AttendanceEventEntity(u, TestRows.meta(routeId = null), "check_out", of.clientUuid, null), of, marks)
        return u
    }

    private fun state(uuid: String) = runBlocking { db.outboxDao().byClientUuid(uuid)!! }
    private fun attendanceItem() = fake.digests.last()["items"]!!.jsonArray.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "attendance_event" }
    private fun countsOf(item: kotlinx.serialization.json.JsonObject) = item["buckets"]!!.jsonArray.sumOf { it.jsonObject["count"]!!.jsonPrimitive.content.toLong() }

    @Test fun aPhoneCloseAnsweredAttendanceDuplicateIsDoneAndTheDigestAgreesWithTheServer() = runBlocking {
        val out = day(byPhone = true)
        fake.attendanceDuplicate += out
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.FOREGROUND).stop)
        assertEquals("acked: done for the phone, nothing shown", OutboxState.ACKED, state(out).state)
        assertEquals("attendance_duplicate", state(out).lastCode)
        assertEquals("the server stored only the check-in", 1, fake.storedOf("attendance_event").size)
        assertEquals(1, fake.digests.size)
        assertEquals("the digest counts the one row the server holds, not the duplicate", 1L, countsOf(attendanceItem()))
        assertNull("no confirming digest owed", db.referenceDao().meta(SyncEngine.KEY_DIGEST_DUE))
        assertTrue("nothing was re-sent: the digest result says 0", db.referenceDao().meta(SyncEngine.KEY_DIGEST_LAST_RESULT)!!.split('|')[1] == "0")
        val closeSends = fake.requests.flatMap { r -> r.body?.get("records")?.jsonArray.orEmpty().map { it.jsonObject["client_uuid"]!!.jsonPrimitive.content } }.count { it == out }
        assertEquals("the close was sent exactly once", 1, closeSends)
    }

    @Test fun aRepsOwnRefusedDuplicateIsNotThisCaseAndStaysRefused() = runBlocking {
        val out = day(byPhone = false)
        fake.attendanceDuplicate += out
        engine().run(SyncTrigger.FOREGROUND)
        assertEquals("the old rule for any other attendance row: refused", OutboxState.REJECTED, state(out).state)
    }

    private companion object {
        const val USER = 334003L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
