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
import com.aktcl.aron.backend.sync.SrTargets
import com.aktcl.aron.contract.Role
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
 * N-2 (lane T-KPI1, docs/status/sr-targets-server.md s2): the SR bundle carries the route's per-SKU monthly targets, the
 * server month-to-date achievement through the day BEFORE the bundle date (today never in a server figure, so the phone's
 * local memos are never counted twice) and the working-day numbers; another route's rows never appear.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrTargetsBundleTest {
    private lateinit var fresh: FreshDb
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-03")
    private var sr = 0L
    private var route = 0L
    private var otherRoute = 0L
    private var skuA = 0L
    private var skuB = 0L
    private val asOf = "2027-01-03T01:00:00Z"

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }

    private fun build(): BundleService.Result {
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        val p = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))
        return service.bundle(p, day, "1.0.0+1")
    }

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        route = build().full.routes.first().route_id
        otherRoute = q("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'T-OTHER', 'Other route', 'Daily', zone_id, 'sr', 'daily', 127, 99 FROM app.route WHERE id = $route RETURNING id")
        val skus = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.sku ORDER BY id LIMIT 2").mapTo(Long::class.java).list() }
        skuA = skus[0]; skuB = skus[1]
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b001', decode(repeat('ab', 32), 'hex'), 4, $sr)")
        for ((r, s, q) in listOf(Triple(route, skuA, 500), Triple(route, skuB, 900), Triple(otherRoute, skuA, 77)))
            x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($r, $s, DATE '2027-01-01', $q, '00000000-0000-4000-8000-00000000b001')")
        x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $skuA, DATE '2026-12-01', 1, '00000000-0000-4000-8000-00000000b001')") // another month: out
        // Real accepted memos (D-TKPI-12): the figures are read from app.memo / app.memo_line, not from dw.agg_*.
        val o = q("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
        val fx = SrMemoFixture({ x(it) }, sr)
        fun uid(n: Int) = "00000000-0000-4000-8000-0000000d%04d".format(n)
        fx.memo(uid(1), route, o, "2027-01-01", listOf(SrMemoFixture.L(skuA, 60)), "TIMESTAMPTZ '2027-01-01T05:00:00Z'")
        fx.memo(uid(2), route, o, "2027-01-01", listOf(SrMemoFixture.L(skuA, 40)), "TIMESTAMPTZ '2027-01-01T06:00:00Z'")
        fx.memo(uid(3), route, o, "2027-01-02", listOf(SrMemoFixture.L(skuA, 50)), "TIMESTAMPTZ '$asOf'")
        fx.memo(uid(4), route, o, "2027-01-03", listOf(SrMemoFixture.L(skuA, 999)), "TIMESTAMPTZ '2027-01-03T01:30:00Z'")   // the bundle date: never in a server figure
        fx.memo(uid(5), route, o, "2026-12-31", listOf(SrMemoFixture.L(skuA, 7)), "TIMESTAMPTZ '2026-12-31T05:00:00Z'")      // last month: out
        fx.memo(uid(6), otherRoute, o, "2027-01-02", listOf(SrMemoFixture.L(skuA, 5000)), "TIMESTAMPTZ '2027-01-02T05:00:00Z'") // another route: out
        fx.memo(uid(7), route, o, "2027-01-02", listOf(SrMemoFixture.L(skuA, 11)), "TIMESTAMPTZ '2027-01-02T07:00:00Z'")
        fx.void(uid(7), "2027-01-02")                                                                                          // voided: out
        fx.memo(uid(8), route, o, "2027-01-02", listOf(SrMemoFixture.L(skuA, 13)), "TIMESTAMPTZ '2027-01-02T08:00:00Z'")
        fx.memo(uid(9), route, o, "2027-01-02", listOf(SrMemoFixture.L(skuA, 17)), "TIMESTAMPTZ '2027-01-02T09:00:00Z'", supersedes = uid(8)) // 8 superseded by 9: 9 in, 8 out
    }

    @AfterAll fun tearDown() = fresh.close()

    @Test
    fun targetsAreTheRoutesOwnRowsOfTheMonthInBaseUnitsAsDecimal3() {
        val r = build().full.routes.single { it.route_id == route }
        val rows = r.targets.map { it.jsonObject }
        assertEquals(setOf(skuA, skuB), rows.map { it["product_id"]!!.jsonPrimitive.content.toLong() }.toSet())
        val a = rows.single { it["product_id"]!!.jsonPrimitive.content.toLong() == skuA }
        assertEquals("500.000", a["std_target"]!!.jsonPrimitive.content)
        assertEquals("route", a["scope_type"]!!.jsonPrimitive.content); assertEquals("2027-01", a["month"]!!.jsonPrimitive.content)
        assertEquals("sku", a["product_level"]!!.jsonPrimitive.content); assertEquals("0", a["memo_target"]!!.jsonPrimitive.content)
        assertEquals(q("SELECT 1").let { fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT base_unit FROM app.sku WHERE id = $skuA").mapTo(String::class.java).one() } }, a["std_unit"]!!.jsonPrimitive.content)
        assertTrue(rows.none { it["scope_id"]!!.jsonPrimitive.content.toLong() == otherRoute }, "another route's target never rides in this bundle")
    }

    @Test
    fun achievementIsThroughYesterdayOnlyWithAsOfAndNeverTodayNorOtherMonthsNorOtherRoutes() {
        val r = build().full.routes.single { it.route_id == route }
        val a = r.achievement_mtd.map { it.jsonObject }.single()
        assertEquals(skuA, a["product_id"]!!.jsonPrimitive.content.toLong())
        assertEquals("167", a["achieved_qty_base"]!!.jsonPrimitive.content, "60 + 40 + 50 + 17 (the edit); today's 999, last month's 7, the other route's 5000, the voided 11 and the superseded 13 are out")
        assertEquals("4", a["memo_count"]!!.jsonPrimitive.content)
        assertEquals("2027-01-02", a["through_date"]!!.jsonPrimitive.content)
        assertEquals(Instant.parse(asOf), Instant.parse(a["as_of"]!!.jsonPrimitive.content), "as_of is informational: the newest received_at among the memos read (memo 3); today's memo (01:30) and the other route's are not read")
    }

    @Test
    fun calendarMatchesTheIndependentViewAndCarriesThroughDateAndAsOf() {
        val c = assertNotNull(build().full.routes.single { it.route_id == route }.calendar)
        fun n(sql: String) = q(sql).toInt()
        assertEquals(n("SELECT working_days FROM dw.v_calendar_month WHERE month = DATE '2027-01-01'"), c.working_days)
        assertEquals(n("SELECT count(*) FROM dw.v_calendar WHERE business_date BETWEEN DATE '2027-01-01' AND DATE '2027-01-02' AND is_working_day"), c.elapsed_working_days_before_today)
        assertEquals(n("SELECT count(*) FROM dw.v_calendar WHERE business_date BETWEEN DATE '2027-01-04' AND DATE '2027-01-31' AND is_working_day"), c.remaining_working_days_after_today)
        assertEquals(n("SELECT working_days FROM dw.v_calendar_month WHERE month = DATE '2026-12-01'"), c.last_month_working_days)
        assertEquals(q("SELECT count(*) FROM dw.v_calendar WHERE business_date = DATE '2027-01-03' AND is_working_day") == 1L, c.today_is_working)
        assertEquals("2027-01", c.month); assertEquals("2027-01-02", c.through_date)
        assertEquals(c.working_days, c.elapsed_working_days_before_today + c.remaining_working_days_after_today + (if (c.today_is_working) 1 else 0), "the three parts add up to the month")
    }

    @Test
    fun firstOfTheMonthHasNoServerAchievementAndAskedRoutesOnlyAreAnswered() {
        val got = fresh.db.jdbi.withHandle<Map<Long, SrTargets.Snapshot>, Exception> { h ->
            SrTargets.load(h, mapOf(route to q("SELECT zone_id FROM app.route WHERE id = $route")), LocalDate.parse("2027-02-01"), clock.now(), null)
        }
        assertEquals(setOf(route), got.keys)
        assertTrue(got.getValue(route).achievement.isEmpty(), "day 1: the day before is last month, so nothing is in the month-to-date figure")
        assertTrue(got.getValue(route).targets.isEmpty(), "no February target uploaded")
        assertEquals(0, got.getValue(route).calendar.elapsed_working_days_before_today)
    }

    @Test
    fun targetsTruncatedIsTrueAt501RowsAndAbsentAtExactly500() {
        // skuA and skuB already have target rows: 499 more SKUs make 501
        x("INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, pack_type, entry_unit_default) SELECT 'TR-' || g, s.variant_id, s.category_code, 'Trunc ' || g, 'Trunc' || g, s.base_unit, s.base_per_pack, s.pack_type, s.entry_unit_default FROM app.sku s, generate_series(1, 499) g WHERE s.id = $skuA AND NOT EXISTS (SELECT 1 FROM app.sku WHERE code = 'TR-1')")
        x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) SELECT $route, id, DATE '2027-01-01', 1, '00000000-0000-4000-8000-00000000b001' FROM app.sku WHERE code LIKE 'TR-%'")
        try {
            val over = build().full.routes.single { it.route_id == route }
            assertEquals(500, over.targets.size, "the section is cut at 500 rows")
            assertEquals(true, over.targets_truncated, "501 target rows: the route says rows were cut")
            x("DELETE FROM app.route_monthly_target WHERE route_id = $route AND sku_id = (SELECT id FROM app.sku WHERE code = 'TR-1')")
            val exact = build().full.routes.single { it.route_id == route }
            assertEquals(500, exact.targets.size)
            assertEquals(null, exact.targets_truncated, "exactly 500 rows: nothing was cut, no flag")
        } finally { x("DELETE FROM app.route_monthly_target WHERE route_id = $route AND sku_id IN (SELECT id FROM app.sku WHERE code LIKE 'TR-%')") }
    }
}
