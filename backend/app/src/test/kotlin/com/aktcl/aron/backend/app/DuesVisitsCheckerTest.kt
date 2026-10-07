package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.sync.DuesLedger
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
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent checker (docs/26 s4) for F-SYS-060 (dues ledger, FIFO ageing), N-036 (breadcrumb storage) and F-SYS-078
 * (visit-kind policy). Every test here FAILED against the build it was written for; each one names the defect it shows.
 * Fixtures follow BatchAcceptanceTest (seed, production wiring, clock Sunday 2027-01-03 10:00 Dhaka, SR sr1001).
 * Each test uses its own outlet so the ledger balances never mix.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuesVisitsCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keyFile: File
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private var routeId = 0L
    private val outlets = mutableListOf<Long>()
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
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            outlets += h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r ORDER BY id").bind("r", routeId).mapTo(Long::class.java).list()
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
        }
        check(outlets.size >= 8) { "the seed route has ${outlets.size} outlets; the checker needs 8" }
        keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    // ---- fixtures (as BatchAcceptanceTest) -------------------------------------------------------------------------

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private var cachedToken: String? = null

    private suspend fun HttpClient.token(): String = cachedToken ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { cachedToken = it }
    }

    private fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private fun batch(records: List<JsonObject>): String = buildJsonObject {
        put("batch_uuid", uuid()); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
        put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
        put("time_anchors", kotlinx.serialization.json.JsonArray(emptyList()))
        put("device_counts", buildJsonObject { put(day, buildJsonObject { put("visit", 1) }) })
        put("records", kotlinx.serialization.json.JsonArray(records))
    }.toString()

    private suspend fun HttpClient.send(token: String, records: List<JsonObject>): JsonObject = json(post("/v1/sync/batch") {
        bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
        setBody(io.ktor.http.content.ByteArrayContent(gz(batch(records)), ContentType.Application.Json))
    }.bodyAsText())

    private fun statuses(r: JsonObject) = r["acks"]!!.jsonArray.map { it.jsonObject["status"]!!.jsonPrimitive.content }

    private fun nextAt(): String = Instant.parse("2027-01-02T18:30:00Z").plusSeconds(captureSeq.incrementAndGet() * 60L).toString()

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, at: String = nextAt(), route: Long? = routeId) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", day); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private val fixJson = """{"purpose":"PURPOSE","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    private class Sale(val visit: String, val memo: String, val memoNo: String, val records: List<JsonObject>)

    private fun visitRecord(visit: String, outlet: Long, at: String, kind: String = "sr_call") = envelope("visit", visit, visit, 0, json(
        """
        {"visit_kind":"$kind","outlet_id":$outlet,"opened_at":"$at","sequence_no":1,"planned":true,"fix":${fixJson.replace("PURPOSE", "visit_open")},
         "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
        """.trimIndent(),
    ), at)

    /** A memo of 20 + 10 sticks with [dueMtk] on credit, and its two lines; [supersedes] makes it an edit. */
    private fun memoRecords(visit: String, outlet: Long, dueMtk: Long, at: String, supersedes: String? = null, qty1: Int = 20): Triple<String, String, List<JsonObject>> {
        val memo = uuid(); val memoNo = "sr1001-270103-${memoSeq.incrementAndGet()}"
        val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
        val g1 = qty1 * p1; val g2 = 10 * p2; val gross = g1 + g2
        val edit = supersedes?.let { ""","supersedes_client_uuid":"$it","edit_reason_code":"wrong_quantity"""" } ?: ""
        val header = envelope("memo", memo, visit, 1, json(
            """
            {"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"$at",
             "price_list_date":"$day","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
             "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":${gross - dueMtk},"due_mtk":$dueMtk,"is_credit":${dueMtk > 0},"line_count":2,"discount_line_count":0,"qc_line_count":0,
             "offer_version_ids":[],"rounding_mode":"half_up_paisa"$edit}
            """.trimIndent(),
        ), at)
        fun line(no: Int, sku: Long, qty: Int, price: Long, g: Long) = envelope("memo_line", uuid(), visit, 2, json(
            """
            {"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale","qty_entered":$qty,"unit_entered":"stick","pack_factor":1,"qty_base":$qty,
             "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":$g}
            """.trimIndent(),
        ), at)
        return Triple(memo, memoNo, listOf(header, line(1, s1, qty1, p1, g1), line(2, s2, 10, p2, g2)))
    }

    private fun sale(outlet: Long, dueMtk: Long, kind: String = "sr_call"): Sale {
        val visit = uuid(); val at = nextAt()
        val (memo, memoNo, memoRecs) = memoRecords(visit, outlet, dueMtk, at)
        val close = envelope("visit_close", uuid(), visit, 1, json("""{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"$at","is_zero_sale":false}"""), at)
        return Sale(visit, memo, memoNo, listOf(visitRecord(visit, outlet, at, kind)) + memoRecs + close)
    }

    private fun collection(outlet: Long, memo: String, memoNo: String, amount: Long, outstandingBefore: Long): JsonObject {
        val cu = uuid()
        return envelope("due_collection", cu, cu, 0, json(
            """{"outlet_id":$outlet,"against_memo_client_uuid":"$memo","against_memo_no":"$memoNo","against_memo_business_date":"$day","amount_mtk":$amount,
               "is_full_settlement":${amount >= outstandingBefore},"outstanding_before_mtk":$outstandingBefore,"payment_mode":"cash"}""",
        ))
    }

    private fun void(memo: String, memoNo: String): JsonObject {
        val vu = uuid()
        return envelope("memo_void", vu, vu, 0, json(
            """{"memo_client_uuid":"$memo","memo_no":"$memoNo","reason_code":"retailer_cancelled","retailer_ack":true,"fix":${fixJson.replace("PURPOSE", "memo_void")}}""",
        ))
    }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun outletBalance(outlet: Long): Long = count("SELECT COALESCE(sum(amount_mtk), 0) FROM app.due_ledger WHERE outlet_id = $outlet")
    private fun memoBalance(memo: String): Long = count("SELECT COALESCE(sum(amount_mtk), 0) FROM app.due_ledger WHERE memo_client_uuid = '$memo'")

    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }

    // ---- F-SYS-060: out-of-order arrival must give the in-order balance ------------------------------------------

    /**
     * The phone sells on credit (due 50,000), collects 20,000, then voids the memo (the void slip shows 30,000 open).
     * In arrival order memo, collection, void the ledger nets to 0. If the collection arrives after the void (it was
     * parked by a server_error, sat in a later batch, or came from the AMO's phone), the void has already closed the
     * full 50,000 and the collection then credits another 20,000: the outlet is left at -20,000.
     * Defect: DuesLedger.kt:36 posts a collection against a memo that is no longer active; closeMemoBalance (:49) only
     * reads what is on the ledger at the moment of the void.
     */
    @Test
    fun aCollectionArrivingAfterTheVoidOfItsMemoLeavesTheInOrderBalance() = testApplication {
        app()
        val token = client.token()
        val outlet = outlets[1]
        val s = sale(outlet, 50_000)
        assertEquals(List(5) { "accepted" }, statuses(client.send(token, s.records)))
        val coll = collection(outlet, s.memo, s.memoNo, 20_000, 50_000)
        val v = void(s.memo, s.memoNo)
        assertEquals(listOf("accepted"), statuses(client.send(token, listOf(v))))
        assertEquals(listOf("accepted"), statuses(client.send(token, listOf(coll))))
        assertEquals(0L, memoBalance(s.memo), "a voided memo owes nothing and has no credit left on it, in any arrival order")
        assertEquals(0L, outletBalance(outlet), "the outlet balance is the in-order one (0), not -20,000")
    }

    /** The same race for an edit: the edited memo B closes all of A's 50,000 before A's collection lands. */
    @Test
    fun aCollectionArrivingAfterTheEditOfItsMemoLeavesTheInOrderBalance() = testApplication {
        app()
        val token = client.token()
        val outlet = outlets[2]
        val s = sale(outlet, 50_000)
        assertEquals(List(5) { "accepted" }, statuses(client.send(token, s.records)))
        val coll = collection(outlet, s.memo, s.memoNo, 20_000, 50_000)
        // The edit (after the collection on the phone) owes 30,000 on the new memo.
        val (b, _, edit) = memoRecords(s.visit, outlet, 30_000, nextAt(), supersedes = s.memo, qty1 = 19)
        assertEquals(List(3) { "accepted" }, statuses(client.send(token, edit)))
        assertEquals(listOf("accepted"), statuses(client.send(token, listOf(coll))))
        // In order: A = 50,000 - 20,000 - 30,000 (superseded) = 0, B = 30,000; outlet 30,000.
        assertEquals(30_000L, memoBalance(b))
        assertEquals(0L, memoBalance(s.memo), "the superseded memo nets to 0 in any arrival order")
        assertEquals(30_000L, outletBalance(outlet), "the outlet owes what the edited memo says, not 10,000")
    }

    // ---- F-SYS-060: a memo that is no longer active cannot be superseded again ------------------------------------

    /**
     * Two edits that both name the original memo A (a stale copy on the phone, a retry minted under a new uuid with
     * different lines, or a second device). The second edit's UPDATE matches nothing (A is already superseded) and
     * closeMemoBalance finds A's balance already 0, yet the second memo is stored active and posts its own due.
     * Result: both B and C are active and the outlet owes 40,000 + 30,000.
     * Defect: DuesLedger.kt:25-32 never checks that the superseded memo was active; nothing refuses a second edit.
     */
    @Test
    fun aSecondEditOfTheSameOriginalMemoDoesNotOweTwice() = testApplication {
        app()
        val token = client.token()
        val outlet = outlets[3]
        val s = sale(outlet, 50_000)
        assertEquals(List(5) { "accepted" }, statuses(client.send(token, s.records)))
        val (b, _, edit1) = memoRecords(s.visit, outlet, 40_000, nextAt(), supersedes = s.memo, qty1 = 19)
        assertEquals(List(3) { "accepted" }, statuses(client.send(token, edit1)))
        val (c, _, edit2) = memoRecords(s.visit, outlet, 30_000, nextAt(), supersedes = s.memo, qty1 = 18)
        client.send(token, edit2)
        val active = count("SELECT count(*) FROM app.memo WHERE client_uuid IN ('$b', '$c') AND status = 'active'")
        assertEquals(1L, active, "only one edit of A can be the live memo")
        assertTrue(outletBalance(outlet) <= 40_000L, "the outlet owes one edited memo, not both: ${outletBalance(outlet)}")
    }

    /** An edit of a memo that is already voided brings a cancelled sale's due back onto the ledger. */
    @Test
    fun anEditOfAVoidedMemoDoesNotBringItsDuesBack() = testApplication {
        app()
        val token = client.token()
        val outlet = outlets[7]
        val s = sale(outlet, 50_000)
        assertEquals(List(5) { "accepted" }, statuses(client.send(token, s.records)))
        assertEquals(listOf("accepted"), statuses(client.send(token, listOf(void(s.memo, s.memoNo)))))
        val (b, _, edit) = memoRecords(s.visit, outlet, 40_000, nextAt(), supersedes = s.memo, qty1 = 19)
        client.send(token, edit)
        assertEquals(0L, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$b' AND status = 'active'"), "an edit of a voided memo is not a live sale")
        assertEquals(0L, outletBalance(outlet), "the voided sale owes nothing: ${outletBalance(outlet)}")
    }

    // ---- F-SYS-060: a collection credits the outlet of its memo ---------------------------------------------------

    /**
     * A collection against a memo of outlet X that carries outlet_id Y (both on the SR's route) is accepted and the
     * credit is posted to Y: X keeps owing 50,000 and Y shows a 50,000 credit. The memo's open balance in the bundle
     * (openMemos) says 0 while X's outlet balance says 50,000. Defect: DuesLedger.kt:36 takes the outlet from the
     * collection payload; nothing checks it against app.memo.outlet_id.
     */
    @Test
    fun aCollectionNamingAnotherOutletThanItsMemoDoesNotMoveDuesBetweenOutlets() = testApplication {
        app()
        val token = client.token()
        val x = outlets[4]; val y = outlets[5]
        val s = sale(x, 50_000)
        assertEquals(List(5) { "accepted" }, statuses(client.send(token, s.records)))
        client.send(token, listOf(collection(y, s.memo, s.memoNo, 50_000, 50_000)))
        assertEquals(0L, outletBalance(y), "outlet Y owed nothing and must not get a credit for X's memo")
        assertEquals(count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE memo_client_uuid = '${s.memo}' AND outlet_id = $x"), memoBalance(s.memo),
            "every ledger row of a memo sits on the memo's own outlet")
    }

    // ---- F-SYS-060: FIFO ageing must not let a void or an edit pay the oldest debt --------------------------------

    /**
     * Outlet with a 45-day-old debt of 10,000 (opening balance) sells on credit today (10,000) and voids that memo
     * today. The outlet still owes the old 10,000, aged 31-60. DuesLedger.fifo (:84-87) treats the void's -10,000 as
     * a payment of the oldest debit, so ageing reports 10,000 in 0-7 and nothing in 31-60: a cancelled sale makes a
     * six-week-old debt disappear from the overdue buckets. A void/supersession cancels its own memo; only collections
     * and adjustments are FIFO payments.
     */
    @Test
    fun voidingTodaysCreditMemoDoesNotAgeAwayTheOldestDebt() = testApplication {
        app()
        val token = client.token()
        val outlet = outlets[6]
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, source_client_uuid) VALUES (?, DATE '2026-11-19', 'opening_balance', 10000, gen_random_uuid())", outlet)
        }
        val s = sale(outlet, 10_000)
        assertEquals(List(5) { "accepted" }, statuses(client.send(token, s.records)))
        assertEquals(listOf("accepted"), statuses(client.send(token, listOf(void(s.memo, s.memoNo)))))
        val a = fresh.db.jdbi.withHandle<DuesLedger.Ageing, Exception> { h -> DuesLedger.ageing(h, outlet, LocalDate.parse(day)) }
        assertEquals(10_000L, a.balance)
        assertEquals(listOf(0L, 0L, 10_000L, 0L), listOf(a.d0to7, a.d8to30, a.d31to60, a.d61plus), "the 45-day-old debt stays in 31-60")
    }

    // ---- F-SYS-060: a collection above the memo's open balance -----------------------------------------------------

    /**
     * Policy (backend-core decision 2026-10-07, docs/status/backend-core.md; the spec sets none): an over-collection is
     * cash the SR really took, so it is booked, never dropped or held: the memo goes below zero and the outlet shows
     * the surplus as credit in the ageing (buckets empty, credit = balance), visible to dues-ageing and review.
     */
    @Test
    fun aCollectionAboveTheMemosOpenBalanceIsBookedAndShowsAsOutletCredit() = testApplication {
        app()
        val token = client.token()
        val outlet = outlets[0]
        val s = sale(outlet, 50_000)
        assertEquals(List(5) { "accepted" }, statuses(client.send(token, s.records)))
        val c = collection(outlet, s.memo, s.memoNo, 80_000, 50_000)
        assertEquals(listOf("accepted"), statuses(client.send(token, listOf(c))))
        assertEquals(listOf("duplicate"), statuses(client.send(token, listOf(c))), "a resend books nothing twice")
        assertEquals(-30_000L, outletBalance(outlet))
        val a = fresh.db.jdbi.withHandle<DuesLedger.Ageing, Exception> { h -> DuesLedger.ageing(h, outlet, LocalDate.parse(day)) }
        assertEquals(listOf(0L, 0L, 0L, 0L, -30_000L), listOf(a.d0to7, a.d8to30, a.d31to60, a.d61plus, a.credit))
    }

    // ---- F-SYS-078: visit-kind policy -------------------------------------------------------------------------------

    /**
     * docs/24 s12 item 1: visits with visit_kind web_entry are created by the Web Entry endpoint. A field device can
     * nevertheless upload a visit with visit_kind web_entry (and an SR can upload amo_control_call or tso_visit): the
     * batch stores it as-is. Nothing in IngestService/RecordWriter relates visit_kind to the uploader's role, so an SR's
     * phone can book supervisor calls and web-entry rows. Defect: no visit-kind policy in the ingest path
     * (IngestService.process; TypeRules has no per-kind rule); BatchAcceptanceTest asserts the opposite with an SR token.
     */
    @Test
    fun anSrDeviceCannotStoreWebEntryOrSupervisorVisitKinds() = testApplication {
        app()
        val token = client.token()
        for (kind in listOf("web_entry", "amo_control_call", "tso_visit")) {
            val v = uuid()
            val r = client.send(token, listOf(visitRecord(v, outlets[0], nextAt(), kind)))
            assertNotEquals(listOf("accepted"), statuses(r), "an SR device upload with visit_kind $kind is refused or quarantined")
            assertEquals(0L, count("SELECT count(*) FROM app.visit WHERE client_uuid = '$v'"), "no $kind visit stored from an SR phone")
        }
    }
}
