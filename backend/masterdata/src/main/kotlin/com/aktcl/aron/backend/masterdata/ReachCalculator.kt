package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import java.time.LocalDate

/** Geography needed to expand scope nodes to zones (docs/24 s12.1). */
data class Geo(
    val zoneTerritory: Map<Long, Long>,
    val territoryDivision: Map<Long, Long>,
    val divisionWing: Map<Long, Long>,
    val routeZone: Map<Long, Long>,
    val names: Map<Pair<String, Long>, Pair<String?, String?>> = emptyMap(),
) {
    fun zonesUnder(type: String, id: Long): Set<Long> = when (type) {
        "national" -> zoneTerritory.keys
        "wing" -> zoneTerritory.filter { (_, t) -> territoryDivision[t]?.let { divisionWing[it] } == id }.keys
        "division" -> zoneTerritory.filter { (_, t) -> territoryDivision[t] == id }.keys
        "territory" -> zoneTerritory.filter { (_, t) -> t == id }.keys
        "zone" -> if (id in zoneTerritory) setOf(id) else emptySet()
        else -> emptySet()
    }
}

/** An effective-dated `user_scope` row; [validTo] is exclusive (contract UserScopeNode). */
data class ScopeRow(val nodeType: String, val nodeId: Long, val validFrom: LocalDate, val validTo: LocalDate?) {
    fun validOn(d: LocalDate) = !d.isBefore(validFrom) && (validTo == null || d.isBefore(validTo))
}

/** An effective-dated `route_assignment` row (primary or cover); [validTo] is exclusive. */
data class AssignmentRow(val routeId: Long, val kind: String, val validFrom: LocalDate, val validTo: LocalDate?) {
    fun validOn(d: LocalDate) = !d.isBefore(validFrom) && (validTo == null || d.isBefore(validTo))
}

/**
 * Reach of a user on a business date (docs/24 s8.4), a pure function of role, scope rows, assignments and geography:
 * - SR: the routes assigned on the date (primary or cover) and their outlets, own records only;
 * - AMO, TSO, DMO, WM: the zones under the scope nodes valid on the date, plus any route assigned to them;
 * - TOP, ANALYST, SUPPORT, ADMIN, SUPERADMIN: national.
 * Nothing the client sends is an input.
 */
object ReachCalculator {
    private val nationalRoles = setOf(Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

    fun compute(userId: Long, role: Role, date: LocalDate, scope: List<ScopeRow>, assignments: List<AssignmentRow>, geo: Geo): Reach {
        val routes = assignments.filter { it.validOn(date) }.map { it.routeId }.filter { it in geo.routeZone }.toSet()
        if (role in nationalRoles) {
            return Reach(userId, role, date, true, geo.zoneTerritory.keys, routes, false, listOf(ReachNode("national", 0)))
        }
        if (role == Role.SR) {
            return Reach(userId, role, date, false, emptySet(), routes, true, routes.sorted().map { node("route", it, geo) })
        }
        val valid = scope.filter { it.validOn(date) }
        if (valid.any { it.nodeType == "national" }) {
            return Reach(userId, role, date, true, geo.zoneTerritory.keys, routes, false, listOf(ReachNode("national", 0)))
        }
        val zones = valid.flatMap { geo.zonesUnder(it.nodeType, it.nodeId) }.toSet()
        val top = valid.sortedWith(compareBy({ it.nodeType }, { it.nodeId })).map { node(it.nodeType, it.nodeId, geo) } +
            routes.filter { geo.routeZone[it] !in zones }.sorted().map { node("route", it, geo) }
        return Reach(userId, role, date, false, zones, routes, false, top.distinct())
    }

    private fun node(type: String, id: Long, geo: Geo): ReachNode {
        val (code, name) = geo.names[type to id] ?: (null to null)
        return ReachNode(type, id, code, name)
    }
}

/** Optional narrowing selectors of a list call (docs/24 s3.5). They only narrow; outside the reach is 403. */
data class GeoSelector(val wingId: Long? = null, val divisionId: Long? = null, val territoryId: Long? = null, val zoneId: Long? = null, val routeId: Long? = null)

/** An outlet as the scope filter sees it. */
data class OutletRef(val id: Long, val zoneId: Long, val routeId: Long?)

object ReachFilter {
    /** Zones a selector may return rows from, or a 403 when the selector names a node outside the reach. */
    fun allowedZones(reach: Reach, sel: GeoSelector, geo: Geo): Set<Long>? {
        var zones: Set<Long>? = null
        fun narrow(type: String, id: Long?) {
            if (id == null) return
            val under = geo.zonesUnder(type, id)
            zones = (zones ?: under).intersect(under)
        }
        narrow("wing", sel.wingId); narrow("division", sel.divisionId); narrow("territory", sel.territoryId); narrow("zone", sel.zoneId)
        return zones
    }

    /**
     * Applies reach and selectors to [outlets]. A named node (or route) with no overlap with the reach is
     * 403 ERR_OUT_OF_SCOPE; an SR sees only the outlets of the routes assigned on the date.
     */
    fun outlets(reach: Reach, sel: GeoSelector, geo: Geo, outlets: Sequence<OutletRef>): List<OutletRef> {
        val selZones = allowedZones(reach, sel, geo)
        if (selZones != null && sel.routeId == null) {
            val overlap = if (reach.ownRecordsOnly) reach.routeIds.any { geo.routeZone[it] in selZones } else reach.national || selZones.any { it in reach.zoneIds } || reach.routeIds.any { geo.routeZone[it] in selZones }
            if (!overlap) throw outOfScope()
        }
        sel.routeId?.let { r ->
            val z = geo.routeZone[r] ?: throw outOfScope()
            if (!reach.coversRoute(r, z)) throw outOfScope()
            if (selZones != null && z !in selZones) return emptyList()
        }
        return outlets.filter { o ->
            val inReach = if (reach.ownRecordsOnly) o.routeId != null && o.routeId in reach.routeIds
            else reach.national || o.zoneId in reach.zoneIds || (o.routeId != null && o.routeId in reach.routeIds)
            inReach && (selZones == null || o.zoneId in selZones) && (sel.routeId == null || o.routeId == sel.routeId)
        }.sortedBy { it.id }.toList()
    }

    fun outOfScope() = ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "the selected node is outside your reach")
}
