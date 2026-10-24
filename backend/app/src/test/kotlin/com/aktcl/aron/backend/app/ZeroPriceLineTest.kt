package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.IngestService
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
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/requests/backend-core-zero-price-line.md (D-SALE-ZERO-PRICE-01, lead 2026-10-24): a `sale` memo line priced at
 * zero (the SKU's catalogue price is zero, or it has none and the phone priced it at zero) is a final per-record
 * `schema_invalid`; the memo and its priced lines are stored (the zero line adds nothing to the gross), the memo is
 * flagged `zero_price_line`, and a resend in any order is idempotent. A line the phone priced above zero for an SKU the
 * server has no price for is stored and flagged `price_mismatch` (money collected). Sunday 2027-01-03, sr1001, MIR-SR-D.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZeroPriceLineTest {
    private lateinit var fresh: FreshDb
    private lateinit var ingest: IngestService
    private val now = Instant.parse("2027-01-03T04:00:00Z")
    private val clock = AronClock { now }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private var sr = 0L
    private var sr2 = 0L
    private var route2 = 0L
    private val switches = AtomicInteger(0)
    private lateinit var device: Pair<Long, String>
    /** (sku, price per base_per_qty, per_base_qty, base_per_pack): a priced SKU, one priced at zero, one with no outlet price. */
    private lateinit var priced: List<Long>
    private lateinit var zero: List<Long>
    private lateinit var unpriced: List<Long>
    private lateinit var unpricedAsPrinted: List<Long>
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
            device = h.createQuery("SELECT id, device_uuid::text FROM app.device ORDER BY id LIMIT 1").map { rs, _ -> rs.getLong(1) to rs.getString(2) }.one()
            // A second SR with its own daily route in sr1001's zone (the user guard of the flag).
            sr2 = id("INSERT INTO app.app_user (username, full_name, role, password_hash) VALUES ('sr2002', 'Second SR', 'SR', 'x') RETURNING id")
            route2 = id("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'MIR-SR-X', 'Second route', zone_id, 'sr', 'daily', 127 FROM app.route WHERE id = $routeId RETURNING id")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) VALUES ($route2, $sr2, 'primary', DATE '2026-01-01', 'test')")
            val skus = h.createQuery(
                "SELECT s.id, p.amount_mtk, p.per_base_qty, s.base_per_pack FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' " +
                    "WHERE p.amount_mtk > 0 AND p.valid_from <= DATE '$day' AND (p.valid_to IS NULL OR p.valid_to > DATE '$day') ORDER BY s.id LIMIT 3",
            ).map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }.list()
            priced = skus[0]
            // The catalogue's MaxDB-10S_10HL case: a price row of zero. The phone states the zero it was given.
            h.execute("UPDATE app.sku_price SET amount_mtk = 0 WHERE sku_id = ${skus[1][0]} AND price_type = 'outlet'")
            zero = listOf(skus[1][0], 0L, skus[1][2], skus[1][3])
            // No outlet price at all on the server; a phone stating one has it from nowhere the server knows.
            h.execute("DELETE FROM app.sku_price WHERE sku_id = ${skus[2][0]} AND price_type = 'outlet'")
            unpriced = listOf(skus[2][0], 0L, skus[2][2], skus[2][3])
            unpricedAsPrinted = skus[2]
        }
        RecordWriter.forgetColumns()
        ingest = IngestService(fresh.db, DbServerConfig(fresh.db, RegistryDefaults(), clock), SqlReachResolver(fresh.db, GeoRepository(fresh.db, clock), clock), clock)
    }

    @AfterAll
    fun tearDown() { RecordWriter.forgetColumns(); fresh.close() }

    private fun uuid() = UUID.randomUUID().toString()
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun count(q: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(q).mapTo(Long::class.java).one() }
    private fun uuidOf(r: JsonObject) = (r["client_uuid"] as JsonPrimitive).content
    private fun typeOf(r: JsonObject) = (r["type"] as JsonPrimitive).content
    private fun skuOf(r: JsonObject) = ((r["payload"] as JsonObject)["sku_id"] as JsonPrimitive).content.toLong()

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
            """{"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"${Instant.parse("${bd}T00:00:00Z").plusSeconds(60L * minute.incrementAndGet())}",
               "price_list_date":"$bd","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
               "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":${lines.size},"discount_line_count":0,"qc_line_count":0,
               "offer_version_ids":[],"rounding_mode":"half_up_paisa"}""",
        )
        return listOf(envelope("visit", visit, visit, 0, visitPayload, route, bd), envelope("memo", memo, visit, 1, memoPayload, route, bd)) +
            lines.mapIndexed { i, l -> envelope("memo_line", uuid(), visit, 2, lineOf(memo, i + 1, l), route, bd) }
    }

    private fun lineOf(memo: String, no: Int, l: L) = json(
        """{"memo_client_uuid":"$memo","line_no":$no,"sku_id":${l.sku[0]},"line_kind":"sale","qty_entered":${l.qty},"unit_entered":"${l.unit}","pack_factor":${l.factor},
           "qty_base":${l.qty * (if (l.unit == "pack") l.factor else 1)},"price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":${l.sku[1]},
           "price_per_qty":${l.sku[2]},"gross_mtk":${lineGross(l)}}""",
    )

    private fun send(user: Long, records: List<JsonObject>, via: IngestService = ingest, key: ECPublicKey? = null): SyncBatchResponse = via.ingest(
        Uploader(user, Role.SR, 1, device.first, device.second, key),
        SyncBatchRequest(
            batch_uuid = uuid(), device_uuid = device.second, schema_version = 1, app_version = "1.0.9+9", trigger = "manual",
            sent_at_device = now.toString(), pending_rows = 0, time_anchors = emptyList(), device_counts = emptyMap(), records = records,
        ),
    )

    private fun SyncBatchResponse.ack(r: JsonObject) = acks.single { it.client_uuid == uuidOf(r) }.let { "${it.status} ${it.code ?: ""}".trim() }
    private fun SyncBatchResponse.retryable(r: JsonObject) = acks.single { it.client_uuid == uuidOf(r) }.retryable

    private fun memoNo() = "sr1001-270103-${seq.incrementAndGet()}"

    @Test
    fun aZeroPriceLineIsRefusedAloneAndItsMemoStands() {
        for (bad in listOf(L(zero, 2, "pack", zero[3]), L(unpriced, 20, "stick", 1))) {
            val f = sale(memoNo(), listOf(L(priced, 20, "stick", 1), bad))
            val res = send(sr, f)
            assertEquals(listOf("accepted", "accepted", "accepted", "rejected schema_invalid"), f.map { res.ack(it) }, res.acks.toString())
            assertEquals(false, res.retryable(f[3]), "final")
            val memo = uuidOf(f[1])
            assertEquals(1, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$memo'"), "only the priced line is stored")
            assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo' AND 'zero_price_line' = ANY(server_flags)"))
            assertEquals(lineGross(L(priced, 20, "stick", 1)), count("SELECT gross_mtk FROM app.memo WHERE client_uuid = '$memo'"), "money unchanged")
            val again = send(sr, f.reversed())
            assertEquals(listOf("duplicate", "duplicate", "duplicate", "rejected schema_invalid"), f.map { again.ack(it).substringBefore(" ").takeIf { s -> s != "rejected" } ?: again.ack(it) })
            assertEquals(1, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$memo'"), "resend stores nothing")
        }
    }

    /** Checker #6: the zero line arrives before its memo is stored (phone order, or the memo was held); the memo is flagged when it lands. */
    @Test
    fun aZeroLineSentBeforeItsMemoStillFlagsTheMemo() {
        val f = sale(memoNo(), listOf(L(priced, 20, "stick", 1), L(zero, 1, "pack", zero[3])))
        assertEquals("rejected schema_invalid", send(sr, listOf(f[3])).ack(f[3]), "refused before any parent check")
        val res = send(sr, f.take(3))
        assertEquals(listOf("accepted", "accepted", "accepted"), f.take(3).map { res.ack(it) }, res.acks.toString())
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${uuidOf(f[1])}' AND 'zero_price_line' = ANY(server_flags)"))
        // A priced family of the same user stays unflagged.
        val clean = sale(memoNo(), listOf(L(priced, 7, "stick", 1)))
        send(sr, clean)
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${uuidOf(clean[1])}' AND 'zero_price_line' = ANY(COALESCE(server_flags, '{}'))"))
    }

    @Test
    fun aLineStatingZeroIsRefusedEvenWhenTheServerHasAPrice() {
        val f = sale(memoNo(), listOf(L(priced, 20, "stick", 1), L(listOf(priced[0], 0L, priced[2], priced[3]), 10, "stick", 1)))
        val res = send(sr, f)
        assertEquals("rejected schema_invalid", res.ack(f[3]), res.acks.toString())
        assertEquals("accepted", res.ack(f[2]))
    }

    @Test
    fun aLinePricedAboveZeroIsStoredAndFlaggedWhenTheServerHasNoPrice() {
        val f = sale(memoNo(), listOf(L(priced, 20, "stick", 1), L(unpricedAsPrinted, 20, "stick", 1)))
        val res = send(sr, f)
        assertEquals(listOf("accepted", "accepted", "accepted", "accepted"), f.map { res.ack(it) }, res.acks.toString())
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${uuidOf(f[1])}' AND 'price_mismatch' = ANY(server_flags) AND NOT 'zero_price_line' = ANY(server_flags)"))
    }

    /** A new global value of [key], in force from a fixed instant before [now] (one second later per switch), and a fresh service (its config cache is per instance). */
    private fun switch(key: String, value: Boolean): IngestService {
        val n = switches.incrementAndGet()
        fresh.db.jdbi.useTransaction<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test gate' FROM app.cfg_version")
            h.execute("UPDATE app.cfg_value SET effective_to = TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n WHERE key = '$key' AND scope_type = 'global' AND effective_to IS NULL")
            h.execute(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                    "SELECT '$key', 'global', 0, '$value'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n, max(config_version), 'test' FROM app.cfg_version",
            )
        }
        return IngestService(fresh.db, DbServerConfig(fresh.db, RegistryDefaults(), clock), SqlReachResolver(fresh.db, GeoRepository(fresh.db, clock), clock), clock)
    }

    /**
     * Lead 2026-10-24 (a): a memo held by the device gate while its zero line is refused gets the flag when it is released.
     * The integrity gate holds only the sale header (an enrolled phone with a key but no Play Integrity pass), so the
     * lines go on to their own checks: the priced one waits for its parent, the zero one is refused.
     */
    @Test
    fun aMemoHeldByTheDeviceGateIsFlaggedOnRelease() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute(
                "INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, used_count, expires_at, created_by) " +
                    "SELECT decode(repeat('cd', 32), 'hex'), 'cdcdcd', 'sr', 'dev', 1, 1, now() + interval '1 hour', id FROM app.app_user WHERE username = 'aron.system'",
            )
            h.execute("UPDATE app.device SET enrolment_token_id = (SELECT max(id) FROM app.enrolment_token), integrity_verdict = 'unevaluated' WHERE id = ${device.first}")
        }
        val gated = switch("cfg.device.require_integrity", true)
        try {
            val f = sale(memoNo(), listOf(L(priced, 20, "stick", 1), L(zero, 1, "pack", zero[3])))
            val held = send(sr, f, gated, key)
            assertEquals("quarantined device_integrity_failed", held.ack(f[1]), held.acks.toString())
            assertEquals("rejected schema_invalid", held.ack(f[3]), "the zero line is refused while its memo is held")
            assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${uuidOf(f[1])}'"))
            // The phone passes Play Integrity; the resend releases the memo and its priced line, and the memo is flagged.
            fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device SET integrity_verdict = 'pass', integrity_checked_at = now() WHERE id = ${device.first}") }
            val res = send(sr, f, gated, key)
            assertEquals(listOf("accepted", "accepted", "rejected schema_invalid"), f.drop(1).map { res.ack(it) }, res.acks.toString())
            assertEquals(1, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${uuidOf(f[1])}'"))
            assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${uuidOf(f[1])}' AND 'zero_price_line' = ANY(server_flags)"))
        } finally {
            switch("cfg.device.require_integrity", false)
            fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.device SET enrolment_token_id = NULL, integrity_verdict = 'unevaluated' WHERE id = ${device.first}") }
        }
    }

    /** Lead 2026-10-24 (a): a zero line naming another user's memo uuid never flags that memo, stored before or after it. */
    @Test
    fun aZeroLineOfAnotherUserNeverFlagsTheMemo() {
        fun zeroLineOf(memo: String) = envelope("memo_line", uuid(), uuid(), 2, lineOf(memo, 9, L(zero, 1, "pack", zero[3])), route2)
        fun flagged(memo: String) = count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo' AND 'zero_price_line' = ANY(COALESCE(server_flags, '{}'))")
        // Stored first: sr2002's line arrives after sr1001's memo.
        val stored = sale(memoNo(), listOf(L(priced, 20, "stick", 1)))
        assertEquals("accepted", send(sr, stored).ack(stored[1]))
        val after = zeroLineOf(uuidOf(stored[1]))
        assertEquals("rejected schema_invalid", send(sr2, listOf(after)).ack(after))
        assertEquals(0, flagged(uuidOf(stored[1])))
        // Line first: sr2002 names a memo uuid sr1001 stores later.
        val later = sale(memoNo(), listOf(L(priced, 5, "stick", 1)))
        val before = zeroLineOf(uuidOf(later[1]))
        assertEquals("rejected schema_invalid", send(sr2, listOf(before)).ack(before))
        assertEquals("accepted", send(sr, later).ack(later[1]))
        assertEquals(0, flagged(uuidOf(later[1])))
    }

    /** Property: memos with a random mix of priced and zero lines, sent whole, then resent shuffled with duplicates. */
    @Test
    fun randomMixesLandThePricedLinesOnceAndRefuseEveryZeroLine() {
        val rnd = Random(System.getenv("ARON_ZERO_PRICE_SEED")?.toLong() ?: 20261024L)
        val families = (1..12).map {
            val lines = (1..rnd.nextInt(1, 5)).map { _ ->
                when (rnd.nextInt(4)) {
                    0 -> L(zero, rnd.nextLong(1, 4), "pack", zero[3])
                    1 -> L(unpriced, rnd.nextLong(1, 40), "stick", 1)
                    else -> L(priced, rnd.nextLong(1, 40), "stick", 1)
                }
            }
            sale(memoNo(), lines)
        }
        val all = families.flatten()
        fun isZero(r: JsonObject) = typeOf(r) == "memo_line" && skuOf(r) != priced[0]
        val first = send(sr, all)
        for (r in all) assertEquals(if (isZero(r)) "rejected schema_invalid" else "accepted", first.ack(r), "${typeOf(r)} ${uuidOf(r)}")
        repeat(3) {
            val again = send(sr, (all + all.filter { rnd.nextBoolean() }).shuffled(rnd))
            for (r in all) {
                val a = again.acks.first { it.client_uuid == uuidOf(r) }
                assertEquals(if (isZero(r)) "rejected schema_invalid" else "duplicate", if (a.status == "rejected") "rejected ${a.code}" else a.status, "resend ${typeOf(r)}")
            }
        }
        for (f in families) {
            val memo = uuidOf(f[1])
            val pricedLines = f.drop(2).filterNot(::isZero)
            assertEquals(pricedLines.size.toLong(), count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$memo'"))
            assertEquals(pricedLines.size < f.size - 2, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$memo' AND 'zero_price_line' = ANY(COALESCE(server_flags, '{}'))") == 1L)
        }
        assertTrue(all.any(::isZero) && all.any { typeOf(it) == "memo_line" && !isZero(it) }, "the seed mixes both")
        assertEquals(0, count("SELECT count(*) FROM app.memo_line WHERE line_kind = 'sale' AND base_price_mtk <= 0"), "no zero line is ever stored")
    }
}
