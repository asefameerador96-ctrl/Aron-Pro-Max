package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.SyncApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** F-SYS-006: one download, then the day runs from Room with no network; a killed download resumes without duplicates. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BundleDownloaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var db: AronDatabase
    private lateinit var staging: File
    private val requests = ArrayList<RecordedRequest>()
    private var paged = false
    private var dropPage2 = 0
    private var bundleBody: () -> String = { fullBundle() }
    private var deltaAnswer: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(304).addHeader("X-Aron-Api", "1").build() }

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_165_600_000L // 2026-10-05T02:00:00Z = 08:00 in Dhaka
        override fun elapsedRealtimeMs(): Long = 1_000L
    }

    private fun fixture(): JsonObject =
        Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject

    private fun fullBundle(): String {
        val base = fixture()
        val extra = Json.parseToJsonElement(
            """{
              "user": {"user_id": 1001, "username": "sr334001", "full_name": "রহিম উদ্দিন", "role": "SR", "locale": "bn", "bind_ordinal": 1, "memo_seq_block_size": 500},
              "config": {"config_version": 318, "values": [{"key": "geo.radius_m", "value": 100, "scope_type": "global", "requires_ack": false}], "scheduled": []},
              "prices": [{"id": 1, "sku_id": 100, "price_type": "outlet", "amount_mtk": 9000, "per_base_qty": 1, "valid_from": "2026-09-01"}],
              "code_lists": [], "offers": [], "calendar": {}, "templates": [], "tasks": [],
              "reason_texts": {"schema_invalid": {"bn": "তথ্য সঠিক নয়", "en": "Invalid data"}}
            }""",
        ).jsonObject
        var b = JsonObject(base + extra)
        if (paged) {
            val meta = JsonObject(b["meta"]!!.jsonObject + ("paged_sections" to Json.parseToJsonElement("""[{"section":"outlets","pages":2,"rows":200}]""")))
            val routes = JsonArray(b["routes"]!!.jsonArray.map { JsonObject(it.jsonObject + ("outlets" to JsonArray(emptyList()))) })
            b = JsonObject(b + ("meta" to meta) + ("routes" to routes))
        }
        return b.toString()
    }

    private fun page(n: Int): String {
        val all = fixture()["routes"]!!.jsonArray.flatMap { it.jsonObject["outlets"]!!.jsonArray }
        val rows = all.chunked(100)[n - 1]
        return buildJsonObject {
            put("bundle_version", JsonPrimitive("2026-10-05:3")); put("section", JsonPrimitive("outlets"))
            put("page", JsonPrimitive(n)); put("pages", JsonPrimitive(2)); put("rows", buildJsonArray { rows.forEach { add(it) } })
        }.toString()
    }

    private fun api(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("X-Aron-Api", "1").addHeader("ETag", "\"2026-10-05:3\"").body(body).build()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.url.encodedPath) {
                    "/v1/sync/bundle" ->
                        if (request.headers["If-None-Match"] == "\"2026-10-05:3\"") MockResponse.Builder().code(304).addHeader("X-Aron-Api", "1").build()
                        else api(bundleBody())
                    "/v1/sync/delta" -> deltaAnswer(request)
                    "/v1/sync/bundle/page" -> {
                        val n = request.url.queryParameter("page")!!.toInt()
                        if (n == 2 && dropPage2-- > 0) MockResponse.Builder().onRequestStart(SocketEffect.ShutdownConnection).build() else api(page(n))
                    }
                    else -> api("{}", 404)
                }
            }
        }
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
        staging = File(context.noBackupFilesDir, "bundle-staging-test").also { it.deleteRecursively() }
    }

    @After fun tearDown() {
        db.close()
        runCatching { server.close() }
    }

    private fun downloader(origin: String = server.url("/").toString().trimEnd('/')): BundleDownloader {
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(origin, allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f" })
        return BundleDownloader(db, SyncApi(client), staging, Clock)
    }

    private suspend fun assertDayIsInRoom() {
        val repo = ReferenceRepository(db)
        assertEquals("2026-10-05", repo.businessDate())
        val routes = repo.routesOfDay("2026-10-05")
        assertEquals(2, routes.size)
        assertEquals(200, routes.sumOf { it.outlets.size })
        assertEquals(200, db.referenceDao().outletCount())
        assertTrue(repo.skus().isNotEmpty())
        assertEquals(9000L, repo.priceOn(100, "outlet", "2026-10-05")!!.amountMtk)
        assertEquals("100", repo.config("geo.radius_m", "2026-10-05T02:00:00.000Z"))
        assertTrue(repo.section("user")!!.contains("রহিম উদ্দিন"))
    }

    @Test fun afterOneDownloadAirplaneModeStillLoadsTheWholeDayFromRoom() = runBlocking {
        val report = downloader().download()
        assertEquals(BundleOutcome.APPLIED, report.outcome)
        assertEquals("2026-10-05", requests.single().url.queryParameter("for")) // the Dhaka business date, not the UTC date
        server.close() // airplane mode
        assertEquals(BundleOutcome.OFFLINE, downloader().download().outcome)
        db.close(); db = AronDatabase.open(context, USER, null) // and a relaunch
        assertDayIsInRoom()
    }

    @Test fun theNextRequestOfTheDayIsConditionalAndCountsAsLoggedIn() = runBlocking {
        val d = downloader()
        d.download()
        assertTrue(d.loggedIn("2026-10-05"))
        assertEquals(BundleOutcome.UNCHANGED, d.download().outcome)
        assertEquals("\"2026-10-05:3\"", requests.last().headers["If-None-Match"])
        assertEquals("318", requests.last().headers["X-Config-Version"])
        assertFalse(d.loggedIn("2026-10-06"))
        assertDayIsInRoom()
    }

    @Test fun aDownloadKilledHalfWayResumesWithItsPagesAndAppliesOnceWithoutDuplicates() = runBlocking {
        paged = true
        dropPage2 = 1
        assertEquals(BundleOutcome.OFFLINE, downloader().download().outcome)
        assertEquals(0, db.referenceDao().outletCount()) // nothing applied from a partial snapshot
        assertTrue(File(staging, "2026-10-05_3/outlets-1.json").exists())

        db.close(); db = AronDatabase.open(context, USER, null) // the app was killed
        val pagesBefore = requests.count { it.url.encodedPath.endsWith("/page") }
        assertEquals(BundleOutcome.APPLIED, downloader().download().outcome)
        val pageRequests = requests.drop(pagesBefore + 1).filter { it.url.encodedPath.endsWith("/page") }
        assertEquals(listOf("2"), pageRequests.map { it.url.queryParameter("page") }) // page 1 came from the stage
        assertDayIsInRoom()
        assertTrue(staging.listFiles().orEmpty().isEmpty())

        paged = false
        downloader().download() // a later full re-apply of the same snapshot replaces, never adds
        assertEquals(200, db.referenceDao().outletCount())
    }

    @Test fun anEveningPrefetchStartsTomorrowOfflineAndNeverReplacesToday() = runBlocking {
        downloader().download()
        bundleBody = {
            val b = Json.parseToJsonElement(fullBundle()).jsonObject
            val meta = JsonObject(b["meta"]!!.jsonObject + mapOf("bundle_version" to JsonPrimitive("2026-10-06:1"), "valid_for_business_date" to JsonPrimitive("2026-10-06"), "is_prefetch" to JsonPrimitive(true)))
            val routes = JsonArray(b["routes"]!!.jsonArray.take(1))
            JsonObject(b + ("meta" to meta) + ("routes" to routes)).toString()
        }
        assertEquals(BundleOutcome.PREFETCH_STORED, downloader().download("2026-10-06").outcome)
        assertEquals("2026-10-06", requests.last().url.queryParameter("for"))
        assertDayIsInRoom() // today untouched
        assertFalse(downloader().loggedIn("2026-10-06"))

        server.close() // next morning, no network
        val tomorrow = object : WallClock {
            override fun nowMs() = Clock.nowMs() + 86_400_000L
            override fun elapsedRealtimeMs() = 1L
        }
        val ok = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f" })
        val r = BundleDownloader(db, SyncApi(client), staging, tomorrow).download()
        assertEquals(BundleOutcome.PREFETCH_PROMOTED, r.outcome)
        assertEquals("2026-10-06", ReferenceRepository(db).businessDate())
        assertEquals(1, ReferenceRepository(db).routesOfDay("2026-10-06").size)
    }

    @Test fun anUnreadableBundleKeepsThePreviousDay() = runBlocking {
        downloader().download()
        db.referenceDao().deleteMeta(ReferenceRepository.KEY_BUNDLE_ETAG) // force a 200
        bundleBody = { """{"meta": {"bundle_version": "2026-10-05:4"}}""" }
        val r = downloader().download()
        assertTrue(r.outcome == BundleOutcome.FAILED)
        assertNotNull(r.code)
        assertDayIsInRoom()
        assertEquals("2026-10-05:3", ReferenceRepository(db).bundleVersion())
    }

    private companion object {
        const val USER = 1001L
    }

    // ---- F-SYS-007: bundle delta refresh

    private fun problem(status: Int, code: String) = MockResponse.Builder().code(status).addHeader("X-Aron-Api", "1")
        .addHeader("Content-Type", "application/problem+json")
        .body("""{"type":"about:blank","title":"t","status":$status,"code":"$code","request_id":"r1"}""").build()

    private fun deltaBody(base: String = "c1") = """{"meta": {"bundle_version": "2026-10-05:4", "base_cursor": "$base", "cursor": "c2",
        "valid_for_business_date": "2026-10-05", "config_version": 318, "server_time": "2026-10-05T03:00:00.000Z"},
        "sections": {"prices": {"upsert": [{"id": 1, "sku_id": 100, "price_type": "outlet", "amount_mtk": 9500, "per_base_qty": 1, "valid_from": "2026-09-01"}], "delete": []}},
        "routes_added": [], "routes_removed": [], "day_states": [],
        "resolutions": [{"client_uuid": "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f", "type": "memo", "resolution": "accepted", "resolved_at": "2026-10-05T02:59:00.000Z"}]}"""

    @Test
    fun aDeltaAppliesOnlyChangedRowsAndIsNeverTheDaysLogin() = runBlocking {
        assertEquals(BundleOutcome.APPLIED, downloader().download().outcome)
        db.referenceDao().deleteMeta(BundleDownloader.KEY_LOGGED_IN + "2026-10-05") // as if the login were not recorded yet
        requests.clear()
        deltaAnswer = { r ->
            assertEquals("c1", r.url.queryParameter("since")); assertEquals("2026-10-05", r.url.queryParameter("for"))
            api(deltaBody())
        }
        val r = downloader().refreshDelta()
        assertEquals(BundleOutcome.APPLIED, r.outcome)
        assertEquals("2026-10-05:4", r.bundleVersion)
        assertEquals(listOf("/v1/sync/delta"), requests.map { it.url.encodedPath }) // no bundle request: no login event
        assertFalse(downloader().loggedIn("2026-10-05"))
        assertEquals(9500L, ReferenceRepository(db).priceOn(100, "outlet", "2026-10-05")!!.amountMtk)
        assertEquals(200, db.referenceDao().outletCount())
        assertEquals("accepted", db.referenceDao().meta(SyncEngine.RESOLUTION_PREFIX + "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"))
        deltaAnswer = { MockResponse.Builder().code(304).addHeader("X-Aron-Api", "1").build() }
        assertEquals(BundleOutcome.UNCHANGED, downloader().refreshDelta().outcome)
    }

    @Test
    fun aNewDateIsLeftToTheDayStartAndAnExpiredCursorFetchesTheSameDay() = runBlocking {
        downloader().download()
        requests.clear()
        deltaAnswer = { problem(409, "ERR_BUNDLE_NEW_BUSINESS_DATE") }
        assertEquals(BundleOutcome.NEW_DATE, downloader().refreshDelta().outcome)
        assertEquals(listOf("/v1/sync/delta"), requests.map { it.url.encodedPath })
        requests.clear()
        deltaAnswer = { problem(410, "ERR_BUNDLE_CURSOR_EXPIRED") }
        downloader().refreshDelta()
        assertEquals(listOf("/v1/sync/delta", "/v1/sync/bundle"), requests.map { it.url.encodedPath })
        assertEquals("2026-10-05", requests.last().url.queryParameter("for"))
        // A delta that does not continue the held cursor brings the full bundle of the same day.
        requests.clear()
        deltaAnswer = { api(deltaBody(base = "other")) }
        downloader().refreshDelta()
        assertEquals(listOf("/v1/sync/delta", "/v1/sync/bundle"), requests.map { it.url.encodedPath })
    }

    @Test
    fun theForegroundRefreshAsksAtMostEveryThirtyMinutesAndOnlyANewerServerBundleTriggersOneAfterSync() = runBlocking {
        downloader().download()
        requests.clear()
        assertNotNull(downloader().refreshOnForeground())
        assertNull(downloader().refreshOnForeground()) // within 30 min of an answered request
        assertEquals(1, requests.size)
        assertNull(downloader().refreshIfServerNewer()) // the server reported nothing newer
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(SyncEngine.KEY_BUNDLE_CURRENT, "2026-10-05:3"))
        assertNull(downloader().refreshIfServerNewer())
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(SyncEngine.KEY_BUNDLE_CURRENT, "2026-10-05:7"))
        assertNotNull(downloader().refreshIfServerNewer())
        assertEquals(2, requests.size)
    }
}
