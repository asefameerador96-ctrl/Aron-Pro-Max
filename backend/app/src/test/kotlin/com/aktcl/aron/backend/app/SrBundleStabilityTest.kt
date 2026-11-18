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
 * B1 of the lead review, redone for the no-clock design (D-TKPI-12): the SR programme sections must not make a bundle a new version when nothing they show changed.
 * The figures are read straight from the accepted memos and `as_of` is the newest `received_at` among the memos read (informational), so the aggregation worker (which
 * rewrites `dw.summary_freshness` about every 5 seconds even when idle, and rebuilds aggregates) can never move a version. Two builds with idle worker passes and even
 * a real rebuild of the day between give the SAME version and a 304 (null delta); a new accepted memo gives another version; a route with no programme data carries no
 * `calendar` and no `kpi`.
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

    /** What an idle aggregation worker pass does: the REAL `AggregationWorker` pass (its own `touchFreshness`, wall clock) rewrites the freshness rows to now() for every table. */
    private fun idleWorkerPass() { com.aktcl.aron.backend.analytics.AggregationWorker(fresh.db, AronClock { Instant.now() }).runUntilIdle() }

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
        val outlet = q("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
        val fx = SrMemoFixture({ x(it) }, sr)
        fx.memo("00000000-0000-4000-8000-0000000a0001", route, outlet, "2027-01-02", listOf(SrMemoFixture.L(sku, 100)), "TIMESTAMPTZ '2027-01-03T01:00:00Z'", emit = true)
        idleWorkerPass()
        val a = build()
        val r = a.full.routes.single { it.route_id == route }
        assertNotNull(r.calendar, "a route with targets carries the calendar")
        assertEquals("2027-01-03T01:00:00Z", Instant.parse(r.calendar!!.as_of).toString(), "as_of is the newest received_at among the memos read")
        repeat(3) { Thread.sleep(30); idleWorkerPass() }
        val b = build()
        assertEquals(a.version, b.version, "figures unchanged: same version although the worker kept rewriting its freshness row and projected the memo")
        assertNull(service.delta(principal, a.bundle.meta.cursor, day))
        // a real rebuild of the memo's day (what a late geo fix or a check-in does): the aggregate is rewritten, the figure is not read from it, the version stays
        com.aktcl.aron.backend.analytics.AggregationWorker(fresh.db, AronClock { Instant.now() }).also { w -> w.requestRebuild(LocalDate.parse("2027-01-02")); w.runUntilIdle() }
        assertEquals(a.version, build().version, "a rebuild of the aggregate changes nothing the bundle shows")
        assertNull(service.delta(principal, a.bundle.meta.cursor, day))
        // a new accepted memo of the day before: the figure and as_of move and the version changes
        fx.memo("00000000-0000-4000-8000-0000000a0002", route, outlet, "2027-01-02", listOf(SrMemoFixture.L(sku, 30)), "TIMESTAMPTZ '2027-01-03T01:30:00Z'", emit = true)
        val c = build()
        assertNotEquals(a.version, c.version, "a new accepted memo is a new bundle version")
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

    @Test @Order(4)
    fun anEditWithIdenticalLinesAndAVoidMoveTheVersionOnceEach() {
        val outlet = q("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
        val fx = SrMemoFixture({ x(it) }, sr, 300)
        val lines = listOf(SrMemoFixture.L(sku, 40))
        fx.memo("00000000-0000-4000-8000-0000000a0101", route, outlet, "2027-01-02", lines, "TIMESTAMPTZ '2027-01-03T01:40:00Z'")
        val a = build(); idleWorkerPass(); assertEquals(a.version, build().version, "stable before the edit")
        // the edit has the SAME lines: the figure is unchanged, but the new memo is newer, so as_of and with it the version move ONCE
        fx.memo("00000000-0000-4000-8000-0000000a0102", route, outlet, "2027-01-02", lines, "TIMESTAMPTZ '2027-01-03T01:50:00Z'", supersedes = "00000000-0000-4000-8000-0000000a0101")
        val b = build()
        assertNotEquals(a.version, b.version, "an edit with identical lines moves the version once (as_of is the newest received_at)")
        repeat(2) { idleWorkerPass(); assertEquals(b.version, build().version, "and then it is stable again") }
        // a void changes the figure: the version moves once
        fx.void("00000000-0000-4000-8000-0000000a0102", "2027-01-02")
        val c = build()
        assertNotEquals(b.version, c.version, "a void changes the figure and the version")
        repeat(2) { idleWorkerPass(); assertEquals(c.version, build().version, "stable after the void") }
    }
}
