package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.contract.ProblemCode
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.Query

/** A dashboard node and the zones under it that the caller may read (`zones == null` means every zone). */
internal class ScopedNode(val ref: NodeRefDto, val zones: List<Long>?) {
    fun clause(col: String) = if (zones == null) "true" else "$col = ANY(:zones)"
    fun bind(q: Query): Query = q.also { if (zones != null) it.bindArray("zones", Long::class.javaObjectType, zones) }
}

private val GEO_COLUMNS = mapOf("wing" to ("wing_id" to "wing_name"), "division" to ("division_id" to "division_name"), "territory" to ("territory_id" to "territory_name"), "zone" to ("zone_id" to "zone_name"))

/**
 * Levels a caller's default node may have: the query levels plus `house`, a TSO or AMO scope node since contract 1.7.0
 * (D-DASH-TSO-HOUSE-SCOPE-01). An explicit `level=house` stays 400, as the contract's GeoLevelQuery says.
 */
private val DEFAULT_COLUMNS = GEO_COLUMNS + ("house" to ("house_id" to "house_name"))

/**
 * Resolves `level` + `node_id` (or the caller's top reach node) to the zones to read. Reach comes only from the token's user:
 * a node outside it, or unknown, is 403 with no existence leak; a node is allowed only when every zone under it is in reach.
 * The default node may be a `house` (its zones under it in `dw.dim_geo`) or, for route assignments only, [routeOnlyZones].
 * [outOfScope] is the 403's code: `ERR_FORBIDDEN` for the older dashboards, `ERR_OUT_OF_SCOPE` where the contract names it.
 */
internal fun resolveScopedNode(h: Handle, reach: Reach, level: String?, nodeId: Long?, outOfScope: ProblemCode = ProblemCode.ERR_FORBIDDEN): ScopedNode {
    val out = ApiProblem(outOfScope, "node is outside your reach")
    val lv: String; val id: Long
    if (level == null && nodeId == null) {
        if (reach.national) return ScopedNode(NodeRefDto("national", 0, "national", "National"), null)
        val top = reach.topNodes.firstOrNull() ?: throw out
        // Route nodes follow the scope nodes in the reach, so a route first means route assignments only.
        if (top.type == "route") return routeOnlyZones(h, reach, outOfScope).let { (zones, name) -> ScopedNode(NodeRefDto("zone", zones.first(), null, name), zones) }
        // A caller with several top nodes sees all of them: the zones are the whole reach, the node label is the first top node.
        if (reach.topNodes.size > 1) return ScopedNode(NodeRefDto(top.type, top.id, top.code, top.name), reach.zoneIds.toList().ifEmpty { listOf(-1L) })
        lv = top.type; id = top.id
    } else {
        lv = level ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "node_id needs level", errors = listOf(FieldError("query.level", "required")))
        if (lv == "national") { if (!reach.national) throw out; return ScopedNode(NodeRefDto("national", 0, "national", "National"), null) }
        if (lv !in GEO_COLUMNS) throw ApiProblem(ProblemCode.ERR_VALIDATION, "level must be national, wing, division, territory or zone", errors = listOf(FieldError("query.level", "invalid_value")))
        id = nodeId?.takeIf { it > 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "node_id required", errors = listOf(FieldError("query.node_id", "required")))
    }
    val (col, nameCol) = (if (level == null && nodeId == null) DEFAULT_COLUMNS else GEO_COLUMNS)[lv] ?: throw out
    val zones = h.createQuery("SELECT DISTINCT zone_id FROM dw.dim_geo WHERE $col = :id").bind("id", id).mapTo(Long::class.java).list()
    if (zones.isEmpty() || (!reach.national && !reach.zoneIds.containsAll(zones))) throw out
    val name = h.createQuery("SELECT DISTINCT $nameCol FROM dw.dim_geo WHERE $col = :id").bind("id", id).mapTo(String::class.java).first()
    return ScopedNode(NodeRefDto(lv, id, null, name), zones)
}

/**
 * Default zones of a caller who holds route assignments and no geo scope node (D-DASH-TSO-HOUSE-SCOPE-01), with the first
 * zone's name: the zones whose every `dw.dim_geo` route is assigned to the caller on the reach's business date (the same
 * assignments as `GET /v1/memos`). The dashboards read zone aggregates, so a zone held only in part stays out: its other
 * routes are outside the reach. An own-records-only caller (SR) never reads a zone. None qualifies: 403 [outOfScope].
 */
internal fun routeOnlyZones(h: Handle, reach: Reach, outOfScope: ProblemCode = ProblemCode.ERR_FORBIDDEN): Pair<List<Long>, String?> {
    val out = ApiProblem(outOfScope, "node is outside your reach")
    if (reach.national || reach.ownRecordsOnly || reach.routeIds.isEmpty()) throw out
    val zones = h.createQuery("SELECT zone_id FROM dw.dim_geo GROUP BY zone_id HAVING bool_and(route_id = ANY(:routes)) ORDER BY zone_id")
        .bindArray("routes", Long::class.javaObjectType, reach.routeIds.toList()).mapTo(Long::class.java).list()
    if (zones.isEmpty()) throw out
    val name = h.createQuery("SELECT zone_name FROM dw.dim_geo WHERE zone_id = :z LIMIT 1").bind("z", zones.first()).mapTo(String::class.java).findOne().orElse(null)
    return zones to name
}
