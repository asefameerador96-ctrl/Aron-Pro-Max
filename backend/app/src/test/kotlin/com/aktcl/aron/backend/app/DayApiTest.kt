package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.rules.Geo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-API-008 (POST /v1/day/sales-submit), F-API-009 (POST /v1/day/final-submit), F-API-039 (GET
 * /v1/day/final-submit/preview): online Sales Submit is the day_submit record (idempotent by client_uuid, settles on the
 * device counts); Final Submit once per zone-day (replay = first success, another uuid 409 with context.submitted_at),
 * in the caller's reach only; rows after it are accepted and counted late. Seed, sr1001, tso1001, real routing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DayApiTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = Instant.parse("2027-01-03T06:00:00Z")
    private val clock = AronClock { now }
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val day = "2027-01-03"
    private val seq = AtomicInteger(0)
    private var r1 = 0L
    private var template = 0L
    private var zone = 0L
    private var otherZone = 0L
    private val pinLat = 23.80
    private val pinLng = 90.36
    private var token: String? = null

    private fun north(m: Double) = pinLat + m / (Geo.EARTH_RADIUS_M * PI / 180.0)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            val sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            r1 = h.createQuery("SELECT min(route_id) FROM app.route_assignment WHERE user_id = :u").bind("u", sr).mapTo(Long::class.java).one()
            template = h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r AND status = 'active' ORDER BY id LIMIT 1").bind("r", r1).mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test: admins log in without MFA' FROM app.cfg_version")
            zone = h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", r1).mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            otherZone = h.createQuery("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER' RETURNING id").mapTo(Long::class.java).one()
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

    /** A new active outlet on route r1 (copy of a seeded one) with a master pin. */
    private fun newOutlet(code: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery(
            """
            INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, sub_channel_id, geo_class, lat, lng, location_basis)
            SELECT :code, name, owner_name, zone_id, route_id, cluster_id, channel, sub_channel_id, geo_class, :la, :ln, 'master' FROM app.outlet WHERE id = :t
            RETURNING id
            """.trimIndent(),
        ).bind("code", code).bind("la", pinLat).bind("ln", pinLng).bind("t", template).mapTo(Long::class.java).one()
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()

    private val tokens = HashMap<String, String>()
    private suspend fun HttpClient.web(user: String): String = tokens.getOrPut(user) {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"$user","password":"$password","client":"web"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.login(): String = token ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
    }

    private fun visit(outlet: Long, metresNorth: Double, capturedAt: String? = null, cu: String = uuid()): JsonObject {
        val n = seq.incrementAndGet()
        return buildJsonObject {
            put("type", "visit"); put("client_uuid", cu); put("family_uuid", cu); put("rank", 0); put("schema_version", 1)
            put("business_date", day); put("captured_at", capturedAt ?: "2027-01-03T03:%02d:%02d.000Z".format(n / 60, n % 60))
            put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
            put("clock_offset_ms", 0); put("captured_offline", true); put("route_id", r1); put("bundle_version", "$day:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", json(
                """
                {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":$n,"planned":true,
                 "fix":{"purpose":"visit_open","fix_status":"ok","lat":${north(metresNorth)},"lng":$pinLng,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
                 "geo":{"verdict":"in_range","distance_m":$metresNorth,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
                """.trimIndent(),
            ))
        }
    }

    private fun uuidOf(rec: JsonObject) = rec["client_uuid"]!!.jsonPrimitive.content

    private suspend fun HttpClient.send(records: List<JsonObject>, want: String = "accepted"): JsonObject {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val t = login()
        val r = post("/v1/sync/batch") {
            bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        b["acks"]!!.jsonArray.forEach { a -> assertEquals(want, a.jsonObject["status"]!!.jsonPrimitive.content, a.toString()) }
        return b
    }


    private suspend fun HttpClient.salesSubmit(cu: String, date: String, counts: String = "{}"): HttpResponse = post("/v1/day/sales-submit") {
        bearerAuth(login()); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); contentType(ContentType.Application.Json)
        setBody(
            """{"client_uuid":"$cu","scope":"route_day","route_id":$r1,"business_date":"$date","submit_cycle":1,"device_counts":$counts,
               "device_money":$money,"submitted_with_dues":false,"dues_outstanding_mtk":0}""",
        )
    }

    private val money = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,
        "due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""

    private fun routeDay(date: String, col: String): Any? = fresh.db.jdbi.withHandle<Any?, Exception> { h ->
        h.createQuery("SELECT $col FROM app.route_day WHERE route_id = :r AND business_date = CAST(:d AS date)").bind("r", r1).bind("d", date).mapToMap().one()[col]
    }

    private fun state(r: HttpResponse, body: String): JsonObject {
        assertEquals(HttpStatusCode.OK, r.status, body)
        return json(body)["route_days"]!!.jsonArray.single().jsonObject
    }

    @Test
    fun onlineSalesSubmitIsIdempotentAndSettlesOnTheDeviceCounts() = testApplication {
        application { aronApi(wiring) }
        val cu = uuid()
        val r = client.salesSubmit(cu, "2027-01-02")
        assertEquals("sales_submitted", state(r, r.bodyAsText())["state"]!!.jsonPrimitive.content)
        val again = client.salesSubmit(cu, "2027-01-02")
        assertEquals("sales_submitted", state(again, again.bodyAsText())["state"]!!.jsonPrimitive.content)
        val events = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(*) FROM app.route_day_event WHERE client_uuid = CAST(:c AS uuid)").bind("c", cu).mapTo(Long::class.java).one()
        }
        assertEquals(1L, events, "the replay stored nothing new")
        // Device counts above the server's totals: pending until the rows arrive or the settle timeout.
        val pending = client.salesSubmit(uuid(), "2027-01-01", """{"memo":5}""")
        val s = state(pending, pending.bodyAsText())
        assertEquals("submit_pending_rows", s["state"]!!.jsonPrimitive.content, s.toString())
    }

    @Test
    fun aWebUserCannotSalesSubmit() = testApplication {
        application { aronApi(wiring) }
        val r = client.post("/v1/day/sales-submit") {
            bearerAuth(client.web("tso1001")); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"${uuid()}","scope":"supervisor_day","business_date":"$day","submit_cycle":1,"device_counts":{},"device_money":{},"submitted_with_dues":false,"dues_outstanding_mtk":0}""")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
    }

    private suspend fun HttpClient.finalSubmit(token: String, cu: String, z: Long = zone): HttpResponse = post("/v1/day/final-submit") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"client_uuid":"$cu","zone_id":$z,"business_date":"$day"}""")
    }

    private suspend fun HttpClient.preview(token: String, z: Long = zone): HttpResponse =
        get("/v1/day/final-submit/preview?zone_id=$z&business_date=$day") { bearerAuth(token) }

    @Test
    fun finalSubmitOncePerZoneDayReplayAndLateRows() = testApplication {
        application { aronApi(wiring) }
        val o = newOutlet("DAY-FS-1")
        client.send(listOf(visit(o, 10.0))) // the route-day exists and has data
        val tso = client.web("tso1001")
        val p0 = client.preview(tso)
        assertEquals(HttpStatusCode.OK, p0.status, p0.bodyAsText())
        val pre = json(p0.bodyAsText())
        assertEquals(false, pre["already_submitted"]!!.jsonPrimitive.content.toBoolean())
        val route = pre["routes"]!!.jsonArray.map { it.jsonObject }.single { it["route_id"]!!.jsonPrimitive.content.toLong() == r1 }
        assertEquals(true, route["had_data"]!!.jsonPrimitive.content.toBoolean(), route.toString())

        val cu = uuid()
        val first = client.finalSubmit(tso, cu)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val res = json(first.bodyAsText())
        assertEquals("manual", res["kind"]!!.jsonPrimitive.content)
        val replay = client.finalSubmit(tso, cu)
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals(res["submitted_at"], json(replay.bodyAsText())["submitted_at"], "the replay answers the first success")
        val second = client.finalSubmit(tso, uuid())
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
        val problem = json(second.bodyAsText())
        assertTrue(second.bodyAsText().contains("ERR_DAY_ALREADY_FINAL_SUBMITTED"), problem.toString())
        assertEquals(res["submitted_at"], problem["context"]!!.jsonObject["submitted_at"])
        assertEquals(true, json(client.preview(tso).bodyAsText())["already_submitted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("final_submitted", routeDay(day, "state"))

        // A row of the zone-day after Final Submit: accepted, counted late on the route-day and the final submit.
        client.send(listOf(visit(o, 12.0)))
        assertEquals(1, routeDay(day, "late_rows_after_final"))
        val late = fresh.db.jdbi.withHandle<Int, Exception> { h ->
            h.createQuery("SELECT late_rows FROM app.final_submit WHERE client_uuid = CAST(:c AS uuid)").bind("c", cu).mapTo(Int::class.java).one()
        }
        assertEquals(1, late)
    }

    @Test
    fun zoneDayOutsideReachAndFieldRolesAreRefused() = testApplication {
        application { aronApi(wiring) }
        val tso = client.web("tso1001")
        assertEquals(HttpStatusCode.Forbidden, client.preview(tso, otherZone).status)
        val r = client.finalSubmit(tso, uuid(), otherZone)
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        val sr = client.finalSubmit(client.login(), uuid())
        assertTrue(sr.status == HttpStatusCode.Forbidden, sr.bodyAsText())
        assertEquals(0L, fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(*) FROM app.final_submit WHERE zone_id = :z").bind("z", otherZone).mapTo(Long::class.java).one()
        })
    }
}
