package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.RouteDayPlanningJob
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-SYS-056 on the seed: from 00:05 Dhaka a route_day exists for every route held on the date, planned by visit kind
 * and days and the calendar exactly as the bundle's planner decides, with target_outlets left for the first bundle;
 * the job is idempotent and never changes a route-day that exists.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteDayPlanningTest {
    private lateinit var fresh: FreshDb
    private val now = AtomicReference(Instant.parse("2027-01-03T18:04:00Z")) // 2027-01-04 00:04 Dhaka (Monday)
    private val clock = AronClock { now.get() }
    private lateinit var job: RouteDayPlanningJob
    private lateinit var planner: SqlRoutePlanner

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        job = RouteDayPlanningJob(fresh.db, config, clock)
        planner = SqlRoutePlanner(fresh.db, GeoRepository(fresh.db, clock), config)
    }

    @AfterAll
    fun tearDown() { fresh.close() }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }

    private val heldOn = { d: String ->
        "SELECT count(DISTINCT route_id) FROM app.route_assignment WHERE valid_from <= DATE '$d' AND (valid_to IS NULL OR valid_to > DATE '$d')"
    }

    /** route_day.planned agrees with the bundle planner for every holder of the date. */
    private fun assertSameAsPlanner(date: LocalDate) {
        val holders = fresh.db.jdbi.withHandle<List<Long>, Exception> { h ->
            h.createQuery("SELECT DISTINCT user_id FROM app.route_assignment WHERE valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)")
                .bind("d", date).mapTo(Long::class.java).list()
        }
        assertTrue(holders.isNotEmpty())
        for (u in holders) for (p in planner.routesFor(u, date)) {
            val planned = fresh.db.jdbi.withHandle<Boolean, Exception> { h ->
                h.createQuery("SELECT planned FROM app.route_day WHERE route_id = :r AND business_date = :d").bind("r", p.routeId).bind("d", date).mapTo(Boolean::class.java).one()
            }
            assertEquals(p.plannedToday, planned, "route ${p.code} on $date")
        }
    }

    @Test
    fun theJobPlansEveryHeldRouteFrom0005DhakaOnceAndLikeThePlanner() {
        val monday = "2027-01-04"
        assertEquals(0, job.tick(), "00:04 Dhaka: not yet")
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$monday'"))
        now.set(Instant.parse("2027-01-03T18:05:00Z"))
        val created = job.tick()
        assertTrue(created > 0)
        assertEquals(count(heldOn(monday)), count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$monday'"), "one route-day per held route")
        assertEquals(created.toLong(), count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$monday'"))
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$monday' AND (target_outlets IS NOT NULL OR target_frozen_at IS NOT NULL OR state <> 'not_started')"),
            "the target is frozen by the first bundle, not by the job")
        assertSameAsPlanner(LocalDate.parse(monday))
        // Idempotent: a second tick and a second replica change nothing.
        assertEquals(0, job.tick())
        assertEquals(0, job.planDay(LocalDate.parse(monday)))
    }

    @Test
    fun weekendAndCalendarDecidePlannedAndAnExistingRouteDayIsNeverChanged() {
        // Friday 2027-01-08 is the default weekend: no route is planned.
        assertTrue(job.planDay(LocalDate.parse("2027-01-08")) > 0)
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '2027-01-08' AND planned"))
        assertSameAsPlanner(LocalDate.parse("2027-01-08"))

        // A global holiday on Tuesday 2027-01-05 with a zone make-up day for one zone: only that zone's routes may be planned.
        val zone = count("SELECT min(zone_id) FROM app.route r WHERE EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = r.id)")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES (DATE '2027-01-05', 'global', 0, 'holiday', false, 'Test holiday')")
            h.execute("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES (DATE '2027-01-05', 'zone', $zone, 'makeup_day', true, 'Make-up')")
        }
        // A route-day the bundle created first (planned true) for a route of another zone stays as it was.
        val other = count("SELECT COALESCE(min(r.id), 0) FROM app.route r WHERE r.zone_id <> $zone AND EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = r.id)")
        if (other > 0) fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.route_day (route_id, business_date, planned, target_outlets) VALUES ($other, DATE '2027-01-05', true, 7)")
        }
        job.planDay(LocalDate.parse("2027-01-05"))
        assertEquals(count(heldOn("2027-01-05")), count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '2027-01-05'"))
        assertEquals(0, count("SELECT count(*) FROM app.route_day rd JOIN app.route r ON r.id = rd.route_id WHERE rd.business_date = DATE '2027-01-05' AND rd.planned AND r.zone_id <> $zone AND rd.route_id <> $other"),
            "a global holiday: no route outside the make-up zone is planned")
        if (other > 0) assertEquals(1, count("SELECT count(*) FROM app.route_day WHERE route_id = $other AND business_date = DATE '2027-01-05' AND planned AND target_outlets = 7"))
        for (u in fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT DISTINCT a.user_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE r.zone_id = $zone").mapTo(Long::class.java).list() }) {
            for (p in planner.routesFor(u, LocalDate.parse("2027-01-05")).filter { it.zoneId == zone }) {
                assertEquals(p.plannedToday, count("SELECT count(*) FROM app.route_day WHERE route_id = ${p.routeId} AND business_date = DATE '2027-01-05' AND planned") == 1L, "make-up zone route ${p.code}")
            }
        }
    }
}
