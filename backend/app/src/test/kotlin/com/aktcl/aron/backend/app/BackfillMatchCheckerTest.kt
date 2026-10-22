package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent T1 checker of POST /v1/admin/quarantine/{id}/match-backfill: one transaction with the audit row, reach
 * derived on the server, concurrency, validation and contract shape. Setup copied from [BackfillMatchTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackfillMatchCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keyFile: File
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val memoSeq = java.util.concurrent.atomic.AtomicInteger(500)
    private val captureSeq = java.util.concurrent.atomic.AtomicInteger(0)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            // A narrow-reach ADMIN and SUPPORT: zone Z-OTHER only (not the SR's Mirpur zone).
            for (u in listOf("admin2002" to "ADMIN", "support2002" to "SUPPORT")) {
                h.execute(
                    "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) " +
                        "SELECT '${u.first}', 'Narrow ${u.second}', '${u.second}', 'Narrow', 'T-${u.first}', 'en', id, true, false FROM app.zone WHERE code = 'Z-OTHER'",
                )
            }
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT u.id, 'zone', z.id, DATE '2026-01-01', 'test' FROM app.app_user u, app.zone z WHERE u.username IN ('admin2002', 'support2002') AND z.code = 'Z-OTHER'")
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            // D-ROLES-02: no portal login for SR/AMO; amo1001 calls from an enrolled AMO phone bound to it (as ApiWorld.AMO_PHONE).
            h.execute("INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version) SELECT '${ApiWorld.AMO_PHONE}', 'amo', 'com.aktcl.aron.amo', 'active', device_owner, lockdown_level, 'normal', public_key_jwk, 'qa-amo-000d', attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version FROM app.device WHERE device_uuid = '${ApiWorld.SR_PHONE}'")
            h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u WHERE d.device_uuid = '${ApiWorld.AMO_PHONE}' AND u.username = 'amo1001'")
            outletId = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = :r").bind("r", routeId).mapTo(Long::class.java).one()
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test rl' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'test: admins log in without MFA' FROM app.cfg_version")
            // An audit failure on demand: a reason starting FAIL-AUDIT makes the backfill_matched audit insert raise.
            h.execute(
                "CREATE FUNCTION app.test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
                    "IF NEW.action = 'backfill_matched' AND NEW.reason LIKE 'FAIL-AUDIT%' THEN RAISE EXCEPTION 'forced audit failure'; END IF; RETURN NEW; END \$\$",
            )
            h.execute("CREATE TRIGGER aaa_test_fail_audit BEFORE INSERT ON app.audit_log FOR EACH ROW EXECUTE FUNCTION app.test_fail_audit()")
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
    private val webTokens = HashMap<String, String>()

    private suspend fun HttpClient.token(): String = cachedToken ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { cachedToken = it }
    }

    private suspend fun HttpClient.webToken(user: String): String = webTokens[user] ?: run {
        val r = post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$password","client":"web"}""") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { webTokens[user] = it }
    }

    /** D-ROLES-02: no portal login for SR/AMO; the AMO's token comes from its AMO phone. */
    private suspend fun HttpClient.amoPhoneToken(): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"amo1001","password":"$password","client":"app_amo","device_uuid":"${ApiWorld.AMO_PHONE}"}""")
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

    private suspend fun HttpClient.send(token: String, body: String): HttpResponse = post("/v1/sync/batch") {
        bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9")
        header("Content-Encoding", "gzip"); setBody(io.ktor.http.content.ByteArrayContent(gz(body), ContentType.Application.Json))
    }

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:41:00.120Z"); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); put("route_id", routeId); put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private fun saleFamily(memoNo: String): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
        val g1 = 20 * p1; val g2 = 10 * p2; val gross = g1 + g2
        val visitPayload = Json.parseToJsonElement(
            """
            {"visit_kind":"sr_call","outlet_id":$outletId,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":1,"planned":true,
             "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                    "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
             "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
            """.trimIndent(),
        ).jsonObject
        val memoPayload = Json.parseToJsonElement(
            """
            {"visit_client_uuid":"$visit","outlet_id":$outletId,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"2027-01-03T03:43:00.120Z",
             "price_list_date":"$day","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
             "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":2,"discount_line_count":0,"qc_line_count":0,
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
        val at = JsonPrimitive(Instant.parse("2027-01-02T18:30:00Z").plusSeconds(captureSeq.incrementAndGet() * 60L).toString())
        return listOf(
            envelope("visit", visit, visit, 0, visitPayload),
            envelope("memo", memo, visit, 1, memoPayload),
            envelope("memo_line", uuid(), visit, 2, line(1, s1, 20, p1, g1)),
            envelope("memo_line", uuid(), visit, 2, line(2, s2, 10, p2, g2)),
            envelope("visit_close", uuid(), visit, 1, close),
        ).map { JsonObject(it + ("captured_at" to at)) }.map { r ->
            if (r["type"]!!.jsonPrimitive.content == "memo") JsonObject(r + ("payload" to JsonObject(r["payload"]!!.jsonObject + ("committed_at" to at)))) else r
        }
    }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun str(sql: String): String = fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).one() }
    private fun status(qid: Long): String = str("SELECT status FROM app.sync_quarantine WHERE id = $qid")
    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }

    private suspend fun HttpClient.key(token: String, memoNo: String): HttpResponse = post("/v1/admin/data-entry") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        val gross = 20 * skus[0].second + 10 * skus[1].second
        setBody("""{"client_uuid":"${uuid()}","memo_no":"$memoNo","business_date":"$day","user_id":${count("SELECT id FROM app.app_user WHERE username = 'sr1001'")},"route_id":$routeId,"outlet_id":$outletId,
            "lines":[{"sku_id":${skus[0].first},"qty_base":20,"line_kind":"sale"},{"sku_id":${skus[1].first},"qty_base":10,"line_kind":"sale"}],"paid_mtk":$gross,"reason":"Phone died, keyed from the printed memo"}""")
    }

    private suspend fun HttpClient.match(token: String, id: Any, body: String = """{"reason":"Same printed memo, phone came back"}"""): HttpResponse =
        post("/v1/admin/quarantine/$id/match-backfill") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private class Held(val qid: Long, val phoneMemo: String, val paper: String, val memoNo: String, val family: List<JsonObject>)

    /** Keys a paper memo, then uploads the same memo from the phone ([strip] removes envelope members, e.g. route_id). */
    private suspend fun HttpClient.held(strip: Set<String> = emptySet()): Held {
        val sr = token(); val admin = webToken("admin1001")
        val no = "sr1001-270103-${memoSeq.incrementAndGet()}"
        assertEquals(HttpStatusCode.OK, key(admin, no).status)
        val family = saleFamily(no).map { JsonObject(it - strip) }
        val phoneMemo = family[1]["client_uuid"]!!.jsonPrimitive.content
        val r = send(sr, batch(family)); assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val paper = str("SELECT client_uuid::text FROM app.memo WHERE memo_no = '$no' AND entry_source = 'manual'")
        val qid = count("SELECT COALESCE(max(id), 0) FROM app.sync_quarantine WHERE client_uuid = CAST('$phoneMemo' AS uuid) AND code = 'memo_no_duplicate'")
        assertTrue(qid > 0, "the phone memo is held memo_no_duplicate: " + r.bodyAsText())
        return Held(qid, phoneMemo, paper, no, family)
    }

    private fun audits(phoneMemo: String) = count("SELECT count(*) FROM app.audit_log WHERE action = 'backfill_matched' AND after->>'phone_memo_uuid' = '$phoneMemo'")

    @Test
    fun anAuditFailureRollsTheWholeMatchBack() = testApplication {
        app()
        val admin = client.webToken("admin1001")
        val x = client.held()
        val r = client.match(admin, x.qid, """{"reason":"FAIL-AUDIT same printed memo"}""")
        assertTrue(r.status.value >= 500, "a failed audit write fails the request: ${r.status} ${r.bodyAsText()}")
        assertEquals("open", status(x.qid), "the item stays open")
        assertEquals("quarantined", str("SELECT status FROM app.ingest_registry WHERE client_uuid = CAST('${x.phoneMemo}' AS uuid)"))
        assertEquals(0, count("SELECT count(*) FROM app.visit v JOIN app.memo m ON m.visit_client_uuid = v.client_uuid WHERE m.client_uuid = CAST('${x.paper}' AS uuid) AND v.voided_at IS NOT NULL"), "the paper visit is not voided")
        assertEquals(0, count("SELECT count(*) FROM app.sync_quarantine WHERE resolution_note LIKE 'same_as_backfill%' AND status <> 'open' AND client_uuid IN (SELECT (r->>'client_uuid')::uuid FROM jsonb_array_elements(CAST('${kotlinx.serialization.json.JsonArray(x.family)}' AS jsonb)) r)"), "no child closed")
        val ok = client.match(admin, x.qid)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals(1, audits(x.phoneMemo))
    }

    @Test
    fun reachIsFromTheServerAndOutOfReachLooksUnknown() = testApplication {
        app()
        val x = client.held()
        // SUPPORT, ADMIN and SUPERADMIN are national by role (ReachCalculator.nationalRoles), whatever user_scope says: a
        // zone-scoped ADMIN still reaches every route, so the endpoint's reach check can never narrow a permitted caller.
        assertEquals(HttpStatusCode.NotFound, client.match(client.webToken("admin2002"), 987654321).status)
        val phone = client.post("/v1/admin/quarantine/${x.qid}/match-backfill") {
            bearerAuth(client.token()); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9")
            contentType(ContentType.Application.Json); setBody("""{"reason":"Same printed memo, phone came back"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, phone.status, "a phone token: ${phone.bodyAsText()}")
        for (u in listOf("dmo1001", "tso1001")) assertEquals(HttpStatusCode.Forbidden, client.match(client.webToken(u), x.qid).status, u)
        // D-ROLES-02: no portal login for SR/AMO; the AMO, from its phone, cannot match either.
        val amo = client.post("/v1/admin/quarantine/${x.qid}/match-backfill") {
            bearerAuth(client.amoPhoneToken()); header("X-Device-Id", ApiWorld.AMO_PHONE); header("X-App-Version", "1.0.9+9")
            contentType(ContentType.Application.Json); setBody("""{"reason":"Same printed memo, phone came back"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, amo.status, "amo1001 from its phone: ${amo.bodyAsText()}")
        assertEquals("open", status(x.qid))
        assertEquals(0, audits(x.phoneMemo))
        assertEquals(HttpStatusCode.OK, client.match(client.webToken("support1001"), x.qid).status, "national SUPPORT")
    }

    @Test
    fun aHeldMemoWithoutARouteOnItsEnvelopeCanStillBeMatchedByANationalReviewer() = testApplication {
        app()
        // RecordEnvelope.route_id is optional in the contract; the SR, outlet and paper memo are server-side facts.
        val x = client.held(strip = setOf("route_id"))
        assertEquals(0, count("SELECT count(*) FROM app.sync_quarantine WHERE id = ${x.qid} AND route_id IS NOT NULL"), "the item has no route")
        val r = client.match(client.webToken("superadmin1001"), x.qid)
        assertEquals(HttpStatusCode.OK, r.status, "a national SUPERADMIN cannot resolve an item without route_id: ${r.bodyAsText()}")
    }

    @Test
    fun validationIsA400AndChangesNothingAndTheResponseIsTheContractShape() = testApplication {
        app()
        val admin = client.webToken("admin1001")
        val x = client.held()
        val bad = listOf(
            "{\"reason\":\"Same printed\\u0000 memo\"}", """{"reason":"short"}""", """{"reason":"          x         "}""",
            """{"reason":"${"x".repeat(501)}"}""", """{"reason":"Same printed memo, phone back","extra":1}""", """{}""", """{"reason":null}""", """not json""",
        )
        for (b in bad) assertEquals(HttpStatusCode.BadRequest, client.match(admin, x.qid, b).status, b)
        for (p in listOf("0", "-1", "abc", "99999999999999999999", "1.5")) assertEquals(HttpStatusCode.BadRequest, client.match(admin, p).status, p)
        assertEquals("open", status(x.qid))
        val ok = client.match(admin, x.qid)
        assertEquals(HttpStatusCode.OK, ok.status)
        val o = json(ok.bodyAsText())
        assertEquals(setOf("paper_memo_id", "paper_memo_uuid", "released", "kept_open", "voided_paper_visit_uuid", "phone_visit_uuid"), o.keys, "exactly the contract's fields")
        val uuidRe = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        (listOf(o["paper_memo_uuid"]!!, o["voided_paper_visit_uuid"]!!, o["phone_visit_uuid"]!!) + o["released"]!!.jsonArray + o["kept_open"]!!.jsonArray)
            .forEach { assertTrue(uuidRe.matches(it.jsonPrimitive.content), "uuid $it") }
        val a = json(str("SELECT jsonb_build_object('before', before, 'after', after, 'reason', reason, 'entity_id', entity_id)::text FROM app.audit_log WHERE action = 'backfill_matched' AND after->>'phone_memo_uuid' = '${x.phoneMemo}'"))
        assertEquals(x.paper, a["entity_id"]!!.jsonPrimitive.content)
        assertEquals(o["released"], a["after"]!!.jsonObject["released"])
        assertEquals(o["voided_paper_visit_uuid"], a["after"]!!.jsonObject["voided_paper_visit_uuid"])
        assertEquals("Same printed memo, phone came back", a["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun simultaneousMatchesOneWinsOneAuditRowNo500() = testApplication {
        app()
        val admin = client.webToken("admin1001"); val sup = client.webToken("support1001")
        repeat(3) {
            val x = client.held()
            val rs = withTimeout(60_000) {
                coroutineScope { listOf(admin, sup, admin).map { t -> async(Dispatchers.IO) { client.match(t, x.qid) } }.map { it.await() } }
            }
            val desc = rs.map { "${it.status} ${it.bodyAsText()}" }.toString()
            assertEquals(1, rs.count { it.status == HttpStatusCode.OK }, desc)
            assertEquals(2, rs.count { it.status == HttpStatusCode.Conflict }, desc)
            rs.filter { it.status == HttpStatusCode.Conflict }.forEach { assertEquals("ERR_REQUEST_STATE", json(it.bodyAsText())["code"]!!.jsonPrimitive.content) }
            assertEquals(1, audits(x.phoneMemo))
        }
    }

    @Test
    fun aMatchRacingThePhoneResendNeverDoublesTheMemo() = testApplication {
        app()
        val admin = client.webToken("admin1001"); val sr = client.token()
        repeat(3) {
            val x = client.held()
            val rs = withTimeout(60_000) {
                coroutineScope {
                    val m = async(Dispatchers.IO) { client.match(admin, x.qid) }
                    val s = async(Dispatchers.IO) { client.send(sr, batch(x.family)) }
                    listOf(m.await(), s.await())
                }
            }
            assertTrue(rs.none { it.status.value >= 500 }, rs.map { "${it.status} ${it.bodyAsText()}" }.toString())
            assertEquals(HttpStatusCode.OK, rs[0].status, rs[0].bodyAsText())
            assertEquals(1, count("SELECT count(*) FROM app.memo WHERE memo_no = '${x.memoNo}'"))
            assertEquals(1, audits(x.phoneMemo))
        }
    }
}
