package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPS-02 / OPS-03: the worker's heartbeat reads the gauges the alerts need and the liveness probe follows it: 503
 * before the first beat and once a beat is older than the stale limit, 200 while it is fresh.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerHealthTest {
    private lateinit var fresh: FreshDb

    @BeforeAll
    fun setUp() { fresh = FreshDb.create() }

    @AfterAll
    fun tearDown() = fresh.close()

    @Test
    fun theHeartbeatReadsTheGaugesAndTheProbeFollowsIt() = testApplication {
        val t = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
        val health = WorkerHealth(fresh.db, AronClock { t.get() }, staleAfter = Duration.ofMinutes(3))
        application { workerHealthRoutes(health) }
        assertFalse(health.alive())
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/live").status, "no beat yet")
        assertEquals(HttpStatusCode.OK, client.get("/health").status, "the process answers")

        // One key waiting, one dead-lettered; nothing in the outbox; no registry row; no field users yet (coverage 100).
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SELECT app.mark_dirty('route_day_agg', 1, DATE '2027-01-03', 'test')")
            h.execute("SELECT app.mark_dirty('route_day_agg', 2, DATE '2027-01-03', 'test')")
            h.execute("UPDATE app.dirty_key SET dead_at = now() WHERE subject_id = 2")
        }
        health.beat()
        assertTrue(health.alive())
        val live = client.get("/health/live")
        assertEquals(HttpStatusCode.OK, live.status, live.bodyAsText())
        val g = health.last!!
        assertEquals(0L, g.outboxDepth); assertEquals(0L, g.outboxAgeS)
        assertEquals(1L, g.dirtyUnclaimed); assertEquals(1L, g.dirtyDead)
        assertEquals(null, g.registryOldestDays); assertEquals(45, g.registryWindowDays); assertEquals(100.0, g.bundleCoveragePct)
        assertEquals("heartbeat outbox_depth=0 outbox_age_s=0 dirty_unclaimed=1 dirty_dead=1 registry_oldest_days=0 registry_window_days=45 bundle_coverage_pct=100", g.line())
        assertTrue(Regex("^heartbeat( [a-z_]+=[0-9]+)+$").matches(g.line()), "every value an integer the alert regexes extract")

        // Three minutes and a second later without a beat: stale, the probe fails, a fresh beat recovers it.
        t.set(t.get().plusSeconds(181))
        assertFalse(health.alive())
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/live").status)
        health.beat()
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }
}
