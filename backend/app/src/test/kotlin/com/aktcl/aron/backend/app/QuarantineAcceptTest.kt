package com.aktcl.aron.backend.app

import kotlinx.serialization.json.long
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
import kotlinx.serialization.json.contentOrNull
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
 * `POST /v1/admin/quarantine/{id}/resolve` accept and accept_with_fix through the ingest hook
 * (`IngestService.acceptQuarantined`): the held record is stored once under its own uuid, user and device, its held
 * children follow, a resend is `duplicate`, money is never overridden, a payload conflict cannot be accepted.
 * Clock: Sunday 2027-01-03 10:00 Dhaka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuarantineAcceptTest {
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
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test rl' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test: no MFA' FROM app.cfg_version")
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

    private val tokens = HashMap<String, String>()

    /** One login per user and class: the login limiter never empties under the fixed clock. */
    private suspend fun HttpClient.token(user: String = "sr1001"): String = tokens.getOrPut(user) {
        val body = if (user == "sr1001") """{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}"""
        else """{"username":"$user","password":"$password","client":"web"}"""
        val r = post("/v1/auth/login") { contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9"); setBody(body) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
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
        provider: String = "fused",
        gnss: String? = null,
    ): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val (s1, p1x) = skus[0]; val (s2, p2x) = skus[1]
        val p1 = p1x + priceDelta; val p2 = p2x + priceDelta
        val g1 = 20 * p1; val g2 = 10 * p2; val gross = g1 + g2 + headerGrossDelta
        val visitPayload = Json.parseToJsonElement(
            """
            {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":1,"planned":true,
             "fix":{"purpose":"visit_open","fix_status":"ok","lat":$lat,"lng":90.36,"accuracy_m":12.0,"provider":"$provider","is_mock":false,"reused":false,${gnss?.let { "\"gnss\":$it," } ?: ""}
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

    private fun cu(r: JsonObject) = r["client_uuid"]!!.jsonPrimitive.content
    private fun item(clientUuid: String): Pair<Long, String> = fresh.db.jdbi.withHandle<Pair<Long, String>, Exception> { h ->
        h.createQuery("SELECT id, status FROM app.sync_quarantine WHERE client_uuid = CAST(:c AS uuid) ORDER BY id DESC LIMIT 1").bind("c", clientUuid)
            .map { rs, _ -> rs.getLong(1) to rs.getString(2) }.one()
    }
    private fun registry(clientUuid: String): String = fresh.db.jdbi.withHandle<String, Exception> { h ->
        h.createQuery("SELECT status || '/' || user_id || '/' || coalesce(device_id::text, '-') FROM app.ingest_registry WHERE client_uuid = CAST(:c AS uuid)").bind("c", clientUuid).mapTo(String::class.java).one()
    }

    private suspend fun HttpClient.resolve(id: Long, action: String, fixed: JsonObject? = null, user: String = "admin1001"): HttpResponse =
        post("/v1/admin/quarantine/$id/resolve") {
            bearerAuth(token(user)); header("X-App-Version", "1.0.9+9"); contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("action", action); put("reason", "checked against the printed memo with the TSO")
                fixed?.let { put("fixed_record", it) }
            }.toString())
        }

    @Test
    fun anOutOfScopeSaleIsAcceptedOnceWithItsHeldChildrenAndResendsAreDuplicates() = testApplication {
        app()
        val fam = saleFamily(outlet = otherOutletId)
        val first = json(client.send(client.token(), batch(fam)).bodyAsText())
        assertEquals(List(5) { "quarantined" }, statuses(first), first.toString())
        val (visit, memo, l1, l2, close) = fam.map { cu(it) }
        val registered = registry(visit).substringAfter("/")

        // The memo names a held visit: accepting it first changes nothing (409) and the item stays open.
        val early = client.resolve(item(memo).first, "accept")
        assertEquals(HttpStatusCode.Conflict, early.status, early.bodyAsText())
        assertEquals("open", item(memo).second)
        assertEquals(0L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))

        val v = client.resolve(item(visit).first, "accept")
        assertEquals(HttpStatusCode.OK, v.status, v.bodyAsText())
        assertEquals("accepted", json(v.bodyAsText())["status"]!!.jsonPrimitive.content)
        assertEquals("accepted", item(close).second, "the visit close was held only for its visit")
        assertEquals("open", item(memo).second, "the memo was held for its own outlet: its own decision")
        // A held child keeps its own uploader (B1): pretend the first line came from the TSO's upload.
        val tso = count("SELECT id FROM app.app_user WHERE username = 'tso1001'")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.sync_quarantine SET user_id = ?, device_id = NULL WHERE client_uuid = CAST(? AS uuid)", tso, l1)
            h.execute("UPDATE app.ingest_registry SET user_id = ?, device_id = NULL WHERE client_uuid = CAST(? AS uuid)", tso, l1)
        }
        val m = client.resolve(item(memo).first, "accept")
        assertEquals(HttpStatusCode.OK, m.status, m.bodyAsText())
        for (c in listOf(visit, memo, l2, close)) assertEquals("accepted/$registered", registry(c), "same uuid, user and device: $c")
        // The reviewer accepted the SR's memo, not the TSO's line: it runs as the TSO with no override, and a line on
        // another user's memo is outside the TSO's own rules, so it stays held for its own review.
        assertEquals("open", item(l1).second)
        assertEquals("quarantined/$tso/-", registry(l1))
        assertEquals(0L, count("SELECT count(*) FROM app.memo_line WHERE client_uuid = '$l1'"))
        assertEquals("accepted", item(l2).second)
        assertEquals(1L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
        assertEquals(1L, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$memo'"))

        // The phone resends (a new batch): every record is a duplicate; nothing is stored twice.
        val again = json(client.send(client.token(), batch(fam.filter { cu(it) != l1 })).bodyAsText())
        assertEquals(List(4) { "duplicate" }, statuses(again), again.toString())
        assertEquals(1L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
        // The same decision again is the stored answer; another one is a state conflict.
        assertEquals(HttpStatusCode.OK, client.resolve(item(visit).first, "accept").status)
        assertEquals(HttpStatusCode.Conflict, client.resolve(item(visit).first, "discard").status)
    }

    @Test
    fun moneyIsNeverOverriddenAndAFixThatReproducesIsStored() = testApplication {
        app()
        val fam = saleFamily(headerGrossDelta = 1)
        val first = json(client.send(client.token(), batch(fam)).bodyAsText())
        assertEquals(listOf("accepted", "quarantined", "quarantined", "quarantined", "accepted"), statuses(first), first.toString())
        val memoRec = fam[1]; val memo = cu(memoRec)
        val plain = client.resolve(item(memo).first, "accept")
        assertEquals(HttpStatusCode.Conflict, plain.status, plain.bodyAsText())
        assertEquals("open", item(memo).second)
        assertEquals(0L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))

        // docs/24 s8.6: a money fix needs a second person, which the contract cannot carry yet: refused, nothing changes.
        val p = memoRec["payload"]!!.jsonObject
        val gross = p["gross_mtk"]!!.jsonPrimitive.long
        val fix = JsonObject(memoRec + ("payload" to JsonObject(p + mapOf("gross_mtk" to JsonPrimitive(gross - 1), "net_mtk" to JsonPrimitive(gross - 1), "paid_mtk" to JsonPrimitive(gross - 1)))))
        val one = client.resolve(item(memo).first, "accept_with_fix", fix)
        assertEquals(HttpStatusCode.Conflict, one.status, one.bodyAsText())
        assertEquals("open", item(memo).second)
        assertEquals(0L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
    }

    @Test
    fun aFixKeepsItsIdentityAndThePhonesOriginalIsADuplicateAfterIt() = testApplication {
        app()
        val fam = saleFamily(outlet = otherOutletId)
        assertEquals(List(5) { "quarantined" }, statuses(json(client.send(client.token(), batch(fam)).bodyAsText())))
        val visitRec = fam[0]
        val vp = visitRec["payload"]!!.jsonObject
        val moved = JsonObject(visitRec + ("payload" to JsonObject(vp + ("outlet_id" to JsonPrimitive(outletId)))))
        assertEquals(HttpStatusCode.BadRequest, client.resolve(item(cu(visitRec)).first, "accept_with_fix", moved).status, "a fix never moves the outlet")
        val renamed = JsonObject(visitRec + ("client_uuid" to JsonPrimitive(uuid())))
        assertEquals(HttpStatusCode.BadRequest, client.resolve(item(cu(visitRec)).first, "accept_with_fix", renamed).status)
        val fixed = JsonObject(visitRec + ("payload" to JsonObject(vp + ("sequence_no" to JsonPrimitive(7)))))
        val r = client.resolve(item(cu(visitRec)).first, "accept_with_fix", fixed)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("accepted_with_fix", item(cu(visitRec)).second)
        val again = json(client.send(client.token(), batch(listOf(visitRec))).bodyAsText())
        assertEquals(listOf("duplicate"), statuses(again), "the phone's own copy of the fixed record: $again")
    }

    @Test
    fun anItemHeldForAnotherReasonIsStillAcceptedAfterTheWindowPassed() = testApplication {
        app()
        val fam = saleFamily(outlet = otherOutletId)
        assertEquals(List(5) { "quarantined" }, statuses(json(client.send(client.token(), batch(fam)).bodyAsText())))
        val visit = cu(fam[0])
        // Reviewed 12 days later (after a holiday): the record was inside the window when it arrived.
        val later = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)),
            AronClock { Instant.parse("2027-01-15T04:00:00Z") })
        tokens.remove("admin1001") // the cached token expired under the later clock
        try {
            testApplication {
                application { aronApi(later) }
                val res = client.resolve(item(visit).first, "accept")
                assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            }
        } finally { later.database?.close(); tokens.remove("admin1001") }
        assertEquals("accepted", item(visit).second)
    }

    @Test
    fun aPayloadConflictCopyCannotBeAccepted() = testApplication {
        app()
        val fam = saleFamily()
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(client.token(), batch(fam)).bodyAsText())))
        val close = fam[4]
        val changed = JsonObject(close + ("payload" to JsonObject(close["payload"]!!.jsonObject + ("ended_at" to JsonPrimitive("2027-01-03T03:44:10.000Z")))))
        val r = json(client.send(client.token(), batch(listOf(changed))).bodyAsText())
        assertEquals(listOf("quarantined"), statuses(r))
        val res = client.resolve(item(cu(close)).first, "accept")
        assertEquals(HttpStatusCode.Conflict, res.status, res.bodyAsText())
        assertEquals("open", item(cu(close)).second)
    }

    // Kept here (no device rate limit in this class): the batch path, not quarantine.
    /** db V0079/V0080 bound the ledger at |amount| <= 10^13 milli-taka: a memo above it is refused per record, never the batch. */
    @Test
    fun moneyAboveTheLedgerBoundIsRefusedPerRecordAndTheBatchGoesOn() = testApplication {
        app()
        val t = client.token()
        val good = saleFamily()
        val bad = saleFamily(priceDelta = 1_000_000_000_000) // lines of 2*10^13 and a memo of 3*10^13 milli-taka
        val body = batch(good + bad)
        val first = json(client.send(t, body).bodyAsText())
        fun outcomes(r: JsonObject) = r["acks"]!!.jsonArray.map { a -> a.jsonObject["status"]!!.jsonPrimitive.content + ":" + (a.jsonObject["code"]?.jsonPrimitive?.contentOrNull ?: "-") }
        assertEquals(List(5) { "accepted:-" } + listOf("accepted:-", "rejected:schema_invalid", "rejected:schema_invalid", "rejected:schema_invalid", "accepted:-"), outcomes(first), first.toString())
        val badMemo = bad[1]["client_uuid"]!!.jsonPrimitive.content
        assertEquals(0L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$badMemo'"))
        assertEquals(0L, count("SELECT count(*) FROM app.due_ledger WHERE memo_client_uuid = '$badMemo'"))
        // The same batch again is the stored answer; the records resent in a new batch keep their outcome.
        val replay = json(client.send(t, body).bodyAsText())
        assertEquals(true, replay["replayed"]!!.jsonPrimitive.boolean)
        assertEquals(outcomes(first), outcomes(replay))
        assertEquals(outcomes(first).drop(5), outcomes(json(client.send(t, batch(bad)).bodyAsText())).map { it.replace("duplicate", "accepted") })
    }
}
