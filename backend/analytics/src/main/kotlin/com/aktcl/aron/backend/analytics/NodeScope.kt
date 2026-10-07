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
 * Resolves `level` + `node_id` (or the caller's top reach node) to the zones to read. Reach comes only from the token's user:
 * a node outside it, or unknown, is 403 with no existence leak; a node is allowed only when every zone under it is in reach.
 */
internal fun resolveScopedNode(h: Handle, reach: Reach, level: String?, nodeId: Long?): ScopedNode {
    val out = ApiProblem(ProblemCode.ERR_FORBIDDEN, "node is outside your reach")
    val lv: String; val id: Long
    if (level == null && nodeId == null) {
        if (reach.national) return ScopedNode(NodeRefDto("national", 0, "national", "National"), null)
        val top = reach.topNodes.firstOrNull() ?: throw out
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
