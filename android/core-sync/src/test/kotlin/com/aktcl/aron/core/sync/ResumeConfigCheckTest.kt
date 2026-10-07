package com.aktcl.aron.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.SyncApi
import com.aktcl.aron.core.session.TrustedClockSource
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** F-SYS-092: one conditional GET on resume when the last contact is older than the gap, at most the daily cap, no timer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ResumeConfigCheckTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private var elapsed = 10_000_000L
    private val wall = 1_791_165_600_000L // 2026-10-05T02:00Z
    private lateinit var clock: TrustedClockSource
    private lateinit var check: ResumeConfigCheck

    @Before fun setUp(): Unit = runBlocking {
        server.start()
        clock = TrustedClockSource(tmp.newFile("anchors"), { 41 }, { elapsed }, { wall + (elapsed - 10_000_000L) })
        val ok = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok,
            ClientIdentity("1.0.3+10003") { "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f" }, null) { clock.onApiResponse(it) }
        check = ResumeConfigCheck({ db }, SyncApi(client), clock)
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val raw = JsonObject(base + ("config" to Json.parseToJsonElement(
            """{"config_version":318,"values":[{"key":"geo.radius_m","value":100,"scope_type":"global","requires_ack":false}],"scheduled":[]}""",
        )))
        ReferenceRepository(db).apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)
        Unit
    }

    @After fun tearDown() { db.close(); runCatching { server.close() } }

    private fun api(code: Int, body: String = "") = MockResponse.Builder().code(code).addHeader("X-Aron-Api", "1")
        .addHeader("X-Server-Time", "2026-10-05T02:00:00.000Z").body(body).build()

    @Test fun aRecentContactMeansNoRequestAndAStaleOneMeansExactlyOne() = runBlocking {
        server.enqueue(api(304))
        assertEquals(ConfigCheckResult.UNCHANGED, check.checkOnResume(1)) // no contact yet: due
        assertEquals("/v1/config/delta?since=318", server.takeRequest().target)
        elapsed += 4 * 60_000
        assertEquals(ConfigCheckResult.NOT_DUE, check.checkOnResume(1)) // the 304 itself was a contact
        assertEquals(1, server.requestCount)
        elapsed += 2 * 60_000
        server.enqueue(api(304))
        assertEquals(ConfigCheckResult.UNCHANGED, check.checkOnResume(1))
        assertEquals(2, server.requestCount)
    }

    @Test fun aDeltaIsAppliedInOneGo() = runBlocking {
        val outlet = ReferenceRepository(db).routesOfDay("2026-10-05").first().outlets.first().outletId
        server.enqueue(api(200, """{"from_version":318,"to_version":320,
            "values":[{"key":"geo.radius_m","value":80,"scope_type":"zone","scope_id":7,"effective_from":null,"requires_ack":true}],
            "scheduled":[{"key":"sale.max_lines","value":50,"scope_type":"global","effective_from":"2026-10-06T00:00:00.000Z","requires_ack":false}],
            "removed_keys":["old.key"],"calendar_changes":[{"date":"2026-10-10","kind":"holiday"}],
            "outlet_radius_changes":[{"outlet_id":$outlet,"radius_m":150,"max_accuracy_m":60}],"policy_changed":true}"""))
        assertEquals(ConfigCheckResult.APPLIED, check.checkOnResume(1))
        val repo = ReferenceRepository(db)
        assertEquals("80", repo.config("geo.radius_m", "2026-10-05T03:00:00.000Z"))
        assertEquals("50", repo.config("sale.max_lines", "2026-10-06T00:00:00.000Z"))
        assertEquals("320", db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION))
        assertEquals(150, db.referenceDao().outlet(outlet)!!.radiusM)
        assertEquals("true", db.referenceDao().meta(ReferenceRepository.KEY_POLICY_REFRESH))
        assertTrue(repo.section("calendar_changes")!!.contains("2026-10-10"))
    }

    @Test fun anOldDeltaNeverMovesConfigBackwards() = runBlocking {
        server.enqueue(api(200, """{"from_version":300,"to_version":310,"values":[{"key":"geo.radius_m","value":5,"scope_type":"global","effective_from":null,"requires_ack":false}],"scheduled":[],"removed_keys":[],"calendar_changes":[]}"""))
        assertEquals(ConfigCheckResult.UNCHANGED, check.checkOnResume(1))
        assertEquals("100", ReferenceRepository(db).config("geo.radius_m", "2026-10-05T03:00:00.000Z"))
    }

    @Test fun atMostTheDailyCapThenNothing() = runBlocking {
        val capped = ResumeConfigCheck({ db }, SyncApi(AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true),
            OkHttpClient(), ClientIdentity("1") { null })), clock, gapMs = 0, dailyCap = 3)
        repeat(5) { server.enqueue(api(304)) }
        val results = (1..5).map { capped.checkOnResume(1) }
        assertEquals(listOf(ConfigCheckResult.UNCHANGED, ConfigCheckResult.UNCHANGED, ConfigCheckResult.UNCHANGED, ConfigCheckResult.CAPPED, ConfigCheckResult.CAPPED), results)
        assertEquals(3, server.requestCount)
    }

    @Test fun tooFarBehindAsksForABundleAndOfflineIsQuiet() = runBlocking {
        server.enqueue(api(410, """{"code":"ERR_CONFIG_DELTA_TOO_OLD","status":410}"""))
        assertEquals(ConfigCheckResult.NEEDS_BUNDLE, check.checkOnResume(1))
        assertEquals("true", db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_REFRESH))
        elapsed += 10 * 60_000
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.ShutdownConnection).build())
        assertEquals(ConfigCheckResult.OFFLINE, check.checkOnResume(1))
    }
}
