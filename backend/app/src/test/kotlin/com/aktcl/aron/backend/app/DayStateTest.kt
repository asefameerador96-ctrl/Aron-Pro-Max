package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-SYS-016 through POST /v1/sync/batch on the seed: route_day moves not_started, logged_in, in_field, synced,
 * sales_submitted from events (never back), an SR on two routes has two route-days, a submit whose rows are missing
 * waits and then settles with a mismatch, and an AMO submit writes supervisor_day without touching an SR route.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DayStateTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val day = "2027-01-03"
    private val seq = AtomicInteger(0)
    private var r1 = 0L
    private var r2 = 0L
    private var outlet1 = 0L
    private var srId = 0L

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
            r1 = routes[0]; r2 = routes[1]
            outlet1 = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = :r").bind("r", r1).mapTo(Long::class.java).one()
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun state(route: Long): String = fresh.db.jdbi.withHandle<String, Exception> { h ->
        h.createQuery("SELECT state FROM app.route_day WHERE route_id = :r AND business_date = DATE '$day'").bind("r", route).mapTo(String::class.java).one()
    }

    private suspend fun HttpClient.login(): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private fun env(type: String, route: Long?, payload: JsonObject) = buildJsonObject {
        val n = seq.incrementAndGet()
        put("type", type); val cu = uuid(); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:%02d:%02d.000Z".format(n / 60, n % 60)); put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1); put("payload", payload)
    }

    private fun dayOpen(vararg routes: Long) = env("day_open", routes.first(), buildJsonObject {
        put("route_ids", JsonArray(routes.map { JsonPrimitive(it) })); put("online", false); put("bundle_valid_for", day); put("offline_start", true)
    })

    private fun visit(route: Long, outlet: Long) = env("visit", route, Json.parseToJsonElement(
        """
        {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":${seq.get()},"planned":true,
         "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
         "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
        """.trimIndent(),
    ).jsonObject)

    private fun daySubmit(route: Long, counts: Map<String, Int>, scope: String = "route_day") = env("day_submit", route, buildJsonObject {
        put("scope", scope); put("submit_cycle", 1)
        put("device_counts", buildJsonObject { counts.forEach { (k, v) -> put(k, v) } })
        put("device_money", buildJsonObject {
            for (k in listOf("active_memo_count", "gross_mtk", "offer_discount_mtk", "drp_discount_mtk", "qc_deduction_mtk", "net_mtk", "paid_mtk", "due_mtk", "due_collected_mtk")) put(k, 0)
            put("net_by_category_mtk", buildJsonObject {}); put("issued_qty_base_by_sku", buildJsonObject {}); put("sold_qty_base_by_sku", buildJsonObject {})
        })
        put("rejected_count", 0); put("quarantined_count", 0); put("pending_count", 0); put("submitted_with_dues", false)
        put("dues_outstanding_mtk", 0); put("retailers_with_dues", 0); put("stock_slip_printed", false)
    })

    private suspend fun HttpClient.send(token: String, records: List<JsonObject>): JsonObject {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val r = post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        b["acks"]!!.jsonArray.forEach { a -> assertEquals("accepted", a.jsonObject["status"]!!.jsonPrimitive.content, a.toString()) }
        return b
    }

    @Test
    fun aRouteDayMovesForwardFromEventsAndTwoRoutesAreTwoRouteDays() = testApplication {
        application { aronApi(wiring) }
        val token = client.login()
        // An offline start on a cached bundle opens both routes: two route-days, logged in.
        client.send(token, listOf(dayOpen(r1, r2)))
        assertEquals(2, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$day' AND route_id IN ($r1, $r2) AND logged_in_at IS NOT NULL"))
        assertEquals("logged_in", state(r1)); assertEquals("logged_in", state(r2))
        // The first visit puts r1 in the field; the batch arrived with nothing parked: synced. r2 does not move.
        val b = client.send(token, listOf(visit(r1, outlet1)))
        assertEquals("synced", state(r1)); assertEquals("logged_in", state(r2))
        val states = b["day_states"]!!.jsonArray.map { it.jsonObject }.associateBy { it["route_id"]!!.jsonPrimitive.content.toLong() }
        assertEquals("synced", states.getValue(r1)["state"]!!.jsonPrimitive.content, "the batch response carries the new state")
        // Sales Submit with the device counts the server holds: sales_submitted at once.
        client.send(token, listOf(daySubmit(r1, mapOf("visit" to 1, "day_open" to 1))))
        assertEquals("sales_submitted", state(r1))
        assertEquals(1, count("SELECT count(*) FROM app.route_day WHERE route_id = $r1 AND business_date = DATE '$day' AND submit_count_mismatch = false"))
        // A late visit and a late day_open never move it back.
        client.send(token, listOf(visit(r1, outlet1), dayOpen(r1)))
        assertEquals("sales_submitted", state(r1))

        // r2: a submit whose rows are missing waits, then settles at the timeout with a mismatch.
        client.send(token, listOf(visit(r2, fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = $r2").mapTo(Long::class.java).one() })))
        client.send(token, listOf(daySubmit(r2, mapOf("visit" to 99))))
        assertEquals("submit_pending_rows", state(r2))
        val job = RouteDayPlanningJob(fresh.db, DbServerConfig(fresh.db, RegistryDefaults(), clock), clock)
        assertEquals(0, job.settleExpired(), "not before the 30-minute deadline")
        now.set(now.get().plus(Duration.ofMinutes(31)))
        // F-SYS-086: the timeout moves the state without any stored record; the move itself dirties the tile's key.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DELETE FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = ?", r2) }
        try {
            assertEquals(1, job.settleExpired())
            assertEquals("sales_submitted", state(r2))
            assertEquals(1, count("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $r2 AND business_date = DATE '$day'"))
            assertEquals(1, count("SELECT count(*) FROM app.route_day WHERE route_id = $r2 AND business_date = DATE '$day' AND submit_count_mismatch"))
        } finally { now.set(Instant.parse("2027-01-03T04:00:00Z")) }
    }

    @Test
    fun anAmoSubmitWritesSupervisorDayAndNeverTouchesAnSrRoute() {
        val amo = count("SELECT id FROM app.app_user WHERE role = 'AMO' ORDER BY id LIMIT 1")
        val up = Uploader(amo, Role.AMO, 1, 0, devPhone, null)
        val job = RouteDayPlanningJob(fresh.db, DbServerConfig(fresh.db, RegistryDefaults(), clock), clock)
        val before = fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT string_agg(route_id || ':' || state || ':' || COALESCE(sales_submitted_at::text, '-'), ',' ORDER BY route_id) FROM app.route_day").mapTo(String::class.java).one() ?: "" }
        fresh.db.jdbi.useTransaction<Exception> { h ->
            val touched = DayStates.Touched()
            for (rec in listOf(env("attendance_event", null, buildJsonObject { put("kind", "check_in") }), daySubmit(r1, mapOf("visit" to 3), scope = "supervisor_day"))) {
                DayStates.afterStored(h, rec["type"]!!.jsonPrimitive.content, rec, rec["payload"]!!.jsonObject, up, now.get(), job, touched)
            }
            DayStates.afterBatch(h, amo, touched, now.get())
            assertEquals(0, touched.routeDays.size)
        }
        assertEquals(1, count("SELECT count(*) FROM app.supervisor_day WHERE user_id = $amo AND business_date = DATE '$day' AND checked_in_at IS NOT NULL AND sales_submitted_at IS NOT NULL"))
        val after = fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT string_agg(route_id || ':' || state || ':' || COALESCE(sales_submitted_at::text, '-'), ',' ORDER BY route_id) FROM app.route_day").mapTo(String::class.java).one() ?: "" }
        assertEquals(before, after, "no route-day changed")
    }
}
