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
class BatchAcceptanceTest {
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
        val at = JsonPrimitive(Instant.parse("2027-01-03T03:00:00Z").plusMillis(captureSeq.incrementAndGet().toLong()).toString())
        return listOf(
            envelope("visit", visit, visit, 0, visitPayload, outlet),
            envelope("memo", memo, visit, 1, memoPayload, outlet),
            envelope("memo_line", uuid(), visit, 2, line(1, s1, 20, p1, g1)),
            envelope("memo_line", uuid(), visit, 2, line(2, s2, 10, p2, g2)),
            envelope("visit_close", uuid(), visit, 1, close),
        ).map { JsonObject(it + ("captured_at" to at)) }
    }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private fun statuses(r: JsonObject) = r["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content }

    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }

    @Test
    fun aSaleFamilyIsStoredOnceAndARepeatedBatchReplaysTheStoredResponse() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily()
        val batchUuid = uuid()
        val body = batch(family, batchUuid)
        val r = client.send(token, body)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        assertEquals(listOf("accepted", "accepted", "accepted", "accepted", "accepted"), statuses(b), b.toString())
        assertEquals(false, b["replayed"]!!.jsonPrimitive.boolean)
        assertEquals(family.map { it["client_uuid"]!!.jsonPrimitive.content }, b["acks"]!!.jsonArray.map { it.jsonObject["client_uuid"]!!.jsonPrimitive.content }, "one ack per record, in order")
        val memoCu = family[1]["client_uuid"]!!.jsonPrimitive.content
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memoCu'"))
        // Identity from the token: the memo belongs to sr1001 and the dev phone.
        assertEquals(1, count("SELECT count(*) FROM app.memo m JOIN app.app_user u ON u.id = m.user_id JOIN app.device d ON d.id = m.device_id WHERE m.client_uuid = '$memoCu' AND u.username = 'sr1001' AND d.device_uuid = '$devPhone'"))
        assertEquals(1, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${family[0]["client_uuid"]!!.jsonPrimitive.content}' AND outcome_code = 'sold' AND verdict = 'in_range' AND fix_lat IS NOT NULL"))
        assertEquals(1, count("SELECT count(*) FROM app.geo_fix WHERE source_client_uuid = '${family[0]["client_uuid"]!!.jsonPrimitive.content}'"))
        // Server totals and day states.
        val totals = b["server_totals"]!!.jsonArray.map { it.jsonObject }.single { it["business_date"]!!.jsonPrimitive.content == day }
        // Other tests of this class sell on the same day: the totals equal the database, whatever ran before.
        val mine = "user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001') AND business_date = '$day'"
        assertEquals(count("SELECT count(*) FROM app.memo WHERE $mine"), totals["by_type"]!!.jsonObject["memo"]!!.jsonObject["accepted"]!!.jsonPrimitive.content.toLong())
        assertEquals(count("SELECT count(*) FROM app.memo_line WHERE $mine"), totals["by_type"]!!.jsonObject["memo_line"]!!.jsonObject["accepted"]!!.jsonPrimitive.content.toLong())
        assertEquals(count("SELECT count(*) FROM app.memo WHERE $mine AND status = 'active'"), totals["money"]!!.jsonObject["active_memo_count"]!!.jsonPrimitive.content.toLong())
        assertEquals(count("SELECT sum(net_mtk) FROM app.memo WHERE $mine AND status = 'active'"), totals["money"]!!.jsonObject["net_mtk"]!!.jsonPrimitive.content.toLong())
        assertTrue(b.containsKey("day_states") && b.containsKey("resolutions") && b.containsKey("generation"))
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(b["generation"]!!.jsonPrimitive.content))

        // The same batch again: the stored response, replayed, nothing written twice.
        val again = json(client.send(token, body).bodyAsText())
        assertEquals(true, again["replayed"]!!.jsonPrimitive.boolean)
        assertEquals(b["acks"], again["acks"])
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memoCu'"))
        // The same rows under a new batch_uuid: duplicates, still one memo.
        val dup = json(client.send(token, batch(family)).bodyAsText())
        assertEquals(List(5) { "duplicate" }, statuses(dup))
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memoCu'"))
        // The same batch_uuid with another record set: 409.
        val reused = client.send(token, batch(saleFamily(), batchUuid))
        assertEquals(HttpStatusCode.Conflict, reused.status)
        assertEquals("ERR_SYNC_BATCH_UUID_REUSED", json(reused.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun childrenBeforeTheirParentAreParkedAndAcceptedOnResend() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily()
        val lines = family.filter { it["type"]!!.jsonPrimitive.content == "memo_line" }
        val first = json(client.send(token, batch(lines)).bodyAsText())
        assertEquals(listOf("rejected", "rejected"), statuses(first))
        first["acks"]!!.jsonArray.forEach { a ->
            assertEquals("parent_missing", a.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals(true, a.jsonObject["retryable"]!!.jsonPrimitive.boolean)
        }
        assertEquals(2, count("SELECT count(*) FROM app.sync_rejected WHERE code = 'parent_missing' AND retryable AND parked_until IS NOT NULL AND client_uuid IN ('${lines[0]["client_uuid"]!!.jsonPrimitive.content}', '${lines[1]["client_uuid"]!!.jsonPrimitive.content}')"))
        val second = json(client.send(token, batch(family)).bodyAsText())
        assertEquals(List(5) { "accepted" }, statuses(second), second.toString())
        assertEquals(2, count("SELECT count(*) FROM app.sync_rejected WHERE stored_at IS NOT NULL AND client_uuid IN ('${lines[0]["client_uuid"]!!.jsonPrimitive.content}', '${lines[1]["client_uuid"]!!.jsonPrimitive.content}')"))
    }

    @Test
    fun aChangedPayloadUnderAStoredUuidIsQuarantinedAndTheFirstCopyStays() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily()
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(family)).bodyAsText())))
        val visit = family[0]
        val changed = JsonObject(visit + ("payload" to JsonObject(visit["payload"]!!.jsonObject + ("sequence_no" to JsonPrimitive(9)))))
        val r = json(client.send(token, batch(listOf(changed))).bodyAsText())
        assertEquals("quarantined", statuses(r).single())
        assertEquals("payload_conflict", r["acks"]!!.jsonArray[0].jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(1, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${visit["client_uuid"]!!.jsonPrimitive.content}' AND sequence_no = 1"))
        assertEquals(1, count("SELECT count(*) FROM app.sync_quarantine WHERE code = 'payload_conflict' AND client_uuid = '${visit["client_uuid"]!!.jsonPrimitive.content}'"))
    }

    @Test
    fun aReusedMemoNumberUnderAnotherUuidIsQuarantinedNeverAckedAsDuplicate() = testApplication {
        app()
        val token = client.token()
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(saleFamily(memoNo = "sr1001-270103-0999"))).bodyAsText())))
        val second = saleFamily(memoNo = "sr1001-270103-0999")
        val r = json(client.send(token, batch(second)).bodyAsText())
        val memoAck = r["acks"]!!.jsonArray[1].jsonObject
        assertEquals("quarantined", memoAck["status"]!!.jsonPrimitive.content, r.toString())
        assertEquals("memo_no_duplicate", memoAck["code"]!!.jsonPrimitive.content)
        assertEquals(1, count("SELECT count(*) FROM app.sync_quarantine WHERE code = 'memo_no_duplicate' AND client_uuid = '${second[1]["client_uuid"]!!.jsonPrimitive.content}'"))
    }

    @Test
    fun badRecordsAreAckedOneByOneAndNeverFailTheBatch() = testApplication {
        app()
        val token = client.token()
        val good = saleFamily()
        val unknownType = envelope("teleport", uuid(), uuid(), 0, buildJsonObject { })
        val badShape = envelope("visit_skip", uuid(), uuid(), 0, buildJsonObject { put("nonsense", 1) })
        val outOfScope = saleFamily(outlet = otherOutletId).first().let { v -> JsonObject(v - "route_id") }
        val oldDate = JsonObject(saleFamily().first() + ("business_date" to JsonPrimitive("2026-12-01")))
        val unknownOutlet = saleFamily(outlet = 99_999_999).first()
        val r = json(client.send(token, batch(good + listOf(unknownType, badShape, outOfScope, oldDate, unknownOutlet))).bodyAsText())
        val acks = r["acks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(10, acks.size)
        assertEquals(List(5) { "accepted" }, acks.take(5).map { it["status"]!!.jsonPrimitive.content })
        assertEquals(
            listOf("rejected:unknown_record_type", "rejected:schema_invalid", "quarantined:scope_out_of_reach", "quarantined:business_date_out_of_window", "rejected:unknown_outlet"),
            acks.drop(5).map { it["status"]!!.jsonPrimitive.content + ":" + it["code"]!!.jsonPrimitive.content },
        )
        val s = r["summary"]!!.jsonObject
        assertEquals(10, listOf("accepted", "duplicate", "rejected", "quarantined").sumOf { s[it]!!.jsonPrimitive.int }, "partition invariant")
    }

    @Test
    fun theBatchIsGzipOnlyAndAtMost500Records() = testApplication {
        app()
        val token = client.token()
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.send(token, batch(saleFamily()), gzip = false).status)
        val many = (1..501).map { envelope("visit_skip", uuid(), uuid(), 0, buildJsonObject { }) }
        val r = client.send(token, batch(many))
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertEquals("ERR_SYNC_BATCH_TOO_LARGE", json(r.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun aStormFromOneDeviceGets429WithRetryAfter() = testApplication {
        // Its own wiring: the limiter is per replica, and the fixed clock never empties its window.
        val own = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock)
        application { aronApi(own) }
        val token = client.login()
        val body = batch(saleFamily())
        val statuses = (1..45).map { client.send(token, body) }
        val limited = statuses.firstOrNull { it.status == HttpStatusCode.TooManyRequests }
        assertTrue(limited != null, "a storm is limited")
        assertTrue((limited.headers["Retry-After"]?.toIntOrNull() ?: 0) > 0)
        assertEquals("ERR_RATE_LIMITED", json(limited.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    // ---- F-SYS-055 batch replay and content fingerprint ---------------------------------------------------------

    /** The same family with every uuid re-minted (a phone that lost its outbox ids and resends). */
    private fun remint(family: List<JsonObject>): List<JsonObject> {
        val map = HashMap<String, String>()
        fun swap(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { swap(it.value) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { swap(it) })
            is JsonPrimitive -> if (e.isString && Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(e.content)) JsonPrimitive(map.getOrPut(e.content) { uuid() }) else e
            else -> e
        }
        return family.map { swap(it) as JsonObject }
    }

    @Test
    fun aReplayWithRegeneratedUuidsIsCaughtByTheContentFingerprint() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily()
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(family)).bodyAsText())))
        val outletMemos = count("SELECT count(*) FROM app.memo WHERE memo_no = '${family[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content}'")
        val r = json(client.send(token, batch(remint(family))).bodyAsText())
        val acks = r["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content + ":" + it.jsonObject["code"]!!.jsonPrimitive.contentOrNull() }
        assertEquals(listOf("quarantined:content_duplicate", "quarantined:content_duplicate", "quarantined:content_duplicate", "quarantined:content_duplicate", "quarantined:content_duplicate"), acks)
        assertEquals(outletMemos, count("SELECT count(*) FROM app.memo WHERE memo_no = '${family[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content}'"), "no second sale")
    }

    // ---- F-SYS-048 poison-row isolation ---------------------------------------------------------------------------

    @Test
    fun onePoisonRecordInABatchOf100IsServerErrorAndTheOther99AreAccepted() = testApplication {
        app()
        // A fault the server cannot classify: the database raises an internal error for one specific visit.
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("CREATE OR REPLACE FUNCTION app.test_poison() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.sequence_no = 999 THEN RAISE EXCEPTION 'poison' USING ERRCODE = 'XX000'; END IF; RETURN NEW; END $$")
            h.execute("DROP TRIGGER IF EXISTS test_poison ON app.visit")
            h.execute("CREATE TRIGGER test_poison BEFORE INSERT ON app.visit FOR EACH ROW EXECUTE FUNCTION app.test_poison()")
        }
        try {
            val token = client.token()
            val visits = (1..100).map { i ->
                val v = saleFamily().first()
                JsonObject(v + ("payload" to JsonObject(v["payload"]!!.jsonObject + ("sequence_no" to JsonPrimitive(if (i == 50) 999 else i)))))
            }
            val r = json(client.send(token, batch(visits)).bodyAsText())
            val acks = r["acks"]!!.jsonArray.map { it.jsonObject }
            assertEquals(99, acks.count { it["status"]!!.jsonPrimitive.content == "accepted" })
            val poison = acks[49]
            assertEquals("rejected", poison["status"]!!.jsonPrimitive.content)
            assertEquals("server_error", poison["code"]!!.jsonPrimitive.content)
            assertEquals(true, poison["retryable"]!!.jsonPrimitive.boolean)
            val cu = visits[49]["client_uuid"]!!.jsonPrimitive.content
            assertEquals(1, count("SELECT count(*) FROM app.sync_rejected WHERE client_uuid = '$cu' AND code = 'server_error' AND retryable"))
            assertEquals(1, count("SELECT count(*) FROM app.ingest_registry WHERE client_uuid = '$cu' AND status = 'parked'"))
            // Resent after the fault is gone, it is stored once.
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DROP TRIGGER test_poison ON app.visit") }
            assertEquals(listOf("accepted"), statuses(json(client.send(token, batch(listOf(visits[49]))).bodyAsText())))
            assertEquals(1, count("SELECT count(*) FROM app.sync_rejected WHERE client_uuid = '$cu' AND stored_at IS NOT NULL"))
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("DROP TRIGGER IF EXISTS test_poison ON app.visit") }
        }
    }

    // ---- F-SYS-014 invalid references are kept, never dropped ----------------------------------------------------

    @Test
    fun aMemoLineForAnUnknownSkuIsKeptForReviewAndTheOtherRowsAreAccepted() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily()
        val bad = family[3].let { l -> JsonObject(l + ("payload" to JsonObject(l["payload"]!!.jsonObject + ("sku_id" to JsonPrimitive(987_654_321))))) }
        val r = json(client.send(token, batch(family.take(3) + bad + family[4])).bodyAsText())
        val acks = r["acks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("accepted", "accepted", "accepted", "rejected", "accepted"), acks.map { it["status"]!!.jsonPrimitive.content })
        assertEquals("unknown_sku", acks[3]["code"]!!.jsonPrimitive.content)
        val cu = bad["client_uuid"]!!.jsonPrimitive.content
        assertEquals(1, count("SELECT count(*) FROM app.sync_rejected WHERE client_uuid = '$cu' AND code = 'unknown_sku' AND payload->'payload'->>'sku_id' = '987654321'"), "kept with its payload, never dropped")
        val totals = r["server_totals"]!!.jsonArray.map { it.jsonObject }.single { it["business_date"]!!.jsonPrimitive.content == day }
        assertTrue(totals["by_type"]!!.jsonObject["memo_line"]!!.jsonObject["rejected"]!!.jsonPrimitive.int >= 1, "reconciliation counts it")
    }


    // ---- F-SYS-062 server recompute ------------------------------------------------------------------------------

    @Test
    fun aMemoWhoseTotalDisagreesWithItsLinesIsQuarantinedWithItsLinesAndNothingIsStored() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily(headerGrossDelta = 1000) // header internally consistent, but gross != sum of lines
        val r = json(client.send(token, batch(family)).bodyAsText())
        val acks = r["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content + ":" + it.jsonObject["code"]!!.jsonPrimitive.contentOrNull() }
        assertEquals(listOf("accepted:null", "quarantined:arithmetic_mismatch", "quarantined:arithmetic_mismatch", "quarantined:arithmetic_mismatch", "accepted:null"), acks)
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${family[1]["client_uuid"]!!.jsonPrimitive.content}'"))
        assertEquals(0, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${family[1]["client_uuid"]!!.jsonPrimitive.content}'"))
        assertEquals(3, count("SELECT count(*) FROM app.sync_quarantine WHERE code = 'arithmetic_mismatch' AND (client_uuid = '${family[1]["client_uuid"]!!.jsonPrimitive.content}' OR payload->'payload'->>'memo_client_uuid' = '${family[1]["client_uuid"]!!.jsonPrimitive.content}')"))
        // A header whose own equation fails (net != gross - discounts) is quarantined too, never a schema rejection.
        val broken = saleFamily().let { f -> f.mapIndexed { i, rec -> if (i == 1) JsonObject(rec + ("payload" to JsonObject(rec["payload"]!!.jsonObject + ("net_mtk" to JsonPrimitive(10))))) else rec } }
        assertEquals("quarantined", statuses(json(client.send(token, batch(broken)).bodyAsText()))[1])
    }

    @Test
    fun aPriceMismatchIsFlaggedButThePrintedMemoIsStored() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily(priceDelta = 50)
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(family)).bodyAsText())))
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${family[1]["client_uuid"]!!.jsonPrimitive.content}' AND 'price_mismatch' = ANY(server_flags)"))
        val fair = saleFamily()
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(fair)).bodyAsText())))
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${fair[1]["client_uuid"]!!.jsonPrimitive.content}' AND server_flags IS NOT NULL AND cardinality(server_flags) > 0"))
    }

    @Test
    fun aFixOutsideBangladeshIsFlaggedAndTheVisitIsStored() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily(lat = 30.5)
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(family)).bodyAsText())))
        assertEquals(1, count("SELECT count(*) FROM app.risk_signal WHERE code = 'GEO_OUT_OF_BOUNDS' AND subject_type = 'visit' AND subject_id = '${family[0]["client_uuid"]!!.jsonPrimitive.content}'"))
        val inside = saleFamily()
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(inside)).bodyAsText())))
        assertEquals(0, count("SELECT count(*) FROM app.risk_signal WHERE subject_id = '${inside[0]["client_uuid"]!!.jsonPrimitive.content}'"))
    }


    // ---- F-SYS-060 dues ledger -------------------------------------------------------------------------------------

    private val fixJson = """{"purpose":"PURPOSE","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    private fun ledger(memo: String) = fresh.db.jdbi.withHandle<List<Pair<String, Long>>, Exception> { h ->
        h.createQuery("SELECT entry_kind, amount_mtk FROM app.due_ledger WHERE memo_client_uuid = CAST(:m AS uuid) ORDER BY id").bind("m", memo)
            .map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list()
    }

    @Test
    fun aCreditMemoACollectionAndAVoidEachAddOneLedgerRowAndReplaysAddNone() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily(dueMtk = 50_000)
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(family)).bodyAsText())))
        val memo = family[1]["client_uuid"]!!.jsonPrimitive.content
        val memoNo = family[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content
        assertEquals(listOf("memo_due" to 50_000L), ledger(memo))
        val cu = uuid()
        val collection = envelope("due_collection", cu, cu, 0, Json.parseToJsonElement(
            """{"outlet_id":$outletId,"against_memo_client_uuid":"$memo","against_memo_no":"$memoNo","against_memo_business_date":"$day","amount_mtk":20000,
               "is_full_settlement":false,"outstanding_before_mtk":50000,"payment_mode":"cash"}""",
        ).jsonObject)
        val r = json(client.send(token, batch(listOf(collection))).bodyAsText())
        assertEquals(listOf("accepted"), statuses(r), r.toString())
        assertEquals(listOf("memo_due" to 50_000L, "collection" to -20_000L), ledger(memo))
        assertEquals(listOf("duplicate"), statuses(json(client.send(token, batch(listOf(collection))).bodyAsText())))
        val vu = uuid()
        val void = envelope("memo_void", vu, vu, 0, Json.parseToJsonElement(
            """{"memo_client_uuid":"$memo","memo_no":"$memoNo","reason_code":"retailer_cancelled","retailer_ack":true,"fix":${fixJson.replace("PURPOSE", "memo_void")}}""",
        ).jsonObject)
        assertEquals(listOf("accepted"), statuses(json(client.send(token, batch(listOf(void))).bodyAsText())))
        assertEquals(listOf("memo_due" to 50_000L, "collection" to -20_000L, "memo_void" to -30_000L), ledger(memo))
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo' AND status = 'voided' AND voided_by_client_uuid = '$vu'"))
        // Server totals no longer count the voided memo.
        val totals = json(client.send(token, batch(listOf(void))).bodyAsText())["server_totals"]!!.jsonArray.map { it.jsonObject }.single { it["business_date"]!!.jsonPrimitive.content == day }
        assertEquals(count("SELECT count(*) FROM app.memo WHERE user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001') AND business_date = '$day' AND status = 'active'"),
            totals["money"]!!.jsonObject["active_memo_count"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun fifoAgeingBucketsSumToTheOutletBalance() {
        val outlet = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT max(id) FROM app.outlet WHERE route_id = :r").bind("r", routeId).mapTo(Long::class.java).one() }
        fresh.db.jdbi.useHandle<Exception> { h ->
            fun row(date: String, kind: String, amt: Long) = h.execute(
                "INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, source_client_uuid) VALUES (?, CAST(? AS date), ?, ?, gen_random_uuid())", outlet, date, kind, amt)
            row("2026-10-20", "opening_balance", 10_000)   // 75 days old
            row("2026-11-20", "opening_balance", 20_000)   // 44 days
            row("2026-12-20", "opening_balance", 30_000)   // 14 days
            row("2027-01-01", "opening_balance", 40_000)   // 2 days
            row("2027-01-02", "collection", -15_000)       // pays the 10,000 and 5,000 of the 20,000 (FIFO)
        }
        val a = fresh.db.jdbi.withHandle<com.aktcl.aron.backend.sync.DuesLedger.Ageing, Exception> { h -> com.aktcl.aron.backend.sync.DuesLedger.ageing(h, outlet, java.time.LocalDate.parse(day)) }
        assertEquals(listOf(40_000L, 30_000L, 15_000L, 0L), listOf(a.d0to7, a.d8to30, a.d31to60, a.d61plus))
        assertEquals(85_000L, a.balance)
        assertEquals(a.balance, a.d0to7 + a.d8to30 + a.d31to60 + a.d61plus + a.credit)
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
