package com.aktcl.aron.feature.amo

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.RouteDay
import com.aktcl.aron.feature.outlet.VisitOutlet

/** Visit kinds the AMO records (contract `VisitKind`). */
object AmoVisitKind {
    const val CONTROL_CALL = "amo_control_call"
    const val JOINT_CALL = "amo_joint_call"
}

/** One row of the route list of a Control or Joint Call (F-AMO-004): the zone's routes, active ones, by code. */
data class AmoRouteRow(val routeId: Long, val title: String, val outletCount: Int)

object ControlCall {
    /** Routes of the zone-wide bundle for the picker: closed routes hidden, ordered by code; the title is the display label or name. */
    fun routes(days: List<RouteDay>): List<AmoRouteRow> =
        days.filter { it.route.status == "active" }
            .sortedWith(compareBy({ it.route.code }, { it.route.routeId }))
            .map { AmoRouteRow(it.route.routeId, "${it.route.displayLabel ?: it.route.name} (${it.route.code})", it.outlets.count { o -> o.status == "active" }) }

    /** Outlets of one route (route ids can repeat across a delta: the first occurrence wins), as the SR picker expects them. */
    fun outlets(days: List<RouteDay>, routeId: Long): List<OutletEntity> =
        days.firstOrNull { it.route.routeId == routeId }?.outlets.orEmpty()

    /** The geo-gated visit target of an outlet, tagged with the AMO visit kind (F-AMO-036: every AMO outlet open is tagged). */
    fun visitOutlet(o: OutletEntity, kind: String = AmoVisitKind.CONTROL_CALL) = VisitOutlet(
        outletId = o.outletId, routeId = o.routeId, name = o.name, code = o.code, lat = o.lat, lng = o.lng,
        locationBasis = if (o.lat == null || o.lng == null) "none" else if (o.locationConfirmed) "master" else "provisional",
        radiusM = o.radiusM, maxAccuracyM = o.maxAccuracyM, visitKind = kind,
    )
}

/**
 * The daily limit of Manual Override (F-AMO-005). MQ-14 leaves the number unknown, so [DEFAULT_MAX] is an assumption
 * (docs/status/android-amo.md); the app reads `cfg.amo.manual_override_max_per_day` when the registry has it.
 */
object ManualOverrideCap {
    const val DEFAULT_MAX = 10

    /** Overrides used today: AMO calls opened by a forced (out-of-range) decision. */
    fun used(todaysVisits: List<VisitEntity>): Int = usedOf(todaysVisits.map { it.visitKind to it.geoAction })

    fun usedOf(kindAndAction: List<Pair<String, String>>): Int =
        kindAndAction.count { (kind, action) -> (kind == AmoVisitKind.CONTROL_CALL || kind == AmoVisitKind.JOINT_CALL) && action == "force_sale" }

    fun reached(todaysVisits: List<VisitEntity>, max: Int = DEFAULT_MAX): Boolean = used(todaysVisits) >= max.coerceAtLeast(0)
}
