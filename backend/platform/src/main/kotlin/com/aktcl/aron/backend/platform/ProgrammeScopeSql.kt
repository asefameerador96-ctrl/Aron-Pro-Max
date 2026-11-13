package com.aktcl.aron.backend.platform

/**
 * The ONE geography predicate of loyalty programmes (lane portal-loyalty-api, push B3; used by the portal reads in `AdminLoyalty`, by the bundle's `programmes` section,
 * `getLoyaltyBalances`, `listGiftChoices` and the gift picture read, so the five can never disagree).
 *
 * SQL over a programme version row aliased [v] (LEFT JOINed: a programme made before the portal has none and shows to everyone who may read, as "no place"): the version
 * names no wing/division/territory/zone/route (channel rows do not narrow geography), or one of them contains a zone of the reach or is a route of it. `programme_version_scope`
 * (V0158): empty means everywhere. [zones] and [routes] are Jdbi list placeholders (`<vz>`, `<vr>`) the caller binds with `bindList`; an empty list must be bound as `[-1]`
 * (no id is ever -1) because an empty `IN ()` is invalid SQL.
 */
object ProgrammeScopeSql {
    fun geo(v: String, zones: String = "<vz>", routes: String = "<vr>"): String =
        "(NOT EXISTS (SELECT 1 FROM app.programme_version_scope s WHERE s.programme_version_id = $v.id AND s.node_type IN ('wing','division','territory','zone','route')) " +
            "OR EXISTS (SELECT 1 FROM app.programme_version_scope s WHERE s.programme_version_id = $v.id AND (" +
            "(s.node_type = 'zone' AND s.node_id IN ($zones)) " +
            "OR (s.node_type = 'territory' AND EXISTS (SELECT 1 FROM app.zone zz WHERE zz.territory_id = s.node_id AND zz.id IN ($zones))) " +
            "OR (s.node_type = 'division' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id WHERE tt.division_id = s.node_id AND zz.id IN ($zones))) " +
            "OR (s.node_type = 'wing' AND EXISTS (SELECT 1 FROM app.zone zz JOIN app.territory tt ON tt.id = zz.territory_id JOIN app.division dd ON dd.id = tt.division_id WHERE dd.wing_id = s.node_id AND zz.id IN ($zones))) " +
            "OR (s.node_type = 'route' AND EXISTS (SELECT 1 FROM app.route rr WHERE rr.id = s.node_id AND (rr.zone_id IN ($zones) OR rr.id IN ($routes)))))))"

    /** The list to bind for [geo]: never empty. */
    fun ids(l: Collection<Long>): List<Long> = l.toList().ifEmpty { listOf(-1L) }
}
