package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.contract.ProblemCode
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.Query

/**
 * A dashboard node and the zones under it that the caller may read (`zones == null` means every zone), or, for a route-only
 * caller, the routes ([routes] non-null, from the token's reach): rows are then read by their route, never their zone
 * (D-BC-DASH-ROUTE-KEYED-01). Every read names both columns so a route-only caller can never fall back to a zone filter.
 */
internal class ScopedNode(val ref: NodeRefDto, val zones: List<Long>?, val routes: List<Long>? = null) {
    val routeOnly: Boolean get() = routes != null
    fun clause(zoneCol: String, routeCol: String) = when {
        routes != null -> "$routeCol = ANY(:routes)"
        zones == null -> "true"
        else -> "$zoneCol = ANY(:zones)"
    }
    fun bind(q: Query): Query = q.also {
        if (routes != null) it.bindArray("routes", Long::class.javaObjectType, routes)
        else if (zones != null) it.bindArray("zones", Long::class.javaObjectType, zones)
    }
}

private val GEO_COLUMNS = mapOf("wing" to ("wing_id" to "wing_name"), "division" to ("division_id" to "division_name"), "territory" to ("territory_id" to "territory_name"), "zone" to ("zone_id" to "zone_name"))

/**
 * Resolves `level` + `node_id` (or the caller's top reach node) to the zones to read. Reach comes only from the token's user:
 * a node outside it, or unknown, is 403 with no existence leak; a node is allowed only when every zone under it is in reach.
 * The default node may be a `house` (the reach's zones); route assignments alone give the caller's routes, read route-keyed
 * (D-BC-DASH-ROUTE-KEYED-01; labelled by the first route). An explicit node is never a route: a route-only caller reaches no zone (403).
 * [outOfScope] is the 403's code: `ERR_FORBIDDEN` for the older dashboards, `ERR_OUT_OF_SCOPE` where the contract names it.
 */
internal fun resolveScopedNode(h: Handle, reach: Reach, level: String?, nodeId: Long?, outOfScope: ProblemCode = ProblemCode.ERR_FORBIDDEN): ScopedNode {
    val out = ApiProblem(outOfScope, "node is outside your reach")
    val lv: String; val id: Long
    if (level == null && nodeId == null) {
        if (reach.national) return ScopedNode(NodeRefDto("national", 0, "national", "National"), null)
        val top = reach.topNodes.firstOrNull() ?: throw out
        // Route nodes follow the scope nodes in the reach, so a route first means route assignments only: no zone is in reach,
        // and zone figures carry the zone stamped when written, so they would show other routes (D-BC-DASH-ROUTE-ONLY-01): read the
        // routes themselves (D-BC-DASH-ROUTE-KEYED-01).
        if (top.type == "route") return routeOnlyNode(reach) ?: throw out
        // A house (a TSO or AMO scope node since contract 1.7.0, D-BC-DASH-HOUSE-01): its zones as the reach has them
        // (`app.zone.house_id`), not `dw.dim_geo`, which folds same-name houses into one. An explicit `level=house` stays 400.
        if (top.type == "house") return ScopedNode(NodeRefDto(top.type, top.id, top.code, top.name), reach.zoneIds.toList().ifEmpty { throw out })
        // A caller with several top nodes sees all of them: the zones are the whole reach, the node label is the first top node.
        if (reach.topNodes.size > 1) return ScopedNode(NodeRefDto(top.type, top.id, top.code, top.name), reach.zoneIds.toList().ifEmpty { listOf(-1L) })
        lv = top.type; id = top.id
    } else {
        lv = level ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "node_id needs level", errors = listOf(FieldError("query.level", "required")))
        if (lv == "national") { if (!reach.national) throw out; return ScopedNode(NodeRefDto("national", 0, "national", "National"), null) }
        if (lv !in GEO_COLUMNS) throw ApiProblem(ProblemCode.ERR_VALIDATION, "level must be national, wing, division, territory or zone", errors = listOf(FieldError("query.level", "invalid_value")))
        id = nodeId?.takeIf { it > 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "node_id required", errors = listOf(FieldError("query.node_id", "required")))
    }
    val (col, nameCol) = GEO_COLUMNS[lv] ?: throw out
    val zones = h.createQuery("SELECT DISTINCT zone_id FROM dw.dim_geo WHERE $col = :id").bind("id", id).mapTo(Long::class.java).list()
    if (zones.isEmpty() || (!reach.national && !reach.zoneIds.containsAll(zones))) throw out
    val name = h.createQuery("SELECT DISTINCT $nameCol FROM dw.dim_geo WHERE $col = :id").bind("id", id).mapTo(String::class.java).first()
    return ScopedNode(NodeRefDto(lv, id, null, name), zones)
}

/** The route-keyed node of a caller with route assignments and no geo scope node, or null when the reach is not route-only. */
internal fun routeOnlyNode(reach: Reach): ScopedNode? {
    if (reach.national || reach.zoneIds.isNotEmpty() || reach.routeIds.isEmpty()) return null
    val top = reach.topNodes.firstOrNull { it.type == "route" } ?: return null
    return ScopedNode(NodeRefDto("route", top.id, top.code, top.name), emptyList(), reach.routeIds.sorted())
}
