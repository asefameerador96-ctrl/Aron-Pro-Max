package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role

/**
 * Grants a web token carries (D-ROLES-02, docs/34 s1), read when a token is minted. Implemented by auth over
 * `app.user_module_grant` and `app.user_brand_scope`; [live] is false until db's migrations have created both tables
 * (probed once per process: migrations run before the API restarts on a deploy), and then no claim is minted.
 */
interface PortalGrants {
    fun live(): Boolean

    /** The ADMIN user's module grants, `{module: "r"|"w"}` (only modules in [AdminModules.ALL]). */
    fun modules(userId: Long): Map<String, String>

    /** The BRAND user's brand node ids, ascending. */
    fun brands(userId: Long): List<Long>

    /**
     * The `geo` claim of a web token (docs/34 s1 "claims `geo` (level + ids)", D-AUTHZ-GEO-CLAIM-01): `{level: [ids]}` of the
     * user's scope on [onDate], `{"national": []}` for a national level. Informational for the portal only: the server never
     * reads scope from a token (reach is resolved from the rows, keyed by `sv`). Empty = no claim.
     */
    fun geo(userId: Long, role: Role, onDate: java.time.LocalDate): Map<String, List<Long>> = emptyMap()

    object None : PortalGrants {
        override fun live() = false
        override fun modules(userId: Long) = emptyMap<String, String>()
        override fun brands(userId: Long) = emptyList<Long>()
    }
}

/**
 * ADMIN module grants (docs/34 s1, D-ROLES-02, wave 4 row 2): an ADMIN's web token carries `modules`
 * (`{module: "r"|"w"}`, from `app.user_module_grant`); every route an ADMIN can reach belongs to exactly one module
 * ([ROUTES], first match wins) or is the caller's own session ([OWN]); a call outside the grants is 403. A read is
 * GET/HEAD/OPTIONS and the [READ_POSTS] (a report query is a read sent as POST); everything else needs `w`. SUPERADMIN
 * holds every module; other roles are not judged here (their handlers' role and scope checks apply). An unmapped route
 * is refused for ADMIN (fail closed); RouteInventoryTest proves every production route is mapped or listed in [NOT_ADMIN].
 */
object AdminModules {
    val ALL: List<String> = listOf(
        "masterdata", "routes", "outlets", "products", "prices", "users", "targets", "credentials", "mdm", "reports",
        "config", "data_entry", "indent", "leaderboard", "daily_tracking",
    )

    private fun p(path: String) = PortalLevels.Pattern("*", path)

    /** Routes of no module: the caller's own session and profile (an ADMIN has no phone flavour). */
    val OWN: List<PortalLevels.Pattern> = PortalLevels.OWN_SESSION + listOf(
        PortalLevels.Pattern("GET", "/v1/me"),
        PortalLevels.Pattern("GET", "/v1/tutorials"),
        PortalLevels.Pattern("GET", "/v1/tutorials/**"),
        PortalLevels.Pattern("GET", "/v1/app/update-check"),
        PortalLevels.Pattern("*", "/v1/me/notifications/**"), // tso-manual B1: the caller's own bell inbox (D-TSO-MAN-26)
    )

    /** Most specific first. */
    val ROUTES: List<Pair<PortalLevels.Pattern, String>> = listOf(
        // credentials before users: a password reset or MFA reset is its own grant.
        p("/v1/admin/users/{id}/credentials") to "credentials",
        p("/v1/admin/users/{id}/credentials/**") to "credentials",
        p("/v1/admin/users/{id}/mfa/**") to "credentials",
        p("/v1/admin/device-otps/**") to "credentials",
        p("/v1/admin/users/**") to "users",
        p("/v1/leave/**") to "users",
        p("/v1/admin/permissions/**") to "config",
        p("/v1/admin/config/**") to "config",
        p("/v1/config/**") to "config",
        p("/v1/admin/audit/**") to "config",
        p("/v1/admin/calendar/**") to "masterdata",
        p("/v1/admin/geo/**") to "masterdata",
        p("/v1/admin/clusters/**") to "masterdata",
        p("/v1/admin/code-lists/**") to "masterdata",
        p("/v1/admin/content/**") to "masterdata",
        p("/v1/admin/tutorials/**") to "masterdata",
        p("/v1/admin/surveys/**") to "masterdata",
        p("/v1/admin/rubrics/**") to "masterdata",
        p("/v1/admin/print-templates/**") to "masterdata",
        p("/v1/admin/routes/**") to "routes",
        p("/v1/admin/route-assignments/**") to "routes",
        p("/v1/routes/**") to "routes",
        p("/v1/visit-plans/**") to "routes",
        p("/v1/admin/outlets/**") to "outlets",
        p("/v1/outlets/**") to "outlets",
        p("/v1/outlet-requests/**") to "outlets",
        p("/v1/admin/skus/**") to "products",
        p("/v1/admin/product-nodes/**") to "products",
        p("/v1/admin/assets/**") to "products",
        p("/v1/sku-images/**") to "products",
        p("/v1/admin/prices/**") to "prices",
        p("/v1/admin/targets/**") to "targets",
        p("/v1/admin/route-targets/**") to "targets",
        p("/v1/admin/target-revisions/**") to "targets",
        p("/v1/admin/supervisor-targets/**") to "targets",
        p("/v1/admin/sales-plans/**") to "targets",
        // Deferred programmes (docs/27): hooks only; their admin pages sit with targets.
        p("/v1/admin/programmes/**") to "targets",
        p("/v1/admin/offers/**") to "targets",
        p("/v1/admin/gifts/**") to "targets",
        p("/v1/programmes/**") to "targets",
        p("/v1/admin/devices/**") to "mdm",
        p("/v1/admin/enrolment-tokens/**") to "mdm",
        p("/v1/admin/releases/**") to "mdm",
        p("/v1/admin/mdm/**") to "mdm",
        p("/v1/admin/notifications/**") to "mdm",
        p("/v1/support/**") to "mdm",
        p("/v1/admin/data-entry/**") to "data_entry",
        p("/v1/admin/data-void/**") to "data_entry",
        p("/v1/admin/entry-unlocks/**") to "data_entry",
        p("/v1/admin/dues-adjustments/**") to "data_entry",
        p("/v1/admin/quarantine/**") to "data_entry",
        p("/v1/web-entry/**") to "data_entry",
        p("/v1/day/**") to "data_entry",
        p("/v1/admin/indent/**") to "indent",
        p("/v1/indent/**") to "indent",
        p("/v1/leaderboard/**") to "leaderboard",
        p("/v1/dashboards/daily-tracking/**") to "daily_tracking",
        p("/v1/tasks/**") to "daily_tracking",
        p("/v1/risk-signals/**") to "daily_tracking",
        p("/v1/dashboards/**") to "reports",
        p("/v1/reports/**") to "reports",
        p("/v1/report-exports/**") to "reports",
        p("/v1/app/home") to "reports",
        p("/v1/team/**") to "reports",
        p("/v1/memos/**") to "reports",
        p("/v1/feedback/**") to "reports",
        p("/v1/media/**") to "reports",
    )

