package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.sync.BundlePreGenerator
import com.aktcl.aron.backend.sync.BundleService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-SYS-067 on the seed: the 22:00 pass builds the next business day for every active field user once, the 03:30 pass
 * rebuilds only dirtied users, the 04:30 check builds what is missing below 99 %, a pre-generation never freezes a target
 * or marks a login, builds are paced, and one replica at a time runs a phase. Clock: Sunday 2027-01-03 22:00 Dhaka.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BundlePreGenerationTest {
    private lateinit var fresh: FreshDb
    private lateinit var gen: BundlePreGenerator
    private val now = AtomicReference(Instant.parse("2027-01-03T16:00:00Z"))
    private val clock = AronClock { now.get() }
    private val paces = ArrayList<Int>()
    private val d1: LocalDate = LocalDate.parse("2027-01-04")

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        val config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        val bundles = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        gen = BundlePreGenerator(fresh.db, config, bundles, clock) { paces += it }
    }

    @AfterAll
    fun tearDown() { fresh.close() }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private val fieldUsers get() = count("SELECT count(*) FROM app.app_user WHERE status = 'active' AND role IN ('SR','AMO','TSO')").toInt()
    private fun snapshots(d: LocalDate = d1) = count("SELECT count(*) FROM app.bundle_snapshot WHERE business_date = DATE '$d'").toInt()

    @Test
    @Order(1)
    fun theNightlyPassBuildsEveryActiveFieldUserOnceAndPacesTheBuilds() {
        assertTrue(fieldUsers >= 3, "fixture: $fieldUsers")
        val r = gen.generate(d1)!!
        assertEquals(fieldUsers, r.total)
        assertEquals(fieldUsers, r.built); assertEquals(0, r.failed)
        assertEquals(fieldUsers, snapshots())
        assertEquals(List(fieldUsers) { 20 }, paces, "one pause per build at cfg.bundle.regen_max_per_s")
        assertEquals(100.0, gen.coverage(d1).pct)
        // Again: nobody lacks a snapshot, so nothing is built and no snapshot row is added.
        val again = gen.generate(d1)!!
        assertEquals(0, again.built)
        assertEquals(fieldUsers, snapshots())
    }

    @Test
    @Order(2)
    fun aPreGenerationFreezesNoTargetAndMarksNoLogin() {
        assertTrue(count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$d1'") > 0, "route-days exist for the pre-fetched date")
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$d1' AND (target_outlets IS NOT NULL OR logged_in_at IS NOT NULL OR state <> 'not_started')"))
    }

    @Test
    @Order(3)
    fun theRefreshRebuildsOnlyDirtiedUsersAndClearsTheirKeys() {
        val sr = count("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val before = count("SELECT max(snapshot_seq) FROM app.bundle_snapshot WHERE user_id = $sr AND business_date = DATE '$d1'")
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.outlet SET name = name || ' r' WHERE route_id = (SELECT route_id FROM app.route_assignment WHERE user_id = $sr LIMIT 1) AND id = (SELECT min(id) FROM app.outlet WHERE route_id = (SELECT route_id FROM app.route_assignment WHERE user_id = $sr LIMIT 1))")
            h.execute("SELECT app.mark_dirty('bundle_user', $sr, DATE '$d1', 'test')")
        }
        paces.clear()
        val r = gen.refresh(d1)!!
        assertEquals(1, r.total); assertEquals(1, r.built)
        assertEquals(1, paces.size)
        assertEquals(0, count("SELECT count(*) FROM app.dirty_key WHERE kind = 'bundle_user'"))
        val after = count("SELECT max(snapshot_seq) FROM app.bundle_snapshot WHERE user_id = $sr AND business_date = DATE '$d1'")
        assertTrue(after > before, "the changed content got a new seq: $before -> $after")
        // Nothing dirty: nothing built.
        assertEquals(0, gen.refresh(d1)!!.total)
    }

    @Test
    @Order(4)
    fun theCoverageCheckBuildsWhatIsMissingBelowNinetyNinePercent() {
        val amo = count("SELECT id FROM app.app_user WHERE username = 'amo1001'")
        fresh.db.jdbi.useHandle<Exception> { it.execute("DELETE FROM app.bundle_snapshot WHERE user_id = $amo AND business_date = DATE '$d1'") }
        val c = gen.coverage(d1)
        assertTrue(c.pct < BundlePreGenerator.COVERAGE_TARGET_PCT && c.covered == c.active - 1, c.toString())
        paces.clear()
        val after = gen.coverageCheck(d1)!!
        assertEquals(100.0, after.pct)
        assertEquals(1, paces.size, "only the missing user was built")
        assertEquals(1, count("SELECT count(*) FROM app.bundle_snapshot WHERE user_id = $amo AND business_date = DATE '$d1'"))
    }

    @Test
    @Order(5)
    fun aPhaseRunsOnOneReplicaAtATime() {
        // Another session holds the phase's lock: this worker does nothing and says so.
        java.sql.DriverManager.getConnection(fresh.url).use { c ->
            c.createStatement().use { it.execute("SELECT pg_advisory_lock(hashtextextended('bundle-pregen:d1:2027-01-05', 7))") }
            assertNull(gen.generate(LocalDate.parse("2027-01-05")))
        }
        // Postgres frees a closed session's advisory lock once its backend notices the closed socket (asynchronous), so
        // retry for a bounded time; generate returns null with no side effects while the lock is still held.
        val deadline = System.nanoTime() + 5_000_000_000L
        var done = gen.generate(LocalDate.parse("2027-01-05"))
        while (done == null && System.nanoTime() < deadline) { Thread.sleep(50); done = gen.generate(LocalDate.parse("2027-01-05")) }
        assertNotNull(done, "the lock goes with its session")
    }

    private fun tickAt(iso: String) { now.set(Instant.parse(iso)); gen.tick(now.get()) }

    @Test
    @Order(6)
    fun theSchedulerRunsEachPhaseOncePerDateWhenItsTimeComes() {
        val d2 = LocalDate.parse("2027-01-06")
        fresh.db.jdbi.useHandle<Exception> { it.execute("DELETE FROM app.bundle_snapshot WHERE business_date = DATE '$d2'") }
        paces.clear()
        tickAt("2027-01-05T15:59:00Z") // 21:59 Dhaka on the 5th: D+1 not yet
        assertEquals(0, snapshots(d2))
        tickAt("2027-01-05T16:00:00Z") // 22:00
        assertEquals(fieldUsers, snapshots(d2))
        val built = paces.size
        tickAt("2027-01-05T16:01:00Z") // the same night again
        assertEquals(built, paces.size, "a phase does not run twice for one date")
    }
}
