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
import kotlinx.serialization.json.JsonArray
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
        // contract 1.26.0: the SKU rule of the STD rides with the row so the phone can show "today on this phone" separately (never added into std)
        assertEquals("1000", s.s("unit_divisor"), "the programme's unit divisor"); assertEquals("match", s.s("sku_category"), "the category rule")
        assertEquals(null, s["sku_ids"], "no explicit SKU list: sku_ids is absent and sku_category carries the rule")
    }

    /** The Star row of [route] for [date] (cut at the day before) under a programme whose rules are [rules]; the programme and its target are removed afterwards. */
    private fun starUnder(code: String, rules: String, date: LocalDate, month: String, body: (JsonObject?) -> Unit) {
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b0e2', decode(repeat('ab', 32), 'hex'), 1, $sr) ON CONFLICT DO NOTHING")
        val p = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status, rules) VALUES ('superstar', '$code', 'Star $code', DATE '2026-12-01', DATE '2026-12-31', 'active', '$rules') RETURNING id")
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($p, $route, DATE '$month', 20.000, '00000000-0000-4000-8000-00000000b0e2')")
        try {
            val zone = q("SELECT zone_id FROM app.route WHERE id = $route")
            body(
                fresh.db.jdbi.withHandle<JsonObject?, Exception> { h ->
                    val sn = com.aktcl.aron.backend.sync.SrTargets.load(h, mapOf(route to zone), date, clock.now(), null).getValue(route)
                    com.aktcl.aron.backend.sync.SrStar.load(h, mapOf(route to com.aktcl.aron.backend.sync.SrStar.RouteInfo("Route", null, null, sn.calendar)), date, date.minusDays(1))[route]
                },
            )
        } finally { x("DELETE FROM app.route_programme_target WHERE programme_id = $p"); x("DELETE FROM app.programme WHERE id = $p") }
    }
    private fun ids(star: JsonObject?) = (assertNotNull(star)["sku_ids"] as JsonArray).map { it.jsonPrimitive.content.toLong() }

    @Test
    fun anExplicitSkuListIsCarriedInsteadOfTheCategoryAndTheStdCountsExactlyThoseSkus() {
        val date = LocalDate.parse("2026-12-10")   // through 2026-12-09: the window is 2026-12-01..09
        val match = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.sku WHERE category_code = 'match' ORDER BY id LIMIT 2").mapTo(Long::class.java).list() }
        val cigarette = q("SELECT id FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1")
        val listedMatch = match[0]; val unlistedMatch = match[1]
        // listed: one match SKU and one CIGARETTE (NOT in the default category `match`); unlisted: another match SKU (IN the default category)
        val o = q("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
        val fx = SrMemoFixture({ x(it) }, sr, seqStart = 700)
        val u = { n: Int -> "00000000-0000-4000-8000-0000000f%04d".format(n) }
        fx.memo(u(1), route, o, "2026-12-03", listOf(SrMemoFixture.L(cigarette, 700)))        // listed, not in the category: COUNTS
        fx.memo(u(2), route, o, "2026-12-05", listOf(SrMemoFixture.L(listedMatch, 40)))       // listed and in the category: COUNTS
        fx.memo(u(3), route, o, "2026-12-04", listOf(SrMemoFixture.L(unlistedMatch, 9000)))   // unlisted, in the default category: does NOT count
        fx.memo(u(4), route, o, "2026-12-10", listOf(SrMemoFixture.L(cigarette, 5000)))       // the bundle date: never counts
        try {
            starUnder("MBLS-IDS", "{\"sku_ids\":\"$cigarette,$listedMatch\",\"unit_divisor\":1}", date, "2026-12-01") { star ->
                assertEquals(listOf(cigarette, listedMatch).sorted(), ids(star), "the explicit list is carried, ascending by id"); assertEquals(null, star!!["sku_category"], "and the category is absent")
                assertEquals("1", star.s("unit_divisor"))
                assertEquals("740.000", star.s("std"), "STD = 700 (listed cigarette) + 40 (listed match); the 9000 of the unlisted match SKU of the default category is out, so a category rule would give 9040")
            }
        } finally { listOf(1 to "2026-12-03", 2 to "2026-12-05", 3 to "2026-12-04", 4 to "2026-12-10").forEach { (n, d) -> fx.void(u(n), d) } }   // synced rows are tombstoned: last month's figure of the other tests stays as it was
    }

    /** In-window (2026-12-02) one-line sale memos of the route, one per (sku, qty); voided afterwards so the other tests' last-month figure stays as it was. [base] keeps the uuids apart. */
    private fun withSales(base: Int, vararg sales: Pair<Long, Int>, body: () -> Unit) {
        val o = q("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
        val fx = SrMemoFixture({ x(it) }, sr, seqStart = 800 + base)
        val us = sales.indices.map { "00000000-0000-4000-8000-0000000e%04d".format(base + it) }
        sales.forEachIndexed { i, (sku, qty) -> fx.memo(us[i], route, o, "2026-12-02", listOf(SrMemoFixture.L(sku, qty))) }
        try { body() } finally { us.forEach { fx.void(it, "2026-12-02") } }
    }

    @Test
    fun theSkuListIsCleanedBeforeTheCapAndCarriedAscending() {
        val real = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.sku ORDER BY id DESC LIMIT 3").mapTo(Long::class.java).list() }   // unordered on purpose
        val rule = " ,0,-5,${real[0]},abc,${real[2]},${real[0]},999999999, ${real[1]},${real[2]}"
        val other = q("SELECT min(id) FROM app.sku")   // a SKU that is NOT on the list (the list holds the three highest ids)
        val match = q("SELECT id FROM app.sku WHERE category_code = 'match' ORDER BY id LIMIT 1"); val cigarette = q("SELECT id FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1")
        withSales(10, real[0] to 11, real[2] to 22, other to 500) {
            starUnder("MBLS-CLEAN", "{\"sku_ids\":\"$rule\"}", LocalDate.parse("2026-12-10"), "2026-12-01") { star ->
                assertEquals(real.sorted(), ids(star), "distinct, existing in the catalogue, >= 1, ascending by id; junk and unknown ids dropped"); assertEquals(null, star!!["sku_ids_truncated"])
                assertEquals("33.000", star.s("std"), "the STD counts exactly the cleaned list: 11 + 22, not the 500 of the SKU that is off the list")
            }
        }
        // a list with no usable id falls back to the category rule (which then rides) instead of an empty explicit list, and the STD follows the category
        withSales(20, match to 40, cigarette to 900) {
            starUnder("MBLS-NOIDS", "{\"sku_ids\":\"0,abc,999999999\"}", LocalDate.parse("2026-12-10"), "2026-12-01") { star ->
                assertEquals(null, assertNotNull(star)["sku_ids"]); assertEquals("match", star.s("sku_category"))
                assertEquals("40.000", star.s("std"), "the category rule counts the match sale only, not the cigarette sale")
            }
        }
    }

    @Test
    fun theCapCountsTheCleanedListAt500And501() {
        x("INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, pack_type, entry_unit_default, report_unit, report_factor, sort, status) SELECT 'TB'||g, variant_id, 'bidi', 'Bulk '||g, 'TB'||g, base_unit, base_per_pack, pack_type, entry_unit_default, report_unit, report_factor, sort, 'active' FROM app.sku, generate_series(1, 501) g WHERE id = (SELECT min(id) FROM app.sku WHERE category_code = 'cigarette')")
        try {
            val bulk = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.sku WHERE code LIKE 'TB%' ORDER BY id").mapTo(Long::class.java).list() }
            assertEquals(501, bulk.size)
            val junk = "0,999999999,${bulk[0]},${bulk[1]}"   // duplicates and unknown ids must not count towards the cap
            // 500 distinct existing ids (plus junk and duplicates that clean away): all carried, not truncated
            starUnder("MBLS-500", "{\"sku_ids\":\"${bulk.take(500).reversed().joinToString(",")},$junk\"}", LocalDate.parse("2026-12-10"), "2026-12-01") { star ->
                assertEquals(bulk.take(500), ids(star), "500 cleaned ids, ascending"); assertEquals(null, star!!["sku_ids_truncated"], "500 is not over the cap")
            }
            // 501 distinct existing ids: the first 500 ascending are carried and the flag says so
            starUnder("MBLS-501", "{\"sku_ids\":\"${bulk.reversed().joinToString(",")}\"}", LocalDate.parse("2026-12-10"), "2026-12-01") { star ->
                assertEquals(bulk.take(500), ids(star), "the first 500 of the cleaned list, ascending"); assertEquals(true, star!!["sku_ids_truncated"]!!.jsonPrimitive.content.toBoolean())
            }
            // 500 real ids plus 100 unknown ones: the cap counts the cleaned list, so no flag
            starUnder("MBLS-500X", "{\"sku_ids\":\"${bulk.take(500).joinToString(",")},${(900_000_000L until 900_000_100L).joinToString(",")}\"}", LocalDate.parse("2026-12-10"), "2026-12-01") { star ->
                assertEquals(500, ids(star).size); assertEquals(null, star!!["sku_ids_truncated"], "unknown ids never count towards the cap")
            }
        } finally { x("DELETE FROM app.sku WHERE code LIKE 'TB%'") }
    }

    @Test
    fun aCategoryOutsideTheContractEnumIsOmittedNeverSentAndAValidOneRides() {
        val match = q("SELECT id FROM app.sku WHERE category_code = 'match' ORDER BY id LIMIT 1"); val cigarette = q("SELECT id FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1")
        withSales(30, match to 40, cigarette to 900) {   // sales exist in the window, so a std of 0 proves the rule counts nothing
            starUnder("MBLS-BADCAT", "{\"sku_category\":\"cigar\",\"unit_divisor\":1}", LocalDate.parse("2026-12-10"), "2026-12-01") { star ->
                assertNotNull(star); assertEquals(null, star["sku_category"], "an unknown category is omitted, never sent out of the enum"); assertEquals(null, star["sku_ids"])
                assertEquals("0.000", star.s("std"), "and the STD counts no SKU although 940 were sold in the window")
            }
            // with an explicit list the off-enum category is ignored on the wire and the STD DOES count the listed SKUs
            starUnder("MBLS-BADCAT-IDS", "{\"sku_category\":\"cigar\",\"sku_ids\":\"$cigarette\"}", LocalDate.parse("2026-12-10"), "2026-12-01") { star ->
                assertEquals(listOf(cigarette), ids(star)); assertEquals(null, star!!["sku_category"]); assertEquals("900.000", star.s("std"))
            }
        }
        for (c in listOf("cigarette", "bidi", "match", "lighter")) starUnder("MBLS-C-$c", "{\"sku_category\":\"$c\"}", LocalDate.parse("2026-12-10"), "2026-12-01") { star -> assertEquals(c, assertNotNull(star).s("sku_category")) }
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
