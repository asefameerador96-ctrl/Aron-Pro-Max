package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** D-ROLES-02: the portal-level gate of the guard as a role x method x path table (docs/34 s1). */
class PortalLevelsTest {
    private fun p(role: Role, flavour: String = "web") = AronPrincipal(
        1, "u", role, 1, Audience.API, null, if (flavour == "web") null else "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10",
        flavour, emptyList(), false, listOf("pwd"), "j", Instant.EPOCH,
    )

    /** null = passes the gate (the handler decides), otherwise the problem code. */
    private fun outcome(role: Role, method: String, path: String, flavour: String = "web"): ProblemCode? =
        try { PortalLevels.check(p(role, flavour), method, path); null } catch (e: ApiProblem) { e.code }

    @Test
    fun theTable() {
        val forbidden = ProblemCode.ERR_FORBIDDEN
        val rows = listOf(
            // NATIONAL and the retired TOP: every read passes; writes only for their own session and report queries.
            Triple(Role.NATIONAL, "GET /v1/admin/users", null), Triple(Role.NATIONAL, "HEAD /v1/outlets", null),
            Triple(Role.NATIONAL, "POST /v1/reports/sales-summary/query", null), Triple(Role.NATIONAL, "POST /v1/auth/logout", null),
            Triple(Role.NATIONAL, "POST /v1/auth/change-password", null), Triple(Role.NATIONAL, "POST /v1/client-errors", null),
            Triple(Role.NATIONAL, "POST /v1/admin/notifications", forbidden), Triple(Role.NATIONAL, "PATCH /v1/admin/users/5", forbidden),
            Triple(Role.NATIONAL, "POST /v1/tasks", forbidden), Triple(Role.NATIONAL, "PUT /v1/admin/users/5/scope", forbidden),
            Triple(Role.NATIONAL, "DELETE /v1/admin/users/5", forbidden), Triple(Role.TOP, "POST /v1/admin/notifications", forbidden),
            Triple(Role.TOP, "GET /v1/dashboards/summary", null),
            // Crafted paths: encoded segments and dot segments are judged as the router would, never looser.
            Triple(Role.NATIONAL, "POST /v1/reports/x/query/../../admin/users", forbidden),
            Triple(Role.NATIONAL, "POST /v1/%61uth/logout", null), Triple(Role.NATIONAL, "POST //v1//reports/k/query", null),
            Triple(Role.NATIONAL, "POST /v1/reports/a%2Fb/query", forbidden), Triple(Role.NATIONAL, "POST /v1/reports/k/query/extra", forbidden),
            Triple(Role.NATIONAL, "POST /v1/authx/logout", forbidden), Triple(Role.NATIONAL, "POST /v1/client-errors/1", forbidden),
            Triple(Role.NATIONAL, "POST /v1/auth/some-future-route", forbidden), Triple(Role.BRAND, "DELETE /v1/auth/logout", forbidden),
            Triple(Role.BRAND, "GET /V1/ME", forbidden), Triple(Role.NATIONAL, "POST /v1/reports/k/query;x=1/../../admin", forbidden),
            // BRAND: its own session only until the brand-filtered reads are built (row 3).
            Triple(Role.BRAND, "GET /v1/me", null), Triple(Role.BRAND, "POST /v1/auth/logout", null),
            Triple(Role.BRAND, "GET /v1/outlets", forbidden), Triple(Role.BRAND, "GET /v1/admin/users", forbidden),
            Triple(Role.BRAND, "GET /v1/me/../admin/users", forbidden), Triple(Role.BRAND, "GET /v1/me/x", forbidden),
            // Everyone else passes to the handlers unchanged.
            Triple(Role.TSO, "POST /v1/tasks", null), Triple(Role.ADMIN, "POST /v1/admin/users", null), Triple(Role.SUPERADMIN, "PUT /v1/admin/x", null),
            Triple(Role.ANALYST, "GET /v1/reports", null), Triple(Role.SUPPORT, "POST /v1/admin/device-otps", null),
        )
        val wrong = rows.mapNotNull { (role, call, want) ->
            val (m, path) = call.split(' ', limit = 2)
            val got = outcome(role, m, path)
            if (got != want) "$role $call: want $want, got $got" else null
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }

    @Test
    fun aPhoneTokenServesOnlyItsFlavoursRoleTheUploadGrantIncluded() {
        for (r in listOf(Role.SR, Role.AMO, Role.TSO)) assertNull(outcome(r, "POST", "/v1/sync/batch", r.wire.lowercase()))
        // Checker B1: a role change does not ride an old phone token.
        for (r in Role.entries - Role.TSO) assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(r, "GET", "/v1/me", "tso"), "$r on a tso token")
        assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(Role.BRAND, "GET", "/v1/reports", "sr"))
        // Checker round 2 (B1-bis): an upload token of a non-field role never reaches sync/batch with that role's reach.
        for (r in Role.entries - Role.SR) {
            val upload = p(r, "sr").copy(audience = Audience.UPLOAD)
            assertEquals(ProblemCode.ERR_FORBIDDEN, try { PortalLevels.check(upload, "POST", "/v1/sync/batch"); null } catch (e: ApiProblem) { e.code }, "$r upload on an sr phone")
        }
        PortalLevels.check(p(Role.SR, "sr").copy(audience = Audience.UPLOAD), "POST", "/v1/sync/batch")
        assertEquals("/v1/auth/logout", PortalLevels.normalise("/v1/%61uth//logout"))
        assertNull(PortalLevels.normalise("/v1/me/.."))
    }
}
