package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** D-ROLES-02 row 2/6: role x module x access as a table (docs/34 s1); the gate of [AdminModules.check]. */
class AdminModulesTest {
    private fun p(role: Role, modules: Map<String, String>?, audience: String = Audience.API, flavour: String = "web") = AronPrincipal(
        1, "u", role, 1, audience, null, if (flavour == "web") null else "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10",
        flavour, emptyList(), false, listOf("pwd", "mfa"), "j", Instant.EPOCH, modules = modules,
    )

    private fun outcome(principal: AronPrincipal, method: String, path: String, live: Boolean = true): ProblemCode? =
        try { AdminModules.check(principal, method, path, live); null } catch (e: ApiProblem) { e.code }

    /** One read and one write route per module (a real route of the inventory). */
    private val samples: Map<String, Pair<String, String>> = mapOf(
        "masterdata" to ("GET /v1/admin/geo/zones" to "POST /v1/admin/calendar/holidays"),
        "routes" to ("GET /v1/admin/routes" to "PATCH /v1/admin/routes/7"),
        "outlets" to ("GET /v1/admin/outlets" to "POST /v1/admin/outlets/import"),
        "products" to ("GET /v1/admin/skus" to "PATCH /v1/admin/skus/3"),
        "prices" to ("GET /v1/admin/prices" to "POST /v1/admin/prices"),
        "users" to ("GET /v1/admin/users" to "PUT /v1/admin/users/9/access"),
        "targets" to ("GET /v1/admin/route-targets" to "PUT /v1/admin/route-targets"),
        "credentials" to ("GET /v1/admin/device-otps" to "POST /v1/admin/users/9/credentials"),
        "mdm" to ("GET /v1/admin/devices" to "POST /v1/admin/mdm/commands"),
        "reports" to ("GET /v1/dashboards/national" to "POST /v1/report-exports"),
        "config" to ("GET /v1/admin/config/keys" to "POST /v1/admin/config/changes"),
        "data_entry" to ("GET /v1/admin/quarantine" to "POST /v1/admin/data-entry"),
        "indent" to ("GET /v1/indent/requests" to "POST /v1/indent/requests"),
        "leaderboard" to ("GET /v1/leaderboard" to "POST /v1/leaderboard/refresh"),
        "daily_tracking" to ("GET /v1/dashboards/daily-tracking" to "POST /v1/tasks"),
    )

    private fun split(s: String) = s.substringBefore(' ') to s.substringAfter(' ')

    @Test
    fun everyModuleHasASampleAndTheSampleMapsToIt() {
        assertEquals(AdminModules.ALL.toSet(), samples.keys)
        samples.forEach { (module, rw) ->
            listOf(rw.first, rw.second).forEach { s -> val (m, path) = split(s); assertEquals(module, AdminModules.moduleOf(m, path), s) }
        }
    }

    @Test
    fun theMatrix() {
        val forbidden = ProblemCode.ERR_FORBIDDEN
        for (module in AdminModules.ALL) {
            val (read, write) = samples.getValue(module)
            for (access in listOf(null, "r", "w")) {
                val grants = AdminModules.ALL.filter { it != module }.associateWith { "w" } + (access?.let { mapOf(module to it) } ?: emptyMap())
                val admin = p(Role.ADMIN, grants)
                val (rm, rp) = split(read); val (wm, wp) = split(write)
                assertEquals(if (access == null) forbidden else null, outcome(admin, rm, rp), "ADMIN $module=$access $read")
                assertEquals(if (access == "w") null else forbidden, outcome(admin, wm, wp), "ADMIN $module=$access $write")
                // SUPERADMIN holds every module whatever a (never minted) claim says; other roles are the handlers' business.
                for (role in Role.entries - Role.ADMIN) {
                    assertEquals(null, outcome(p(role, mapOf()), wm, wp), "$role $write")
                }
            }
        }
    }

    @Test
    fun beforeTheGrantsAreDeployedAdminKeepsTodaysReach() {
        assertEquals(null, outcome(p(Role.ADMIN, null), "POST", "/v1/admin/prices", live = false))
        assertEquals(null, outcome(p(Role.ADMIN, mapOf()), "POST", "/v1/admin/prices", live = false))
    }

    @Test
    fun aTokenWithoutTheClaimRefreshesOnceLive() {
        assertEquals(ProblemCode.ERR_SCOPE_CHANGED, outcome(p(Role.ADMIN, null), "GET", "/v1/admin/users"))
        // Own session even then: the claim check comes first, so the web refreshes before anything else.
        assertEquals(ProblemCode.ERR_SCOPE_CHANGED, outcome(p(Role.ADMIN, null), "GET", "/v1/me"))
    }

    @Test
    fun ownSessionNeedsNoModule() {
        val none = p(Role.ADMIN, mapOf())
        listOf("GET /v1/me", "POST /v1/auth/logout", "POST /v1/auth/change-password", "POST /v1/auth/reauth", "POST /v1/client-errors", "GET /v1/tutorials")
            .forEach { s -> val (m, path) = split(s); assertEquals(null, outcome(none, m, path), s) }
    }

