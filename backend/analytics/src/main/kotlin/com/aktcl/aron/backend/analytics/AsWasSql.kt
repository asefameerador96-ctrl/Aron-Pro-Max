package com.aktcl.aron.backend.analytics

/**
 * Read half of as-was attribution (D-REPORTS-ASWAS-01, D-REPORTS-ASWAS-02). Each function returns a `JOIN LATERAL (...) alias ON true`
 * clause that is a drop-in for `JOIN dw.dim_geo|dim_product|dim_outlet alias ON alias.key = <expr>`: the same columns, but the
 * placement (zone, territory, division, wing, house, route class and kind; variant, brand, segment, category and unit; outlet
 * channel and class) is the one valid on the row's business date, read from the history views (`dw.v_dim_geo_history`,
 * `v_dim_product_history`, `v_dim_outlet_history`, V0119). Names and codes are the node's current labels (the history views carry
 * ids only). Where no history row covers the date (older than the history, or an entity the history never saw) the current
 * dimension row answers, so today's date and unchanged entities read exactly as before. Expressions are SQL fragments written by
 * this code base (a column of the fact row), never client input. The caller's scope filter still runs over the alias, so scope
 * stays derived from the token's reach.
 */
internal object AsWasSql {
    private const val ACTIVE_HOUSE =
        "(SELECT min(h2.id) FROM app.house h2 WHERE h2.territory_id = hs.territory_id AND lower(h2.name) = lower(hs.name) AND coalesce(to_jsonb(h2)->>'status', 'active') = 'active')"

    private fun window(view: String, key: String, keyExpr: String, date: String) =
        "LEFT JOIN LATERAL (SELECT hh.* FROM $view hh WHERE hh.$key = $keyExpr AND (hh.valid_from IS NULL OR hh.valid_from <= $date) " +
            "AND (hh.valid_to IS NULL OR hh.valid_to > $date) ORDER BY hh.valid_from DESC NULLS LAST LIMIT 1) h ON true"

    /** Name of a geography node: current label of the as-was node, the dimension's own value where the node did not change. */
    private fun geoName(level: String) =
        "CASE WHEN h.${level}_id IS NULL OR h.${level}_id = cg.${level}_id THEN cg.${level}_name ELSE coalesce(n_$level.name, cg.${level}_name) END AS ${level}_name"

    /** `dw.dim_geo` as of [date]: route -> zone -> territory -> division -> wing, house and route class. [join] is JOIN or LEFT JOIN. */
    fun geo(alias: String, route: String, date: String, join: String = "JOIN"): String = """
        $join LATERAL (
          SELECT cg.route_id, cg.route_code, cg.route_name, coalesce(h.route_kind, cg.route_kind) AS route_kind,
                 coalesce(h.visit_kind, cg.visit_kind) AS visit_kind, coalesce(h.route_class, cg.route_class) AS route_class,
                 cg.status, cg.updated_at,
                 coalesce(h.zone_id, cg.zone_id) AS zone_id, ${geoName("zone")},
                 coalesce(h.territory_id, cg.territory_id) AS territory_id, ${geoName("territory")},
                 coalesce(h.division_id, cg.division_id) AS division_id, ${geoName("division")},
                 coalesce(h.wing_id, cg.wing_id) AS wing_id, ${geoName("wing")},
                 CASE WHEN h.route_id IS NULL OR h.zone_id = cg.zone_id THEN cg.house_id ELSE coalesce($ACTIVE_HOUSE, cg.house_id) END AS house_id,
                 CASE WHEN h.route_id IS NULL OR h.zone_id = cg.zone_id THEN cg.house_name ELSE coalesce(hs.name, cg.house_name) END AS house_name
            FROM dw.dim_geo cg
            ${window("dw.v_dim_geo_history", "route_id", "cg.route_id", date)}
            LEFT JOIN app.zone n_zone ON n_zone.id = h.zone_id LEFT JOIN app.territory n_territory ON n_territory.id = h.territory_id
            LEFT JOIN app.division n_division ON n_division.id = h.division_id LEFT JOIN app.wing n_wing ON n_wing.id = h.wing_id
            LEFT JOIN app.house hs ON hs.id = h.house_id
           WHERE cg.route_id = $route
        ) $alias ON true
    """.trimIndent()

