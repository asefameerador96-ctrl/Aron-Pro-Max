package com.aktcl.aron.backend.config

import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-060: density index and calibration report from stored outlets and fixes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigGeoReportsTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var reports: ConfigGeoReports
    private lateinit var svc: ConfigService
    private var outlet = 0L

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        val r = ConfigResolver(env.fresh.db, clock)
        svc = ConfigService(env.fresh.db, r, clock)
        reports = ConfigGeoReports(env.fresh.db, svc, r, clock)
        outlet = env.one("SELECT id FROM app.outlet WHERE lat IS NOT NULL ORDER BY id LIMIT 1")!!.toLong()
    }
    @AfterAll fun tearDown() = env.close()

    @Test
    fun densityReportsMedianNeighboursPerRadiusAndTheIndex() {
        val d = reports.density("global", 0)
        assertEquals(listOf(25, 50, 100, 150, 300), d.radii_m)
        assertTrue(d.rows.isNotEmpty() && d.rows.all { it.median_neighbours.size == d.radii_m.size && it.node.type == "zone" })
        assertTrue(d.rows.sumOf { it.outlets } > 0)
        // Monotone: a wider radius never has a smaller median.
        assertTrue(d.rows.all { r -> r.median_neighbours.zipWithNext().all { (a, b) -> b >= a } })
        // Two outlets 10 m apart in a fresh zone: each has one neighbour within 25 m, index 100 %.
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-D', 'D', division_id FROM app.territory LIMIT 1")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-D', 'D', id FROM app.territory WHERE code = 'T-D'")
            h.execute("INSERT INTO app.cluster (zone_id, name) SELECT id, 'C' FROM app.zone WHERE code = 'Z-D'")
            h.execute("INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel, lat, lng) SELECT 'DN-1', 'a', 'o', z.id, c.id, 'GT', 23.8000, 90.4000 FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id WHERE z.code = 'Z-D'")
            h.execute("INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel, lat, lng) SELECT 'DN-2', 'b', 'o', z.id, c.id, 'GT', 23.80009, 90.4000 FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id WHERE z.code = 'Z-D'")
        }
        val zid = env.one("SELECT id FROM app.zone WHERE code = 'Z-D'")!!.toLong()
        val z = reports.density("zone", zid).rows.single()
        assertEquals(2, z.outlets); assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0), z.median_neighbours); assertEquals(100.0, z.density_index_pct)
    }

    @Test
    fun calibrationBucketsDistancesAndSuggestsARadiusOnlyWithEnoughVisits() {
        val sr = env.ids.getValue("sr1001")
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            for (n in 0 until 120) h.createUpdate(
                "INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, distance_m) " +
                    "VALUES (CAST(:u AS uuid), CAST(:f AS uuid), app.dhaka_date(now()), :sr, now(), 0, 'sr_call', :o, now(), 1, true, :v, 100, 100, 'master', :a, :d)",
            ).bind("u", UUID.randomUUID().toString()).bind("f", UUID.randomUUID().toString()).bind("sr", sr).bind("o", outlet).bind("v", if (10 + n <= 100) "in_range" else "out_of_range").bind("a", if (n % 4 == 0) "force_sale" else "sale_allowed").bind("d", (10 + n).toDouble()).execute()
        }
        val few = reports.calibration("global", 0).rows.single()
        assertEquals(120, few.visits); assertNull(few.suggested_radius_m) // default minimum is 500 visits
        assertEquals(25.0, few.force_sale_pct); assertEquals(120, few.histogram.sumOf { it.visits })
        svc.create(env.principal("admin1001", Role.ADMIN), ConfigChangeRequestIn("Lower the calibration minimum", false, listOf(ConfigChangeItemIn("cfg.geo.calibration_min_visits", "global", 0, JsonPrimitive(100)))), null, null)
        clock.advance(2)
        val enough = ConfigGeoReports(env.fresh.db, svc, ConfigResolver(env.fresh.db, clock), clock).calibration("global", 0).rows.single()
        assertEquals(120, enough.suggested_radius_m) // p90 = 117 m, rounded up to 5 m
    }
}
