package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.BundleDeltaResult
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AUD-PERF-06: the AMO shape (a 54-route zone, about 6,000 outlets with Bangla names) goes through the real download path
 * (decoded once from the response stream), applies inside the 1.5 s budget and under a heap ceiling, and a large delta is
 * applied as chunked upserts and tombstones.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AmoShapeBundleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var db: AronDatabase
    private lateinit var staging: File

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_165_600_000L // 2026-10-05T02:00:00Z = 08:00 in Dhaka
        override fun elapsedRealtimeMs(): Long = 1_000L
    }

    @Before fun setUp() {
        server.start()
        context.deleteDatabase(AronDatabase.fileName(USER))
        db = AronDatabase.open(context, USER, null)
        staging = File(context.noBackupFilesDir, "bundle-staging-amo").also { it.deleteRecursively() }
    }

    @After fun tearDown() {
        db.close()
        runCatching { server.close() }
    }

    private fun fixture(): JsonObject =
        Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject

    private fun outlet(template: JsonObject, id: Long, routeId: Long): JsonObject = JsonObject(
        template + mapOf(
            "outlet_id" to JsonPrimitive(id), "route_id" to JsonPrimitive(routeId), "code" to JsonPrimitive("O-$id"),
            "name" to JsonPrimitive("Store $id"), "name_bn" to JsonPrimitive("মেসার্স রহমান জেনারেল স্টোর $id"),
            "owner_name" to JsonPrimitive("মোঃ আব্দুল করিম $id"), "name_sort_key" to JsonPrimitive("store %07d".format(id)),
        ),
    )

    /** 54 routes of 111 outlets (5,994), every name in Bangla, the rest of the shape from the SR fixture. */
    private fun amoBundle(): String {
        val base = fixture()
        val route = base["routes"]!!.jsonArray.first().jsonObject
        val template = route["outlets"]!!.jsonArray.first().jsonObject
        val routes = JsonArray((0 until ROUTES).map { r ->
            val routeId = 20_000L + r
            val outlets = JsonArray((0 until PER_ROUTE).map { o -> outlet(template, 100_000L + r * PER_ROUTE + o, routeId) })
            val inner = JsonObject(route["route"]!!.jsonObject + mapOf("id" to JsonPrimitive(routeId), "code" to JsonPrimitive("R-AMO-$r"), "name" to JsonPrimitive("রুট $r")))
            val day = JsonObject(route["day_state"]!!.jsonObject + ("route_id" to JsonPrimitive(routeId)))
            JsonObject(route + mapOf("route_id" to JsonPrimitive(routeId), "route" to inner, "outlets" to outlets, "day_state" to day))
        })
        val extra = Json.parseToJsonElement(
            """{
              "user": {"user_id": 1001, "username": "amo5012", "full_name": "করিম উদ্দিন", "role": "AMO", "locale": "bn", "bind_ordinal": 1, "memo_seq_block_size": 500},
              "prices": [], "code_lists": [], "offers": [], "calendar": {}, "templates": [], "tasks": []
            }""",
        ).jsonObject
        return JsonObject(base + extra + ("routes" to routes)).toString()
    }

    private fun api(body: String) = MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").addHeader("ETag", "\"2026-10-05:3\"").body(body).build()

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc() }
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test fun theAmoZoneDownloadsThroughTheStreamAndAppliesWholly() = runBlocking {
        val body = amoBundle()
        server.enqueue(api(body))
        val ok = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { null })
        val report = BundleDownloader(db, SyncApi(client), staging, Clock).download("2026-10-05")
        assertEquals(BundleOutcome.APPLIED, report.outcome)
        assertEquals(ROUTES * PER_ROUTE, db.referenceDao().outletCount())
        val routes = ReferenceRepository(db).routesOfDay("2026-10-05")
        assertEquals(ROUTES, routes.size)
        assertEquals("মেসার্স রহমান জেনারেল স্টোর 100000", routes.flatMap { it.outlets }.first { it.outletId == 100_000L }.nameBn)
    }

    private fun downloader(): BundleDownloader {
        val ok = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { null })
        return BundleDownloader(db, SyncApi(client), staging, Clock)
    }

    /** Checker: a 200 whose body is not a bundle object fails as malformed (never offline, so it is not retried forever). */
    @Test fun aBodyThatIsNotABundleFailsAsMalformed() = runBlocking {
        server.enqueue(api("[]"))
        server.enqueue(api("""{"meta":{}}"""))
        repeat(2) {
            val report = downloader().download("2026-10-05")
            assertEquals(BundleOutcome.FAILED, report.outcome)
            assertEquals("malformed", report.code)
        }
        assertEquals(0, db.referenceDao().outletCount())
    }

    /** Checker: a body cut off mid-stream is a transport failure; nothing of the half-read day is applied. */
    @Test fun aBodyCutOffMidStreamAppliesNothing() = runBlocking {
        server.enqueue(api(amoBundle()).newBuilder().throttleBody(64 * 1024, 0, TimeUnit.MILLISECONDS)
            .onResponseBody(mockwebserver3.SocketEffect.ShutdownConnection).build())
        val report = downloader().download("2026-10-05")
        assertEquals(BundleOutcome.OFFLINE, report.outcome)
        assertEquals(0, db.referenceDao().outletCount())
        assertNull(ReferenceRepository(db).businessDate())
    }

    /** The apply alone: inside 1.5 s, and the live set while the parsed day is held stays under the ceiling. */
    @Test fun theAmoApplyMeetsItsTimeBudgetAndHeapCeiling() = runBlocking {
        val repo = ReferenceRepository(db)
        // Warm the JIT and Room on a small day first, so the budget measures the apply, not class loading.
        val small = fixture()
        repo.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), small), small)

        val before = usedHeap()
        val raw = Json.parseToJsonElement(amoBundle()).jsonObject
        val bundle = BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw)
        val start = System.nanoTime()
        repo.apply(bundle, raw, "\"2026-10-05:4\"")
        val applyMs = (System.nanoTime() - start) / 1_000_000
        val live = usedHeap() - before
        assertEquals(ROUTES * PER_ROUTE, db.referenceDao().outletCount())
        assertTrue("apply took $applyMs ms (budget $APPLY_BUDGET_MS)", applyMs < APPLY_BUDGET_MS)
        assertTrue("live set ${live / 1_048_576} MB (ceiling ${HEAP_CEILING / 1_048_576} MB)", live < HEAP_CEILING)
        assertTrue(raw.isNotEmpty() && bundle.routes.size == ROUTES) // held until here on purpose
    }

    /** A delta of 1,200 tombstones and 1,200 upserts (more than two chunks of 500 each) applies exactly. */
    @Test fun aLargeDeltaIsAppliedInChunksExactly() = runBlocking {
        val repo = ReferenceRepository(db)
        val raw = Json.parseToJsonElement(amoBundle()).jsonObject
        repo.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw, "\"2026-10-05:3\"")
        val template = fixture()["routes"]!!.jsonArray.first().jsonObject["outlets"]!!.jsonArray.first().jsonObject
        val gone = (100_000L until 101_200L).map { JsonPrimitive(it.toString()) }
        val renamed = (101_200L until 102_400L).map { id ->
            JsonObject(outlet(template, id, 20_000L + (id - 100_000L) / PER_ROUTE) + ("name_bn" to JsonPrimitive("নতুন নাম $id")))
        }
        val delta = Json.parseToJsonElement(
            """{"meta":{"bundle_version":"2026-10-05:3","valid_for_business_date":"2026-10-05","base_cursor":"c1","cursor":"c2"},
               "sections":{"outlets":{"upsert":${JsonArray(renamed)},"delete":${JsonArray(gone)}}}}""",
        ).jsonObject
        assertEquals(BundleDeltaResult.APPLIED, repo.applyBundleDelta(delta))
        assertEquals(ROUTES * PER_ROUTE - 1_200, db.referenceDao().outletCount())
        assertNull(db.referenceDao().outlet(100_000L))
        assertNull(db.referenceDao().outlet(101_199L))
        assertEquals("নতুন নাম 101200", db.referenceDao().outlet(101_200L)!!.nameBn)
        assertEquals("নতুন নাম 102399", db.referenceDao().outlet(102_399L)!!.nameBn)
        assertEquals("মেসার্স রহমান জেনারেল স্টোর 102400", db.referenceDao().outlet(102_400L)!!.nameBn)
    }

    private companion object {
        const val USER = 5012L
        const val ROUTES = 54
        const val PER_ROUTE = 111
        const val APPLY_BUDGET_MS = 1_500L
        const val HEAP_CEILING = 128L * 1_048_576
    }
}
