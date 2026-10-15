package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.IngestService
import com.aktcl.aron.backend.sync.RecordWriter
import com.aktcl.aron.backend.sync.SyncBatchRequest
import com.aktcl.aron.backend.sync.Uploader
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F-API-038 paper backfill entry point (`IngestService.ingestManual`, T1): a web actor keys a dead-phone day for an SR
 * through the same per-record path. Stored once by client_uuid (a repeat is duplicate, other content is
 * payload_conflict), parents before children whatever the order, a split entry converges, a memo number already
 * stored is stored once, the actor's own reach is enforced server-side, the backfill window holds, and rows are marked
 * `entry_source = manual` (columns added here as docs/requests/backend-core-memo-source.md asks).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualIngestTest {
    private lateinit var fresh: FreshDb
    private lateinit var ingest: IngestService
    private val now = Instant.parse("2027-01-03T04:00:00Z")
    private val clock = AronClock { now }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private var otherOutletId = 0L
    private var sr = 0L
    private var admin = 0L
    private var narrowAdmin = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val seq = AtomicInteger(500)

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
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            // Another territory with an outlet, and an ADMIN whose reach is only that territory.
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            h.execute("INSERT INTO app.cluster (zone_id, name) SELECT id, 'Other cluster' FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel) SELECT 'OTH-1', 'Other 1', 'Owner', z.id, c.id, 'GT' FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id WHERE z.code = 'Z-OTHER'")
            otherOutletId = id("SELECT id FROM app.outlet WHERE code = 'OTH-1'")
            narrowAdmin = id("INSERT INTO app.app_user (username, full_name, role, password_hash) VALUES ('adminother', 'Other Admin', 'ADMIN', 'x') RETURNING id")
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT $narrowAdmin, 'territory', id, DATE '2026-01-01', 'test' FROM app.territory WHERE code = 'T-OTHER'")
            // Working-day windows (F-SYS-090): the paper window counts them like the phone path (lead ruling 2026-10-15).
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'working days' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.calendar.window_unit', 'global', 0, '\"working_days\"'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
            // The columns and the registry flag docs/requests/backend-core-memo-source.md asks db for.
            for (t in listOf("memo", "visit")) {
                h.execute("ALTER TABLE app.$t ADD COLUMN IF NOT EXISTS entry_source text NOT NULL DEFAULT 'device' CHECK (entry_source IN ('device','manual')), ADD COLUMN IF NOT EXISTS entered_by_user_id bigint REFERENCES app.app_user(id)")
            }
            h.execute("ALTER TABLE app.ingest_registry DROP CONSTRAINT ingest_registry_flags_known")
            h.execute("ALTER TABLE app.ingest_registry ADD CONSTRAINT ingest_registry_flags_known CHECK (flags <@ ARRAY['resync_late','config_stamp_regress','checkout_too_early','manual_entry']::text[])")
        }
        RecordWriter.forgetColumns()
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val real = SqlReachResolver(fresh.db, GeoRepository(fresh.db, clock), clock)
        // Every manual role is national today; the narrow ADMIN gets its territory's reach, as a narrowed role would.
        ingest = IngestService(fresh.db, config, { u, role, sv, d -> real.reach(u, if (u == narrowAdmin) Role.TSO else role, sv, d) }, clock)
    }

    @AfterAll
    fun tearDown() { RecordWriter.forgetColumns(); fresh.close() }

    private fun uuid() = UUID.randomUUID().toString()
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun count(q: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(q).mapTo(Long::class.java).one() }
    private fun str(q: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(q).mapTo(String::class.java).findOne().orElse(null) }

    private fun web(userId: Long, role: Role, flavour: String = "web") = AronPrincipal(
        userId, "u$userId", role, fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT scope_version FROM app.app_user WHERE id = $userId").mapTo(Long::class.java).one() },
        "aron-api", null, null, flavour, emptyList(), false, listOf("pwd", "mfa"), uuid(), now.plusSeconds(900),
    )

    private fun envelope(type: String, cu: String, family: String, rank: Int, payload: JsonObject, bd: String = day) = buildJsonObject {
        put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
        put("business_date", bd); put("captured_at", "${bd}T03:%02d:00.000Z".format(seq.incrementAndGet() % 60)); put("captured_elapsed_ms", 0); put("boot_count", 0)
        put("clock_offset_ms", 0); put("captured_offline", false); put("route_id", routeId); put("bundle_version", "$bd:0")
        put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    /** One printed memo keyed on the web: visit (web_entry, no fix), memo, two lines. */
    private fun paper(memoNo: String = "sr1001-270103-${seq.incrementAndGet()}", outlet: Long = outletId, bd: String = day): List<JsonObject> {
        val visit = uuid(); val memo = uuid()
        val (s1, p1) = skus[0]; val (s2, p2) = skus[1]
        val gross = 20 * p1 + 10 * p2
        fun line(no: Int, sku: Long, qty: Int, price: Long) = json(
            """{"memo_client_uuid":"$memo","line_no":$no,"sku_id":$sku,"line_kind":"sale","qty_entered":$qty,"unit_entered":"stick","pack_factor":1,"qty_base":$qty,
               "price_type":"outlet","price_valid_from":"2026-01-01","base_price_mtk":$price,"price_per_qty":1,"gross_mtk":${qty * price}}""",
        )
        return listOf(
            envelope("visit", visit, visit, 0, json(
                """{"visit_kind":"web_entry","outlet_id":$outlet,"opened_at":"${bd}T03:41:00.000Z","sequence_no":1,"planned":true,
                   "geo":{"verdict":"no_fix","radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
            ), bd),
            envelope("memo", memo, visit, 1, json(
                """{"visit_client_uuid":"$visit","outlet_id":$outlet,"memo_no":"$memoNo","memo_kind":"sale","committed_at":"${bd}T03:43:00.000Z",
                   "price_list_date":"$bd","price_type":"outlet","gross_mtk":$gross,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,
                   "round_adj_mtk":0,"net_mtk":$gross,"paid_mtk":$gross,"due_mtk":0,"is_credit":false,"line_count":2,"discount_line_count":0,"qc_line_count":0,
                   "offer_version_ids":[],"rounding_mode":"half_up_paisa"}""",
            ), bd),
            envelope("memo_line", uuid(), visit, 2, line(1, s1, 20, p1), bd),
            envelope("memo_line", uuid(), visit, 2, line(2, s2, 10, p2), bd),
        )
    }

    private fun memoUuid(e: List<JsonObject>) = (e[1]["client_uuid"] as JsonPrimitive).content
    private fun memoNo(e: List<JsonObject>) = ((e[1]["payload"] as JsonObject)["memo_no"] as JsonPrimitive).content

    @Test
    fun aPaperMemoIsStoredOnceAndMarkedManual() {
        val e = paper()
        val first = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), e)
        assertEquals(listOf("accepted", "accepted", "accepted", "accepted"), first.acks.map { it.status }, first.acks.toString())
        val again = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), e)
        assertEquals(listOf("duplicate", "duplicate", "duplicate", "duplicate"), again.acks.map { it.status }, "a resend of the same entry is duplicate")
        val m = memoUuid(e)
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$m'"))
        assertEquals(2, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$m'"))
        assertEquals("$sr|manual|$admin|-", str("SELECT user_id || '|' || entry_source || '|' || entered_by_user_id || '|' || coalesce(device_id::text, '-') FROM app.memo WHERE client_uuid = '$m'"))
        assertEquals("manual|no_fix", str("SELECT v.entry_source || '|' || v.verdict FROM app.visit v JOIN app.memo m ON m.visit_client_uuid = v.client_uuid WHERE m.client_uuid = '$m'"))
        assertEquals("{manual_entry}", str("SELECT flags::text FROM app.ingest_registry WHERE client_uuid = '$m'"))
    }

    @Test
    fun outOfOrderAndSplitEntriesConverge() {
        // Children first in one call: the entry point puts parents first.
        val e = paper()
        assertTrue(ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), e.reversed()).acks.all { it.status == "accepted" })
        // A shuffled entry is stored whole; lines alone (aimed at a stored memo, no route) are refused (checker B2).
        val s = paper()
        val whole = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), s.shuffled())
        assertTrue(whole.stored && whole.acks.all { it.status == "accepted" }, whole.acks.toString())
        assertEquals(2, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${memoUuid(s)}'"))
        val extra = JsonObject(s[3] + ("client_uuid" to JsonPrimitive(uuid())) + ("payload" to JsonObject((s[3]["payload"] as JsonObject) + ("line_no" to JsonPrimitive(9)))))
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), listOf(extra, JsonObject(extra - "route_id"), extra)) }.code)
        assertEquals(2, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${memoUuid(s)}'"), "nothing appended to the stored memo")
    }

    @Test
    fun aMemoNumberAlreadyStoredIsStoredOnce() {
        // The phone stored the memo before it died; the printed copy is keyed again under new uuids.
        val phone = paper()
        val device = fresh.db.jdbi.withHandle<Pair<Long, String>, Exception> { h ->
            h.createQuery("SELECT id, device_uuid::text FROM app.device ORDER BY id LIMIT 1").map { rs, _ -> rs.getLong(1) to rs.getString(2) }.one()
        }
        val phoneRecs = phone.map { r ->
            if ((r["type"] as JsonPrimitive).content == "visit") JsonObject(r + ("payload" to JsonObject((r["payload"] as JsonObject) + ("visit_kind" to JsonPrimitive("sr_call")) + ("fix" to json(
                """{"purpose":"visit_open","fix_status":"ok","lat":23.80,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                   "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}""",
            ))))) else r
        }
        val res = ingest.ingest(Uploader(sr, Role.SR, 1, device.first, device.second, null), SyncBatchRequest(
            batch_uuid = uuid(), device_uuid = device.second, schema_version = 1, app_version = "1.0.9+9", trigger = "manual",
            sent_at_device = now.toString(), pending_rows = 0, time_anchors = emptyList(), device_counts = emptyMap(), records = phoneRecs,
        ))
        assertTrue(res.acks.all { it.status == "accepted" }, res.acks.toString())
        val keyed = paper(memoNo = memoNo(phone))
        val m = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), keyed)
        assertTrue(m.duplicateMemoNo)
        assertEquals(setOf("duplicate|memo_no_duplicate"), m.acks.map { "${it.status}|${it.code}" }.toSet())
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE memo_no = '${memoNo(phone)}'"))
        assertEquals(0, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${(keyed[0]["client_uuid"] as JsonPrimitive).content}'"), "nothing of the entry is written")
        assertEquals("device", str("SELECT entry_source FROM app.memo WHERE memo_no = '${memoNo(phone)}'"))
    }

    @Test
    fun theActorsOwnReachTheWindowAndTheShapeAreEnforced() {
        // An ADMIN of another territory cannot key Mirpur's day (server-side scope; nothing written).
        val e = paper()
        val far = assertFailsWith<ApiProblem> { ingest.ingestManual(web(narrowAdmin, Role.ADMIN), sr, uuid(), e) }
        assertEquals(ProblemCode.ERR_FORBIDDEN, far.code)
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${memoUuid(e)}'"))
        // Roles and clients: a TSO, or an ADMIN token from a phone, is refused.
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.TSO), sr, uuid(), paper()) }.code)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN, "app_sr"), sr, uuid(), paper()) }.code)
        // Only for an SR; only visit/memo/memo_line; a paper visit has no fix.
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), admin, uuid(), paper()) }.code)
        val withClose = paper() + envelope("visit_close", uuid(), uuid(), 1, json("""{"visit_client_uuid":"${uuid()}","outcome_code":"sold","call_declined":false,"ended_at":"2027-01-03T03:44:00.000Z","is_zero_sale":false}"""))
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), withClose) }.code)
        val p = paper()
        val withFix = listOf(JsonObject(p[0] + ("payload" to JsonObject((p[0]["payload"] as JsonObject) + ("fix" to json("""{"purpose":"visit_open"}""")))))) + p.drop(1)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), withFix) }.code)
        // Older than cfg.entry.paper_backfill_window_days (7): quarantined out of window, never stored.
        val old = paper(memoNo = "sr1001-261222-${seq.incrementAndGet()}", bd = "2026-12-22")
        val r = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), old)
        assertEquals("quarantined|business_date_out_of_window", r.acks[1].status + "|" + r.acks[1].code, r.acks.toString())
        assertTrue(!r.stored)
        assertEquals(0, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${(old[0]["client_uuid"] as JsonPrimitive).content}'"), "all or nothing: no visit left behind")
        // A future date is out of window too.
        val future = paper(memoNo = "sr1001-270104-${seq.incrementAndGet()}", bd = "2027-01-04")
        assertTrue(!ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), future).stored)
        // An inactive SR is refused.
        val sr2 = count("INSERT INTO app.app_user (username, full_name, role, password_hash, status) VALUES ('srgone', 'Gone', 'SR', 'x', 'disabled') RETURNING id")
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), sr2, uuid(), paper()) }.code)
        // 2026-12-26 is 8 calendar days back but 7 working days (Friday off): inside the paper window.
        val friday = paper(memoNo = "sr1001-261226-${seq.incrementAndGet()}", bd = "2026-12-26")
        val fr = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), friday)
        assertTrue(fr.stored, fr.acks.toString())
        // Inside the window (2 days back): stored.
        val back = paper(memoNo = "sr1001-270101-${seq.incrementAndGet()}", bd = "2027-01-01")
        assertTrue(ingest.ingestManual(web(admin, Role.SUPPORT), sr, uuid(), back).acks.all { it.status == "accepted" })
    }

    @Test
    fun theSameUuidWithOtherContentIsAPayloadConflict() {
        val e = paper()
        assertTrue(ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), e).acks.all { it.status == "accepted" })
        val changed = e.mapIndexed { i, r -> if (i == 1) JsonObject(r + ("payload" to JsonObject((r["payload"] as JsonObject) + ("paid_mtk" to JsonPrimitive(0)) + ("due_mtk" to (r["payload"] as JsonObject)["net_mtk"]!!) + ("is_credit" to JsonPrimitive(true))))) else r }
        val r = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), changed)
        assertEquals("quarantined|payload_conflict", r.acks[1].status + "|" + r.acks[1].code)
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${memoUuid(e)}'"))
        assertEquals("0", str("SELECT due_mtk::text FROM app.memo WHERE client_uuid = '${memoUuid(e)}'"), "the first copy stands")
    }

    /** Checker B1: one memo per entry, its stated line count, its money, every SKU known; anything else stores nothing. */
    @Test
    fun theWholeMemoIsJudgedTogetherAndNothingIsLeftBehind() {
        val a = paper(); val b = paper()
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), a + b) }.code, "two memos")
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), a.dropLast(1)) }.code, "line_count 2, one line sent")
        // Gross that the lines do not back: quarantined, and the visit is not stored either.
        val c = paper()
        val wrong = c.mapIndexed { i, r -> if (i == 1) JsonObject(r + ("payload" to JsonObject((r["payload"] as JsonObject) + ("gross_mtk" to JsonPrimitive(1)) + ("net_mtk" to JsonPrimitive(1)) + ("paid_mtk" to JsonPrimitive(1))))) else r }
        val w = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), wrong)
        assertTrue(!w.stored && w.acks.any { it.code == "arithmetic_mismatch" }, w.acks.toString())
        assertEquals(0, count("SELECT count(*) FROM app.visit WHERE client_uuid = '${(c[0]["client_uuid"] as JsonPrimitive).content}'"))
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${memoUuid(c)}'"))
        // An unknown SKU on one line: nothing stored.
        val d = paper()
        val unknown = d.mapIndexed { i, r -> if (i == 3) JsonObject(r + ("payload" to JsonObject((r["payload"] as JsonObject) + ("sku_id" to JsonPrimitive(999_999))))) else r }
        val u = ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), unknown)
        assertTrue(!u.stored, u.acks.toString())
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '${memoUuid(d)}'"))
        // The corrected entry (same uuids for the good rows, fixed line) then stores.
        assertTrue(ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), d).stored)
        // Re-check: stored visit and memo resent with new line uuids would over-fill the memo: 409, nothing written.
        // Same line numbers: the line index refuses them and the entry rolls back.
        val refill = d.take(2) + d.drop(2).map { JsonObject(it + ("client_uuid" to JsonPrimitive(uuid()))) }
        assertTrue(!ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), refill).stored)
        // New line numbers: stored records mixed with new ones are 409.
        val renumbered = d.take(2) + d.drop(2).mapIndexed { i, l -> JsonObject(l + ("client_uuid" to JsonPrimitive(uuid())) + ("payload" to JsonObject((l["payload"] as JsonObject) + ("line_no" to JsonPrimitive(10 + i))))) }
        assertEquals(ProblemCode.ERR_CONFLICT, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), renumbered) }.code)
        assertEquals(2, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${memoUuid(d)}'"))
        // Re-check: a memo stating a discount or QC line would escape the family check: 400.
        val e = paper()
        val disc = e.mapIndexed { i, r -> if (i == 1) JsonObject(r + ("payload" to JsonObject((r["payload"] as JsonObject) + ("discount_line_count" to JsonPrimitive(1))))) else r }
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), disc) }.code)
    }

    /** Checker B3: two keyings of one printed number at once store one memo and leave no orphan visit. */
    @Test
    fun concurrentKeyingsOfOneNumberStoreOnce() {
        val no = "sr1001-270103-${seq.incrementAndGet()}"
        val entries = List(4) { paper(memoNo = no) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val results = entries.map { e -> pool.submit<IngestService.ManualResult> { ingest.ingestManual(web(admin, Role.ADMIN), sr, uuid(), e) } }.map { it.get() }
        pool.shutdown()
        assertEquals(1, results.count { it.stored }, results.toString())
        assertEquals(3, results.count { it.duplicateMemoNo })
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE memo_no = '$no'"))
        val visits = entries.joinToString(",") { "'" + (it[0]["client_uuid"] as JsonPrimitive).content + "'" }
        assertEquals(1, count("SELECT count(*) FROM app.visit WHERE client_uuid IN ($visits)"), "no orphan visit")
    }
}
