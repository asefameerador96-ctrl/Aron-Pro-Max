package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.BundleService
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * N-3 (lane T-KPI1, docs/status/sr-targets-server.md s5): the outlet campaign target of the SR bundle. Screenshot 21's pop-up figures ride exactly
 * (STD 2240 / 390 / 1850, memos 18 / 7 / 11); memos are distinct memos with a campaign-SKU line; voided, superseded, free-goods lines, other SKUs, today, other
 * outlets and other months never count; an outlet without a campaign row has none; an inactive programme shows nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrCampaignsBundleTest {
    private lateinit var fresh: FreshDb
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-03")
    private var sr = 0L
    private var route = 0L
    private var o1 = 0L
    private var o2 = 0L
    private var programme = 0L
    private var skuA = 0L
    private var skuB = 0L
    private var skuC = 0L

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }
    private fun bundle(): BundleService.Result {
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        return service.bundle(AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600)), day, "1.0.0+1")
    }
    private fun campaigns(): List<JsonObject> = bundle().full.routes.single { it.route_id == route }.campaigns.orEmpty().map { it.jsonObject }
    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.content.toLong()

    // Real accepted one-line memos (D-TKPI-12): the figures are read from app.memo / app.memo_line.
    private val fx by lazy { SrMemoFixture({ x(it) }, sr) }
    private fun line(date: String, memo: String, outlet: Long, sku: Long, qty: Int, kind: String = "sale", voided: Boolean = false, at: String = "TIMESTAMPTZ '2027-01-02T06:00:00Z'") {
        fx.memo(memo, route, outlet, date, listOf(SrMemoFixture.L(sku, qty, kind)), at)
        if (voided) x("UPDATE app.memo_line SET voided_at = now() WHERE memo_client_uuid = CAST('$memo' AS uuid)")
    }
    private fun memo() = UUID.randomUUID().toString()

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c -> seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } } }
        sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val r = bundle().full.routes.first(); route = r.route_id
        o1 = r.outlets[0].outlet_id; o2 = r.outlets[1].outlet_id
        val outside = q("SELECT id FROM app.outlet WHERE id NOT IN (${r.outlets.joinToString(",") { it.outlet_id.toString() }}) ORDER BY id LIMIT 1")
        val skus = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.sku ORDER BY id LIMIT 3").mapTo(Long::class.java).list() }
        skuA = skus[0]; skuB = skus[1]; skuC = skus[2]
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b004', decode(repeat('ab', 32), 'hex'), 1, $sr)")
        programme = q("INSERT INTO app.programme (kind, code, name_en, name_bn, active_from, active_to, status) VALUES ('campaign', 'SSL', 'Super Star Low (January)', 'সুপার স্টার লো', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        val b = "'00000000-0000-4000-8000-00000000b004'"
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($programme, DATE '2027-01-01', $o1, 2240.000, 18, $b), ($programme, DATE '2027-01-01', $outside, 9, 9, $b)")
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($programme, DATE '2027-01-01', $o1, $skuA, 1500, $b), ($programme, DATE '2027-01-01', $o1, $skuB, 740, $b), ($programme, DATE '2027-01-01', $outside, $skuA, 5, $b)")
        for (i in 1..6) line("2027-01-0${if (i <= 2) 1 else 2}", memo(), o1, skuA, 50)   // six memos, 300 of SKU A
        line("2027-01-02", memo(), o1, skuB, 90, at = "TIMESTAMPTZ '2027-01-03T01:00:00Z'") // the seventh memo: 90 of SKU B -> 390 and 7; the newest memo read
        line("2027-01-02", memo(), o1, skuA, 1000, voided = true)                          // voided line: out
        val sup = memo(); line("2027-01-02", sup, o1, skuA, 1000)                          // superseded memo: out (the edit holds a SKU without a campaign target)
        fx.memo(memo(), route, o1, "2027-01-02", listOf(SrMemoFixture.L(skuC, 1)), "now()", supersedes = sup)
        line("2027-01-02", memo(), o1, skuA, 1000, kind = "promo_free")                    // free goods: out
        line("2027-01-02", memo(), o1, skuC, 1000)                                         // a SKU without a campaign target: out
        line("2027-01-03", memo(), o1, skuA, 5000)                                         // the bundle date: out
        line("2026-12-31", memo(), o1, skuA, 5000)                                         // last month: out
        line("2027-01-02", memo(), o2, skuA, 5000)                                         // another outlet (no campaign row): out
    }

    @AfterAll fun tearDown() = fresh.close()

    @Test
    fun theOutletPopUpFiguresAreTheScreenshotsAndOnlyThatOutletAppears() {
        val c = campaigns().single()
        assertEquals(o1, c.l("outlet_id")); assertEquals("Super Star Low (January)", c["label_en"]!!.jsonPrimitive.content)
        val std = c["std"]!!.jsonObject; val memo = c["memo"]!!.jsonObject
        assertEquals(2240, std.l("target")); assertEquals(390, std.l("achieved")); assertEquals(1850, std.l("remaining"))
        assertEquals(18, memo.l("target")); assertEquals(7, memo.l("achieved")); assertEquals(11, memo.l("remaining"))
        assertEquals("2027-01-02", c["through_date"]!!.jsonPrimitive.content); assertEquals(Instant.parse("2027-01-03T01:00:00Z"), Instant.parse(c["as_of"]!!.jsonPrimitive.content))
    }

    @Test
    fun theSkuTableCarriesTargetAndSoldPerCampaignSkuOnly() {
        val skus = campaigns().single()["skus"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(skuA, skuB), skus.map { it.l("sku_id") })
        assertEquals(listOf(1500L, 740L), skus.map { it.l("target_qty_base") }); assertEquals(listOf(300L, 90L), skus.map { it.l("sold_qty_base") })
    }

    @Test
    fun aProgrammeOutsideItsDatesShowsNothing() {
        x("UPDATE app.programme SET active_to = DATE '2027-01-02' WHERE id = $programme")
        try { assertTrue(campaigns().isEmpty()) } finally { x("UPDATE app.programme SET active_to = DATE '2027-01-31' WHERE id = $programme") }
        assertNotNull(campaigns().singleOrNull())
        assertTrue(bundle().full.routes.single { it.route_id == route }.campaigns!!.none { it["outlet_id"]!!.let { v -> v !is JsonNull && v.jsonPrimitive.content.toLong() != o1 } })
    }

    @Test
    fun aSecondCampaignOnTheOutletNeverMixesItsMemosIntoTheShownOne() {
        // another campaign programme of the same outlet and month owns SKU C; the memo of SKU C (the "a SKU without a campaign target" line of the set-up) must stay out of campaign 1
        val p2 = q("INSERT INTO app.programme (kind, code, name_en, name_bn, active_from, active_to, status) VALUES ('campaign', 'SSL2', 'Second campaign', NULL, DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        val b = "'00000000-0000-4000-8000-00000000b004'"
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($p2, DATE '2027-01-01', $o1, 100, 5, $b)")
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($p2, DATE '2027-01-01', $o1, $skuC, 100, $b)")
        try {
            val c = campaigns().single()
            assertEquals(programme, c.l("programme_id"), "the lowest programme id is the shown one")
            assertEquals(7, c["memo"]!!.jsonObject.l("achieved"), "the memo of SKU C belongs to the other campaign: still 7, not 8")
            assertEquals(390, c["std"]!!.jsonObject.l("achieved"))
            assertEquals(true, c["truncated"]!!.jsonPrimitive.content.toBoolean(), "this outlet's own flag says another active campaign of the outlet is not shown (second-campaign branch)")
            assertEquals(true, bundle().full.routes.single { it.route_id == route }.campaigns_truncated, "a second active campaign at the outlet was not shown: the route says so (never a silent drop)")
            assertEquals(null, bundle().full.routes.single { it.route_id == route }.campaigns_outlets_truncated, "an outlet with a hidden second campaign is still a COMPLETE outlet: no outlet is missing")
        } finally {
            x("DELETE FROM app.outlet_campaign_sku_target WHERE programme_id = $p2"); x("DELETE FROM app.outlet_campaign_target WHERE programme_id = $p2"); x("DELETE FROM app.programme WHERE id = $p2")
        }
        assertEquals(null, bundle().full.routes.single { it.route_id == route }.campaigns_truncated, "with one campaign per outlet the flag is absent")
    }

    @Test
    fun capsAreStatedNeverSilentAndTheCutHappensBeforeTheFiguresAreRead() {
        val got = fresh.db.jdbi.withHandle<Map<Long, com.aktcl.aron.backend.sync.SrCampaigns.Result>, Exception> { h ->
            com.aktcl.aron.backend.sync.SrCampaigns.load(h, mapOf(route to listOf(o1)), day, day.minusDays(1), maxOutlets = 1, maxSkus = 1)
        }
        val r = got.getValue(route)
        assertTrue(r.truncated, "outlet o1 has two SKU targets and the cap is 1: truncated says so")
        assertEquals(1, r.rows.single()["skus"]!!.jsonArray.size)
        val whole = fresh.db.jdbi.withHandle<Map<Long, com.aktcl.aron.backend.sync.SrCampaigns.Result>, Exception> { h -> com.aktcl.aron.backend.sync.SrCampaigns.load(h, mapOf(route to listOf(o1)), day, day.minusDays(1)) }
        assertEquals(false, whole.getValue(route).truncated)
        assertEquals(150, com.aktcl.aron.backend.sync.SrCampaigns.MAX_OUTLETS); assertEquals(20, com.aktcl.aron.backend.sync.SrCampaigns.MAX_SKUS)
    }

    @Test
    fun theCampaignSectionStaysInItsSizeBudgetAtTheDenseWorstCase() {
        // docs/04: 70 outlets x 20 SKUs (dense) at most 120 KB raw and 20 KB gzip. Built from the real row of the bundle with realistic six-digit figures.
        val real = campaigns().single()
        val rows = (1..70).map { i ->
            kotlinx.serialization.json.buildJsonObject {
                real.forEach { (k, v) -> if (k != "skus" && k != "outlet_id") put(k, v) }
                put("outlet_id", 100_000L + i)
                put("skus", kotlinx.serialization.json.buildJsonArray { for (j in 1..20) add(kotlinx.serialization.json.buildJsonObject { put("sku_id", 1_000L + j); put("target_qty_base", 123_456L); put("sold_qty_base", 98_765L) }) })
            }
        }
        val raw = kotlinx.serialization.json.JsonArray(rows).toString().toByteArray()
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(raw) } }.size()
        assertTrue(raw.size <= 120 * 1024, "campaigns raw ${raw.size} bytes over the 120 KB budget")
        assertTrue(gz <= 20 * 1024, "campaigns gzip $gz bytes over the 20 KB budget")
    }

    @Test
    fun aCutSkuTableStillCountsEveryCampaignSkuAndOnlyTheCutOutletsAreFlagged() {
        // an outlet whose campaign has 22 SKU targets: the table shows the first 20, the sales are on SKUs 21 and 22 (the two that are cut)
        val skus = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.sku ORDER BY id OFFSET 3 LIMIT 22").mapTo(Long::class.java).list() }
        assertEquals(22, skus.size, "the seed has at least 25 SKUs (the first 3 are used by the other tests)")
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b0c4', decode(repeat('ab', 32), 'hex'), 1, $sr) ON CONFLICT DO NOTHING")
        val b = "'00000000-0000-4000-8000-00000000b0c4'"
        val p22 = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSL22', 'Twenty-two SKUs', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($p22, DATE '2027-01-01', $o2, 2200, 9, $b)")
        skus.forEach { x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($p22, DATE '2027-01-01', $o2, $it, 100, $b)") }
        val m21 = memo(); val m22 = memo()
        line("2027-01-02", m21, o2, skus[20], 7); line("2027-01-02", m22, o2, skus[21], 5)
        try {
            val rows = campaigns()
            val cut = rows.single { it.l("outlet_id") == o2 }
            assertEquals(20, cut["skus"]!!.jsonArray.size, "the table is cut at 20 SKUs")
            assertEquals(true, cut["truncated"]!!.jsonPrimitive.content.toBoolean(), "this outlet's own flag says its table is cut")
            assertEquals(12L, cut["std"]!!.jsonObject.l("achieved"), "the achieved STD counts the SALES of SKUs 21 and 22 although the table does not list them (7 + 5)")
            assertEquals(2L, cut["memo"]!!.jsonObject.l("achieved"), "and the memo count counts both memos")
            val other = rows.single { it.l("outlet_id") == o1 }
            assertEquals(false, other["truncated"]!!.jsonPrimitive.content.toBoolean(), "an outlet whose table is whole carries false (the member is on every entry from 1.27.0)")
            assertEquals(true, bundle().full.routes.single { it.route_id == route }.campaigns_truncated, "the route-level flag stays")
        } finally {
            x("UPDATE app.memo_line SET voided_at = now() WHERE memo_client_uuid IN (CAST('$m21' AS uuid), CAST('$m22' AS uuid))")   // synced rows are tombstoned, never deleted
            x("DELETE FROM app.outlet_campaign_sku_target WHERE programme_id = $p22"); x("DELETE FROM app.outlet_campaign_target WHERE programme_id = $p22"); x("DELETE FROM app.programme WHERE id = $p22")
        }
    }

    @Test
    fun aCutTableWithASecondCampaignNeverCountsASkuOutsideTheChosenCampaign() {
        // o2: the chosen campaign (lowest id) has 22 SKU targets (table cut at 20); a SECOND campaign of the same outlet owns SKUs skuA and skuC, which also sell at o2 (the set-up has 5000 of skuA there)
        val skus = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.sku ORDER BY id OFFSET 3 LIMIT 22").mapTo(Long::class.java).list() }
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b0c4', decode(repeat('ab', 32), 'hex'), 1, $sr) ON CONFLICT DO NOTHING")
        val b = "'00000000-0000-4000-8000-00000000b0c4'"
        val chosen = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSLC', 'Chosen 22', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        val second = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSLD', 'Second', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($chosen, DATE '2027-01-01', $o2, 2200, 9, $b), ($second, DATE '2027-01-01', $o2, 50, 2, $b)")
        skus.forEach { x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($chosen, DATE '2027-01-01', $o2, $it, 100, $b)") }
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($second, DATE '2027-01-01', $o2, $skuA, 40, $b), ($second, DATE '2027-01-01', $o2, $skuC, 10, $b)")
        val m21 = memo(); val m22 = memo(); val mc = memo()
        line("2027-01-02", m21, o2, skus[20], 7); line("2027-01-02", m22, o2, skus[21], 5)   // the two CUT SKUs of the chosen campaign: counted
        line("2027-01-02", mc, o2, skuC, 300)                                              // the second campaign's SKU: never counted in the chosen figure
        try {
            val cut = campaigns().single { it.l("outlet_id") == o2 }
            assertEquals(chosen, cut.l("programme_id"), "the lowest programme id of the outlet is the chosen campaign")
            assertEquals(20, cut["skus"]!!.jsonArray.size); assertEquals(true, cut["truncated"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(12L, cut["std"]!!.jsonObject.l("achieved"), "7 + 5 of the cut SKUs; the 5000 of SKU A and the 300 of SKU C belong to the second campaign")
            assertEquals(2L, cut["memo"]!!.jsonObject.l("achieved"), "two memos; the memos of the second campaign's SKUs are not counted")
            assertEquals(2200 - 12L, cut["std"]!!.jsonObject.l("remaining"))
        } finally {
            x("UPDATE app.memo_line SET voided_at = now() WHERE memo_client_uuid IN (CAST('$m21' AS uuid), CAST('$m22' AS uuid), CAST('$mc' AS uuid))")
            x("DELETE FROM app.outlet_campaign_sku_target WHERE programme_id IN ($chosen, $second)"); x("DELETE FROM app.outlet_campaign_target WHERE programme_id IN ($chosen, $second)"); x("DELETE FROM app.programme WHERE id IN ($chosen, $second)")
        }
    }

    @Test
    fun everyEntryCarriesTruncatedAndTheRouteSaysWhenOutletsThemselvesAreMissing() {
        // contract 1.27.0: `truncated` is on EVERY entry (false when whole) so the phone can tell a 1.26+ route; `campaigns_outlets_truncated` is true only when the outlet LIST was cut by the cap
        val solo = campaigns().single()
        assertEquals(false, solo["truncated"]!!.jsonPrimitive.content.toBoolean(), "an uncut outlet carries false, not an absent member")
        assertEquals(null, bundle().full.routes.single { it.route_id == route }.campaigns_outlets_truncated, "nothing cut: the route-level flag is absent")
        // a second campaign outlet (o2) with a one-SKU table; the cap lets ONE outlet through: the route is over the outlet cap, and NO shown outlet is itself cut
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b0c5', decode(repeat('ab', 32), 'hex'), 1, $sr) ON CONFLICT DO NOTHING")
        val b = "'00000000-0000-4000-8000-00000000b0c5'"
        val p = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSLO', 'Outlet cap', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($p, DATE '2027-01-01', $o2, 10, 1, $b)")
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($p, DATE '2027-01-01', $o2, $skuA, 10, $b)")
        try {
            val capped = fresh.db.jdbi.withHandle<com.aktcl.aron.backend.sync.SrCampaigns.Result, Exception> { h ->
                com.aktcl.aron.backend.sync.SrCampaigns.load(h, mapOf(route to listOf(o1, o2)), day, day.minusDays(1), maxOutlets = 1).getValue(route)
            }
            assertEquals(listOf(o1), capped.rows.map { it.l("outlet_id") }, "the lowest outlet id passes the cap")
            assertEquals(false, capped.rows.single()["truncated"]!!.jsonPrimitive.content.toBoolean(), "the shown outlet is whole: its own flag is false")
            assertTrue(capped.outletsTruncated, "but outlets are missing: the route-level outlet flag says so"); assertTrue(capped.truncated, "and campaigns_truncated (the OR) is true")
            val whole = fresh.db.jdbi.withHandle<com.aktcl.aron.backend.sync.SrCampaigns.Result, Exception> { h -> com.aktcl.aron.backend.sync.SrCampaigns.load(h, mapOf(route to listOf(o1, o2)), day, day.minusDays(1)).getValue(route) }
            assertEquals(2, whole.rows.size); assertEquals(false, whole.outletsTruncated); assertEquals(false, whole.truncated)
            assertTrue(whole.rows.all { it["truncated"]!!.jsonPrimitive.content == "false" })
        } finally { x("DELETE FROM app.outlet_campaign_sku_target WHERE programme_id = $p"); x("DELETE FROM app.outlet_campaign_target WHERE programme_id = $p"); x("DELETE FROM app.programme WHERE id = $p") }
    }

    @Test
    fun aBundleWithMoreThan150CampaignOutletsSaysOutletsAreMissing() {
        // bundle level: 151 cloned outlets on the route (plus o1) enrolled in the campaign; the cap is 150, the lowest outlet ids pass
        x("INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, lat, lng, location_basis, location_confirmed, status, visit_sequence) SELECT 'TC'||g, 'Cap clone '||g, 'Owner', zone_id, route_id, cluster_id, channel, lat, lng, location_basis, location_confirmed, 'active', 100 + g FROM app.outlet, generate_series(1, 151) g WHERE id = $o1")
        val clones = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.outlet WHERE code LIKE 'TC%' ORDER BY id").mapTo(Long::class.java).list() }
        assertEquals(151, clones.size)
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b0c6', decode(repeat('ab', 32), 'hex'), 1, $sr) ON CONFLICT DO NOTHING")
        val b = "'00000000-0000-4000-8000-00000000b0c6'"
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) SELECT $programme, DATE '2027-01-01', id, 10, 1, $b FROM app.outlet WHERE code LIKE 'TC%'")
        try {
            val r = bundle().full.routes.single { it.route_id == route }
            assertEquals(150, r.campaigns!!.size, "the list is cut at the outlet cap")
            assertEquals(true, r.campaigns_outlets_truncated, "outlets are missing: the route says so")
            assertEquals(true, r.campaigns_truncated, "and the OR flag is true")
            assertTrue(r.campaigns!!.all { it.jsonObject["truncated"]!!.jsonPrimitive.content == "false" }, "no shown outlet is itself cut")
        } finally { x("DELETE FROM app.outlet_campaign_target WHERE outlet_id IN (SELECT id FROM app.outlet WHERE code LIKE 'TC%')"); x("DELETE FROM app.outlet WHERE code LIKE 'TC%'") }
    }
}
