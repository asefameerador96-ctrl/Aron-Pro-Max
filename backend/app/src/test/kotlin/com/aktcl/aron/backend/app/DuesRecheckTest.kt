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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent re-check (fresh context) of the F-SYS-060 fix 7c78803: permutations, duplicates and partial batches of
 * the same device records must end at the in-order balances. Fixtures follow DuesVisitsCheckerTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuesRecheckTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keyFile: File
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    @Volatile private var nowMs = Instant.parse("2027-01-03T04:00:00Z").toEpochMilli()
    /** Fixed clock that the test moves on by a minute when the per-device rate limit answers 429 (fixed windows). */
    private val clock = AronClock { Instant.ofEpochMilli(nowMs) }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var baseOutlet = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val memoSeq = java.util.concurrent.atomic.AtomicInteger(5000)
    private val captureSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val qtySeq = java.util.concurrent.atomic.AtomicInteger(20)
    private val outletSeq = java.util.concurrent.atomic.AtomicInteger(0)

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
            baseOutlet = h.createQuery("SELECT id FROM app.outlet WHERE route_id = :r ORDER BY id LIMIT 1").bind("r", routeId).mapTo(Long::class.java).one()
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
    fun tearDown() { wiring.database?.close(); fresh.close() }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    /** A fresh outlet on the SR's route (a copy of the first seeded one), so balances never mix between runs. */
    private fun newOutlet(): Long = fresh.db.jdbi.withHandle<Long, Exception> { h ->
        h.createQuery(
            """
            INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, sub_channel_id, geo_class, lat, lng, location_basis, location_confirmed)
            SELECT 'CHK-' || :n, name, owner_name, zone_id, route_id, cluster_id, channel, sub_channel_id, geo_class, lat, lng, location_basis, location_confirmed
            FROM app.outlet WHERE id = :o RETURNING id
            """.trimIndent(),
        ).bind("n", outletSeq.incrementAndGet()).bind("o", baseOutlet).mapTo(Long::class.java).one()
    }

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

    private suspend fun HttpClient.send(@Suppress("UNUSED_PARAMETER") token: String, records: List<JsonObject>): JsonObject {
        val body = gz(batch(records))
        repeat(20) {
            val r = post("/v1/sync/batch") {
                bearerAuth(token()); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
                setBody(io.ktor.http.content.ByteArrayContent(body, ContentType.Application.Json))
            }
            when (r.status) {
                HttpStatusCode.TooManyRequests -> nowMs += 61_000
                HttpStatusCode.Unauthorized -> cachedToken = null
                else -> return json(r.bodyAsText())
            }
        }
        error("sync kept failing")
    }

    private fun acks(r: JsonObject) = (r["acks"] ?: error("no acks: $r")).jsonArray.map { it.jsonObject }

    /** Capture times one second apart, all on the Dhaka business day before the server clock. */
    private fun nextAt(): String = Instant.parse("2027-01-02T18:31:00Z").plusSeconds(captureSeq.incrementAndGet().toLong()).toString()

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, at: String = nextAt(), bd: String = day) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", bd); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); put("route_id", routeId); put("bundle_version", "$bd:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private val fixJson = """{"purpose":"PURPOSE","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    private class M(val memo: String, val memoNo: String, val records: List<JsonObject>)

    private fun visitRecord(visit: String, outlet: Long, at: String, bd: String) = envelope("visit", visit, visit, 0, json(
        """
        {"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"$at","sequence_no":1,"planned":true,"fix":${fixJson.replace("PURPOSE", "visit_open")},
         "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
        """.trimIndent(),
    ), at, bd)

    private fun memoRecords(visit: String, outlet: Long, dueMtk: Long, at: String, bd: String = day, supersedes: String? = null): M {
        val memo = uuid(); val memoNo = "sr1001-270103-${memoSeq.incrementAndGet()}"
        val qty1 = qtySeq.incrementAndGet()
        val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
        val g1 = qty1 * p1; val g2 = 10 * p2; val gross = g1 + g2
        check(gross >= dueMtk) { "gross $gross < due $dueMtk" }
        val edit = supersedes?.let { ""","supersedes_client_uuid":"$it","edit_reason_code":"wrong_quantity"""" } ?: ""
        val header = envelope("memo", memo, visit, 1, json(
            """
            {"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"$at",
             "price_list_date":"$bd","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
             "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":${gross - dueMtk},"due_mtk":$dueMtk,"is_credit":${dueMtk > 0},"line_count":2,"discount_line_count":0,"qc_line_count":0,
             "offer_version_ids":[],"rounding_mode":"half_up_paisa"$edit}
            """.trimIndent(),
        ), at, bd)
        fun line(no: Int, sku: Long, qty: Int, price: Long, g: Long) = envelope("memo_line", uuid(), visit, 2, json(
            """
            {"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale","qty_entered":$qty,"unit_entered":"stick","pack_factor":1,"qty_base":$qty,
             "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":$g}
            """.trimIndent(),
        ), at, bd)
        return M(memo, memoNo, listOf(header, line(1, s1, qty1, p1, g1), line(2, s2, 10, p2, g2)))
    }

    private class Sale(val visit: String, val memo: String, val memoNo: String, val records: List<JsonObject>)

    private fun sale(outlet: Long, dueMtk: Long, at: String = nextAt(), bd: String = day): Sale {
        val visit = uuid()
        val m = memoRecords(visit, outlet, dueMtk, at, bd)
        val close = envelope("visit_close", uuid(), visit, 1, json("""{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"$at","is_zero_sale":false}"""), at, bd)
        return Sale(visit, m.memo, m.memoNo, listOf(visitRecord(visit, outlet, at, bd)) + m.records + close)
    }

    private fun collection(outlet: Long, memo: String?, memoNo: String, amount: Long, outstandingBefore: Long, at: String = nextAt(), bd: String = day): JsonObject {
        val cu = uuid()
        val against = memo?.let { """"against_memo_client_uuid":"$it",""" } ?: """"against_memo_client_uuid":null,"""
        return envelope("due_collection", cu, cu, 0, json(
            """{"outlet_id":$outlet,$against"against_memo_no":"$memoNo","against_memo_business_date":"$bd","amount_mtk":$amount,
               "is_full_settlement":${amount >= outstandingBefore},"outstanding_before_mtk":$outstandingBefore,"payment_mode":"cash"}""",
        ), at, bd)
    }

    private fun void(memo: String, memoNo: String, at: String = nextAt(), bd: String = day): JsonObject {
        val vu = uuid()
        return envelope("memo_void", vu, vu, 0, json(
            """{"memo_client_uuid":"$memo","memo_no":"$memoNo","reason_code":"retailer_cancelled","retailer_ack":true,"fix":${fixJson.replace("PURPOSE", "memo_void")}}""",
        ), at, bd)
    }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun outletBalance(outlet: Long, upTo: String? = null): Long =
        count("SELECT COALESCE(sum(amount_mtk), 0) FROM app.due_ledger WHERE outlet_id = $outlet" + (upTo?.let { " AND business_date <= DATE '$it'" } ?: ""))
    private fun memoBalance(memo: String): Long = count("SELECT COALESCE(sum(amount_mtk), 0) FROM app.due_ledger WHERE memo_client_uuid = '$memo'")
    private fun ageing(outlet: Long, asOf: String) = fresh.db.jdbi.withHandle<DuesLedger.Ageing, Exception> { h -> DuesLedger.ageing(h, outlet, LocalDate.parse(asOf)) }

    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }

    /**
     * Uploads [units] in the given order ([oneBatch]: all in one batch, else one unit per batch), then resends what
     * was parked (the phone's retry) until everything is accepted or a duplicate. Fails if anything stays refused.
     */
    private suspend fun HttpClient.upload(token: String, units: List<List<JsonObject>>, oneBatch: Boolean) {
        var pending: List<List<JsonObject>> = units
        repeat(8) {
            if (pending.isEmpty()) return
            val next = ArrayList<List<JsonObject>>()
            val sends = if (oneBatch) listOf(pending.flatten()) else pending
            for (s in sends) {
                val ack = acks(send(token, s))
                val bad = ack.filter { it["status"]!!.jsonPrimitive.content !in setOf("accepted", "duplicate") }.map { it["client_uuid"]!!.jsonPrimitive.content }.toSet()
                if (bad.isNotEmpty()) next += s.filter { (it["client_uuid"]!!.jsonPrimitive.content) in bad }
            }
            pending = next
        }
        // Report the final outcome of what never settled.
        val last = if (pending.isEmpty()) emptyList() else acks(send(token, pending.flatten()))
        assertTrue(pending.isEmpty(), "records never settled: " + last.map { "${it["type"]}:${it["status"]}:${it["code"]}" })
    }

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.indices.flatMap { i -> permutations(xs.take(i) + xs.drop(i + 1)).map { listOf(xs[i]) + it } }

    // ---- 1: memo, two collections, void in every order --------------------------------------------------------

    /**
     * Captured on the phone in this order: credit memo (due 50,000), collection c1, collection c2, void. Every upload
     * order (24), one record set per batch and all in one batch, plus a full resend, must end at the in-order memo
     * and outlet balance: 0 when c1+c2 < due, the over-collection when c1+c2 > due.
     */
    @Test
    fun memoTwoCollectionsAndVoidInEveryUploadOrderEndAtTheInOrderBalance() = testApplication {
        app()
        val token = client.token()
        val failures = ArrayList<String>()
        for ((c2Amount, expected) in listOf(10_000L to 0L, 40_000L to -10_000L)) {
            for (oneBatch in listOf(false, true)) {
                for (perm in permutations(listOf(0, 1, 2, 3))) {
                    val outlet = newOutlet()
                    val s = sale(outlet, 50_000)
                    val c1 = collection(outlet, s.memo, s.memoNo, 20_000, 50_000)
                    val c2 = collection(outlet, s.memo, s.memoNo, c2Amount, 30_000)
                    val v = void(s.memo, s.memoNo)
                    val units = listOf(s.records, listOf(c1), listOf(c2), listOf(v))
                    client.upload(token, perm.map { units[it] }, oneBatch)
                    client.upload(token, units, oneBatch = true) // full resend: all duplicates
                    val got = memoBalance(s.memo) to outletBalance(outlet)
                    if (got != expected to expected) failures += "c2=$c2Amount oneBatch=$oneBatch order=$perm memo/outlet=$got expected $expected"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---- 2: memo A, collection on A, edit B of A, collection on B, void B in every order --------------------

    @Test
    fun editChainWithCollectionsAndVoidInEveryUploadOrderEndsAtTheInOrderBalanceFirstHalf() = editChain(0 until 60)

    @Test
    fun editChainWithCollectionsAndVoidInEveryUploadOrderEndsAtTheInOrderBalanceSecondHalf() = editChain(60 until 120)

    private fun editChain(range: IntRange) = testApplication {
        app()
        val token = client.token()
        val failures = ArrayList<String>()
        for (perm in permutations(listOf(0, 1, 2, 3, 4)).slice(range)) {
            val outlet = newOutlet()
            val s = sale(outlet, 50_000)
            val cA = collection(outlet, s.memo, s.memoNo, 20_000, 50_000)
            val b = memoRecords(s.visit, outlet, 30_000, nextAt(), supersedes = s.memo)
            val cB = collection(outlet, b.memo, b.memoNo, 10_000, 30_000)
            val vB = void(b.memo, b.memoNo)
            val units = listOf(s.records, listOf(cA), b.records, listOf(cB), listOf(vB))
            client.upload(token, perm.map { units[it] }, oneBatch = perm.first() % 2 == 0)
            client.upload(token, units, oneBatch = true)
            // In order: A = 50,000 - 20,000 - 30,000 = 0; B = 30,000 - 10,000 - 20,000 = 0; outlet 0.
            val got = Triple(memoBalance(s.memo), memoBalance(b.memo), outletBalance(outlet))
            if (got != Triple(0L, 0L, 0L)) failures += "order=$perm A/B/outlet=$got"
            val bStatus = count("SELECT count(*) FROM app.memo WHERE client_uuid = '${b.memo}' AND status = 'voided'")
            if (bStatus != 1L) failures += "order=$perm B is not voided"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---- 3: edit chain A -> B -> C with late collections -------------------------------------------------------

    @Test
    fun editChainABCWithLateCollectionsEndsAtTheInOrderBalance() = testApplication {
        app()
        val token = client.token()
        val failures = ArrayList<String>()
        for (perm in listOf(listOf(0, 1, 2, 3, 4), listOf(0, 2, 3, 1, 4), listOf(0, 2, 3, 4, 1), listOf(0, 2, 4, 3, 1), listOf(0, 3, 2, 4, 1))) {
            val outlet = newOutlet()
            val s = sale(outlet, 50_000)
            val cA = collection(outlet, s.memo, s.memoNo, 20_000, 50_000)
            val b = memoRecords(s.visit, outlet, 40_000, nextAt(), supersedes = s.memo)
            val cB = collection(outlet, b.memo, b.memoNo, 15_000, 40_000)
            val c = memoRecords(s.visit, outlet, 25_000, nextAt(), supersedes = b.memo)
            // capture order: A, cA, B, cB, C
            val units = listOf(s.records, listOf(cA), b.records, listOf(cB), c.records)
            client.upload(token, perm.map { units[it] }, oneBatch = false)
            val got = listOf(memoBalance(s.memo), memoBalance(b.memo), memoBalance(c.memo), outletBalance(outlet))
            if (got != listOf(0L, 0L, 25_000L, 25_000L)) failures += "order=$perm A/B/C/outlet=$got"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---- 4: opening-balance collection (no memo) -------------------------------------------------------------

    /** The contract requires against_memo_client_uuid: a collection without a memo is refused and books nothing. */
    @Test
    fun aCollectionWithoutAMemoIsRefusedAndBooksNothing() = testApplication {
        app()
        val token = client.token()
        val outlet = newOutlet()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, source_client_uuid) VALUES (?, DATE '2026-11-19', 'opening_balance', 30000, gen_random_uuid())", outlet)
        }
        val c = collection(outlet, null, "OB", 12_000, 30_000)
        repeat(2) { assertEquals("rejected", acks(client.send(token, listOf(c))).single()["status"]!!.jsonPrimitive.content) }
        assertEquals(30_000L, outletBalance(outlet))
        val a = ageing(outlet, day)
        assertEquals(listOf(0L, 0L, 30_000L, 0L, 0L, 30_000L), listOf(a.d0to7, a.d8to30, a.d31to60, a.d61plus, a.credit, a.balance))
    }

    // ---- 5: balances by business date must not depend on upload order ------------------------------------------

    /**
     * Memo on 01-01 (due 50,000), collection on 01-02 (20,000), void on 01-03. In order the ledger has -20,000 on
     * 01-02 and -30,000 on 01-03; the outlet owes 30,000 as of 01-02. Uploaded void-before-collection, the void posts
     * -50,000 on 01-03 and the late adjustment +20,000 is dated on the collection's 01-02, which then nets to zero:
     * the as-of-01-02 balance and ageing become 50,000, not 30,000.
     */
    @Test
    fun theBalanceAndAgeingAsOfAnEarlierDateDoNotDependOnUploadOrder() = testApplication {
        app()
        val token = client.token()
        fun scenario(): Triple<Long, Sale, List<List<JsonObject>>> {
            val outlet = newOutlet()
            val s = sale(outlet, 50_000, at = "2026-12-31T20:00:00Z", bd = "2027-01-01")
            val c = collection(outlet, s.memo, s.memoNo, 20_000, 50_000, at = "2027-01-01T20:00:00Z", bd = "2027-01-02")
            val v = void(s.memo, s.memoNo, at = "2027-01-02T20:00:00Z", bd = "2027-01-03")
            return Triple(outlet, s, listOf(s.records, listOf(c), listOf(v)))
        }
        val (inOrder, _, u1) = scenario()
        client.upload(token, u1, oneBatch = false)
        val (late, _, u2) = scenario()
        client.upload(token, listOf(u2[0], u2[2], u2[1]), oneBatch = false)
        assertEquals(outletBalance(inOrder), outletBalance(late), "today's balance is the same")
        assertEquals(30_000L, outletBalance(inOrder, "2027-01-02"))
        val a1 = ageing(inOrder, "2027-01-02"); val a2 = ageing(late, "2027-01-02")
        assertEquals(a1, a2, "dues ageing as of 01-02 is the same whatever the upload order")
        assertEquals(outletBalance(inOrder, "2027-01-02"), outletBalance(late, "2027-01-02"), "the ledger as of 01-02 is the same whatever the upload order")
    }

    // ---- 5b: a collection captured after its memo was edited (stale bundle, second phone) ----------------------

    /**
     * Uploaded in capture order: memo A (due 50,000), edit B of A (due 50,000), then a 20,000 cash collection captured
     * after the edit but naming A (a stale bundle on the second shared phone, or the AMO's open-memo list). The cash
     * was taken: the collection row is booked, but the late-collection adjustment gives the 20,000 straight back to A's
     * closure, so the outlet still owes 50,000. The rule cannot tell "captured before the edit, uploaded late" from
     * "captured after the edit" because it never looks at captured_at.
     */
    @Test
    fun aCollectionCapturedAfterTheEditStillReducesWhatTheOutletOwes() = testApplication {
        app()
        val token = client.token()
        val outlet = newOutlet()
        val s = sale(outlet, 50_000)
        val b = memoRecords(s.visit, outlet, 50_000, nextAt(), supersedes = s.memo)
        val c = collection(outlet, s.memo, s.memoNo, 20_000, 50_000) // captured after B
        client.upload(token, listOf(s.records, b.records, listOf(c)), oneBatch = false)
        assertEquals(-20_000L, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE entry_kind = 'collection' AND outlet_id = $outlet"), "the cash is booked")
        assertEquals(30_000L, outletBalance(outlet), "20,000 cash taken from the outlet must reduce what it owes")
    }

    // ---- 6: FIFO ageing, pure ---------------------------------------------------------------------------------

    /** Property: on random ledgers, buckets + credit == balance, buckets >= 0, credit <= 0 and never both nonzero. */
    @Test
    fun fifoBucketsPlusCreditEqualTheBalanceOnRandomLedgers() {
        val asOf = LocalDate.parse("2027-01-03")
        val rnd = Random(60)
        repeat(20_000) { iter ->
            val rows = ArrayList<DuesLedger.Entry>()
            val memos = (0 until rnd.nextInt(1, 6)).map { "m$it" }
            for (m in memos) {
                val d = asOf.minusDays(rnd.nextLong(-5, 120))
                val due = rnd.nextLong(1, 100_000)
                rows += DuesLedger.Entry(d, "memo_due", due, m)
                repeat(rnd.nextInt(0, 3)) { rows += DuesLedger.Entry(d.plusDays(rnd.nextLong(0, 20)), "collection", -rnd.nextLong(1, 60_000), m) }
                if (rnd.nextBoolean()) {
                    val kind = if (rnd.nextBoolean()) "memo_void" else "memo_superseded"
                    rows += DuesLedger.Entry(d.plusDays(rnd.nextLong(0, 30)), kind, -rnd.nextLong(1, due + 1), m)
                    if (rnd.nextBoolean()) rows += DuesLedger.Entry(d.plusDays(rnd.nextLong(0, 30)), "adjustment", rnd.nextLong(1, 30_000), m, DuesLedger.LATE_COLLECTION)
                }
            }
            if (rnd.nextBoolean()) rows += DuesLedger.Entry(asOf.minusDays(rnd.nextLong(0, 200)), "opening_balance", rnd.nextLong(1, 50_000))
            if (rnd.nextBoolean()) rows += DuesLedger.Entry(asOf.minusDays(rnd.nextLong(0, 200)), "collection", -rnd.nextLong(1, 50_000))
            if (rnd.nextBoolean()) rows += DuesLedger.Entry(asOf.minusDays(rnd.nextLong(0, 200)), "adjustment", rnd.nextLong(-20_000, 20_000).takeIf { it != 0L } ?: 1, null, "manual")
            val inWindow = rows.filter { !it.date.isAfter(asOf) }.sortedBy { it.date }
            val a = DuesLedger.fifo(inWindow, asOf)
            val buckets = a.d0to7 + a.d8to30 + a.d31to60 + a.d61plus
            assertEquals(inWindow.sumOf { it.amount }, a.balance, "iter $iter")
            assertEquals(a.balance, buckets + a.credit, "iter $iter rows=$inWindow")
            assertTrue(listOf(a.d0to7, a.d8to30, a.d31to60, a.d61plus).all { it >= 0 } && a.credit <= 0, "iter $iter $a")
            assertTrue(buckets == 0L || a.credit == 0L, "iter $iter both buckets and credit: $a")
            // Order of rows within a date must not change the result.
            assertEquals(a, DuesLedger.fifo(inWindow.shuffled(Random(iter)).sortedBy { it.date }, asOf), "iter $iter order")
        }
    }

    /** Bucket edges: ages 7|8, 30|31, 60|61. */
    @Test
    fun fifoBucketEdges() {
        val asOf = LocalDate.parse("2027-03-01")
        fun at(days: Long, amt: Long) = DuesLedger.Entry(asOf.minusDays(days), "memo_due", amt, "m$days")
        val a = DuesLedger.fifo(listOf(at(61, 1), at(60, 10), at(31, 100), at(30, 1_000), at(8, 10_000), at(7, 100_000), at(0, 1_000_000)).sortedBy { it.date }, asOf)
        assertEquals(listOf(1_100_000L, 11_000L, 110L, 1L), listOf(a.d0to7, a.d8to30, a.d31to60, a.d61plus))
    }

    /**
     * A void inside the window whose memo_due is dated after asOf (device business dates out of step) is a plain
     * credit; and a late-collection adjustment in the window whose void is after asOf must not raise the memo above
     * its own due.
     */
    @Test
    fun fifoCancellationsWithTheirCounterpartOutsideTheWindow() {
        val asOf = LocalDate.parse("2027-01-02")
        // Memo 01-01 due 50,000; collection 01-02 -20,000; the void and its late-collection adjustment are dated on the
        // void's date 01-03 (outside the window), so the ledger as of 01-02 holds only the memo and the collection.
        val ledger = listOf(
            DuesLedger.Entry(LocalDate.parse("2027-01-01"), "memo_due", 50_000, "a"),
            DuesLedger.Entry(LocalDate.parse("2027-01-02"), "collection", -20_000, "a"),
            DuesLedger.Entry(LocalDate.parse("2027-01-03"), "memo_void", -50_000, "a"),
            DuesLedger.Entry(LocalDate.parse("2027-01-03"), "adjustment", 20_000, "a", DuesLedger.LATE_COLLECTION),
        )
        val rows = ledger.filter { !it.date.isAfter(asOf) }
        assertEquals(0L, DuesLedger.fifo(ledger, LocalDate.parse("2027-01-03")).balance, "after the void the memo owes nothing")
        val a = DuesLedger.fifo(rows, asOf)
        assertEquals(30_000L, a.balance, "as of 01-02 the outlet owes 50,000 - 20,000 collected; the void comes later")
    }
}
