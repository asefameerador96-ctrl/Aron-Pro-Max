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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * N-5 (lane T-KPI1, docs/status/sr-targets-server.md s3): the brand-wise KPI of the SR bundle. The figures are the owner's screenshot 60/61
 * rows: memo 90 of 119 = 75.63%, 68 of 146 = 46.58%, totals the SUM of the brand rows (265 targets, 158 achieved), a brand with no target prints no
 * percentage (null), today and another route never count, KPI money that the owner has not defined stays null.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrKpiBundleTest {
    private lateinit var fresh: FreshDb
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-03")
    private var sr = 0L
    private var route = 0L
    private var other = 0L
    private val brands = ArrayList<Long>()

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }

    private fun kpi(): JsonObject {
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        val p = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))
        return assertNotNull(service.bundle(p, day, "1.0.0+1").full.routes.single { it.route_id == route }.kpi)
    }

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c -> seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } } }
        sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        route = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
            .bundle(AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600)), day, "1.0.0+1").full.routes.first().route_id
        other = q("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'T-OTHER', 'Other route', 'Daily', zone_id, 'sr', 'daily', 127, 99 FROM app.route WHERE id = $route RETURNING id")
        // three brands that own a cigarette SKU
        val rows = fresh.db.jdbi.withHandle<List<List<Long>>, Exception> { h ->
            h.createQuery("SELECT DISTINCT b.id, (SELECT min(s2.id) FROM app.sku s2 JOIN app.product_node v2 ON v2.id = s2.variant_id WHERE v2.parent_id = b.id AND s2.category_code = 'cigarette') FROM app.product_node b WHERE b.level = 'brand' AND EXISTS (SELECT 1 FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id WHERE v.parent_id = b.id AND s.category_code = 'cigarette') ORDER BY 1 LIMIT 3")
                .map { rs, _ -> listOf(rs.getLong(1), rs.getLong(2)) }.list()
        }
        assertEquals(3, rows.size); rows.forEach { brands += it[0] }
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b002', decode(repeat('ab', 32), 'hex'), 4, $sr)")
        val b = "'00000000-0000-4000-8000-00000000b002'"
        x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, ${rows[0][1]}, DATE '2027-01-01', 10800, $b), ($route, ${rows[1][1]}, DATE '2027-01-01', 16000, $b), ($other, ${rows[0][1]}, DATE '2027-01-01', 999, $b)")
        x("INSERT INTO app.route_brand_kpi_target (route_id, brand_id, month, memo_target, kpi_alloc_mtk, batch_uuid) VALUES ($route, ${rows[0][0]}, DATE '2027-01-01', 119, 2000000, $b), ($route, ${rows[1][0]}, DATE '2027-01-01', 146, 3000000, $b), ($other, ${rows[0][0]}, DATE '2027-01-01', 5, 0, $b)")
        x("INSERT INTO app.route_kpi_month (route_id, month, kpi_alloc_mtk, cpr_target_bp, cpr_alloc_mtk, batch_uuid) VALUES ($route, DATE '2027-01-01', 5700000, 5000, 600000, $b)")
        // Real accepted memos (D-TKPI-12): the brand figures are read from app.memo / app.memo_line (the brand's cigarette SKU), grouped by the current tree.
        val o = q("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
        val fx = SrMemoFixture({ x(it) }, sr)
        val at = "TIMESTAMPTZ '2027-01-03T01:00:00Z'"
        fx.bulk("a000", route, o, "2027-01-01", rows[0][1], 50, 2000, "TIMESTAMPTZ '2027-01-01T05:00:00Z'")
        fx.bulk("a100", route, o, "2027-01-02", rows[0][1], 40, 367, at)   // 2,367 and 90 memos
        fx.bulk("b000", route, o, "2027-01-02", rows[1][1], 68, 1981, "TIMESTAMPTZ '2027-01-02T05:00:00Z'")
        fx.bulk("c000", route, o, "2027-01-03", rows[0][1], 99, 9999)                      // the bundle date: out
        fx.bulk("d000", other, o, "2027-01-02", rows[0][1], 77, 7777)                      // another route: out
        fx.bulk("e000", route, o, "2026-12-31", rows[1][1], 55, 5555)                      // last month: out
        fx.bulk("f000", route, o, "2027-01-02", rows[2][1], 3, 40, "TIMESTAMPTZ '2027-01-02T06:00:00Z'")   // sells, no target: shown with null percentages
    }

    @AfterAll fun tearDown() = fresh.close()

    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.bpOrNull(k: String) = this[k]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content.toLong() }
    private fun brandRow(k: JsonObject, id: Long) = k["brands"]!!.jsonArray.map { it.jsonObject }.single { it.long("brand_id") == id }

    @Test
    fun brandRowsCarryTheScreenshotPercentagesInBasisPoints() {
        val k = kpi()
        val a = brandRow(k, brands[0]); val b = brandRow(k, brands[1])
        assertEquals(10800, a.long("volume_target")); assertEquals(2367, a.long("volume_achieved")); assertEquals(2192L, a.bpOrNull("volume_bp"), "2367 of 10800 = 21.92%")
        assertEquals(119, a.long("memo_target")); assertEquals(90, a.long("memo_achieved")); assertEquals(7563L, a.bpOrNull("memo_bp"), "90 of 119 = 75.63%")
        assertEquals(1238L, b.bpOrNull("volume_bp")); assertEquals(4658L, b.bpOrNull("memo_bp"), "68 of 146 = 46.58% (half up)")
        assertEquals(2000000, a.long("kpi_alloc_mtk")); assertEquals(null, a.bpOrNull("kpi_today_mtk"), "the owner has not defined it")
    }

    @Test
    fun aBrandWithoutATargetHasNoPercentageNotZero() {
        val c = brandRow(kpi(), brands[2])
        assertEquals(0, c.long("memo_target")); assertEquals(3, c.long("memo_achieved")); assertEquals(null, c.bpOrNull("memo_bp")); assertEquals(null, c.bpOrNull("volume_bp"))
    }

    @Test
    fun totalsAreTheSumOfTheBrandRowsAndTodayOtherRoutesAndOtherMonthsAreOut() {
        val k = kpi()
        val memo = k["memo"]!!.jsonObject
        assertEquals(265, memo.long("target")); assertEquals(90 + 68 + 3, memo.long("achieved")); assertEquals(6075L, memo.bpOrNull("bp"), "161 of 265 = 60.75%")
        val vol = k["volume"]!!.jsonObject
        assertEquals(26800, vol.long("target")); assertEquals(2367 + 1981 + 40, vol.long("achieved"))
        assertEquals("2027-01-02", k["through_date"]!!.jsonPrimitive.content)
        assertEquals(Instant.parse("2027-01-03T01:00:00Z"), Instant.parse(k["as_of"]!!.jsonPrimitive.content))
        assertTrue(k["brands"]!!.jsonArray.none { it.jsonObject.long("volume_achieved") == 9999L || it.jsonObject.long("volume_achieved") == 7777L })
    }

    @Test
    fun monthCardAndCprAreTheAdminEntriesAndAchievedKpiStaysNull() {
        val k = kpi()
        assertEquals(5700000, k.long("allocated_mtk")); assertEquals(null, k.bpOrNull("achieved_mtk")); assertEquals(null, k.bpOrNull("achievement_bp"))
        val cpr = k["cpr"]!!.jsonObject
        assertEquals(5000, cpr.long("target_bp")); assertEquals(600000, cpr.long("alloc_mtk")); assertEquals(null, cpr.bpOrNull("achieved_bp"))
        assertEquals(false, k["brands_truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun brandsTruncatedIsTrueAt201BrandRowsAndFalseAtExactly200() {
        // the fixture has three brands with figures; add 197 brands (one variant, one cigarette SKU and a route target each) = 200, then one more = 201
        x(
            """
            WITH b AS (INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'brand', (SELECT id FROM app.product_node WHERE level = 'segment' ORDER BY id LIMIT 1), 'TB' || g, 'Trunc brand ' || g FROM generate_series(1, 198) g RETURNING id, code),
                 v AS (INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'variant', id, 'TV' || code, 'Trunc variant ' || code FROM b RETURNING id, code)
            INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, pack_type, entry_unit_default)
            SELECT 'TS' || v.code, v.id, t.category_code, 'Trunc sku ' || v.code, 'T' || v.code, t.base_unit, t.base_per_pack, t.pack_type, t.entry_unit_default
              FROM v CROSS JOIN (SELECT * FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1) t
            """.trimIndent(),
        )
        val ids = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.sku WHERE code LIKE 'TSTV%' ORDER BY id").mapTo(Long::class.java).list() }
        assertEquals(198, ids.size)
        fun target(skuIds: List<Long>) = skuIds.forEach { x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $it, DATE '2027-01-01', 1, '00000000-0000-4000-8000-00000000b002')") }
        try {
            target(ids.take(197))
            val exact = kpi()
            assertEquals(200, exact["brands"]!!.jsonArray.size, "exactly 200 brand rows")
            assertEquals(false, exact["brands_truncated"]!!.jsonPrimitive.content.toBoolean(), "exactly 200: nothing cut, no flag")
            target(ids.drop(197))
            val over = kpi()
            assertEquals(200, over["brands"]!!.jsonArray.size, "the section is cut at 200 rows")
            assertEquals(true, over["brands_truncated"]!!.jsonPrimitive.content.toBoolean(), "201 brand rows: the flag says rows were cut")
        } finally { x("DELETE FROM app.route_monthly_target WHERE route_id = $route AND sku_id IN (SELECT id FROM app.sku WHERE code LIKE 'TSTV%')") }
    }
}
