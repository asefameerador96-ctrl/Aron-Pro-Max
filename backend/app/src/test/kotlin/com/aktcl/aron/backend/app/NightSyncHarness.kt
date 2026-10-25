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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals

/**
 * Shared harness of the owner's night rows (D-NIGHT-DAYCLOSE-01, D-NIGHT-CHECKOUT-01): its own database on the seed,
 * the production wiring under a fixed clock (Sunday 2027-01-03 10:00 Dhaka, as SyncReadsTest), sr1001 on route
 * MIR-SR-D, batches through POST /v1/sync/batch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class NightSyncHarness {
    protected lateinit var fresh: FreshDb
    protected lateinit var wiring: Wiring
    private lateinit var keyFile: File
    protected val password = "throwaway-" + System.nanoTime()
    protected val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    protected val day = "2027-01-03"
    protected var routeId = 0L
    protected var outletId = 0L
    protected var srId = 0L
    protected val skus = mutableListOf<Pair<Long, Long>>() // sku id to outlet price
    protected val memoSeq = AtomicInteger(100)
    private val seq = AtomicInteger(0)

    @BeforeAll
    fun setUpHarness() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            srId = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            outletId = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = :r").bind("r", routeId).mapTo(Long::class.java).one()
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
    fun tearDownHarness() { wiring.securityStore?.close(); wiring.database?.close(); fresh.close() }

    /** The seeded, bound development phone of sr1001 (db/seed/05_dev_device.sql). */
    protected val seededPhone = "00000000-0000-4000-8000-000000000001"

    /** A second phone of sr1001 (another install or device) as the seed makes the first: enrolled, active, bound at [ordinal]. */
    protected fun addPhone(uuid: String, ordinal: Int) = fresh.db.jdbi.useHandle<Exception> { h ->
        val cols = h.createQuery("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'device' AND column_name NOT IN ('id') AND is_generated = 'NEVER' AND column_default IS DISTINCT FROM 'now()' ORDER BY ordinal_position")
            .mapTo(String::class.java).list()
        val repl = mapOf("device_uuid" to "CAST('$uuid' AS uuid)", "public_key_thumbprint" to "'test-$uuid'")
        h.execute("INSERT INTO app.device (${cols.joinToString()}) SELECT ${cols.joinToString { repl[it] ?: it }} FROM app.device WHERE device_uuid = '$seededPhone'")
        h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, $srId, $ordinal, 'support' FROM app.device d WHERE d.device_uuid = '$uuid'")
    }

    protected fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    protected fun uuid() = UUID.randomUUID().toString()
    protected fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private val tokens = HashMap<String, String>()

    /** One login per user and phone (the login limiter never empties under the fixed clock). */
    protected suspend fun HttpClient.token(user: String, device: String): String = tokens["$user/$device"] ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"$user","password":"$password","client":"app_sr","device_uuid":"$device"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { tokens["$user/$device"] = it }
    }

    /** One batch from [device]; the acks as "status:code". */
    protected suspend fun HttpClient.send(token: String, device: String, records: List<JsonObject>): List<String> {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", device); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val r = post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", device); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { a -> a.jsonObject.let { "${it["status"]!!.jsonPrimitive.content}:${(it["code"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "-"}" } }
    }

    protected fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, date: String = day, route: Long? = routeId): JsonObject {
        val n = seq.incrementAndGet()
        val at = Instant.parse("${date}T00:00:00Z").minusSeconds(6 * 3600).plusSeconds(3600L + n * 60L).toString()
        return buildJsonObject {
            put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
            put("business_date", date); put("captured_at", at); put("captured_elapsed_ms", 18330000 + n); put("boot_count", 412)
            put("clock_offset_ms", -1840); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$date:3")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", payload)
        }
    }

    protected fun attendance(kind: String, date: String = day) = uuid().let {
        val purpose = if (kind == "check_in") "attendance_in" else "attendance_out"
        envelope("attendance_event", it, it, 0, json(
            """
            {"kind":"$kind","fix":{"purpose":"$purpose","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
             "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}
            """.trimIndent(),
        ), date, route = null)
    }

    protected fun daySubmit(date: String = day, route: Long = routeId) = uuid().let {
        envelope("day_submit", it, it, 0, buildJsonObject {
            put("scope", "route_day"); put("submit_cycle", 1)
            put("device_counts", buildJsonObject {})
            put("device_money", buildJsonObject {
                for (k in listOf("active_memo_count", "gross_mtk", "offer_discount_mtk", "drp_discount_mtk", "qc_deduction_mtk", "net_mtk", "paid_mtk", "due_mtk", "due_collected_mtk")) put(k, 0)
                put("net_by_category_mtk", buildJsonObject {}); put("issued_qty_base_by_sku", buildJsonObject {}); put("sold_qty_base_by_sku", buildJsonObject {})
            })
            put("rejected_count", 0); put("quarantined_count", 0); put("pending_count", 0); put("submitted_with_dues", false)
            put("dues_outstanding_mtk", 0); put("retailers_with_dues", 0); put("stock_slip_printed", false)
        }, date, route)
    }

    /** A sale family: visit, memo with two lines (arithmetic reproduces), visit close. [packFactor] other than 1 on line 2 makes it a pack line. */
    protected fun sale(memoNo: String = "sr1001-270103-${memoSeq.incrementAndGet()}", packFactor: Int = 1): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
        val q2 = 10 * packFactor
        val g1 = 20 * p1; val g2 = q2 * p2; val gross = g1 + g2
        val visitPayload = json(
            """
            {"visit_kind":"sr_call","outlet_id":$outletId,"opened_at":"2027-01-03T03:41:00.120Z","sequence_no":1,"planned":true,
             "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                    "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
             "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
            """.trimIndent(),
        )
        val memoPayload = json(
            """
            {"visit_client_uuid":"$visit","outlet_id":$outletId,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"2027-01-03T03:43:00.120Z",
             "price_list_date":"$day","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
             "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":2,"discount_line_count":0,"qc_line_count":0,
             "offer_version_ids":[],"rounding_mode":"half_up_paisa"}
            """.trimIndent(),
        )
        fun line(no: Int, sku: Long, entered: Int, unit: String, factor: Int, qty: Int, price: Long, g: Long) = json(
            """
            {"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale","qty_entered":$entered,"unit_entered":"$unit","pack_factor":$factor,"qty_base":$qty,
             "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":$g}
            """.trimIndent(),
        )
        val close = json("""{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"2027-01-03T03:43:10.000Z","is_zero_sale":false}""")
        return listOf(
            envelope("visit", visit, visit, 0, visitPayload),
            envelope("memo", memo, visit, 1, memoPayload),
            envelope("memo_line", uuid(), visit, 2, line(1, s1, 20, "stick", 1, 20, p1, g1)),
            envelope("memo_line", uuid(), visit, 2, if (packFactor == 1) line(2, s2, 10, "stick", 1, 10, p2, g2) else line(2, s2, 10, "pack", packFactor, q2, p2, g2)),
            envelope("visit_close", uuid(), visit, 1, close),
        ).map { r ->
            // Each family is its own sale minute (s4.5 content fingerprint: outlet, lines and minute).
            if (r["type"]!!.jsonPrimitive.content != "memo") r
            else JsonObject(r + ("payload" to JsonObject(r["payload"]!!.jsonObject + ("committed_at" to r["captured_at"]!!))))
        }
    }

    protected fun cu(r: JsonObject) = r["client_uuid"]!!.jsonPrimitive.content
}
