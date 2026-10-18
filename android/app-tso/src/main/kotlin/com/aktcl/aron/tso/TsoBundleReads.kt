package com.aktcl.aron.tso

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.feature.tso.mycall.PlanOutlet
import com.aktcl.aron.feature.tso.mycall.PlanRoute

/**
 * The TSO bundle carries the zone-wide routes and outlets of the TSO's reach with the AMO outlet fields (owner_name, contact_number,
 * open_due_mtk; ruling D-TSO-BUNDLE-OUTLETS, lead-backend f13adb3). The sync engine already stores them in the reference tables,
 * so the Set Plan / Visit Plan / Periphery pickers read them locally: no request, instant, and the same list offline. The online
 * `GET /v1/outlets` stays the fallback for a phone whose bundle has no outlets yet (an older server, a first run before the bundle).
 */
internal object TsoBundleReads {
    /** The outlets of [routeId] in visit order; inactive ones are left out, as the online read does. */
    fun outlets(rows: List<OutletEntity>): List<PlanOutlet> = rows.filter { it.status == "active" }.map {
        PlanOutlet(it.outletId, it.code, it.name, it.nameBn, it.ownerName, it.clusterName.takeIf { c -> c.isNotBlank() }, it.contactNumber?.takeIf { p -> p.isNotBlank() })
    }

    /** The routes of the bundle. The bundle names no SR on a route (the assignee comes from the online read), so [PlanRoute.sr] is null here. */
    fun routes(rows: List<RouteEntity>): List<PlanRoute> = rows.filter { it.status == "active" }.map {
        PlanRoute(it.routeId, it.code, it.name, it.zoneId, it.displayLabel)
    }
}
