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

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_194_400_000L
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
        return SyncEngine(USER, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", Clock, SyncPolicy(), random = Random(7), recordSigner = signer)
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

    private companion object {
        const val USER = 334003L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
