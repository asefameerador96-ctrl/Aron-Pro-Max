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
import io.ktor.server.testing.testApplication
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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Money correctness gate, server side (docs/31 money row, F-SYS-062, docs/24 s7.3 and s7.4, D-18, D-19). Owner: qa lane.
 *
 * Real `POST /v1/sync/batch` on the seed (sr1001 on the dev phone, route MIR-SR-D). Every expected amount is computed
 * in this file with BigInteger/BigDecimal from the lines alone (an oracle that shares no code with shared:rules), then
 * compared with what PostgreSQL stored. Prices are set to 3-decimal and pack prices (7.935 Tk a stick, 158.705 Tk per 20
 * sticks, 92.005 Tk per dozen of 12) so line gross values carry milli-taka that a per-line rounding would move.
 *
 * Proves: stored gross/discount/QC/net/round_adj/paid/due equal the oracle; net is rounded once (a header rounded per
 * line is quarantined); a replay of the same records (same client_uuid) returns `duplicate` and changes no amount, and
 * a re-send with changed amounts under the same client_uuid changes no amount; a negative net is stored only for a zero
 * gross (D-18). Pending and findings are marked `@Disabled` with the finding id; enable them when the owning lane fixes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoneyIngestOracleTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private var token: String? = null
    private val memoSeq = AtomicInteger(0)
    private val captureSeq = AtomicInteger(0)

    /** SKU id, price per [per] base units (mtk), [per]: the snapshot each line and QC line carries. */
    data class Sku(val id: Long, val priceMtk: Long, val per: Long)
    private val skus = mutableListOf<Sku>()

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
            val ids = h.createQuery("SELECT s.id FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 4")
                .mapTo(Long::class.java).list()
            // 3-decimal and pack prices (docs/24 s7.1 and s7.3): the gross of a line is not a whole paisa.
            listOf(7_935L to 1L, 158_705L to 20L, 92_005L to 12L, 9_200L to 1L).zip(ids).forEach { (p, id) ->
                h.createUpdate("UPDATE app.sku_price SET amount_mtk = :a, per_base_qty = :q WHERE sku_id = :s AND price_type = 'outlet'")
                    .bind("a", p.first).bind("q", p.second).bind("s", id).execute()
                skus += Sku(id, p.first, p.second)
            }
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'qa money gate rl' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.api.rl.device_per_min', 'global', 0, '600'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.api.rl.user_per_min', 'global', 0, '2000'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    // ---------------------------------------------------------------------------------------------------------------
    // The oracle (BigInteger / BigDecimal; HALF_UP is half away from zero for both signs)

    private fun big(n: Long) = BigInteger.valueOf(n)
    private fun halfAway(n: BigInteger, d: Long): BigInteger = BigDecimal(n).divide(BigDecimal(d), 0, RoundingMode.HALF_UP).toBigIntegerExact()
    private fun lineGross(qty: Long, s: Sku): Long = halfAway(big(qty) * big(s.priceMtk), s.per).longValueExact()

    data class L(val sku: Sku, val qty: Long)
    data class Q(val sku: Sku, val defect: Long)

    /** Expected stored numbers of one memo, from its lines alone. */
    data class Expected(val gross: Long, val offer: Long, val drp: Long, val qc: Long, val net: Long, val roundAdj: Long, val paid: Long, val due: Long)

    private fun oracle(lines: List<L>, drp: List<Long>, qcs: List<Q>, paid: (Long) -> Long): Expected {
        val gross = lines.fold(BigInteger.ZERO) { a, l -> a + big(lineGross(l.qty, l.sku)) }
        val drpSum = drp.fold(BigInteger.ZERO) { a, v -> a + big(v) }
        val qc = qcs.fold(BigInteger.ZERO) { a, q -> a + big(lineGross(q.defect, q.sku)) } // D-34: defect x price at capture
        val raw = gross - drpSum - qc
        val net = halfAway(raw, 10) * BigInteger.TEN // D-19: once, to the paisa
        val p = paid(net.longValueExact())
        return Expected(gross.longValueExact(), 0, drpSum.longValueExact(), qc.longValueExact(), net.longValueExact(), (net - raw).longValueExact(), p, net.longValueExact() - p)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Records

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun uuid() = UUID.randomUUID().toString()
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun nextAt(): String = Instant.parse("2027-01-03T03:00:00Z").plusSeconds(captureSeq.incrementAndGet() * 20L).toString()

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, at: String) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", day); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", -1840); put("captured_offline", true); put("route_id", routeId); put("bundle_version", "$day:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private val fix = """{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    /** One memo family; the header states [stated] (the oracle unless a test tampers with it). Returns (records, memo uuid). */
    private fun family(lines: List<L>, drp: List<Long>, qcs: List<Q>, stated: Expected): Pair<List<JsonObject>, String> {
        val at = nextAt(); val visit = uuid(); val memo = uuid()
        val memoNo = "sr1001-270103-" + memoSeq.incrementAndGet().toString().padStart(3, '0')
        val out = mutableListOf<JsonObject>()
        out += envelope("visit", visit, visit, 0, json(
            """{"visit_kind":"sr_call","outlet_id":$outletId,"opened_at":"$at","sequence_no":1,"planned":true,"fix":$fix,
               "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
        ), at)
        out += envelope("memo", memo, visit, 1, json(
            """{"visit_client_uuid":"$visit","outlet_id":$outletId,"memo_no":"$memoNo","memo_kind":"${if (lines.isEmpty()) "zero_sale" else "sale"}","committed_at":"$at",
               "price_list_date":"$day","price_type":"outlet","gross_mtk":${stated.gross},"offer_discount_mtk":${stated.offer},"drp_discount_mtk":${stated.drp},
               "qc_deduction_mtk":${stated.qc},"round_adj_mtk":${stated.roundAdj},"net_mtk":${stated.net},"paid_mtk":${stated.paid},"due_mtk":${stated.due},
               "is_credit":${stated.due > 0},"line_count":${lines.size},"discount_line_count":${drp.size},"qc_line_count":${qcs.size},
               "offer_version_ids":[],"rounding_mode":"half_up_paisa"}""",
        ), at)
        lines.forEachIndexed { i, l ->
            out += envelope("memo_line", uuid(), visit, 2, json(
                """{"memo_client_uuid":"$memo","line_no":${i + 1},"sku_id":${l.sku.id},"line_kind":"sale","qty_entered":${l.qty},"unit_entered":"stick","pack_factor":1,
                   "qty_base":${l.qty},"price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":${l.sku.priceMtk},"price_per_qty":${l.sku.per},
                   "gross_mtk":${lineGross(l.qty, l.sku)}}""",
            ), at)
        }
        drp.forEach { v ->
            out += envelope("memo_discount", uuid(), visit, 2, buildJsonObject {
                put("memo_client_uuid", memo); put("kind", "drp"); put("sku_id", JsonNull); put("qty_base", 0); put("value_mtk", v)
            }, at)
        }
        qcs.forEach { q ->
            out += envelope("qc_line", uuid(), visit, 2, buildJsonObject {
                put("visit_client_uuid", visit); put("memo_client_uuid", memo); put("applied_to_memo", true)
                put("sku_id", q.sku.id); put("fault_type_code", "torn_pack"); put("fault_group", "MFC"); put("qty_base", q.defect)
                put("unit_price_mtk", q.sku.priceMtk); put("settlement_mtk", lineGross(q.defect, q.sku))
            }, at)
        }
        out += envelope("visit_close", uuid(), visit, 1, json(
            """{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"$at","is_zero_sale":${lines.isEmpty()}}""",
        ), at)
        return out to memo
    }

    private fun gz(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private suspend fun HttpClient.login(): String = token ?: run {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"sr1001","password":"$password","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content.also { token = it }
    }

    /** Sends [records] in one batch and returns client_uuid to "status[:code]". */
    private suspend fun HttpClient.send(records: List<JsonElement>, batchUuid: String = uuid()): Map<String, String> {
        val body = buildJsonObject {
            put("batch_uuid", batchUuid); put("device_uuid", devPhone); put("schema_version", 1); put("app_version", "1.0.9+9")
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val t = login()
        val r = post("/v1/sync/batch") {
            bearerAuth(t); header("X-Device-Id", devPhone); header("X-App-Version", "1.0.9+9"); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz(body), ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText().take(400))
        return json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject }.associate { a ->
            a.s("client_uuid") to a.s("status") + ((a["code"] as? JsonPrimitive)?.takeIf { it.isString }?.let { ":" + it.content } ?: "")
        }
    }

    /** The stored header amounts and child sums of a memo, or null when it was not stored. */
    private fun stored(memo: String): Expected? = fresh.db.jdbi.withHandle<Expected?, Exception> { h ->
        h.createQuery(
            """
            SELECT gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, round_adj_mtk, paid_mtk, due_mtk FROM app.memo
            WHERE client_uuid = CAST(:m AS uuid)
            """.trimIndent(),
        ).bind("m", memo).map { rs, _ -> Expected(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)) }
            .findOne().orElse(null)
    }

    private fun childSums(memo: String): Triple<Long, Long, Long> = fresh.db.jdbi.withHandle<Triple<Long, Long, Long>, Exception> { h ->
        h.createQuery(
            """
            SELECT (SELECT COALESCE(sum(gross_mtk), 0) FROM app.memo_line WHERE memo_client_uuid = CAST(:m AS uuid)),
                   (SELECT COALESCE(sum(value_mtk), 0) FROM app.memo_discount WHERE memo_client_uuid = CAST(:m AS uuid)),
                   (SELECT COALESCE(sum(settlement_mtk), 0) FROM app.qc_entry_line WHERE memo_client_uuid = CAST(:m AS uuid) AND applied_to_memo)
            """.trimIndent(),
        ).bind("m", memo).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.one()
    }

    private fun count(sql: String, memo: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).bind("m", memo).mapTo(Long::class.java).one() }

    private fun flags(memo: String): String = fresh.db.jdbi.withHandle<String, Exception> { h ->
        h.createQuery("SELECT COALESCE(server_flags::text, '{}') FROM app.memo WHERE client_uuid = CAST(:m AS uuid)").bind("m", memo).mapTo(String::class.java).one()
    }

    /** A random memo whose net is >= 0 (QC and DRP never exceed the gross); paid is a whole paisa in [0, net]. */
    private fun randomMemo(rnd: Random): Triple<List<L>, List<Long>, List<Q>> {
        val lines = List(rnd.nextInt(1, 7)) { L(skus.random(rnd), rnd.nextLong(1, 400)) }
        val gross = lines.sumOf { lineGross(it.qty, it.sku) }
        val drp = if (rnd.nextInt(3) == 0) listOf(rnd.nextLong(0, gross / 4 + 1)) else emptyList()
        val qcs = if (rnd.nextInt(3) == 0) listOf(Q(skus.random(rnd), rnd.nextLong(1, 6))).filter { lineGross(it.defect, it.sku) <= gross / 2 } else emptyList()
        return Triple(lines, drp, qcs)
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun storedMemoAmountsEqualTheIndependentOracle() = testApplication {
        application { aronApi(wiring) }
        val rnd = Random(31)
        var memos = 0; var notWholePaisaGross = 0
        repeat(6) { b ->
            val batch = List(8) {
                val (lines, drp, qcs) = randomMemo(rnd)
                val e = oracle(lines, drp, qcs) { net -> (rnd.nextLong(0, net + 1) / 10) * 10 }
                if (e.gross % 10 != 0L) notWholePaisaGross++
                Triple(family(lines, drp, qcs, e), e, Triple(lines, drp, qcs))
            }
            val acks = client.send(batch.flatMap { it.first.first })
            for ((fam, e, _) in batch) {
                val (records, memo) = fam
                records.forEach { r -> assertEquals("accepted", acks[r.s("client_uuid")], "batch $b ${r.s("type")} of memo $memo") }
                assertEquals(e, stored(memo), "stored memo $memo equals the oracle")
                assertEquals(Triple(e.gross, e.drp, e.qc), childSums(memo), "stored children of $memo sum to the header")
                assertEquals("{}", flags(memo), "no price or arithmetic flag on a correct memo")
                memos++
            }
        }
        assertEquals(48, memos)
        assertTrue(notWholePaisaGross > 10, "the generator must produce gross values that are not a whole paisa ($notWholePaisaGross)")
    }

    @Test
    fun aHeaderRoundedPerLineIsQuarantinedNeverStored() = testApplication {
        application { aronApi(wiring) }
        // Two lines of 1 stick at 7.935: once-rounded 15.870 (D-19); per line 7.94 + 7.94 = 15.88.
        val lines = listOf(L(skus[0], 1), L(skus[0], 1))
        val good = oracle(lines, emptyList(), emptyList()) { it }
        assertEquals(15_870L, good.net)
        val perLine = good.copy(net = 15_880, roundAdj = 15_880 - 15_870, paid = 15_880, due = 0)
        val (records, memo) = family(lines, emptyList(), emptyList(), perLine)
        val acks = client.send(records)
        assertEquals("quarantined:arithmetic_mismatch", acks[memo], "a per-line-rounded net is refused (D-19)")
        assertEquals(null, stored(memo))
        // The same lines with the once-rounded header are stored.
        val (ok, okMemo) = family(lines, emptyList(), emptyList(), good)
        val okAcks = client.send(ok)
        assertEquals("accepted", okAcks[okMemo])
        assertEquals(good, stored(okMemo))
    }

    @Test
    fun aReplayedSaleReturnsTheOriginalAmounts() = testApplication {
        application { aronApi(wiring) }
        val lines = listOf(L(skus[1], 37), L(skus[2], 11), L(skus[0], 3))
        val qcs = listOf(Q(skus[0], 2))
        val e = oracle(lines, listOf(1_005), qcs) { net -> net / 2 / 10 * 10 }
        val (records, memo) = family(lines, listOf(1_005), qcs, e)
        val batchUuid = uuid()
        assertTrue(client.send(records, batchUuid).values.all { it == "accepted" })
        val ledger = "SELECT count(*) FROM app.due_ledger WHERE memo_client_uuid = CAST(:m AS uuid)"
        val ledgerSum = "SELECT COALESCE(sum(amount_mtk), 0) FROM app.due_ledger WHERE memo_client_uuid = CAST(:m AS uuid)"
        val before = Triple(stored(memo), count(ledger, memo), count(ledgerSum, memo))
        assertEquals(e, before.first)
        // The same batch again (a retried upload), then the same records in a new batch (a re-sent outbox).
        client.send(records, batchUuid)
        val again = client.send(records)
        records.forEach { r -> assertEquals("duplicate", again[r.s("client_uuid")], "replayed ${r.s("type")}") }
        assertEquals(before, Triple(stored(memo), count(ledger, memo), count(ledgerSum, memo)), "a replay changes no amount and no dues entry")
        assertEquals(1L, count("SELECT count(*) FROM app.memo WHERE client_uuid = CAST(:m AS uuid)", memo))
        // The same client_uuid with different amounts (a corrupted or re-priced copy): never applied over the original.
        val changed = records.map { r ->
            if (r.s("type") != "memo") r else JsonObject(r + ("payload" to JsonObject(r["payload"]!!.jsonObject +
                mapOf("paid_mtk" to JsonPrimitive(e.net), "due_mtk" to JsonPrimitive(0), "is_credit" to JsonPrimitive(false)))))
        }
        val conflict = client.send(changed)
        assertTrue(conflict[memo] != "accepted", "a changed copy under the same client_uuid is not accepted: ${conflict[memo]}")
        assertEquals(before, Triple(stored(memo), count(ledger, memo), count(ledgerSum, memo)), "the original amounts stay")
    }

    @Test
    fun dRule18NegativeNetIsStoredOnlyForAZeroGross() = testApplication {
        application { aronApi(wiring) }
        // Zero sale with a QC credit: net = -QC, stored as a credit (cfg.memo.allow_negative_net).
        val credit = oracle(emptyList(), emptyList(), listOf(Q(skus[0], 3))) { 0 }
        assertEquals(-23_810L, credit.net) // 3 x 7.935 = 23.805 -> -23.81 (half away from zero)
        val (zr, zMemo) = family(emptyList(), emptyList(), listOf(Q(skus[0], 3)), credit)
        assertTrue(client.send(zr).values.all { it == "accepted" })
        assertEquals(credit, stored(zMemo))
        // A sale whose QC exceeds its gross: every equation holds, but a negative net with a gross is refused (D-18).
        val lines = listOf(L(skus[3], 1))
        val neg = oracle(lines, emptyList(), listOf(Q(skus[0], 5))) { 0 }
        assertTrue(neg.net < 0 && neg.gross > 0)
        val (nr, nMemo) = family(lines, emptyList(), listOf(Q(skus[0], 5)), neg)
        val acks = client.send(nr)
        assertTrue(acks[nMemo]!!.startsWith("quarantined"), "negative net with gross ${neg.gross}: ${acks[nMemo]}")
        assertEquals(null, stored(nMemo))
    }

    /**
     * FINDING QA-MNY-01 (backend sync, MemoChecks): D-18 is enforced only when the memo and all its children arrive in one
     * batch (familyMismatches -> MemoMath.verify). A header sent before its children (a split family) passes
     * recordMismatch, which never checks the sign of the net, and afterChildStored only compares sums, so a sale with a
     * gross and a negative net is stored and never flagged.
     */
    @Disabled("QA-MNY-01: split family stores a negative net with a gross (D-18); enable when MemoChecks checks the sign outside the family path")
    @Test
    fun aSplitFamilyWithANegativeNetAndAGrossIsNotStoredSilently() = testApplication {
        application { aronApi(wiring) }
        val lines = listOf(L(skus[3], 1))
        val neg = oracle(lines, emptyList(), listOf(Q(skus[0], 5))) { 0 }
        val (records, memo) = family(lines, emptyList(), listOf(Q(skus[0], 5)), neg)
        val header = records.filter { it.s("type") in setOf("visit", "memo") }
        val acks = client.send(header)
        client.send(records - header.toSet())
        val ok = acks[memo] != "accepted" || flags(memo).contains("arithmetic_mismatch")
        assertTrue(ok, "memo ${acks[memo]}, flags ${stored(memo)?.let { flags(memo) }}: stored net ${stored(memo)?.net} with gross ${neg.gross}")
    }

    /**
     * FINDING QA-MNY-02 (backend sync; D-34 still MUST-CONFIRM, MQ-03): the server never checks QC settlement = defect
     * quantity x the price at capture. A qc_line whose settlement_mtk is not qty_base x unit_price_mtk (per the SKU's
     * price_per_qty) is stored and deducted from the memo without a flag. Pending until D-34 is confirmed and the TSO
     * lane's QC-only path lands; then the server should recompute it like a memo line (flag, not reject: the printed
     * memo is the fact).
     */
    @Disabled("QA-MNY-02: QC settlement not re-checked against defect x price at capture (D-34 pending)")
    @Test
    fun aQcSettlementThatIsNotDefectTimesPriceIsFlagged() = testApplication {
        application { aronApi(wiring) }
        val lines = listOf(L(skus[3], 100))
        val qcs = listOf(Q(skus[3], 2)) // 2 x 9.200 = 18.400
        val e = oracle(lines, emptyList(), qcs) { 0 }
        val (records, memo) = family(lines, emptyList(), qcs, e)
        // Inflate the settlement (and the header with it) by 1 Tk: every memo equation still holds.
        val bumped = records.map { r ->
            when (r.s("type")) {
                "qc_line" -> JsonObject(r + ("payload" to JsonObject(r["payload"]!!.jsonObject + ("settlement_mtk" to JsonPrimitive(18_400 + 1_000)))))
                "memo" -> JsonObject(r + ("payload" to JsonObject(r["payload"]!!.jsonObject + mapOf(
                    "qc_deduction_mtk" to JsonPrimitive(e.qc + 1_000), "net_mtk" to JsonPrimitive(e.net - 1_000), "due_mtk" to JsonPrimitive(e.due - 1_000),
                    "is_credit" to JsonPrimitive(e.due - 1_000 > 0)))))
                else -> r
            }
        }
        val acks = client.send(bumped)
        assertTrue(acks[memo] != "accepted" || flags(memo) != "{}", "settlement 19.400 for 2 sticks at 9.200 stored as ${acks[memo]} with flags ${flags(memo)}")
    }
}
