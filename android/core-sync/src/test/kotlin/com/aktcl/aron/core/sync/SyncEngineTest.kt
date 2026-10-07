package com.aktcl.aron.core.sync

import com.aktcl.aron.contract.RecordAck
import com.aktcl.aron.contract.SyncBatchResponse
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.ApiResult
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SyncEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()
    private lateinit var db: AronDatabase
    private lateinit var api: SyncBatchApi
    private val auth = FakeAuth()

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_194_400_000L // 2026-10-05T10:00:00Z, business date 2026-10-05 in Dhaka
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }

    class FakeAuth : UploadAuth {
        val tokens = ArrayDeque(listOf("upload-1"))
        var refreshes = 0
        override suspend fun token(userId: Long): String? = tokens.firstOrNull()
        override suspend fun refresh(userId: Long, rejected: String?): Boolean {
            refreshes++
            if (tokens.size <= 1) return false
            tokens.removeFirst()
            return true
        }
    }

    /** Simulates process death on the [killAt]-th send: before the request leaves, or after the server answered. */
    class KillingSender(private val delegate: BatchSender, private val killAt: Int, private val afterServer: Boolean) : BatchSender {
        var sends = 0
        override suspend fun send(token: String, deviceUuid: String, batchUuid: String, gzipBody: ByteArray, headers: BatchHeaders): ApiResult<SyncBatchResponse> {
            sends++
            if (sends == killAt && !afterServer) throw ProcessDeath()
            val r = delegate.send(token, deviceUuid, batchUuid, gzipBody, headers)
            if (sends == killAt) throw ProcessDeath()
            return r
        }
    }

    class ProcessDeath : RuntimeException("killed")

    @Before fun setUp() {
        server.dispatcher = fake
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
        api = SyncBatchApi(client, null)
    }

    @After fun tearDown() {
        db.close()
        server.close()
    }

    private fun engine(sender: BatchSender = api, policy: SyncPolicy = SyncPolicy(), seed: Int = 7) =
        SyncEngine(USER, db, sender, auth, { DEVICE }, "1.0.3+10003", Clock, policy, random = Random(seed))

    private fun reopen() {
        db.close()
        db = AronDatabase.open(context, USER, null)
    }

    /** One visit with a two-line sale (memo, 2 lines, 1 discount, 1 QC line) and a close: 7 records in one family. */
    private suspend fun sale(n: Int): String {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val (visit, fix) = TestRows.visit(outletId = 50001L + n, seq = n)
        repo.recordVisitOpen(visit, fix)
        val s = TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-%03d".format(n))
        repo.recordSale(s.copy(memo = s.memo.copy(outletId = 50001L + n)))
        repo.recordVisitClose(TestRows.close(visit.clientUuid))
        return s.memo.clientUuid
    }

    private suspend fun inState(): List<OutboxEntity> {
        val all = ArrayList<OutboxEntity>()
        val c = db.openHelper.readableDatabase.query("SELECT client_uuid FROM outbox ORDER BY seq")
        while (c.moveToNext()) all += db.outboxDao().byClientUuid(c.getString(0))!!
        c.close()
        return all
    }

    private suspend fun assertServerHasExactly(memos: Int) {
        assertEquals("memos on the server", memos, fake.storedOf("memo").size)
        assertEquals("memo lines on the server", memos * 2, fake.storedOf("memo_line").size)
        val all = inState()
        assertTrue("every row acked: ${all.filter { it.state != OutboxState.ACKED }.map { it.state + "/" + it.lastCode }}", all.all { it.state == OutboxState.ACKED })
        for (row in all) assertEquals(fake.registry[row.clientUuid]!!.serverId, row.serverId)
        assertEquals(all.size, fake.registry.size)
    }

    @Test fun oneSaleUploadsOnceWithTheContractEnvelope() = runBlocking {
        val memo = sale(1)
        val report = engine().run(SyncTrigger.MANUAL)
        assertEquals(SyncStop.DRAINED, report.stop)
        assertEquals(7, report.acked)
        assertServerHasExactly(1)
        assertEquals(memo, fake.storedOf("memo").single().record["client_uuid"]!!.jsonPrimitive.content)

        val req = fake.requests.single()
        assertTrue(req.gzip)
        assertEquals("1", req.attempt)
        val body = req.body!!
        for (m in listOf("batch_uuid", "device_uuid", "schema_version", "app_version", "trigger", "sent_at_device", "pending_rows", "time_anchors", "device_counts", "records")) {
            assertNotNull("required member $m", body[m])
        }
        assertEquals("manual", body["trigger"]!!.jsonPrimitive.content)
        assertEquals(DEVICE, body["device_uuid"]!!.jsonPrimitive.content)
        assertEquals("1", body["device_counts"]!!.jsonObject["2026-10-05"]!!.jsonObject["memo"]!!.jsonPrimitive.content)
        // Commit order: the visit header first, then the memo, then its children (docs/24 s4.2 rule 1).
        val types = body["records"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals("visit", types.first()); assertTrue(types.indexOf("memo") < types.indexOf("memo_line"))
        assertEquals("2026-10-05T10:00:00.000Z", db.referenceDao().meta(SyncEngine.KEY_LAST_SUCCESS))
        assertNotNull(db.referenceDao().meta(SyncEngine.KEY_SERVER_TOTALS + "2026-10-05"))
        assertNull(db.referenceDao().meta(SyncEngine.KEY_LAST_ERROR))
    }

    @Test fun emptyOutboxSendsNothing() = runBlocking {
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.PERIODIC).stop)
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun lostResponseAfterCommitResendsTheSameBatchAndGetsTheReplay() = runBlocking {
        sale(1)
        fake.dropAfterCommit = 1
        val first = engine().run(SyncTrigger.WRITE_DEBOUNCE)
        assertEquals(SyncStop.OFFLINE, first.stop)
        assertTrue(inState().all { it.state == OutboxState.IN_FLIGHT })

        val second = engine().run(SyncTrigger.CONNECTIVITY)
        assertEquals(SyncStop.DRAINED, second.stop)
        assertEquals(2, fake.requests.size)
        assertEquals(fake.requests[0].batchUuid, fake.requests[1].batchUuid)
        assertEquals("2", fake.requests[1].attempt)
        assertTrue(fake.requests[1].replayed)
        assertServerHasExactly(1)
    }

    @Test fun killBeforeTheRequestLeavesResendsThePersistedBatchAfterRelaunch() = runBlocking {
        sale(1); sale(2)
        val killer = KillingSender(api, killAt = 1, afterServer = false)
        runCatching { engine(killer).run(SyncTrigger.WRITE_DEBOUNCE) }.also { assertTrue(it.exceptionOrNull() is ProcessDeath) }
        val inFlight = inState().filter { it.state == OutboxState.IN_FLIGHT }
        assertEquals(14, inFlight.size)
        reopen()
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.FOREGROUND).stop)
        assertEquals(inFlight.first().batchUuid, fake.requests.first().batchUuid)
        assertEquals("2", fake.requests.first().attempt)
        assertServerHasExactly(2)
    }

    @Test fun killAfterTheServerCommittedEndsInTheReplayNotASecondMemo() = runBlocking {
        sale(1)
        runCatching { engine(KillingSender(api, killAt = 1, afterServer = true)).run(SyncTrigger.MANUAL) }
        assertEquals(1, fake.storedOf("memo").size)
        reopen()
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
        assertTrue(fake.requests.last().replayed)
        assertServerHasExactly(1)
    }

    @Test fun theSameRecordsSentTwiceUnderNewBatchUuidsAreDuplicatesNotNewRows() = runBlocking {
        sale(1)
        engine().run(SyncTrigger.MANUAL)
        // Force every row back to pending as if the device had never seen the acks, then send again (a new batch_uuid).
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'pending', server_id = NULL, acked_at = NULL")
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
        assertTrue(fake.requests[0].batchUuid != fake.requests[1].batchUuid)
        assertServerHasExactly(1)
    }

    @Test fun retryableRejectStaysPendingAndIsHeldForTheRunThenStoredLater() = runBlocking {
        val memo = sale(1)
        fake.rejectRetryable[memo] = 1
        val r = engine().run(SyncTrigger.MANUAL)
        assertEquals(SyncStop.RETRY_LATER, r.stop)
        assertEquals(1, r.deferred)
        assertEquals(OutboxState.PENDING, db.outboxDao().byClientUuid(memo)!!.state)
        assertEquals("parent_missing", db.outboxDao().byClientUuid(memo)!!.lastCode)
        assertEquals(1, fake.requests.size) // not resent in a tight loop
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.PERIODIC).stop)
        assertServerHasExactly(1)
    }

    @Test fun finalRejectAndQuarantineAreTerminalAndResolutionsApply() = runBlocking {
        val memo1 = sale(1); val memo2 = sale(2)
        fake.rejectFinal += memo1
        fake.quarantine += memo2
        val r = engine().run(SyncTrigger.MANUAL)
        assertEquals(SyncStop.DRAINED, r.stop)
        assertEquals(1, r.rejected); assertEquals(1, r.quarantined)
        assertEquals(OutboxState.REJECTED, db.outboxDao().byClientUuid(memo1)!!.state)
        assertEquals("schema_invalid", db.outboxDao().byClientUuid(memo1)!!.lastCode)
        assertEquals(OutboxState.QUARANTINED, db.outboxDao().byClientUuid(memo2)!!.state)

        sale(3)
        fake.pendingResolutions += memo2 to "accepted_with_fix"
        engine().run(SyncTrigger.MANUAL)
        assertEquals(OutboxState.ACKED, db.outboxDao().byClientUuid(memo2)!!.state)
        assertEquals("resolved_accepted_with_fix", db.outboxDao().byClientUuid(memo2)!!.lastCode)
        assertEquals(OutboxState.REJECTED, db.outboxDao().byClientUuid(memo1)!!.state) // a resolution never revives a reject
    }

    @Test fun a500BisectsUntilThePoisonFamilyIsAloneAndTheOthersStillGo() = runBlocking {
        sale(1); val poison = sale(2); sale(3); sale(4)
        fake.poison += poison
        val r = engine().run(SyncTrigger.MANUAL)
        assertEquals(SyncStop.RETRY_LATER, r.stop)
        assertEquals(3, fake.storedOf("memo").size)
        val poisonRow = db.outboxDao().byClientUuid(poison)!!
        assertEquals(OutboxState.PENDING, poisonRow.state)
        val family = inState().filter { it.familyUuid == poisonRow.familyUuid }
        assertTrue(family.all { it.state == OutboxState.PENDING })
        // Every batch that reached the server was a new uuid (bisect never reuses a uuid with other members).
        val uuids = fake.requests.map { it.batchUuid }
        assertEquals(uuids.size, uuids.toSet().size)
        fake.poison.clear()
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
        assertServerHasExactly(4)
    }

    @Test fun aRowThatKeepsFailingIsRejectedRetryExhaustedAfterTenSendsAndStillCounted() = runBlocking {
        val memo = sale(1)
        fake.rejectRetryable[memo] = 100
        repeat(12) { engine().run(SyncTrigger.PERIODIC) }
        val row = db.outboxDao().byClientUuid(memo)!!
        assertEquals(OutboxState.REJECTED, row.state)
        assertEquals(AckRules.RETRY_EXHAUSTED, row.lastCode)
        assertEquals(10, row.attempts)
        assertEquals(1, db.outboxDao().committedCounts("2026-10-05").single { it.recordType == "memo" }.count)
    }

    @Test fun anExpiredUploadTokenIsRefreshedOnceAndTheSameBatchResent() = runBlocking {
        sale(1)
        auth.tokens.addLast("upload-2")
        fake.validTokens = mutableSetOf("upload-2")
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.MANUAL).stop)
        assertEquals(1, auth.refreshes)
        assertEquals(fake.requests[0].batchUuid, fake.requests[1].batchUuid)
        assertServerHasExactly(1)
    }

    @Test fun noTokenAtAllStopsWithoutMarkingAnythingInFlight() = runBlocking {
        sale(1)
        auth.tokens.clear()
        assertEquals(SyncStop.AUTH_REQUIRED, engine().run(SyncTrigger.MANUAL).stop)
        assertTrue(inState().all { it.state == OutboxState.PENDING })
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun serviceUnavailableKeepsTheBatchForAnIdenticalResend() = runBlocking {
        sale(1)
        fake.failBefore += 503
        val r = engine().run(SyncTrigger.PERIODIC)
        assertEquals(SyncStop.RETRY_LATER, r.stop)
        assertNotNull(r.retryAfterMs)
        assertEquals(SyncStop.DRAINED, engine().run(SyncTrigger.PERIODIC).stop)
        assertEquals(fake.requests[0].batchUuid, fake.requests[1].batchUuid)
        assertServerHasExactly(1)
    }

    @Test fun holdPausesAutomaticRunsButNotManualOnes() = runBlocking {
        sale(1); sale(2)
        fake.holdS = 60
        val auto = engine(policy = SyncPolicy(batchMaxRows = 7)).run(SyncTrigger.PERIODIC)
        assertEquals(SyncStop.RETRY_LATER, auto.stop)
        assertTrue(auto.retryAfterMs!! >= 60_000)
        assertEquals(1, fake.requests.size)
        assertEquals(SyncStop.DRAINED, engine(policy = SyncPolicy(batchMaxRows = 7)).run(SyncTrigger.MANUAL).stop)
    }

    @Test fun batchesRespectTheRowCapAndKeepFamiliesWhole() = runBlocking {
        repeat(4) { sale(it + 1) }
        assertEquals(SyncStop.DRAINED, engine(policy = SyncPolicy(batchMaxRows = 10)).run(SyncTrigger.MANUAL).stop)
        // 7-row families with a 10-row cap: one family per batch, never 7 + 3.
        assertTrue(fake.requests.all { it.body!!["records"]!!.jsonArray.size == 7 })
        assertServerHasExactly(4)
    }

    @Test fun aResolutionForARowNotYetQuarantinedLocallyIsKeptAndAppliedLater() = runBlocking {
        val memo = sale(1)
        fake.rejectRetryable[memo] = 1
        fake.pendingResolutions += memo to "accepted"
        engine().run(SyncTrigger.MANUAL)
        assertEquals(OutboxState.PENDING, db.outboxDao().byClientUuid(memo)!!.state)
        fake.quarantine += memo
        engine().run(SyncTrigger.MANUAL)
        assertEquals(OutboxState.ACKED, db.outboxDao().byClientUuid(memo)!!.state)
        assertEquals("resolved_accepted", db.outboxDao().byClientUuid(memo)!!.lastCode)
        assertTrue(db.referenceDao().metaWithPrefix("sync.resolution.").isEmpty())
    }

    @Test fun aReplayedResponseDoesNotMoveServerStateBackwards() = runBlocking {
        sale(1)
        fake.dropAfterCommit = 1
        engine().run(SyncTrigger.MANUAL)
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(SyncEngine.KEY_CONFIG_VERSION, "999"))
        engine().run(SyncTrigger.MANUAL)
        assertTrue(fake.requests.last().replayed)
        assertEquals("999", db.referenceDao().meta(SyncEngine.KEY_CONFIG_VERSION))
        assertServerHasExactly(1)
    }

    @Test fun aSingleFamilyThatTheServerKeepsRefusingWith413EndsRetryExhaustedNotStuck() = runBlocking {
        val memo = sale(1)
        fake.failBefore.addAll(List(30) { 413 })
        repeat(12) { engine().run(SyncTrigger.PERIODIC) }
        assertEquals(AckRules.RETRY_EXHAUSTED, db.outboxDao().byClientUuid(memo)!!.lastCode)
        assertEquals(0, db.outboxDao().unsentCount())
    }

    @Test fun assembleCutsAtTheByteCapButAlwaysTakesOneRow() {
        val big = "x".repeat(200 * 1024)
        fun row(seq: Long, fam: String) = OutboxEntity(seq, ClientIds.newUuid(), "memo", fam, 1, "2026-10-05", big, "h", createdAt = "t")
        val e = engine()
        assertEquals(1, e.assemble(listOf(row(1, "a"), row(2, "b")), 200).size)
        assertEquals(1, e.assemble(listOf(row(1, "a"), row(2, "a")), 200).size) // a family larger than the cap still moves
        val small = listOf(1L, 2, 3).map { OutboxEntity(it, ClientIds.newUuid(), "memo", if (it < 3) "a" else "b", 1, "d", "{}", "h", createdAt = "t") }
        assertEquals(listOf(1L, 2L), e.assemble(small + small.last().copy(seq = 4), 3).map { it.seq })
    }

    /**
     * The acceptance property (F-SYS-008): whatever the faults (lost responses, kills before or after the server, 500s,
     * 503s, retryable rejects, small batches that split families, replays of old batches by a second path), the server ends
     * with exactly one memo and one set of lines per sale and the device's rows are all acked with the server's ids.
     */
    @Test fun anyMixOfDuplicatesReordersAndKillsLeavesExactlyOneMemoPerSale() = runBlocking {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val recorded = RecordingSender(api)
            var memos = 0
            repeat(rnd.nextInt(3, 9)) { step ->
                repeat(rnd.nextInt(0, 3)) { sale(++memos) }
                when (rnd.nextInt(7)) {
                    0 -> fake.dropAfterCommit = 1
                    1 -> fake.failBefore += 503
                    2 -> fake.failBefore += 500
                    3 -> inState().filter { it.recordType == "memo" && it.state == OutboxState.PENDING }.randomOrNull(rnd)?.let { fake.rejectRetryable[it.clientUuid] = 1 }
                    else -> Unit
                }
                val policy = SyncPolicy(batchMaxRows = rnd.nextInt(1, 12))
                val sender: BatchSender = if (rnd.nextInt(4) == 0) KillingSender(recorded, 1, rnd.nextBoolean()) else recorded
                runCatching { engine(sender, policy, seed * 31 + step).run(SyncTrigger.entries.random(rnd)) }
                if (rnd.nextInt(3) == 0) reopen()
                if (rnd.nextInt(3) == 0) recorded.replayOne(rnd) // a duplicate upload of an old batch
            }
            fake.dropAfterCommit = 0; fake.failBefore.clear(); fake.rejectRetryable.clear()
            var guard = 0
            while (db.outboxDao().unsentCount() > 0 && guard++ < 50) engine(seed = guard).run(SyncTrigger.MANUAL)
            assertServerHasExactly(memos)
            // Reset for the next seed.
            db.close(); context.deleteDatabase(AronDatabase.fileName(USER)); db = AronDatabase.open(context, USER, null)
            fake.registry.clear()
        }
    }

    /** Keeps every request so the test can send an old one again, exactly as it went. */
    class RecordingSender(private val delegate: BatchSender) : BatchSender {
        private val sent = ArrayList<Array<Any>>()
        override suspend fun send(token: String, deviceUuid: String, batchUuid: String, gzipBody: ByteArray, headers: BatchHeaders): ApiResult<SyncBatchResponse> {
            sent += arrayOf(token, deviceUuid, batchUuid, gzipBody, headers)
            return delegate.send(token, deviceUuid, batchUuid, gzipBody, headers)
        }

        suspend fun replayOne(rnd: Random) {
            val s = sent.randomOrNull(rnd) ?: return
            delegate.send(s[0] as String, s[1] as String, s[2] as String, s[3] as ByteArray, s[4] as BatchHeaders)
        }
    }

    private companion object {
        const val USER = 334001L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
