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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B1 and B2 of the T1 review (D-TKPI-18). B1: a figure is COMPLETE through `through_date`, which must be a day that has ENDED when the bundle is built: a bundle built AHEAD (the next working day's
 * pre-fetch, taken while today is still open) cuts at today - 1, not at bundle date - 1 (`SrMemoFigures.through`), and every family (achievement, calendar, KPI, Star, campaigns) reports the SAME
 * `through_date`. B2: ONE read-only REPEATABLE READ transaction around every memo-derived read of a bundle: a memo that commits between the route figures and the outlet stats is in both or in neither.
 * The clock is movable (`nowAt`), memos are real rows (`SrMemoFixture`), the service is the real `BundleService`.
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SrBundleThroughAndSnapshotTest {
    private lateinit var fresh: FreshDb
    private lateinit var service: BundleService
    @Volatile private var nowAt: Instant = Instant.parse("2027-01-03T02:00:00Z")   // Sunday 2027-01-03 08:00 Dhaka
    private val clock = AronClock { nowAt }
    private var sr = 0L; private var route = 0L; private var outlet = 0L; private var skuC = 0L
    private lateinit var fx: SrMemoFixture

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }
    private val principal get() = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c -> seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } } }
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val r = service.bundle(principal, LocalDate.parse("2027-01-03"), "1.0.0+1").full.routes.first()
        route = r.route_id; outlet = r.outlets.first().outlet_id
        skuC = q("SELECT id FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1")
        fx = SrMemoFixture({ x(it) }, sr)
        // a target, a Star programme and a campaign for the route and the outlet in January AND February, so every family is on the wire in both months
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000c201', decode(repeat('cd', 32), 'hex'), 1, $sr)")
        val b = "'00000000-0000-4000-8000-00000000c201'"
        val star = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('superstar', 'MBLS-T', 'Star', DATE '2026-12-01', DATE '2027-03-31', 'active') RETURNING id")
        val camp = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSL-T', 'Campaign', DATE '2026-12-01', DATE '2027-03-31', 'active') RETURNING id")
        for (m in listOf("2027-01-01", "2027-02-01")) {
            x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $skuC, DATE '$m', 5000, $b)")
            x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($star, $route, DATE '$m', 20.000, $b)")
            x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($camp, DATE '$m', $outlet, 1000, 50, $b)")
            x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($camp, DATE '$m', $outlet, $skuC, 1000, $b)")
        }
    }

    @AfterEach fun tearDown() { BundleService.betweenReads = null; fresh.close() }

    /** The through_date every family of the route reports, the first SKU's achieved quantity and the day counts. */
    private class Seen(val through: Set<String>, val achieved: Long, val elapsed: Int, val today: Boolean, val remaining: Int, val workingDays: Int)
    private fun seen(date: String): Seen {
        val r = service.bundle(principal, LocalDate.parse(date), "1.0.0+1").full.routes.single { it.route_id == route }
        // every family must be PRESENT before the through_date sets are compared: an absent family would make the comparison vacuous
        assertTrue(r.calendar != null && r.kpi != null && r.star != null && r.campaigns.orEmpty().isNotEmpty(), "calendar, kpi, star and campaigns are all present: ${listOf(r.calendar != null, r.kpi != null, r.star != null, r.campaigns.orEmpty().isNotEmpty())}")
        val t = HashSet<String>()
        r.calendar?.let { t += it.through_date }
        r.achievement_mtd.forEach { t += it.jsonObject["through_date"]!!.jsonPrimitive.content }
        r.kpi?.jsonObject?.get("through_date")?.let { t += it.jsonPrimitive.content }
        r.star?.jsonObject?.get("through_date")?.let { t += it.jsonPrimitive.content }
        r.campaigns.orEmpty().forEach { t += it.jsonObject["through_date"]!!.jsonPrimitive.content }
        val a = r.achievement_mtd.map { it.jsonObject }.singleOrNull { it["product_id"]!!.jsonPrimitive.content == skuC.toString() }?.get("achieved_qty_base")?.jsonPrimitive?.content?.toLong() ?: 0L
        val c = r.calendar!!
        return Seen(t, a, c.elapsed_working_days_before_today, c.today_is_working, c.remaining_working_days_after_today, c.working_days)
    }

    /** The hand-worked literals below assume the seed's calendar: Friday is the only weekend day and January and February 2027 have no holiday or make-up day on any level. Pinned here, so a changed seed fails THIS, not a count. */
    private fun assertSeedCalendarAssumption() {
        val weekend = DbServerConfig(fresh.db, RegistryDefaults(), clock).value("cfg.calendar.weekend_days").toString()
        assertEquals("[5]", weekend, "the global weekend is Friday only")
        assertEquals(0L, q("SELECT count(*) FROM app.cfg_value WHERE key = 'cfg.calendar.weekend_days' AND scope_type IN ('wing', 'division') AND effective_to IS NULL"), "no wing or division weekend override")
        assertEquals(0L, q("SELECT count(*) FROM app.calendar_holiday WHERE revoked_at IS NULL AND date BETWEEN DATE '2027-01-01' AND DATE '2027-02-28'"), "no holiday or make-up day in January or February 2027 on any level")
    }

    /** The three day counts pinned to LITERAL values (hand-worked: Friday weekend, no holiday in January or February 2027 in the seed), and their sum to the month's working days. */
    private fun assertCounts(s: Seen, before: Int, today: Boolean, after: Int, what: String) {
        assertSeedCalendarAssumption()
        assertEquals(listOf<Any>(before, today, after), listOf(s.elapsed, s.today, s.remaining), "elapsed / today_is_working / remaining: $what")
        assertEquals(s.workingDays, s.elapsed + (if (s.today) 1 else 0) + s.remaining, "before + today + after = the month's working days: $what")
    }

    @Test
    fun aBundleBuiltAheadOnTheSameDayCutsAtTheLastClosedDayAndEveryFamilyReportsIt() {
        fx.memo("00000000-0000-4000-8000-0000000b0001", route, outlet, "2027-01-02", listOf(SrMemoFixture.L(skuC, 5)))   // a closed day
        fx.memo("00000000-0000-4000-8000-0000000b0002", route, outlet, "2027-01-03", listOf(SrMemoFixture.L(skuC, 7)))   // today, still open, accepted after the pre-fetch below
        val ahead = seen("2027-01-04")   // today is Sunday 2027-01-03 08:00: the bundle for Monday is built AHEAD
        assertEquals(setOf("2027-01-02"), ahead.through, "built ahead on Sunday: complete through Saturday (the last CLOSED day), not through Sunday which is still open; every family says so")
        assertEquals(5L, ahead.achieved, "today's memo is in no figure")
        assertCounts(ahead, 1, true, 24, "built Sun 3 Jan for Mon 4 Jan: the anchor is Sunday 3 Jan (Sat 2 Jan elapsed; Jan 1 is a Friday)")
        assertEquals(setOf("2027-01-02"), seen("2027-01-03").through)
        nowAt = Instant.parse("2027-01-04T02:00:00Z")   // Monday 08:00: Sunday has ended
        val next = seen("2027-01-04")
        assertEquals(setOf("2027-01-03"), next.through, "built on Monday for Monday: complete through Sunday")
        assertEquals(12L, next.achieved, "the memo of Sunday is in the figure once Sunday has closed")
        assertCounts(next, 2, true, 23, "built Mon 4 Jan for Mon 4 Jan: the anchor is Monday 4 Jan (Sat 2 and Sun 3 Jan elapsed)")
    }

    @Test
    fun aBundleBuiltOnThursdayForSaturdayCutsAtWednesdayNotAtFridayWhichHasNotHappened() {
        nowAt = Instant.parse("2027-01-07T02:00:00Z")   // Thursday 2027-01-07 08:00 Dhaka; Friday is the weekend day, the next working day is Saturday 2027-01-09
        fx.memo("00000000-0000-4000-8000-0000000b0011", route, outlet, "2027-01-06", listOf(SrMemoFixture.L(skuC, 5)))
        fx.memo("00000000-0000-4000-8000-0000000b0012", route, outlet, "2027-01-07", listOf(SrMemoFixture.L(skuC, 9)))
        val s = seen("2027-01-09")
        assertEquals(setOf("2027-01-06"), s.through, "Thursday is still open: the figure is complete through Wednesday, not through Friday")
        assertEquals(5L, s.achieved)
        assertCounts(s, 5, true, 20, "built Thu 7 Jan for Sat 9 Jan: through is Wednesday 6 Jan, the anchor is Thursday 7 Jan (Sat 2, Sun 3, Mon 4, Tue 5, Wed 6 elapsed)")
    }

    @Test
    fun aBundleBuiltOnAFridayForSaturdayAnchorsOnTheNonWorkingFridayNotOnTheWorkingSaturday() {
        nowAt = Instant.parse("2027-01-08T02:00:00Z")   // Friday 2027-01-08 08:00 Dhaka (the weekend day); the bundle for Saturday 9 Jan is built ahead
        fx.memo("00000000-0000-4000-8000-0000000b0051", route, outlet, "2027-01-07", listOf(SrMemoFixture.L(skuC, 5)))
        val s = seen("2027-01-09")
        assertEquals(setOf("2027-01-07"), s.through, "through is Thursday 7 Jan, the last closed day")
        // the anchor is Friday 8 Jan (not a working day): had today_is_working been read for the bundle date (Saturday, a working day) it would say true
        assertCounts(s, 6, false, 20, "built Fri 8 Jan for Sat 9 Jan: Sat 2, Sun 3, Mon 4, Tue 5, Wed 6 and Thu 7 elapsed; the anchor Friday is not working; Sat 9 .. Sat 30 minus Fridays 15, 22, 29 remain")
    }

    @Test
    fun aBundleBuiltAheadOnTheLastDayOfTheMonthForTheFirstHasAnEmptyWindowAndSaysSo() {
        nowAt = Instant.parse("2027-01-31T02:00:00Z")   // Sunday 2027-01-31 08:00: the bundle for Monday 2027-02-01 is built ahead
        fx.memo("00000000-0000-4000-8000-0000000b0021", route, outlet, "2027-01-30", listOf(SrMemoFixture.L(skuC, 5)))
        fx.memo("00000000-0000-4000-8000-0000000b0022", route, outlet, "2027-01-31", listOf(SrMemoFixture.L(skuC, 9)))
        val s0 = seen("2027-02-01")   // asserts calendar, kpi, star and campaigns are all present before comparing
        assertEquals(setOf("2027-01-30"), s0.through, "calendar, achievement, kpi, star and campaigns all report through 30 January")
        assertCounts(s0, 0, true, 23, "built Sun 31 Jan for Mon 1 Feb: the anchor is Monday 1 Feb, not Sunday 31 Jan")
        val r = service.bundle(principal, LocalDate.parse("2027-02-01"), "1.0.0+1").full.routes.single { it.route_id == route }
        val star = r.star!!.jsonObject
        assertEquals(0, star["ewd"]!!.jsonPrimitive.content.toInt(), "the empty window has no elapsed working day: ewd is 0")
        assertEquals("kotlinx.serialization.json.JsonNull", star["pads"]!!.javaClass.name, "pads is null on the empty window (never zero)")
        assertEquals("kotlinx.serialization.json.JsonNull", star["growth_bp"]!!.javaClass.name, "growth_bp is null on the empty window")
        assertEquals("0.000", star["lmads"]!!.jsonPrimitive.content, "no memo in January and 25 closed working days: lmads is 0.000, a number, not null (lmads is null only when lmwd is 0)")
        assertEquals("2027-02", r.calendar?.month, "the bundle's month is February")
        assertEquals("2027-01-30", r.calendar?.through_date, "complete through the last closed day, which is in January: the window of February is empty")
        assertTrue(r.achievement_mtd.none { it.jsonObject["achieved_qty_base"]!!.jsonPrimitive.content.toLong() > 0 }, "no January memo is in a February figure")
        val c = r.calendar!!
        assertEquals(0, c.elapsed_working_days_before_today, "the anchor is Monday 1 February, the first day of the month: nothing has elapsed in February")
        assertEquals(c.working_days, c.elapsed_working_days_before_today + (if (c.today_is_working) 1 else 0) + c.remaining_working_days_after_today, "before + today + after = the working days of February")
        // the same bundle date built ON the day gives the same counts (the prefetch and the day-of build agree)
        nowAt = Instant.parse("2027-02-01T02:00:00Z")
        val onTheDay = service.bundle(principal, LocalDate.parse("2027-02-01"), "1.0.0+1").full.routes.single { it.route_id == route }.calendar!!
        assertEquals(listOf(c.elapsed_working_days_before_today, c.today_is_working, c.remaining_working_days_after_today), listOf(onTheDay.elapsed_working_days_before_today, onTheDay.today_is_working, onTheDay.remaining_working_days_after_today), "same bundle date, same counts, prefetched or built on the day")
    }

    @Test
    fun aMemoCommittedBetweenTheRouteFiguresAndTheOutletStatsIsInBothOrInNeither() {
        fx.memo("00000000-0000-4000-8000-0000000b0031", route, outlet, "2027-01-02", listOf(SrMemoFixture.L(skuC, 5)))
        /** ONE bundle build: the first SKU's achieved quantity and the outlet stats' previous-day quantity of the same SKU, read from the same bundle. */
        fun build(date: String): Pair<Long, Long?> {
            val bundle = service.bundle(principal, LocalDate.parse(date), "1.0.0+1").full
            val r = bundle.routes.single { it.route_id == route }
            val a = r.achievement_mtd.map { it.jsonObject }.singleOrNull { it["product_id"]!!.jsonPrimitive.content == skuC.toString() }?.get("achieved_qty_base")?.jsonPrimitive?.content?.toLong() ?: 0L
            val st = bundle.outlet_sku_stats.filter { it.outlet_id == outlet }.flatMap { it.items }.firstOrNull { it.size >= 4 && it[0] == skuC }?.get(1)
            return a to st
        }
        val seenInHook = java.util.concurrent.CopyOnWriteArrayList<String>()
        var injected = false
        BundleService.betweenReads = { h ->
            seenInHook += h.createQuery("SHOW transaction_isolation").mapTo(String::class.java).one() + "/" + h.createQuery("SHOW transaction_read_only").mapTo(String::class.java).one()
            if (!injected) {
                injected = true
                var failure: Throwable? = null
                // committed on ANOTHER connection (another thread) after the route figures were read and before the outlet stats are
                kotlin.concurrent.thread { try { fx.memo("00000000-0000-4000-8000-0000000b0032", route, outlet, "2027-01-02", listOf(SrMemoFixture.L(skuC, 7))) } catch (t: Throwable) { failure = t } }.join()
                failure?.let { throw it }
            }
        }
        val (firstFigure, firstStats) = build("2027-01-03").also { BundleService.betweenReads = null }
        assertTrue(seenInHook.isNotEmpty() && seenInHook.all { it == "repeatable read/on" }, "the whole build is one read-only REPEATABLE READ transaction: $seenInHook")
        // the first build was started before the commit: its figure and its stats must agree (both without the 7 sticks, or both with them)
        val (secondFigure, secondStats) = build("2027-01-03")
        assertEquals(12L, secondFigure, "a later build sees the memo in the figure"); assertEquals(12L, secondStats, "and in the stats")
        assertEquals(firstFigure, firstStats, "figure and stats of the same build agree: one snapshot, not two")
        assertEquals(5L, firstFigure, "the build that started before the commit shows neither the 7 sticks in the figure nor in the stats")
    }

    /** C3: nothing that reads through a PROCESS-WIDE CACHE (the reach resolver, the server config) may run while the bundle's snapshot is open. */
    @Test
    fun noCacheLoaderRunsWhileTheBundleSnapshotIsOpen() {
        val violations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val realConfig = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val config = object : com.aktcl.aron.backend.platform.ServerConfig {
            override fun value(key: String): kotlinx.serialization.json.JsonElement { if (BundleService.inSnapshot.get()) violations += "config.value($key)"; return realConfig.value(key) }
            override fun configVersion(): Long { if (BundleService.inSnapshot.get()) violations += "config.configVersion()"; return realConfig.configVersion() }
        }
        val geo = GeoRepository(fresh.db, clock)
        val realReach = SqlReachResolver(fresh.db, geo, clock)
        val reach = com.aktcl.aron.backend.platform.ReachResolver { u, r, v, d -> if (BundleService.inSnapshot.get()) violations += "reach.reach($u)"; realReach.reach(u, r, v, d) }
        val realPlanner = SqlRoutePlanner(fresh.db, geo, realConfig)
        val planner = com.aktcl.aron.backend.platform.RoutePlanner { u, d -> if (BundleService.inSnapshot.get()) violations += "planner.routesFor($u)"; realPlanner.routesFor(u, d) }
        val guarded = BundleService(fresh.db, config, planner, clock, reach = reach)
        guarded.bundle(principal, LocalDate.parse("2027-01-03"), "1.0.0+1")
        guarded.bundle(principal, LocalDate.parse("2027-01-04"), "1.0.0+1")   // a bundle built ahead too
        assertTrue(violations.isEmpty(), "no cache-backed loader ran inside the snapshot: $violations")
        assertTrue(!BundleService.inSnapshot.get(), "the flag is cleared after a build")
    }

    /** D1: Star's last month is cut at the last CLOSED day too (a bundle built ahead on the last day of a month), and `lmwd` counts the same days. */
    @Test
    fun starLastMonthIsCutAtTheLastClosedDayAndLmwdCountsTheSameDays() {
        val skuM = q("SELECT id FROM app.sku WHERE category_code = 'match' ORDER BY id LIMIT 1")
        nowAt = Instant.parse("2027-01-31T02:00:00Z")   // Sunday 31 Jan 08:00: the bundle for Mon 1 Feb is built ahead
        fx.memo("00000000-0000-4000-8000-0000000b0041", route, outlet, "2027-01-30", listOf(SrMemoFixture.L(skuM, 10)))   // closed
        fx.memo("00000000-0000-4000-8000-0000000b0042", route, outlet, "2027-01-31", listOf(SrMemoFixture.L(skuM, 40)))   // today, still open
        fun star(): JsonObject = service.bundle(principal, LocalDate.parse("2027-02-01"), "1.0.0+1").full.routes.single { it.route_id == route }.star!!.jsonObject
        val ahead = star()
        assertEquals("10.000", ahead["lms"]!!.jsonPrimitive.content, "last month's sold quantity excludes the open 31 Jan")
        assertEquals(25, ahead["lmwd"]!!.jsonPrimitive.content.toInt(), "lmwd counts the closed days of January only (26 working days, minus Sunday 31 Jan)")
        nowAt = Instant.parse("2027-02-01T02:00:00Z")   // Monday 1 Feb: January has closed
        val onTheDay = star()
        assertEquals("50.000", onTheDay["lms"]!!.jsonPrimitive.content, "built on the day: all of January")
        assertEquals(26, onTheDay["lmwd"]!!.jsonPrimitive.content.toInt(), "lmwd is the whole of January")
    }
}
