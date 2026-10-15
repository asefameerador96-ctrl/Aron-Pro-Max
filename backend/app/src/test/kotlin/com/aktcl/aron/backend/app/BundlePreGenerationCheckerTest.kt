package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.sync.BundlePreGenerator
import com.aktcl.aron.backend.sync.BundleService
import com.aktcl.aron.contract.Role
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

/** Independent T2 checker for F-SYS-067. Each test is expected to FAIL while its defect stands. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BundlePreGenerationCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var config: ServerConfig
    private lateinit var bundles: BundleService
    private val now = AtomicReference(Instant.parse("2027-01-03T16:00:00Z"))
    private val clock = AronClock { now.get() }
    private val paces = ArrayList<Int>()
    private lateinit var gen: BundlePreGenerator

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        config = DbServerConfig(fresh.db, RegistryDefaults(), clock)
        val geo = GeoRepository(fresh.db, clock)
        bundles = BundleService(fresh.db, config, SqlRoutePlanner(fresh.db, geo, config), clock, reach = SqlReachResolver(fresh.db, geo, clock))
        gen = BundlePreGenerator(fresh.db, config, bundles, clock) { paces += it }
    }

    @AfterAll
    fun tearDown() { fresh.close() }

    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql) }

    /** 04:30 Dhaka: the coverage check (and the 03:30 refresh) build TODAY, which is not a pre-fetch: it freezes targets and stamps logins. */
    @Test
    fun coverageCheckAndRefreshForTodayMustNotMarkLoginsOrFreezeTargets() {
        val today = LocalDate.parse("2027-01-11")
        now.set(Instant.parse("2027-01-10T22:30:00Z")) // 2027-01-11 04:30 Dhaka
        gen.coverageCheck(today)
        assertTrue(count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$today'") > 0, "fixture: route-days built")
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$today' AND (logged_in_at IS NOT NULL OR target_outlets IS NOT NULL OR state <> 'not_started')"),
            "worker-built bundle for today froze targets / marked logins before any phone fetched")
    }

    /** A pass that starts before midnight and runs across it turns D+1 into today part-way: later builds are no longer pre-fetches. */
    @Test
    fun aPassCrossingMidnightDoesNotMarkLogins() {
        val d = LocalDate.parse("2027-01-12")
        now.set(Instant.parse("2027-01-11T17:59:00Z")) // 23:59 Dhaka on the 11th
        val g = BundlePreGenerator(fresh.db, config, bundles, clock) { now.set(now.get().plusSeconds(40)) }
        g.generate(d)
        assertEquals(0, count("SELECT count(*) FROM app.route_day WHERE business_date = DATE '$d' AND (logged_in_at IS NOT NULL OR target_outlets IS NOT NULL)"),
            "builds after 00:00 Dhaka froze targets / marked logins")
    }

    /** The refresh must keep the dirty key of a user whose rebuild failed. */
    @Test
    fun refreshKeepsTheDirtyKeyOfAFailedBuild() {
        val d = LocalDate.parse("2027-01-13")
        val sr = count("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val broken = object : ServerConfig by config {
            override fun value(key: String) = if (key == "cfg.sys.schedule_horizon_days") error("boom") else config.value(key)
            override fun int(key: String): Int = if (key == "cfg.sys.schedule_horizon_days") error("boom") else config.int(key)
        }
        val bad = BundleService(fresh.db, broken, SqlRoutePlanner(fresh.db, GeoRepository(fresh.db, clock), broken), clock)
        exec("SELECT app.mark_dirty('bundle_user', $sr, DATE '$d', 'test')")
        val r = BundlePreGenerator(fresh.db, config, bad, clock) { }.refresh(d)!!
        assertEquals(1, r.failed)
        assertEquals(1, count("SELECT count(*) FROM app.dirty_key WHERE kind = 'bundle_user' AND subject_id = $sr AND business_date = DATE '$d'"),
            "a failed rebuild must stay queued")
    }

    /** The worker principal has no device: bind_ordinal 0. A phone bound as ordinal >0 computes another digest, so the pre-built seq is not its ETag. */
    @Test
    fun preBuiltSeqEqualsWhatABoundPhoneGets() {
        val d = LocalDate.parse("2027-01-14")
        now.set(Instant.parse("2027-01-13T14:00:00Z")) // 20:00 on the 13th Dhaka: D+1 = 14th
        val sr = count("SELECT id FROM app.app_user WHERE username = 'sr1001'")
        val dev = count("SELECT id FROM app.device WHERE device_uuid = '00000000-0000-4000-8000-000000000001'")
        exec("UPDATE app.device_binding SET bind_ordinal = 1 WHERE user_id = $sr AND device_id = $dev AND status = 'active'")
        gen.generate(d)
        val pre = count("SELECT max(snapshot_seq) FROM app.bundle_snapshot WHERE user_id = $sr AND business_date = DATE '$d'")
        now.set(Instant.parse("2027-01-13T02:00:00Z"))
        val phone = AronPrincipal(sr, "sr1001", Role.SR, 0, "api", dev, "00000000-0000-4000-8000-000000000001", "sr", emptyList(), false, emptyList(), "j", now.get().plusSeconds(600))
        val got = bundles.bundle(phone, d, "1.0.0+1")
        assertEquals(pre, got.seq, "phone's first fetch of D+1 bumps the seq (no 304 on the pre-built ETag)")
    }

    /** Thursday 22:00 Dhaka: the phone pre-fetches the next WORKING day (Saturday), but the worker builds Friday only. */
    @Test
    fun thursdayNightPreBuildsTheNextWorkingDay() {
        now.set(Instant.parse("2027-01-07T16:00:00Z")) // Thursday 22:00 Dhaka
        val g = BundlePreGenerator(fresh.db, config, bundles, clock) { }
        g.tick(now.get())
        assertTrue(count("SELECT count(*) FROM app.bundle_snapshot WHERE business_date = DATE '2027-01-09'") > 0, "Saturday (next working day) has no pre-built snapshot")
    }

    /** The session lock must be gone after the phase. */
    @Test
    fun phaseLockIsReleased() {
        val d = LocalDate.parse("2027-01-20")
        gen.generate(d)
        java.sql.DriverManager.getConnection(fresh.url).use { c ->
            c.createStatement().use { s ->
                val rs = s.executeQuery("SELECT pg_try_advisory_lock(hashtextextended('bundle-pregen:d1:$d', 7))")
                rs.next(); assertTrue(rs.getBoolean(1), "lock leaked on the pooled connection")
            }
        }
    }
}
