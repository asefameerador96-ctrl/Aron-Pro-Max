package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GET /v1/dashboards/geo-validation (contract getGeoValidation) on the seeded day (Z1: R1 with visits 1-3, R2 with
 * visit 4; Z2: R3 with visit 5). Server verdicts: visit 1 in_range and 2 out_of_range (both agree with the phone),
 * 4 and 5 out_of_range (the phone said in_range), 3 never re-checked. Signals: GEO_MOCK in Z1 (open), GEO_MOCK in Z1
 * (dismissed), GEO_TELEPORT in Z2 (reviewed), CLOCK_SKEW with no zone. Figures are hand-computed; a zone caller sees
 * only its zone and is refused another.
 */
class GeoValidationTest {
    private val day = LocalDate.parse("2026-10-04")
    private lateinit var fresh: FreshDb
    private lateinit var service: DashboardService
    private var z1 = 0L
    private var z2 = 0L

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("seed_day.sql")!!.bufferedReader().readText()) }
            c.createStatement().use {
                it.execute(
                    """
                    UPDATE app.visit SET server_verdict = CASE client_uuid::text
                        WHEN '00000000-0000-4000-8000-000000000001' THEN 'in_range' WHEN '00000000-0000-4000-8000-000000000002' THEN 'out_of_range'
                        WHEN '00000000-0000-4000-8000-000000000004' THEN 'out_of_range' WHEN '00000000-0000-4000-8000-000000000005' THEN 'out_of_range' END,
                      server_checked_at = now()
                     WHERE client_uuid::text <> '00000000-0000-4000-8000-000000000003';
                    INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, zone_id, score, status, config_version)
                    SELECT s.code, 2, DATE '2026-10-04', 'visit', s.subject, z.id, 20, s.status, 1
                      FROM (VALUES ('GEO_MOCK', 'v3', 'Z1', 'open'), ('GEO_MOCK', 'v2', 'Z1', 'dismissed'), ('GEO_TELEPORT', 'v5', 'Z2', 'reviewed'),
                                   ('CLOCK_SKEW', 'v9', NULL, 'open')) AS s(code, subject, zone, status)
                      LEFT JOIN app.zone z ON z.code = s.zone;
                    INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, zone_id, score, status, config_version)
                    SELECT 'GEO_MOCK', 2, DATE '2026-10-05', 'visit', 'v8', id, 20, 'open', 1 FROM app.zone WHERE code = 'Z1';
                    """.trimIndent(),
                )
            }
            for (u in listOf("a1", "a2", "a4", "a5")) c.createStatement().use {
                it.execute("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','x','2026-10-04','00000000-0000-4000-8000-0000000000$u')")
            }
        }
        AggregationWorker(fresh.db).also { w -> w.runUntilIdle(); fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }; w.requestRebuild(day); w.runUntilIdle() }
        z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'"); z2 = id("SELECT id FROM app.zone WHERE code = 'Z2'")
        service = DashboardService(fresh.db, AronClock { Instant.parse("2026-10-04T12:00:00Z") })
    }

    @AfterEach
    fun tearDown() = fresh.close()

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun reach(vararg zones: Long, national: Boolean = false) =
        Reach(1, Role.TSO, day, national, zones.toSet(), emptySet(), false, if (national) emptyList() else listOf(ReachNode("zone", zones.first())))

    @Test
    fun nationalFiguresEqualTheHandComputedOnes() {
        val g = service.geoValidation(reach(national = true), null, null, day, day)
        assertEquals("national", g.node.type)
        assertEquals(5, g.visits)
        // Geo-valid follows the server verdict where there is one: only visit 1 stays in range (1 of 5).
        assertEquals(20.0, g.geo_valid_pct); assertEquals(20.0, g.force_sale_pct); assertEquals(20.0, g.mock_pct)
        assertEquals(50.0, g.geo_mismatch_pct, "2 of the 4 re-checked visits disagree")
        val summary = service.summary(reach(national = true), null, null, day, day)
        assertEquals(summary.kpis.geo_valid_pct, g.geo_valid_pct)
        assertEquals(com.aktcl.aron.backend.analytics.pct(summary.kpis.suspicious_visits.toLong(), 5), g.suspicious_pct)
        assertEquals(mapOf("CLOCK_SKEW" to 1, "GEO_MOCK" to 1, "GEO_TELEPORT" to 1), g.signals_by_code, "dismissed and other dates left out")
        assertEquals(listOf("wing"), g.children.map { it.node.type }.distinct())
        assertEquals(5, g.children.sumOf { it.visits })
    }

    @Test
    fun aZoneCallerSeesOnlyItsZone() {
        val g = service.geoValidation(reach(z1), null, null, day, day)
        assertEquals("zone" to z1, g.node.type to g.node.id)
        assertEquals(4, g.visits)
        assertEquals(33.33, g.geo_mismatch_pct, "1 of 3 re-checked visits in Z1")
        assertEquals(mapOf("GEO_MOCK" to 1), g.signals_by_code, "no Z2 signal and no zone-less signal")
        assertEquals(setOf("route"), g.children.map { it.node.type }.toSet())
        assertEquals(4, g.children.sumOf { it.visits })
        val z2g = service.geoValidation(reach(z2), null, null, day, day)
        assertEquals(100.0, z2g.geo_mismatch_pct); assertEquals(mapOf("GEO_TELEPORT" to 1), z2g.signals_by_code)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { service.geoValidation(reach(z1), "zone", z2, day, day) }.code)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { service.geoValidation(reach(z1), "national", null, day, day) }.code)
    }

    @Test
    fun emptyRangesAreNullRatesAndBadRangesAre400() {
        val g = service.geoValidation(reach(national = true), null, null, day.plusDays(10), day.plusDays(10))
        assertEquals(0, g.visits); assertNull(g.geo_valid_pct); assertNull(g.geo_mismatch_pct); assertTrue(g.signals_by_code.isEmpty())
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { service.geoValidation(reach(national = true), null, null, day, day.minusDays(1)) }.code)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { service.geoValidation(reach(national = true), null, null, day, day.plusDays(93)) }.code)
    }
}
