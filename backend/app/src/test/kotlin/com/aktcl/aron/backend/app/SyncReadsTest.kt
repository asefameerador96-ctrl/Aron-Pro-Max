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
 * The phone's two sync reads through the production wiring, on the seed (Sunday 2027-01-03 10:00 Dhaka):
 * GET /v1/sync/totals (the Server column of Sales Submit; F-SYS-005 reconciliation) and GET /v1/memos (F-API-025,
 * reprints and Sale History beyond the local window). Its own database: the batch class's low device rate limit would
 * be spent by these reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncReadsTest {
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
        }
        keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.securityStore?.close(); wiring.database?.close(); fresh.close() }

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

    private suspend fun HttpClient.read(token: String, path: String): HttpResponse = get(path) { bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9") }

    /** The scope-free part of a ServerTotals (as_of is the read time). */
    private fun JsonObject.sansAsOf() = JsonObject(this - "as_of")

    @Test
    fun syncTotalsEqualTheBatchTotalsAndARepeatedUploadLeavesThemUnchanged() = testApplication {
        app()
        val token = client.token()
        val family = saleFamily()
        val b = json(client.send(token, batch(family)).bodyAsText())
        assertEquals(List(5) { "accepted" }, statuses(b))
        val fromBatch = b["server_totals"]!!.jsonArray.map { it.jsonObject }.single { it["business_date"]!!.jsonPrimitive.content == day }
        val r = client.read(token, "/v1/sync/totals?business_date=$day")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val t = json(r.bodyAsText())
        assertEquals(fromBatch.sansAsOf(), t["totals"]!!.jsonObject.sansAsOf(), "the Server column equals what the batch answered")
        assertEquals(b["day_states"], t["day_states"])
        assertTrue(t.containsKey("supervisor_day"))
        // The same rows under a new batch uuid (duplicates), then that batch again (replayed): the totals do not move.
        val again = batch(family)
        repeat(2) {
            val r2 = client.send(token, again)
            assertEquals(HttpStatusCode.OK, r2.status, r2.bodyAsText())
            if (it == 0) assertEquals(List(5) { "duplicate" }, statuses(json(r2.bodyAsText())))
            else assertEquals(true, json(r2.bodyAsText())["replayed"]!!.jsonPrimitive.boolean, "the same batch uuid replays")
        }
        assertEquals(t["totals"]!!.jsonObject.sansAsOf(), json(client.read(token, "/v1/sync/totals?business_date=$day").bodyAsText())["totals"]!!.jsonObject.sansAsOf())
        // Only the caller's own records: a date with nothing is all zero, and the query cannot name another user.
        val empty = json(client.read(token, "/v1/sync/totals?business_date=2027-01-01&user_id=1").bodyAsText())
        assertEquals(JsonObject(emptyMap()), empty["totals"]!!.jsonObject["by_type"])
        assertEquals(0L, empty["totals"]!!.jsonObject["money"]!!.jsonObject["net_mtk"]!!.jsonPrimitive.long)
        assertEquals(HttpStatusCode.BadRequest, client.read(token, "/v1/sync/totals").status)
        assertEquals(HttpStatusCode.BadRequest, client.read(token, "/v1/sync/totals?business_date=2027-1-3").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/sync/totals?business_date=$day").status)
    }

    @Test
    fun memosAreReadByNumberOrOutletWithLinesAndNeverOutsideTheCallersReach() = testApplication {
        app()
        val token = client.token()
        val memoNo = "sr1001-270103-${memoSeq.incrementAndGet()}"
        val family = saleFamily(memoNo = memoNo, dueMtk = 0)
        assertEquals(List(5) { "accepted" }, statuses(json(client.send(token, batch(family)).bodyAsText())))
        val memoCu = family[1]["client_uuid"]!!.jsonPrimitive.content

        val byNo = json(client.read(token, "/v1/memos?memo_no=$memoNo").bodyAsText())
        val m = byNo["items"]!!.jsonArray.single().jsonObject
        assertEquals(memoCu, m["memo_client_uuid"]!!.jsonPrimitive.content)
        assertEquals("active", m["status"]!!.jsonPrimitive.content)
        assertEquals(outletId, m["outlet_id"]!!.jsonPrimitive.long)
        assertEquals(listOf(1, 2), m["lines"]!!.jsonArray.map { it.jsonObject["line_no"]!!.jsonPrimitive.int })
        val gross = m["lines"]!!.jsonArray.sumOf { it.jsonObject["gross_mtk"]!!.jsonPrimitive.long }
        assertEquals(gross, m["totals"]!!.jsonObject["net_mtk"]!!.jsonPrimitive.long, "integer milli-taka, header equals its lines")
        assertEquals(0, m["print_count"]!!.jsonPrimitive.int)
        assertEquals(null, byNo["next_cursor"]!!.jsonPrimitive.contentOrNull())

        // By outlet and date window, paged one memo at a time: every memo of the outlet once, newest first.
        val all = count("SELECT count(*) FROM app.memo WHERE outlet_id = $outletId AND business_date = '$day'")
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = json(client.read(token, "/v1/memos?outlet_id=$outletId&from=$day&to=$day&limit=1" + (cursor?.let { "&cursor=$it" } ?: "")).bodyAsText())
            seen += page["items"]!!.jsonArray.map { it.jsonObject["memo_client_uuid"]!!.jsonPrimitive.content }
            cursor = page["next_cursor"]!!.jsonPrimitive.contentOrNull()
        } while (cursor != null && seen.size <= all)
        assertEquals(all, seen.size.toLong()); assertEquals(seen.toSet().size, seen.size)
        assertTrue(memoCu in seen)

        // A memo on a route in another zone (written there by another user): sr1001 never sees it, whatever it names.
        val otherNo = "tso1001-270103-901"
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'OTH-R', 'Other', 'Other', (SELECT id FROM app.zone WHERE code = 'Z-OTHER'), kind, visit_kind, visit_days_mask, sequence_no FROM app.route WHERE code = 'MIR-SR-D' ON CONFLICT DO NOTHING")
            val cols = h.createQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'memo' AND column_name <> 'id' AND is_generated = 'NEVER' ORDER BY ordinal_position").mapTo(String::class.java).list()
            val repl = mapOf(
                "client_uuid" to "gen_random_uuid()", "family_uuid" to "gen_random_uuid()", "visit_client_uuid" to "gen_random_uuid()", "memo_no" to "'$otherNo'",
                "route_id" to "(SELECT id FROM app.route WHERE code = 'OTH-R')", "outlet_id" to "$otherOutletId", "user_id" to "(SELECT id FROM app.app_user WHERE username = 'tso1001')",
                "device_id" to "NULL", "sig" to "NULL", "zone_id" to "NULL", "external_ref" to "NULL",
            )
            h.execute("INSERT INTO app.memo (${cols.joinToString()}) SELECT ${cols.joinToString { repl[it] ?: it }} FROM app.memo WHERE client_uuid = '$memoCu'")
        }
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE memo_no = '$otherNo'"))
        for (q in listOf("memo_no=$otherNo", "outlet_id=$otherOutletId&from=$day&to=$day", "from=$day&to=$day&limit=500")) {
            val items = json(client.read(token, "/v1/memos?$q").bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
            assertTrue(items.none { it["memo_no"]!!.jsonPrimitive.content == otherNo }, "out of reach: $q")
        }
        // An admin data void tombstones the row (voided_at, status untouched): it is listed as void, as the totals leave it out.
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.memo SET voided_at = now() WHERE client_uuid = '$memoCu'") }
        assertEquals("void", json(client.read(token, "/v1/memos?memo_no=$memoNo").bodyAsText())["items"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
        // Bad queries are 400, never a silent full scan.
        for (q in listOf("memo_no=SR1001-1", "from=2027-01-03&to=2026-01-01", "from=2026-01-01&to=2027-01-03", "limit=0", "cursor=!!", "outlet_id=-1")) {
            assertEquals(HttpStatusCode.BadRequest, client.read(token, "/v1/memos?$q").status, q)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/memos?memo_no=$memoNo").status)
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
