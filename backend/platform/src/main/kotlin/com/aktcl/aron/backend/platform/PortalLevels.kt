package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.decodeURLPart

/**
 * The owner's portal levels (docs/34 s1, D-ROLES-02), enforced in the guard before any handler runs, so a role that a
 * handler's older deny-list (`Role.entries - SR - ...`) never heard of cannot slip through it:
 * - SR and AMO have no portal login: no web token is ever minted for them (LoginService.complete, which every login,
 *   change-password, MFA and bind path ends in, and the web refresh refuse them with ERR_AUTH_PORTAL_NOT_ALLOWED).
 * - NATIONAL (TOP retired; TOP is treated the same until the migration moves its users) reads everything its
 *   handlers allow and writes nothing but its own session, error reports and report queries.
 * - BRAND may call only [BRAND_ROUTES]: its own session and the product-based reads that filter by its brands.
 * Everything else passes to the handler's own role and scope checks unchanged.
 */
object PortalLevels {
    val NO_PORTAL: Set<Role> = setOf(Role.SR, Role.AMO)

    /** National read-only levels: the retired TOP and its successor. */
    val NATIONAL_READ: Set<Role> = setOf(Role.TOP, Role.NATIONAL)

    /** One route pattern: METHOD and a path with `{x}` for one segment and a last segment of two stars for any tail (or none). */
    data class Pattern(val method: String, val path: String) {
        private val regex = Regex("^" + path.split('/').joinToString("/") { seg ->
            when {
                seg == "**" -> ".*"
                seg.startsWith("{") && seg.endsWith("}") -> "[^/]+"
                else -> Regex.escape(seg)
            }
        }.replace("/.*", "(/.*)?") + "$")

        fun matches(m: String, p: String): Boolean = (method == "*" || method == m) && regex.matches(p)
    }

    /** Writes any portal role may make: its own session (logout, password, MFA, reveal) and its own error reports. */
    val OWN_SESSION: List<Pattern> = listOf(
        Pattern("POST", "/v1/auth/logout"), Pattern("POST", "/v1/auth/change-password"), Pattern("POST", "/v1/auth/mfa/enrol"),
        Pattern("POST", "/v1/auth/reauth"), Pattern("POST", "/v1/client-errors"),
    )

    /** The one field role each phone flavour serves (login refuses any other; a role change must not carry over). */
    val PHONE_ROLE: Map<String, Role> = mapOf("sr" to Role.SR, "amo" to Role.AMO, "tso" to Role.TSO)

    /** NATIONAL's non-read calls besides [OWN_SESSION]: a report query is a read sent as POST (it may queue an export). */
    val NATIONAL_WRITES: List<Pattern> = OWN_SESSION + Pattern("POST", "/v1/reports/{report_key}/query") +
        Pattern("POST", "/v1/dashboards/national/**")   // the dashboard cards take their filter object as a POST body: reads

    /** The national dashboard reads that carry a brand dimension; the cards without one (CPR, final submit, login, geo, FF Geo, maps) stay refused (docs/34 s1). */
    private val BRAND_DASHBOARD: List<Pattern> = listOf(
        Pattern("POST", "/v1/dashboards/national/sales"), Pattern("POST", "/v1/dashboards/national/channel-calls"),
        Pattern("POST", "/v1/dashboards/national/channel-std"), Pattern("POST", "/v1/dashboards/national/breakdown"),
        Pattern("GET", "/v1/dashboards/national/filter-options"), Pattern("GET", "/v1/dashboards/national/freshness"),
    )

    /** BRAND's whole surface (row 3 adds the brand-filtered reads as they are built). */
    val BRAND_ROUTES: List<Pattern> = OWN_SESSION + listOf(Pattern("GET", "/v1/me")) + BRAND_DASHBOARD

    /** The path the router matches: decoded segments, empty ones dropped (the same normalisation as the MFA check). */
    fun normalise(rawPath: String): String? {
        val segments = rawPath.split('/').filter { it.isNotEmpty() }.map { seg -> runCatching<String> { seg.decodeURLPart() }.getOrDefault(seg) }
        if (segments.any { it == "." || it == ".." || '/' in it }) return null
        return segments.joinToString("/", prefix = "/")
    }

    /** Throws when [p] may not call [method] [rawPath] at its portal level. */
    fun check(p: AronPrincipal, method: String, rawPath: String) {
        if (p.isPhone) {
            // A phone token serves only its flavour's field role: after a role change (TSO to BRAND, SR to TSO, ...) a
            // token minted for the old app is refused here, so the new role's national reach never rides a phone token.
            // The upload grant (sync/batch, logout only) is exempt: captured rows always reach the server (D24-57) and
            // ingest judges every record by the uploader's current assignment.
            if (p.audience != Audience.UPLOAD && PHONE_ROLE[p.flavour] != p.role) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "this account cannot use this app")
            return
        }
        val m = if (method == "HEAD") "GET" else method
        val read = m == "GET" || m == "OPTIONS"
        when (p.role) {
            Role.BRAND -> {
                val path = normalise(rawPath) ?: throw forbidden()
                if (BRAND_ROUTES.none { it.matches(m, path) }) throw forbidden()
            }
            in NATIONAL_READ -> if (!read) {
                val path = normalise(rawPath) ?: throw forbidden()
                if (NATIONAL_WRITES.none { it.matches(m, path) }) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a national user reads only")
            }
            else -> Unit
        }
    }

    private fun forbidden() = ApiProblem(ProblemCode.ERR_FORBIDDEN, "not available at this portal level")
}
