package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-API-006 acceptance on the seed through the production wiring: POST /v1/sync/batch upserts every record by
 * client_uuid, replays a repeated batch_uuid, returns accepted, rejected, parked, server_totals and day_states, accepts
 * at most 500 rows and answers 429 with Retry-After on a storm. Clock: Sunday 2027-01-03 10:00 Dhaka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataVoidAcceptanceTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keyFile: File
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private var otherOutletId = 0L
    private val skus = mutableListOf<Pair<Long, Long>>() // sku id to outlet price
    private val memoSeq = java.util.concurrent.atomic.AtomicInteger(100)
    private val captureSeq = java.util.concurrent.atomic.AtomicInteger(0)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            outletId = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = :r").bind("r", routeId).mapTo(Long::class.java).one()
            // An outlet outside the SR's routes (another territory) for the scope check.
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            h.execute("INSERT INTO app.cluster (zone_id, name) SELECT id, 'Other cluster' FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel) SELECT 'OTH-1', 'Other 1', 'Owner', z.id, c.id, 'GT' FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id WHERE z.code = 'Z-OTHER'")
            otherOutletId = h.createQuery("SELECT id FROM app.outlet WHERE code = 'OTH-1'").mapTo(Long::class.java).one()
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            // A small per-device limit so a storm is cheap to provoke (the guard reads it at wiring time).
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test rl' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.api.rl.device_per_min', 'global', 0, '40'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test: admins log in without MFA' FROM app.cfg_version")
        }
        keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()

    private var cachedToken: String? = null

    /** One login per class: the login limiter (10 per 15 min per username) never empties under the fixed clock. */
    private suspend fun HttpClient.token(): String = cachedToken ?: login().also { cachedToken = it }

    private suspend fun HttpClient.login(): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private fun batch(records: List<JsonObject>, batchUuid: String = uuid()): String = buildJsonObject {
        put("batch_uuid", batchUuid); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
        put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
        put("time_anchors", kotlinx.serialization.json.JsonArray(emptyList()))
        put("device_counts", buildJsonObject { put(day, buildJsonObject { put("visit", 1) }) })
        put("records", kotlinx.serialization.json.JsonArray(records))
    }.toString()

    private suspend fun HttpClient.send(token: String, body: String, gzip: Boolean = true): HttpResponse = post("/v1/sync/batch") {
        bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9")
        if (gzip) { header("Content-Encoding", "gzip"); setBody(io.ktor.http.content.ByteArrayContent(gz(body), ContentType.Application.Json)) }
        else { contentType(ContentType.Application.Json); setBody(body) }
    }

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, outlet: Long = outletId, route: Long? = routeId) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:41:00.120Z"); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    /** A sale family: visit (with fix and verdict), memo with two lines, visit close. */
    private fun saleFamily(
        outlet: Long = outletId,
        memoNo: String = "sr1001-270103-${memoSeq.incrementAndGet()}",
        headerGrossDelta: Long = 0,
        priceDelta: Long = 0,
        lat: Double = 23.80,
        dueMtk: Long = 0,
    ): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val (s1, p1x) = skus[0]; val (s2, p2x) = skus[1]
        val p1 = p1x + priceDelta; val p2 = p2x + priceDelta
        val g1 = 20 * p1; val g2 = 10 * p2; val gross = g1 + g2 + headerGrossDelta
        val visitPayload = Json.parseToJsonElement(
            """
            {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":1,"planned":true,
             "fix":{"purpose":"visit_open","fix_status":"ok","lat":$lat,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                    "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
             "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
            """.trimIndent(),
        ).jsonObject
        val memoPayload = Json.parseToJsonElement(
            """
            {"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"2027-01-03T03:43:00.120Z",
             "price_list_date":"$day","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
             "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":${gross - dueMtk},"due_mtk":$dueMtk,"is_credit":${dueMtk > 0},"line_count":2,"discount_line_count":0,"qc_line_count":0,
             "offer_version_ids":[],"rounding_mode":"half_up_paisa"}
            """.trimIndent(),
        ).jsonObject
        fun line(no: Int, sku: Long, qty: Int, price: Long, gross: Long) = Json.parseToJsonElement(
            """
            {"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale","qty_entered":$qty,"unit_entered":"stick","pack_factor":1,"qty_base":$qty,
             "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":$gross}
            """.trimIndent(),
        ).jsonObject
        val close = Json.parseToJsonElement("""{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"2027-01-03T03:43:10.000Z","is_zero_sale":false}""").jsonObject
        // Every family is its own capture: a distinct captured_at, so the content fingerprint tells families apart.
        val at = JsonPrimitive(Instant.parse("2027-01-02T18:30:00Z").plusSeconds(captureSeq.incrementAndGet() * 60L).toString())
        return listOf(
            envelope("visit", visit, visit, 0, visitPayload, outlet),
            envelope("memo", memo, visit, 1, memoPayload, outlet),
            envelope("memo_line", uuid(), visit, 2, line(1, s1, 20, p1, g1)),
            envelope("memo_line", uuid(), visit, 2, line(2, s2, 10, p2, g2)),
            envelope("visit_close", uuid(), visit, 1, close),
        ).map { JsonObject(it + ("captured_at" to at)) }.map { r ->
            // Each family is its own sale minute (s4.5 content fingerprint: outlet, lines and minute).
            if (r["type"]!!.jsonPrimitive.content == "memo") JsonObject(r + ("payload" to JsonObject(r["payload"]!!.jsonObject + ("committed_at" to at)))) else r
        }
    }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private fun statuses(r: JsonObject) = r["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content }

    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }

    private suspend fun HttpClient.webToken(user: String): String {
        val r = post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$password","client":"web"}""") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.voidDay(token: String, cu: String, scope: String = "all", route: Long = routeId, date: String = day) = post("/v1/admin/data-void") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"client_uuid":"$cu","route_id":$route,"business_date":"$date","scope":"$scope","reason":"Wrong day uploaded by mistake"}""")
    }

    @Test
    fun aVoidTombstonesTheRouteDayRejectsLateRowsReversesDuesAndReplaysIdempotently() = testApplication {
        application { aronApi(wiring) }
        val sr = client.token(); val admin = client.webToken("admin1001")
        val family = saleFamily(dueMtk = 500)
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(sr, batch(family)).bodyAsText())))
        val memo = family[1]["client_uuid"]!!.jsonPrimitive.content
        assertEquals(500, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE memo_client_uuid = '$memo'"))
        val dueBefore = count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outletId")

        // N-049: the sale wrote its outbox events in the same transaction, and every stored row has its stable external reference.
        assertEquals(1, count("SELECT count(*) FROM app.domain_event WHERE event_type = 'memo.created' AND source_client_uuid = '$memo'"))
        assertEquals(1, count("SELECT count(*) FROM app.domain_event WHERE event_type = 'visit.closed' AND aggregate_id = '${family[0]["client_uuid"]!!.jsonPrimitive.content}' AND source_client_uuid = '${family[4]["client_uuid"]!!.jsonPrimitive.content}'"))
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo' AND external_ref = '$memo'"))
        assertEquals(List(5) { "duplicate" }, statuses(json(client.send(sr, batch(family)).bodyAsText())))
        assertEquals(1, count("SELECT count(*) FROM app.domain_event WHERE event_type = 'memo.created' AND source_client_uuid = '$memo'"), "a replay emits nothing")

        val cu = uuid()
        val r = client.voidDay(admin, cu)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val out = json(r.bodyAsText())
        assertTrue(out["tombstoned_records"]!!.jsonPrimitive.int >= 4, out.toString())
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE route_id = $routeId AND business_date = '$day' AND voided_at IS NULL"))
        assertEquals(0, count("SELECT count(*) FROM app.visit WHERE route_id = $routeId AND business_date = '$day' AND voided_at IS NULL"))
        assertEquals("voided", fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT status FROM app.ingest_registry WHERE client_uuid = CAST(:u AS uuid)").bind("u", memo).mapTo(String::class.java).one() })
        // The ledger is append-only: the open due is taken off by an adjustment, so the outlet balance falls by exactly that memo's 500.
        assertEquals(dueBefore - 500, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outletId"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'data_void' AND entity_id = '$cu' AND reason IS NOT NULL"))
        assertEquals(1, count("SELECT count(*) FROM app.domain_event WHERE event_type = 'memo.voided' AND aggregate_id = '$memo'"))

        // A replay answers the same and changes nothing; the same uuid for another day conflicts.
        val again = client.voidDay(admin, cu)
        assertEquals(out["tombstoned_records"], json(again.bodyAsText())["tombstoned_records"]); assertEquals(out["voided_at"], json(again.bodyAsText())["voided_at"])
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'data_void' AND entity_id = '$cu'"))
        assertEquals(HttpStatusCode.Conflict, client.voidDay(admin, cu, date = "2027-01-02").status)

        // A resend of a voided row is a duplicate (tombstone stays); a NEW row captured before the void is refused voided_by_admin.
        assertEquals("duplicate", statuses(json(client.send(sr, batch(family.take(1))).bodyAsText()))[0])
        val late = saleFamily()
        val lateAcks = json(client.send(sr, batch(listOf(late[0]))).bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject }
        assertEquals("rejected", lateAcks[0]["status"]!!.jsonPrimitive.content, lateAcks.toString())
        assertEquals("voided_by_admin", lateAcks[0]["code"]!!.jsonPrimitive.content)
        assertEquals(0, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${late[0]["client_uuid"]!!.jsonPrimitive.content}'"))
    }

    @Test
    fun aVoidIsRefusedAfterFinalSubmitOutsideReachAndForTheWrongRole() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("tso1001")
        assertEquals(HttpStatusCode.Forbidden, client.voidDay(client.webToken("dmo1001"), uuid()).status)
        assertEquals(HttpStatusCode.Forbidden, client.voidDay(admin, uuid(), scope = "web_entry", route = 999999).status)
        val otherRoute = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createUpdate("INSERT INTO app.route (code, name, zone_id, kind, visit_days_mask) SELECT 'RT-OTH', 'Other', id, 'sr', 127 FROM app.zone WHERE code = 'Z-OTHER'").execute()
            h.createQuery("SELECT id FROM app.route WHERE code = 'RT-OTH'").mapTo(Long::class.java).one()
        }
        assertEquals(HttpStatusCode.Forbidden, client.voidDay(admin, uuid(), scope = "web_entry", route = otherRoute).status, "a TSO cannot void outside the own territory")
        fresh.db.jdbi.useHandle<Exception> { h ->
            val sub = h.createQuery("SELECT id FROM app.app_user WHERE username = 'tso1001'").mapTo(Long::class.java).one()
            h.createUpdate("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, via) SELECT CAST(:u AS uuid), zone_id, DATE '2027-01-02', :s, 'web' FROM app.route WHERE id = :r")
                .bind("u", uuid()).bind("s", sub).bind("r", routeId).execute()
        }
        val r = client.voidDay(admin, uuid(), scope = "web_entry", date = "2027-01-02")
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText()); assertEquals("ERR_DAY_ALREADY_FINAL_SUBMITTED", json(r.bodyAsText())["code"]?.jsonPrimitive?.content ?: json(r.bodyAsText())["type"]?.jsonPrimitive?.content?.substringAfterLast('/'))
        assertEquals(HttpStatusCode.BadRequest, client.voidDay(admin, uuid(), scope = "bogus").status)
    }
}
