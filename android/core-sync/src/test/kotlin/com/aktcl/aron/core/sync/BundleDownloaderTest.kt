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
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    private var badRowOnPage2 = false
    private var bundleBody: () -> String = { fullBundle() }
    private var deltaAnswer: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(304).addHeader("X-Aron-Api", "1").build() }

    private object Clock : WallClock {
        @Volatile var now = 1_791_165_600_000L
        override fun nowMs(): Long = now // 2026-10-05T02:00:00Z = 08:00 in Dhaka
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
        val rows = all.chunked(100)[n - 1].let { r -> if (badRowOnPage2 && n == 2) r.mapIndexed { i, e -> if (i == 0) JsonObject(e.jsonObject - "outlet_id") else e } else r }
        return buildJsonObject {
            put("bundle_version", JsonPrimitive("2026-10-05:3")); put("section", JsonPrimitive("outlets"))
            put("page", JsonPrimitive(n)); put("pages", JsonPrimitive(2)); put("rows", buildJsonArray { rows.forEach { add(it) } })
        }.toString()
    }

    private fun api(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("X-Aron-Api", "1").addHeader("ETag", "\"2026-10-05:3\"").body(body).build()

    @Before fun setUp() {
        Clock.now = 1_791_165_600_000L // the clock is one shared object: every test starts at the same instant
        tracking = true
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

    private var tracking = true // the SR app; the AMO and TSO apps do not track sent-but-unanswered rows

    private fun downloader(origin: String = server.url("/").toString().trimEnd('/'), labGrant: (String?, Long?) -> Unit = { _, _ -> }): BundleDownloader {
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(origin, allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f" })
        return BundleDownloader(db, SyncApi(client), staging, Clock, labGrant, userId = USER, trackUncertain = tracking)
    }

    /** Contract ACHIEVEMENT RULE, single flight: a sync run in flight when the day request is wanted finishes first, and the request instant is taken after it. */
    @Test fun aSyncRunInFlightFinishesBeforeTheBundleRequestAndTheRequestInstantFollowsIt() = runBlocking {
        Clock.now = 1_791_165_600_000L
        val lock = SyncEngine.userLock(USER)
        lock.lock() // the sync worker is uploading a batch
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { downloader().download() }
        Thread.sleep(400)
        assertTrue("no bundle request leaves while the upload runs", requests.isEmpty())
        Clock.now += 5_000L // the batch is acknowledged at this instant, then the run ends
        val ackedAt = SyncEngine.iso(Clock.now)
        Clock.now += 1L
        lock.unlock()
        job.join()
        assertEquals(1, requests.count { it.url.encodedPath == "/v1/sync/bundle" })
        val requestedAt = db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_REQUESTED_AT)!!
        assertTrue("the request instant ($requestedAt) is after the ack ($ackedAt)", requestedAt > ackedAt)
    }

    /** The other order: while the bundle head request is in flight no sync run can start, so no acknowledgement lands inside the request. */
    @Test fun noSyncRunCanStartWhileTheBundleRequestIsInFlight() = runBlocking {
        val inRequest = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        bundleBody = { inRequest.countDown(); release.await(10, TimeUnit.SECONDS); fullBundle() }
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { downloader().download() }
        assertTrue(inRequest.await(10, TimeUnit.SECONDS))
        assertTrue("the user's sync lock is held while the head request is out", SyncEngine.userLock(USER).isLocked)
        release.countDown()
        job.join()
        assertFalse("and released once the head answer is in", SyncEngine.userLock(USER).isLocked)
    }

    /**
     * Lost answer (ProgrammesSection ACHIEVEMENT RULE, ruling B1c): a memo sent in an upload whose answer never arrived is recorded as UNCERTAIN when the bundle is
     * requested (the server may hold it); when it gets its answer ONE full day bundle is asked for, at most once per gap, and the list is renewed by it.
     */
    @Test fun aMemoSentButUnansweredIsRecordedAtTheRequestAndEarnsOneRefetchWhenAnswered() = runBlocking {
        val sent = com.aktcl.aron.core.common.ClientIds.newUuid(); val fresh = com.aktcl.aron.core.common.ClientIds.newUuid()
        fun row(u: String, state: String) = com.aktcl.aron.core.database.entity.OutboxEntity(
            clientUuid = u, recordType = "memo", familyUuid = u, rank = 0, businessDate = "2026-10-05", payloadJson = "{}", payloadSha256 = "x", state = state, createdAt = "t",
        )
        db.outboxDao().insert(listOf(row(sent, "in_flight"), row(fresh, "pending"))) // one upload left in flight (answer lost), one never sent
        val d = downloader()
        assertEquals(BundleOutcome.APPLIED, d.download().outcome)
        assertEquals(sent, db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_UNCERTAIN)) // only the one that was SENT
        val bundles = { requests.count { it.url.encodedPath == "/v1/sync/bundle" } }
        assertNull("still unanswered: no refetch", d.refreshIfUncertainResolved()); assertEquals(1, bundles())
        db.outboxDao().applyAck(sent, "acked", null, 7L, SyncEngine.iso(Clock.now + 1_000L)) // the replay is answered later
        db.referenceDao().deleteMeta(ReferenceRepository.KEY_BUNDLE_ETAG)
        assertNotNull("answered: one full refetch", d.refreshIfUncertainResolved()); assertEquals(2, bundles())
        assertNull("the refetch renewed the list", db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_UNCERTAIN))
        assertNull("and nothing waits any more", d.refreshIfUncertainResolved()); assertEquals(2, bundles())
    }

    /** Rule B of the programme figures (no clock): the memos acknowledged BEFORE the request are recorded by uuid at the request; one acknowledged after it is not in the set. */
    @Test fun theMemosAcknowledgedBeforeTheRequestAreRecordedByUuidAndALaterAckIsNot() = runBlocking {
        val before = com.aktcl.aron.core.common.ClientIds.newUuid(); val after = com.aktcl.aron.core.common.ClientIds.newUuid(); val lastMonth = com.aktcl.aron.core.common.ClientIds.newUuid()
        fun row(u: String, date: String, state: String) = com.aktcl.aron.core.database.entity.OutboxEntity(
            clientUuid = u, recordType = "memo", familyUuid = u, rank = 0, businessDate = date, payloadJson = "{}", payloadSha256 = "x", state = state, createdAt = "t",
        )
        db.outboxDao().insert(listOf(row(before, "2026-10-03", "pending"), row(after, "2026-10-04", "pending"), row(lastMonth, "2026-06-30", "pending")))
        db.outboxDao().applyAck(before, "acked", null, 7L, SyncEngine.iso(Clock.now))
        db.outboxDao().applyAck(lastMonth, "acked", null, 8L, SyncEngine.iso(Clock.now))
        assertEquals(BundleOutcome.APPLIED, downloader().download().outcome)
        val set = ReferenceRepository.decodeAcked(db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_ACKED_BEFORE))
        assertEquals("memos inside the figure window (the quarter at most), acknowledged before the request", setOf(before), set)
        db.outboxDao().applyAck(after, "acked", null, 9L, SyncEngine.iso(Clock.now + 1_000L)) // acknowledged after R
        assertEquals(setOf(before), ReferenceRepository.decodeAcked(db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_ACKED_BEFORE)))
    }

    @Test fun redemptionsVoidsAndProgrammeRecordsAcknowledgedBeforeTheRequestAreInTheOrderToo() = runBlocking {
        val ids = (1..4).map { com.aktcl.aron.core.common.ClientIds.newUuid() }
        val types = listOf("redemption", "memo_void", "gift_choice", "visit")
        db.outboxDao().insert(ids.zip(types).map { (u, t) -> com.aktcl.aron.core.database.entity.OutboxEntity(clientUuid = u, recordType = t, familyUuid = u, rank = 0, businessDate = "2026-10-03", payloadJson = "{}", payloadSha256 = "x", state = "pending", createdAt = "t") })
        ids.forEach { db.outboxDao().applyAck(it, "acked", null, 7L, SyncEngine.iso(Clock.now)) }
        downloader().download()
        val set = ReferenceRepository.decodeAcked(db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_ACKED_BEFORE))!!
        assertEquals(ids.take(3).toSet(), set) // a visit is not read by any figure rule
    }

    @Test fun aMemoQuarantinedWhenTheRequestLeavesIsRecordedAsUnsureEvenIfItIsAcceptedLater() = runBlocking {
        val q = com.aktcl.aron.core.common.ClientIds.newUuid()
        db.outboxDao().insert(listOf(com.aktcl.aron.core.database.entity.OutboxEntity(clientUuid = q, recordType = "memo", familyUuid = q, rank = 0, businessDate = "2026-10-03", payloadJson = "{}", payloadSha256 = "x", state = "pending", createdAt = "t")))
        db.outboxDao().applyAck(q, "quarantined", "late_sync", null, SyncEngine.iso(Clock.now))
        downloader().download()
        assertEquals(q, db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_UNCERTAIN)) // never added by the phone, whenever the resolution is heard
    }

    @Test fun aRedemptionAcknowledgedLongBeforeIsStillInTheSetNoDateCutForRecordsThatAreNotMonthly() = runBlocking {
        val old = com.aktcl.aron.core.common.ClientIds.newUuid(); val oldMemo = com.aktcl.aron.core.common.ClientIds.newUuid()
        fun row(u: String, t: String, date: String) = com.aktcl.aron.core.database.entity.OutboxEntity(clientUuid = u, recordType = t, familyUuid = u, rank = 0, businessDate = date, payloadJson = "{}", payloadSha256 = "x", state = "pending", createdAt = "t")
        db.outboxDao().insert(listOf(row(old, "redemption", "2026-01-05"), row(oldMemo, "memo", "2026-01-05")))
        db.outboxDao().applyAck(old, "acked", null, 1L, SyncEngine.iso(Clock.now)); db.outboxDao().applyAck(oldMemo, "acked", null, 2L, SyncEngine.iso(Clock.now))
        downloader().download()
        val set = ReferenceRepository.decodeAcked(db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_ACKED_BEFORE))!!
        assertEquals(setOf(old), set) // the redemption (balance) has no date cut; the January memo is outside every figure window
    }

    @Test fun nothingAcknowledgedYetIsAnEmptySetNotAnUnknownOrder() = runBlocking {
        downloader().download()
        assertEquals(emptySet<String>(), ReferenceRepository.decodeAcked(db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_ACKED_BEFORE)))
        assertNull(ReferenceRepository.decodeAcked(null)); assertNull(ReferenceRepository.decodeAcked("garbage"))
    }

    @Test fun anAppThatDoesNotTrackRecordsNoOrder() = runBlocking {
        tracking = false
        downloader().download()
        assertNull(db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_ACKED_BEFORE))
    }

    /** The AMO and TSO apps never pay a second full download of their large bundle for this: nothing is recorded and nothing is refetched. */
    @Test fun anAppThatDoesNotTrackRecordsNothingAndNeverRefetches() = runBlocking {
        tracking = false
        val sent = com.aktcl.aron.core.common.ClientIds.newUuid()
        db.outboxDao().insert(listOf(com.aktcl.aron.core.database.entity.OutboxEntity(clientUuid = sent, recordType = "memo", familyUuid = sent, rank = 0, businessDate = "2026-10-05", payloadJson = "{}", payloadSha256 = "x", state = "in_flight", createdAt = "t")))
        val d = downloader(); d.download()
        assertNull(db.referenceDao().meta(ReferenceRepository.KEY_BUNDLE_UNCERTAIN))
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(ReferenceRepository.KEY_BUNDLE_UNCERTAIN, sent)); db.outboxDao().applyAck(sent, "acked", null, 7L, SyncEngine.iso(Clock.now))
        assertNull(d.refreshIfUncertainResolved())
    }

    /** Only the held bundle of TODAY is refetched (as refreshDelta); on a later Dhaka day the day start brings its own bundle. */
    @Test fun theRefetchOnlyAppliesToTheHeldBundleOfToday() = runBlocking {
        val sent = com.aktcl.aron.core.common.ClientIds.newUuid()
        db.outboxDao().insert(listOf(com.aktcl.aron.core.database.entity.OutboxEntity(clientUuid = sent, recordType = "memo", familyUuid = sent, rank = 0, businessDate = "2026-10-05", payloadJson = "{}", payloadSha256 = "x", state = "in_flight", createdAt = "t")))
        val d = downloader(); d.download()
        db.outboxDao().applyAck(sent, "acked", null, 7L, SyncEngine.iso(Clock.now))
        Clock.now += 24L * 3600 * 1000 // the next Dhaka day: the held bundle is yesterday's
        assertNull(d.refreshIfUncertainResolved())
    }

    @Test fun theRefetchAfterAnAnswerIsRateLimitedSoAFlappingLinkCannotLoop() = runBlocking {
        val sent = com.aktcl.aron.core.common.ClientIds.newUuid()
        db.outboxDao().insert(listOf(com.aktcl.aron.core.database.entity.OutboxEntity(clientUuid = sent, recordType = "memo", familyUuid = sent, rank = 0, businessDate = "2026-10-05", payloadJson = "{}", payloadSha256 = "x", state = "in_flight", createdAt = "t")))
        val d = downloader(); d.download()
        db.outboxDao().applyAck(sent, "acked", null, 7L, SyncEngine.iso(Clock.now))
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(ReferenceRepository.KEY_BUNDLE_UNCERTAIN, sent)) // the list is still held
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(BundleDownloader.KEY_UNCERTAIN_REFETCH_AT, Clock.now.toString())) // a refetch just ran
        assertNull(d.refreshIfUncertainResolved())
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

    /** D-AMO-BUNDLE-01 / D-TSO-BUNDLE-01: the supervisor apps' day start: the first bundle in full, then never a second full one that day. */
    @Test fun ensureDayDownloadsTheFirstBundleAndOnlyAsksForADeltaAfterwards() = runBlocking {
        val d = downloader()
        assertEquals(BundleOutcome.APPLIED, d.ensureDay("2026-10-05").outcome)
        assertEquals(1, requests.count { it.url.encodedPath == "/v1/sync/bundle" })
        val afterFirst = requests.size
        d.ensureDay("2026-10-05")
        val afterSecond = requests.size
        d.ensureDay("2026-10-05") // inside the 30-minute gate: nothing at all goes out
        assertEquals(afterSecond, requests.size)
        // the second call is the resume path: a delta at most (the answer to a missing cursor is the held day's own refetch), never a new login download
        assertTrue("the second call sent ${afterSecond - afterFirst} requests", afterSecond - afterFirst <= 2)
    }

    /** A TSO wipe closes the user's database; the next sign-in must get a downloader on the NEW database, not the closed one. */
    @Test fun aDownloaderIsRebuiltWhenTheUsersDatabaseWasClosedAndReopened() = runBlocking {
        val databases = com.aktcl.aron.core.database.UserDatabases(context) { null }
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { null })
        val downloaders = BundleDownloaders(staging, databases, SyncApi(client), Clock)
        val first = downloaders.of(4242L)
        assertSame("same database: the same downloader", first, downloaders.of(4242L))
        databases.close(4242L)
        context.deleteDatabase(AronDatabase.fileName(4242L))
        databases.allowOpen(4242L)
        val second = downloaders.of(4242L)
        assertNotSame(first, second)
        assertTrue(second.uses(databases.of(4242L)))
        databases.close(4242L)
        context.deleteDatabase(AronDatabase.fileName(4242L))
        Unit
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

    @Test fun aStagedPageWithAnUnreadableRowAppliesNothingAndKeepsTheHeldSnapshot() = runBlocking {
        downloader().download() // today's snapshot, 200 outlets, inline
        assertEquals(200, db.referenceDao().outletCount())
        paged = true
        badRowOnPage2 = true
        bundleBody = { fullBundle().replace("2026-10-05:3", "2026-10-05:4") }
        db.referenceDao().deleteMeta(ReferenceRepository.KEY_BUNDLE_ETAG) // an unconditional request: no 304
        val r = downloader().download()
        assertEquals(BundleOutcome.FAILED, r.outcome)
        assertEquals("malformed_page", r.code)
        assertEquals(200, db.referenceDao().outletCount()) // the whole previous snapshot, no half of the new one
        assertEquals("2026-10-05:3", ReferenceRepository(db).bundleVersion())
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

    // ---- D-OWNER-MDM-02 iii: the lab grant in a full day bundle (device-scoped `cfg.integrity.lab_exemption`)

    private fun withLabGrant(token: String?, scope: String = "device"): String {
        val b = Json.parseToJsonElement(fullBundle()).jsonObject
        val cfg = b["config"]!!.jsonObject
        val values = JsonArray(cfg["values"]!!.jsonArray + (if (token == null) emptyList() else listOf(Json.parseToJsonElement(
            """{"key":"cfg.integrity.lab_exemption","value":"$token","scope_type":"$scope","scope_id":7,"effective_from":null,"requires_ack":false}"""))))
        return JsonObject(b + ("config" to JsonObject(cfg + ("values" to values)))).toString()
    }

    @Test fun aDayBundleThatCarriesTheGrantHandsItToTheGateAndOneWithoutItWithdraws() = runBlocking {
        val seen = ArrayList<String?>(); val times = ArrayList<Long?>()
        bundleBody = { withLabGrant("tok.sig") }
        assertEquals(BundleOutcome.APPLIED, downloader(labGrant = { t, st -> seen += t; times += st }).download().outcome)
        assertEquals(listOf<String?>("tok.sig"), seen)
        assertEquals("the response's own server time travels with the grant (it is what the phone judges freshness and expiry against)", listOf<Long?>(java.time.Instant.parse("2026-10-05T02:12:44.120Z").toEpochMilli()), times)
        bundleBody = { withLabGrant(null).replace("2026-10-05:3", "2026-10-05:4") }
        db.referenceDao().deleteMeta(ReferenceRepository.KEY_BUNDLE_ETAG)
        assertEquals(BundleOutcome.APPLIED, downloader(labGrant = { t, st -> seen += t; times += st }).download().outcome)
        assertEquals("a full bundle without the value is the server withdrawing it", listOf<String?>("tok.sig", null), seen)
    }

    @Test fun onlyTheDeviceScopedValueCountsAndOnlyAnAppliedDayBundleSpeaks() = runBlocking {
        val seen = ArrayList<String?>(); val times = ArrayList<Long?>()
        bundleBody = { withLabGrant("tok.sig", scope = "global") }
        downloader(labGrant = { t, st -> seen += t; times += st }).download()
        assertEquals("a value of another scope is not a grant", listOf<String?>(null), seen)
        seen.clear()
        // offline, an older snapshot, a failed or unreadable bundle and a prefetch never grant and never revoke
        bundleBody = { withLabGrant("tok.sig").replace("2026-10-05:3", "2026-10-05:2") }
        db.referenceDao().deleteMeta(ReferenceRepository.KEY_BUNDLE_ETAG)
        assertEquals(BundleOutcome.OLDER_IGNORED, downloader(labGrant = { t, st -> seen += t; times += st }).download().outcome)
        bundleBody = { """{"meta": {"bundle_version": "2026-10-05:9"}}""" }
        db.referenceDao().deleteMeta(ReferenceRepository.KEY_BUNDLE_ETAG)
        assertEquals(BundleOutcome.FAILED, downloader(labGrant = { t, st -> seen += t; times += st }).download().outcome)
        bundleBody = {
            val b = Json.parseToJsonElement(withLabGrant("tok.sig")).jsonObject
            val meta = JsonObject(b["meta"]!!.jsonObject + mapOf("bundle_version" to JsonPrimitive("2026-10-06:1"), "valid_for_business_date" to JsonPrimitive("2026-10-06"), "is_prefetch" to JsonPrimitive(true)))
            JsonObject(b + ("meta" to meta) + ("routes" to JsonArray(b["routes"]!!.jsonArray.take(1)))).toString()
        }
        assertEquals(BundleOutcome.PREFETCH_STORED, downloader(labGrant = { t, st -> seen += t; times += st }).download("2026-10-06").outcome)
        server.close()
        assertEquals(BundleOutcome.OFFLINE, downloader(labGrant = { t, st -> seen += t; times += st }).download().outcome)
        assertEquals(emptyList<String?>(), seen)
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
        // The resolution was stashed with the cursor and drained at once (no such row on this phone: nothing left over).
        assertNull(db.referenceDao().meta(SyncEngine.RESOLUTION_PREFIX + "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"))
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

    /** Checker round 1 (F-SYS-007, defect 3): a delta the phone cannot read falls back to the full day once, not after every sync. */
    @Test
    fun aMalformedDeltaFallsBackInsteadOfRefetchingAfterEverySync() = runBlocking {
        downloader().download()
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(SyncEngine.KEY_BUNDLE_CURRENT, "2026-10-05:4"))
        deltaAnswer = { api(deltaBody().replace("\"prices\": {", "\"outlets\": {\"upsert\": [{\"outlet_id\": 50000}], \"delete\": []}, \"prices\": {")) }
        requests.clear()
        repeat(3) { downloader().refreshIfServerNewer() }
        assertEquals(1, requests.count { it.url.encodedPath == "/v1/sync/delta" })
        val full = requests.filter { it.url.encodedPath == "/v1/sync/bundle" }
        assertEquals(1, full.size)
        assertNull("the fallback is unconditional", full.single().headers["If-None-Match"])
    }
    /** Checker (3926bd6f re-check, finding 1): a delta that got no answer (503, offline) does not spend the server version. */
    @Test
    fun anUnansweredDeltaIsTriedAgainAfterTheNextSyncRun() = runBlocking {
        downloader().download()
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(SyncEngine.KEY_BUNDLE_CURRENT, "2026-10-05:4"))
        deltaAnswer = { problem(503, "ERR_BUNDLE_NOT_READY") }
        requests.clear()
        assertEquals(BundleOutcome.NOT_READY, downloader().refreshIfServerNewer()!!.outcome)
        deltaAnswer = { api(deltaBody()) }
        assertEquals(BundleOutcome.APPLIED, downloader().refreshIfServerNewer()!!.outcome)
        assertEquals(2, requests.count { it.url.encodedPath == "/v1/sync/delta" })
        assertEquals("2026-10-05:4", ReferenceRepository(db).bundleVersion())
        assertNull(downloader().refreshIfServerNewer()) // now held: nothing newer
    }

    /** Checker (wiring round 1, finding 4): a refused delta (400) spends the version; no full bundle after every sync. */
    @Test
    fun aRefusedDeltaIsNotRetriedAfterEverySyncRun() = runBlocking {
        downloader().download()
        db.referenceDao().putMeta(com.aktcl.aron.core.database.entity.SyncMetaEntity(SyncEngine.KEY_BUNDLE_CURRENT, "2026-10-05:4"))
        deltaAnswer = { problem(400, "ERR_VALIDATION") }
        requests.clear()
        assertEquals(BundleOutcome.FAILED, downloader().refreshIfServerNewer()!!.outcome)
        assertNull(downloader().refreshIfServerNewer())
        assertEquals(1, requests.size)
    }

    @Test
    fun onlyAnsweredOutcomesSpendTheVersion() {
        fun r(o: BundleOutcome, status: Int? = null) = BundleDownloader.spendsVersion(BundleReport(o, httpStatus = status))
        assertEquals(listOf(false, false, false, false, false, true, true, true), listOf(
            r(BundleOutcome.OFFLINE), r(BundleOutcome.NOT_READY, 503), r(BundleOutcome.AUTH_REQUIRED, 401),
            r(BundleOutcome.FAILED, 500), r(BundleOutcome.FAILED, 429), r(BundleOutcome.FAILED, 400), r(BundleOutcome.FAILED), r(BundleOutcome.UNCHANGED),
        ))
    }

    // ---- SR manual 08 (SRM-A-11): the real step counter "Processing(n/total)" ----

    @Test fun anInlineBundleReportsTwoStepsThenDone() = runBlocking {
        val seen = ArrayList<Pair<Int, Int>>()
        assertEquals(BundleOutcome.APPLIED, downloader().download(progress = BundleProgress { d, t -> seen += d to t }).outcome)
        assertEquals(listOf(1 to 2, 2 to 2), seen)
    }

    @Test fun aPagedBundleCountsTheHeadEachPageAndTheApplyAndNeverGoesBackwards() = runBlocking {
        paged = true
        val seen = ArrayList<Pair<Int, Int>>()
        assertEquals(BundleOutcome.APPLIED, downloader().download(progress = BundleProgress { d, t -> seen += d to t }).outcome)
        assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4), seen)
        assertEquals(200, db.referenceDao().outletCount())
    }

    @Test fun aNotModifiedAnswerReportsNoStepsAndAFailedDownloadNeverReportsDone() = runBlocking {
        downloader().download()
        val none = ArrayList<Pair<Int, Int>>()
        assertEquals(BundleOutcome.UNCHANGED, downloader().download(progress = BundleProgress { d, t -> none += d to t }).outcome)
        assertTrue(none.isEmpty())
        paged = true; dropPage2 = 1
        db.referenceDao().deleteMeta(ReferenceRepository.KEY_BUNDLE_ETAG)
        val partial = ArrayList<Pair<Int, Int>>()
        assertEquals(BundleOutcome.OFFLINE, downloader().download(progress = BundleProgress { d, t -> partial += d to t }).outcome)
        assertTrue(partial.none { it.first == it.second })
    }

    @Test fun aThrowingProgressCallbackNeverAbortsTheDownloadOrTheApply() = runBlocking {
        paged = true
        var calls = 0
        val r = downloader().download(progress = BundleProgress { _, _ -> calls++; error("a broken screen") })
        assertEquals(BundleOutcome.APPLIED, r.outcome)
        assertEquals(200, db.referenceDao().outletCount())
        assertEquals(4, calls) // every step was still reported to it, and the staged pages were applied once
    }

    @Test fun theBarIsCompleteOnlyWhenTheSnapshotWasApplied() = runBlocking {
        downloader().download() // version 3 held
        paged = true
        bundleBody = { fullBundle().replace("2026-10-05:3", "2026-10-05:2") } // a server snapshot OLDER than the held one
        db.referenceDao().deleteMeta(ReferenceRepository.KEY_BUNDLE_ETAG)
        val seen = ArrayList<Pair<Int, Int>>()
        val r = downloader().download(progress = BundleProgress { d, t -> seen += d to t })
        assertTrue(r.outcome != BundleOutcome.APPLIED)
        assertTrue("never complete without an apply: $seen", seen.none { it.first == it.second })
    }

    /** The AMO and TSO apps keep their own timing: a long upload run (the user's sync lock held) must not delay their bundle head request; the SR app (tracking) does wait (single flight). */
    @Test fun aLongSyncRunNeverBlocksTheBundleRequestOfAnAppThatDoesNotTrackTheOrder() = runBlocking {
        tracking = false
        val lock = SyncEngine.userLock(USER)
        lock.lock() // a long upload run holds the user's lock
        try {
            val r = kotlinx.coroutines.withTimeoutOrNull(5_000) { downloader().download() }
            assertNotNull("the AMO/TSO bundle fetch finished while the lock was held", r)
            assertEquals(BundleOutcome.APPLIED, r!!.outcome)
            assertTrue(lock.isLocked)
        } finally { lock.unlock() }
    }
}
