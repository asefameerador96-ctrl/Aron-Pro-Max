package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-037 (T1): typed scoped values with effective date, reason and risk class, stamped with a version. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigServiceTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private val admin get() = env.principal("admin1001", Role.ADMIN)
    private val sa1 get() = env.principal("superadmin1001", Role.SUPERADMIN)
    private val sa2 get() = env.principal("superadmin1002", Role.SUPERADMIN)
    private var zone = 0L
    private var outlets = listOf<Long>()
    private var routes = listOf<Long>()
    private var nextOutlet = 0
    private var nextRoute = 0
    /** Each test takes its own outlet and route so the tests do not share state or depend on order. */
    private fun freshOutlet() = outlets[nextOutlet++]
    private fun freshRoute() = routes[nextRoute++]

    @BeforeAll
    fun setUp() {
        env = SeededConfigDb()
        svc = ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock)
        zone = env.one("SELECT id FROM app.zone ORDER BY id LIMIT 1")!!.toLong()
        outlets = env.fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.outlet WHERE zone_id = $zone ORDER BY id LIMIT 30").mapTo(Long::class.java).list() }
        routes = env.fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.route ORDER BY id").mapTo(Long::class.java).list() }
    }

    @AfterAll
    fun tearDown() = env.close()

    private fun item(key: String, type: String, id: Long, value: Any?, from: String? = null, to: String? = null) = ConfigChangeItemIn(
        key, type, id, when (value) { null -> JsonNull; is Int -> JsonPrimitive(value); is Boolean -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) }, from, to,
    )
    private fun req(vararg i: ConfigChangeItemIn, reason: String = "Dense market, widen the radius") = ConfigChangeRequestIn(reason, false, i.toList())
    private fun code(f: () -> Unit) = assertFailsWith<ApiProblem>(block = f).code

    @Test
    fun outletOverrideIsC1AppliedAtOnceStampedAndResolvesAboveTheGlobalValue() {
        val outlet = freshOutlet()
        val before = svc.currentVersion()
        val c = svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, 130)), null, null)
        assertEquals("applied", c.status); assertEquals(1, c.risk_class); assertEquals(before + 1, c.config_version)
        val r = svc.resolve("cfg.geo.radius_m", "outlet", outlet, clock.now())
        assertEquals(130, r.value.jsonPrimitive.int); assertEquals("outlet", r.scope_type); assertEquals(before + 1, r.config_version)
        // The zone has no row of its own: its resolution is the registry default or global value, not the outlet's.
        assertTrue(svc.resolve("cfg.geo.radius_m", "zone", zone, clock.now()).scope_type in setOf("default", "global"))
        assertEquals(before + 1, svc.currentVersion())
    }

    @Test
    fun replacingAValueClosesTheOldRowAndKeepsItInHistory() {
        val outlet = freshOutlet()
        svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, 140)), null, null)
        clock.advance(30)
        val c = svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, 90)), null, null)
        assertEquals(JsonPrimitive(140), c.changes.single().old_value)
        val hist = svc.values("cfg.geo.radius_m", "outlet", outlet, true, 50, null).items
        assertEquals(setOf(140, 90), hist.map { it.value.jsonPrimitive.int }.toSet())
        val open = svc.values("cfg.geo.radius_m", "outlet", outlet, false, 50, null).items
        assertEquals(listOf(90), open.filter { it.effective_to == null }.map { it.value.jsonPrimitive.int })
        assertTrue(hist.first { it.value.jsonPrimitive.int == 140 }.superseded_in_version != null)
    }

    @Test
    fun removingAnOverrideFallsBackToTheNextLevel() {
        val outlet = freshOutlet()
        svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, 120)), null, null)
        clock.advance(5)
        val c = svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, null)), null, null)
        assertEquals("applied", c.status)
        assertTrue(svc.resolve("cfg.geo.radius_m", "outlet", outlet, clock.now()).scope_type != "outlet")
    }

    @Test
    fun validationRefusesBadKeyScopeBoundsTypeAndReason() {
        val outlet = freshOutlet()
        assertEquals(ProblemCode.ERR_CFG_UNKNOWN_KEY, code { svc.create(admin, req(item("cfg.geo.nope", "global", 0, 1)), null, null) })
        assertEquals(ProblemCode.ERR_CFG_SCOPE_NOT_ALLOWED, code { svc.create(admin, req(item("cfg.geo.radius_min_m", "zone", zone, 30)), null, null) })
        assertEquals(ProblemCode.ERR_CFG_OUT_OF_BOUNDS, code { svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, 10)), null, null) }) // below radius_min_m 20
        assertEquals(ProblemCode.ERR_CFG_OUT_OF_BOUNDS, code { svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, 2500)), null, null) }) // above radius_max_m 2000
        assertEquals(ProblemCode.ERR_VALIDATION, code { svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, "wide")), null, null) })
        assertEquals(ProblemCode.ERR_CFG_REASON_REQUIRED, code { svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, 100), reason = "short"), null, null) })
        assertEquals(ProblemCode.ERR_VALIDATION, code { svc.create(admin, req(item("cfg.geo.radius_m", "zone", 0, 100)), null, null) }) // scope id 0 only for global
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(env.principal("tso1001", Role.TSO), req(item("cfg.geo.radius_m", "outlet", outlet, 100)), null, null) })
    }

    @Test
    fun c2IsScheduledCancellableAndAppliedByTheTickAfterTheDelay() {
        val route = freshRoute()
        val c = svc.create(admin, req(item("cfg.geo.radius_m", "route", route, 120)), null, null)
        assertEquals("scheduled", c.status); assertEquals(2, c.risk_class); assertNull(c.config_version)
        assertTrue(svc.resolve("cfg.geo.radius_m", "route", route, clock.now()).scope_type != "route")
        clock.advance(11 * 60)
        assertTrue(svc.applyDue() >= 1)
        val done = svc.change(c.change_id)
        assertEquals("applied", done.status)
        assertEquals("route", svc.resolve("cfg.geo.radius_m", "route", route, clock.now()).scope_type)
        val route2 = freshRoute()
        val c2 = svc.create(admin, req(item("cfg.geo.radius_m", "route", route2, 110)), null, null)
        val cancelled = svc.decide(admin, c2.change_id, ConfigDecisionIn("cancel"), null)
        assertEquals("cancelled", cancelled.status)
        clock.advance(11 * 60)
        svc.applyDue()
        assertTrue(svc.resolve("cfg.geo.radius_m", "route", route2, clock.now()).scope_type != "route")
    }

    @Test
    fun c3NeedsASecondSuperadminOutsideTheFreezeWindows() {
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(admin, req(item("cfg.geo.radius_m", "global", 0, 120)), null, null) })
        val c = svc.create(sa1, req(item("cfg.geo.radius_m", "global", 0, 120)), null, null)
        assertEquals("pending_approval", c.status); assertEquals(3, c.risk_class)
        assertEquals(ProblemCode.ERR_CFG_SELF_APPROVAL, code { svc.decide(sa1, c.change_id, ConfigDecisionIn("approve"), null) })
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.decide(admin, c.change_id, ConfigDecisionIn("approve"), null) })
        clock.setDhaka(8, 0)
        assertEquals(ProblemCode.ERR_CFG_FREEZE_WINDOW, code { svc.decide(sa2, c.change_id, ConfigDecisionIn("approve"), null) })
        clock.setDhaka(12, 0)
        val done = svc.decide(sa2, c.change_id, ConfigDecisionIn("approve"), null)
        assertEquals("applied", done.status); assertEquals(sa2.userId, done.approved_by)
        assertEquals(ProblemCode.ERR_REQUEST_STATE, code { svc.decide(sa2, c.change_id, ConfigDecisionIn("approve"), null) })
        clock.advance(60)
        assertEquals(120, svc.resolve("cfg.geo.radius_m", "global", 0, clock.now()).value.jsonPrimitive.int)
    }

    /** Q3/Q4 option B: loosening MFA roles or the price-change rail needs a second SUPERADMIN (C3); tightening or no change stays C2. */
    @Test
    fun loosenedSecurityControlsNeedASecondApproverAndTightenedOnesDoNot() {
        fun roles(vararg r: String) = ConfigChangeItemIn("cfg.auth.mfa_required_roles", "global", 0, kotlinx.serialization.json.JsonArray(r.map { JsonPrimitive(it) }), null, null)
        fun mfa(vararg r: String) = req(roles(*r), reason = "Review of the admin second factor policy")
        fun pct(v: Int) = req(item("cfg.price.max_change_pct", "global", 0, v), reason = "Review of the price change rail threshold")
        // Loosening: one ADMIN cannot even ask; a SUPERADMIN's request waits for a second SUPERADMIN.
        for (r in listOf(mfa(), mfa("ADMIN", "SUPERADMIN"), pct(100), pct(16))) {
            assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(admin, r, null, null) }, r.changes.toString())
            val c = svc.create(sa1, r, null, null)
            assertEquals(3, c.risk_class, r.changes.toString()); assertEquals("pending_approval", c.status)
            assertEquals(ProblemCode.ERR_CFG_SELF_APPROVAL, code { svc.decide(sa1, c.change_id, ConfigDecisionIn("approve"), null) })
            svc.decide(sa1, c.change_id, ConfigDecisionIn("cancel"), null)
        }
        // Tightening, or the same value: C2, scheduled by one ADMIN after the delay.
        for (r in listOf(mfa("ADMIN", "SUPERADMIN", "SUPPORT", "TSO"), mfa("ADMIN", "SUPERADMIN", "SUPPORT"), pct(10), pct(15))) {
            val c = svc.create(admin, r, null, null)
            assertEquals(2, c.risk_class, r.changes.toString()); assertEquals("scheduled", c.status)
            svc.decide(admin, c.change_id, ConfigDecisionIn("cancel"), null)
        }
    }

    /** Checker B1 and B2 on option B: a window that ends on a looser value is C3; a change cannot undo one that is still waiting. */
    @Test
    fun aWindowThatEndsLooserIsC3AndAWaitingChangeCannotBeUndone() {
        // The config timeline needs a clock that never goes back (setDhaka alone may jump backwards past rows already written).
        fun noonAhead() { val was = clock.now(); clock.setDhaka(12, 0); if (clock.now().isBefore(was)) clock.advance(24 * 3600) }
        fun pct(v: Int, to: String? = null) = req(item("cfg.price.max_change_pct", "global", 0, v, to = to), reason = "Review of the price change rail threshold")
        // B2: an ADMIN's tightening (15 -> 10) is scheduled; nobody can queue another change of the key behind it.
        val tight = svc.create(admin, pct(10), null, null)
        assertEquals("scheduled", tight.status); assertEquals(2, tight.risk_class)
        assertEquals(ProblemCode.ERR_REQUEST_STATE, code { svc.create(admin, pct(15), null, null) })
        assertEquals(ProblemCode.ERR_REQUEST_STATE, code { svc.create(sa1, pct(20), null, null) })
        clock.advance(11 * 60); svc.applyDue()
        assertEquals("applied", svc.change(tight.change_id).status)
        assertEquals(10, svc.resolve("cfg.price.max_change_pct", "global", 0, clock.now()).value.jsonPrimitive.int)
        // B1: a window (same value or tighter) falls back to the default 15 when it ends: C3, so one ADMIN cannot.
        val end = clock.now().plusSeconds(3 * 3600).toString()
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(admin, pct(10, to = end), null, null) })
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(admin, pct(5, to = end), null, null) })
        val windowed = svc.create(sa1, pct(5, to = end), null, null)
        assertEquals(3, windowed.risk_class); assertEquals("pending_approval", windowed.status)
        svc.decide(sa1, windowed.change_id, ConfigDecisionIn("cancel"), null)
        // Back to the default, which is a loosening (10 -> 15): C3, two SUPERADMINs.
        val back = svc.create(sa1, pct(15), null, null)
        assertEquals(3, back.risk_class)
        noonAhead()
        assertEquals("applied", svc.decide(sa2, back.change_id, ConfigDecisionIn("approve"), null).status)
        // Checker N1: two SUPERADMINs approve a temporary 25 until T; one ADMIN's 'same value, no end' (or a longer window)
        // would make it permanent, so it is C3 and refused to the ADMIN.
        val t = clock.now().plusSeconds(6 * 3600).toString()
        val temp = svc.create(sa1, pct(25, to = t), null, null)
        assertEquals(3, temp.risk_class)
        assertEquals("applied", svc.decide(sa2, temp.change_id, ConfigDecisionIn("approve"), null).status)
        clock.advance(60)
        assertEquals(25, svc.resolve("cfg.price.max_change_pct", "global", 0, clock.now()).value.jsonPrimitive.int)
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(admin, pct(25), null, null) }, "same value, open-ended")
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(admin, pct(25, to = clock.now().plusSeconds(12 * 3600).toString()), null, null) }, "a longer window")
        // Any change of the guarded keys with an end is C3 (checker B3: its tail is judged against rows that start later),
        // even a shorter, tighter window inside T.
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(admin, pct(20, to = clock.now().plusSeconds(3600).toString()), null, null) }, "a shorter window")
        // B3: a windowed tightening by one ADMIN (it could cut a row starting during the C2 delay and fall back to 15 after its end).
        assertEquals(ProblemCode.ERR_FORBIDDEN, code { svc.create(admin, pct(5, to = clock.now().plusSeconds(48 * 3600).toString()), null, null) }, "a windowed tightening")
        // Clean up: back to the default once the window has passed.
        clock.advance(7 * 3600)
        assertEquals(15, svc.resolve("cfg.price.max_change_pct", "global", 0, clock.now()).value.jsonPrimitive.int)
    }

    @Test
    fun radiusAbove150ThatGrowsTheValueEscalatesToC3AtAnyLevel() {
        val outlet = freshOutlet()
        val c = svc.create(sa1, req(item("cfg.geo.radius_m", "outlet", outlet, 400)), null, null)
        assertEquals(3, c.risk_class); assertEquals("pending_approval", c.status)
        svc.decide(sa1, c.change_id, ConfigDecisionIn("cancel"), null)
    }

    @Test
    fun aRejectedChangeChangesNothing() {
        val v = svc.currentVersion()
        val c = svc.create(sa1, req(item("cfg.geo.radius_m", "global", 0, 130)), null, null)
        assertEquals("rejected", svc.decide(sa2, c.change_id, ConfigDecisionIn("reject", "no"), null).status)
        assertEquals(v, svc.currentVersion())
    }

    @Test
    fun futureDatedValueIsInvisibleNowAndWinsFromItsInstant() {
        val outlet = freshOutlet()
        val from = clock.now().plusSeconds(3 * 3600).toString()
        val c = svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, 77, from = from)), null, null)
        assertEquals("applied", c.status)
        val now = svc.resolve("cfg.geo.radius_m", "outlet", outlet, clock.now())
        assertTrue(now.value.jsonPrimitive.int != 77)
        assertEquals(77, svc.resolve("cfg.geo.radius_m", "outlet", outlet, clock.now().plusSeconds(4 * 3600)).value.jsonPrimitive.int)
    }

    @Test
    fun theSameIdempotencyKeyReturnsTheFirstChangeAndStoresOne() {
        val key = UUID.randomUUID()
        val a = svc.create(admin, req(item("cfg.sys.schedule_horizon_days", "global", 0, 8)), key, null)
        val v = svc.currentVersion()
        val b = svc.create(admin, req(item("cfg.sys.schedule_horizon_days", "global", 0, 8)), key, null)
        assertEquals(a.change_id, b.change_id); assertEquals(v, svc.currentVersion())
    }

    /** Replay-first: a retry after the effective_from passed answers the stored change; the same key with other content is 409. */
    @Test
    fun aLateReplayOfAFutureDatedChangeAnswersTheStoredChange() {
        val key = UUID.randomUUID()
        val from = clock.now().plusSeconds(30).toString()
        val body = req(item("cfg.sys.schedule_horizon_days", "global", 0, 9).copy(effective_from = from))
        val a = svc.create(admin, body, key, null)
        clock.advance(120)                                                     // the first answer was lost; the retry comes two minutes later
        assertEquals(a.change_id, svc.create(admin, body, key, null).change_id)
        assertEquals(ProblemCode.ERR_CONFLICT, assertFailsWith<ApiProblem> { svc.create(admin, req(item("cfg.sys.schedule_horizon_days", "global", 0, 10)), key, null) }.code)
    }

    @Test
    fun everyWriteIsAuditedAndTheChainVerifies() {
        assertTrue(env.one("SELECT count(*) FROM app.audit_log WHERE entity IN ('cfg_change','cfg_version')")!!.toInt() >= 4)
        assertNull(env.one("SELECT app.audit_verify()"))
        assertEquals("applied", svc.changes("applied", null, null, null, 5, null).items.first().status)
    }

    @Test
    fun blastRadiusCountsTheZonesRoutesOutletsAndDevicesATargetTouches() {
        val c = svc.create(sa1, req(item("cfg.geo.max_accuracy_m", "zone", zone, 125)), null, null)
        assertTrue(c.blast_radius.zones == 1 && c.blast_radius.outlets >= 1)
        svc.decide(sa1, c.change_id, ConfigDecisionIn("cancel"), null)
    }
}
