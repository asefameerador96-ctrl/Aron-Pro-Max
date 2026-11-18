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
import kotlinx.serialization.json.JsonArray
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * THE BUNDLE FIGURE THROUGH THE REAL ENDPOINTS (D-LEAD-FIGURES-SIMPLE-01): batches go through `POST /v1/sync/batch`, quarantine is released or discarded by the reviewer's
 * `POST /v1/admin/quarantine/{id}/resolve`, the office voids a route-day through `POST /v1/admin/data-void` and keys a paper memo through `POST /v1/admin/data-entry`, and after EACH
 * step the figures of `GET /v1/sync/bundle?for=D` (achievement, KPI brand volume and memos, Star STD, campaign STD and memos) are compared with the TRUTH computed independently
 * from `app.memo` / `app.memo_line` through D - 1 (the cut: today is never in a server figure). Clock: Sunday 2027-01-03 10:00 Dhaka; memos are dated 2027-01-03, so the bundle for
 * 2027-01-04 holds them and the bundle for 2027-01-03 does not. (The phone-side merge, `POST /v1/sync/figures` and its identity rule are PARKED, docs/requests/sr-programmes-phone-rule-b.md.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation::class)
class SrBundleFiguresIngestTest {
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
    private var brand0 = 0L; private var sameBrand = false; private var cat0 = ""; private var sameCat = false
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
            // every merged component on the wire: a route target, a brand KPI target, a Star programme and a campaign at the SR's outlet for the first SKU
            fun brandOf(sku: Long) = h.createQuery("SELECT v.parent_id FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id WHERE s.id = :s").bind("s", sku).mapTo(Long::class.java).one()
            fun catOf(sku: Long) = h.createQuery("SELECT category_code FROM app.sku WHERE id = :s").bind("s", sku).mapTo(String::class.java).one()
            brand0 = brandOf(skus[0].first); sameBrand = brandOf(skus[1].first) == brand0; cat0 = catOf(skus[0].first); sameCat = catOf(skus[1].first) == cat0
            val sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000e5e1', decode(repeat('ab', 32), 'hex'), 1, $sr)")
            val b = "'00000000-0000-4000-8000-00000000e5e1'"
            h.execute("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($routeId, ${skus[0].first}, DATE '2027-01-01', 5000, $b), ($routeId, ${skus[1].first}, DATE '2027-01-01', 5000, $b)")
            h.execute("INSERT INTO app.route_brand_kpi_target (route_id, brand_id, month, memo_target, kpi_alloc_mtk, batch_uuid) VALUES ($routeId, $brand0, DATE '2027-01-01', 50, 1000000, $b)")
            val star = h.createQuery("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status, rules) VALUES ('superstar', 'MBLS-E', 'Star', DATE '2026-12-01', DATE '2027-01-31', 'active', '{\"sku_category\":\"$cat0\",\"unit_divisor\":1}') RETURNING id").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($star, $routeId, DATE '2027-01-01', 500.000, $b)")
            val camp = h.createQuery("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSL-E', 'Campaign', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($camp, DATE '2027-01-01', $outletId, 1000, 50, $b)")
            h.execute("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($camp, DATE '2027-01-01', $outletId, ${skus[0].first}, 1000, $b)")
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test rl' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'test: no MFA' FROM app.cfg_version")
        }
        keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock)        // log both users in once under the fixed clock: the gate tests move their own clock forward, and a token issued then is 'not yet valid' for this wiring
        testApplication { application { aronApi(wiring) }; client.token(); client.token("admin1001") }
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


    // ---------------------------------------------------------------- the bundle figure and the database truth

    /** The eight merged figures of the route: achievement qty and memos of the first SKU; brand qty and memos; Star STD in milli; the campaign's STD and memo count at the SR's outlet. */
    private data class Fig(val achQty: Long = 0, val achMemos: Long = 0, val brandQty: Long = 0, val brandMemos: Long = 0, val starMilli: Long = 0, val campStd: Long = 0, val campMemos: Long = 0)

    private suspend fun HttpClient.bundleJson(forDate: String): JsonObject =
        json(get("/v1/sync/bundle?for=$forDate") { bearerAuth(token()); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9") }.bodyAsText())

    /** The figures as the bundle carries them for [forDate]. */
    private suspend fun HttpClient.bundleFig(forDate: String): Fig {
        val route = bundleJson(forDate)["routes"]!!.jsonArray.map { it.jsonObject }.single { it["route_id"]!!.jsonPrimitive.long == routeId }
        fun JsonObject.l(k: String) = this[k]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.long ?: 0L
        val a = route["achievement_mtd"]!!.jsonArray.map { it.jsonObject }.singleOrNull { it["product_id"]!!.jsonPrimitive.long == skus[0].first }
        val kpi = route["kpi"]?.takeIf { it is JsonObject }?.jsonObject
        val brand = kpi?.get("brands")?.jsonArray?.map { it.jsonObject }?.singleOrNull { it["brand_id"]!!.jsonPrimitive.long == brand0 }
        val star = route["star"]?.takeIf { it is JsonObject }?.jsonObject?.get("std")?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content?.let { java.math.BigDecimal(it).movePointRight(3).toLong() } ?: 0L
        val camp = (route["campaigns"] as? JsonArray).orEmpty().map { it.jsonObject }.singleOrNull { it["outlet_id"]!!.jsonPrimitive.long == outletId }
        return Fig(a?.l("achieved_qty_base") ?: 0, a?.l("memo_count") ?: 0, brand?.l("volume_achieved") ?: 0, brand?.l("memo_achieved") ?: 0, star,
            camp?.get("std")?.jsonObject?.l("achieved") ?: 0, camp?.get("memo")?.jsonObject?.l("achieved") ?: 0)
    }

    /** The truth through [through], straight from the memo tables with the aggregator's filters; written here independently of the server's loader. */
    private fun dbFig(through: String): Fig = fresh.db.jdbi.withHandle<Fig, Exception> { h ->
        val from = "2027-01-01"
        fun q(select: String, where: String): Long = h.createQuery(
            """
            SELECT $select FROM app.memo m JOIN app.memo_line ml ON ml.memo_client_uuid = m.client_uuid AND ml.business_date = m.business_date
              JOIN app.sku s ON s.id = ml.sku_id JOIN app.product_node v ON v.id = s.variant_id
             WHERE m.business_date BETWEEN DATE '$from' AND DATE '$through' AND m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL AND ml.voided_at IS NULL AND $where
            """.trimIndent(),
        ).mapTo(Long::class.java).one()
        val r = "m.route_id = $routeId"; val sale = "ml.line_kind = 'sale'"
        Fig(
            q("coalesce(sum(ml.qty_base) FILTER (WHERE $sale), 0)", "$r AND ml.sku_id = ${skus[0].first}"),
            q("count(DISTINCT m.client_uuid)", "$r AND ml.sku_id = ${skus[0].first}"),
            q("coalesce(sum(ml.qty_base), 0)", "$r AND $sale AND v.parent_id = $brand0"),
            q("count(DISTINCT m.client_uuid)", "$r AND $sale AND v.parent_id = $brand0"),
            q("coalesce(sum(ml.qty_base), 0)", "$r AND $sale AND s.category_code = '$cat0'") * 1000,
            q("coalesce(sum(ml.qty_base), 0)", "m.outlet_id = $outletId AND $sale AND ml.sku_id = ${skus[0].first}"),
            q("count(DISTINCT m.client_uuid)", "m.outlet_id = $outletId AND $sale AND ml.sku_id = ${skus[0].first}"),
        )
    }

    /** The bundle for each of today and tomorrow equals the truth through the day before it: today's memos are cut from today's bundle, in tomorrow's. */
    private suspend fun HttpClient.assertBundle(what: String) {
        assertEquals(dbFig("2027-01-02"), bundleFig("2027-01-03"), "bundle for 2027-01-03 = truth through 2027-01-02 (today's memos cut): $what")
        assertEquals(dbFig("2027-01-03"), bundleFig("2027-01-04"), "bundle for 2027-01-04 = truth through 2027-01-03: $what")
    }

    private suspend fun HttpClient.sendOk(fam: List<JsonObject>, expect: String = "accepted"): List<String> =
        statuses(json(send(token(), batch(fam)).bodyAsText())).also { st -> assertEquals(List(fam.size) { expect }, st) }

    private fun memoNo(fam: List<JsonObject>) = fam[1]["payload"]!!.jsonObject["memo_no"]!!.jsonPrimitive.content

    // ---- the device gate holds gated records (memo, memo_void, ...) of a phone that is not enrolled: a reviewer releases them later --------------------------------------------------

    private val gateNow = java.util.concurrent.atomic.AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val gateSwitches = java.util.concurrent.atomic.AtomicInteger(0)

    /** A second wiring on the same database with a clock the test moves, so a config switch is seen (DbServerConfig caches 30 s by the injected clock). */
    private fun gateWiring() = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), AronClock { gateNow.get() })

    private fun requireEnrolled(on: Boolean) {
        val n = gateSwitches.incrementAndGet()
        fresh.db.jdbi.useTransaction<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test gate' FROM app.cfg_version")
            h.execute("UPDATE app.cfg_value SET effective_to = TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n WHERE key = 'cfg.device.require_enrolled' AND scope_type = 'global' AND effective_to IS NULL")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.device.require_enrolled', 'global', 0, '$on'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n, max(config_version), 'test' FROM app.cfg_version")
        }
        gateNow.updateAndGet { it.plusSeconds(31) }
    }

    /** A phone not enrolled with the device gate: its sale family is held for review, a reviewer then discards the memo. */
    private fun withGate(body: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        val w2 = gateWiring()
        try { testApplication { application { aronApi(w2) }; body() } } finally { w2.database?.close() }
    }


    @Test
    fun aCommittedMemoIsInTomorrowsFigureAndNeverInTodays() = testApplication {
        app()
        client.assertBundle("start")
        val before = client.bundleFig("2027-01-04"); val fam = saleFamily()
        client.sendOk(fam)
        val after = client.bundleFig("2027-01-04")
        assertEquals(20L, after.achQty - before.achQty, "the committed memo's 20 sticks are in tomorrow's figure"); assertEquals(1L, after.achMemos - before.achMemos)
        assertEquals(1L, after.campMemos - before.campMemos, "and in the outlet's campaign memo count")
        client.assertBundle("committed")
        client.sendOk(fam, "duplicate")
        client.assertBundle("the same batch sent again (lost response): nothing doubles")
    }

    @Test
    fun aHeldMemoIsOutOfEveryFigureUntilTheReviewerAcceptsIt() = testApplication {
        app()
        val fam = saleFamily(outlet = otherOutletId); val (visit, memo) = fam.map { cu(it) }
        assertEquals(List(5) { "quarantined" }, statuses(json(client.send(client.token(), batch(fam)).bodyAsText())))
        client.assertBundle("held: not stored, in no figure")
        assertEquals(HttpStatusCode.OK, client.resolve(item(visit).first, "accept").status)
        assertEquals(HttpStatusCode.OK, client.resolve(item(memo).first, "accept").status)
        assertEquals(1L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
        client.assertBundle("released: stored, in the figures through the cut")
    }

    @Test
    fun aLineRefusedForGoodLeavesTheMemoWithTheStoredLinesOnlyAndTheFigureIsTheDatabasesTruth() = testApplication {
        app()
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.sku_price SET amount_mtk = 0 WHERE sku_id = ? AND price_type = 'outlet'", skus[1].first) }
        try {
            val fam = saleFamily(zeroSecond = true)
            val acks = statuses(json(client.send(client.token(), batch(fam)).bodyAsText()))
            assertTrue("rejected" in acks && "accepted" in acks, "the zero-priced line is refused for good, the rest is stored: $acks")
            assertEquals(1L, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${cu(fam[1])}'"), "only the priced line is stored")
            client.assertBundle("partial memo: the figure holds the stored line only")
        } finally { fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.sku_price SET amount_mtk = ? WHERE sku_id = ? AND price_type = 'outlet'", skus[1].second, skus[1].first) } }
    }

    @Test
    fun anAcceptedEditAndAnAcceptedVoidLeaveOnlyTheLiveMemoInTheFigure() = testApplication {
        app()
        val f1 = saleFamily(); client.sendOk(f1)
        client.assertBundle("memo M")
        val f2 = saleFamily(qty1 = 15, supersedes = cu(f1[1])); client.sendOk(f2)
        client.assertBundle("edit E supersedes M: only E (15) is in the figure")
        val vu = uuid()
        val voidRec = envelope("memo_void", vu, vu, 0, Json.parseToJsonElement(
            """{"memo_client_uuid":"${cu(f2[1])}","memo_no":"${memoNo(f2)}","reason_code":"retailer_cancelled","retailer_ack":true,"fix":{"purpose":"memo_void","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,"device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}""",
        ).jsonObject)
        assertEquals(listOf("accepted"), statuses(json(client.send(client.token(), batch(listOf(voidRec))).bodyAsText())))
        client.assertBundle("void of E: no memo of the pair is in the figure")
    }

    @Test
    fun aPaperMemoKeyedByTheOfficeIsInTheFigureOnceEvenWhenThePhonesOwnCopyIsHeldAsADuplicate() = testApplication {
        app()
        val srId = count("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val no = "sr1001-270103-${memoSeq.incrementAndGet()}"
        val paper = client.post("/v1/admin/data-entry") {
            bearerAuth(client.token("admin1001")); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"${uuid()}","memo_no":"$no","business_date":"$day","user_id":$srId,"route_id":$routeId,"outlet_id":$outletId,"lines":[{"sku_id":${skus[0].first},"qty_base":20,"line_kind":"sale"}],"paid_mtk":0,"reason":"phone died, keyed from the printed memo"}""")
        }
        assertEquals(HttpStatusCode.OK, paper.status, paper.bodyAsText())
        client.assertBundle("the paper memo is stored")
        val fam = saleFamily(memoNo = no)
        val acks = statuses(json(client.send(client.token(), batch(fam)).bodyAsText()))
        assertTrue(acks.any { it == "duplicate" || it == "quarantined" }, "the phone's own copy is held or answered as a memo number duplicate: $acks")
        assertEquals(0L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${cu(fam[1])}'"), "the phone's copy is not stored under its own uuid")
        client.assertBundle("the paper memo is in the figure once, the held copy is in none")
    }

    @Test
    fun anActiveMemoWhoseLinesAreNotStoredYetAddsNothingToAnyFigure() = testApplication {
        app()
        val srId = count("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val fx = SrMemoFixture({ q -> fresh.db.jdbi.useHandle<Exception> { it.execute(q) } }, srId, 700)
        fx.memo(uuid(), routeId, outletId, "2027-01-02", listOf(SrMemoFixture.L(skus[0].first, 20)), storeLines = false)
        client.assertBundle("a header without stored lines: no figure counts it (the aggregator's filters)")
    }

    @Test
    fun theBundleVersionIsStableWhileNothingChangesAndMovesWhenAMemoIsStored() = testApplication {
        app()
        suspend fun version() = client.bundleJson("2027-01-04")["meta"]!!.jsonObject["bundle_version"]!!.jsonPrimitive.content
        val v1 = version(); assertEquals(v1, version(), "two reads with nothing changed: the same version")
        client.sendOk(saleFamily())
        assertTrue(version() != v1, "a stored memo changes the figures, so the content digest")
        val v2 = version(); assertEquals(v2, version(), "stable again")
    }

    @Test
    fun aDiscardedHeldMemoIsInNoFigure() = withGate {
        requireEnrolled(true)
        try {
            val f = saleFamily()
            assertTrue(statuses(json(client.send(client.token(), batch(f)).bodyAsText())).contains("quarantined"))
            assertEquals(HttpStatusCode.OK, client.resolve(item(cu(f[1])).first, "discard").status)
            assertEquals(0L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${cu(f[1])}'"))
            client.assertBundle("discarded: never stored, in no figure")
        } finally { requireEnrolled(false) }
    }

    @Test
    fun aHeldVoidReleasedByTheReviewerRemovesTheMemoFromTheFigure() = withGate {
        val f1 = saleFamily(); client.sendOk(f1)
        client.assertBundle("memo stored")
        requireEnrolled(true)
        try {
            val vu = uuid()
            val voidRec = envelope("memo_void", vu, vu, 0, Json.parseToJsonElement(
                """{"memo_client_uuid":"${cu(f1[1])}","memo_no":"${memoNo(f1)}","reason_code":"retailer_cancelled","retailer_ack":true,"fix":{"purpose":"memo_void","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,"device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}""",
            ).jsonObject)
            assertEquals(listOf("quarantined"), statuses(json(client.send(client.token(), batch(listOf(voidRec))).bodyAsText())), "the void is held by the device gate")
            client.assertBundle("void held: the memo is still in the figure")
            assertEquals(HttpStatusCode.OK, client.resolve(item(vu).first, "accept").status)
            client.assertBundle("void released: the memo is out of the figure")
        } finally { requireEnrolled(false) }
    }

    @Test
    fun aHeldEditReleasedByTheGateClearingReplacesTheOldMemoInTheFigure() = withGate {
        val f1 = saleFamily(); client.sendOk(f1)
        requireEnrolled(true)
        try {
            val f2 = saleFamily(qty1 = 15, supersedes = cu(f1[1]))
            assertTrue(statuses(json(client.send(client.token(), batch(f2)).bodyAsText())).contains("quarantined"), "the edit is held by the device gate")
            client.assertBundle("edit held: the old memo is the figure")
            requireEnrolled(false)
            val rel = statuses(json(client.send(client.token(), batch(f2)).bodyAsText()))
            assertTrue(rel.none { it == "quarantined" || it == "rejected" }, "the resend after the gate cleared stores the edit: $rel")
            client.assertBundle("edit released: the edit replaces the old memo")
        } finally { requireEnrolled(false) }
    }

    @Test
    fun aQuantityFixOfAHeldMemoLineIsRefusedByTheMoneyRuleSoAReviewNeverChangesAMemosQuantities() = testApplication {
        // The review claimed accept_with_fix of a line quantity as a way a memo could change on release. It cannot: a memo or line fix moves money (docs/24 s8.6), refused 409 (maker-checker).
        app()
        val fam = saleFamily(outlet = otherOutletId); val (visit) = fam.map { cu(it) }; val line = fam[2]; val lp = line["payload"]!!.jsonObject
        assertEquals(List(5) { "quarantined" }, statuses(json(client.send(client.token(), batch(fam)).bodyAsText())))
        assertEquals(HttpStatusCode.OK, client.resolve(item(visit).first, "accept").status)
        val fix = JsonObject(line + ("payload" to JsonObject(lp + mapOf("qty_entered" to JsonPrimitive(18), "qty_base" to JsonPrimitive(18), "gross_mtk" to JsonPrimitive(lp["base_price_mtk"]!!.jsonPrimitive.long * 18)))))
        val r = client.resolve(item(cu(line)).first, "accept_with_fix", fix)
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
        assertEquals("open", item(cu(line)).second, "the item stays open")
        assertEquals(0L, count("SELECT count(*) FROM app.memo_line WHERE client_uuid = '${cu(line)}'"), "nothing was stored by the refused fix")
    }

    @Test @org.junit.jupiter.api.Order(Int.MAX_VALUE)   // last (unannotated tests default to MAX_VALUE/2): a data void tombstones the route-day and refuses every later upload of it
    fun anAdminDataVoidOfTheRouteDayRemovesItsMemosFromTheFigureAndRefusesALateUpload() = testApplication {
        app()
        val f = saleFamily(); client.sendOk(f)
        client.assertBundle("today's memo committed")
        val v = client.post("/v1/admin/data-void") {
            bearerAuth(client.token("admin1001")); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"${uuid()}","route_id":$routeId,"business_date":"$day","scope":"all","reason":"Wrong day uploaded by mistake"}""")
        }
        assertEquals(HttpStatusCode.OK, v.status, v.bodyAsText())
        client.assertBundle("the office voided the route-day: its memos are out of the figure")
        val late = saleFamily(); val acks = statuses(json(client.send(client.token(), batch(late)).bodyAsText()))
        assertTrue(acks.none { it == "accepted" }, "the late upload is refused for good: $acks")
        client.assertBundle("the refused late upload is in no figure")
    }
}
