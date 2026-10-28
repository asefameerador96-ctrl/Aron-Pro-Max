package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Data-architecture review High 2 / Medium 6, 8 on real PostgreSQL 16 over the control-total day of `seed_day.sql` (2026-10-04): the audited range
 * rebuild, the nightly 7-day reconcile (`dw.agg_reconcile`), the restatement log (`dw.agg_restatement_log`), and the back-off, dead state and re-drive
 * of a failing key. The worker clock is fixed at 2026-10-06, so 2026-10-04 is a closed day.
 */
class RebuildReconcileTest {
    private val day = LocalDate.parse("2026-10-04")
    private val now = AtomicReference(Instant.parse("2026-10-06T06:00:00Z"))   // 12:00 in Dhaka
    private val clock = AronClock { now.get() }
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
    private fun worker(id: String = "w1") = AggregationWorker(fresh.db, clock, id)
    private fun routeId(code: String) = long("SELECT id FROM app.route WHERE code = '$code'")
    private fun zoneId(code: String) = long("SELECT id FROM app.zone WHERE code = '$code'")

    private fun admin(role: Role = Role.ADMIN) = AronPrincipal(
        userId = long("SELECT id FROM app.app_user WHERE username = 'sr001'"), username = "admin1", role = role, scopeVersion = 1, audience = "aron-api",
        deviceId = null, deviceUuid = null, flavour = "web", permissions = emptyList(), pii = false, amr = listOf("pwd", "mfa"), jti = "j", expiresAt = now.get().plusSeconds(600),
    )
    private fun reach(national: Boolean, vararg zones: Long) = Reach(1, Role.ADMIN, day, national, zones.toSet(), emptySet(), false, emptyList())

    // ---------- the audited range rebuild ----------

    @Test
    fun aRangeRebuildRepairsACorruptedDayAuditsItAndRestatesWithItsId() {
        val w = worker(); w.runUntilIdle()
        val net = long("SELECT net_mtk FROM dw.agg_daily_route WHERE route_id = ${routeId("R1")} AND business_date = '$day'")
        exec("UPDATE dw.agg_daily_route SET net_mtk = net_mtk + 700 WHERE route_id = ${routeId("R1")} AND business_date = '$day'")
        val svc = RebuildService(fresh.db, clock)
        val r = svc.rebuild(admin(), reach(true), day, day, RebuildService.Scope(national = true), "repair after the 4 Oct incident", "5b3f0a3e-6d1c-4b43-9a6c-1c1c1c1c1c1c")
        assertEquals(3, r.routeDays)
        w.runUntilIdle()
        assertEquals(net, long("SELECT net_mtk FROM dw.agg_daily_route WHERE route_id = ${routeId("R1")} AND business_date = '$day'"))
        // The restatement says what moved: -700 on the route, from the audited rebuild.
        assertEquals(-700, long("SELECT (delta ->> 'net_mtk')::bigint FROM dw.agg_restatement_log WHERE grain = 'route' AND key1 = ${routeId("R1")} AND reason = 'rebuild'"))
        assertEquals(r.rebuildId.toString(), text("SELECT rebuild_id::text FROM dw.agg_restatement_log WHERE grain = 'route' AND key1 = ${routeId("R1")}"))
        // The request is in the audit chain with its reason and range.
        assertEquals(1, long("SELECT count(*) FROM app.audit_log WHERE entity = 'dw_rebuild' AND entity_id = '${r.rebuildId}' AND reason = 'repair after the 4 Oct incident'"))
        assertEquals(3, long("SELECT (after ->> 'route_days')::int FROM app.audit_log WHERE entity = 'dw_rebuild' AND entity_id = '${r.rebuildId}'"))
    }

