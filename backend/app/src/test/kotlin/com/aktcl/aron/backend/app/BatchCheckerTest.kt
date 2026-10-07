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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent checker (T1) for F-API-006 POST /v1/sync/batch. Each test is a reproduction of a defect against
 * docs/24 s3.3, s4.1 to s4.6 and s8.4; it fails while the defect is there. Same seed, wiring and clock as
 * BatchAcceptanceTest (Sunday 2027-01-03 10:00 Dhaka, sr1001 on the dev phone).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchCheckerTest {
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
    private var otherRouteId = 0L
    private var otherUserId = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val memoSeq = java.util.concurrent.atomic.AtomicInteger(100)

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
            otherUserId = h.createQuery("SELECT id FROM app.app_user WHERE username = 'amo1001'").mapTo(Long::class.java).one()
            // Another territory, zone, route and outlet: outside sr1001's reach.
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            h.execute("INSERT INTO app.cluster (zone_id, name) SELECT id, 'Other cluster' FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'OTH-SR-A', 'Other route', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z-OTHER'")
            otherRouteId = h.createQuery("SELECT id FROM app.route WHERE code = 'OTH-SR-A'").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel) SELECT 'OTH-1', 'Other 1', 'Owner', z.id, c.id, 'GT' FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id WHERE z.code = 'Z-OTHER'")
            otherOutletId = h.createQuery("SELECT id FROM app.outlet WHERE code = 'OTH-1'").mapTo(Long::class.java).one()
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            // The fixed clock never empties a rate-limit window: raise the limits so the checks are not throttled.
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'checker rl' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.api.rl.device_per_min', 'global', 0, '600'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.api.rl.user_per_min', 'global', 0, '2000'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
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
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private suspend fun HttpClient.token(): String {
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
        put("time_anchors", JsonArray(emptyList()))
        put("device_counts", buildJsonObject { put(day, buildJsonObject { put("visit", 1) }) })
        put("records", JsonArray(records))
    }.toString()

    private suspend fun HttpClient.send(token: String, body: String): HttpResponse = post("/v1/sync/batch") {
        bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9")
        header("Content-Encoding", "gzip"); setBody(io.ktor.http.content.ByteArrayContent(gz(body), ContentType.Application.Json))
    }

    private suspend fun HttpClient.ok(token: String, body: String): JsonObject {
        val r = send(token, body)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())
    }

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, route: Long? = routeId) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:41:00.120Z"); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private val fix = """{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    private fun line(memo: String, no: Int, sku: Long, qty: Int, price: Long) = Json.parseToJsonElement(
        """
        {"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale","qty_entered":$qty,"unit_entered":"stick","pack_factor":1,"qty_base":$qty,
         "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":${qty * price}}
        """.trimIndent(),
    ).jsonObject

    /** A sale family: visit, memo with two lines, visit close (the same shape as BatchAcceptanceTest). */
    private fun saleFamily(outlet: Long = outletId, memoNo: String = "sr1001-270103-${memoSeq.incrementAndGet()}"): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
        val gross = 20 * p1 + 10 * p2
        val visitPayload = Json.parseToJsonElement(
            """
            {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":1,"planned":true,"fix":$fix,
             "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
            """.trimIndent(),
        ).jsonObject
        val memoPayload = Json.parseToJsonElement(
            """
            {"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"2027-01-03T03:43:00.120Z",
             "price_list_date":"$day","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
             "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":2,"discount_line_count":0,"qc_line_count":0,
             "offer_version_ids":[],"rounding_mode":"half_up_paisa"}
            """.trimIndent(),
        ).jsonObject
        val close = Json.parseToJsonElement("""{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"2027-01-03T03:43:10.000Z","is_zero_sale":false}""").jsonObject
        val at = JsonPrimitive(Instant.parse("2027-01-03T03:00:00Z").plusMillis(captureSeq.incrementAndGet().toLong()).toString())
        return listOf(
            envelope("visit", visit, visit, 0, visitPayload),
            envelope("memo", memo, visit, 1, memoPayload),
            envelope("memo_line", uuid(), visit, 2, line(memo, 1, s1, 20, p1)),
            envelope("memo_line", uuid(), visit, 2, line(memo, 2, s2, 10, p2)),
            envelope("visit_close", uuid(), visit, 1, close),
        ).map { JsonObject(it + ("captured_at" to at)) }
    }

    /** Each family is its own capture (the content fingerprint of F-SYS-055 tells families apart by captured_at). */
    private val captureSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private fun statuses(r: JsonObject) = r["acks"]!!.jsonArray.map { it.jsonObject.s("status") }

    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }

    /**
     * Another user's open sale on a route and outlet outside sr1001's reach (as stored by that user's phone): sr1001
     * uploads visit, memo and lines, then the rows are moved to amo1001, OTH-SR-A and OTH-1 with triggers off.
     * Returns (visit client_uuid, memo client_uuid, memo_no).
     */
    private suspend fun HttpClient.foreignSale(token: String): Triple<String, String, String> {
        val fam = saleFamily().dropLast(1) // no visit_close: the visit stays open
        assertEquals(List(4) { "accepted" }, statuses(ok(token, batch(fam))))
        val visit = fam[0].s("client_uuid"); val memo = fam[1].s("client_uuid")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET session_replication_role = replica")
            try {
                h.execute("UPDATE app.visit SET user_id = $otherUserId, route_id = $otherRouteId, outlet_id = $otherOutletId WHERE client_uuid = '$visit'")
                h.execute("UPDATE app.memo SET user_id = $otherUserId, route_id = $otherRouteId, outlet_id = $otherOutletId WHERE client_uuid = '$memo'")
                h.execute("UPDATE app.memo_line SET user_id = $otherUserId, route_id = $otherRouteId WHERE memo_client_uuid = '$memo'")
                h.execute("UPDATE app.ingest_registry SET user_id = $otherUserId WHERE family_uuid = '$visit'")
            } finally {
                h.execute("SET session_replication_role = origin")
            }
        }
        return Triple(visit, memo, fam[1]["payload"]!!.jsonObject.s("memo_no"))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Scope (s4.1 item 3, s8.4): child records reach other users' rows through their parent reference.

    @Test
    fun checkerVisitCloseCannotCloseAnotherUsersVisitOutsideReach() = testApplication {
        app()
        val token = client.token()
        val (visit, _, _) = client.foreignSale(token)
        val close = Json.parseToJsonElement("""{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"2027-01-03T03:50:00.000Z","is_zero_sale":false}""").jsonObject
        // sr1001's own route on the envelope: the scope check passes on the envelope, the target is someone else's visit.
        val r = client.ok(token, batch(listOf(envelope("visit_close", uuid(), visit, 1, close))))
        assertNotEquals("accepted", statuses(r).single(), "sr1001 closed amo1001's visit on OTH-SR-A: $r")
        assertEquals(0, count("SELECT count(*) FROM app.visit WHERE client_uuid = '$visit' AND close_client_uuid IS NOT NULL"), "another user's visit was closed")
    }

    @Test
    fun checkerMemoLineCannotBeAddedToAnotherUsersMemo() = testApplication {
        app()
        val token = client.token()
        val (_, memo, _) = client.foreignSale(token)
        val (s1, p1) = skus[0]
        val netBefore = count("SELECT COALESCE(sum(gross_mtk), 0) FROM app.memo_line WHERE memo_client_uuid = '$memo'")
        // No route_id on the line (none is required): no scope check runs at all, only "parent exists".
        val r = client.ok(token, batch(listOf(envelope("memo_line", uuid(), uuid(), 2, line(memo, 3, s1, 500, p1), route = null))))
        assertNotEquals("accepted", statuses(r).single(), "a line was added to amo1001's memo on OTH-SR-A: $r")
        assertEquals(netBefore, count("SELECT COALESCE(sum(gross_mtk), 0) FROM app.memo_line WHERE memo_client_uuid = '$memo'"), "another user's memo lines changed")
    }

    @Test
    fun checkerMemoVoidCannotTargetAnotherUsersMemo() = testApplication {
        app()
        val token = client.token()
        val (_, memo, memoNo) = client.foreignSale(token)
        val void = Json.parseToJsonElement(
            """{"memo_client_uuid":"$memo","memo_no":"$memoNo","reason_code":"wrong_outlet","retailer_ack":true,"fix":${fix.replace("visit_open", "memo_void")}}""",
        ).jsonObject
        val cu = uuid()
        val r = client.ok(token, batch(listOf(envelope("memo_void", cu, cu, 0, void))))
        assertNotEquals("accepted", statuses(r).single(), "sr1001 voided amo1001's memo on OTH-SR-A: $r")
        assertEquals(0, count("SELECT count(*) FROM app.memo_void WHERE memo_client_uuid = '$memo'"))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Malformed records (s3.4 400 row, s4.4, s4.5): one bad record never fails its family's header or the batch.

    @Test
    fun checkerAMalformedVisitCloseDoesNotRollBackTheSaleItCloses() = testApplication {
        app()
        val token = client.token()
        val fam = saleFamily()
        // visit_client_uuid as a number: the parent check skips it (str() is null) and closeVisit hits `!!`.
        val badClose = JsonObject(fam[4] + ("payload" to JsonObject(fam[4]["payload"]!!.jsonObject + ("visit_client_uuid" to JsonPrimitive(5)))))
        val records = fam.dropLast(1) + badClose
        val r = client.ok(token, batch(records))
        assertEquals(List(4) { "accepted" }, statuses(r).take(4), "the visit, memo and lines must be stored; got $r")
        val closeAck = r["acks"]!!.jsonArray[4].jsonObject
        assertEquals("rejected", closeAck.s("status"))
        assertEquals("schema_invalid", closeAck.s("code"), "a malformed record is a final schema_invalid, not a retryable server_error: $closeAck")
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${fam[1].s("client_uuid")}'"), "the sale was not stored")
        // And a resend never gets better: the family is poisoned for good.
        val again = client.ok(token, batch(records))
        assertEquals(List(4) { "duplicate" }, statuses(again).take(4), "resend: $again")
    }

    @Test
    fun checkerAnOutOfRangeNumberInOneRecordDoesNotFailTheBatch() = testApplication {
        app()
        val token = client.token()
        val good = saleFamily()
        val cu = uuid()
        // A JSON number outside the double range: valid JSON, but Jcs.number() throws in the Rec constructor.
        val bad = Json.parseToJsonElement(envelope("visit_skip", cu, cu, 0, buildJsonObject { put("outlet_id", outletId); put("reason_code", "closed") }).toString()
            .replace("\"clock_offset_ms\":-1840", "\"clock_offset_ms\":1e400")).jsonObject
        val r = client.send(token, batch(good + bad))
        assertEquals(HttpStatusCode.OK, r.status, "one bad record failed the whole batch: ${r.status} ${r.bodyAsText().take(300)}")
        val b = json(r.bodyAsText())
        assertEquals(List(5) { "accepted" }, statuses(b).take(5))
        assertEquals("rejected", statuses(b)[5])
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Idempotency under concurrency (s3.3 item 1): same client_uuid, different hash is payload_conflict, never duplicate.

    @Test
    fun checkerConcurrentDifferentPayloadsUnderOneUuidAreNeverAckedDuplicate() = testApplication {
        app()
        val token = client.token()
        val bad = mutableListOf<String>()
        repeat(12) { i ->
            val v = saleFamily().first()
            val v2 = JsonObject(v + ("payload" to JsonObject(v["payload"]!!.jsonObject + ("sequence_no" to JsonPrimitive(2)))))
            val (a, b) = coroutineScope {
                val x = async { json(client.send(token, batch(listOf(v))).bodyAsText()) }
                val y = async { json(client.send(token, batch(listOf(v2))).bodyAsText()) }
                x.await() to y.await()
            }
            val pair = listOf(statuses(a).single(), statuses(b).single()).sorted()
            // Correct outcomes: one accepted and the other quarantined(payload_conflict).
            if (pair != listOf("accepted", "quarantined")) bad += "round $i: $pair"
        }
        assertTrue(bad.isEmpty(), "a different payload under a stored client_uuid was acked without quarantine (second copy lost): $bad")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ack order (s4.5): exactly one ack per request record, in request order, also on a replay.

    @Test
    fun checkerAReplayAnswersInTheRequestOrder() = testApplication {
        app()
        val token = client.token()
        val fam = saleFamily()
        val b = uuid()
        client.ok(token, batch(fam, b))
        val reordered = fam.reversed()
        val r = client.send(token, batch(reordered, b))
        if (r.status == HttpStatusCode.OK) {
            val acks = json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject.s("client_uuid") }
            assertEquals(reordered.map { it.s("client_uuid") }, acks, "replayed acks are not in the request order")
        } else {
            assertEquals(HttpStatusCode.Conflict, r.status)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Identity from the body (s4.1 item 3, s8.4): acting_for_user_id is a cover claim the server must check.

    @Test
    fun checkerActingForAnotherUserWithoutACoverIsNotTrusted() = testApplication {
        app()
        val token = client.token()
        val fam = saleFamily()
        val v = JsonObject(fam[0] + ("acting_for_user_id" to JsonPrimitive(otherUserId)))
        val r = client.ok(token, batch(listOf(v)))
        val stored = count("SELECT count(*) FROM app.visit WHERE client_uuid = '${v.s("client_uuid")}' AND acting_for_user_id = $otherUserId")
        assertEquals(0, stored, "a visit was stored as acting for amo1001 with no cover assignment: ${statuses(r)}")
    }
}
