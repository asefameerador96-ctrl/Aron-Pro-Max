package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent T1 checker for F-API-048 (data void) and F-API-041 (config_ack handler). Each test uses its own
 * route-day (distinct business dates) so barriers never interfere. Clock: 2027-01-03 10:00 Dhaka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataVoidCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private var routeId = 0L
    private var outletId = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val memoSeq = AtomicInteger(100)
    private val captureSeq = AtomicInteger(0)

    /** Test-only handler that parks one chosen record between the barrier check and the write. */
    @Volatile private var holdUuid: String? = null
    private val reached = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val holder = object : RecordHandler {
        override val types = setOf("visit")
        override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
            if (rec.clientUuid == holdUuid) { reached.countDown(); release.await(30, TimeUnit.SECONDS) }
            return null
        }
    }

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
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test mfa off' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
        }
        val keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(
            Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock,
            extraRecordHandlers = listOf(holder),
        )
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private var srToken: String? = null
    private val webTokens = HashMap<String, String>()

    private suspend fun HttpClient.sr(): String = srToken ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { srToken = it }
    }

    private suspend fun HttpClient.web(user: String): String = webTokens[user] ?: run {
        val r = post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$password","client":"web"}""") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { webTokens[user] = it }
    }

    private fun batch(records: List<JsonObject>): String = buildJsonObject {
        put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
        put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
        put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {})
        put("records", JsonArray(records))
    }.toString()

    private suspend fun HttpClient.send(body: String): List<JsonObject> {
        val token = sr()
        val r = post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9")
            header("Content-Encoding", "gzip"); setBody(io.ktor.http.content.ByteArrayContent(gz(body), ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject }
    }

    private suspend fun HttpClient.voidDay(user: String, date: String, scope: String = "all", cu: String = uuid()): HttpResponse {
        val token = web(user)
        return post("/v1/admin/data-void") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"$cu","route_id":$routeId,"business_date":"$date","scope":"$scope","reason":"Wrong day uploaded by mistake"}""")
        }
    }

    /** Captured at 09:mm Dhaka on [date] (03:mm UTC), always before the 10:00 Dhaka barrier of the fixed clock. */
    private fun capturedAt(date: String) = "${date}T03:%02d:00.000Z".format(captureSeq.incrementAndGet() % 59)

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, date: String, at: String) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", date); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", true); put("route_id", routeId); put("bundle_version", "$date:3")
        put("bundle_stale", false); put("config_version", 1); put("payload", payload)
    }

    private val fixJson = """{"purpose":"PURPOSE","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    private class Sale(val records: List<JsonObject>, val visit: String, val memo: String, val memoNo: String)

    private fun sale(date: String, dueMtk: Long = 0): Sale {
        val d = LocalDate.parse(date)
        val memoNo = "sr1001-%02d%02d%02d-%d".format(d.year % 100, d.monthValue, d.dayOfMonth, memoSeq.incrementAndGet())
        val visit = uuid(); val memo = uuid(); val at = capturedAt(date)
        val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
        val g1 = 20 * p1; val g2 = 10 * p2; val gross = g1 + g2
        val visitPayload = json(
            """{"visit_kind":"sr_call","outlet_id":$outletId,"opened_at":"$at","sequence_no":1,"planned":true,"fix":${fixJson.replace("PURPOSE", "visit_open")},
             "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
        )
        val memoPayload = json(
            """{"visit_client_uuid":"$visit","outlet_id":$outletId,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"$at",
             "price_list_date":"$date","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
             "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":${gross - dueMtk},"due_mtk":$dueMtk,"is_credit":${dueMtk > 0},"line_count":2,"discount_line_count":0,"qc_line_count":0,
             "offer_version_ids":[],"rounding_mode":"half_up_paisa"}""",
        )
        fun line(no: Int, sku: Long, qty: Int, price: Long, g: Long) = json(
            """{"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale","qty_entered":$qty,"unit_entered":"stick","pack_factor":1,"qty_base":$qty,
             "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":$g}""",
        )
        return Sale(
            listOf(
                envelope("visit", visit, visit, 0, visitPayload, date, at),
                envelope("memo", memo, visit, 1, memoPayload, date, at),
                envelope("memo_line", uuid(), visit, 2, line(1, s1, 20, p1, g1), date, at),
                envelope("memo_line", uuid(), visit, 2, line(2, s2, 10, p2, g2), date, at),
            ),
            visit, memo, memoNo,
        )
    }

    private fun memoVoid(s: Sale, date: String): JsonObject {
        val vu = uuid()
        return envelope("memo_void", vu, vu, 0, json(
            """{"memo_client_uuid":"${s.memo}","memo_no":"${s.memoNo}","reason_code":"retailer_cancelled","retailer_ack":true,"fix":${fixJson.replace("PURPOSE", "memo_void")}}""",
        ), date, capturedAt(date))
    }

    private fun ledgerSum(memo: String) = count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE memo_client_uuid = '$memo'")
    private fun memoStatus(memo: String) = fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT status FROM app.memo WHERE client_uuid = CAST(:m AS uuid)").bind("m", memo).mapTo(String::class.java).one() }
    private fun statuses(acks: List<JsonObject>) = acks.map { it["status"]!!.jsonPrimitive.content }

    /**
     * Money: a credit memo of day D1 voided by a memo_void on day D2 has a zero open balance. A data void of D1 must not
     * take the due off a second time; DataVoidApi.reverseDues reverses the memo's memo_due without seeing the memo_void
     * row (different source_client_uuid), so the outlet ends with a phantom credit of the whole due.
     */
    @Test
    fun voidingTheDayOfAMemoAlreadyVoidedOnALaterDayDoesNotReverseItsDueTwice() = testApplication {
        application { aronApi(wiring) }
        val d1 = "2026-12-29"; val d2 = "2026-12-30"
        val s = sale(d1, dueMtk = 500)
        assertEquals(List(4) { "accepted" }, statuses(client.send(batch(s.records))))
        assertEquals(500, ledgerSum(s.memo))
        assertEquals(listOf("accepted"), statuses(client.send(batch(listOf(memoVoid(s, d2))))))
        assertEquals(0, ledgerSum(s.memo), "memo_void closed the balance")
        val r = client.voidDay("admin1001", d1)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(0, ledgerSum(s.memo), "the memo owed nothing before the data void; its due must not be reversed again (phantom outlet credit)")
    }

    /**
     * Money / consistency: voiding the day of a cross-day memo_void puts the due back on the ledger (+500 adjustment)
     * while the memo row stays status 'voided', so the outlet owes on a memo every memo report shows as voided.
     */
    @Test
    fun voidingTheDayOfACrossDayMemoVoidLeavesLedgerAndMemoStatusConsistent() = testApplication {
        application { aronApi(wiring) }
        val d1 = "2026-12-31"; val d2 = "2027-01-01"
        val s = sale(d1, dueMtk = 500)
        assertEquals(List(4) { "accepted" }, statuses(client.send(batch(s.records))))
        assertEquals(listOf("accepted"), statuses(client.send(batch(listOf(memoVoid(s, d2))))))
        val r = client.voidDay("admin1001", d2)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val status = memoStatus(s.memo); val open = ledgerSum(s.memo)
        assertTrue((status == "voided" && open == 0L) || (status == "active" && open == 500L), "memo status $status but ledger open balance $open")
    }

    /**
     * Scope: a `web_entry` void must only touch web-entry rows (docs/16 D-577: the barrier is written for scope
     * include_app_memos or all). DataVoidApi writes the barrier for every scope and the handler ignores scope, so a
     * real app sale captured before a web-entry-only void is finally rejected voided_by_admin (lost), while the app
     * rows already stored for the same route-day stay active.
     */
    @Test
    fun aWebEntryOnlyVoidDoesNotRejectLateAppSales() = testApplication {
        application { aronApi(wiring) }
        val d = "2027-01-02"
        val r = client.voidDay("tso1001", d, scope = "web_entry")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val s = sale(d)
        val acks = client.send(batch(s.records))
        assertEquals(List(4) { "accepted" }, statuses(acks), acks.toString())
    }

    /**
     * Race: the barrier is checked (DataVoidBarrierHandler.check) before the row is written, with no lock that a void
     * waits on. A void that commits between the check and the write leaves a row captured before the barrier stored
     * and active, which is exactly what the barrier exists to prevent.
     */
    @Test
    fun aVoidCommittingWhileARowOfTheSameRouteDayIsInFlightLeavesNoActiveRowCapturedBeforeTheBarrier() = testApplication {
        application { aronApi(wiring) }
        val d = "2026-12-28"
        client.sr(); client.web("admin1001")
        val s = sale(d)
        holdUuid = s.visit
        val (vr, acks) = coroutineScope {
            val ingest = async(Dispatchers.IO) { client.send(batch(s.records.take(1))) }
            withContext(Dispatchers.IO) { assertTrue(reached.await(20, TimeUnit.SECONDS), "ingest did not reach the hold point") }
            val v = async(Dispatchers.IO) { client.voidDay("admin1001", d) }
            val vr = v.await()
            release.countDown()
            vr to ingest.await()
        }
        assertEquals(HttpStatusCode.OK, vr.status, vr.bodyAsText())
        val active = count("SELECT count(*) FROM app.visit WHERE client_uuid = '${s.visit}' AND voided_at IS NULL")
        assertEquals(0, active, "visit captured before the barrier is active after the void; ack=$acks")
    }

    /**
     * Late rows: every row of a family captured before the void is rejected voided_by_admin, final (docs/24 s4.5 table).
     * The parent check (IngestService step 9) runs before the barrier handler (step 10), so the memo and its lines of a
     * late family are parked parent_missing, retryable: the phone keeps resending them until the family skip.
     */
    @Test
    fun everyRowOfALateFamilyCapturedBeforeTheVoidIsAFinalVoidedByAdmin() = testApplication {
        application { aronApi(wiring) }
        val d = "2027-01-03"
        val r = client.voidDay("admin1001", d, scope = "all")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val acks = client.send(batch(sale(d).records))
        assertEquals(List(4) { "voided_by_admin" }, acks.map { it["code"]?.jsonPrimitive?.content }, acks.toString())
    }

    /** Roles: docs/24 s8.5 gives data void to ADMIN/SUPERADMIN only (docs/15 F-ADM-058: app memos need a higher role than TSO). */
    @Test
    fun aTsoCannotVoidAppMemosOfARouteDay() = testApplication {
        application { aronApi(wiring) }
        assertEquals(HttpStatusCode.Forbidden, client.voidDay("tso1001", "2026-12-27", scope = "all").status)
        assertEquals(HttpStatusCode.Forbidden, client.voidDay("tso1001", "2026-12-27", scope = "app_memos").status)
    }

    /**
     * F-API-041: a config_ack whose payload config_version is not a number is a malformed record (final schema_invalid);
     * ConfigAckHandler calls jsonPrimitive on it, throws, and the record is parked server_error retryable, so the phone
     * resends a poison row until the family skip.
     */
    @Test
    fun aConfigAckWithANonNumericVersionIsAFinalSchemaRejectionNotARetryableServerError() = testApplication {
        application { aronApi(wiring) }
        val cu = uuid()
        val rec = buildJsonObject {
            put("type", "config_ack"); put("client_uuid", cu); put("family_uuid", uuid()); put("rank", 0); put("schema_version", 1)
            put("business_date", "2027-01-03"); put("captured_at", "2027-01-03T03:59:00.000Z"); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
            put("clock_offset_ms", 0); put("captured_offline", false); put("bundle_version", "2027-01-03:3"); put("bundle_stale", false); put("config_version", 1)
            put("payload", buildJsonObject { put("applied_at", "2027-01-03T03:59:00.000Z"); put("config_version", JsonArray(listOf(JsonPrimitive(1)))); put("keys", JsonArray(listOf(JsonPrimitive("cfg.a")))) })
        }
        val ack = client.send(batch(listOf(rec))).single()
        assertEquals("rejected", ack["status"]!!.jsonPrimitive.content, ack.toString())
        assertEquals("schema_invalid", ack["code"]!!.jsonPrimitive.content, ack.toString())
        assertEquals("false", ack["retryable"]!!.jsonPrimitive.content, ack.toString())
    }
}