    @Test
    fun aRebuildIsAdminOnlyNamedAndInsideTheCallersReach() {
        val svc = RebuildService(fresh.db, clock)
        val s = RebuildService.Scope(national = true)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { svc.rebuild(admin(Role.TSO), reach(true), day, day, s, "x") }.code)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.rebuild(admin(), reach(true), day, day, s, " ") }.code)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.rebuild(admin(), reach(true), day, day, RebuildService.Scope(), "x") }.code)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.rebuild(admin(), reach(true), day.plusDays(3), day, s, "x") }.code)
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.rebuild(admin(), reach(true), day, LocalDate.parse("2026-10-07"), s, "x") }.code, "the future")
        assertEquals(ProblemCode.ERR_VALIDATION, assertFailsWith<ApiProblem> { svc.rebuild(admin(), reach(true), day.minusDays(100), day, s, "x") }.code, "more than 93 days")
        // A zone-scoped admin: national scope, another zone and a route of another zone are all outside the reach.
        val z1 = reach(false, zoneId("Z1"))
        assertEquals(ProblemCode.ERR_OUT_OF_SCOPE, assertFailsWith<ApiProblem> { svc.rebuild(admin(), z1, day, day, s, "x") }.code)
        assertEquals(ProblemCode.ERR_OUT_OF_SCOPE, assertFailsWith<ApiProblem> { svc.rebuild(admin(), z1, day, day, RebuildService.Scope(zoneIds = setOf(zoneId("Z2"))), "x") }.code)
        assertEquals(ProblemCode.ERR_OUT_OF_SCOPE, assertFailsWith<ApiProblem> { svc.rebuild(admin(), z1, day, day, RebuildService.Scope(routeIds = setOf(routeId("R3"))), "x") }.code)
        assertEquals(0, long("SELECT count(*) FROM app.dirty_key WHERE reason LIKE 'rebuild:%'"), "a refused rebuild queues nothing")
        // Inside the reach: only Z1's routes (R1, R2), as the route belonged to the zone on that date.
        assertEquals(2, svc.rebuild(admin(), z1, day, day, RebuildService.Scope(zoneIds = setOf(zoneId("Z1"))), "z1 only").routeDays)
    }

    // ---------- the nightly reconcile ----------

    @Test
    fun theReconcileFindsADifferenceKeepsOneOpenFindingReEnqueuesAndClosesItAfterTheRebuild() {
        val w = worker(); w.runUntilIdle()
        val clean = Reconciler(fresh.db, clock).run()
        assertEquals(0, clean.findings, "clean run: $clean"); assertTrue(clean.checked > 0, "checked: $clean; routes=${long("SELECT count(*) FROM dw.agg_daily_route")} on=${text("SELECT string_agg(DISTINCT business_date::text, ',') FROM dw.agg_daily_route")}")
        exec("UPDATE dw.agg_daily_route SET net_mtk = net_mtk + 500 WHERE route_id = ${routeId("R1")} AND business_date = '$day'")
        val first = Reconciler(fresh.db, clock).run()
        // The route disagrees with its memos (500); its zone-day no longer equals the sum of its route-days (-500).
        assertEquals(2, first.newFindings); assertEquals(2, first.reEnqueued); assertEquals(0, first.alerts)
        assertEquals(-500, long("SELECT diff FROM dw.agg_reconcile WHERE grain = 'route' AND key_id = ${routeId("R1")} AND metric = 'net_mtk' AND resolved_at IS NULL"))
        assertEquals(1, long("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = ${routeId("R1")} AND reason = 'reconcile'"))
        // While the key is queued the finding is not counted again and not closed.
        assertEquals(0, Reconciler(fresh.db, clock).run().findings)
        assertEquals(2, long("SELECT count(*) FROM dw.agg_reconcile WHERE resolved_at IS NULL"))
        w.runUntilIdle()                                                  // the rebuild repairs the route and restates it as 'reconcile'
        assertEquals(1, long("SELECT count(*) FROM dw.agg_restatement_log WHERE grain = 'route' AND key1 = ${routeId("R1")} AND reason = 'reconcile'"))
        val after = Reconciler(fresh.db, clock).run()
        assertEquals(0, after.findings); assertEquals(2, after.resolved)
        assertEquals(0, long("SELECT count(*) FROM dw.agg_reconcile WHERE resolved_at IS NULL"))
        assertEquals(2, long("SELECT count(*) FROM dw.agg_reconcile"), "the closed findings stay as history")
    }

    @Test
    fun aDifferenceThatSurvivesASecondRunRaisesAnAlert() {
        val w = worker(); w.runUntilIdle()
        exec("UPDATE dw.agg_daily_route SET gross_mtk = gross_mtk - 100 WHERE route_id = ${routeId("R2")} AND business_date = '$day'")
        assertEquals(0, Reconciler(fresh.db, clock).run().alerts)
        exec("DELETE FROM app.dirty_key")                                 // the re-enqueue was lost (a dead or cleared key)
        val second = Reconciler(fresh.db, clock).run()
        assertTrue(second.alerts >= 1, "second: $second")
        assertEquals(2, long("SELECT consecutive_runs FROM dw.agg_reconcile WHERE grain = 'route' AND key_id = ${routeId("R2")} AND metric = 'gross_mtk' AND resolved_at IS NULL"))
        assertEquals(1, long("SELECT count(*) FROM dw.agg_reconcile WHERE grain = 'route' AND key_id = ${routeId("R2")} AND metric = 'gross_mtk' AND resolved_at IS NULL"), "one open finding per key and metric")
    }

    @Test
    fun theNightlyStepRunsOncePerDhakaDateAndNotBeforeOneOClock() {
        val w = worker(); w.runUntilIdle()
        now.set(Instant.parse("2026-10-05T18:30:00Z"))                    // 00:30 on 6 Oct in Dhaka
        assertNull(w.reconcileIfDue())
        now.set(Instant.parse("2026-10-05T19:30:00Z"))                    // 01:30
        assertNotNull(w.reconcileIfDue())
        assertNull(worker("w2").reconcileIfDue(), "another replica loses")
        now.set(Instant.parse("2026-10-06T19:30:00Z"))                    // the next Dhaka date
        assertNotNull(worker("w2").reconcileIfDue())
    }

    // ---------- back-off, dead state, re-drive ----------

    @Test
    fun aFailingKeyBacksOffGoesDeadAfterEightAttemptsAndAReDriveRevivesIt() {
        val w = worker()
        var failing = true
        w.beforeRelease = { kind, _, _ -> if (failing && kind == "route_day_agg") error("boom") }
        exec("DELETE FROM app.dirty_key")
        exec("SELECT app.mark_dirty('route_day_agg', ${routeId("R1")}, DATE '$day', 'test')")
        for (attempt in 1..8) {
            assertEquals(0, w.drain(), "attempt $attempt fails")
            assertEquals(attempt.toLong(), long("SELECT attempts FROM app.dirty_key WHERE subject_id = ${routeId("R1")}"))
            if (attempt < 8) {
                assertEquals(0L, long("SELECT count(*) FROM app.dirty_key WHERE subject_id = ${routeId("R1")} AND dead_at IS NOT NULL"))
                assertTrue(text("SELECT last_error FROM app.dirty_key WHERE subject_id = ${routeId("R1")}").contains("boom"), "last_error=" + text("SELECT coalesce(last_error, '<null>') FROM app.dirty_key WHERE subject_id = ${routeId("R1")}"))
                assertEquals(0, w.drain(), "backed off: not claimed again at the same instant")
                assertEquals(attempt.toLong(), long("SELECT attempts FROM app.dirty_key WHERE subject_id = ${routeId("R1")}"), "no new attempt while backed off")
                now.set(now.get().plusSeconds(1000))                      // past any back-off (cap 900 s)
            }
        }
        assertEquals(1, long("SELECT count(*) FROM app.dirty_key WHERE subject_id = ${routeId("R1")} AND dead_at IS NOT NULL"))
        assertEquals(1, long("SELECT dead_keys FROM app.v_dirty_key_dead WHERE kind = 'route_day_agg'"))
        now.set(now.get().plusSeconds(100_000))
        assertEquals(0, w.drain(), "a dead key is not claimed")
        failing = false
        assertEquals(0, w.drain(), "still dead after the cause is fixed")
        assertEquals(1, w.redrive("route_day_agg", routeId("R1"), day))
        assertEquals(2, w.drain(), "the re-driven key rebuilds, and its zone-day follows in the same pass")
        assertEquals(0, long("SELECT count(*) FROM app.dirty_key WHERE subject_id = ${routeId("R1")} AND kind = 'route_day_agg'"))
    }

    @Test
    fun theBackOffGrowsExponentially() {
        val w = worker()
        w.beforeRelease = { kind, _, _ -> if (kind == "route_day_agg") error("boom") }
        exec("DELETE FROM app.dirty_key")
        exec("SELECT app.mark_dirty('route_day_agg', ${routeId("R1")}, DATE '$day', 'test')")
        val waits = mutableListOf<Long>()
        repeat(4) {
            w.drain()
            waits += long("SELECT extract(epoch FROM not_before - TIMESTAMPTZ '${now.get()}')::bigint FROM app.dirty_key WHERE subject_id = ${routeId("R1")}")
            now.set(now.get().plusSeconds(waits.last() + 1))
        }
        assertEquals(listOf(5L, 10L, 20L, 40L), waits)
    }

    @Test
    fun newDataRevivesADeadKey() {
        val w = worker()
        w.beforeRelease = { kind, _, _ -> if (kind == "route_day_agg") error("boom") }
        exec("DELETE FROM app.dirty_key")
        exec("SELECT app.mark_dirty('route_day_agg', ${routeId("R1")}, DATE '$day', 'test')")
        exec("UPDATE app.dirty_key SET attempts = 7, not_before = NULL")
        w.drain()
        assertEquals(1, long("SELECT count(*) FROM app.dirty_key WHERE dead_at IS NOT NULL"))
        exec("SELECT app.mark_dirty('route_day_agg', ${routeId("R1")}, DATE '$day', 'memo.created')")
        assertEquals(0, long("SELECT count(*) FROM app.dirty_key WHERE dead_at IS NOT NULL"))
    }
}