    private fun nodeCols(level: String, withCode: Boolean): String {
        val same = "h.${level}_id IS NULL OR h.${level}_id = cp.${level}_id"
        val code = if (withCode) {
            ", CASE WHEN $same THEN cp.${level}_code ELSE coalesce(n_$level.code, 'n' || n_$level.id, cp.${level}_code) END AS ${level}_code"
        } else ""
        return "coalesce(h.${level}_id, cp.${level}_id) AS ${level}_id, " +
            "CASE WHEN $same THEN cp.${level}_name ELSE coalesce(n_$level.name, cp.${level}_name) END AS ${level}_name$code"
    }

    /** `dw.dim_product` as of [date]: SKU -> variant -> brand -> segment -> category, category code and units. */
    fun product(alias: String, sku: String, date: String, join: String = "JOIN"): String = """
        $join LATERAL (
          SELECT cp.sku_id, cp.sku_code, cp.short_name, coalesce(h.base_unit, cp.base_unit) AS base_unit,
                 coalesce(h.base_per_pack, cp.base_per_pack) AS base_per_pack, coalesce(h.category_code, cp.category_code) AS category_code,
                 cp.updated_at, ${nodeCols("variant", true)}, ${nodeCols("brand", true)}, ${nodeCols("segment", true)}, ${nodeCols("category", false)}
            FROM dw.dim_product cp
            ${window("dw.v_dim_product_history", "sku_id", "cp.sku_id", date)}
            LEFT JOIN app.product_node n_variant ON n_variant.id = h.variant_id LEFT JOIN app.product_node n_brand ON n_brand.id = h.brand_id
            LEFT JOIN app.product_node n_segment ON n_segment.id = h.segment_id LEFT JOIN app.product_node n_category ON n_category.id = h.category_id
           WHERE cp.sku_id = $sku
        ) $alias ON true
    """.trimIndent()

    /** `dw.dim_outlet` as of [date]: zone, channel, sub-channel, geo class and kind. */
    fun outlet(alias: String, outlet: String, date: String, join: String = "JOIN"): String = """
        $join LATERAL (
          SELECT co.outlet_id, co.outlet_code, co.outlet_name, co.route_id, co.cluster_id, coalesce(h.zone_id, co.zone_id) AS zone_id,
                 coalesce(h.channel, co.channel) AS channel, coalesce(h.sub_channel_id, co.sub_channel_id) AS sub_channel_id,
                 coalesce(h.geo_class, co.geo_class) AS geo_class, coalesce(h.outlet_kind, co.outlet_kind) AS outlet_kind,
                 co.location_confirmed, co.status, co.updated_at
            FROM dw.dim_outlet co
            ${window("dw.v_dim_outlet_history", "outlet_id", "co.outlet_id", date)}
           WHERE co.outlet_id = $outlet
        ) $alias ON true
    """.trimIndent()

    /** A zone with its territory, division and wing as of [date] (zone-level aggregates carry no route): the as-was replacement of a `DISTINCT` zone chain over `dw.dim_geo`. */
    fun zone(alias: String, zone: String, date: String, join: String = "JOIN"): String = """
        $join LATERAL (
          SELECT cz.zone_id, cz.zone_name,
                 coalesce(h.territory_id, cz.territory_id) AS territory_id, ${zoneName("territory")},
                 coalesce(h.division_id, cz.division_id) AS division_id, ${zoneName("division")},
                 coalesce(h.wing_id, cz.wing_id) AS wing_id, ${zoneName("wing")}
            FROM (SELECT DISTINCT zone_id, zone_name, territory_id, territory_name, division_id, division_name, wing_id, wing_name
                    FROM dw.dim_geo WHERE zone_id = $zone) cz
            ${window("dw.v_dim_geo_history", "zone_id", "cz.zone_id", date)}
            LEFT JOIN app.territory n_territory ON n_territory.id = h.territory_id
            LEFT JOIN app.division n_division ON n_division.id = h.division_id LEFT JOIN app.wing n_wing ON n_wing.id = h.wing_id
        ) $alias ON true
    """.trimIndent()

    private fun zoneName(level: String) =
        "CASE WHEN h.${level}_id IS NULL OR h.${level}_id = cz.${level}_id THEN cz.${level}_name ELSE coalesce(n_$level.name, cz.${level}_name) END AS ${level}_name"
}
