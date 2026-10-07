package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.SyncApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** Checker (refuter) tests for F-SYS-006 at the downloader level. Each test reproduces one defect. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerF006Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var db: AronDatabase
    private lateinit var staging: File

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_165_600_000L // 2026-10-05T02:00:00Z = 08:00 in Dhaka
        override fun elapsedRealtimeMs(): Long = 1_000L
    }

    private fun body(version: String, date: String, prefetch: Boolean): String {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val meta = JsonObject(
            base["meta"]!!.jsonObject + mapOf(
                "bundle_version" to JsonPrimitive(version), "valid_for_business_date" to JsonPrimitive(date), "is_prefetch" to JsonPrimitive(prefetch),
            ),
        )
        val extra = Json.parseToJsonElement(
            """{
              "user": {"user_id": 1001, "username": "sr334001", "full_name": "রহিম উদ্দিন", "role": "SR", "locale": "bn", "bind_ordinal": 1, "memo_seq_block_size": 500},
              "config": {"config_version": 318, "values": [], "scheduled": []},
              "prices": [], "code_lists": [], "offers": [], "calendar": {}, "templates": [], "tasks": [],
              "reason_texts": {}
            }""",
        ).jsonObject
        return JsonObject(base + extra + ("meta" to meta)).toString()
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val forDate = request.url.queryParameter("for") ?: "2026-10-05"
                val version = if (forDate == "2026-10-06") "2026-10-06:1" else "2026-10-05:3"
                if (request.headers["If-None-Match"] == "\"$version\"") return MockResponse.Builder().code(304).addHeader("X-Aron-Api", "1").build()
                return MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").addHeader("ETag", "\"$version\"")
                    .body(body(version, forDate, prefetch = forDate != "2026-10-05")).build()
            }
        }
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
        staging = File(context.noBackupFilesDir, "bundle-staging-checker").also { it.deleteRecursively() }
    }

    @After fun tearDown() {
        db.close()
        runCatching { server.close() }
    }

    private fun downloader(): BundleDownloader {
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(
            ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok,
            ClientIdentity("1.0.3+10003") { "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f" },
        )
        return BundleDownloader(db, SyncApi(client), staging, Clock)
    }

    /** A prefetch never counts as a login (s4.9); a 304 to a repeated prefetch must not mark tomorrow logged in either. */
    @Test fun aNotModifiedPrefetchDoesNotMarkTomorrowLoggedIn() = runBlocking {
        val d = downloader()
        d.download("2026-10-06")
        assertFalse(d.loggedIn("2026-10-06"))
        assertEquals(BundleOutcome.UNCHANGED, d.download("2026-10-06").outcome)
        assertFalse(d.loggedIn("2026-10-06"))
    }

    /** An evening prefetch of D+1 during day D must leave today's routes in Room (offline selling continues). */
    @Test fun aPrefetchDuringTheDayKeepsTodaysRoutesInRoom() = runBlocking {
        val d = downloader()
        assertEquals(BundleOutcome.APPLIED, d.download().outcome)
        d.download("2026-10-06")
        assertEquals(2, com.aktcl.aron.core.database.repo.ReferenceRepository(db).routesOfDay("2026-10-05").size)
    }

    /** Paged `team` and `pending_outlet_requests` rows belong in `supervisor` (SupervisorSection), not at the top level. */
    @Test fun pagedTeamRowsAreMergedIntoTheSupervisorSection() {
        val raw = Json.parseToJsonElement("""{"supervisor": {"zone_ids": [7], "route_ids": [], "team": [{"user_id": 1}], "pending_outlet_requests": []}}""").jsonObject
        val merged = BundleDownloader.merge(raw, mapOf("team" to listOf(Json.parseToJsonElement("""{"user_id": 2}"""))))
        assertEquals(2, merged["supervisor"]!!.jsonObject["team"]!!.jsonArray.size)
        assertFalse(merged.containsKey("team"))
    }

    private companion object {
        const val USER = 1001L
    }
}
