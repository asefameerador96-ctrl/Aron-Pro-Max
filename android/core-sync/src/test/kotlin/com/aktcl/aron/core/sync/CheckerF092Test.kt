package com.aktcl.aron.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.SyncApi
import com.aktcl.aron.core.session.TrustedClockSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** Checker (refuter) for F-SYS-092 Resume config check. Each test is a defect: it fails on the current code. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerF092Test {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private var elapsed = 10_000_000L
    private var boot = 41
    private val wall = 1_791_165_600_000L // 2026-10-05T02:00Z
    private lateinit var anchors: File
    private lateinit var clock: TrustedClockSource
    private lateinit var raw: JsonObject

    @Before fun setUp(): Unit = runBlocking {
        server.start()
        anchors = tmp.newFile("anchors")
        clock = newClock()
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        raw = JsonObject(base + ("config" to Json.parseToJsonElement(
            """{"config_version":318,"values":[{"key":"geo.radius_m","value":100,"scope_type":"global","requires_ack":false}],"scheduled":[]}""",
        )))
        ReferenceRepository(db).apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)
        Unit
    }

    @After fun tearDown() { db.close(); runCatching { server.close() } }

    private fun newClock() = TrustedClockSource(anchors, { boot }, { elapsed }, { wall + (elapsed - 10_000_000L) })

    private fun check(c: TrustedClockSource = clock, gapMs: Long = 5 * 60_000L, cap: Int = 24): ResumeConfigCheck {
        val ok = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok,
            ClientIdentity("1.0.3+10003") { "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f" }, null) { c.onApiResponse(it) }
        return ResumeConfigCheck({ db }, SyncApi(client), c, gapMs, cap)
    }

    private fun api(code: Int, body: String = "") = MockResponse.Builder().code(code).addHeader("X-Aron-Api", "1")
        .addHeader("X-Server-Time", "2026-10-05T02:00:00.000Z").body(body)

    private val delta320 = """{"from_version":318,"to_version":320,"values":[{"key":"geo.radius_m","value":80,"scope_type":"global",
        "effective_from":null,"requires_ack":false}],"scheduled":[],"removed_keys":[],"calendar_changes":[]}"""

    private suspend fun radius() = ReferenceRepository(db).config("geo.radius_m", "2026-10-05T03:00:00.000Z")

    /**
     * Bundle apply overwrites a newer delta-applied config: it clears config_value and writes the bundle's config_version
     * (318) over the delta's to_version (320) with no comparison (ReferenceRepository.apply line ~117).
     */
    @Test fun aBundleWithAnOlderConfigVersionDoesNotRollBackADeltaAppliedConfig() = runBlocking {
        server.enqueue(api(200, delta320).build())
        assertEquals(ConfigCheckResult.APPLIED, check().checkOnResume(1))
        assertEquals("80", radius())
        // The same day snapshot (built before the config change) is applied again, e.g. after a 410 or an ETag-less refetch.
        ReferenceRepository(db).apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)
        assertEquals("320", db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION))
        assertEquals("80", radius())
    }

    /** A delta whose from_version is newer than the held version skips changes; it is applied anyway (no from_version guard). */
    @Test fun aDeltaThatSkipsVersionsIsNotApplied() = runBlocking {
        server.enqueue(api(200, delta320.replace("\"from_version\":318", "\"from_version\":319")).build())
        assertNotEquals(ConfigCheckResult.APPLIED, check().checkOnResume(1))
        assertEquals("318", db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION))
    }

    /** recentAnchors() survives a reboot in the anchor file; the gap check ignores bootCount and compares elapsed across boots. */
    @Test fun anAnchorFromAnotherBootIsNotARecentContact() = runBlocking {
        elapsed = 1_000_000L
        server.enqueue(api(304).build())
        assertEquals(ConfigCheckResult.UNCHANGED, check().checkOnResume(1)) // contact recorded at elapsed 1,000,000 on boot 41
        boot = 42 // the phone reboots; hours later its uptime is just past the old anchor's elapsed time
        elapsed = 1_100_000L
        val rebooted = newClock()
        server.enqueue(api(304).build())
        assertEquals(ConfigCheckResult.UNCHANGED, check(rebooted).checkOnResume(1))
        assertEquals(2, server.requestCount)
    }

    /** Offline resumes each spend one request of the daily cap (counted before the call); back online the day is capped. */
    @Test fun offlineResumesDoNotSpendTheDailyCap() = runBlocking {
        val c = check(gapMs = 0, cap = 3)
        repeat(3) {
            server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.ShutdownConnection).build())
            assertEquals(ConfigCheckResult.OFFLINE, c.checkOnResume(1))
        }
        server.enqueue(api(304).build())
        assertEquals(ConfigCheckResult.UNCHANGED, c.checkOnResume(1))
    }

    /** Two resumes at once (activity + visit refresh): no lock, the anchor moves only on the answer, so two GETs go out. */
    @Test fun twoConcurrentResumesSendOneRequest() = runBlocking {
        val c = check()
        repeat(2) { server.enqueue(api(304).headersDelay(700, TimeUnit.MILLISECONDS).build()) }
        (1..2).map { async(Dispatchers.IO) { c.checkOnResume(1) } }.awaitAll()
        assertEquals(1, server.requestCount)
    }

    /**
     * SyncEngine (line ~339) stores a batch response's server config_version as KEY_CONFIG_VERSION without applying any
     * delta; the resume check then asks `since=<server version>`, gets 304 and the stale config is never refreshed.
     */
    @Test fun aBatchResponseConfigVersionDoesNotHideTheDelta() = runBlocking {
        db.referenceDao().putMeta(SyncMetaEntity(SyncEngine.KEY_CONFIG_VERSION_SERVER, "320")) // what SyncEngine now stores on an ack
        server.enqueue(api(200, delta320).build())
        check().checkOnResume(1)
        assertEquals("/v1/config/delta?since=318", server.takeRequest().target)
    }
}
