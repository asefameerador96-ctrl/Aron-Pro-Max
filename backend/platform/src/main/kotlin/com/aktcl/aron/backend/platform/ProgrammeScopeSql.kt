package com.aktcl.aron.backend.platform

/**
 * The ONE geography predicate of loyalty programmes (lane portal-loyalty-api, push B3/B4; used by the portal reads in `AdminLoyalty`, by the bundle's `programmes` section,
 * `getLoyaltyBalances`, `listGiftChoices` and the gift picture read, so the five can never disagree).
 *
 * SQL over a programme version row aliased [v] (LEFT JOINed: a programme made before the portal has none and shows to everyone who may read, as "no place"): the version
 * names no wing/division/territory/zone/route (channel rows do not narrow geography), or one of them matches the caller. `programme_version_scope` (V0158): empty means
 * everywhere. The caller binds the three lists of [ProgrammeScopeParams] with `bindList` (placeholders `<vz>`, `<vr>`, `<vrz>`; never empty, see [ProgrammeScopeParams.ids]):
 *  - `<vz>` = outletZones: zones that contain an outlet of the caller's reach; matches zone, territory, division and wing nodes.
 *  - `<vr>` = the caller's routes; a route node matches `route.id IN (<vr>)`.
 *  - `<vrz>` = reachZones: zones the caller holds AS zone reach (TSO, AMO, DMO, WM: not an SR); a route node ALSO matches a route of such a zone (the zone-wide reading of a
 *    route node). An SR holds no zone reach, so a campaign scoped to route X does not reach an SR on route Y of the same zone.
 */
object ProgrammeScopeSql {
    fun geo(v: String, outletZones: String = "<vz>", routes: String = "<vr>", reachZones: String = "<vrz>"): String =
        "(NOT EXISTS (SELECT 1 FROM app.programme_version_scope s WHERE s.programme_version_id = $v.id AND s.node_type IN ('wing','division','territory','zone','route')) " +
            "OR EXISTS (SELECT 1 FROM app.programme_version_scope s WHERE s.programme_version_id = $v.id AND (" +
            "(s.node_type = 'zone' AND s.node_id IN ($outletZones)) " +
            "OR (s.node_type = 'territory' AND EXISTS (SELECT 1 FROM app.zone zz WHERE zz.territory_id = s.node_id AND zz.id IN ($outletZones))) " +
            "OR (s.node_type = 'division' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id WHERE tt.division_id = s.node_id AND zz.id IN ($outletZones))) " +
            "OR (s.node_type = 'wing' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id JOIN app.division dd ON dd.id = tt.division_id WHERE dd.wing_id = s.node_id AND zz.id IN ($outletZones))) " +
            "OR (s.node_type = 'route' AND EXISTS (SELECT 1 FROM app.route rr WHERE rr.id = s.node_id AND (rr.zone_id IN ($reachZones) OR rr.id IN ($routes)))))))"

    /**
     * The same rule for ONE outlet (aliased [o], with `zone_id` and `route_id`): the programme's scope contains this outlet. Used by the balances, so an AMO who covers two
     * zones gets no balance for an outlet outside the programme's scope. A route node is the outlet's own route here (an enrolment names the outlet, not the reader).
     */
    fun outlet(v: String, o: String): String =
        "(NOT EXISTS (SELECT 1 FROM app.programme_version_scope s WHERE s.programme_version_id = $v.id AND s.node_type IN ('wing','division','territory','zone','route')) " +
            "OR EXISTS (SELECT 1 FROM app.programme_version_scope s WHERE s.programme_version_id = $v.id AND (" +
            "(s.node_type = 'zone' AND s.node_id = $o.zone_id) " +
            "OR (s.node_type = 'territory' AND EXISTS (SELECT 1 FROM app.zone zz WHERE zz.territory_id = s.node_id AND zz.id = $o.zone_id)) " +
            "OR (s.node_type = 'division' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id WHERE tt.division_id = s.node_id AND zz.id = $o.zone_id)) " +
            "OR (s.node_type = 'wing' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id JOIN app.division dd ON dd.id = tt.division_id WHERE dd.wing_id = s.node_id AND zz.id = $o.zone_id)) " +
            "OR (s.node_type = 'route' AND s.node_id = $o.route_id))))"

    /** The list to bind for [geo]: never empty. */
    fun ids(l: Collection<Long>): List<Long> = l.toList().ifEmpty { listOf(-1L) }
}

/**
 * The scope parameters of [ProgrammeScopeSql.geo], built by ONE function for the bundle, the three field reads, the gift picture and the portal (B4 of the lead's review: the
 * five used to derive them five ways, which lost the gift picture for an SR and leaked a route campaign to the other SRs of its zone).
 */
class ProgrammeScopeParams(val outletZones: List<Long>, val routes: List<Long>, val reachZones: List<Long>) {
    /** Binds `vz`, `vr` and `vrz` on a statement that contains [ProgrammeScopeSql.geo]. */
    fun <T : org.jdbi.v3.core.statement.SqlStatement<T>> bindTo(q: T): T =
        q.bindList("vz", ProgrammeScopeSql.ids(outletZones)).bindList("vr", ProgrammeScopeSql.ids(routes)).bindList("vrz", ProgrammeScopeSql.ids(reachZones))

    companion object {
        /**
         * [reach] is the caller's [Reach] (null: fail closed, nothing matches). [routes] are the routes of the outlets in play (default: the caller's own `routeIds`).
         * - reachZones: the zones the caller holds as zone reach; empty for a field rep (`ownRecordsOnly`, an SR). For a national reader whole programmes are readable, so
         *   it is the outlet zones (the narrowing the caller asked for does not change which programmes exist).
         * - outletZones: the zones of [routes] plus the reach zones of a non-SR; for an SR the zones of its own routes.
         */
        /**
         * The portal levels (WM, DMO, TSO as readers): the held zones serve as both sets and the routes are the user's own, exactly what `AdminLoyalty` always used (no outlet is in
         * play, so the zones of assigned routes outside the held zones do not widen zone-type nodes: ruling of push B4, no change of portal behaviour).
         */
        fun ofPortal(reach: Reach): ProgrammeScopeParams = reach.zoneIds.toList().let { ProgrammeScopeParams(it, reach.routeIds.toList(), it) }

        fun of(h: org.jdbi.v3.core.Handle, reach: Reach?, routes: Collection<Long>? = null): ProgrammeScopeParams {
            if (reach == null) return ProgrammeScopeParams(emptyList(), emptyList(), emptyList())
            val rs = (routes ?: reach.routeIds).toList()
            val routeZones = if (rs.isEmpty()) emptyList() else h.createQuery("SELECT DISTINCT zone_id FROM app.route WHERE id = ANY(CAST(:r AS bigint[]))")
                .bindArray("r", Long::class.javaObjectType, rs).mapTo(Long::class.java).list()
            val held = if (reach.ownRecordsOnly) emptyList() else reach.zoneIds.toList()
            val outletZones = (routeZones + held).distinct()
            return ProgrammeScopeParams(outletZones, rs, if (reach.national) outletZones else held)
        }
    }
}
