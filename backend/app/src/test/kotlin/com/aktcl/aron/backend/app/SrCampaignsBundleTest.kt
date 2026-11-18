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

    private fun line(date: String, memo: String, outlet: Long, sku: Long, qty: Int, kind: String = "sale", voided: Boolean = false, status: String = "active") = x(
        """INSERT INTO dw.fact_memo_line (business_date, line_client_uuid, memo_client_uuid, line_no, outlet_id, sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base, gross_mtk, voided, memo_status)
           VALUES (DATE '$date', '${UUID.randomUUID()}', '$memo', 1, $outlet, $sku, '$kind', $qty, 'stick', 1, $qty, 0, $voided, '$status')""",
    )
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
        line("2027-01-02", memo(), o1, skuB, 90)                                           // the seventh memo: 90 of SKU B -> 390 and 7
        line("2027-01-02", memo(), o1, skuA, 1000, voided = true)                          // voided line: out
        line("2027-01-02", memo(), o1, skuA, 1000, status = "superseded")                  // superseded memo: out
        line("2027-01-02", memo(), o1, skuA, 1000, kind = "promo_free")                    // free goods: out
        line("2027-01-02", memo(), o1, skuC, 1000)                                         // a SKU without a campaign target: out
        line("2027-01-03", memo(), o1, skuA, 5000)                                         // the bundle date: out
        line("2026-12-31", memo(), o1, skuA, 5000)                                         // last month: out
        line("2027-01-02", memo(), o2, skuA, 5000)                                         // another outlet (no campaign row): out
        x("INSERT INTO dw.summary_freshness (table_name, last_updated_at, last_event_id) VALUES ('agg_daily_route_sku', TIMESTAMPTZ '2027-01-03T01:00:00Z', 1) ON CONFLICT (table_name) DO UPDATE SET last_updated_at = EXCLUDED.last_updated_at")
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
}
