package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.IngestService
import com.aktcl.aron.backend.sync.MemoNumbers
import com.aktcl.aron.backend.sync.RecordWriter
import com.aktcl.aron.backend.sync.SyncBatchRequest
import com.aktcl.aron.backend.sync.SyncBatchResponse
import com.aktcl.aron.backend.sync.Uploader
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.Money
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/requests/backend-core-qa-1.md items 1 and 2 (T1, QA-FLOW-02 and QA-FLOW-06):
 * - the memo number rule of docs/24 s7.5: another user's login, another business date or a sequence outside every
 *   ordinal block and overflow range is a final `memo_no_invalid` with the memo's children, nothing is stored, and a
 *   forged number can no longer hold the real owner's memo as `memo_no_duplicate` (phone and paper backfill);
 * - the pack-size re-check of docs/34 s4.2: a line entered in packs with a `pack_factor` that is not the SKU's
 *   `base_per_pack` is parked `qty_invalid` (retryable) with its memo and siblings, never booked; a resend after the
 *   master agrees is stored once; a line held after its memo was stored flags that memo; a stock movement likewise.
 * Sunday 2027-01-03 10:00 Dhaka, sr1001 (block size 500) on the dev phone; sr2002 is a second SR on its own route.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoNumberPackSizeTest {
    private lateinit var fresh: FreshDb
    private lateinit var ingest: IngestService
    private val now = Instant.parse("2027-01-03T04:00:00Z")
    private val clock = AronClock { now }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private var route2 = 0L
    private var outlet2 = 0L
    private var sr = 0L
    private var sr2 = 0L
    private var admin = 0L
    private lateinit var device: Pair<Long, String>
    /** (sku, price per base_per_qty, per_base_qty, base_per_pack) of a 20-stick SKU and of a stick-entered one. */
    private lateinit var pack20: List<Long>
    private lateinit var loose: List<Long>
    private val seq = AtomicInteger(100)
    private val minute = AtomicInteger(0)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            fun id(q: String) = h.createQuery(q).mapTo(Long::class.java).one()
            routeId = id("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            outletId = id("SELECT min(id) FROM app.outlet WHERE route_id = $routeId")
            sr = id("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            admin = id("SELECT id FROM app.app_user WHERE username = 'admin1001'")
            device = h.createQuery("SELECT id, device_uuid::text FROM app.device ORDER BY id LIMIT 1").map { rs, _ -> rs.getLong(1) to rs.getString(2) }.one()
            // A second SR with its own daily route and outlet in sr1001's zone.
            sr2 = id("INSERT INTO app.app_user (username, full_name, role, password_hash) VALUES ('sr2002', 'Second SR', 'SR', 'x') RETURNING id")
            route2 = id("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'MIR-SR-X', 'Second route', zone_id, 'sr', 'daily', 127 FROM app.route WHERE id = $routeId RETURNING id")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) VALUES ($route2, $sr2, 'primary', DATE '2026-01-01', 'test')")
            outlet2 = id(
                "INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel, route_id) " +
                    "SELECT 'MIR-X-1', 'Second outlet', 'Owner', o.zone_id, o.cluster_id, o.channel, $route2 FROM app.outlet o WHERE o.id = $outletId RETURNING id",
            )
            fun sku(where: String) = h.createQuery(
                "SELECT s.id, p.amount_mtk, p.per_base_qty, s.base_per_pack FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' " +
                    "WHERE p.amount_mtk > 0 AND p.valid_from <= DATE '$day' AND (p.valid_to IS NULL OR p.valid_to > DATE '$day') AND $where ORDER BY s.id LIMIT 1",
            ).map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }.one()
            pack20 = sku("s.base_per_pack = 20")
            loose = sku("s.id <> ${pack20[0]}")
        }
        RecordWriter.forgetColumns()
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        ingest = IngestService(fresh.db, config, SqlReachResolver(fresh.db, GeoRepository(fresh.db, clock), clock), clock)
    }

    @AfterAll
    fun tearDown() { RecordWriter.forgetColumns(); fresh.close() }

    private fun uuid() = UUID.randomUUID().toString()
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun count(q: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(q).mapTo(Long::class.java).one() }
    private fun str(q: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(q).mapTo(String::class.java).findOne().orElse(null) }
    private fun uuidOf(r: JsonObject) = (r["client_uuid"] as JsonPrimitive).content
    private fun typeOf(r: JsonObject) = (r["type"] as JsonPrimitive).content

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, route: Long, bd: String = day) = buildJsonObject {
        val at = Instant.parse("2027-01-02T18:30:00Z").plusSeconds(minute.incrementAndGet() * 60L).toString()
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", bd); put("captured_at", at); put("captured_elapsed_ms", 18330000); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", true); put("route_id", route); put("bundle_version", "$bd:3")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    private val fix = """{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    /** One line: (sku row, qty entered, unit, pack factor). qty_base and gross follow from them, as the phone computes. */
    private class L(val sku: List<Long>, val qty: Long, val unit: String, val factor: Long)

    private fun lineGross(l: L) = Money.lineGrossMtk(l.qty * (if (l.unit == "pack") l.factor else 1), l.sku[1], l.sku[2])

    /**
     * A sale family as a phone sends it: visit, memo, lines (consistent money). [web] keys it as a paper entry (web_entry
     * visit, no fix, no visit_close).
     */
    private fun sale(memoNo: String, lines: List<L>, route: Long = routeId, outlet: Long = outletId, bd: String = day, web: Boolean = false): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val gross = lines.sumOf { lineGross(it) }
        val visitPayload = if (web) json(
            """{"visit_kind":"web_entry","outlet_id":$outlet,"opened_at":"${bd}T03:41:00.000Z","sequence_no":1,"planned":true,
               "geo":{"verdict":"no_fix","radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
        ) else json(
            """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${bd}T03:41:00.120Z","sequence_no":1,"planned":true,"fix":$fix,
               "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
        )
        val memoPayload = json(
            """{"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"${bd}T03:%02d:00.000Z",
               "price_list_date":"$bd","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
               "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":${lines.size},"discount_line_count":0,"qc_line_count":0,
               "offer_version_ids":[],"rounding_mode":"half_up_paisa"}""".format(minute.incrementAndGet() % 60),
        )
        return listOf(envelope("visit", visit, visit, 0, visitPayload, route, bd), envelope("memo", memo, visit, 1, memoPayload, route, bd)) +
            lines.mapIndexed { i, l -> envelope("memo_line", uuid(), visit, 2, lineOf(memo, i + 1, l), route, bd) }
    }

    private fun lineOf(memo: String, no: Int, l: L) = json(
        """{"memo_client_uuid":"$memo","line_no":$no,"sku_id":${l.sku[0]},"line_kind":"sale","qty_entered":${l.qty},"unit_entered":"${l.unit}","pack_factor":${l.factor},
           "qty_base":${l.qty * (if (l.unit == "pack") l.factor else 1)},"price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":${l.sku[1]},
           "price_per_qty":${l.sku[2]},"gross_mtk":${lineGross(l)}}""",
    )

    private fun send(user: Long, records: List<JsonObject>, via: IngestService = ingest): SyncBatchResponse = via.ingest(
        Uploader(user, Role.SR, 1, device.first, device.second, null),
        SyncBatchRequest(
            batch_uuid = uuid(), device_uuid = device.second, schema_version = 1, app_version = "1.0.9+9", trigger = "manual",
            sent_at_device = now.toString(), pending_rows = 0, time_anchors = emptyList(), device_counts = emptyMap(), records = records,
        ),
    )

    private fun SyncBatchResponse.ack(r: JsonObject) = acks.single { it.client_uuid == uuidOf(r) }.let { "${it.status} ${it.code ?: ""}".trim() }
    private fun SyncBatchResponse.retryable(r: JsonObject) = acks.single { it.client_uuid == uuidOf(r) }.retryable

    private fun web() = AronPrincipal(
        admin, "u$admin", Role.ADMIN, fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT scope_version FROM app.app_user WHERE id = $admin").mapTo(Long::class.java).one() },
        "aron-api", null, null, "web", emptyList(), false, listOf("pwd", "mfa"), uuid(), now.plusSeconds(900),
    )

    private val stick = { L(loose, 20, "stick", 1) }

    @Test
    fun theRuleItself() {
        val d = LocalDate.parse(day)
        val rule = MemoNumbers.Rule("sr1001", 500)
        for (ok in listOf("sr1001-270103-001", "sr1001-270103-500", "sr1001-270103-501", "sr1001-270103-2000", "sr1001-270103-5001", "sr1001-270103-5999", "sr1001-270103-8999")) {
            assertNull(MemoNumbers.problem(rule, ok, d), ok)
        }
        for (bad in listOf("sr1001-270103-000", "sr1001-270103-2001", "sr1001-270103-3000", "sr1001-270103-5000", "sr1001-270103-6000", "sr1001-270103-9000",
            "sr1001-270103-99999999999", "sr2002-270103-001", "sr1001x-270103-001", "sr100-270103-001", "sr1001-270102-001", "sr1001-260103-001", "MEMO-1", "")) {
            assertTrue(MemoNumbers.problem(rule, bad, d) != null, bad)
        }
        // The block size bounds what the ordinal blocks cover (cfg.memo.seq_block_size 100..999).
        assertNull(MemoNumbers.problem(MemoNumbers.Rule("sr1001", 999), "sr1001-270103-3996", d))
        assertTrue(MemoNumbers.problem(MemoNumbers.Rule("sr1001", 100), "sr1001-270103-401", d) != null)
    }

    /** The oracle the slice smoke follows: every accepted sequence, at the smallest, default and largest block size. */
    @Test
    fun inRangesAcceptsExactlyTheFourBlocksAndTheFourOverflowRanges() {
        for (size in listOf(100, 500, 999)) {
            val want = (1..4 * size).toSet() + (5001..5999) + (6001..6999) + (7001..7999) + (8001..8999)
            assertEquals(want, (-1..10_000).filter { MemoNumbers.inRanges(it, size) }.toSet(), "block size $size")
        }
    }

    /**
     * Lead P1 2026-10-24: the dev deploy's SR slice smoke built `seq` 9000..9999, outside every range, so its memo was
     * `memo_no_invalid` on the first selling day it ran. It now numbers from the overflow range of the ordinal its login
     * names (`5000 + o x 1000 + 1 .. + 999`), else from MEMO_SEQ_RANGE; each must pass the rule at every block size and
     * stay clear of the ordinal blocks (`1 .. 4 x 999`).
     */
    @Test
    fun theSliceSmokesMemoRangeIsValid() {
        val script = File(System.getProperty("aron.repoRoot"), "infra/scripts/slice-smoke.py").readText()
        val (lo, hi) = Regex("""(?m)^MEMO_SEQ_RANGE = \((\d+), (\d+)\)""").find(script)!!.destructured.toList().map { it.toInt() }
        assertTrue(script.contains("return (5000 + o * 1000 + 1, 5000 + o * 1000 + 999) if isinstance(o, int) and 0 <= o <= 3 else MEMO_SEQ_RANGE"), "the smoke's overflow formula")
        assertTrue(script.contains("return lo + (now.hour * 3600 + now.minute * 60 + now.second) * (hi - lo + 1) // 86400"), "the smoke's slot stays inside its range")
        assertTrue(script.contains("memo_seq(seq_range, started)") && script.contains("seq_range = memo_seq_range(b)"), "the smoke's memo_no takes its seq from the login's range")
        val ranges = listOf(lo..hi) + (0..3).map { o -> (5000 + o * 1000 + 1)..(5000 + o * 1000 + 999) }
        val d = LocalDate.parse(day)
        for (r in ranges) {
            assertTrue(r.first > 4 * 999 && r.first <= r.last, "range $r")
            for (size in listOf(100, 500, 999)) for (s in r) {
                assertNull(MemoNumbers.problem(MemoNumbers.Rule("sr1001", size), "sr1001-270103-$s", d), "seq $s, block size $size")
            }
        }
    }

    /**
     * Session 14 open item (b): a phone keeps the block size it bound with, so lowering `cfg.memo.seq_block_size` must
     * not refuse numbers it already printed. The rule uses the largest size ever set. The rows here keep that at 500,
     * the default, so the other tests of this class see the same rule.
     */
    @Test
    fun aLoweredBlockSizeStillAcceptsNumbersPrintedUnderTheOldOne() {
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, reason) " +
                    "SELECT 'cfg.memo.seq_block_size', 'global', 0, '500'::jsonb, TIMESTAMPTZ '2026-01-01T00:00:00Z', TIMESTAMPTZ '2026-06-01T00:00:00Z', max(config_version), 'test' FROM app.cfg_version",
            )
            h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                    "SELECT 'cfg.memo.seq_block_size', 'global', 0, '100'::jsonb, TIMESTAMPTZ '2026-06-01T00:00:00Z', max(config_version), 'test' FROM app.cfg_version",
            )
        }
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        assertEquals(100, config.int("cfg.memo.seq_block_size"), "the lowered size is in force")
        val lowered = IngestService(fresh.db, config, SqlReachResolver(fresh.db, GeoRepository(fresh.db, clock), clock), clock)
        // 1999 lies in ordinal 3's block of size 500 but past 4 x 100 = 400: printed before the change, still accepted.
        val printed = sale("sr1001-270103-1999", listOf(stick()))
        val res = send(sr, printed, lowered)
        assertEquals(listOf("accepted", "accepted", "accepted"), printed.map { res.ack(it) }, res.acks.toString())
        // Past every block of the largest size ever set: still refused.
        val beyond = sale("sr1001-270103-2001", listOf(stick()))
        assertEquals("rejected memo_no_invalid", send(sr, beyond, lowered).ack(beyond[1]))
    }

    @Test
    fun aNumberNotTheUsersOwnIsRefusedWithItsLinesAndNothingIsStored() {
        // QA-FLOW-02's rows: own number in block (accepted), another user's name, another date, outside every block.
        val good = sale("sr1001-270103-${seq.incrementAndGet()}", listOf(stick()))
        assertEquals(listOf("accepted", "accepted", "accepted"), good.map { send(sr, listOf(it)).ack(it) })
        for (no in listOf("sr2002-270103-151", "sr1001-270102-152", "sr1001-270103-3000", "sr1001-270103-4999")) {
            val f = sale(no, listOf(stick(), stick()))
            val res = send(sr, f)
            assertEquals("accepted", res.ack(f[0]), "the visit stands: $no")
            for (r in f.drop(1)) {
                assertEquals("rejected memo_no_invalid", res.ack(r), "$no ${typeOf(r)}")
                assertEquals(false, res.retryable(r), "final: $no ${typeOf(r)}")
            }
            assertEquals(0, count("SELECT count(*) FROM app.memo WHERE memo_no = '$no'"), no)
            assertEquals(0, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${uuidOf(f[1])}'"), no)
            // Idempotent: the resend answers the same, children first as well.
            val again = send(sr, f.reversed())
            assertEquals(f.drop(1).map { "rejected memo_no_invalid" }, f.drop(1).map { again.ack(it) }, "resend of $no")
            assertEquals(0, count("SELECT count(*) FROM app.memo WHERE memo_no = '$no'"), no)
        }
        // A line that arrived before its refused memo was parked; its resend after the refusal is final too.
        val split = sale("sr1001-270103-9001", listOf(stick()))
        assertEquals("rejected parent_missing", send(sr, listOf(split[2])).ack(split[2]))
        assertEquals("rejected memo_no_invalid", send(sr, split.take(2)).ack(split[1]))
        assertEquals("rejected memo_no_invalid", send(sr, listOf(split[2])).ack(split[2]))
    }

    @Test
    fun aForgedNumberNeverHoldsTheRealOwnersMemo() {
        // sr1001 prints sr2002's next number first; before the fix that stored it and sr2002's real memo was quarantined.
        val no = "sr2002-270103-${seq.incrementAndGet()}"
        val forged = sale(no, listOf(stick()))
        assertEquals("rejected memo_no_invalid", send(sr, forged).ack(forged[1]))
        val real = sale(no, listOf(stick()), route = route2, outlet = outlet2)
        val res = send(sr2, real)
        assertEquals(listOf("accepted", "accepted", "accepted"), real.map { res.ack(it) }, res.acks.toString())
        assertEquals("$sr2", str("SELECT string_agg(user_id::text, ',') FROM app.memo WHERE memo_no = '$no'"))
        assertEquals(0, count("SELECT count(*) FROM app.sync_quarantine WHERE client_uuid = '${uuidOf(real[1])}'"))
        // The owner's own second memo under the same number is still memo_no_duplicate (the global check stays).
        val twin = sale(no, listOf(L(loose, 3, "stick", 1)), route = route2, outlet = outlet2)
        assertEquals("quarantined memo_no_duplicate", send(sr2, twin).ack(twin[1]))
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE memo_no = '$no'"))
    }

    @Test
    fun aPaperBackfillOfAnotherUsersNumberIsInvalidNotADuplicate() {
        val no = "sr2002-270103-${seq.incrementAndGet()}"
        val real = sale(no, listOf(stick()), route = route2, outlet = outlet2)
        assertEquals("accepted", send(sr2, real).ack(real[1]))
        // Keyed for sr1001 with sr2002's stored number: refused as invalid; sr2002's memo is never named.
        val keyed = sale(no, listOf(stick()), web = true)
        val m = ingest.ingestManual(web(), sr, uuid(), keyed)
        assertFalse(m.stored); assertFalse(m.duplicateMemoNo)
        assertEquals("rejected memo_no_invalid", m.acks.single { it.type == "memo" }.let { "${it.status} ${it.code}" })
        assertNull(m.acks.single { it.type == "memo" }.server_id)
        assertEquals(0, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${uuidOf(keyed[0])}'"), "nothing of the entry is written")
        // The SR's own paper number is stored as before.
        val own = sale("sr1001-270103-${seq.incrementAndGet()}", listOf(stick()), web = true)
        assertTrue(ingest.ingestManual(web(), sr, uuid(), own).stored)
    }

    @Test
    fun aPackSizeThatIsNotTheSkusIsParkedWithItsMemoAndNeverBooked() {
        // QA-FLOW-06: 2 packets of a 20-stick SKU sent with pack_factor 10 (qty_base 20, money for 20 sticks).
        val wrong = sale("sr1001-270103-${seq.incrementAndGet()}", listOf(L(pack20, 2, "pack", 10), stick()))
        val res = send(sr, wrong)
        assertEquals("accepted", res.ack(wrong[0]))
        for (r in wrong.drop(1)) {
            assertEquals("rejected qty_invalid", res.ack(r), typeOf(r))
            assertEquals(true, res.retryable(r), "retryable after a bundle refresh: ${typeOf(r)}")
        }
        val memo = uuidOf(wrong[1])
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"), "the memo is not booked")
        assertEquals(0, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$memo'"), "no line is booked")
        assertEquals(0, count("SELECT count(*) FROM app.due_ledger WHERE memo_client_uuid = '$memo'"))
        assertEquals(3, count("SELECT count(*) FROM app.ingest_registry WHERE client_uuid IN (${wrong.drop(1).joinToString { "'${uuidOf(it)}'" }}) AND status = 'parked' AND outcome_code = 'qty_invalid'"))
        // Resent unchanged (shuffled): the same answer, still nothing booked.
        val again = send(sr, wrong.shuffled())
        assertEquals(wrong.drop(1).map { "rejected qty_invalid" }, wrong.drop(1).map { again.ack(it) })
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo'"))
        // The right pack size is stored in base units: 2 packets of 20 = 40 sticks.
        val right = sale("sr1001-270103-${seq.incrementAndGet()}", listOf(L(pack20, 2, "pack", 20)))
        assertEquals(listOf("accepted", "accepted", "accepted"), send(sr, right).let { r -> right.map { r.ack(it) } })
        assertEquals(40, count("SELECT qty_base FROM app.memo_line WHERE memo_client_uuid = '${uuidOf(right[1])}'"))
        // Loose units (unit_entered stick) carry pack_factor 1 and are not a pack-size claim.
        val sticks = sale("sr1001-270103-${seq.incrementAndGet()}", listOf(L(pack20, 7, "stick", 1)))
        assertEquals("accepted", send(sr, sticks).ack(sticks[2]))
    }

    @Test
    fun aParkedFamilyIsStoredOnceWhenTheMasterAgrees() {
        // The server's master was the stale side: once base_per_pack says 10, the phone's resend is stored, once.
        val sku = fresh.db.jdbi.withHandle<List<Long>, Exception> { h ->
            h.createQuery(
                "SELECT s.id, p.amount_mtk, p.per_base_qty, s.base_per_pack FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' " +
                    "WHERE p.amount_mtk > 0 AND s.base_per_pack = 20 AND s.id <> ${pack20[0]} AND p.valid_from <= DATE '$day' AND (p.valid_to IS NULL OR p.valid_to > DATE '$day') ORDER BY s.id LIMIT 1",
            ).map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }.one()
        }
        val f = sale("sr1001-270103-${seq.incrementAndGet()}", listOf(L(sku, 3, "pack", 10)))
        assertEquals("rejected qty_invalid", send(sr, f).ack(f[2]))
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.sku SET base_per_pack = 10 WHERE id = ?", sku[0]) }
        try {
            val res = send(sr, f)
            assertEquals(listOf("duplicate", "accepted", "accepted"), f.map { res.ack(it) }, res.acks.toString())
            assertEquals(listOf("duplicate", "duplicate", "duplicate"), send(sr, f).let { r -> f.map { r.ack(it) } })
            assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${uuidOf(f[1])}'"))
            assertEquals(30, count("SELECT sum(qty_base) FROM app.memo_line WHERE memo_client_uuid = '${uuidOf(f[1])}'"))
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.sku SET base_per_pack = 20 WHERE id = ?", sku[0]) }
        }
    }

    @Test
    fun aWrongLineAfterItsMemoWasStoredIsHeldAndFlagsTheMemo() {
        // Split family: the header and the visit came first (the memo was printed and stays stored).
        val f = sale("sr1001-270103-${seq.incrementAndGet()}", listOf(L(pack20, 2, "pack", 10)))
        assertEquals(listOf("accepted", "accepted"), send(sr, f.take(2)).let { r -> f.take(2).map { r.ack(it) } })
        val res = send(sr, listOf(f[2]))
        assertEquals("rejected qty_invalid", res.ack(f[2]))
        assertEquals(true, res.retryable(f[2]))
        assertEquals(0, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${uuidOf(f[1])}'"))
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${uuidOf(f[1])}' AND 'pack_factor_mismatch' = ANY(server_flags)"))
    }

    @Test
    fun aStockMovementWithAWrongPackSizeIsParked() {
        fun move(factor: Long) = envelope(
            "stock_movement", uuid(), uuid(), 0,
            json("""{"kind":"issue","sku_id":${pack20[0]},"qty_entered":5,"unit_entered":"pack","pack_factor":$factor,"qty_base":${5 * factor},"slip_printed":true}"""), routeId,
        ).let { JsonObject(it + ("family_uuid" to it["client_uuid"]!!)) }
        val wrong = move(10)
        val res = send(sr, listOf(wrong))
        assertEquals("rejected qty_invalid", res.ack(wrong))
        assertEquals(true, res.retryable(wrong))
        assertEquals(0, count("SELECT count(*) FROM app.stock_movement WHERE client_uuid = '${uuidOf(wrong)}'"))
        val right = move(20)
        assertEquals("accepted", send(sr, listOf(right)).ack(right))
        assertEquals(100, count("SELECT qty_base FROM app.stock_movement WHERE client_uuid = '${uuidOf(right)}'"))
    }

    @Test
    fun aRefusedMemosLinesAreRefusedWithItEvenWithABadPackSize() {
        // Checker note 1: the number rule wins over the pack-size hold for the memo's children (final, not retryable).
        val f = sale("sr2002-270103-${seq.incrementAndGet()}", listOf(L(pack20, 2, "pack", 10), stick()))
        for (res in listOf(send(sr, f), send(sr, f.shuffled()))) {
            for (r in f.drop(1)) {
                assertEquals("rejected memo_no_invalid", res.ack(r), typeOf(r))
                assertEquals(false, res.retryable(r))
            }
        }
    }

    @Test
    fun aMemoWhoseLineWasHeldFirstIsHeldToo() {
        // Checker note 2: the bad line arrives before its memo; the memo and its good line are then held, never booked.
        val f = sale("sr1001-270103-${seq.incrementAndGet()}", listOf(L(pack20, 2, "pack", 10), stick()))
        assertEquals("rejected qty_invalid", send(sr, listOf(f[2])).ack(f[2]))
        val res = send(sr, f.take(2) + f[3])
        assertEquals("accepted", res.ack(f[0]))
        assertEquals("rejected qty_invalid", res.ack(f[1]))
        assertEquals(true, res.retryable(f[1]))
        assertEquals("rejected parent_missing", res.ack(f[3]))
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${uuidOf(f[1])}'"))
        assertEquals(0, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${uuidOf(f[1])}'"))
        assertEquals(0, count("SELECT count(*) FROM app.due_ledger WHERE memo_client_uuid = '${uuidOf(f[1])}'"))
    }
}
