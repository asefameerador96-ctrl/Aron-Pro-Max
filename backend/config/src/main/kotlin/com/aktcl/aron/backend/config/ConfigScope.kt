package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.contract.ProblemCode
import org.jdbi.v3.core.Handle

/**
 * Server-side scope of the configuration reads (`GET /v1/admin/config/resolve`, `/values`; qa scope ledger 2026-10-17):
 * a caller without national reach (TSO, DMO, WM) sees a scope node only when it touches the reach. Categorical nodes
 * (`global`, `role`, `geo_class`) carry no geography and stay visible; a wing, division, territory or zone is visible
 * when at least one zone under it is in the reach (its own nodes and their ancestors, and nodes under them); a route,
 * outlet or device when its zone (or the route) is in the reach; a user when it is the caller or homed in a reach zone.
 * National callers are unchanged.
 */
internal class ConfigScope(private val db: Database) {
    fun visible(h: Handle, reach: Reach, type: String, id: Long): Boolean {
        if (reach.national) return true
        val zones = reach.zoneIds.toList()
        fun inZones(sql: String): Boolean =
            h.createQuery(sql).bind("i", id).bindArray("z", Long::class.javaObjectType, zones.ifEmpty { listOf(-1L) }).mapTo(Boolean::class.java).findOne().orElse(false)
        return when (type) {
            "global", "role", "geo_class" -> true
            "zone" -> id in reach.zoneIds
            "territory" -> inZones("SELECT EXISTS (SELECT 1 FROM app.zone WHERE territory_id = :i AND id = ANY(:z))")
            "division" -> inZones("SELECT EXISTS (SELECT 1 FROM app.zone z JOIN app.territory t ON t.id = z.territory_id WHERE t.division_id = :i AND z.id = ANY(:z))")
            "wing" -> inZones("SELECT EXISTS (SELECT 1 FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id WHERE d.wing_id = :i AND z.id = ANY(:z))")
            "route" -> id in reach.routeIds || inZones("SELECT EXISTS (SELECT 1 FROM app.route WHERE id = :i AND zone_id = ANY(:z))")
            "outlet" -> inZones("SELECT EXISTS (SELECT 1 FROM app.outlet WHERE id = :i AND zone_id = ANY(:z))") ||
                h.createQuery("SELECT route_id FROM app.outlet WHERE id = :i").bind("i", id).mapTo(Long::class.javaObjectType).findOne().orElse(null) in reach.routeIds
            "device" -> inZones("SELECT EXISTS (SELECT 1 FROM app.device WHERE id = :i AND zone_id = ANY(:z))")
            "user" -> id == reach.userId || inZones("SELECT EXISTS (SELECT 1 FROM app.app_user WHERE id = :i AND home_zone_id = ANY(:z))")
            else -> false
        }
    }

    /** 403 `ERR_OUT_OF_SCOPE` for a node outside the reach (an unknown node answers the same: no existence leak). */
    fun require(reach: Reach, type: String, id: Long) {
        val ok = db.jdbi.withHandle<Boolean, Exception> { h -> visible(h, reach, type, id) }
        if (!ok) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "scope node outside your reach")
    }

    /**
     * For resolve: the node and every node of its resolution chain must be visible, so a cover route (or an outlet on
     * one) in another zone never shows that zone's winning row; such a node is 403 like any node outside the reach.
     */
    fun requireChain(reach: Reach, chain: List<ScopeNode>) {
        if (reach.national) return
        val ok = db.jdbi.withHandle<Boolean, Exception> { h -> chain.all { visible(h, reach, it.type, it.id) && (it.type != "route" || routeInZones(h, reach, it.id)) } }
        if (!ok) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "scope node outside your reach")
    }

    private fun routeInZones(h: Handle, reach: Reach, id: Long): Boolean =
        h.createQuery("SELECT EXISTS (SELECT 1 FROM app.route WHERE id = :i AND zone_id = ANY(:z))").bind("i", id)
            .bindArray("z", Long::class.javaObjectType, reach.zoneIds.toList().ifEmpty { listOf(-1L) }).mapTo(Boolean::class.java).one()

    fun filter(reach: Reach, rows: List<ConfigValueDto>): List<ConfigValueDto> =
        if (reach.national) rows else db.jdbi.withHandle<List<ConfigValueDto>, Exception> { h -> rows.filter { visible(h, reach, it.scope_type, it.scope_id) } }
}
