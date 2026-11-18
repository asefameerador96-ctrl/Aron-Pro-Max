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
 * THE IDENTITY RULE THROUGH THE REAL ENDPOINTS (D-TKPI-14): batches go through `POST /v1/sync/batch`, quarantine is released by the reviewer's `POST /v1/admin/quarantine/{id}/resolve`,
 * the office voids a route-day through `POST /v1/admin/data-void` and keys a paper memo through `POST /v1/admin/data-entry`, and the figures and the identity answer are read from the real
 * `POST /v1/sync/figures`. The phone is a model of `docs/requests/sr-programmes-phone-rule-b.md` (no acknowledgements, resolutions, quarantine states, clocks or single flight): it probes the
 * uuids of its memos and applies IN / VOIDED / NOT IN. Each test measures figures as a DELTA over what earlier tests left in the shared database and COMPARES TOTALS. Clock: Sunday 2027-01-03
 * 10:00 Dhaka; the memos are dated that day; `for` 2027-01-04 has through_date = the memos' day, `for` 2027-01-03 has the memos after through_date.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrFiguresEndToEndTest {
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
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'test: no MFA' FROM app.cfg_version")
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
        qty1: Int = 20, zeroSecond: Boolean = false, supersedes: String? = null,
    ): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val (s1, p1x) = skus[0]; val (s2, p2x) = skus[1]
        val p1 = p1x + priceDelta; val p2 = p2x + priceDelta
        val g1 = qty1 * p1; val g2 = if (zeroSecond) 0L else 10 * p2; val gross = g1 + g2 + headerGrossDelta
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
             "offer_version_ids":[],"rounding_mode":"half_up_paisa"${supersedes?.let { ",\"supersedes_client_uuid\":\"$it\",\"edit_reason_code\":\"wrong_quantity\"" } ?: ""}}
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
            envelope("memo_line", uuid(), visit, 2, line(1, s1, qty1, p1, g1)),
            envelope("memo_line", uuid(), visit, 2, line(2, s2, 10, if (zeroSecond) 0L else p2, g2)),
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

    // ---------------------------------------------------------------- the real /figures and the identity phone

    private class PM(val uuid: String, val date: String, val qty: Long = 20, val memoNo: String? = null) {
        var removed = false; var confirmed = false; var dropped = false
    }

    private class FigureReply(val qty: Long, val memos: Long, val inFig: Map<String, Int>, val inLines: Map<String, List<Triple<Long, Long, String>>>, val voided: Set<String>, val raw: JsonObject)

    private suspend fun HttpClient.figures(forDate: String, probe: List<PM>): FigureReply {
        val body = buildJsonObject {
            put("for", forDate)
            put("probe", kotlinx.serialization.json.JsonArray(probe.map { m -> buildJsonObject { put("memo_client_uuid", m.uuid); m.memoNo?.let { put("memo_no", it) }; put("business_date", m.date); if (m.removed) put("removed_locally", true) } }))
        }.toString()
        val r = post("/v1/sync/figures") { bearerAuth(token()); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val j = json(r.bodyAsText())
        val route = j["routes"]!!.jsonArray.map { it.jsonObject }.single { it["route_id"]!!.jsonPrimitive.long == routeId }
        val row = route["achievement_mtd"]!!.jsonArray.map { it.jsonObject }.singleOrNull { it["product_id"]!!.jsonPrimitive.long == skus[0].first }
        val fp = j["figure_probe"]!!.jsonObject
        val inMemos = fp["in_figure"]!!.jsonArray.map { it.jsonObject }
        return FigureReply(
            row?.get("achieved_qty_base")?.jsonPrimitive?.long ?: 0L, row?.get("memo_count")?.jsonPrimitive?.long ?: 0L,
            inMemos.associate { it["memo_client_uuid"]!!.jsonPrimitive.content to it["lines"]!!.jsonPrimitive.int },
            inMemos.filter { it["stored_lines"] != null }.associate { m -> m["memo_client_uuid"]!!.jsonPrimitive.content to m["stored_lines"]!!.jsonArray.map { l -> Triple(l.jsonObject["sku_id"]!!.jsonPrimitive.long, l.jsonObject["qty_base"]!!.jsonPrimitive.long, l.jsonObject["line_kind"]!!.jsonPrimitive.content) } },
            fp["voided"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), j,
        )
    }

    /** What the phone shows for the first SKU (quantity, memo count) as a delta over [base], by the identity rule, and what is true. */
    private suspend fun HttpClient.shows(base: FigureReply, forDate: String, phone: List<PM>): Pair<Long, Long> {
        val through = java.time.LocalDate.parse(forDate).minusDays(1).toString()
        val probes = phone.filter { !it.dropped && (!it.confirmed || it.removed) && !(it.removed && it.date > through) }
        val a = figures(forDate, probes)
        var qty = a.qty - base.qty; var memos = a.memos - base.memos
        for (m in phone) {
            if (m.dropped || (m.confirmed && !m.removed)) continue
            val voided = m.uuid in a.voided; val inFig = m.uuid in a.inFig
            if (!m.removed) {
                if (voided) m.dropped = true
                else if (m.date > through) { qty += m.qty; memos += 1 }
                else if (inFig) m.confirmed = true
                else { qty += m.qty; memos += 1 }
            } else if (m.date <= through && inFig) {
                val lines = a.inLines.getValue(m.uuid).filter { it.first == skus[0].first && it.third == "sale" }
                qty -= lines.sumOf { it.second }; if (lines.isNotEmpty()) memos -= 1
            }
        }
        return qty to memos
    }
    private fun truth(phone: List<PM>) = phone.filter { !it.removed && !it.dropped }.let { l -> l.sumOf { it.qty } to l.size.toLong() }
    private suspend fun HttpClient.baseline(forDate: String = "2027-01-04") = figures(forDate, emptyList())
    private suspend fun HttpClient.assertShows(base: FigureReply, forDate: String, phone: List<PM>, what: String) {
        val shown = shows(base, forDate, phone); assertEquals(truth(phone), shown, "by identity the phone shows exactly the true total: $what")
    }
    private suspend fun HttpClient.sendOk(fam: List<JsonObject>, expect: String = "accepted"): List<String> =
        statuses(json(send(token(), batch(fam)).bodyAsText())).also { st -> assertEquals(List(fam.size) { expect }, st) }

    @Test
    fun aCommittedBatchWhoseResponseWasLostIsAnsweredInWithNoAcknowledgement() = testApplication {
        app()
        val base = client.baseline(); val fam = saleFamily(); val phone = listOf(PM(cu(fam[1]), day, memoNo = fam[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content))
        client.assertShows(base, "2027-01-04", phone, "nothing uploaded yet: NOT IN, added")
        client.send(client.token(), batch(fam))                  // committed; the phone never hears the answer
        client.assertShows(base, "2027-01-04", phone, "committed and the response lost: IN, nothing added (no acknowledgement, no single flight)")
        assertEquals(20L to 1L, client.figures("2027-01-04", emptyList()).let { (it.qty - base.qty) to (it.memos - base.memos) })
    }

    @Test
    fun aHeldMemoReleasedByTheReviewerIsExactBeforeDuringAndAfterTheRelease() = testApplication {
        app()
        val base = client.baseline(); val fam = saleFamily(outlet = otherOutletId); val (visit, memo) = fam.map { cu(it) }
        val phone = listOf(PM(memo, day, memoNo = fam[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content))
        assertEquals(List(5) { "quarantined" }, statuses(json(client.send(client.token(), batch(fam)).bodyAsText())))
        client.assertShows(base, "2027-01-04", phone, "held (not stored): NOT IN, the phone adds it")
        assertEquals(HttpStatusCode.OK, client.resolve(item(visit).first, "accept").status)
        assertEquals(HttpStatusCode.OK, client.resolve(item(memo).first, "accept").status)
        assertEquals(1L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
        client.assertShows(base, "2027-01-04", phone, "released before the phone hears: IN, nothing added")
        client.assertShows(base, "2027-01-03", listOf(PM(memo, day, memoNo = phone[0].memoNo)), "the same memo seen from today's bundle (after through_date): added, not in the figure")
    }

    @Test
    fun anAcceptedEditAndAnAcceptedVoidAreNeverDoubleCountedOrLost() = testApplication {
        app()
        val base = client.baseline()
        val f1 = saleFamily(); val m1 = PM(cu(f1[1]), day, 20, f1[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content)
        client.sendOk(f1)
        client.assertShows(base, "2027-01-04", listOf(m1), "M committed")
        // the phone edits M (quantity 20 -> 15): the new memo E supersedes M; before the upload the phone subtracts M's stored lines and adds E
        val f2 = saleFamily(qty1 = 15, supersedes = m1.uuid); val e = PM(cu(f2[1]), day, 15, f2[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content)
        m1.removed = true
        client.assertShows(base, "2027-01-04", listOf(m1, e), "edit not uploaded: M IN (stored lines subtracted), E NOT IN (added)")
        client.sendOk(f2)
        client.assertShows(base, "2027-01-04", listOf(m1, e), "edit committed: M VOIDED (superseded), E IN: nothing added, nothing subtracted")
        // the phone voids E: before the upload E is IN and subtracted, after the upload it is VOIDED
        val vu = uuid()
        val voidRec = envelope("memo_void", vu, vu, 0, Json.parseToJsonElement(
            """{"memo_client_uuid":"${e.uuid}","memo_no":"${e.memoNo}","reason_code":"retailer_cancelled","retailer_ack":true,"fix":{"purpose":"memo_void","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,"device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}""",
        ).jsonObject)
        e.removed = true
        client.assertShows(base, "2027-01-04", listOf(m1, e), "void not uploaded: E IN, its stored lines subtracted")
        val voidAck = statuses(json(client.send(client.token(), batch(listOf(voidRec))).bodyAsText()))
        assertEquals(listOf("accepted"), voidAck)
        client.assertShows(base, "2027-01-04", listOf(m1, e), "void committed: E VOIDED, nothing subtracted a second time")
    }

    @Test
    fun aLineRefusedForGoodSoTheMemoIsStoredWithFewerLinesIsInAndNeverAddedOnEveryBundle() = testApplication {
        app()
        val base = client.baseline()
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.sku_price SET amount_mtk = 0 WHERE sku_id = ? AND price_type = 'outlet'", skus[1].first) }
        try {
            val fam = saleFamily(zeroSecond = true)
            val acks = statuses(json(client.send(client.token(), batch(fam)).bodyAsText()))
            assertTrue("rejected" in acks && "accepted" in acks, "the zero-priced line is refused for good, the rest is stored: $acks")
            val memo = cu(fam[1]); assertEquals(1L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
            assertEquals(1L, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$memo'"), "only the priced line is stored")
            val phone = listOf(PM(memo, day, memoNo = fam[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content))
            val a = client.figures("2027-01-04", phone)
            assertEquals(1, a.inFig[memo], "IN with the stored line count 1 (the phone sent 2)")
            repeat(2) { client.assertShows(base, "2027-01-04", phone, "bundle ${it + 1}: IN, the phone adds nothing: the total is the server's, never added on every bundle") }
        } finally { fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.sku_price SET amount_mtk = ? WHERE sku_id = ? AND price_type = 'outlet'", skus[1].second, skus[1].first) } }
    }

    @Test
    fun anAdminDataVoidOfTodaysRouteDayDropsTheMemosOnThePhone() = testApplication {
        app()
        val base = client.baseline("2027-01-03")
        val f = saleFamily(); val m = PM(cu(f[1]), day, 20, f[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content)
        client.sendOk(f)
        client.assertShows(base, "2027-01-03", listOf(m), "today's memo committed: cut by through_date, added by the phone")
        val admin = client.token("admin1001")
        val v = client.post("/v1/admin/data-void") {
            bearerAuth(admin); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"${uuid()}","route_id":$routeId,"business_date":"$day","scope":"all","reason":"Wrong day uploaded by mistake"}""")
        }
        assertEquals(HttpStatusCode.OK, v.status, v.bodyAsText())
        client.assertShows(base, "2027-01-03", listOf(m), "the office voided today's route-day: VOIDED, the phone drops it (the total is no longer above the truth)")
        assertTrue(m.dropped)
    }

    @Test
    fun aPaperTwinOfAMemoThePhoneHasNotUploadedIsNotCountedTwiceBeforeOrAfterItsUpload() = testApplication {
        app()
        val base = client.baseline()
        val srId = count("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val no = "sr1001-270102-${memoSeq.incrementAndGet()}"
        val paper = client.post("/v1/admin/data-entry") {
            bearerAuth(client.token("admin1001")); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"${uuid()}","memo_no":"$no","business_date":"2027-01-02","user_id":$srId,"route_id":$routeId,"outlet_id":$outletId,"lines":[{"sku_id":${skus[0].first},"qty_base":20,"line_kind":"sale"}],"paid_mtk":0,"reason":"phone died, keyed from the printed memo"}""")
        }
        assertEquals(HttpStatusCode.OK, paper.status, paper.bodyAsText())
        val phoneCopy = PM(uuid(), "2027-01-02", 20, no)
        client.assertShows(base, "2027-01-04", listOf(phoneCopy), "the paper memo is in the figure and the phone's own copy (another uuid, not uploaded) is IN through the memo number: 20, not 40")
        assertTrue(phoneCopy.confirmed)
    }

    private fun note(s: String) = println("NOTE SrFiguresEndToEndTest: $s")
}