    /**
     * Guarded phone-side routes (sync and device paths, the phone-side auth steps) that belong to no module: the module
     * gate leaves them to their handlers' role checks, as before. RouteInventoryTest checks that every guarded route is in
     * [ROUTES], [OWN] or here; a route in none of them is refused for ADMIN.
     */
    val NOT_ADMIN: List<PortalLevels.Pattern> = listOf(
        p("/v1/sync/**"), p("/v1/devices/**"), p("/v1/auth/**"), p("/v1/client-errors"),
        p("/v1/app/**"),
    )

    /** POSTs that only read. */
    val READ_POSTS: List<PortalLevels.Pattern> = listOf(
        PortalLevels.Pattern("POST", "/v1/reports/{report_key}/query"),
        // The national dashboard cards take their filter object as a POST body (as for NATIONAL, PortalLevels.NATIONAL_WRITES):
        // reads, so reports:r loads them (checker row 2 round 3, N4).
        PortalLevels.Pattern("POST", "/v1/dashboards/national/**"),
    )

    /**
     * Routes that need a second module besides their own (checker row 2, B1): creating a user answers its temporary
     * password, so it is a credential act as much as a users one. The reverse holds too (D-AUTHZ-ADMIN-CREDS-01 grants the
     * credential acts on another login to an ADMIN holding users:w AND credentials:w; checker row 2 round 3, N1): a password
     * or MFA reset hands the account over, so `credentials:w` alone does not reset a login.
     */
    val ALSO_NEEDS: List<Pair<PortalLevels.Pattern, String>> = listOf(
        PortalLevels.Pattern("POST", "/v1/admin/users") to "credentials",
        p("/v1/admin/users/{id}/credentials") to "users",
        p("/v1/admin/users/{id}/credentials/**") to "users",
        p("/v1/admin/users/{id}/mfa/**") to "users",
    )

    /** "own", a module name, or null when the route is unmapped. [path] is normalised; [method] is upper case. */
    fun moduleOf(method: String, path: String): String? {
        if (OWN.any { it.matches(method, path) }) return "own"
        return ROUTES.firstOrNull { it.first.matches(method, path) }?.second
    }

    fun needsWrite(method: String, path: String): Boolean =
        method != "GET" && method != "HEAD" && method != "OPTIONS" && READ_POSTS.none { it.matches(method, path) }

    /**
     * Throws when an ADMIN web API token may not call [method] [rawPath] under its `modules` claim. [live] says the grants
     * are deployed: before that ADMIN keeps today's reach. Once live, an ADMIN token without the claim was minted before
     * the deploy: 401 ERR_SCOPE_CHANGED makes the web refresh and the new token carries the grants.
     */
    fun check(p: AronPrincipal, method: String, rawPath: String, live: Boolean) {
        if (!live || p.isPhone || p.role != Role.ADMIN || p.audience != Audience.API) return
        val mods = p.modules ?: throw ApiProblem(ProblemCode.ERR_SCOPE_CHANGED, "access changed; refresh the session")
        val m = if (method == "HEAD") "GET" else method.uppercase()
        val path = PortalLevels.normalise(rawPath) ?: throw denied()
        // Phone-side routes are no module's business: their handlers judge an ADMIN as before (contract-declared answers).
        val module = moduleOf(m, path) ?: if (NOT_ADMIN.any { it.matches(m, path) }) return else throw denied()
        if (module == "own") return
        val access = mods[module] ?: throw denied(module)
        if (needsWrite(m, path) && access != "w") throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "read-only access to module $module")
        for ((pattern, extra) in ALSO_NEEDS) {
            if (!pattern.matches(m, path)) continue
            val a = mods[extra] ?: throw denied(extra)
            if (needsWrite(m, path) && a != "w") throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "read-only access to module $extra")
        }
    }

    private fun denied(module: String? = null) =
        ApiProblem(ProblemCode.ERR_FORBIDDEN, if (module == null) "not available to an admin" else "no access to module $module")
}
