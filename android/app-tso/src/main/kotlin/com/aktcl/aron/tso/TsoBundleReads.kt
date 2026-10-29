package com.aktcl.aron.tso

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.feature.tso.mycall.PlanOutlet
import com.aktcl.aron.feature.tso.mycall.PlanRoute
import com.aktcl.aron.feature.tso.mycall.RouteSr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The TSO bundle carries the zone-wide routes and outlets of the TSO's reach with the AMO outlet fields (owner_name, contact_number,
 * open_due_mtk; ruling D-TSO-BUNDLE-OUTLETS, lead-backend f13adb3). The sync engine already stores them in the reference tables,
 * so the Set Plan / Visit Plan / Periphery pickers read them locally: no request, instant, and the same list offline. The online
 * `GET /v1/outlets` stays the fallback for a phone whose bundle has no outlets yet (an older server, a first run before the bundle).
 */
internal object TsoBundleReads {
    /**
     * tso-manual A3 (D-TSO-MAN-34): the bundle's outlets of [routes] for the offline Retailer search, with the zone of their
     * route; the server's filters (status, confirmed coordinates) are applied by NearbyRule, not here.
     */
    fun nearbyOutlets(routes: List<RouteEntity>, outletsByRoute: Map<Long, List<OutletEntity>>): List<com.aktcl.aron.feature.tso.periphery.BundleOutlet> =
        routes.flatMap { r ->
            outletsByRoute[r.routeId].orEmpty().map {
                com.aktcl.aron.feature.tso.periphery.BundleOutlet(
                    it.outletId, it.code, it.name, it.lat, it.lng, it.routeId, r.zoneId, it.status == "active", it.locationConfirmed,
                    it.clusterName.takeIf { c -> c.isNotBlank() }, it.channel.takeIf { c -> c.isNotBlank() }, it.contactNumber?.takeIf { p -> p.isNotBlank() },
                )
            }
        }.distinctBy { it.id }

    /** The outlets of [routeId] in visit order; inactive ones are left out, as the online read does. */
    fun outlets(rows: List<OutletEntity>): List<PlanOutlet> = rows.filter { it.status == "active" }.map {
        PlanOutlet(it.outletId, it.code, it.name, it.nameBn, it.ownerName, it.clusterName.takeIf { c -> c.isNotBlank() }, it.contactNumber?.takeIf { p -> p.isNotBlank() }, it.channel.takeIf { c -> c.isNotBlank() })
    }

    /**
     * What a stored route snapshot says about its SR (contract 1.10.0 `RouteSnapshot.sr_user_id` / `sr_name`, R-W-06 (4)): the
     * route's SR on the bundle date, the same SR `GET /v1/routes` names. [carried] is false when the snapshot has neither member
     * (a server before R-W-06, or an unreadable section); [sr] is null when the route is vacant ("SR Not Set").
     */
    data class SrInBundle(val carried: Boolean, val sr: RouteSr?)

    private val NOT_CARRIED = SrInBundle(false, null)

    /** Reads `sr_user_id` / `sr_name` from a route's raw snapshot section (`route.<id>`: every member but the route and outlets). */
    fun srOf(extras: String?): SrInBundle {
        val o = runCatching { Json.parseToJsonElement(extras ?: return NOT_CARRIED) as? JsonObject }.getOrNull() ?: return NOT_CARRIED
        if ("sr_user_id" !in o) return NOT_CARRIED
        val idEl = o["sr_user_id"]
        if (idEl == null || idEl is JsonNull) return SrInBundle(true, null) // vacant on the bundle date
        val id = (idEl as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 1 } ?: return NOT_CARRIED
        val name = (o["sr_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return NOT_CARRIED
        return SrInBundle(true, RouteSr(id, name))
    }

    /** One active route of the bundle; [carried] says whether its snapshot names the SR ([PlanRoute.sr], null when vacant). */
    data class BundleRoute(val route: PlanRoute, val carried: Boolean)

    /**
     * tso-manual X4b (R-W-12 (9), fix round D-X4b-08): the bundle's active routes, each with what its own snapshot says about its
     * SR, so Set Plan, My Visit Plan and Assign Task work offline and read nothing. A route whose snapshot lacks the members is
     * not carried (an older server, a delta from a mixed version): its SR is null here and the caller fills it from the online
     * read, or the last one kept, before calling it vacant. Null when the bundle has no active route.
     */
    fun routesWithSr(rows: List<RouteEntity>, extrasOf: (Long) -> String?): List<BundleRoute>? {
        val active = rows.filter { it.status == "active" }
        if (active.isEmpty()) return null
        return active.map { r ->
            val s = srOf(extrasOf(r.routeId))
            BundleRoute(PlanRoute(r.routeId, r.code, r.name, r.zoneId, r.displayLabel, s.sr), s.carried)
        }
    }

    /**
     * The offline answer when some route's snapshot does not carry its SR: the bundle where it does, the last online read
     * ([lastRead], by route id) for the others; a route that neither names is vacant ("SR Not Set": no task can be queued for it).
     */
    fun fill(bundle: List<BundleRoute>, lastRead: List<PlanRoute>?): List<PlanRoute> {
        val read = lastRead.orEmpty().associateBy { it.id }
        return bundle.map { b -> if (b.carried) b.route else b.route.copy(sr = read[b.route.id]?.sr) }
    }
}
