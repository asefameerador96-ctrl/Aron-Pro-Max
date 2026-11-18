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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B1 of the lead review (D-TKPI-10): the SR programme sections must not make a bundle a new version when nothing they show changed. The aggregation
 * worker rewrites `dw.summary_freshness` about every 5 seconds even when idle; `as_of` of these sections therefore comes from the route's own aggregate
 * rows and moves only when the projector rewrote one of them. Two builds with idle worker passes between give the SAME version and a 304 (null delta); a
 * rewritten aggregate row (a new memo) gives another version; a route with no programme data carries no `calendar` and no `kpi`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SrBundleStabilityTest {
    private lateinit var fresh: FreshDb
    private lateinit var service: BundleService
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-03")
    private var sr = 0L
    private var route = 0L
    private var sku = 0L

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }
    private val principal get() = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", null, null, "sr", emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600))
    private fun build() = service.bundle(principal, day, "1.0.0+1")

    /** What an idle aggregation worker pass does: the REAL `DashboardSummaries.touchFreshness` (the worker's own code) rewrites the freshness rows to now() for every table. */
    private fun idleWorkerPass() = fresh.db.jdbi.useHandle<Exception> { h -> com.aktcl.aron.backend.analytics.DashboardSummaries.touchFreshness(h, Instant.now()) }

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        sr = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        route = build().full.routes.first().route_id
        sku = q("SELECT id FROM app.sku ORDER BY id LIMIT 1")
    }

    @AfterAll fun tearDown() = fresh.close()

    @Test @Order(1)
    fun aRouteWithNoProgrammeDataCarriesNoCalendarAndNoKpiAndIdleWorkerPassesChangeNothing() {
        idleWorkerPass()
        val a = build()
        val r = a.full.routes.single { it.route_id == route }
        assertNull(r.calendar, "no targets, no KPI, no star, no campaign: no calendar on the wire")
        assertNull(r.kpi); assertNull(r.star); assertTrue(r.targets.isEmpty() && r.achievement_mtd.isEmpty())
        Thread.sleep(30); idleWorkerPass(); Thread.sleep(30); idleWorkerPass()
        val b = build()
        assertEquals(a.version, b.version, "idle worker passes between two builds do not change the bundle version")
        assertNull(service.delta(principal, a.bundle.meta.cursor, day), "an unchanged bundle is a 304 (null delta), not a resend of the route")
    }

    @Test @Order(2)
    fun idleWorkerPassesNeverMoveTheVersionOfARouteWithTargetsAndFiguresButANewMemoDoes() {
        val batch = "00000000-0000-4000-8000-00000000c001"
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('$batch', decode(repeat('cd', 32), 'hex'), 1, $sr)")
        x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) VALUES ($route, $sku, DATE '2027-01-01', 500, '$batch')")
        x("INSERT INTO dw.agg_daily_route_sku (business_date, route_id, sku_id, sold_qty_base, memo_count, updated_at) VALUES (DATE '2027-01-02', $route, $sku, 100, 2, TIMESTAMPTZ '2027-01-03T01:00:00Z')")
        idleWorkerPass()
        val a = build()
        val r = a.full.routes.single { it.route_id == route }
        assertNotNull(r.calendar, "a route with targets carries the calendar")
        assertEquals("2027-01-03T01:00:00Z", Instant.parse(r.calendar!!.as_of).toString(), "as_of is the newest rewrite of the route's own aggregate rows")
        repeat(3) { Thread.sleep(30); idleWorkerPass() }
        val b = build()
        assertEquals(a.version, b.version, "figures unchanged: same version although the worker kept rewriting its freshness row")
        assertNull(service.delta(principal, a.bundle.meta.cursor, day))
        // a new memo of the day before: the projector rewrites the route-day row, so the figure and its as_of move and the version changes
        x("UPDATE dw.agg_daily_route_sku SET sold_qty_base = 130, memo_count = 3, updated_at = TIMESTAMPTZ '2027-01-03T01:30:00Z' WHERE route_id = $route AND business_date = DATE '2027-01-02'")
        val c = build()
        assertNotEquals(a.version, c.version, "a rewritten aggregate row (a new memo) is a new bundle version")
        assertNotNull(service.delta(principal, a.bundle.meta.cursor, day))
    }

    @Test @Order(3)
    fun idlePassesChangeNothingForAKpiStarAndCampaignRouteEither() {
        val batch = "00000000-0000-4000-8000-00000000c002"
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('$batch', decode(repeat('ce', 32), 'hex'), 1, $sr) ON CONFLICT DO NOTHING")
        x("INSERT INTO app.route_kpi_month (route_id, month, kpi_alloc_mtk, cpr_target_bp, cpr_alloc_mtk, batch_uuid) VALUES ($route, DATE '2027-01-01', 5700000, 5000, 600000, '$batch') ON CONFLICT DO NOTHING")
        val star = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('superstar', 'MBLS-S', 'Star', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) VALUES ($star, $route, DATE '2027-01-01', 20.000, '$batch')")
        val camp = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSL-S', 'Campaign', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        val outlet = build().full.routes.single { it.route_id == route }.outlets.first().outlet_id
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) VALUES ($camp, DATE '2027-01-01', $outlet, 100, 5, '$batch')")
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) VALUES ($camp, DATE '2027-01-01', $outlet, $sku, 100, '$batch')")
        idleWorkerPass()
        val a = build(); val r = a.full.routes.single { it.route_id == route }
        assertNotNull(r.kpi); assertNotNull(r.star); assertTrue(r.campaigns.orEmpty().isNotEmpty(), "kpi, star and campaign are all on the wire")
        repeat(3) { Thread.sleep(30); idleWorkerPass() }
        assertEquals(a.version, build().version, "idle passes with the real touchFreshness: same version")
        assertNull(service.delta(principal, a.bundle.meta.cursor, day))
    }

    private fun asOf(): Instant? = fresh.db.jdbi.withHandle<Instant?, Exception> { h -> com.aktcl.aron.backend.sync.SrTargets.asOfByRoute(h, listOf(route), day)[route] }
    private fun row(d: String, at: String) = x("INSERT INTO dw.agg_daily_route_sku (business_date, route_id, sku_id, sold_qty_base, memo_count, updated_at) VALUES (DATE '$d', $route, $sku, 1, 1, TIMESTAMPTZ '$at') ON CONFLICT (business_date, route_id, sku_id) DO UPDATE SET updated_at = EXCLUDED.updated_at")

    @Test @Order(4)
    fun asOfNeverPassesAMemoOfAnotherDayThatIsStillWaitingToBeAggregated() {
        // The reviewer's scenario: an SR offline for two days syncs both at 09:00:00; day D-2 is rebuilt at 09:00:04, day D-1 is not rebuilt yet (pending), or its
        // rebuild fails (backed off), or it is dead. A bundle pulled in between must say as_of < 09:00:00, so the phone keeps counting the D-1 memos.
        x("DELETE FROM dw.agg_daily_route_sku WHERE route_id = $route"); x("DELETE FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $route")
        row("2027-01-01", "2027-01-03T09:00:04Z")                         // D-2 rebuilt at 09:00:04
        assertEquals(Instant.parse("2027-01-03T09:00:04Z"), asOf(), "nothing pending: no clamp, as_of is the newest rewrite")
        // D-1 pending, first dirtied at 09:00:00 (the memos were accepted at 09:00:00)
        x("INSERT INTO app.dirty_key (kind, subject_id, business_date, first_dirtied_at, last_dirtied_at, reason) VALUES ('route_day_agg', $route, DATE '2027-01-02', TIMESTAMPTZ '2027-01-03T09:00:00Z', TIMESTAMPTZ '2027-01-03T09:00:01Z', 'memo')")
        val pending = asOf()!!
        assertTrue(pending.isBefore(Instant.parse("2027-01-03T09:00:00Z")), "pending key: as_of $pending is before the memos' acceptance 09:00:00")
        // the same key backed off after a failing rebuild, then dead: still clamps (a dead key never lets as_of run past it)
        x("UPDATE app.dirty_key SET not_before = TIMESTAMPTZ '2027-01-03T09:15:00Z' WHERE kind = 'route_day_agg' AND subject_id = $route")
        assertEquals(pending, asOf(), "backed-off key clamps the same")
        x("UPDATE app.dirty_key SET dead_at = TIMESTAMPTZ '2027-01-03T09:30:00Z' WHERE kind = 'route_day_agg' AND subject_id = $route")
        assertEquals(pending, asOf(), "dead key clamps the same")
        // a LATER rewrite of another day must not move it past the waiting memo
        row("2027-01-01", "2027-01-03T10:00:00Z")
        assertEquals(pending, asOf(), "a later rebuild of D-2 does not move as_of past D-1's waiting memos")
        // keys outside the window (today, or another route) never clamp
        x("DELETE FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $route")
        x("INSERT INTO app.dirty_key (kind, subject_id, business_date, first_dirtied_at, last_dirtied_at, reason) VALUES ('route_day_agg', $route, DATE '2027-01-03', TIMESTAMPTZ '2027-01-03T01:00:00Z', TIMESTAMPTZ '2027-01-03T01:00:00Z', 'memo')")
        assertEquals(Instant.parse("2027-01-03T10:00:00Z"), asOf(), "today's key is outside the window (today is never in a server figure)")
        x("DELETE FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $route")
    }
}
