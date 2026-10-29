package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.contract.ProblemCode
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.Query
import java.time.LocalDate

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

/**
 * What a requested node (or the nodes of a report selector) is over a window (D-REPORTS-NODEASWAS-01): [zones] it had on ANY date of the window,
 * [plain] true when nothing in the geography changed since the window began (the current structure is then exact and the as-was filters are not needed).
 */
internal class NodeMembers(
    private val windowById: Map<Long, Set<Long>>, private val currentById: Map<Long, Set<Long>>, val plain: Boolean,
    /** The ids that exist today as a node of the level asked for (a node with no zone at all still exists). */ private val known: Set<Long> = windowById.keys + currentById.keys,
) {
    /** The zones the nodes had on any date of the window. */
    val zones: List<Long> = windowById.values.flatten().distinct()
    /**
     * The reach check (ruling D-LEAD-REPORT-SELECTOR-01: SCOPE only), for EACH node asked for. An id that is no node, and a node outside today's reach, are
     * refused alike (no existence leak); a node inside the reach that has no member in the window is allowed and reads no rows. Inside the reach means: every
     * zone the node has TODAY is in the reach (a node that still holds a zone the caller cannot see is not theirs: 403 as before the history existed); a node
     * that holds no zone today (its zones moved out) is the caller's when one of the zones it had in the window is. A national caller reaches every node.
     * A zone that has since LEFT a node is not held against the caller (their own territory after a zone moved out must stay readable for a window that
     * began before the move); it is simply cut from the read, see [readable].
     */
    fun allowed(reachZones: Set<Long>?, ids: Collection<Long>): Boolean = ids.all { id ->
        id in known && (reachZones == null || currentById[id].orEmpty().let { cur -> if (cur.isNotEmpty()) cur.all { it in reachZones } else windowById[id].orEmpty().any { it in reachZones } })
    }
    /** The zones to read: the nodes' members of the window, never wider than the reach. */
    fun readable(reachZones: Set<Long>?): List<Long> = if (reachZones == null) zones else zones.filter { it in reachZones }
}

/**
 * The nodes [ids] of a level ([col] is `wing_id`, `division_id`, `territory_id` or `zone_id`) as they were on the dates [from]..[to], not as the structure
 * is now. When nothing in the geography changed since [from] the current structure answers (the plain path, the old SQL). A zone's own id never moves.
 */
internal fun memberZones(h: Handle, col: String, ids: List<Long>, from: LocalDate, to: LocalDate): NodeMembers {
    fun rows(sql: String, asWas: Boolean): Map<Long, Set<Long>> {
        val q = h.createQuery(sql).bindArray("ids", Long::class.javaObjectType, ids)
        if (asWas) q.bind("f", from).bind("t", to)
        return q.map { rs, _ -> rs.getLong(2) to rs.getLong(1) }.list().groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    }
    // The members today, from the same source the history timeline lists (app.zone and its chain), so the plain path and the as-was path agree: a zone
    // exists with or without routes, and a node whose zones have no route yet is still a node.
    val nodeExpr = when (col) { "zone_id" -> "z.id"; "territory_id" -> "t.id"; "division_id" -> "d.id"; "wing_id" -> "w.id"; else -> throw IllegalArgumentException(col) }
    val current = rows(
        "SELECT z.id, $nodeExpr FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id WHERE $nodeExpr = ANY(:ids)",
        false,
    )
    val plain = col == "zone_id" || AsWasSql.probe(h, from).geo
    val window = if (plain) current else rows("SELECT DISTINCT zn.zone_id, zn.$col FROM ${AsWasSql.zoneAll("zn", ":f", ":t")} WHERE zn.$col = ANY(:ids)", true)
    val known = h.createQuery("SELECT id FROM app.${col.removeSuffix("_id")} WHERE id = ANY(:ids)").bindArray("ids", Long::class.javaObjectType, ids).mapTo(Long::class.javaObjectType).set()
    return NodeMembers(window, current, plain, known)
}

private val GEO_COLUMNS = mapOf("wing" to ("wing_id" to "wing_name"), "division" to ("division_id" to "division_name"), "territory" to ("territory_id" to "territory_name"), "zone" to ("zone_id" to "zone_name"))

/**
 * Resolves `level` + `node_id` (or the caller's top reach node) to the zones to read. Reach comes only from the token's user:
 * a node outside it, or unknown, is 403 with no existence leak; a node is allowed only when every zone under it is in reach.
 * The default node may be a `house` (the reach's zones); route assignments alone give the caller's routes, read route-keyed
 * (D-BC-DASH-ROUTE-KEYED-01; labelled by the first route). An explicit node is never a route: a route-only caller reaches no zone (403).
 * [outOfScope] is the 403's code: `ERR_FORBIDDEN` for the older dashboards, `ERR_OUT_OF_SCOPE` where the contract names it.
 */
internal fun resolveScopedNode(
    h: Handle, reach: Reach, level: String?, nodeId: Long?, outOfScope: ProblemCode = ProblemCode.ERR_FORBIDDEN,
    /** The dates the read covers: the node's member zones are those it had then (null: the structure as it is now). */ from: LocalDate? = null, to: LocalDate? = from,
): ScopedNode {
    val out = ApiProblem(outOfScope, "node is outside your reach")
    val lv: String; val id: Long
    if (level == null && nodeId == null) {
        if (reach.national) return ScopedNode(NodeRefDto("national", 0, "national", "National"), null)
        // Route assignments and no zone in reach (also a scope node with no zones): the caller's routes, as DashboardService does.
        routeOnlyNode(reach)?.let { return it }
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
    val (col, _) = GEO_COLUMNS[lv] ?: throw out
    val members = if (from != null && to != null) memberZones(h, col, listOf(id), from, to)
    else h.createQuery("SELECT DISTINCT zone_id FROM dw.dim_geo WHERE $col = :id").bind("id", id).mapTo(Long::class.java).list().let { NodeMembers(mapOf(id to it.toSet()), mapOf(id to it.toSet()), true, h.createQuery("SELECT id FROM app.$lv WHERE id = :id").bind("id", id).mapTo(Long::class.javaObjectType).set()) }
    val reachZones = if (reach.national) null else reach.zoneIds
    if (!members.allowed(reachZones, listOf(id))) throw out
    val zones = members.readable(reachZones)
    val name = h.createQuery("SELECT name FROM app.$lv WHERE id = :id").bind("id", id).mapTo(String::class.java).first()
    return ScopedNode(NodeRefDto(lv, id, null, name), zones)
}

/** The route-keyed node of a caller with route assignments and no geo scope node, or null when the reach is not route-only. */
internal fun routeOnlyNode(reach: Reach): ScopedNode? {
    if (reach.national || reach.zoneIds.isNotEmpty() || reach.routeIds.isEmpty()) return null
    val top = reach.topNodes.firstOrNull { it.type == "route" } ?: return null
    return ScopedNode(NodeRefDto("route", top.id, top.code, top.name), emptyList(), reach.routeIds.sorted())
}
