package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** D-AUTHZ-QA-Q4: `cfg_key.editor_permission` is enforced per request through `app.role_grant_map` (seed V0085). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditorPermissionTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private val admin get() = env.principal("admin1001", Role.ADMIN)
    private val sa1 get() = env.principal("superadmin1001", Role.SUPERADMIN)
    private val sa2 get() = env.principal("superadmin1002", Role.SUPERADMIN)
    private val tso get() = env.principal("tso1001", Role.TSO)
    private var zone = 0L

    @BeforeAll
    fun setUp() {
        env = SeededConfigDb()
        zone = env.one("SELECT zone_id FROM app.outlet ORDER BY id OFFSET 5 LIMIT 1")!!.toLong()
        svc = ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock) { _, z -> z == zone }
    }

    @AfterAll
    fun tearDown() = env.close()

    private fun sql(s: String) = env.fresh.db.jdbi.useHandle<Exception> { it.execute(s) }
    private fun revoke(role: String, perm: String) = sql("DELETE FROM app.role_grant_map WHERE role = '$role' AND permission = '$perm'")
    private fun grant(role: String, perm: String) = sql("INSERT INTO app.role_grant_map (role, permission) VALUES ('$role', '$perm') ON CONFLICT DO NOTHING")
    private fun refused(f: () -> Unit) = assertFailsWith<ApiProblem>(block = f)

    /** No change of the MFA roles (a security key, C2) and an outlet radius (a field key, C1). */
    private fun security() = ConfigChangeRequestIn(
        "Review of the admin second factor policy", false,
        listOf(ConfigChangeItemIn("cfg.auth.mfa_required_roles", "global", 0, JsonArray(listOf("ADMIN", "SUPERADMIN", "SUPPORT").map { JsonPrimitive(it) }), null, null)),
    )
    private fun field(outlet: Long) = ConfigChangeRequestIn("Dense market, widen the radius", false, listOf(ConfigChangeItemIn("cfg.geo.radius_m", "outlet", outlet, JsonPrimitive(130), null, null)))
    private fun outlet(n: Int) = env.one("SELECT id FROM app.outlet ORDER BY id OFFSET $n LIMIT 1")!!.toLong()

    @Test
    fun theSeedGrantsEveryEditPermissionToAdminAndSuperadminOnly() {
        val missing = env.one(
            "SELECT string_agg(r.role || ':' || k.p, ',') FROM (VALUES ('ADMIN'), ('SUPERADMIN')) r(role) CROSS JOIN (SELECT DISTINCT editor_permission p FROM app.cfg_key) k " +
                "WHERE NOT EXISTS (SELECT 1 FROM app.role_grant_map g WHERE g.role = r.role AND g.permission = k.p)",
        )
        assertEquals(null, missing, "a cfg.edit.* permission without its seed grant (add it in the migration that introduces it)")
        assertEquals("0", env.one("SELECT count(*) FROM app.role_grant_map WHERE permission LIKE 'cfg.edit.%' AND role NOT IN ('ADMIN', 'SUPERADMIN')"))
    }

    @Test
    fun aRevokedGrantRefusesTheChangeAtOnceAndOtherKeysStayEditable() {
        try {
            revoke("ADMIN", "cfg.edit.security")
            val e = refused { svc.create(admin, security(), null, null) }
            assertEquals(ProblemCode.ERR_FORBIDDEN, e.code); assertTrue("cfg.edit.security" in e.message!!, e.message)
            assertEquals("applied", svc.create(admin, field(outlet(0)), null, null).status, "a field key needs only cfg.edit.field")
            // One item without the grant refuses the whole change set.
            val mixed = ConfigChangeRequestIn("Review of the admin second factor policy", false, security().changes + field(outlet(1)).changes)
            assertEquals(ProblemCode.ERR_FORBIDDEN, refused { svc.create(admin, mixed, null, null) }.code)
            assertEquals("0", env.one("SELECT count(*) FROM app.cfg_change WHERE requested_by = ${admin.userId} AND items::text LIKE '%mfa_required_roles%'"))
        } finally { grant("ADMIN", "cfg.edit.security") }
        assertEquals("scheduled", svc.create(admin, security(), null, null).status, "the grant back: the C2 change is accepted again")
        // The SUPERADMIN keeps its own grant: revoking ADMIN's changes nothing for it.
        revoke("ADMIN", "cfg.edit.field")
        try {
            assertEquals(ProblemCode.ERR_FORBIDDEN, refused { svc.create(admin, field(outlet(2)), null, null) }.code)
            assertEquals("applied", svc.create(sa1, field(outlet(2)), null, null).status)
        } finally { grant("ADMIN", "cfg.edit.field") }
    }

    @Test
    fun theApproverOfAC3ChangeNeedsTheGrantToo() {
        val c = svc.create(sa1, ConfigChangeRequestIn("Raise the national radius after review", false, listOf(ConfigChangeItemIn("cfg.geo.radius_m", "global", 0, JsonPrimitive(110), null, null))), null, null)
        assertEquals("pending_approval", c.status)
        revoke("SUPERADMIN", "cfg.edit.field")
        try {
            assertEquals(ProblemCode.ERR_FORBIDDEN, refused { svc.decide(sa2, c.change_id, ConfigDecisionIn("approve"), null) }.code)
            assertEquals("pending_approval", env.one("SELECT status FROM app.cfg_change WHERE change_id = ${c.change_id}"))
        } finally { grant("SUPERADMIN", "cfg.edit.field") }
        assertEquals("applied", svc.decide(sa2, c.change_id, ConfigDecisionIn("approve"), null).status)
    }

    @Test
    fun aRefusedAdopterLeavesTheTsoProposalPending() {
        // The TSO proposes under its own rule (no grant needed); the ADMIN adopting it needs cfg.edit.field.
        val prop = svc.create(tso, field(outlet(5)), null, null)
        assertEquals("pending_approval", prop.status)
        revoke("ADMIN", "cfg.edit.field")
        try {
            assertEquals(ProblemCode.ERR_FORBIDDEN, refused { svc.decide(admin, prop.change_id, ConfigDecisionIn("adopt", "ok"), null) }.code)
            assertEquals("pending_approval", env.one("SELECT status FROM app.cfg_change WHERE change_id = ${prop.change_id}"), "not cancelled by a refused adopt")
        } finally { grant("ADMIN", "cfg.edit.field") }
        assertEquals("applied", svc.decide(admin, prop.change_id, ConfigDecisionIn("adopt", "ok"), null).status)
    }
}