    @Test
    fun unmappedRoutesAreRefusedForAdmin() {
        val all = p(Role.ADMIN, AdminModules.ALL.associateWith { "w" })
        listOf("GET /v1/admin/brand-new-thing", "GET /v1/secret", "POST /v1/admin")
            .forEach { s -> val (m, path) = split(s); assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(all, m, path), s) }
        // Phone-side routes are left to their handlers (no module), even with no grant at all.
        listOf("GET /v1/sync/totals", "POST /v1/sync/batch", "POST /v1/devices/enrol")
            .forEach { s -> val (m, path) = split(s); assertEquals(null, outcome(p(Role.ADMIN, mapOf()), m, path), s) }
    }

    @Test
    fun readsAndReadPosts() {
        val readOnly = p(Role.ADMIN, AdminModules.ALL.associateWith { "r" })
        assertEquals(null, outcome(readOnly, "HEAD", "/v1/admin/users"))
        assertEquals(null, outcome(readOnly, "OPTIONS", "/v1/admin/users"))
        assertEquals(null, outcome(readOnly, "POST", "/v1/reports/sales-summary/query"))
        // Checker row 2 (N1): a price preview stores the batch it previews; it is a write.
        assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(readOnly, "POST", "/v1/admin/prices/preview"))
        for (m in listOf("POST", "PUT", "PATCH", "DELETE", "post")) assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(readOnly, m, "/v1/admin/users/4"), m)
        // A READ_POST shape on another path is a write.
        assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(readOnly, "POST", "/v1/reports/sales-summary/query/extra"))
    }

    @Test
    fun credentialsAreTheirOwnGrantNotUsers() {
        val usersOnly = p(Role.ADMIN, mapOf("users" to "w"))
        assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(usersOnly, "POST", "/v1/admin/users/9/credentials"))
        assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(usersOnly, "POST", "/v1/admin/users/9/credentials/reset"))
        assertEquals(null, outcome(usersOnly, "PATCH", "/v1/admin/users/9"))
        val dailyOnly = p(Role.ADMIN, mapOf("daily_tracking" to "r"))
        assertEquals(null, outcome(dailyOnly, "GET", "/v1/dashboards/daily-tracking"))
        assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(dailyOnly, "GET", "/v1/dashboards/national"))
    }

    @Test
    fun pathTricksMatchTheRouterNotTheString() {
        val usersOnly = p(Role.ADMIN, mapOf("users" to "w"))
        // Encoded, doubled and trailing slashes normalise to the same route the router serves.
        listOf("/v1/admin/users/9/%63redentials", "/v1//admin/users/9/credentials", "/v1/admin/users/9/credentials/", "//v1/admin/users/9/credentials")
            .forEach { assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(usersOnly, "POST", it), it) }
        listOf("/v1/admin/users/../prices", "/v1/admin/users/%2e%2e/prices", "/v1/admin/users/a%2fb")
            .forEach { assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(usersOnly, "GET", it), it) }
    }

    @Test
    fun notJudgedOutsideAdminWebApiTokens() {
        val none = mapOf<String, String>()
        assertEquals(null, outcome(p(Role.ADMIN, none, audience = Audience.PWCHANGE), "POST", "/v1/auth/change-password"))
        assertEquals(null, outcome(p(Role.ADMIN, none, audience = Audience.MFA), "POST", "/v1/auth/mfa/verify"))
        assertEquals(null, outcome(p(Role.SUPERADMIN, null), "POST", "/v1/admin/users"))
        assertEquals(null, outcome(p(Role.DMO, null), "POST", "/v1/admin/users"))
        try { AdminModules.check(p(Role.ADMIN, none), "GET", "/v1/admin/users", true); fail("no grant") } catch (e: ApiProblem) { assertEquals("no access to module users", e.detail) }
    }

    /** Checker row 2 (B1): creating a user answers its temporary password, so it also needs the credentials module. */
    @Test
    fun creatingAUserAlsoNeedsCredentials() {
        assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(p(Role.ADMIN, mapOf("users" to "w")), "POST", "/v1/admin/users"))
        assertEquals(ProblemCode.ERR_FORBIDDEN, outcome(p(Role.ADMIN, mapOf("users" to "w", "credentials" to "r")), "POST", "/v1/admin/users"))
        assertEquals(null, outcome(p(Role.ADMIN, mapOf("users" to "w", "credentials" to "w")), "POST", "/v1/admin/users"))
        assertEquals(null, outcome(p(Role.ADMIN, mapOf("users" to "w")), "PATCH", "/v1/admin/users/4"))
        assertEquals(null, outcome(p(Role.ADMIN, mapOf("users" to "r")), "GET", "/v1/admin/users"))
    }

    @Test
    fun antiSpoofingReviewsAndFieldPushesSitWithTheirModules() {
        assertEquals("daily_tracking", AdminModules.moduleOf("POST", "/v1/risk-signals/9/review"))
        assertEquals("mdm", AdminModules.moduleOf("POST", "/v1/admin/notifications"))
        assertEquals("mdm", AdminModules.moduleOf("POST", "/v1/support/pda-upload"))
    }
}
