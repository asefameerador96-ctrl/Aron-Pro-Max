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
import com.aktcl.aron.rules.StarProgress
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNull

/**
 * N-8 (lane T-KPI1, docs/status/sr-targets-server.md s4): the Match Box Lifting Star snapshot in the SR bundle. Screenshot 62's quantities (target 20.000,
 * STD 2.833, LMS 12.250 with unit divisor 1000) ride exactly; the ratios are those of [StarProgress] on the bundle's own calendar numbers; slabs read the
 * programme's flat scalar rules; today, other routes and other months never count; a route without a target has no star; an inactive programme has none.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrStarBundleTest {
    private lateinit var fresh: FreshDb
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-03")
    private var sr = 0L
    private var route = 0L
    private var otherRoute = 0L
    private var programme = 0L
    private var matchSku = 0L

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }
    private fun bundle(): BundleService.Result {
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        return service.bundle(AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600)), day, "1.0.0+1")
    }
    private fun star(): JsonObject? = bundle().full.routes.single { it.route_id == route }.star
    private fun JsonObject.s(k: String) = this[k]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c -> seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } } }
        sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        route = bundle().full.routes.first().route_id
        otherRoute = q("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'T-OTHER', 'Other route', 'Daily', zone_id, 'sr', 'daily', 127, 99 FROM app.route WHERE id = $route RETURNING id")
        matchSku = q("SELECT id FROM app.sku WHERE category_code = 'match' ORDER BY id LIMIT 1")
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b003', decode(repeat('ab', 32), 'hex'), 2, $sr)")
        programme = q(
            """INSERT INTO app.programme (kind, code, name_en, name_bn, active_from, active_to, status, rules) VALUES ('superstar', 'MBLS', 'Match Box Lifting Star', 'ম্যাচ বক্স লিফটিং স্টার', DATE '2026-12-01', DATE '2027-01-31', 'active',
               '{"sku_category":"match","unit_divisor":1000,"slab1_label":"Silver","slab1_min_bp":5000,"slab1_amount_mtk":1000000,"slab2_label":"Gold","slab2_min_bp":10000,"slab2_amount_mtk":2500000,"slab3_label":"Diamond","slab3_min_bp":50000,"slab3_amount_mtk":9000000}') RETURNING id""",
        )
        val b = "'00000000-0000-4000-8000-00000000b003'"
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($programme, $route, DATE '2027-01-01', 20.000, $b), ($programme, $otherRoute, DATE '2027-01-01', 5.000, $b)")
        // Real accepted memos (D-TKPI-12); the Star figures are read from app.memo / app.memo_line (match SKU).
        val o = q("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
        val fx = SrMemoFixture({ x(it) }, sr)
        fun m(n: Int, r: Long, d: String, qty: Int, at: String = "2027-01-03T01:00:00Z") = fx.memo("00000000-0000-4000-8000-0000000e%04d".format(n), r, o, d, listOf(SrMemoFixture.L(matchSku, qty)), "TIMESTAMPTZ '$at'")
        m(1, route, "2027-01-01", 2000); m(2, route, "2027-01-02", 833)   // STD 2833 base = 2.833, newest received_at 01:00
        m(3, route, "2027-01-03", 99999, "2027-01-03T01:30:00Z")           // the bundle date: out (and not in as_of)
        m(4, otherRoute, "2027-01-02", 77777)                              // another route: out
        m(5, route, "2026-12-10", 12000); m(6, route, "2026-12-31", 250)   // LMS 12250 base = 12.250
        m(7, route, "2026-11-30", 55555)                                   // two months ago: out
    }

    @AfterAll fun tearDown() = fresh.close()

    @Test
    fun quantitiesAreTheScreenshotsAndTodayOtherRoutesAndOldMonthsAreOut() {
        val s = assertNotNull(star())
        assertEquals("20.000", s.s("target")); assertEquals("2.833", s.s("std")); assertEquals("12.250", s.s("lms"))
        assertEquals("2027-01", s.s("month")); assertEquals("2027-01-02", s.s("through_date")); assertEquals("MBLS", s.s("programme_code"))
        assertEquals(Instant.parse("2027-01-03T01:00:00Z"), Instant.parse(s.s("as_of")!!))
        assertEquals(fresh.db.jdbi.withHandle<String, Exception> { h -> h.createQuery("SELECT name FROM app.route WHERE id = $route").mapTo(String::class.java).one() }, s.s("route_name"), "the Star row carries the route's own name")
    }

    @Test
    fun ratiosAreStarProgressOnTheBundlesOwnCalendar() {
        val r = bundle().full.routes.single { it.route_id == route }
        val c = assertNotNull(r.calendar); val s = assertNotNull(r.star)
        assertEquals(c.elapsed_working_days_before_today, s.s("ewd")!!.toInt()); assertEquals(c.working_days, s.s("wd")!!.toInt()); assertEquals(c.last_month_working_days, s.s("lmwd")!!.toInt())
        val want = StarProgress.compute(StarProgress.Input(20_000, 2_833, 12_250, 1_000, c.elapsed_working_days_before_today, c.working_days, c.last_month_working_days))
        fun d3(v: Long?) = v?.let { java.math.BigDecimal.valueOf(it, 3).toPlainString() }
        assertEquals(d3(want.projectionMilli), s.s("projection")); assertEquals(d3(want.padsMilli), s.s("pads")); assertEquals(d3(want.lmadsMilli), s.s("lmads"))
        assertEquals(want.achievementBp?.toString(), s.s("achievement_bp")); assertEquals(want.growthBp?.toString(), s.s("growth_bp"))
    }

    @Test
    fun slabsFollowTheActualAchievementForTheAchievedSlabAndTheProjectionForTheProjectedAmount() {
        val s = assertNotNull(star())
        // Actual 2.833 of 20 = 14.17% reaches no slab (the first is 50%); the projection is far above 100% on day 1, so the projected rung is the highest it clears.
        assertNull(s.s("achieved_slab")); assertNull(s.s("slab_amount_mtk"))
        val proj = s.s("achievement_bp")!!.toLong()
        val rung = StarProgress.rungFor(listOf(StarProgress.Rung("Silver", 5000, 1_000_000), StarProgress.Rung("Gold", 10_000, 2_500_000), StarProgress.Rung("Diamond", 50_000, 9_000_000)), proj)
        assertEquals(rung?.label, s.s("projected_slab")); assertEquals(rung?.amountMtk?.toString(), s.s("projected_amount_mtk"))
        assertEquals("On Track".takeIf { proj >= 10_000 } ?: "Keep Pushing", s.s("status_en"), "default status rule: 100% and over is On Track")
    }

    @Test
    fun aProgrammeOutsideItsDatesHasNoStar() {
        x("UPDATE app.programme SET active_to = DATE '2027-01-02' WHERE id = $programme")
        try { assertNull(star(), "a programme that ended before the bundle date has no snapshot") } finally { x("UPDATE app.programme SET active_to = DATE '2027-01-31' WHERE id = $programme") }
        x("UPDATE app.programme SET active_from = DATE '2027-01-04' WHERE id = $programme")
        try { assertNull(star(), "a programme that starts after the bundle date has no snapshot") } finally { x("UPDATE app.programme SET active_from = DATE '2026-12-01' WHERE id = $programme") }
        assertNotNull(star())
    }

    @Test
    fun theBundlesOwnCalendarGivesTheScreenshotsEwdUnderEachConvention() {
        // Screenshot 62 is dated 2026-11-11 (EWD 10, WD 26). The bundle's own calendar numbers and star for that date: the default convention (K-13, working days
        // BEFORE the date) gives 9, the through-today convention (the date counts when it is a working day) gives 10: the owner's screenshot is reproduced under
        // THROUGH_TODAY and the one-line switch is SkuProgress.dayCounts (question Q-A).
        val date = LocalDate.parse("2026-11-11")
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b0e1', decode(repeat('ab', 32), 'hex'), 1, $sr)")
        val p = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('superstar', 'MBLS-NOV', 'Match Box Lifting Star (November)', DATE '2026-11-01', DATE '2026-11-30', 'active') RETURNING id")
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($p, $route, DATE '2026-11-01', 20.000, '00000000-0000-4000-8000-00000000b0e1')")
        try {
            val zone = q("SELECT zone_id FROM app.route WHERE id = $route")
            val (snap, star) = fresh.db.jdbi.withHandle<Pair<com.aktcl.aron.backend.sync.SrTargets.Snapshot, JsonObject?>, Exception> { h ->
                val s = com.aktcl.aron.backend.sync.SrTargets.load(h, mapOf(route to zone), date, clock.now(), null).getValue(route)
                s to com.aktcl.aron.backend.sync.SrStar.load(h, mapOf(route to com.aktcl.aron.backend.sync.SrStar.RouteInfo("Route", null, null, s.calendar)), date, date.minusDays(1))[route]
            }
            val c = snap.calendar
            assertEquals(26, c.working_days, "WD of the screenshot"); assertEquals(true, c.today_is_working)
            val cal = com.aktcl.aron.rules.SkuProgress.Calendar(c.elapsed_working_days_before_today, c.remaining_working_days_after_today, c.today_is_working)
            assertEquals(9, com.aktcl.aron.rules.SkuProgress.dayCounts(cal, com.aktcl.aron.rules.SkuProgress.DayConvention.BEFORE_TODAY).elapsed)
            assertEquals(10, com.aktcl.aron.rules.SkuProgress.dayCounts(cal, com.aktcl.aron.rules.SkuProgress.DayConvention.THROUGH_TODAY).elapsed, "EWD of the screenshot")
            assertEquals("9", assertNotNull(star).s("ewd"), "the default convention the star is built with (Q-A, D-TKPI-03)")
            assertEquals(26, c.last_month_working_days, "LMWD of the screenshot (October 2026)")
        } finally {
            x("DELETE FROM app.route_programme_target WHERE programme_id = $p"); x("DELETE FROM app.programme WHERE id = $p")
        }
    }

    @Test
    fun starTruncatedIsPerRouteAndProgrammeOnlyWhenAnExtraActiveProgrammeWasDropped() {
        // route_programme_target is keyed (programme, route, month): the star query returns ONE row per (route, programme), so a route has more than one row exactly when it has
        // more than one active Star programme; the lowest programme id is shown and the route says it dropped the rest. One programme: no flag.
        assertEquals(null, bundle().full.routes.single { it.route_id == route }.star_truncated, "one active programme: not truncated")
        val p2 = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status, rules) VALUES ('superstar', 'MBLS2', 'Second star', DATE '2026-12-01', DATE '2027-01-31', 'active', '{}') RETURNING id")
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($p2, $route, DATE '2027-01-01', 9.000, '00000000-0000-4000-8000-00000000b003')")
        try {
            val r = bundle().full.routes.single { it.route_id == route }
            assertEquals(true, r.star_truncated, "a second active programme of the route was dropped: the route says so")
            assertEquals("MBLS", r.star!!.s("programme_code"), "the lowest programme id is the one shown")
        } finally { x("DELETE FROM app.route_programme_target WHERE programme_id = $p2"); x("DELETE FROM app.programme WHERE id = $p2") }
        assertEquals(null, bundle().full.routes.single { it.route_id == route }.star_truncated)
    }
}
