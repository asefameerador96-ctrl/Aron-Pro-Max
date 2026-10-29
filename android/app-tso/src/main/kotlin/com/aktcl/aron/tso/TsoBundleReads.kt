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

    /** The routes of the bundle without their SR ([PlanRoute.sr] null): the last resort when neither the bundle nor a read names it. */
    fun routes(rows: List<RouteEntity>): List<PlanRoute> = rows.filter { it.status == "active" }.map {
        PlanRoute(it.routeId, it.code, it.name, it.zoneId, it.displayLabel)
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

    /**
     * tso-manual X4b (R-W-12 (9)): the bundle's routes with their SR, so Set Plan, My Visit Plan and Assign Task work offline and
     * read nothing. Null when no active route carries the SR members (an older server): the caller then reads `GET /v1/routes`,
     * as before. A route whose own snapshot lacks them while others carry them is vacant (the server sends both for a named SR).
     */
    fun routesWithSr(rows: List<RouteEntity>, extrasOf: (Long) -> String?): List<PlanRoute>? {
        val active = rows.filter { it.status == "active" }
        if (active.isEmpty()) return null
        val srs = active.associate { it.routeId to srOf(extrasOf(it.routeId)) }
        if (srs.values.none { it.carried }) return null
        return active.map { PlanRoute(it.routeId, it.code, it.name, it.zoneId, it.displayLabel, srs[it.routeId]?.sr) }
    }
}
