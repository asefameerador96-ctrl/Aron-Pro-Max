package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.analytics.Aggregator
import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared world, device model and trace plumbing of the wave-4 data-flow trace and of the attached finding tests (docs/requests/attachments). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DataFlowTraceSupport {
    protected val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    protected val day = "2026-10-24" // Saturday: GUL-SR-2 (mask 9 = Saturday + Tuesday)
    protected val clock = AronClock { Instant.parse("2026-10-24T10:30:00Z") }
    protected val json = ContractModel.json
    protected lateinit var world: ApiWorld
    protected lateinit var app: TestApplication

    protected val trace = StringBuilder()
    protected val findings = mutableListOf<String>()
    protected var section = ""

    // ---- trace plumbing --------------------------------------------------------------------------------------------

    protected fun h(title: String) { section = title; trace.append("\n### $title\n\n| Hop | Values | Result |\n|---|---|---|\n") }

    /** Rows whose [values] are all equal PASS; otherwise FINDING (soft) or an assertion failure (hard). */
    protected fun hop(name: String, vararg values: Pair<String, Any?>, hard: Boolean = true) {
        val shown = values.joinToString("; ") { "${it.first} = ${it.second}" }
        val ok = values.map { it.second.toString() }.distinct().size == 1
        trace.append("| $name | ${shown.replace("|", "/")} | ${if (ok) "PASS" else "**FINDING**"} |\n")
        if (!ok) {
            findings += "[$section] $name: $shown"
            if (hard) throw AssertionError("[$section] $name differs: $shown")
        }
    }

    protected fun note(name: String, value: Any?) { trace.append("| $name | ${value.toString().replace("|", "/")} | info |\n") }

    protected fun <T> db(block: (org.jdbi.v3.core.Handle) -> T): T = world.fresh.db.jdbi.withHandle<T, Exception> { block(it) }
    protected fun sql(q: String): String? = db { it.createQuery(q).mapTo(String::class.java).findOne().orElse(null) }
    protected fun sqlLong(q: String): Long = sql(q)!!.toLong()
    protected fun rows(q: String): List<Map<String, Any?>> = db { it.createQuery(q).mapToMap().list() }

    protected fun uuid() = UUID.randomUUID().toString()

    @BeforeAll
    fun setUp() {
        world = ApiWorld(repoRoot, mirrors = listOf(ApiWorld.OTHER), clock = clock, devProfile = true)
        app = TestApplication { application { aronApi(world.wiring) } }
        runBlocking { app.start() }
        db { h ->
            // What infra/scripts/devseed-run.sh does for the smoke device on dev: a used single-use enrolment token names it (the N-027 gate).
            // Its real key is the harness key already (ApiWorld). Without this the sync gate quarantines every money row `device_not_enrolled`.
            h.execute(
                "WITH tok AS (INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, used_count, release_id, expires_at, created_by, note) " +
                    "SELECT sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'smoke0', 'sr', 'dev', 1, 1, (SELECT id FROM app.app_release WHERE flavour = 'sr' AND status = 'published' ORDER BY version_code DESC LIMIT 1), " +
                    "now() + interval '1 hour', (SELECT id FROM app.app_user WHERE username = 'admin1001'), 'qa trace: the dev seed job enrols the smoke device' RETURNING id) " +
                    "UPDATE app.device d SET enrolment_token_id = tok.id FROM tok WHERE d.device_uuid = '${ApiWorld.SR_PHONE}' AND d.enrolment_token_id IS NULL",
            )
            // Test geography only: the other division gets its own wing, so wing, division and territory scopes each have something to cut.
            h.execute("INSERT INTO app.wing (code, name) VALUES ('W-LKX', 'Wing LKX')")
            h.execute("UPDATE app.division SET wing_id = (SELECT id FROM app.wing WHERE code = 'W-LKX') WHERE code = 'D-LKX'")
            val hash = PasswordHasher().hash(world.password)
            // The portal levels db/seed does not have (docs/34 s1): NATIONAL, WM of the Gulshan wing, BRAND of one brand.
            for ((u, role) in listOf("qanational1001" to "NATIONAL", "qawm1001" to "WM", "qabrand1001" to "BRAND")) {
                h.execute(
                    "INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password, password_hash) " +
                        "SELECT '$u', 'QA $role', '$role', 'QA', upper('$u'), 'en', home_zone_id, true, false, '$hash' FROM app.app_user WHERE username = 'admin1001'",
                )
            }
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT u.id, 'national', 0, DATE '2026-01-01', 'qa trace' FROM app.app_user u WHERE u.username = 'qanational1001'")
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT u.id, 'wing', d.wing_id, DATE '2026-01-01', 'qa trace' FROM app.app_user u, app.division d WHERE u.username = 'qawm1001' AND d.code = 'D-DHK'")
            h.execute("INSERT INTO app.user_brand_scope (user_id, brand_id, granted_by) SELECT u.id, b.id, a.id FROM app.app_user u, app.product_node b, app.app_user a WHERE u.username = 'qabrand1001' AND a.username = 'superadmin1001' AND b.level = 'brand' AND b.code = (SELECT min(code) FROM app.product_node WHERE level = 'brand' AND status = 'active')")
        }
    }

    // ---- device-side truth -----------------------------------------------------------------------------------------

    data class Sku(val id: Long, val code: String, val category: String, val baseUnit: String, val perPack: Int, val entryUnit: String, val price: Long, val perBase: Long)
    data class Line(val sku: Sku, val qtyEntered: Int) {
        val packFactor: Int get() = if (sku.entryUnit == "pack") sku.perPack else 1
        val qtyBase: Long get() = qtyEntered.toLong() * packFactor
        val gross: Long get() = (qtyBase * sku.price + sku.perBase / 2) / sku.perBase
    }
    class Memo(val outlet: Long, val outletCode: String, val lines: List<Line>, val paidShare: Int, val voided: Boolean = false, val minute: Int) {
        val visit = UUID.randomUUID().toString(); val memo = UUID.randomUUID().toString(); val close = UUID.randomUUID().toString()
        val gross: Long get() = lines.sumOf { it.gross }
        val paid: Long get() = gross * paidShare / 100
        val due: Long get() = gross - paid
    }

    class Device(val username: String, val deviceUuid: String, val deviceId: Long, val routeId: Long, val bundleVersion: String, val configVersion: Long, val token: String) {
        val memos = mutableListOf<Memo>()
        val all = mutableListOf<ObjectNode>()
        val counts = sortedMapOf<String, Int>()
        var seq = 1
        var truthGross = 0L
        var truthSold: Map<String, Long> = emptyMap()
    }

    protected fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0; val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val a = sin((p2 - p1) / 2).let { it * it } + cos(p1) * cos(p2) * sin(Math.toRadians(lng2 - lng1) / 2).let { it * it }
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    protected fun at(minute: Int): String = Instant.parse("2026-10-24T04:30:00Z").plusSeconds(minute * 60L).toString()

    protected fun fix(purpose: String, lat: Double, lng: Double): ObjectNode = json.readTree(
        """{"purpose":"$purpose","fix_status":"ok","lat":$lat,"lng":$lng,"accuracy_m":9.0,"provider":"fused","is_mock":false,"reused":false,
            "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}""",
    ) as ObjectNode

    protected fun envelope(d: Device, type: String, cu: String, family: String, rank: Int, minute: Int, payload: JsonNode): ObjectNode =
        (json.createObjectNode() as ObjectNode).apply {
            put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
            put("business_date", day); put("captured_at", at(minute)); put("captured_elapsed_ms", 18_330_000L + minute * 60_000L); put("boot_count", 412)
            put("clock_offset_ms", 0); put("captured_offline", true); put("route_id", d.routeId); put("bundle_version", d.bundleVersion)
            put("bundle_stale", false); put("config_version", d.configVersion); set<JsonNode>("payload", payload)
        }

    /** The records one memo makes on the phone, in the order the outbox holds them (visit, memo, lines, close, then a void). */
    protected fun recordsOf(d: Device, m: Memo, pin: Pair<Double, Double>, radius: Int, maxAcc: Int, seqNo: Int): List<ObjectNode> {
        val out = mutableListOf<ObjectNode>()
        val lat = pin.first + 0.00004; val lng = pin.second + 0.00003 // about 5 m from the pin
        val dist = haversine(lat, lng, pin.first, pin.second)
        out += envelope(d, "visit", m.visit, m.visit, 0, m.minute, json.readTree(
            """{"visit_kind":"sr_call","outlet_id":${m.outlet},"opened_at":"${at(m.minute)}","sequence_no":$seqNo,"planned":true,"fix":${fix("visit_open", lat, lng)},
                "geo":{"verdict":"in_range","distance_m":${"%.1f".format(dist)},"radius_m_used":$radius,"max_accuracy_m_used":$maxAcc,"location_basis":"master","action":"sale_allowed"}}"""))
        if (m.lines.isEmpty()) {
            out += envelope(d, "visit_close", m.close, m.visit, 1, m.minute + 2, json.readTree(
                """{"visit_client_uuid":"${m.visit}","outcome_code":"closed","call_declined":false,"ended_at":"${at(m.minute + 2)}","is_zero_sale":true}"""))
            return out
        }
        val memoNo = "${d.username}-261024-${(d.seq++).toString().padStart(3, '0')}"
        out += envelope(d, "memo", m.memo, m.visit, 1, m.minute + 4, json.readTree(
            """{"visit_client_uuid":"${m.visit}","outlet_id":${m.outlet},"memo_no":"$memoNo","memo_kind":"sale","committed_at":"${at(m.minute + 4)}","price_list_date":"$day",
                "price_type":"outlet","gross_mtk":${m.gross},"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"round_adj_mtk":0,"net_mtk":${m.gross},
                "paid_mtk":${m.paid},"due_mtk":${m.due},"is_credit":${m.due > 0},"line_count":${m.lines.size},"discount_line_count":0,"qc_line_count":0,
                "offer_version_ids":[],"rounding_mode":"half_up_paisa"}"""))
        m.lines.forEachIndexed { i, l ->
            out += envelope(d, "memo_line", uuid(), m.visit, 2, m.minute + 4, json.readTree(
                """{"memo_client_uuid":"${m.memo}","line_no":${i + 1},"sku_id":${l.sku.id},"line_kind":"sale","qty_entered":${l.qtyEntered},"unit_entered":"${l.sku.entryUnit}",
                    "pack_factor":${l.packFactor},"qty_base":${l.qtyBase},"price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":${l.sku.price},
                    "price_per_qty":${l.sku.perBase},"gross_mtk":${l.gross}}"""))
        }
        out += envelope(d, "visit_close", m.close, m.visit, 1, m.minute + 8, json.readTree(
            """{"visit_client_uuid":"${m.visit}","outcome_code":"sold","call_declined":false,"ended_at":"${at(m.minute + 8)}","is_zero_sale":false}"""))
        if (m.voided) {
            val vu = uuid()
            out += envelope(d, "memo_void", vu, vu, 0, m.minute + 30, json.readTree(
                """{"memo_client_uuid":"${m.memo}","memo_no":"$memoNo","reason_code":"retailer_cancelled","retailer_ack":true,"fix":${fix("memo_void", lat, lng)}}"""))
        }
        return out
    }

    // ---- wire helpers ----------------------------------------------------------------------------------------------

    protected fun gz(s: String): ByteArray = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    protected fun batchBody(d: Device, recs: List<JsonNode>, batchUuid: String): String = (json.createObjectNode() as ObjectNode).apply {
        put("batch_uuid", batchUuid); put("device_uuid", d.deviceUuid); put("schema_version", 1); put("app_version", ApiWorld.APP_VERSION)
        put("trigger", "manual"); put("sent_at_device", "2026-10-24T10:29:00.000Z"); put("pending_rows", 0); set<JsonNode>("time_anchors", json.createArrayNode())
        set<JsonNode>("device_counts", json.createObjectNode().also { c -> c.set<JsonNode>(day, json.createObjectNode().also { n -> d.counts.forEach { (k, v) -> n.put(k, v) } }) })
        set<JsonNode>("records", json.createArrayNode().also { a -> recs.forEach { a.add(it) } })
    }.toString()

    data class Answer(val status: Int, val body: JsonNode?, val text: String = "")

    protected fun send(d: Device, recs: List<JsonNode>, batchUuid: String = uuid()): Answer = runBlocking {
        val bytes = gz(batchBody(d, recs, batchUuid))
        val r = app.client.post("/v1/sync/batch") {
            bearerAuth(d.token); header("X-Device-Id", d.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
            header("X-Device-Proof", world.batchProof(d.deviceUuid, bytes))
            setBody(ByteArrayContent(bytes, ContentType.Application.Json))
        }
        val text = r.bodyAsText()
        Answer(r.status.value, if (r.status == HttpStatusCode.OK) json.readTree(text) else null, text)
    }

    /** Statuses with their codes: `rejected(parent_missing)`; for a non-200 answer the problem code and title. */
    protected fun detail(a: Answer): String = a.body?.path("acks")?.joinToString(" ") { "${it.path("type").asText()}:${it.path("status").asText()}${it.path("code").asText("").let { c -> if (c.isEmpty()) "" else "($c)" }}${it.path("message_key").asText("").let { c -> if (c.isEmpty()) "" else "[$c]" }}" }
        ?: ("HTTP ${a.status} " + a.text.take(300))

    protected fun acks(a: Answer): List<String> = a.body?.path("acks")?.map { it.path("status").asText() } ?: listOf("http-${a.status}")

    protected fun get(path: String, token: String, device: String? = null): HttpResponse = runBlocking {
        app.client.get(path) { bearerAuth(token); header("X-App-Version", ApiWorld.APP_VERSION); device?.let { header("X-Device-Id", it) } }
    }

    protected fun postJson(path: String, token: String, body: String): HttpResponse = runBlocking {
        app.client.post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
    }

    protected fun jsonOf(r: HttpResponse): JsonNode = runBlocking { json.readTree(r.bodyAsText()) }

    protected fun login(user: String, client: String, device: String?): String =
        runBlocking { world.login(app.client, ApiWorld.Caller(user, client, device)) } ?: error("no token for $user: ${world.logins[user]?.toString()?.take(200)}")

    protected fun fetchBundle(token: String, device: String): JsonNode = runBlocking {
        val r = app.client.get("/v1/sync/bundle?for=$day") {
            bearerAuth(token); header("X-Device-Id", device); header("X-App-Version", ApiWorld.APP_VERSION); header("Accept-Encoding", "gzip")
        }
        assertEquals(HttpStatusCode.OK, r.status, "bundle")
        val raw = r.bodyAsBytes()
        json.readTree(if (r.headers["Content-Encoding"] == "gzip") GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8) else raw.toString(Charsets.UTF_8))
    }

    // ---- state shared by the ordered steps ---------------------------------------------------------------------------

    protected lateinit var sr: Device
    protected lateinit var lkx: Device
    protected lateinit var bundle: JsonNode
    protected lateinit var route: JsonNode
    protected val skus = mutableMapOf<String, Sku>()
    protected var gulRouteId = 0L
    protected val gulPins = mutableMapOf<Long, Pair<Double, Double>>()

    /** The plan SKUs of [route] with an outlet price valid on [day], as the phone would sell them. */
    protected fun skusOf(bundle: JsonNode, route: JsonNode): Map<String, Sku> {
        val priced = bundle.path("prices").filter { it.path("price_type").asText() == "outlet" && it.path("valid_from").asText() <= day && (it.path("valid_to").isNull || it.path("valid_to").asText("") >= day) }.associateBy { it.path("sku_id").asLong() }
        val plan = route.path("sales_plan_sku_ids").map { it.asLong() }.toSet()
        val out = mutableMapOf<String, Sku>()
        for (s in bundle.path("products").path("skus")) {
            if (s.path("status").asText("active") != "active" || s.path("id").asLong() !in plan) continue
            val p = priced[s.path("id").asLong()] ?: continue
            out[s.path("code").asText()] = Sku(s.path("id").asLong(), s.path("code").asText(), s.path("category_code").asText(), s.path("base_unit").asText(), s.path("base_per_pack").asInt(),
                s.path("entry_unit_default").asText(), p.path("amount_mtk").asLong(), p.path("per_base_qty").asLong().coerceAtLeast(1))
        }
        return out
    }

    protected fun pick(category: String, perPack: Int? = null): Sku =
        skus.values.filter { it.category == category && (perPack == null || it.perPack == perPack) }.minByOrNull { it.id } ?: error("no $category SKU with pack $perPack in the bundle")

    protected fun outletByCode(code: String): Long = route.path("outlets").single { it.path("code").asText() == code }.path("outlet_id").asLong()
    protected fun radiusOf(outlet: Long): Pair<Int, Int> = route.path("outlets").single { it.path("outlet_id").asLong() == outlet }.let { it.path("radius_m").asInt(100) to it.path("max_accuracy_m").asInt(100) }

    protected fun runWorker() {
        AggregationWorker(world.fresh.db, world.clock).also { w ->
            w.runUntilIdle(); world.fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
            w.requestRebuild(LocalDate.parse(day)); w.runUntilIdle()
        }
    }

    protected open val traceName = "data-flow-trace"

    @AfterAll
    fun tearDown() {
        val out = File("build/$traceName.md").apply { parentFile.mkdirs() }
        out.writeText(trace.toString())
        println("$traceName: trace written to ${out.absolutePath}")
        runBlocking { app.stop() }; world.close()
    }

    /** Login as sr1001 on the seeded phone, fetch the Saturday bundle, and fill route, pins, SKUs and the device model. */
    protected fun prepareSrDay() {
        val token = login("sr1001", "app_sr", ApiWorld.SR_PHONE)
        bundle = fetchBundle(token, ApiWorld.SR_PHONE)
        val meta = bundle.path("meta")
        route = bundle.path("routes").single { it.path("planned_today").asBoolean() }
        gulRouteId = route.path("route_id").asLong()
        route.path("outlets").forEach { gulPins[it.path("outlet_id").asLong()] = it.path("lat").asDouble() to it.path("lng").asDouble() }
        skus += skusOf(bundle, route)
        val dev = rows("SELECT id AS did FROM app.device WHERE device_uuid = '${ApiWorld.SR_PHONE}'")
        sr = Device("sr1001", ApiWorld.SR_PHONE, dev.single()["did"] as Long, gulRouteId, meta.path("bundle_version").asText(), meta.path("config_version").asLong(), token)
        sr.seq = bundle.path("user").path("bind_ordinal").asInt() * bundle.path("user").path("memo_seq_block_size").asInt() + 1
    }

    /** The records of one sale of one line at [code] (visit, memo, line, close) with [memoNo]; [packFactorOverride] corrupts the line's pack size consistently (qty_base, line and memo money follow it). */
    protected fun oneSale(code: String, minute: Int, line: Line, memoNo: String, packFactorOverride: Int? = null): List<ObjectNode> {
        val m = Memo(outletByCode(code), code, listOf(line), paidShare = 100, minute = minute)
        val r = recordsOf(sr, m, gulPins.getValue(m.outlet), 100, 100, 20)
        r.first { it.path("type").asText() == "memo" }.path("payload").let { (it as ObjectNode).put("memo_no", memoNo) }
        if (packFactorOverride != null) r.first { it.path("type").asText() == "memo_line" }.path("payload").let {
            (it as ObjectNode).put("pack_factor", packFactorOverride).put("qty_base", line.qtyEntered.toLong() * packFactorOverride)
            val g = (line.qtyEntered.toLong() * packFactorOverride * line.sku.price + line.sku.perBase / 2) / line.sku.perBase
            it.put("gross_mtk", g)
            r.first { x -> x.path("type").asText() == "memo" }.path("payload").let { mp -> (mp as ObjectNode).put("gross_mtk", g).put("net_mtk", g).put("paid_mtk", g).put("due_mtk", 0L) }
        }
        return r
    }

    /** `status code` of the first ack of [type] (or the HTTP status). */
    protected fun ackOf(a: Answer, type: String = "memo") = a.body?.path("acks")?.firstOrNull { it.path("type").asText() == type }?.let { it.path("status").asText() + " " + it.path("code").asText("") }?.trim() ?: "HTTP ${a.status}"
}
