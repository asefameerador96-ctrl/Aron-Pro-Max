package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.sync.DayStates
import com.aktcl.aron.backend.sync.RouteDayPlanningJob
import com.aktcl.aron.backend.sync.Uploader
import com.aktcl.aron.contract.Role
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent checker for F-SYS-056 (route-day planning job) and F-SYS-016 (day state machine). Each test uses its
 * own business date so the tests do not share route-days.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DayCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val seq = AtomicInteger(0)
    private var r1 = 0L
    private var r2 = 0L
    private var r3 = 0L
    private var outlet1 = 0L
    private var srId = 0L
    private lateinit var job: RouteDayPlanningJob
    private lateinit var planner: SqlRoutePlanner

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            srId = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            val routes = h.createQuery("SELECT DISTINCT route_id FROM app.route_assignment WHERE user_id = :u ORDER BY route_id").bind("u", srId).mapTo(Long::class.java).list()
            r1 = routes[0]; r2 = routes[1]; r3 = routes[2]
            outlet1 = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = :r").bind("r", r1).mapTo(Long::class.java).one()
            // r3: the admin scheduled the end of the SR's assignment for 2027-02-01 (POST /admin/route-assignments/{id}/end
            // writes valid_to = the end date and ended_at = now). Until then the SR still holds the route.
            h.execute("UPDATE app.route_assignment SET valid_to = DATE '2027-02-01', ended_at = TIMESTAMPTZ '2027-01-02T10:00:00Z' WHERE route_id = $r3 AND user_id = $srId")
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        job = RouteDayPlanningJob(fresh.db, config, clock)
        planner = SqlRoutePlanner(fresh.db, GeoRepository(fresh.db, clock), config)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun state(route: Long, day: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h ->
        h.createQuery("SELECT state FROM app.route_day WHERE route_id = :r AND business_date = CAST(:d AS date)").bind("r", route).bind("d", day).mapTo(String::class.java).findOne().orElse(null)
    }
    private fun at(day: String) = now.set(Instant.parse("${day}T04:00:00Z"))

    private suspend fun HttpClient.login(): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private fun env(day: String, type: String, route: Long?, payload: JsonObject) = buildJsonObject {
        val n = seq.incrementAndGet() % 3600
        put("type", type); val cu = uuid(); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
        put("business_date", day); put("captured_at", "${day}T03:%02d:%02d.000Z".format(n / 60, n % 60)); put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1); put("payload", payload)
    }

    private fun dayOpen(day: String, vararg routes: Long) = env(day, "day_open", routes.first(), buildJsonObject {
        put("route_ids", JsonArray(routes.map { JsonPrimitive(it) })); put("online", false); put("bundle_valid_for", day); put("offline_start", true)
    })

    private fun outletOf(route: Long) = count("SELECT min(id) FROM app.outlet WHERE route_id = $route")

    private fun visit(day: String, route: Long, outlet: Long) = env(day, "visit", route, Json.parseToJsonElement(
        """
        {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${day}T03:41:00.120Z","sequence_no":${seq.get()},"planned":true,
         "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
         "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
        """.trimIndent(),
    ).jsonObject)

    private fun daySubmit(day: String, route: Long, counts: Map<String, Long>, scope: String = "route_day", cycle: Int = 1) = env(day, "day_submit", route, buildJsonObject {
        put("scope", scope); put("submit_cycle", cycle)
        put("device_counts", buildJsonObject { counts.forEach { (k, v) -> put(k, v) } })
        put("device_money", buildJsonObject {
            for (k in listOf("active_memo_count", "gross_mtk", "offer_discount_mtk", "drp_discount_mtk", "qc_deduction_mtk", "net_mtk", "paid_mtk", "due_mtk", "due_collected_mtk")) put(k, 0)
            put("net_by_category_mtk", buildJsonObject {}); put("issued_qty_base_by_sku", buildJsonObject {}); put("sold_qty_base_by_sku", buildJsonObject {})
        })
        put("rejected_count", 0); put("quarantined_count", 0); put("pending_count", 0); put("submitted_with_dues", false)
        put("dues_outstanding_mtk", 0); put("retailers_with_dues", 0); put("stock_slip_printed", false)
    })

    /** Sends a batch; returns the acks' statuses in order. */
    private suspend fun HttpClient.send(token: String, day: String, records: List<JsonObject>): List<String> {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "${day}T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val r = post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content + ":" + (it.jsonObject["code"]?.jsonPrimitive?.content ?: "") }
    }

    private fun accepted(acks: List<String>) = acks.forEach { assertTrue(it.startsWith("accepted") || it.startsWith("duplicate"), "ack $acks") }

    // ---------------------------------------------------------------- F-SYS-056

    @Test
    fun jobStartsAt0005DhakaNotAtUtcMidnight() {
        // 2027-01-09 23:59 Dhaka = 17:59Z: the job plans the Dhaka date 2027-01-09, never the UTC or next date.
        now.set(Instant.parse("2027-01-09T17:59:00Z"))
        job.tick()
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '2027-01-10'"))
        assertTrue(count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '2027-01-09'") > 0)
        now.set(Instant.parse("2027-01-09T18:04:59Z")) // 00:04:59 Dhaka on 01-10
        assertEquals(0, job.tick())
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '2027-01-10'"))
        now.set(Instant.parse("2027-01-09T18:05:00Z"))
        assertTrue(job.tick() > 0)
        assertTrue(count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '2027-01-10'") >= 2)
    }

    @Test
    fun aScheduledEndStillHoldsTheRouteSoTheJobMustPlanIt() {
        // Monday 2027-01-04: r3 (2F Mon/Thu) is still held until 2027-02-01 and the bundle planner serves it.
        val d = LocalDate.parse("2027-01-04")
        assertTrue(planner.routesFor(srId, d).any { it.routeId == r3 }, "the bundle serves r3 to the SR on $d")
        job.planDay(d)
        assertNotNull(state(r3, "2027-01-04"), "a route-day for every route held on the date (r3's assignment ends 2027-02-01)")
    }

    @Test
    fun aScheduledEndStillHoldsTheRouteSoIngestMustMoveItsDay() = testApplication {
        application { aronApi(wiring) }
        val day = "2027-01-07" // Thursday, r3 planned
        at(day)
        val token = client.login()
        accepted(client.send(token, day, listOf(dayOpen(day, r3), visit(day, r3, outletOf(r3)))))
        assertEquals("synced", state(r3, day), "the SR's offline start and visit on a route held until 2027-02-01 move its day")
    }

    @Test
    fun theJobLeavesTheTargetAndTheFirstBundleFreezesIt() = testApplication {
        application { aronApi(wiring) }
        val day = "2027-01-14"
        at(day)
        job.planDay(LocalDate.parse(day))
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$day' AND target_outlets IS NOT NULL"))
        val plannedBefore = count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$day' AND route_id IN ($r1,$r2) AND planned")
        val token = client.login()
        val r = client.get("/v1/sync/bundle") { bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$day' AND route_id IN ($r1,$r2) AND target_outlets IS NULL"), "frozen by the first bundle")
        assertEquals("logged_in", state(r1, day))
        assertEquals(plannedBefore, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$day' AND route_id IN ($r1,$r2) AND planned"))
        val target = count("SELECT target_outlets FROM app.route_day WHERE business_date = DATE '$day' AND route_id = $r1")
        // A new outlet and a second bundle (and the job again) never move the frozen target.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.outlet SET status = 'closed' WHERE id = $outlet1") }
        try {
            client.get("/v1/sync/bundle") { bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9") }
            job.planDay(LocalDate.parse(day))
            assertEquals(target, count("SELECT target_outlets FROM app.route_day WHERE business_date = DATE '$day' AND route_id = $r1"))
        } finally { fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.outlet SET status = 'active' WHERE id = $outlet1") } }
    }

    // ---------------------------------------------------------------- F-SYS-016

    @Test
    fun aSubmitBeforeItsVisitsWaitsThenSettlesAndAResendOrLateRowNeverMovesIt() = testApplication {
        application { aronApi(wiring) }
        val day = "2027-01-13"
        at(day)
        val token = client.login()
        accepted(client.send(token, day, listOf(dayOpen(day, r1))))
        val submit = daySubmit(day, r1, mapOf("visit" to 1, "day_open" to 1, "day_submit" to 1))
        accepted(client.send(token, day, listOf(submit)))
        assertEquals("submit_pending_rows", state(r1, day))
        accepted(client.send(token, day, listOf(visit(day, r1, outlet1))))
        assertEquals("sales_submitted", state(r1, day))
        assertEquals(1, count("SELECT count(*) FROM app.route_day WHERE route_id = $r1 AND business_date = DATE '$day' AND submit_count_mismatch = false"))
        accepted(client.send(token, day, listOf(submit, visit(day, r1, outlet1), dayOpen(day, r1))))
        assertEquals("sales_submitted", state(r1, day))
        assertEquals("logged_in", state(r2, day) ?: "logged_in", "r2 is not moved by r1's rows")
    }

    @Test
    fun aParkedRowKeepsTheRouteDayOutOfSynced() = testApplication {
        application { aronApi(wiring) }
        val day = "2027-01-11"
        at(day)
        val token = client.login()
        val orphan = env(day, "distribution_check", r1, buildJsonObject { put("visit_client_uuid", uuid()) })
        val acks = client.send(token, day, listOf(dayOpen(day, r1), visit(day, r1, outlet1), orphan))
        assertTrue(acks[2].startsWith("rejected"), "$acks")
        assertEquals("in_field", state(r1, day))
    }

    @Test
    fun aDayOpenNamingAnotherUsersRouteMovesNothingThere() = testApplication {
        application { aronApi(wiring) }
        val day = "2027-01-12"
        at(day)
        val foreign = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            val zone = h.createQuery("SELECT zone_id FROM app.route WHERE id = $r1").mapTo(Long::class.java).one()
            val r = h.createQuery("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'CHK-FOREIGN', 'Foreign', 'F', $zone, kind, 'daily', 127, 9 FROM app.route WHERE id = $r1 RETURNING id").mapTo(Long::class.java).one()
            val other = h.createQuery("SELECT id FROM app.app_user WHERE username = 'tso1001'").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) VALUES ($r, $other, 'primary', DATE '2026-01-01', 'checker')")
            r
        }
        val token = client.login()
        client.send(token, day, listOf(dayOpen(day, r1, foreign)))
        assertEquals("logged_in", state(r1, day))
        val s = state(foreign, day)
        assertTrue(s == null || s == "not_started", "the foreign route-day is not moved ($s)")
    }

    @Test
    fun aContractValidDaySubmitIsNotRejectedByTheDayStateHook() = testApplication {
        application { aronApi(wiring) }
        val day = "2027-01-06"
        at(day)
        val token = client.login()
        accepted(client.send(token, day, listOf(dayOpen(day, r1), visit(day, r1, outlet1))))
        // TypeCounts: integer >= 0 per type, no maximum. Their sum overflows Int in DayStates (rows_awaited CHECK >= 0).
        val acks = client.send(token, day, listOf(daySubmit(day, r1, mapOf("visit" to 2_000_000_000L, "memo" to 2_000_000_000L))))
        assertTrue(acks[0].startsWith("accepted"), "the day_submit is stored, not refused because of the day-state hook: $acks")
        assertEquals("submit_pending_rows", state(r1, day))
    }

    @Test
    fun aDeviceCountAboveIntIsNotReadAsZero() = testApplication {
        application { aronApi(wiring) }
        val day = "2027-01-08"
        at(day)
        val token = client.login()
        accepted(client.send(token, day, listOf(dayOpen(day, r1), visit(day, r1, outlet1))))
        accepted(client.send(token, day, listOf(daySubmit(day, r1, mapOf("visit" to 3_000_000_000L)))))
        assertEquals("submit_pending_rows", state(r1, day), "server holds 1 visit, the device counted 3e9: rows are awaited")
    }

    @Test
    fun aCoverUsersCheckInStartsTheCoveredRouteDay() {
        // An SR covering r2 (whose primary is sr1001) on 2027-01-17, offline: the job made the route-day with the primary.
        val day = LocalDate.parse("2027-01-17")
        now.set(Instant.parse("2027-01-17T04:00:00Z"))
        val cover = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            val zone = h.createQuery("SELECT home_zone_id FROM app.app_user WHERE id = $srId").mapTo(Long::class.java).one()
            val u = h.createQuery("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) VALUES ('srcover1', 'Cover SR', 'SR', 'Sales Representative', 'T-SR-CHK', 'bn', $zone, true, false) RETURNING id").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, valid_to, reason) VALUES ($r2, $u, 'cover', DATE '2027-01-17', DATE '2027-01-18', 'checker')")
            u
        }
        job.planDay(day)
        val up = Uploader(cover, Role.SR, 1, 0, devPhone, null)
        fresh.db.jdbi.useTransaction<Exception> { h ->
            val touched = DayStates.Touched()
            val rec = env("2027-01-17", "attendance_event", null, buildJsonObject { put("kind", "check_in") })
            DayStates.afterStored(h, "attendance_event", rec, rec["payload"]!!.jsonObject, up, now.get(), job, touched)
            DayStates.afterBatch(h, cover, touched, now.get())
        }
        // In the field, and the batch left nothing parked: synced (s4.9), never still not_started.
        assertTrue(state(r2, "2027-01-17") in setOf("in_field", "synced"), "the acting cover's check-in starts the route-day it holds")
    }
}
