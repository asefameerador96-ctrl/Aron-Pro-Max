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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUD-TP-1 / N-014: the idempotent-sync convergence harness for `POST /v1/sync/batch` (CLAUDE.md's most important
 * rule). Each run builds a random day of field work (sale families with credit, collections, some voids) and delivers
 * it under a random schedule: shuffled order (children before parents), random batch splits, whole-batch replays
 * (same batch_uuid), records duplicated into later batches, two batches sent concurrently, and a dropped tail. The
 * phone then resends everything not yet final, as the outbox does, until it converges. The oracle is the reference
 * model: every record stored exactly once, the ledger of every memo equal to its in-order balance, a replay answered
 * with the same acks. Seeded: `ARON_FUZZ_SEED` (default fixed) and `ARON_FUZZ_RUNS` (default 12); the seed of a
 * failing run is in the assertion message, so `ARON_FUZZ_SEED=<seed> ARON_FUZZ_RUNS=1` replays it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncConvergenceFuzzTest {
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
        check(outlets.size >= 4) { "the seed route has ${outlets.size} outlets; the harness needs 4" }
        // The harness sends many batches from one phone under a fixed clock: lift the per-minute limits.
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.api.rl.device_per_min', 'global', 0, '600'::jsonb, now() - interval '1 day', max(config_version), 'fuzz' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.api.rl.user_per_min', 'global', 0, '2000'::jsonb, now() - interval '1 day', max(config_version), 'fuzz' FROM app.cfg_version")
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

    private fun batch(records: List<JsonObject>, batchUuid: String = uuid()): String = buildJsonObject {
        put("batch_uuid", batchUuid); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
        put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
        put("time_anchors", kotlinx.serialization.json.JsonArray(emptyList()))
        put("device_counts", buildJsonObject { put(day, buildJsonObject { put("visit", 1) }) })
        put("records", kotlinx.serialization.json.JsonArray(records))
    }.toString()

    private suspend fun HttpClient.send(token: String, records: List<JsonObject>): JsonObject = sendBody(token, batch(records))

    /** Sends one batch body as is (a replay sends the same body, same batch_uuid). */
    private suspend fun HttpClient.sendBody(token: String, body: String): JsonObject {
        val r = post("/v1/sync/batch") {
            bearerAuth(token); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz(body), ContentType.Application.Json))
        }
        val text = r.bodyAsText()
        // 503 (database briefly unavailable) or 429 is an infrastructure answer: no acks, the phone resends later.
        return if (r.status == HttpStatusCode.OK) json(text) else buildJsonObject { put("http_status", r.status.value); put("body", text) }
    }

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

    private val baseSeed = System.getenv("ARON_FUZZ_SEED")?.toLongOrNull() ?: 20261007L
    private val runs = System.getenv("ARON_FUZZ_RUNS")?.toIntOrNull()?.coerceIn(1, 100_000) ?: 12

    /** The reference model of one run: what the phone captured, and what the server must end up holding. */
    private class Model {
        val records = mutableListOf<JsonObject>()
        val memoDue = LinkedHashMap<String, Long>() // memo client_uuid -> expected open balance at the end
    }

    /**
     * Expected `server_totals` money of the day. Every run uses the same user and business date, so it is cumulative
     * over the runs of this test: a doubled number anywhere (memo, line, collection) shows up here.
     */
    private class Totals {
        var activeMemos = 0; var gross = 0L; var due = 0L; var collected = 0L
        val sold = sortedMapOf<String, Long>()
        val accepted = sortedMapOf<String, Int>()
    }
    private val expected = Totals()

    private fun model(rnd: kotlin.random.Random): Model {
        val m = Model()
        repeat(rnd.nextInt(2, 6)) {
            val outlet = outlets[rnd.nextInt(4)]
            val due = 1_000L * rnd.nextInt(1, 40)
            val s = sale(outlet, due)
            m.records += s.records
            var open = due
            if (rnd.nextInt(3) == 0) {
                val paid = minOf(due, 1_000L * rnd.nextInt(1, 40))
                m.records += collection(outlet, s.memo, s.memoNo, paid, due)
                open -= paid
                expected.collected += paid
            }
            if (rnd.nextInt(5) == 0) {
                m.records += void(s.memo, s.memoNo)
                open = 0 // a voided memo owes nothing, in every arrival order (F-SYS-060)
            } else {
                expected.activeMemos++; expected.gross += 20 * skus[0].second + 10 * skus[1].second; expected.due += due
                expected.sold.merge(skus[0].first.toString(), 20L, Long::plus); expected.sold.merge(skus[1].first.toString(), 10L, Long::plus)
            }
            m.memoDue[s.memo] = open
        }
        return m
    }

    private fun stored(ack: JsonObject) = ack["status"]!!.jsonPrimitive.content in setOf("accepted", "duplicate")

    @Test
    fun everyScheduleConvergesToTheReferenceModel() = testApplication {
        app()
        val token = client.token()
        println("SyncConvergenceFuzzTest: ARON_FUZZ_SEED=$baseSeed ARON_FUZZ_RUNS=$runs")
        var retryableSeen = 0
        var duplicateSeen = 0
        for (run in 0 until runs) {
            val seed = baseSeed + run
            val rnd = kotlin.random.Random(seed)
            val m = model(rnd)
            val acks = HashMap<String, JsonObject>()
            fun note(resp: JsonObject) = resp["acks"]?.jsonArray?.forEach { a ->
                a.jsonObject.let {
                    if (it["retryable"]?.jsonPrimitive?.content == "true") retryableSeen++
                    if (it["status"]?.jsonPrimitive?.content == "duplicate") duplicateSeen++
                    acks[it["client_uuid"]!!.jsonPrimitive.content] = it
                }
            }

            // 1. Chaos: shuffled, split, duplicated, replayed, partly concurrent, tail dropped.
            val shuffled = m.records.shuffled(rnd)
            val keep = if (rnd.nextBoolean()) shuffled.size else rnd.nextInt(1, shuffled.size + 1)
            val chunks = mutableListOf<List<JsonObject>>()
            var i = 0
            while (i < keep) { val n = rnd.nextInt(1, 6); chunks += shuffled.subList(i, minOf(keep, i + n)); i += n }
            val bodies = chunks.map { c ->
                val extra = if (rnd.nextInt(3) == 0 && m.records.isNotEmpty()) listOf(m.records[rnd.nextInt(m.records.size)]) else emptyList()
                batch((c + extra).distinctBy { it["client_uuid"]!!.jsonPrimitive.content })
            }
            var b = 0
            while (b < bodies.size) {
                if (rnd.nextInt(6) == 0) {
                    // The same batch twice in flight (a retry fired before the first answer): the claimed batch row has
                    // no stored response yet, the state a crash between commit and response leaves. Each copy is
                    // answered with acks or an infrastructure retry; the oracle below proves nothing was stored twice.
                    val twin = coroutineScope { listOf(async { client.sendBody(token, bodies[b]) }, async { client.sendBody(token, bodies[b]) }).awaitAll() }
                    twin.forEach { r ->
                        val st = r["http_status"]?.jsonPrimitive?.content?.toInt()
                        assertTrue(st == null || st == 409 || st == 503 || st == 429, "seed=$seed: a concurrent copy is answered with acks or a retry: $r")
                        note(r)
                    }
                    b++
                } else if (b + 1 < bodies.size && rnd.nextInt(4) == 0) {
                    val pair = coroutineScope { listOf(async { client.sendBody(token, bodies[b]) }, async { client.sendBody(token, bodies[b + 1]) }).awaitAll() }
                    pair.forEach(::note); b += 2
                } else {
                    // A lost response (timeout, process killed): the server stored the batch, the phone saw no acks, so
                    // its outbox resends those records later under a new batch_uuid and they must come back duplicate.
                    val first = client.sendBody(token, bodies[b]); if (rnd.nextInt(4) != 0) note(first)
                    if (rnd.nextInt(3) == 0) {
                        val replay = client.sendBody(token, bodies[b])
                        if (first["acks"] != null) {
                            assertEquals("true", replay["replayed"]?.jsonPrimitive?.content, "seed=$seed: a replay of a completed batch is answered from the stored response: $replay")
                            assertEquals(first["acks"], replay["acks"], "seed=$seed: a replayed batch_uuid answers the same acks")
                        }
                    }
                    b++
                }
            }

            // 2. The outbox: resend whatever is not final yet, in capture order, until nothing moves.
            repeat(6) {
                val pending = m.records.filter { r -> acks[r["client_uuid"]!!.jsonPrimitive.content]?.let(::stored) != true }
                if (pending.isEmpty()) return@repeat
                pending.chunked(50).forEach { note(client.send(token, it)) }
            }

            // 2b. The whole day resent once more under new batch_uuids (a reinstalled outbox, a lost ack of the last
            // batch): every record must answer duplicate and change nothing.
            var last: JsonObject? = null
            m.records.shuffled(rnd).chunked(20).forEach { c ->
                val r = client.send(token, c); last = r
                val notDup = r["acks"]?.jsonArray?.filter { it.jsonObject["status"]!!.jsonPrimitive.content != "duplicate" }
                assertTrue(notDup != null && notDup.isEmpty(), "seed=$seed: a full resend answers duplicate for every record: ${notDup ?: r}")
            }

            // 3. The oracle.
            m.records.groupBy { it["type"]!!.jsonPrimitive.content }.forEach { (t, rs) -> expected.accepted.merge(t, rs.size, Int::plus) }
            val totals = last!!["server_totals"]!!.jsonArray.map { it.jsonObject }.single { it["business_date"]!!.jsonPrimitive.content == day }
            val money = totals["money"]!!.jsonObject
            fun mny(k: String) = money[k]!!.jsonPrimitive.content.toLong()
            assertEquals(
                listOf(expected.activeMemos.toLong(), expected.gross, expected.gross, expected.due, expected.gross - expected.due, expected.collected),
                listOf(mny("active_memo_count"), mny("gross_mtk"), mny("net_mtk"), mny("due_mtk"), mny("paid_mtk"), mny("due_collected_mtk")),
                "seed=$seed: server_totals money (count, gross, net, due, paid, collected) equals the model, cumulative over runs",
            )
            assertEquals(expected.sold.toMap(), money["sold_qty_base_by_sku"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }, "seed=$seed: sold sticks by sku")
            val outcomeCounts = totals["by_type"]!!.jsonObject
            assertEquals(expected.accepted.toMap(), outcomeCounts.mapValues { it.value.jsonObject["accepted"]!!.jsonPrimitive.content.toInt() }, "seed=$seed: server_totals accepted per type")
            assertTrue(outcomeCounts.values.all { it.jsonObject["rejected"]!!.jsonPrimitive.content == "0" && it.jsonObject["quarantined"]!!.jsonPrimitive.content == "0" }, "seed=$seed: nothing left rejected or quarantined: $outcomeCounts")
            val notStored = m.records.filter { r -> acks[r["client_uuid"]!!.jsonPrimitive.content]?.let(::stored) != true }
                .map { it["type"]!!.jsonPrimitive.content + ":" + (acks[it["client_uuid"]!!.jsonPrimitive.content]?.toString() ?: "no ack") }
            assertTrue(notStored.isEmpty(), "seed=$seed: every record is stored after the outbox drains: $notStored")
            val byType = m.records.groupBy { it["type"]!!.jsonPrimitive.content }
            for ((type, recs) in byType) {
                val ids = recs.joinToString(",") { "'" + it["client_uuid"]!!.jsonPrimitive.content + "'" }
                val (table, col) = when (type) {
                    "visit" -> "app.visit" to "client_uuid"
                    "visit_close" -> "app.visit" to "close_client_uuid"
                    "memo" -> "app.memo" to "client_uuid"
                    "memo_line" -> "app.memo_line" to "client_uuid"
                    "due_collection" -> "app.due_collection" to "client_uuid"
                    "memo_void" -> "app.memo_void" to "client_uuid"
                    else -> continue
                }
                assertEquals(recs.size.toLong(), count("SELECT count(*) FROM $table WHERE $col IN ($ids)"), "seed=$seed: $type stored exactly once each")
            }
            for ((memo, open) in m.memoDue) {
                assertEquals(open, memoBalance(memo), "seed=$seed: memo $memo ends at its in-order balance")
            }

            // 4. A stored client_uuid resent with other content is quarantined payload_conflict and changes nothing.
            val victim = m.records[rnd.nextInt(m.records.size)]
            val changed = JsonObject(victim + ("captured_elapsed_ms" to kotlinx.serialization.json.JsonPrimitive(1)))
            val conflict = client.send(token, listOf(changed))["acks"]!!.jsonArray.single().jsonObject
            assertEquals("payload_conflict", conflict["code"]?.jsonPrimitive?.content, "seed=$seed: same uuid, other content: $conflict")
            for ((memo, open) in m.memoDue) assertEquals(open, memoBalance(memo), "seed=$seed: a payload conflict leaves memo $memo unchanged")

            // 5. A batch_uuid reused with other content is refused whole (409 ERR_SYNC_BATCH_UUID_REUSED), nothing stored.
            if (bodies.isNotEmpty()) {
                val reusedUuid = json(bodies[0])["batch_uuid"]!!.jsonPrimitive.content
                val stranger = sale(outlets[0], 1_000L)
                val r = client.sendBody(token, batch(stranger.records, reusedUuid))
                assertEquals("409", r["http_status"]?.jsonPrimitive?.content, "seed=$seed: reused batch_uuid with other content: $r")
                assertTrue("ERR_SYNC_BATCH_UUID_REUSED" in (r["body"]?.jsonPrimitive?.content ?: ""), "seed=$seed: $r")
                assertEquals(0L, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${stranger.visit}'"), "seed=$seed: nothing of a refused batch is stored")
            }
        }
        // The schedules must really have been hostile: parents missing (retryable answers) and duplicates seen.
        if (runs >= 5) {
            assertTrue(retryableSeen > 0, "no schedule sent a child before its parent")
            assertTrue(duplicateSeen > 0, "no schedule sent a duplicate")
        }
    }
}
