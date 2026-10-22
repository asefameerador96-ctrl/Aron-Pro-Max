package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The brand ids of a BRAND user (docs/34 s1, claim `brands`); null for every other level. Wired by the authz lane's token claims. */
fun interface BrandScopeResolver { fun brandIds(userId: Long, role: Role): Set<Long>? }

class NationalDeps(
    val service: NationalDashboardService, val reach: ReachResolver, val guard: AuthGuardDeps,
    val brands: BrandScopeResolver = BrandScopeResolver { _, _ -> null }, val clock: AronClock = AronClock.SYSTEM,
)

private fun NationalDeps.caller(call: ApplicationCall): NationalCaller {
    val p = call.principal
    if (p.role !in DASHBOARD_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the dashboard is not available to this role")
    val today = service.run { dateOf(null as String?) }
    return NationalCaller(reach.reach(p.userId, p.role, p.scopeVersion, today), brands.brandIds(p.userId, p.role))
}

private fun pathId(call: ApplicationCall, name: String): Long =
    call.parameters[name]?.toLongOrNull()?.takeIf { it > 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $name", errors = listOf(FieldError("path.$name", "invalid_value")))

/** The national dashboard (contract 1.4.0): twelve cards, FF Geo, route map, sale position, filter options, freshness. */
fun Route.nationalDashboardRoutes(d: NationalDeps) {
    authenticated(d.guard) {
        post("/dashboards/national/sales") { call.respond(d.service.sales(d.caller(call), call.receiveStrict(NationalDashboardFilter.serializer()))) }
        post("/dashboards/national/cpr") { call.respond(d.service.cpr(d.caller(call), call.receiveStrict(NationalDashboardFilter.serializer()))) }
        post("/dashboards/national/channel-calls") { call.respond(d.service.channelCalls(d.caller(call), call.receiveStrict(NationalDashboardFilter.serializer()))) }
        post("/dashboards/national/final-submit") { call.respond(d.service.finalSubmit(d.caller(call), call.receiveStrict(NationalDashboardFilter.serializer()))) }
        post("/dashboards/national/login-submit") { call.respond(d.service.loginSubmit(d.caller(call), call.receiveStrict(NationalDashboardFilter.serializer()))) }
        post("/dashboards/national/channel-std") { call.respond(d.service.channelStd(d.caller(call), call.receiveStrict(NationalDashboardFilter.serializer()))) }
        post("/dashboards/national/breakdown") { call.respond(d.service.breakdown(d.caller(call), call.receiveStrict(NationalBreakdownRequest.serializer()))) }
        post("/dashboards/national/geo-fencing") { call.respond(d.service.geoFencing(d.caller(call), call.receiveStrict(NationalDashboardFilter.serializer()))) }
        post("/dashboards/national/ff-geo/pins") { call.respond(d.service.ffGeoPins(d.caller(call), call.receiveStrict(FfGeoPinsRequest.serializer()))) }
        get("/dashboards/national/ff-geo/routes/{route_id}") {
            call.respond(d.service.ffGeoRoutePopup(d.caller(call), pathId(call, "route_id"), call.request.queryParameters["date"]))
        }
        get("/dashboards/route-map/{route_id}") { call.respond(d.service.routeMap(d.caller(call), pathId(call, "route_id"), call.request.queryParameters["date"])) }
        get("/dashboards/sale-position/{outlet_id}") { call.respond(d.service.salePosition(d.caller(call), pathId(call, "outlet_id"), call.request.queryParameters["date"])) }
        get("/dashboards/national/filter-options") { call.respond(d.service.filterOptions(d.caller(call))) }
        get("/dashboards/national/freshness") { d.caller(call); call.respond(d.service.freshness()) }
    }
}
