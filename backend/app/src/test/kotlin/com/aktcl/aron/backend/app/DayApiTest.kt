package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.notify.PushSender
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.rules.Geo
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
 * in the caller's reach only; a figure row captured before it that arrives after it is held after_final_submit
 * (contract 1.10.0, D-TSO-MAN-31). Seed, sr1001, tso1001, real routing.
 *
 * tso-manual B3 (contract 1.10.0): the preview's upload_state, warn_route_ids and card fields with a saved Web Entry and
 * a dummy route, and the submit is never blocked (D-TSO-MAN-27); the next zone stays ready (CR-5); the push
 * `day_final_submitted` to the zone's SR phone after the commit (D-TSO-MAN-31); the audited, idempotent, role-, window-
 * and reach-bound reopen, and the 409 `ERR_DAY_FINAL_SUBMIT_REOPENED` replay rule with `submit_seq` (D-TSO-MAN-28, 29).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DayApiTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = Instant.parse("2027-01-03T06:00:00Z")
    /** Seconds the test clock moved past [now] (a retry comes later than the first call). */
    @Volatile private var moved = 0L
    private val clock = AronClock { now.plusSeconds(moved) }
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
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'test: admins log in without MFA' FROM app.cfg_version")
            zone = h.createQuery("SELECT zone_id FROM app.route WHERE id = :r").bind("r", r1).mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            otherZone = h.createQuery("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER' RETURNING id").mapTo(Long::class.java).one()
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        // tso-manual B3: zone Z-B3 of tso1001's territory, one route per upload_state on 2026-12-28 (D-TSO-MAN-27).
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-B3', 'B3 Final Submit', id FROM app.territory WHERE code = 'T-DHK-N'")
            b3Zone = h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-B3'").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) SELECT 'srb3a', 'SR - B3 Up', role, designation, 'T-B3-A', locale, home_zone_id, false, false FROM app.app_user WHERE username = 'sr1001'")
            h.execute(
                """
                INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no, route_class)
                SELECT r.code, r.name, r.label, z.id, r.kind, r.vk, r.mask, r.seq, r.cls FROM app.zone z,
                  (VALUES ('B3-UP', 'Apsis Route', 'Daily', 'sr', 'daily', 127, 1, 'normal'), ('B3-WEB', 'Web Route', '(Sun, Tue, Thu)', 'sr', '3f', 42, 2, 'manual'),
                          ('B3-AMO', 'AMO Route', 'AMO', 'amo', NULL, 127, 3, 'normal'), ('B3-DBR', 'BR Route', 'BR', 'sr', 'daily', 127, 4, 'dummy_br'),
                          ('B3-OFF', 'Off Route', '(Mon, Thu)', 'sr', '2f', 36, 5, 'normal')) AS r(code, name, label, kind, vk, mask, seq, cls)
                WHERE z.code = 'Z-B3'
                """.trimIndent(),
            )
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) SELECT r.id, u.id, 'primary', DATE '2026-01-01' FROM app.route r, app.app_user u WHERE r.code = 'B3-UP' AND u.username = 'srb3a'")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) SELECT r.id, u.id, 'primary', DATE '2026-01-01' FROM app.route r, app.app_user u WHERE r.code = 'B3-AMO' AND u.username = 'amo1001'")
            h.execute(
                """
                INSERT INTO app.route_day (route_id, business_date, planned, assigned_user_id, state, logged_in_at, in_field_at, submit_received_at, sales_submitted_at)
                SELECT r.id, DATE '$b3Day', d.planned, a.user_id, d.state, d.li, d.inf, d.sub, d.sub FROM app.route r
                JOIN (VALUES ('B3-UP', true, 'sales_submitted', TIMESTAMPTZ '2026-12-28T03:00:00Z', TIMESTAMPTZ '2026-12-28T04:00:00Z', TIMESTAMPTZ '2026-12-28T11:00:00Z'),
                             ('B3-WEB', true, 'not_started', NULL, NULL, NULL), ('B3-AMO', true, 'in_field', NULL, TIMESTAMPTZ '2026-12-28T04:00:00Z', NULL),
                             ('B3-DBR', true, 'not_started', NULL, NULL, NULL), ('B3-OFF', false, 'not_started', NULL, NULL, NULL)) AS d(code, planned, state, li, inf, sub) ON d.code = r.code
                LEFT JOIN app.route_assignment a ON a.route_id = r.id AND a.kind = 'primary'
                """.trimIndent(),
            )
            h.execute(
                "INSERT INTO app.web_entry_route_day (client_uuid, route_id, business_date, successful_calls, target_outlets, entered_by) " +
                    "SELECT gen_random_uuid(), r.id, DATE '$b3Day', 4, 10, u.id FROM app.route r, app.app_user u WHERE r.code = 'B3-WEB' AND u.username = 'admin1001'",
            )
        }
        val recorder = PushSender { token, data -> sent += token to data; true }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock, pushSender = recorder)
    }

    // tso-manual B3
    private var b3Zone = 0L
    private val b3Day = "2026-12-28"
    private val sent = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

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
        moved += 2 // the retry comes later: the server's capture time differs, the answer must not
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

    private suspend fun HttpClient.finalSubmit(token: String, cu: String, z: Long = zone, phone: Boolean = false): HttpResponse = post("/v1/day/final-submit") {
        bearerAuth(token); contentType(ContentType.Application.Json); if (phone) header("X-Device-Id", devPhone)
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
        assertEquals(res, json(replay.bodyAsText()), "the replay answers the first success")
        val second = client.finalSubmit(tso, uuid())
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
        val problem = json(second.bodyAsText())
        assertTrue(second.bodyAsText().contains("ERR_DAY_ALREADY_FINAL_SUBMITTED"), problem.toString())
        assertEquals(res["submitted_at"], problem["context"]!!.jsonObject["submitted_at"])
        assertEquals(true, json(client.preview(tso).bodyAsText())["already_submitted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("final_submitted", routeDay(day, "state"))

        // tso-manual B3 (D-TSO-MAN-31, default cfg.day.late_sync_after_final_policy = quarantine): a call captured before
        // the close that arrives after it is held after_final_submit, out of the figures and not counted until an ADMIN
        // accepts it (LateAfterFinalTest covers the accept). It was "accepted and counted" before contract 1.10.0.
        val held = client.send(listOf(visit(o, 12.0)), want = "quarantined")
        assertEquals("after_final_submit", held["acks"]!!.jsonArray.single().jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(0, routeDay(day, "late_rows_after_final"))
        val late = fresh.db.jdbi.withHandle<Int, Exception> { h ->
            h.createQuery("SELECT late_rows FROM app.final_submit WHERE client_uuid = CAST(:c AS uuid)").bind("c", cu).mapTo(Int::class.java).one()
        }
        assertEquals(0, late)
    }

    @Test
    fun aZoneDayAfterTodayIsRefusedAndAUuidIsBoundToItsZoneDay() = testApplication {
        application { aronApi(wiring) }
        val tso = client.web("tso1001")
        val tomorrow = client.post("/v1/day/final-submit") {
            bearerAuth(tso); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"${uuid()}","zone_id":$zone,"business_date":"2027-01-04"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, tomorrow.status, tomorrow.bodyAsText())
        val cu = uuid()
        val first = client.post("/v1/day/final-submit") {
            bearerAuth(tso); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"$cu","zone_id":$zone,"business_date":"2026-12-30"}""")
        }
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val other = client.post("/v1/day/final-submit") {
            bearerAuth(tso); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"$cu","zone_id":$zone,"business_date":"2026-12-29"}""")
        }
        assertEquals(HttpStatusCode.Conflict, other.status, other.bodyAsText())
        // Every route of the zone got a closed route-day, assigned or not.
        val open = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery(
                "SELECT count(*) FROM app.route r WHERE r.zone_id = :z AND r.status = 'active' AND NOT EXISTS (SELECT 1 FROM app.route_day d WHERE d.route_id = r.id AND d.business_date = DATE '2026-12-30' AND d.final_submitted_at IS NOT NULL)",
            ).bind("z", zone).mapTo(Long::class.java).one()
        }
        assertEquals(0L, open)
    }

    @Test
    fun zoneDayOutsideReachAndFieldRolesAreRefused() = testApplication {
        application { aronApi(wiring) }
        val tso = client.web("tso1001")
        assertEquals(HttpStatusCode.Forbidden, client.preview(tso, otherZone).status)
        val r = client.finalSubmit(tso, uuid(), otherZone)
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        val sr = client.finalSubmit(client.login(), uuid(), phone = true)
        assertEquals(HttpStatusCode.Forbidden, sr.status, sr.bodyAsText())
        assertEquals(0L, fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(*) FROM app.final_submit WHERE zone_id = :z").bind("z", otherZone).mapTo(Long::class.java).one()
        })
    }

    /**
     * QA-FLOW-04 (docs/requests/backend-core-qa-1.md item 3, lead ruling 2026-10-22): every active route is listed, but
     * only a route planned on the date or with data warns. The first bundle of the day marks an SR's unplanned route
     * logged in; with nothing on it, that route was not due.
     */
    @Test
    fun theFinalSubmitPreviewWarnsOnlyForRoutesDueThatDay() = testApplication {
        application { aronApi(wiring) }
        val tso = client.web("tso1001")
        val d = "2026-12-30"
        fresh.db.jdbi.useHandle<Exception> { h ->
            // The planner's rows for the zone's routes (none planned that day); the bundle then marked r1 logged in.
            h.execute("INSERT INTO app.route_day (route_id, business_date, planned) SELECT id, DATE '$d', false FROM app.route WHERE zone_id = ? AND status = 'active' ON CONFLICT DO NOTHING", zone)
            h.execute("UPDATE app.route_day SET planned = false WHERE business_date = DATE '$d'")
            h.execute("UPDATE app.route_day SET state = 'logged_in', logged_in_at = TIMESTAMPTZ '${d}T03:00:00Z' WHERE route_id = ? AND business_date = DATE '$d'", r1)
        }
        suspend fun preview(): JsonObject {
            val r = client.get("/v1/day/final-submit/preview?zone_id=$zone&business_date=$d") { bearerAuth(tso) }
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            return json(r.bodyAsText())
        }
        // Contract 1.7.0: each route carries `planned` from its route-day row, JSON null (present) when there is none.
        fun planned(p: JsonObject): Map<Long, JsonElement> =
            p["routes"]!!.jsonArray.associate { it.jsonObject["route_id"]!!.jsonPrimitive.content.toLong() to it.jsonObject.getValue("planned") }
        val p = preview()
        assertTrue(planned(p).size > 1 && planned(p).values.all { it == JsonPrimitive(false) }, "every route planned false: $p")
        val listed = p["routes"]!!.jsonArray.map { it.jsonObject }.single { it["route_id"]!!.jsonPrimitive.content.toLong() == r1 }
        assertEquals("logged_in", listed["state"]!!.jsonPrimitive.content, "the unplanned route is still listed")
        assertEquals(false, listed["had_data"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(emptyList(), p["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }, "no route of the zone was due on $d: $p")
        // A route with no route-day row (the planner has not run) still warns as before.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DELETE FROM app.route_day WHERE business_date = DATE '$d' AND route_id <> ?", r1) }
        val noRow = preview()
        assertEquals(listOf("routes_not_sales_submitted", "routes_not_logged_in"), noRow["warnings"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(false), planned(noRow)[r1])
        assertTrue(planned(noRow).filterKeys { it != r1 }.values.let { it.isNotEmpty() && it.all { v -> v == JsonNull } }, "no route-day row: planned null: $noRow")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.route_day (route_id, business_date, planned) SELECT id, DATE '$d', false FROM app.route WHERE zone_id = ? AND status = 'active' ON CONFLICT DO NOTHING", zone)
        }
        assertEquals(emptyList(), preview()["warnings"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Planned that day: the same state warns.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.route_day SET planned = true WHERE route_id = ? AND business_date = DATE '$d'", r1) }
        val plannedDay = preview()
        assertEquals(listOf("routes_not_sales_submitted"), plannedDay["warnings"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(true), planned(plannedDay)[r1])
        assertTrue(planned(plannedDay).filterKeys { it != r1 }.values.all { it == JsonPrimitive(false) })
        // Unplanned, no data, but submitted with rows still on the phone: pending rows still warn (checker note 7).
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.route_day SET planned = false, state = 'submit_pending_rows', rows_awaited = 2 WHERE route_id = ? AND business_date = DATE '$d'", r1)
        }
        val pending = preview()
        assertEquals(listOf("routes_not_sales_submitted", "routes_with_pending_rows"), pending["warnings"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(false), planned(pending)[r1])
    }

    // ---------------- tso-manual B3: D-TSO-MAN-27..29, 31, CR-3, CR-5 ----------------

    private suspend fun HttpClient.previewOf(token: String, z: Long, d: String): JsonObject {
        val r = get("/v1/day/final-submit/preview?zone_id=$z&business_date=$d") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())
    }

    private suspend fun HttpClient.submitOf(token: String, cu: String, z: Long, d: String): HttpResponse = post("/v1/day/final-submit") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"client_uuid":"$cu","zone_id":$z,"business_date":"$d"}""")
    }

    private suspend fun HttpClient.reopenOf(token: String, z: Long, d: String, reason: String = "SR found unsent memos on the phone", cu: String? = null): HttpResponse = post("/v1/day/reopen") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"zone_id":$z,"business_date":"$d","reason":"$reason"${cu?.let { ",\"client_uuid\":\"$it\"" } ?: ""}}""")
    }

    private fun code(body: String): String? = runCatching { json(body)["code"]?.jsonPrimitive?.content }.getOrNull()

    private fun routeIdOf(code: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.route WHERE code = :c").bind("c", code).mapTo(Long::class.java).one() }

    private fun events(type: String, z: Long, d: String): List<JsonObject> = fresh.db.jdbi.withHandle<List<JsonObject>, Exception> { h ->
        h.createQuery("SELECT payload::text FROM app.domain_event WHERE event_type = :t AND aggregate_type = 'zone_day' AND aggregate_id = :a AND business_date = CAST(:d AS date) ORDER BY id")
            .bind("t", type).bind("a", "$z:$d").bind("d", d).mapTo(String::class.java).list().map { json(it) }
    }

    private val switches = AtomicInteger(0)

    /** A global config switch the way the portal sets it (DeviceGateTest pattern); the clock moves past the 30 s config cache. */
    private fun setConfig(key: String, valueJson: String) {
        val n = switches.incrementAndGet()
        fresh.db.jdbi.useTransaction<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test day' FROM app.cfg_version")
            h.execute("UPDATE app.cfg_value SET effective_to = TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n WHERE key = '$key' AND scope_type = 'global' AND effective_to IS NULL")
            h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                    "SELECT '$key', 'global', 0, '$valueJson'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n, max(config_version), 'test' FROM app.cfg_version",
            )
        }
        moved += 31
    }

    /**
     * D-TSO-MAN-27 (screenshot 25): every route of the zone carries its upload_state (a sales-submitted route is uploaded,
     * a saved Web Entry stands for a vacant manual route, a dummy BR route and an unplanned route without data are not
     * due), the due routes not uploaded are named in warn_route_ids, and the card fields (route_class, visit_kind,
     * ff_role) are filled. The submit goes through with a not-uploaded route (warn, never block) and the chip still tells
     * the truth after it; the close emits zone_day.final_submitted. CR-5: the next zone of the TSO is ready on its own.
     */
    @Test
    fun thePreviewNamesTheNotUploadedRoutesAndTheSubmitNeverBlocks() = testApplication {
        application { aronApi(wiring) }
        val tso = client.web("tso1001")
        val p = client.previewOf(tso, b3Zone, b3Day)
        val byCode = p["routes"]!!.jsonArray.map { it.jsonObject }.associateBy { it["route_code"]!!.jsonPrimitive.content }
        fun f(code: String, k: String): JsonElement = byCode.getValue(code).getValue(k)
        assertEquals(
            mapOf("B3-UP" to "uploaded", "B3-WEB" to "web_entry", "B3-AMO" to "not_uploaded", "B3-DBR" to "not_due", "B3-OFF" to "not_due"),
            byCode.mapValues { it.value["upload_state"]!!.jsonPrimitive.content },
        )
        assertEquals(listOf(routeIdOf("B3-AMO")), p["warn_route_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })
        assertEquals(listOf("routes_not_sales_submitted"), p["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }, "the Web Entry and dummy routes do not warn: $p")
        assertEquals(0, p["submit_seq"]!!.jsonPrimitive.content.toInt())
        // The card: "<route name> <kind label>" and the FF line (D-TSO-MAN-27).
        assertEquals(JsonPrimitive("normal"), f("B3-UP", "route_class")); assertEquals(JsonPrimitive("daily"), f("B3-UP", "visit_kind"))
        assertEquals(JsonPrimitive("SR"), f("B3-UP", "ff_role")); assertEquals(JsonPrimitive("SR - B3 Up"), f("B3-UP", "ff_user_name"))
        assertEquals(JsonPrimitive("manual"), f("B3-WEB", "route_class")); assertEquals(JsonPrimitive("3f"), f("B3-WEB", "visit_kind"))
        assertEquals(JsonNull, f("B3-WEB", "ff_user_id"), "a vacant route is SR Not Set"); assertEquals(JsonNull, f("B3-WEB", "ff_role"))
        assertEquals(JsonPrimitive("amo"), f("B3-AMO", "kind")); assertEquals(JsonNull, f("B3-AMO", "visit_kind")); assertEquals(JsonPrimitive("AMO"), f("B3-AMO", "ff_role"))
        assertEquals(JsonPrimitive("dummy_br"), f("B3-DBR", "route_class"))

        // Warn, never block: the TSO submits with the AMO route not uploaded.
        val cu = uuid()
        val fs = client.submitOf(tso, cu, b3Zone, b3Day)
        assertEquals(HttpStatusCode.OK, fs.status, fs.bodyAsText())
        assertEquals(5, json(fs.bodyAsText())["route_count"]!!.jsonPrimitive.content.toInt())
        assertEquals("manual", json(fs.bodyAsText())["kind"]!!.jsonPrimitive.content)
        val after = client.previewOf(tso, b3Zone, b3Day)
        assertEquals(true, after["already_submitted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("not_uploaded", after["routes"]!!.jsonArray.map { it.jsonObject }.single { it["route_code"]!!.jsonPrimitive.content == "B3-AMO" }["upload_state"]!!.jsonPrimitive.content, "the chip tells the truth after the close")
        // The close's event (D-TSO-MAN-38), in the close's transaction, once (a replay emits nothing).
        assertEquals(HttpStatusCode.OK, client.submitOf(tso, cu, b3Zone, b3Day).status)
        val ev = events("zone_day.final_submitted", b3Zone, b3Day).single()
        val tsoId = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = 'tso1001'").mapTo(Long::class.java).one() }
        assertEquals(cu, ev["final_submit_uuid"]!!.jsonPrimitive.content); assertEquals("web", ev["via"]!!.jsonPrimitive.content)
        assertEquals(tsoId, ev["submitted_by"]!!.jsonPrimitive.content.toLong()); assertEquals(0, ev["submit_seq"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, ev["route_count"]!!.jsonPrimitive.content.toInt()); assertEquals(1, ev["not_uploaded_route_count"]!!.jsonPrimitive.content.toInt())

        // CR-5: after OK the TSO picks the next zone; its zone-day is untouched and closes on its own.
        val next = client.previewOf(tso, zone, b3Day)
        assertEquals(false, next["already_submitted"]!!.jsonPrimitive.content.toBoolean(), next.toString())
        val nextFs = client.submitOf(tso, uuid(), zone, b3Day)
        assertEquals(HttpStatusCode.OK, nextFs.status, nextFs.bodyAsText())
    }

    /** D-TSO-MAN-31: after the commit the SR phone of the zone gets one data-only push `day_final_submitted`; a replay sends none. */
    @Test
    fun aCloseNudgesTheSrPhoneOfTheZoneAfterTheCommit() = testApplication {
        application { aronApi(wiring) }
        val phone = client.login()
        val fcm = "fcm-token-" + "d".repeat(40)
        val reg = client.put("/v1/devices/me/push-token") {
            bearerAuth(phone); header("X-Device-Id", devPhone); contentType(ContentType.Application.Json)
            setBody("""{"provider":"fcm","token":"$fcm","app_flavour":"sr"}""")
        }
        assertEquals(HttpStatusCode.NoContent, reg.status, reg.bodyAsText())
        val tso = client.web("tso1001")
        val d = "2026-12-31"
        sent.clear()
        val cu = uuid()
        val r = client.submitOf(tso, cu, zone, d)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        fun mine() = sent.filter { it.first == fcm && it.second["reason"] == "day_final_submitted" }
        val until = System.nanoTime() + 30_000_000_000L
        while (mine().isEmpty() && System.nanoTime() < until) Thread.sleep(20)
        assertEquals(1, mine().size, "one push within 30 seconds: $sent")
        assertEquals(mapOf("kind" to "sync_nudge", "reason" to "day_final_submitted"), mine().single().second, "a data-only nudge, no business data")
        assertEquals(HttpStatusCode.OK, client.submitOf(tso, cu, zone, d).status)
        Thread.sleep(300)
        assertEquals(1, mine().size, "a replay sends no second push")
    }

    /**
     * D-TSO-MAN-28 and 29 (CR-3): the reopen is for cfg.day.reopen_roles (ADMIN, SUPERADMIN), within the window, for a
     * zone in reach, with a reason of 10..500; it is audited once, idempotent by client_uuid, emits zone_day.reopened and
     * puts the route-days back. A replay of the reopened close's uuid is then 409 ERR_DAY_FINAL_SUBMIT_REOPENED; the
     * preview's submit_seq counts the reopen and the next uuid closes the day again.
     */
    @Test
    fun reopenIsAuditedIdempotentAndBoundToRoleWindowAndReach() = testApplication {
        application { aronApi(wiring) }
        val tso = client.web("tso1001")
        val admin = client.web("admin1001")
        val superadmin = client.web("superadmin1001")
        val d = "2027-01-02"
        val first = uuid()
        val closed = client.submitOf(tso, first, b3Zone, d)
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        assertEquals("final_submitted", fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT state FROM app.route_day WHERE route_id = :r AND business_date = DATE '$d'").bind("r", routeIdOf("B3-UP")).mapTo(String::class.java).one() })

        // Refusals, nothing written.
        val byTso = client.reopenOf(tso, b3Zone, d)
        assertEquals(HttpStatusCode.Forbidden, byTso.status, byTso.bodyAsText())
        val short = client.reopenOf(admin, b3Zone, d, reason = "too short")
        assertEquals(HttpStatusCode.BadRequest, short.status, short.bodyAsText())
        val old = client.reopenOf(admin, b3Zone, "2026-12-28")
        assertEquals(HttpStatusCode.Conflict, old.status, old.bodyAsText()); assertEquals("ERR_REQUEST_STATE", code(old.bodyAsText()))
        val future = client.reopenOf(admin, b3Zone, "2027-01-04")
        assertEquals(HttpStatusCode.Conflict, future.status, future.bodyAsText())
        val open = client.reopenOf(admin, otherZone, d)
        assertEquals(HttpStatusCode.Conflict, open.status, open.bodyAsText()); assertEquals("ERR_DAY_NOT_FINAL_SUBMITTED", code(open.bodyAsText()))
        assertEquals(0L, fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT count(*) FROM app.audit_log WHERE entity = 'zone_day' AND action = 'reopen'").mapTo(Long::class.java).one() })

        // The reopen, then its replay (same answer, nothing new), and the same uuid by someone else.
        val ru = uuid()
        val r1 = client.reopenOf(admin, b3Zone, d, cu = ru)
        assertEquals(HttpStatusCode.OK, r1.status, r1.bodyAsText())
        val res = json(r1.bodyAsText())
        val adminId = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = 'admin1001'").mapTo(Long::class.java).one() }
        assertEquals(adminId, res["reopened_by"]!!.jsonPrimitive.content.toLong()); assertEquals(d, res["business_date"]!!.jsonPrimitive.content)
        moved += 2
        val r2 = client.reopenOf(admin, b3Zone, d, cu = ru)
        assertEquals(HttpStatusCode.OK, r2.status, r2.bodyAsText())
        assertEquals(res, json(r2.bodyAsText()), "a repeat answers the first result")
        val other = client.reopenOf(superadmin, b3Zone, d, cu = ru)
        assertEquals(HttpStatusCode.Conflict, other.status, other.bodyAsText()); assertEquals("ERR_CONFLICT", code(other.bodyAsText()))
        val again = client.reopenOf(admin, b3Zone, d)
        assertEquals(HttpStatusCode.Conflict, again.status, again.bodyAsText()); assertEquals("ERR_DAY_NOT_FINAL_SUBMITTED", code(again.bodyAsText()))

        // Audited once, the event once, the close carries the reopen, the route-days are open again and dirty.
        val audit = fresh.db.jdbi.withHandle<List<Map<String, Any?>>, Exception> { h ->
            h.createQuery("SELECT actor_user_id, reason, entity_id FROM app.audit_log WHERE entity = 'zone_day' AND action = 'reopen'").mapToMap().list()
        }
        assertEquals(1, audit.size, audit.toString())
        assertEquals(adminId, (audit.single()["actor_user_id"] as Number).toLong()); assertEquals("$b3Zone:$d", audit.single()["entity_id"])
        assertEquals("SR found unsent memos on the phone", audit.single()["reason"])
        val ev = events("zone_day.reopened", b3Zone, d).single()
        assertEquals(first, ev["final_submit_uuid"]!!.jsonPrimitive.content); assertEquals(ru, ev["reopen_uuid"]!!.jsonPrimitive.content)
        assertEquals(5, ev["route_day_count"]!!.jsonPrimitive.content.toInt())
        val close = fresh.db.jdbi.withHandle<Map<String, Any?>, Exception> { h ->
            h.createQuery("SELECT reopened_by, reopen_reason, reopen_client_uuid::text AS ru FROM app.final_submit WHERE client_uuid = CAST(:u AS uuid)").bind("u", first).mapToMap().one()
        }
        assertEquals(adminId, (close["reopened_by"] as Number).toLong()); assertEquals(ru, close["ru"])
        val days = fresh.db.jdbi.withHandle<List<Pair<String, Boolean>>, Exception> { h ->
            h.createQuery("SELECT rd.state, rd.final_submitted_at IS NULL FROM app.route_day rd JOIN app.route r ON r.id = rd.route_id WHERE r.zone_id = :z AND rd.business_date = DATE '$d'")
                .bind("z", b3Zone).map { rs, _ -> rs.getString(1) to rs.getBoolean(2) }.list()
        }
        assertTrue(days.size == 5 && days.all { (s, open) -> open && s != "final_submitted" }, "every route-day open again: $days")
        assertEquals(5L, fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND business_date = DATE '$d' AND subject_id IN (SELECT id FROM app.route WHERE zone_id = :z)").bind("z", b3Zone).mapTo(Long::class.java).one()
        })

        // D-TSO-MAN-28: the reopened uuid is spent; the preview counts the reopen and the next uuid closes the day again.
        val replay = client.submitOf(tso, first, b3Zone, d)
        assertEquals(HttpStatusCode.Conflict, replay.status, replay.bodyAsText()); assertEquals("ERR_DAY_FINAL_SUBMIT_REOPENED", code(replay.bodyAsText()))
        val pv = client.previewOf(tso, b3Zone, d)
        assertEquals(1, pv["submit_seq"]!!.jsonPrimitive.content.toInt()); assertEquals(false, pv["already_submitted"]!!.jsonPrimitive.content.toBoolean())
        val second = uuid()
        val s2 = client.submitOf(tso, second, b3Zone, d)
        assertEquals(HttpStatusCode.OK, s2.status, s2.bodyAsText())
        assertEquals(json(s2.bodyAsText()), json(client.submitOf(tso, second, b3Zone, d).bodyAsText()), "the live close replays")
        assertEquals("ERR_DAY_FINAL_SUBMIT_REOPENED", code(client.submitOf(tso, first, b3Zone, d).bodyAsText()), "the reopened one stays spent")
        assertEquals(1, events("zone_day.final_submitted", b3Zone, d).last()["submit_seq"]!!.jsonPrimitive.content.toInt())

        // Reach: with TSO among the reopen roles, a zone outside the TSO's reach is refused; the default comes back.
        setConfig("cfg.day.reopen_roles", """["ADMIN","SUPERADMIN","TSO"]""")
        try {
            val outside = client.reopenOf(tso, otherZone, d)
            assertEquals(HttpStatusCode.Forbidden, outside.status, outside.bodyAsText()); assertEquals("ERR_OUT_OF_SCOPE", code(outside.bodyAsText()))
        } finally {
            setConfig("cfg.day.reopen_roles", """["ADMIN","SUPERADMIN"]""")
        }
    }
}
