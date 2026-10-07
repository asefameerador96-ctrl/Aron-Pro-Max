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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** F-API-061 (adopt, break-glass) and F-API-062 (revert, rollback-to create a NEW version and delete nothing). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigWorkflowTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private var zone = 0L
    private var outlets = listOf<Long>()
    private var n = 0
    private val admin get() = env.principal("admin1001", Role.ADMIN)
    private val sa1 get() = env.principal("superadmin1001", Role.SUPERADMIN)
    private val tso get() = env.principal("tso1001", Role.TSO)

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        zone = env.one("SELECT id FROM app.zone ORDER BY id LIMIT 1")!!.toLong()
        svc = ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock) { _, z -> z == zone }
        outlets = env.fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.outlet ORDER BY id LIMIT 30").mapTo(Long::class.java).list() }
    }
    @AfterAll fun tearDown() = env.close()

    private fun set(who: com.aktcl.aron.backend.platform.AronPrincipal, key: String, type: String, id: Long, v: Int?) =
        svc.create(who, ConfigChangeRequestIn("Tuning the pilot values", false, listOf(ConfigChangeItemIn(key, type, id, v?.let { JsonPrimitive(it) } ?: JsonNull))), null, null).also { clock.advance(2) }
    private fun now() = clock.now()
    private fun radius(outlet: Long) = svc.resolve("cfg.geo.radius_m", "outlet", outlet, now()).value.jsonPrimitive.int

    @Test
    fun revertRestoresWhatTheVersionReplacedAndKeepsHistory() {
        val o = outlets[n++]
        set(admin, "cfg.geo.radius_m", "outlet", o, 110)
        val bad = set(admin, "cfg.geo.radius_m", "outlet", o, 140)
        assertEquals(140, radius(o))
        val r = svc.rollback(admin, bad.config_version!!, "revert_this_version", "Wrong value, restore the old one", null)
        assertEquals("applied", r.status); assertEquals(bad.config_version, r.is_revert_of)
        assertEquals(110, radius(o))
        val v = svc.versions(5, null).items.first()
        assertEquals("revert", v.kind); assertEquals(bad.config_version, v.is_revert_of)
        val hist = svc.values("cfg.geo.radius_m", "outlet", o, true, 50, null).items.map { it.value.jsonPrimitive.int }
        assertTrue(hist.containsAll(listOf(110, 140)) && hist.size >= 3)
    }

    @Test
    fun revertingTheFirstValueRemovesTheOverride() {
        val o = outlets[n++]
        val c = set(admin, "cfg.geo.radius_m", "outlet", o, 125)
        svc.rollback(admin, c.config_version!!, "revert_this_version", "Remove the first override again", null)
        assertTrue(svc.resolve("cfg.geo.radius_m", "outlet", o, now()).scope_type != "outlet")
    }

    @Test
    fun rollbackToAVersionRestoresTheWholeStateAsOfThen() {
        val a = outlets[n++]; val b = outlets[n++]
        val v1 = set(admin, "cfg.geo.radius_m", "outlet", a, 90)
        set(admin, "cfg.geo.radius_m", "outlet", a, 95)
        set(admin, "cfg.geo.radius_m", "outlet", b, 80)
        val r = svc.rollback(admin, v1.config_version!!, "rollback_to_this_version", "Back to the state of version one", null)
        assertEquals("applied", r.status)
        assertEquals(90, radius(a)); assertTrue(svc.resolve("cfg.geo.radius_m", "outlet", b, now()).scope_type != "outlet")
        assertEquals("rollback", svc.versions(1, null).items.single().kind)
        assertEquals(ProblemCode.ERR_CONFLICT, assertFailsWith<ApiProblem> { svc.rollback(admin, v1.config_version!!, "rollback_to_this_version", "Nothing left to roll back here", null) }.code)
        assertEquals(ProblemCode.ERR_NOT_FOUND, assertFailsWith<ApiProblem> { svc.rollback(admin, 99999, "revert_this_version", "Unknown version for sure", null) }.code)
    }

    @Test
    fun aTsoProposalIsPendingAndAdoptingItCreatesTheAdminsOwnChange() {
        val o = outlets[n++]
        val prop = set(tso, "cfg.geo.radius_m", "outlet", o, 120)
        assertEquals("pending_approval", prop.status)
        val before = radius(o)
        assertEquals(before, radius(o))
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { set(tso, "cfg.geo.fix_timeout_s", "zone", zone, 9) }.code)
        val outside = env.fresh.db.jdbi.withHandle<Long, Exception> { h -> h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-X', 'X', division_id FROM app.territory LIMIT 1"); h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-X', 'X', id FROM app.territory WHERE code = 'T-X'"); h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-X'").mapTo(Long::class.java).one() }
        assertEquals(ProblemCode.ERR_OUT_OF_SCOPE, assertFailsWith<ApiProblem> { set(tso, "cfg.geo.radius_m", "zone", outside, 120) }.code)
        val adopted = svc.decide(admin, prop.change_id, ConfigDecisionIn("adopt", "ok"), null)
        assertEquals(admin.userId, adopted.requested_by); assertEquals("applied", adopted.status)
        assertEquals("cancelled", svc.change(prop.change_id).status)
        clock.advance(2); assertEquals(120, radius(o))
    }

    @Test
    fun breakGlassNeedsSuperadminAndOnlyRestoresOrRestricts() {
        val o = outlets[n++]
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { svc.create(admin, ConfigChangeRequestIn("Emergency widen now please", true, listOf(ConfigChangeItemIn("cfg.geo.radius_m", "outlet", o, JsonPrimitive(200)))), null, null) }.code)
        // A key without a restrictive direction can only be restored, so a plain change is refused...
        env.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.cfg_key SET restrictive_dir = 'none' WHERE key = 'cfg.geo.radius_m'") }
        svc.resolver.invalidate()
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { svc.create(sa1, ConfigChangeRequestIn("Emergency change, not a restore", true, listOf(ConfigChangeItemIn("cfg.geo.radius_m", "outlet", o, JsonPrimitive(60)))), null, null) }.code)
        // ...with the direction the registry ships ('down', docs/requests/backend-admin-restrictive-dir.md), narrowing applies at once
        // and expires within break_glass_max_h; widening is still refused.
        env.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.cfg_key SET restrictive_dir = 'down' WHERE key = 'cfg.geo.radius_m'") }
        svc.resolver.invalidate()
        val c = svc.create(sa1, ConfigChangeRequestIn("Emergency narrowing at once", true, listOf(ConfigChangeItemIn("cfg.geo.radius_m", "outlet", o, JsonPrimitive(60)))), null, null)
        assertEquals("applied", c.status); clock.advance(2); assertEquals(60, radius(o))
        assertNotNull(c.changes.single().effective_to)
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { svc.create(sa1, ConfigChangeRequestIn("Emergency widen again here", true, listOf(ConfigChangeItemIn("cfg.geo.radius_m", "outlet", o, JsonPrimitive(90)))), null, null) }.code)
        clock.advance(5 * 3600)
        assertTrue(svc.resolve("cfg.geo.radius_m", "outlet", o, now()).scope_type != "outlet")
        env.one("SELECT count(*) FROM app.audit_log WHERE action = 'break_glass'").also { assertTrue(it!!.toInt() >= 1) }
    }
}
