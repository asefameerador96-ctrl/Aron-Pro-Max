package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.DeviceProofSigner
import com.aktcl.aron.core.network.ProofStrings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
 * F-SYS-072: header records carry `sig` = ES256 over `aron-sig-v1`, type, client_uuid, sha256(JCS(record without sig)),
 * made once per row with the enrolled key, so a resent batch is byte-for-byte the same record set (the server's batch
 * fingerprint includes sig). Other record types, and phones without a key, send no sig.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RecordSignatureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()
    private lateinit var db: AronDatabase
    private val signed = ArrayList<String>()
    private var counter = 0

    /** A stand-in for the Keystore key: a different 86-character value on every call, as ES256 would give. */
    private val signer = DeviceProofSigner { msg -> signed += msg; counter++; ("s" + counter).padEnd(86, 'x') }

    private val auth = object : UploadAuth {
        override suspend fun token(userId: Long): String? = "upload-1"
        override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
    }

    private var now = 1_791_194_400_000L
    private val clock = object : WallClock {
        override fun nowMs(): Long = now
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }

    @Before fun setUp() {
        server.dispatcher = fake
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
    }

    @After fun tearDown() { db.close(); server.close() }

    private fun engine(signer: DeviceProofSigner?): SyncEngine {
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
        return SyncEngine(USER, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", clock, SyncPolicy(), random = Random(7), recordSigner = signer)
    }

    private suspend fun visitWithClose(): Pair<String, String> {
        val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        val (visit, fix) = TestRows.visit(outletId = 50001L, seq = 1)
        repo.recordVisitOpen(visit, fix)
        val close = TestRows.close(visit.clientUuid)
        repo.recordVisitClose(close)
        return visit.clientUuid to close.clientUuid
    }

    private fun sentRecords(): List<JsonObject> = fake.requests.mapNotNull { it.body }.flatMap { b -> b["records"]!!.jsonArray.map { it.jsonObject } }

    @Test fun aHeaderRecordIsSignedOverItsCanonicalFormAndOthersAreNot() = runBlocking {
        val (visitUuid, _) = visitWithClose()
        assertEquals(SyncStop.DRAINED, engine(signer).run(SyncTrigger.MANUAL).stop)
        val records = sentRecords()
        val visit = records.single { it["client_uuid"]!!.jsonPrimitive.content == visitUuid }
        val sig = visit["sig"]!!.jsonPrimitive.content
        assertEquals(86, sig.length)
        // Signed over exactly what was sent, without sig: what the server recomputes (IngestService step 6).
        assertEquals(ProofStrings.record("visit", visitUuid, JsonObject(visit.filterKeys { it != "sig" })), signed.single())
        records.filter { it["type"]!!.jsonPrimitive.content !in SyncEngine.SIGNED_TYPES }.forEach { r ->
            assertTrue("no sig on ${r["type"]}", r["sig"] == null || r["sig"] is kotlinx.serialization.json.JsonNull)
        }
    }

    @Test fun aResentBatchCarriesTheSameSigAndNothingIsSignedTwice() = runBlocking {
        val (visitUuid, _) = visitWithClose()
        fake.failBefore += 503
        assertEquals(SyncStop.RETRY_LATER, engine(signer).run(SyncTrigger.MANUAL).stop)
        assertEquals(SyncStop.DRAINED, engine(signer).run(SyncTrigger.MANUAL).stop)
        val sigs = sentRecords().filter { it["client_uuid"]!!.jsonPrimitive.content == visitUuid }.map { it["sig"]!!.jsonPrimitive.content }
        assertEquals(2, sigs.size)
        assertEquals(1, sigs.toSet().size)
        assertEquals(1, signed.size)
    }

    /** Checker: a row that already went out unsigned and came back (parked, released) stays unsigned, byte-identical. */
    @Test fun aRowAlreadySentUnsignedIsNeverSignedLater() = runBlocking {
        val (visitUuid, _) = visitWithClose()
        val row = db.outboxDao().nextSendable(10, 99, emptyList()).first { it.clientUuid == visitUuid }
        db.outboxDao().markInFlight("b-old", listOf(row.seq))
        db.outboxDao().returnToPending("b-old", "parent_missing") // what a parked or released row looks like
        assertEquals(SyncStop.DRAINED, engine(signer).run(SyncTrigger.MANUAL).stop)
        val visit = sentRecords().single { it["client_uuid"]!!.jsonPrimitive.content == visitUuid }
        assertTrue(visit["sig"] == null || visit["sig"] is kotlinx.serialization.json.JsonNull)
        assertTrue(signed.isEmpty())
    }

    @Test fun beforeEnrolmentRecordsGoWithoutSig() = runBlocking {
        val (visitUuid, _) = visitWithClose()
        assertEquals(SyncStop.DRAINED, engine(DeviceProofSigner { null }).run(SyncTrigger.MANUAL).stop)
        val visit = sentRecords().single { it["client_uuid"]!!.jsonPrimitive.content == visitUuid }
        assertTrue(visit["sig"] == null || visit["sig"] is kotlinx.serialization.json.JsonNull)
    }

    /**
     * The state after an older server quarantined [uuids] as device_integrity_failed: the server's registry holds them
     * (released on resend unless enforce), the phone holds them as terminal.
     */
    private suspend fun quarantinedBefore(vararg uuids: String, code: String = "device_integrity_failed") {
        val rows = db.outboxDao().nextSendable(10, 99, emptyList()).filter { it.clientUuid in uuids }
        db.outboxDao().markInFlight("b-old", rows.map { it.seq })
        uuids.forEach { db.outboxDao().applyAck(it, "quarantined", code, null, "2026-10-05T05:00:00.000Z") }
        if (code == "device_integrity_failed") fake.integrityHeld += uuids
    }

    private fun sentCount(uuid: String) = sentRecords().count { it["client_uuid"]!!.jsonPrimitive.content == uuid }
    private suspend fun state(uuid: String) = db.outboxDao().byClientUuid(uuid)!!.state
    private fun nextDay() { now += 24 * 3_600_000L }

    /** Lead ask (BC-53): the server releases the held rows on resend; stored once, never re-signed, then done. */
    @Test fun integrityQuarantinedRowsAreReleasedByUuidAndStoredOnce() = runBlocking {
        val (visitUuid, closeUuid) = visitWithClose()
        quarantinedBefore(visitUuid, closeUuid)
        assertEquals(0, db.outboxDao().unsentCount())
        assertEquals(SyncStop.DRAINED, engine(signer).run(SyncTrigger.MANUAL).stop)
        assertEquals(1, sentCount(visitUuid))
        assertEquals(1, fake.storedOf("visit").size)
        assertEquals("acked", state(visitUuid))
        assertEquals("acked", state(closeUuid))
        assertTrue("a row that already went out is never signed", signed.isEmpty())
        nextDay()
        engine(signer).run(SyncTrigger.MANUAL)
        assertEquals("the episode ended", null, db.referenceDao().meta(SyncEngine.KEY_INTEGRITY_RELEASE))
        assertEquals(1, sentCount(visitUuid))
    }

    /** Re-checker: a run that stops between the release and the answer never ends the episode. */
    @Test fun aRunStoppedAfterTheReleaseKeepsTheEpisode() = runBlocking {
        val (visitUuid, closeUuid) = visitWithClose()
        quarantinedBefore(visitUuid, closeUuid)
        fake.enforce = true
        fake.failBefore += 503
        assertEquals(SyncStop.RETRY_LATER, engine(signer).run(SyncTrigger.MANUAL).stop) // released, then no answer
        engine(signer).run(SyncTrigger.MANUAL) // the resend reaches the older server: quarantined again
        assertEquals("quarantined", state(visitUuid))
        assertTrue(db.referenceDao().meta(SyncEngine.KEY_INTEGRITY_RELEASE)!!.startsWith("1:"))
        nextDay()
        fake.enforce = false
        engine(signer).run(SyncTrigger.MANUAL)
        assertEquals("acked", state(visitUuid))
        assertEquals(1, fake.storedOf("visit").size)
    }

    /** Rows quarantined after an episode ended start a new one. */
    @Test fun rowsQuarantinedLaterStartANewEpisode() = runBlocking {
        val (visitUuid, closeUuid) = visitWithClose()
        quarantinedBefore(visitUuid, closeUuid)
        fake.enforce = true
        repeat(SyncEngine.INTEGRITY_RELEASE_ROUNDS + 1) { engine(signer).run(SyncTrigger.MANUAL); nextDay() }
        fake.enforce = false
        fake.integrityHeld.clear()
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'rejected' WHERE client_uuid IN (?, ?)", arrayOf(visitUuid, closeUuid)) // a reviewer's resolution
        engine(signer).run(SyncTrigger.MANUAL)
        assertEquals(null, db.referenceDao().meta(SyncEngine.KEY_INTEGRITY_RELEASE))
        val (v2, c2) = visitWithClose()
        quarantinedBefore(v2, c2)
        engine(signer).run(SyncTrigger.MANUAL)
        assertEquals("acked", state(v2))
    }

    /** Checker: the build reaches the phone before the server has BC-53 (or enforce): next day's round catches the upgrade. */
    @Test fun anOlderServerFirstThenTheUpgradedServerReleasesOnALaterDay() = runBlocking {
        val (visitUuid, closeUuid) = visitWithClose()
        quarantinedBefore(visitUuid, closeUuid)
        fake.enforce = true
        engine(signer).run(SyncTrigger.MANUAL)
        engine(signer).run(SyncTrigger.MANUAL) // same business date: no second round
        assertEquals(1, sentCount(visitUuid))
        assertEquals("quarantined", state(visitUuid))
        nextDay()
        fake.enforce = false // the server got BC-53 overnight
        engine(signer).run(SyncTrigger.MANUAL)
        assertEquals(2, sentCount(visitUuid))
        assertEquals("acked", state(visitUuid))
        assertEquals(1, fake.storedOf("visit").size)
    }

    /** Under enforce for good: one round per day up to the cap, then the rows stay quarantined (bounded data cost). */
    @Test fun underEnforceTheReleaseIsBoundedPerEpisode() = runBlocking {
        val (visitUuid, closeUuid) = visitWithClose()
        quarantinedBefore(visitUuid, closeUuid)
        fake.enforce = true
        repeat(SyncEngine.INTEGRITY_RELEASE_ROUNDS + 2) { engine(signer).run(SyncTrigger.MANUAL); nextDay() }
        assertEquals(SyncEngine.INTEGRITY_RELEASE_ROUNDS, sentCount(visitUuid))
        assertEquals("quarantined", state(visitUuid))
        assertTrue(fake.storedOf("visit").isEmpty())
    }

    /** The server already holds the row (accepted, ack lost): the resend is a duplicate, never a second sale. */
    @Test fun aReleasedRowTheServerAlreadyHoldsIsADuplicate() = runBlocking {
        val (visitUuid, closeUuid) = visitWithClose()
        assertEquals(SyncStop.DRAINED, engine(DeviceProofSigner { null }).run(SyncTrigger.MANUAL).stop)
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'quarantined', last_code = 'device_integrity_failed' WHERE client_uuid IN (?, ?)", arrayOf(visitUuid, closeUuid))
        db.openHelper.writableDatabase.execSQL("DELETE FROM sync_meta WHERE `key` = ?", arrayOf(SyncEngine.KEY_INTEGRITY_RELEASE))
        assertEquals(SyncStop.DRAINED, engine(DeviceProofSigner { null }).run(SyncTrigger.MANUAL).stop)
        assertEquals(1, fake.storedOf("visit").size)
        assertEquals("acked", state(visitUuid))
    }

    @Test fun aQuarantineForAnotherReasonIsNeverReleased() = runBlocking {
        val (visitUuid, closeUuid) = visitWithClose()
        quarantinedBefore(visitUuid, closeUuid, code = "arithmetic_mismatch")
        engine(signer).run(SyncTrigger.MANUAL)
        assertEquals(0, sentCount(visitUuid))
        assertEquals("quarantined", state(visitUuid))
    }

    private companion object {
        const val USER = 334003L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
