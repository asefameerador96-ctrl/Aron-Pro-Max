package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutboxState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * F-SYS-080 acceptance: after a loss, per-(date, type) counts and 16 bucket hashes identify only the differing rows and
 * only those rows are re-sent (trigger `digest_resend`, zero duplicates); plus the F-SYS-047 `?since=` two-restore gap.
 * Time is a test clock (no real-clock reads).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DigestResendTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()
    private lateinit var db: AronDatabase
    private lateinit var client: AronApiClient
    private var hint: String? = null

    private object Clock : WallClock {
        var now = 1_791_194_400_000L // 2026-10-05T10:00:00Z, 16:00 in Dhaka
        override fun nowMs(): Long = now
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }

    @Before fun setUp() {
        Clock.now = 1_791_194_400_000L
        server.dispatcher = fake
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
    }

    @After fun tearDown() {
        db.close()
        server.close()
    }

    private fun engine(digest: Boolean = true) = SyncEngine(
        USER, db, SyncBatchApi(client, null), SyncEngineTest.FakeAuth(), { DEVICE }, "1.0.3+10003", Clock, random = Random(3),
        generationApi = SyncGenerationApi(client), generationHint = { hint }, digestApi = if (digest) SyncDigestApi(client) else null,
    )

    private var n = 0
    private suspend fun sale() {
        n++
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val (visit, fix) = TestRows.visit(outletId = 50001L + n, seq = n)
        repo.recordVisitOpen(visit, fix)
        val s = TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-%03d".format(n))
        repo.recordSale(s.copy(memo = s.memo.copy(outletId = 50001L + n)))
        repo.recordVisitClose(TestRows.close(visit.clientUuid))
    }

    private fun countState(state: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox WHERE state = ?", arrayOf(state)).use { it.moveToFirst(); it.getInt(0) }
    private fun total() = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox").use { it.moveToFirst(); it.getInt(0) }
    private fun rows(): List<Pair<String, String>> = db.openHelper.readableDatabase.query("SELECT client_uuid, record_type FROM outbox").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
    }
    private fun lastTrigger() = fake.requests.last().body!!["trigger"]!!.jsonPrimitive.content

    /** A database that synced before (an upgrade): its first date is digested. */
    private suspend fun synced() { db.referenceDao().putMeta(SyncMetaEntity(SyncEngine.KEY_LAST_SUCCESS, "2026-10-04T10:00:00.000Z")) }

    @Test
    fun theHashRuleIsTheServers() {
        // Bucket = first hex digit; hash = sum of the first 8 bytes big-endian, modulo 2^64, 16 lowercase hex digits.
        val b = DigestHash.buckets(listOf("f0000000-0000-4000-8000-000000000000", "F0000000-0000-4000-8000-0000000000ff", "01234567-89ab-4def-8123-456789abcdef", "nonsense"))
        assertEquals(2L, b[15].count)
        assertEquals("e000000000008000", b[15].hash) // 0xf000000000004000 * 2 wraps
        assertEquals(1L, b[0].count)
        assertEquals("0123456789ab4def", b[0].hash)
        assertEquals(DigestHash.EMPTY, b[7].hash)
        assertEquals(16, b.size)
    }

    @Test
    fun aSilentLossReSendsOnlyTheDifferingBucketsWithZeroDuplicates() = runBlocking {
        synced()
        repeat(4) { sale() }
        // The first run that acks a batch sends the day's digest: all match.
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.FOREGROUND).stop)
        assertEquals(1, fake.digests.size)
        assertEquals(total(), fake.registry.size)
        assertNull(db.referenceDao().meta(SyncEngine.KEY_DIGEST_DUE))
        // Once a day: another acked run the same day sends none.
        sale()
        engine().run(SyncTrigger.FOREGROUND)
        assertEquals(1, fake.digests.size)

        // The server loses two memo lines and one visit without a new generation.
        val lines = rows().filter { it.second == "memo_line" }.map { it.first }.take(2)
        val visit = rows().first { it.second == "visit" }.first
        val lost = lines + visit
        fake.lose(lost)
        val lostBuckets = lost.map { u -> rows().first { it.first == u }.second to u[0].lowercase() }.toSet()
        val expected = rows().filter { (u, t) -> (t to u[0].lowercase()) in lostBuckets }.map { it.first }.toSet()
        assertTrue("fewer rows than everything", expected.size < total())

        Clock.now += 24 * 3_600_000L // the next day (2026-10-06): the first acked run digests again
        fake.today = "2026-10-06"
        val old = rows().map { it.first }.toSet()
        sale()
        val before = fake.requests.size
        val r = engine().run(SyncTrigger.FOREGROUND)
        assertEquals(SyncStop.DRAINED, r.stop)
        assertEquals(2, fake.digests.size)
        val resent = fake.requests.drop(before).filter { it.status == 200 }.flatMap { q -> q.body!!["records"]!!.jsonArray.map { it.jsonObject["client_uuid"]!!.jsonPrimitive.content } }
            .toSet().intersect(old) // the new sale's family goes as usual
        assertEquals("exactly the rows of the differing buckets", expected, resent)
        assertEquals("digest_resend", lastTrigger())
        assertEquals("every row once on the server", total(), fake.registry.size)
        assertEquals(total(), countState(OutboxState.ACKED))
        // A confirming digest is owed; it comes clean on the next run and ends there.
        assertEquals(SyncEngine.DIGEST_DUE_CONFIRM, db.referenceDao().meta(SyncEngine.KEY_DIGEST_DUE))
        engine().run(SyncTrigger.FOREGROUND)
        assertEquals(3, fake.digests.size)
        assertNull(db.referenceDao().meta(SyncEngine.KEY_DIGEST_DUE))
    }

    @Test
    fun theDigestCarriesOnlyDatesInsideTheWindowAndNeverAPartlyPurgedOrFirstDate() = runBlocking {
        repeat(2) { sale() }
        // A database never synced before: its first date (2026-10-05) is never digested.
        engine().run(SyncTrigger.FOREGROUND)
        assertEquals("nothing to digest: no request", 0, fake.digests.size)
        assertEquals("2026-10-05", db.referenceDao().meta(SyncEngine.KEY_DIGEST_FROM))

        // An upgraded database (synced before): the date is digested, as one item per (date, type) held.
        db.referenceDao().putMeta(SyncMetaEntity(SyncEngine.KEY_DIGEST_FROM, ""))
        engine().run(SyncTrigger.DAY_SUBMIT)
        assertEquals(1, fake.digests.size)
        val items = fake.digests.last()["items"]!!.jsonArray
        assertEquals(rows().map { it.second }.toSet().size, items.size)
        assertTrue(items.all { it.jsonObject["buckets"]!!.jsonArray.size == 16 && it.jsonObject["business_date"]!!.jsonPrimitive.content == "2026-10-05" })
        assertEquals(DEVICE, fake.digests.last()["device_uuid"]!!.jsonPrimitive.content)

        // A purge cutoff after the date: partial, never digested (and so no request).
        db.referenceDao().putMeta(SyncMetaEntity(SyncEngine.KEY_PURGE_CUTOFF, "2026-10-06"))
        engine().run(SyncTrigger.DAY_SUBMIT)
        assertEquals(1, fake.digests.size)
        db.referenceDao().deleteMeta(SyncEngine.KEY_PURGE_CUTOFF)

        // Eight days later the date is outside cfg.sync.max_backdate_days (7): not digested.
        Clock.now += 8 * 24 * 3_600_000L
        engine().run(SyncTrigger.DAY_SUBMIT)
        assertEquals(1, fake.digests.size)
        Clock.now -= 24 * 3_600_000L // seven days later: still inside
        engine().run(SyncTrigger.DAY_SUBMIT)
        assertEquals(2, fake.digests.size)
    }

    @Test
    fun aRefusedDigestChangesNothingAndStaysDue() = runBlocking {
        synced()
        repeat(2) { sale() }
        engine(digest = false).run(SyncTrigger.FOREGROUND)
        fake.lose(rows().take(3).map { it.first })
        db.referenceDao().putMeta(SyncMetaEntity(SyncEngine.KEY_DIGEST_DUE, SyncEngine.DIGEST_DUE_GENERATION))
        fake.digestFailBefore.add(503)
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.FOREGROUND).stop)
        assertEquals(1, fake.digests.size)
        assertEquals(total(), countState(OutboxState.ACKED))
        assertEquals(SyncEngine.DIGEST_DUE_GENERATION, db.referenceDao().meta(SyncEngine.KEY_DIGEST_DUE))
        // The next run (any trigger) sends it again and re-sends the lost rows' buckets.
        engine().run(SyncTrigger.FOREGROUND)
        assertEquals(2, fake.digests.size)
        assertEquals(total(), fake.registry.size)
    }

    @Test
    fun aManualRunDigestsAtMostEveryTenMinutes() = runBlocking {
        synced()
        sale()
        engine().run(SyncTrigger.MANUAL)
        assertEquals(1, fake.digests.size)
        Clock.now += 5 * 60_000L
        engine().run(SyncTrigger.MANUAL)
        assertEquals(1, fake.digests.size)
        Clock.now += 5 * 60_000L
        engine().run(SyncTrigger.MANUAL)
        assertEquals(2, fake.digests.size)
        // A run that acked nothing on a day already digested sends none.
        engine().run(SyncTrigger.FOREGROUND)
        assertEquals(2, fake.digests.size)
    }

    @Test
    fun twoRestoresBeforeThePhoneCalledReSendFromTheEarliestLossAndHoldThePurgeUntilACleanDigest() = runBlocking {
        synced()
        repeat(2) { sale() } // acked at 10:00
        engine().run(SyncTrigger.FOREGROUND)
        val g1 = fake.generation
        val atTen = fake.registry.size
        Clock.now += 2 * 3_600_000L // 12:00
        repeat(2) { sale() }
        engine().run(SyncTrigger.FOREGROUND)
        // G1 -> G2 loses after 11:00 (the 12:00 rows); G2 -> G3 (a failover an hour later) loses after 12:30 only.
        fake.restore(keep = atTen, newGeneration = G2, lostAfter = "2026-10-05T11:00:00.000Z")
        fake.restore(keep = atTen, newGeneration = NEW_GEN, lostAfter = "2026-10-05T12:30:00.000Z")
        fake.mintedAt = "2026-10-05T13:00:00.000Z"
        Clock.now += 2 * 3_600_000L // 14:00
        hint = NEW_GEN
        engine().run(SyncTrigger.FOREGROUND) // noted
        // Without the digest, so the `?since=` answer alone must cover the 12:00 rows (G3's own loss starts at 12:30).
        val r = engine(digest = false).run(SyncTrigger.RESYNC)
        assertEquals(SyncStop.DRAINED, r.stop)
        assertEquals("asked with the generation it handled", g1, fake.generationSince.last())
        assertEquals("the 12:00 rows went again: the earliest loss was 11:00", total(), fake.registry.size)
        assertEquals("resync", fake.requests.filter { it.body?.get("records")?.jsonArray?.isNotEmpty() == true }.last().body!!["trigger"]!!.jsonPrimitive.content)
        assertNotNull(db.referenceDao().meta(SyncEngine.KEY_DIGEST_HOLD))
        // The owed digest follows on the next run and comes clean: the purge hold is over.
        engine().run(SyncTrigger.FOREGROUND)
        assertTrue(fake.digests.isNotEmpty())
        assertTrue(fake.digests.last()["items"]!!.jsonArray.isNotEmpty())
        assertNull(db.referenceDao().meta(SyncEngine.KEY_DIGEST_HOLD))
        assertNull(db.referenceDao().meta(SyncEngine.KEY_DIGEST_DUE))
    }

    @Test
    fun aGenerationChangeHoldsThePurgeWhileTheDigestCannotRun() = runBlocking {
        synced()
        repeat(2) { sale() }
        engine().run(SyncTrigger.FOREGROUND)
        fake.restore(keep = 0, newGeneration = NEW_GEN, lostAfter = "2026-10-05T09:00:00.000Z")
        fake.mintedAt = "2026-10-05T10:30:00.000Z"
        Clock.now += 3_600_000L
        hint = NEW_GEN
        fake.digestFailBefore.add(503)
        engine().run(SyncTrigger.RESYNC)
        assertEquals(total(), fake.registry.size)
        assertNotNull("held until a clean digest", db.referenceDao().meta(SyncEngine.KEY_DIGEST_HOLD))
        assertEquals(SyncEngine.DIGEST_DUE_GENERATION, db.referenceDao().meta(SyncEngine.KEY_DIGEST_DUE))
        engine().run(SyncTrigger.FOREGROUND)
        assertNull(db.referenceDao().meta(SyncEngine.KEY_DIGEST_HOLD))
    }

    @Test
    fun withoutSinceKnownTheGenerationReadFallsBackToItsOwnLoss() = runBlocking {
        // A stored generation the server's history does not hold: earliest is null, lost_after_utc applies.
        synced()
        repeat(2) { sale() }
        engine().run(SyncTrigger.FOREGROUND)
        val kept = fake.registry.size
        Clock.now += 2 * 3_600_000L
        repeat(2) { sale() }
        engine().run(SyncTrigger.FOREGROUND)
        fake.restore(keep = kept, newGeneration = NEW_GEN, lostAfter = "2026-10-05T11:00:00.000Z")
        fake.history.remove(fake.history.keys.first()) // the lineage forgot G1
        Clock.now += 3_600_000L
        hint = NEW_GEN
        engine(digest = false).run(SyncTrigger.RESYNC)
        assertEquals(total(), fake.registry.size)
    }

    companion object {
        const val USER = 334003L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
        const val G2 = "7a1e2f3a-5b6c-4d7e-8f90-a1b2c3d4e5f7"
        const val NEW_GEN = "4d1e2f3a-5b6c-4d7e-8f90-a1b2c3d4e5f6"
    }
}
