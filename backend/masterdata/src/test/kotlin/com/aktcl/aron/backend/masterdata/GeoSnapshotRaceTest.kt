package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-REPORTS-REACH-GEO-02: a reload that read the geography before an admin edit never installs its old snapshot, and a new version empties the reach cache. */
class GeoSnapshotRaceTest {
    private val day = LocalDate.parse("2026-10-04")
    private val clock = AronClock { Instant.parse("2026-10-04T12:00:00Z") }   // fixed: neither the 60 s snapshot nor the 300 s reach entry expires
    private lateinit var fresh: FreshDb
    private var uid = 0L
    private var t1 = 0L

    private fun id(sql: String) = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery(sql).mapTo(Long::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(sql) }

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        exec("INSERT INTO app.wing (code, name) VALUES ('W1','Wing1')")
        exec("INSERT INTO app.division (code, name, wing_id) VALUES ('D1','Div1', (SELECT id FROM app.wing WHERE code = 'W1'))")
        exec("INSERT INTO app.territory (code, name, division_id) VALUES ('T1','Terr1', (SELECT id FROM app.division WHERE code = 'D1'))")
        exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('Z1','Zone1', (SELECT id FROM app.territory WHERE code = 'T1'))")
        exec("INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('tso1','Tso One','TSO',false)")
        t1 = id("SELECT id FROM app.territory WHERE code = 'T1'"); uid = id("SELECT id FROM app.app_user WHERE username = 'tso1'")
        exec("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) VALUES ($uid, 'territory', $t1, DATE '2026-01-01')")
    }

    @AfterEach
    fun tearDown() = fresh.close()

    @Test
    fun aLoadThatReadBeforeTheEditIsNotInstalledAfterTheInvalidation() {
        val geo = GeoRepository(fresh.db, clock)
        var first = true
        geo.afterLoad = {
            // The admin write commits and invalidates while the first reload is between its read and its install.
            if (first) { first = false; exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('Z2','Zone2', $t1)"); geo.invalidate() }
        }
        val z2Seen = { g: Geo -> g.zoneTerritory.size == 2 }
        val s = geo.snapshot()
        assertTrue(z2Seen(s.geo), "the snapshot answered after the edit holds the new zone")
        assertTrue(z2Seen(geo.snapshot().geo), "and so does the cached one (the stale read was not installed)")
        assertEquals(s.version, geo.version(), "one version for that geography")
        val resolver = SqlReachResolver(fresh.db, geo, clock)
        assertEquals(2, resolver.reach(uid, Role.TSO, 1, day).zoneIds.size)
    }

    @Test
    fun anInvalidationBetweenTheGenerationCheckAndTheInstallIsNotLost() {
        val geo = GeoRepository(fresh.db, clock)
        var armed = true
        // The edit commits and invalidates after the load passed its staleness check and right before it installs the snapshot it read.
        geo.beforeInstall = { if (armed) { armed = false; exec("INSERT INTO app.zone (code, name, territory_id) VALUES ('Z2','Zone2', $t1)"); geo.invalidate() } }
        geo.snapshot()   // the pre-edit read
        assertEquals(2, geo.snapshot().geo.zoneTerritory.size, "the next read sees the edit: the old snapshot was not left installed")
    }

    @Test
    fun theGeographyIsReadInOneStatementAndMatchesTheTables() {
        val statements = java.util.concurrent.atomic.AtomicInteger()
        fresh.db.jdbi.setSqlLogger(object : org.jdbi.v3.core.statement.SqlLogger {
            override fun logAfterExecution(context: org.jdbi.v3.core.statement.StatementContext) { statements.incrementAndGet() }
        })
        val g = GeoRepository(fresh.db, clock).geo()
        assertEquals(1, statements.get(), "one statement = one MVCC snapshot: the chain zone > territory > division > wing is never a mix of two commits")
        val z1 = id("SELECT id FROM app.zone WHERE code = 'Z1'")
        assertEquals(mapOf(z1 to t1), g.zoneTerritory)
        assertEquals(mapOf(t1 to id("SELECT id FROM app.division WHERE code = 'D1'")), g.territoryDivision)
        assertEquals(mapOf(id("SELECT id FROM app.division WHERE code = 'D1'") to id("SELECT id FROM app.wing WHERE code = 'W1'")), g.divisionWing)
        assertEquals("Zone1", g.names[("zone" to z1)]?.second); assertEquals("Z1", g.names[("zone" to z1)]?.first)
        assertEquals("Terr1", g.names[("territory" to t1)]?.second)
    }

    @Test
    fun callersHoldingTheWholePoolAreNotStalledBehindAReloadThatNeedsAConnection() {
        // Pool of 2, both connections held by callers that call geo() inside their handle (as AdminGeography and the planner do); a third caller with no handle reloads.
        val small = com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply { jdbcUrl = fresh.url; maximumPoolSize = 2; connectionTimeout = 1_500 })
        try {
            var now = Instant.parse("2026-10-04T12:00:00Z")
            val smallDb = com.aktcl.aron.backend.platform.Database(small)
            val geo = GeoRepository(smallDb, AronClock { now })
            geo.snapshot()                                  // warm
            now = now.plusSeconds(120)                      // past the TTL: an expired entry, no edit since
            val ready = java.util.concurrent.CountDownLatch(2); val go = java.util.concurrent.CountDownLatch(1)
            val waited = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val holders = (1..2).map { n ->
                Thread {
                    smallDb.jdbi.useHandle<Exception> {
                        ready.countDown(); go.await()
                        val t0 = System.nanoTime(); geo.snapshot(); waited["h$n"] = (System.nanoTime() - t0) / 1_000_000
                    }
                }.also { it.start() }
            }
            ready.await()
            var loaderError: Throwable? = null
            val loader = Thread { try { geo.snapshot() } catch (e: Throwable) { loaderError = e } }.also { it.start() }   // takes the reload lock, then waits for a connection
            Thread.sleep(300)
            go.countDown()
            holders.forEach { it.join(10_000) }; loader.join(10_000)
            assertTrue(waited.values.all { it < 1_000 }, "holders were served the expired entry at once, not after the pool timeout: $waited")
            assertEquals(null, loaderError, "and the loader got its connection once they finished")
        } finally { small.close() }
    }

    @Test
    fun aNewGeographyVersionEmptiesTheReachCache() {
        val geo = GeoRepository(fresh.db, clock)
        val resolver = SqlReachResolver(fresh.db, geo, clock)
        resolver.reach(uid, Role.TSO, 1, day)
        assertEquals(1, resolver.cacheSize())
        resolver.reach(uid, Role.TSO, 1, day)
        assertEquals(1, resolver.cacheSize(), "same geography: a hit")
        exec("UPDATE app.zone SET name = 'Renamed' WHERE code = 'Z1'")
        geo.invalidate()
        resolver.reach(uid, Role.TSO, 1, day)
        assertEquals(1, resolver.cacheSize(), "the entry of the old geography is gone, not kept until the 50 000 clear")
    }
}
