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
import kotlin.test.assertTrue

/**
 * Scope of the SR targets sections (lane T-KPI1; lead ruling): every route, outlet and brand of the whole fixture carries a target, a KPI row, a programme
 * target and a campaign target, yet (1) an SR's bundle holds only the rows of the routes and outlets of its own assignment, never another route's, and
 * (2) the TSO's and the AMO's bundles (zone-wide route entries and their own routes) carry none of the SR sections: targets, achievement_mtd, calendar,
 * kpi, star and campaigns stay empty or absent. The client never sends a scope id; the bundle is built from the token's user.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrTargetsScopeTest {
    private lateinit var fresh: FreshDb
    private val clock = AronClock { Instant.parse("2027-01-03T02:00:00Z") }
    private val day = LocalDate.parse("2027-01-03")

    private fun q(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun x(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }
    private fun bundle(user: String, role: Role, flavour: String): BundleService.Result {
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val service = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        val id = q("SELECT id FROM app.app_user WHERE username = '$user'")
        return service.bundle(AronPrincipal(id, user, role, 0, "api", null, null, flavour, emptyList(), false, emptyList(), "j", clock.now().plusSeconds(600)), day, "1.0.0+1")
    }

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c -> seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } } }
        val admin = q("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        // A route nobody holds (same zone as an SR route), with an outlet of its own, so the fixture has a route and an outlet outside every assignment.
        val other = q("INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no) SELECT 'T-OTHER', 'Other route', 'Daily', zone_id, 'sr', 'daily', 127, 99 FROM app.route WHERE code = 'MIR-SR-D' RETURNING id")
        x("INSERT INTO app.outlet (code, name, owner_name, route_id, cluster_id, zone_id, channel, outlet_kind) SELECT 'T-OTHER-1', 'Other outlet', 'Owner', $other, cluster_id, zone_id, channel, outlet_kind FROM app.outlet WHERE zone_id = (SELECT zone_id FROM app.route WHERE id = $other) ORDER BY id LIMIT 1")
        x("INSERT INTO app.route_target_batch (batch_uuid, content_sha256, row_count, committed_by) VALUES ('00000000-0000-4000-8000-00000000b005', decode(repeat('ab', 32), 'hex'), 1, $admin)")
        val b = "'00000000-0000-4000-8000-00000000b005'"
        val sku = q("SELECT id FROM app.sku WHERE category_code = 'cigarette' ORDER BY id LIMIT 1")
        val brand = q("SELECT v.parent_id FROM app.sku s JOIN app.product_node v ON v.id = s.variant_id WHERE s.id = $sku")
        // EVERY route, outlet and programme gets data, so any leak across a route boundary would show.
        x("INSERT INTO app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid) SELECT id, $sku, DATE '2027-01-01', 1000, $b FROM app.route")
        x("INSERT INTO app.route_brand_kpi_target (route_id, brand_id, month, memo_target, kpi_alloc_mtk, batch_uuid) SELECT id, $brand, DATE '2027-01-01', 10, 100, $b FROM app.route")
        x("INSERT INTO app.route_kpi_month (route_id, month, kpi_alloc_mtk, batch_uuid) SELECT id, DATE '2027-01-01', 5000, $b FROM app.route")
        val star = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('superstar', 'MBLS-S', 'Star', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        val camp = q("INSERT INTO app.programme (kind, code, name_en, active_from, active_to, status) VALUES ('campaign', 'SSL-S', 'Campaign', DATE '2026-12-01', DATE '2027-01-31', 'active') RETURNING id")
        x("INSERT INTO app.route_programme_target (programme_id, route_id, month, target_qty, batch_uuid) SELECT $star, id, DATE '2027-01-01', 20, $b FROM app.route")
        x("INSERT INTO app.outlet_campaign_target (programme_id, month, outlet_id, std_target, memo_target, batch_uuid) SELECT $camp, DATE '2027-01-01', id, 100, 5, $b FROM app.outlet")
        x("INSERT INTO app.outlet_campaign_sku_target (programme_id, month, outlet_id, sku_id, qty_base, batch_uuid) SELECT $camp, DATE '2027-01-01', id, $sku, 50, $b FROM app.outlet")
        x("INSERT INTO dw.agg_daily_route_sku (business_date, route_id, sku_id, sold_qty_base, memo_count) SELECT DATE '2027-01-02', id, $sku, 10, 1 FROM app.route")
    }

    @AfterAll fun tearDown() = fresh.close()

    @Test
    fun anSrGetsOnlyTheRowsOfItsOwnRoutesAndOutletsWhileEveryRouteHasData() {
        val b = bundle("sr1001", Role.SR, "sr").full
        val ownRoutes = b.routes.map { it.route_id }.toSet()
        assertTrue(ownRoutes.isNotEmpty() && q("SELECT count(*) FROM app.route") > ownRoutes.size, "the fixture has routes the SR does not hold")
        var rows = 0
        for (r in b.routes) {
            val ownOutlets = r.outlets.map { it.outlet_id }.toSet()
            r.targets.forEach { rows++; assertEquals(r.route_id, it.jsonObject["scope_id"]!!.jsonPrimitive.content.toLong()) }
            r.achievement_mtd.forEach { assertEquals(r.route_id, it.jsonObject["scope_id"]!!.jsonPrimitive.content.toLong()) }
            r.campaigns.orEmpty().forEach { rows++; assertTrue(it["outlet_id"]!!.jsonPrimitive.content.toLong() in ownOutlets, "a campaign row of an outlet the route does not hold") }
            assertTrue(r.targets.isNotEmpty() && r.kpi != null && r.calendar != null && r.star != null, "the own route has every section")
        }
        assertTrue(rows > 0)
        assertTrue(b.routes.flatMap { it.campaigns.orEmpty() }.none { it["outlet_id"]!!.jsonPrimitive.content.toLong() !in b.routes.flatMap { r -> r.outlets }.map { o -> o.outlet_id } })
    }

    @Test
    fun theAmoAndTheTsoBundlesCarryNoneOfTheSrSections() {
        for ((user, role, flavour) in listOf(Triple("amo1001", Role.AMO, "amo"), Triple("tso1001", Role.TSO, "tso"))) {
            val b = bundle(user, role, flavour).full
            assertTrue(b.routes.isNotEmpty(), "$user: the zone-wide route entries are present, so the test is not vacuous")
            for (r in b.routes) {
                assertTrue(r.targets.isEmpty() && r.achievement_mtd.isEmpty(), "$user: targets and achievement stay empty on route ${r.route_id}")
                assertTrue(r.calendar == null && r.kpi == null && r.star == null && r.campaigns == null, "$user: calendar, kpi, star and campaigns are absent on route ${r.route_id}")
            }
        }
    }
}
