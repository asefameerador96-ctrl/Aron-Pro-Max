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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.assertTrue

/**
 * Independent checker (T1) for F-SYS-055, F-SYS-048, F-SYS-014 and F-SYS-062 (sync ingest rules, docs/24 s3.3,
 * s4.2 to s4.6, s7.3, s7.4, s11.4). Each test reproduces a defect and fails while it is there. Same seed, wiring and
 * clock as BatchCheckerTest (Sunday 2027-01-03 10:00 Dhaka, sr1001 on the dev phone).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestRulesCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var keyFile: File
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val memoSeq = java.util.concurrent.atomic.AtomicInteger(200)
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
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 3")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
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

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.p(k: String, v: JsonElement) = JsonObject(this + ("payload" to JsonObject(this["payload"]!!.jsonObject + (k to v))))

    private suspend fun HttpClient.token(): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content
    }

    private fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private fun batch(records: List<JsonElement>, batchUuid: String = uuid()): String = buildJsonObject {
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
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText().take(400))
        return json(r.bodyAsText())
    }

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, at: String, route: Long? = routeId) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", day); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); route?.let { put("route_id", it) }; put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private fun fix(purpose: String, lat: Double = 23.80) = """{"purpose":"$purpose","fix_status":"ok","lat":$lat,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    private fun nextAt(): String = Instant.parse("2027-01-03T03:00:00Z").plusSeconds(captureSeq.incrementAndGet() * 60L).toString()

    data class L(val sku: Long, val qty: Int, val price: Long, val kind: String = "sale")
    data class D(val kind: String, val value: Long, val sku: Long?)
    data class Q(val sku: Long, val settlement: Long, val applied: Boolean)

    private fun divHalfUp(a: Long, b: Long): Long { val q = a / b; val r = a % b; return if (2 * Math.abs(r) >= b) q + (if (a < 0) -1 else 1) else q }

    /**
     * A visit family with one memo: visit, memo, lines, discounts, QC lines, visit close. Totals follow docs/24 s7.4
     * unless [lineCount] or [grossDelta] say otherwise. Returns the records in phone order.
     */
    private fun memoFamily(
        lines: List<L>, discounts: List<D> = emptyList(), qcs: List<Q> = emptyList(), lineCount: Int? = null, grossDelta: Long = 0,
        memoNo: String = "sr1001-270103-${memoSeq.incrementAndGet()}", at: String = nextAt(),
    ): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val gross = lines.sumOf { it.qty * it.price } + grossDelta
        val offer = discounts.filter { it.kind != "drp" }.sumOf { it.value }
        val drp = discounts.filter { it.kind == "drp" }.sumOf { it.value }
        val qc = qcs.filter { it.applied }.sumOf { it.settlement }
        val raw = gross - offer - drp - qc
        val net = divHalfUp(raw, 10) * 10
        val paid = maxOf(net, 0); val due = net - paid
        val visitPayload = json(
            """
            {"visit_kind":"sr_call","outlet_id":$outletId,"opened_at":"$at","sequence_no":1,"planned":true,"fix":${fix("visit_open")},
             "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}
            """.trimIndent(),
        )
        val memoPayload = json(
            """
            {"visit_client_uuid":"$visit","outlet_id":$outletId,"memo_no":"$memoNo","memo_kind":"${if (lines.isEmpty()) "zero_sale" else "sale"}","committed_at":"$at",
             "price_list_date":"$day","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":$offer,"drp_discount_mtk":$drp,"qc_deduction_mtk":$qc,
             "round_adj_mtk":${net - raw},"net_mtk":$net,"paid_mtk":$paid,"due_mtk":$due,"is_credit":${due > 0},"line_count":${lineCount ?: lines.size},
             "discount_line_count":${discounts.size},"qc_line_count":${qcs.count { it.applied }},"offer_version_ids":[],"rounding_mode":"half_up_paisa"}
            """.trimIndent(),
        )
        val lineRecs = lines.mapIndexed { i, l ->
            envelope("memo_line", uuid(), visit, 2, json(
                """
                {"memo_client_uuid":"$memo","line_no":${i + 1},"sku_id":${l.sku},"line_kind":"${l.kind}","qty_entered":${l.qty},"unit_entered":"stick","pack_factor":1,"qty_base":${l.qty},
                 "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":${l.price},"price_per_qty":1,"gross_mtk":${l.qty * l.price}}
                """.trimIndent(),
            ), at)
        }
        val discRecs = discounts.map { d ->
            envelope("memo_discount", uuid(), visit, 2, buildJsonObject {
                put("memo_client_uuid", memo); put("kind", d.kind); put("sku_id", d.sku?.let { JsonPrimitive(it) } ?: JsonNull)
                put("qty_base", if (d.sku != null) 3 else 0); put("value_mtk", d.value)
            }, at)
        }
        val qcRecs = qcs.map { q ->
            envelope("qc_line", uuid(), visit, 2, buildJsonObject {
                put("visit_client_uuid", visit); put("memo_client_uuid", if (q.applied) JsonPrimitive(memo) else JsonNull); put("applied_to_memo", q.applied)
                put("sku_id", q.sku); put("fault_type_code", "torn_pack"); put("fault_group", "MFC"); put("qty_base", 2)
                put("unit_price_mtk", q.settlement / 2); put("settlement_mtk", q.settlement)
            }, at)
        }
        val close = json("""{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"$at","is_zero_sale":${lines.isEmpty()}}""")
        return listOf(envelope("visit", visit, visit, 0, visitPayload, at), envelope("memo", memo, visit, 1, memoPayload, at)) +
            lineRecs + discRecs + qcRecs + envelope("visit_close", uuid(), visit, 1, close, at)
    }

    private fun twoLines(): List<L> = listOf(L(skus[0].first, 20, skus[0].second), L(skus[1].first, 10, skus[1].second))

    /** The same records with every UUID re-minted consistently (a phone that lost its outbox ids). */
    private fun remint(family: List<JsonObject>): List<JsonObject> {
        val map = HashMap<String, String>()
        fun swap(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { swap(it.value) })
            is JsonArray -> JsonArray(e.map { swap(it) })
            is JsonPrimitive -> if (e.isString && Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(e.content)) JsonPrimitive(map.getOrPut(e.content) { uuid() }) else e
            else -> e
        }
        return family.map { swap(it) as JsonObject }
    }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun acks(r: JsonObject) = r["acks"]!!.jsonArray.map { it.jsonObject }
    private fun codes(r: JsonObject) = acks(r).map { a -> a.s("status") + ((a["code"] as? JsonPrimitive)?.takeIf { it.isString }?.let { ":" + it.content } ?: "") }

    private fun ApplicationTestBuilder.app() = application { aronApi(wiring) }

    // ---------------------------------------------------------------------------------------------------------------
    // F-SYS-062 / s7.4: QC lines are part of the memo arithmetic and must be stored with the memo.

    @Test
    fun checkerAQcLineAppliedToAMemoIsStoredWithTheMemo() = testApplication {
        app()
        val token = client.token()
        val fam = memoFamily(twoLines(), qcs = listOf(Q(skus[0].first, 15_000, applied = true)))
        val r = client.ok(token, batch(fam))
        val memo = fam[1].s("client_uuid")
        // The memo was stored with qc_deduction_mtk 15,000 (net reduced by the QC credit) ...
        assertEquals(List(fam.size) { "accepted" }, codes(r), "every record of a consistent memo with a QC line is stored")
        // ... so its QC line must exist, or the deduction has no line behind it.
        assertEquals(1, count("SELECT count(*) FROM app.qc_entry_line WHERE memo_client_uuid = '$memo'"), "the QC line of the memo was not stored")
    }

    @Test
    fun checkerAZeroSaleWithAQcCreditAndNegativeNetIsStored() = testApplication {
        app()
        val token = client.token()
        val fam = memoFamily(emptyList(), qcs = listOf(Q(skus[0].first, 15_000, applied = true)))
        val r = client.ok(token, batch(fam))
        assertEquals(List(fam.size) { "accepted" }, codes(r), "zero sale with a QC credit (net -15,000, s7.4 allow_negative_net)")
    }

    // F-SYS-062 / s7.4 "line_count = the number of child records": more lines than the header states, all in one batch.
    @Test
    fun checkerAMemoWithMoreLinesThanItsHeaderCountsIsNotAcceptedSilently() = testApplication {
        app()
        val token = client.token()
        val lines = twoLines() + L(skus[2].first, 50, skus[2].second)
        // Header: line_count 2 and gross of the first two lines only; the third line (50 sticks) is sent too.
        val fam = memoFamily(lines, lineCount = 2, grossDelta = -50L * skus[2].second)
        val r = client.ok(token, batch(fam))
        val memo = fam[1].s("client_uuid")
        val unflagged = count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo' AND NOT ('arithmetic_mismatch' = ANY(server_flags))")
        assertEquals(0, unflagged, "memo stored as good although its lines sum to more than its gross and line_count: ${codes(r)}")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F-SYS-014: an unknown-SKU line must not leave a stored memo whose gross has a line missing behind it.

    @Test
    fun checkerAnUnknownSkuLineNeverLeavesAHalfAppliedMemo() = testApplication {
        app()
        val token = client.token()
        val fam = memoFamily(twoLines())
        val bad = fam[3].p("sku_id", JsonPrimitive(987_654_321))
        val records = fam.take(3) + bad + fam.drop(4)
        val r = client.ok(token, batch(records))
        val memo = fam[1].s("client_uuid")
        val halfApplied = count(
            """
            SELECT count(*) FROM app.memo m WHERE m.client_uuid = '$memo' AND m.status = 'active' AND cardinality(m.server_flags) = 0
              AND m.line_count > (SELECT count(*) FROM app.memo_line l WHERE l.memo_client_uuid = m.client_uuid)
            """.trimIndent(),
        )
        assertEquals(0, halfApplied, "memo stored active and unflagged with line_count 2 and one stored line (gross includes a line that does not exist): ${codes(r)}")
    }

    // F-SYS-014 / s4.5: children of a memo quarantined for review follow it, they are not parked for a parent that will
    // never be stored by a resend.
    @Test
    fun checkerLinesOfAQuarantinedMemoAreHeldWithItNotParkedForever() = testApplication {
        app()
        val token = client.token()
        val first = memoFamily(twoLines())
        client.ok(token, batch(first))
        // A different sale that reuses the memo number: the memo is quarantined memo_no_duplicate.
        val second = memoFamily(listOf(L(skus[2].first, 5, skus[2].second)), memoNo = first[1]["payload"]!!.jsonObject.s("memo_no"))
        val r = client.ok(token, batch(second))
        val c = codes(r)
        assertEquals("quarantined:memo_no_duplicate", c[1], "precondition: $c")
        assertEquals("quarantined:memo_no_duplicate", c[2], "the line of a quarantined memo is acked $c[2]: the phone retries it and gives up, and if the memo is accepted on review its line is gone")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F-SYS-055 / s4.5 content_duplicate "same outlet, lines and minute".

    @Test
    fun checkerASaleReCommittedInTheSameMinuteUnderANewNumberIsAContentDuplicate() = testApplication {
        app()
        val token = client.token()
        val at = "2027-01-03T03:30:05.000Z"
        val first = memoFamily(twoLines(), at = at)
        assertEquals(List(first.size) { "accepted" }, codes(client.ok(token, batch(first))))
        // The phone re-creates the same sale 20 s later (same outlet, same lines, same minute): new uuids, new memo number.
        val again = remint(first).map { rec ->
            val o = JsonObject(rec + ("captured_at" to JsonPrimitive("2027-01-03T03:30:25.000Z")))
            if (o.s("type") == "memo") o.p("memo_no", JsonPrimitive("sr1001-270103-${memoSeq.incrementAndGet()}")).p("committed_at", JsonPrimitive("2027-01-03T03:30:25.000Z")) else o
        }
        val r = client.ok(token, batch(again))
        assertEquals("quarantined:content_duplicate", codes(r)[1], "a second sale with the same outlet, lines and minute was stored: ${codes(r)}")
    }

    @Test
    fun checkerAReMintedStockMovementSentConcurrentlyIsStoredOnce() = testApplication {
        app()
        val token = client.token()
        val bad = mutableListOf<String>()
        repeat(12) { i ->
            val at = nextAt()
            val cu = uuid()
            val payload = buildJsonObject {
                put("kind", "issue"); put("sku_id", skus[0].first); put("qty_entered", 100 + i); put("unit_entered", "stick")
                put("pack_factor", 1); put("qty_base", 100 + i); put("slip_printed", true)
            }
            val original = envelope("stock_movement", cu, cu, 0, payload, at)
            val reminted = remint(listOf(original)).single()
            coroutineScope {
                val a = async { client.send(token, batch(listOf(original))) }
                val b = async { client.send(token, batch(listOf(reminted))) }
                a.await(); b.await()
            }
            val n = count("SELECT count(*) FROM app.stock_movement WHERE captured_at = '$at'::timestamptz")
            if (n != 1L) bad += "round $i: $n rows"
        }
        assertTrue(bad.isEmpty(), "the same stock issue under a re-minted uuid was stored twice: $bad")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // F-SYS-048 / F-SYS-055: one malformed record never breaks the batch or its replay.

    @Test
    fun checkerAReplayOfABatchWithANonStringClientUuidReturnsTheStoredResponse() = testApplication {
        app()
        val token = client.token()
        val cu = uuid()
        val malformed = JsonObject(envelope("visit_skip", cu, cu, 0, buildJsonObject { put("outlet_id", outletId); put("reason_code", "closed") }, nextAt()) + ("client_uuid" to JsonPrimitive(12345)))
        val records = memoFamily(twoLines()) + malformed
        val b = uuid()
        val first = client.ok(token, batch(records, b))
        assertEquals("rejected", acks(first).last().s("status"))
        // The phone did not see the answer and resends the identical batch (s3.3 item 2).
        val replay = client.send(token, batch(records, b))
        assertEquals(HttpStatusCode.OK, replay.status, "the replay of a batch holding one malformed record fails: ${replay.bodyAsText().take(300)}")
    }

    @Test
    fun checkerANulCharacterInOneRecordDoesNotFailTheBatch() = testApplication {
        app()
        val token = client.token()
        val cu = uuid()
        val poison = envelope("visit_skip", cu, cu, 0, buildJsonObject { put("outlet_id", outletId); put("reason_code", "closed\u0000") }, nextAt())
        val good = memoFamily(twoLines())
        val r = client.send(token, batch(listOf(poison) + good))
        assertEquals(HttpStatusCode.OK, r.status, "one record with a NUL character failed the whole batch: ${r.status} ${r.bodyAsText().take(300)}")
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${good[1].s("client_uuid")}'"), "the good sale after the poison record was not stored")
    }
}
