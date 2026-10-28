package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.FreshDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Data-architecture review High 1 (D-REPORTS-ASWAS-01) on real PostgreSQL 16: a master change made today (a route moved to another
 * zone, a variant moved to another brand, a user given another role) never changes the numbers of an earlier business date, however
 * often that date is rebuilt. The fixture is the control-total day of `seed_day.sql`; the change is made on the real clock day, which
 * is after the fixture's business date, so the V0042/V0119 history keeps the fixture date on the old side.
 */
class AsWasAttributionTest {
    private val day = LocalDate.parse("2026-10-04")   // the fixture's own date, not the dev seed's
    private lateinit var fresh: FreshDb
    private lateinit var conn: Connection

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        conn = fresh.dataSource.connection
        conn.createStatement().use { it.execute(resource("seed_day.sql")) }
        conn.createStatement().use { it.execute(resource("login_extra.sql")) }
        for ((uuid, type) in listOf(
            "00000000-0000-4000-8000-0000000000a1" to "memo.created", "00000000-0000-4000-8000-0000000000a2" to "memo.created",
            "00000000-0000-4000-8000-0000000000a4" to "memo.created", "00000000-0000-4000-8000-0000000000a5" to "memo.created",
            "00000000-0000-4000-8000-000000000003" to "visit.closed",
        )) conn.prepareStatement(
            "INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid, payload) VALUES (?, split_part(?, '.', 1), ?, ?::date, ?::uuid, '{}'::jsonb)",
        ).use { it.setString(1, type); it.setString(2, type); it.setString(3, uuid); it.setString(4, day.toString()); it.setString(5, uuid); it.executeUpdate() }
    }

    @AfterEach
    fun tearDown() { conn.close(); fresh.close() }

    private fun resource(n: String) = checkNotNull(javaClass.classLoader.getResourceAsStream(n)).bufferedReader().readText()
    private fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }
    private fun long(sql: String): Long = conn.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
    private fun text(sql: String): String = conn.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) ?: "" } }

    /** Every aggregate and fact row of the date as comparable text, without the bookkeeping columns that move on each run. */
    private fun snapshot(): String = listOf(
        "agg_daily_route", "agg_daily_route_sku", "agg_daily_route_brand", "agg_daily_route_segment", "agg_daily_route_variant",
        "agg_daily_zone", "agg_hourly_zone", "agg_daily_outlet", "fact_visit", "fact_memo", "fact_attendance",
    ).joinToString("\n") { t ->
        t + "=" + text("SELECT coalesce(string_agg((to_jsonb(x) - 'updated_at' - 'last_event_id')::text, '|' ORDER BY (to_jsonb(x) - 'updated_at' - 'last_event_id')::text), '') FROM dw.$t x")
    }

    private fun fullRebuild(w: AggregationWorker) { w.requestRebuild(day); w.runUntilIdle() }

    @Test
    fun movingARouteToAnotherZoneTodayDoesNotChangeAnEarlierDay() {
        val w = AggregationWorker(fresh.db)
        w.runUntilIdle()
        val before = snapshot()
        val z1Gross = long("SELECT gross_mtk FROM dw.agg_daily_zone a JOIN app.zone z ON z.id = a.zone_id WHERE z.code = 'Z1'")
        exec("UPDATE app.route SET zone_id = (SELECT id FROM app.zone WHERE code = 'Z2') WHERE code = 'R2'")
        fullRebuild(w)
        assertEquals(before, snapshot(), "last week's numbers moved with the route")
        assertEquals(z1Gross, long("SELECT gross_mtk FROM dw.agg_daily_zone a JOIN app.zone z ON z.id = a.zone_id WHERE z.code = 'Z1'"))
        assertEquals("Z1", text("SELECT z.code FROM dw.fact_memo f JOIN app.zone z ON z.id = f.zone_id JOIN app.route r ON r.id = f.route_id WHERE r.code = 'R2' LIMIT 1"))
        // The route's zone is a function of the date: the old day answers Z1, a new day answers Z2.
        fresh.db.jdbi.useHandle<Exception> { h ->
            val today = LocalDate.parse(text("SELECT app.dhaka_date(now())::text"))
            val r2 = long("SELECT id FROM app.route WHERE code = 'R2'")
            assertEquals(long("SELECT id FROM app.zone WHERE code = 'Z1'"), Aggregator.zoneOn(h, r2, day))
            assertEquals(long("SELECT id FROM app.zone WHERE code = 'Z2'"), Aggregator.zoneOn(h, r2, today))
        }
    }

    @Test
    fun movingAVariantToAnotherBrandTodayDoesNotChangeAnEarlierDaysBrandOrSegmentRows() {
        val w = AggregationWorker(fresh.db)
        w.runUntilIdle()
        val before = snapshot()
        assertTrue(long("SELECT count(*) FROM dw.agg_daily_route_brand") > 0)
        exec("UPDATE app.product_node SET parent_id = (SELECT id FROM app.product_node WHERE code = 'B2') WHERE code = 'V1'")
        fullRebuild(w)
        assertEquals(before, snapshot())
        assertEquals(2, long("SELECT memo_count FROM dw.agg_daily_route_brand a JOIN app.product_node b ON b.id = a.brand_id JOIN app.route r ON r.id = a.route_id WHERE r.code = 'R1' AND b.code = 'B1'"))
    }

    @Test
    fun changingAUsersRoleTodayDoesNotChangeAnEarlierDaysAttendanceRole() {
        val w = AggregationWorker(fresh.db)
        w.runUntilIdle()
        assertTrue(long("SELECT count(*) FROM dw.fact_attendance WHERE role = 'SR'") > 0)
        val before = snapshot()
        exec("UPDATE app.app_user SET role = 'AMO' WHERE username = 'sr001'")
        fullRebuild(w)
        assertEquals(before, snapshot())
        assertEquals("SR", text("SELECT a.role FROM dw.fact_attendance a JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr001'"))
    }

    @Test
    fun movingAZoneToAnotherTerritoryTodayDoesNotChangeAnEarlierDaysZoneTerritory() {
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T2','Terr2', (SELECT id FROM app.division))")
        val w = AggregationWorker(fresh.db)
        w.runUntilIdle()
        val t1 = long("SELECT id FROM app.territory WHERE code = 'T1'")
        assertEquals(t1, long("SELECT territory_id FROM dw.agg_daily_zone a JOIN app.zone z ON z.id = a.zone_id WHERE z.code = 'Z1'"))
        exec("UPDATE app.zone SET territory_id = (SELECT id FROM app.territory WHERE code = 'T2') WHERE code = 'Z1'")
        fullRebuild(w)
        assertEquals(t1, long("SELECT territory_id FROM dw.agg_daily_zone a JOIN app.zone z ON z.id = a.zone_id WHERE z.code = 'Z1'"))
    }

    @Test
    fun theFactsCarryTheDimensionKeysOfTheirDateAndDimUserIsFilled() {
        fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        val w = AggregationWorker(fresh.db)
        w.runUntilIdle()
        assertEquals(3, long("SELECT count(*) FROM dw.dim_user WHERE role = 'SR'"), "dim_user is filled from app_user")
        assertEquals(0, long("SELECT count(*) FROM dw.fact_memo WHERE geo_key IS NULL OR outlet_key IS NULL OR user_key IS NULL"))
        assertEquals(0, long("SELECT count(*) FROM dw.fact_visit WHERE geo_key IS NULL OR outlet_key IS NULL OR user_key IS NULL"))
        val key = long("SELECT f.geo_key FROM dw.fact_memo f JOIN app.route r ON r.id = f.route_id WHERE r.code = 'R1' LIMIT 1")
        assertEquals(key, long("SELECT dw.geo_key_on((SELECT id FROM app.route WHERE code = 'R1'), DATE '$day')"))
        assertNotNull(key)
    }

    @Test
    fun theOneTimeRebuildQueuesEveryRouteDayOnceAndTheDrainRestoresTheNumbers() {
        val w = AggregationWorker(fresh.db, com.aktcl.aron.backend.platform.AronClock { java.time.Instant.parse("2026-10-06T06:00:00Z") })
        w.runUntilIdle()
        val before = snapshot()
        exec("TRUNCATE dw.agg_daily_route, dw.agg_daily_route_sku, dw.agg_daily_route_brand, dw.agg_daily_route_segment, dw.agg_daily_route_variant, dw.agg_daily_zone, dw.agg_hourly_zone, dw.agg_daily_outlet, dw.fact_visit, dw.fact_memo, dw.fact_attendance")
        assertEquals(3, w.rebuildOnce("dw-rebuild-test-1"), "R1, R2, R3 have records on the fixture day")
        assertEquals(0, w.rebuildOnce("dw-rebuild-test-1"), "the marker makes it once")
        w.runUntilIdle()
        assertEquals(before, snapshot())
    }
}
