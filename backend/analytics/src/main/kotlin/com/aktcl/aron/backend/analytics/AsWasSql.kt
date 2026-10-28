package com.aktcl.aron.backend.analytics

/**
 * Read half of as-was attribution (D-REPORTS-ASWAS-02). Each function returns a `JOIN (...) alias ON ...` clause that is a drop-in for
 * `JOIN dw.dim_geo|dim_product|dim_outlet alias ON alias.key = <expr>` (and a zone chain for zone-level aggregates): the same columns,
 * but the placement (zone, territory, division, wing, house, route class and kind; variant, brand, segment, category and unit;
 * outlet zone, channel and class) is the one valid on the row's business date, read from the history views of V0119
 * (`dw.v_dim_geo_history`, `v_dim_product_history`, `v_dim_outlet_history`) and `app.zone_history|territory_history|division_history`
 * for the zone chain.
 *
 * Shape: the periods are resolved ONCE per query into a timeline per key and joined to the fact rows on
 * `key = key AND date in [as_from, as_to)`, never looked up per fact row (a per-row lateral over the history views measured 25x to
 * 600x slower at fleet size). A key's timeline is its history periods plus the dates the history does not cover (computed with
 * `range_agg`, so a gap or a key the history never saw reads the CURRENT dimension row, exactly as before); the periods of a key
 * never overlap, so the join returns one row per fact row. An optional [lo]/[hi] window (SQL date expressions bound by the caller)
 * keeps only the periods that touch the query's dates. Names and codes are the current labels of the as-was node (the history views
 * carry ids only). Expressions are SQL fragments written by this code base (a column of the fact row), never client input. The
 * caller's scope filter still runs over the alias, so scope stays derived from the token's reach.
 */
internal object AsWasSql {
    /** Per dimension: true when nothing of it changed since the window began, so the plain current-dimension join is exactly as-was. */
    class Plain(val geo: Boolean, val product: Boolean, val outlet: Boolean)

    /** Probe result per database and window start: (millis, answer). */
    private val probes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Plain>>()

    /** How long a probe answer is reused. Tests that edit masters and read at once set it to 0. */
    @Volatile var probeTtlMs: Long = 20_000

    /** A table whose periods the timelines read: the key column and the columns of it that a read uses. */
    private class Tracked(val table: String, val key: String, val cols: List<String>)

    private val GEO_HISTORY = listOf(
        Tracked("route_zone_history", "route_id", listOf("zone_id")),
        Tracked("route_history", "route_id", listOf("kind", "visit_kind", "route_class")),
        Tracked("zone_history", "zone_id", listOf("territory_id", "house_id")),
        Tracked("territory_history", "territory_id", listOf("division_id")),
        Tracked("division_history", "division_id", listOf("wing_id")),
    )
    private val PRODUCT_HISTORY = listOf(
        Tracked("sku_history", "sku_id", listOf("variant_id", "category_code", "base_unit", "base_per_pack")),
        Tracked("product_node_history", "node_id", listOf("parent_id")),
    )
    private val OUTLET_HISTORY = listOf(Tracked("outlet_class_history", "outlet_id", listOf("zone_id", "channel", "sub_channel_id", "geo_class", "outlet_kind")))

    /**
     * SQL over [t] (alias `a`) for the keys that have a period closed after [after] whose read columns differ from the next period's
     * (or that has no next period): a status, price type or `sales_enable` edit closes a period too, but changes nothing a read uses.
     */
    private fun changedKeys(t: Tracked, after: String?, select: String = "1"): String =
        "SELECT $select FROM app.${t.table} a LEFT JOIN app.${t.table} b ON b.${t.key} = a.${t.key} AND b.valid_from = a.valid_to " +
            "WHERE a.valid_to IS NOT NULL${if (after != null) " AND a.valid_to > $after" else ""} AND " +
            "(b.${t.key} IS NULL OR ROW(${t.cols.joinToString { "a.$it" }}) IS DISTINCT FROM ROW(${t.cols.joinToString { "b.$it" }}))"

    /**
     * Per dimension (geography, product tree, outlet class): true when no master history period of it closed after [from], i.e. nothing
     * of it changed since the window began, so the current dimension IS the as-was dimension for every row of the window and the plain
     * joins answer exactly (and cost what they always did). A change in one dimension leaves the other two on the plain path.
     */
    fun probe(h: org.jdbi.v3.core.Handle, from: java.time.LocalDate): Plain {
        val key = "${h.connection.catalog}|$from"
        val now = System.currentTimeMillis()
        probes[key]?.let { (at, v) -> if (now - at < probeTtlMs) return v }
        fun none(tables: List<Tracked>) = tables.joinToString(" AND ") { "NOT EXISTS (${changedKeys(it, ":f")})" }
        val v = h.createQuery("SELECT ${none(GEO_HISTORY)}, ${none(PRODUCT_HISTORY)}, ${none(OUTLET_HISTORY)}").bind("f", from)
            .map { rs, _ -> Plain(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)) }.one()
        if (probes.size > 1_000) probes.clear()
        probes[key] = now to v
        return v
    }

    private const val ACTIVE_HOUSE =
        "(SELECT min(h2.id) FROM app.house h2 WHERE h2.territory_id = hs.territory_id AND lower(h2.name) = lower(hs.name) AND coalesce(to_jsonb(h2)->>'status', 'active') = 'active')"

    /** Predicate keeping the periods ([from], [to] columns, NULL = open) that touch the window. */
    private fun win(from: String, to: String, lo: String?, hi: String?): String = listOfNotNull(
        lo?.let { "($to IS NULL OR $to > CAST($it AS date))" },
        hi?.let { "($from IS NULL OR $from <= CAST($hi AS date))" },
    ).joinToString(" AND ").ifEmpty { "true" }

    /** The fact row's date falls in the alias's period (`as_from`/`as_to` are never NULL: open ends are infinities). */
    private fun on(alias: String, key: String, keyExpr: String, date: String) =
        "ON $alias.$key = $keyExpr AND $date >= $alias.as_from AND $date < $alias.as_to"

    /** CTEs `cov` and `gap`: the dates of each key in [base] that `per` does not cover (the whole line for a key with no period). */
    private fun gaps(key: String, base: String) =
        "cov AS (SELECT $key, range_agg(daterange(vf, vt, '[)')) AS m FROM per GROUP BY $key),\n" +
            "          gap AS (SELECT b.$key, lower(u) AS vf, upper(u) AS vt FROM $base b LEFT JOIN cov ON cov.$key = b.$key\n" +
            "                   CROSS JOIN LATERAL unnest(datemultirange(daterange(NULL, NULL, '[)')) - coalesce(cov.m, '{}'::datemultirange)) u)"

    private fun geoName(level: String) =
        "coalesce(n_$level.name, cg.${level}_name) AS ${level}_name"

    /** `dw.dim_geo` as of the date: route -> zone -> territory -> division -> wing, house, route kind and class. [join] is JOIN or LEFT JOIN. */
    fun geo(alias: String, route: String, date: String, join: String = "JOIN", lo: String? = null, hi: String? = null, plain: Boolean = false): String =
        if (plain) "$join dw.dim_geo $alias ON $alias.route_id = $route" else """
        $join (
          WITH per AS (SELECT hh.route_id, hh.valid_from AS vf, hh.valid_to AS vt, hh.zone_id, hh.house_id, hh.territory_id, hh.division_id, hh.wing_id,
                              hh.route_kind, hh.visit_kind, hh.route_class
                         FROM dw.v_dim_geo_history hh WHERE ${win("hh.valid_from", "hh.valid_to", lo, hi)}),
          ${gaps("route_id", "dw.dim_geo")},
          h AS (SELECT route_id, vf, vt, zone_id, house_id, territory_id, division_id, wing_id, route_kind, visit_kind, route_class, true AS has FROM per
                UNION ALL SELECT route_id, vf, vt, NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::text, NULL::text, NULL::text, false FROM gap)
          SELECT cg.route_id, cg.route_code, cg.route_name, coalesce(h.route_kind, cg.route_kind) AS route_kind,
                 coalesce(h.visit_kind, cg.visit_kind) AS visit_kind, coalesce(h.route_class, cg.route_class) AS route_class, cg.status, cg.updated_at,
                 coalesce(h.zone_id, cg.zone_id) AS zone_id, ${geoName("zone")},
                 coalesce(h.territory_id, cg.territory_id) AS territory_id, ${geoName("territory")},
                 coalesce(h.division_id, cg.division_id) AS division_id, ${geoName("division")},
                 coalesce(h.wing_id, cg.wing_id) AS wing_id, ${geoName("wing")},
                 CASE WHEN h.has THEN $ACTIVE_HOUSE ELSE cg.house_id END AS house_id,
                 CASE WHEN h.has THEN hs.name ELSE cg.house_name END AS house_name,
                 coalesce(h.vf, '-infinity'::date) AS as_from, coalesce(h.vt, 'infinity'::date) AS as_to
            FROM h JOIN dw.dim_geo cg ON cg.route_id = h.route_id
            LEFT JOIN app.zone n_zone ON n_zone.id = coalesce(h.zone_id, cg.zone_id) LEFT JOIN app.territory n_territory ON n_territory.id = coalesce(h.territory_id, cg.territory_id)
            LEFT JOIN app.division n_division ON n_division.id = coalesce(h.division_id, cg.division_id) LEFT JOIN app.wing n_wing ON n_wing.id = coalesce(h.wing_id, cg.wing_id)
            LEFT JOIN app.house hs ON hs.id = h.house_id
        ) $alias ${on(alias, "route_id", route, date)}
    """.trimIndent()

    private fun nodeCols(level: String, withCode: Boolean): String {
        val code = if (withCode) {
            ", coalesce(n_$level.code, 'n' || n_$level.id, cp.${level}_code) AS ${level}_code"
        } else ""
        return "coalesce(h.${level}_id, cp.${level}_id) AS ${level}_id, " +
            "coalesce(n_$level.name, cp.${level}_name) AS ${level}_name$code"
    }

    private fun productBody(lo: String?, hi: String?): String = """
          WITH per AS (SELECT hh.sku_id, hh.valid_from AS vf, hh.valid_to AS vt, hh.variant_id, hh.brand_id, hh.segment_id, hh.category_id, hh.category_code,
                              hh.base_unit, hh.base_per_pack
                         FROM dw.v_dim_product_history hh WHERE ${win("hh.valid_from", "hh.valid_to", lo, hi)}),
          ${gaps("sku_id", "dw.dim_product")},
          h AS (SELECT sku_id, vf, vt, variant_id, brand_id, segment_id, category_id, category_code, base_unit, base_per_pack FROM per
                UNION ALL SELECT sku_id, vf, vt, NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::text, NULL::text, NULL::int FROM gap)
          SELECT cp.sku_id, cp.sku_code, cp.short_name, coalesce(h.base_unit, cp.base_unit) AS base_unit,
                 coalesce(h.base_per_pack, cp.base_per_pack) AS base_per_pack, coalesce(h.category_code, cp.category_code) AS category_code,
                 cp.updated_at, ${nodeCols("variant", true)}, ${nodeCols("brand", true)}, ${nodeCols("segment", true)}, ${nodeCols("category", false)},
                 coalesce(h.vf, '-infinity'::date) AS as_from, coalesce(h.vt, 'infinity'::date) AS as_to
            FROM h JOIN dw.dim_product cp ON cp.sku_id = h.sku_id
            LEFT JOIN app.product_node n_variant ON n_variant.id = coalesce(h.variant_id, cp.variant_id) LEFT JOIN app.product_node n_brand ON n_brand.id = coalesce(h.brand_id, cp.brand_id)
            LEFT JOIN app.product_node n_segment ON n_segment.id = coalesce(h.segment_id, cp.segment_id) LEFT JOIN app.product_node n_category ON n_category.id = coalesce(h.category_id, cp.category_id)"""

    /** `dw.dim_product` as of the date: SKU -> variant -> brand -> segment -> category, category code and units. */
    fun product(alias: String, sku: String, date: String, join: String = "JOIN", lo: String? = null, hi: String? = null, plain: Boolean = false): String =
        if (plain) "$join dw.dim_product $alias ON $alias.sku_id = $sku" else "$join (${productBody(lo, hi)}\n        ) $alias ${on(alias, "sku_id", sku, date)}"

    /**
     * Every product row the timeline holds for the window (the as-was rows that overlap the window; the current row where the history has no
     * period), as a FROM item for lists of nodes, categories and units that a product filter selects ("which brands, which unit").
     */
    fun productAll(alias: String, lo: String? = null, hi: String? = null, plain: Boolean = false): String = if (plain) "dw.dim_product $alias" else
        "(SELECT z.* FROM (${productBody(lo, hi)}\n        ) z WHERE ${if (lo != null && hi != null) "z.as_from <= CAST($hi AS date) AND z.as_to > CAST($lo AS date)" else "true"}) $alias"

    /** `dw.dim_outlet` as of the date: zone, channel, sub-channel, geo class and kind. */
    fun outlet(alias: String, outlet: String, date: String, join: String = "JOIN", lo: String? = null, hi: String? = null, plain: Boolean = false): String =
        if (plain) "$join dw.dim_outlet $alias ON $alias.outlet_id = $outlet" else """
        $join (
          WITH chg AS (${changedKeys(OUTLET_HISTORY[0], lo?.let { "CAST($it AS date)" }, "DISTINCT a.outlet_id")}),
          per AS (SELECT hh.outlet_id, hh.valid_from AS vf, hh.valid_to AS vt, hh.zone_id, hh.channel, hh.sub_channel_id, hh.geo_class, hh.outlet_kind
                    FROM dw.v_dim_outlet_history hh JOIN chg ON chg.outlet_id = hh.outlet_id WHERE ${win("hh.valid_from", "hh.valid_to", lo, hi)}),
          ${gaps("outlet_id", "(SELECT o.* FROM dw.dim_outlet o JOIN chg ON chg.outlet_id = o.outlet_id)")},
          h AS (SELECT outlet_id, vf, vt, zone_id, channel, sub_channel_id, geo_class, outlet_kind FROM per
                UNION ALL SELECT outlet_id, vf, vt, NULL::bigint, NULL::text, NULL::bigint, NULL::text, NULL::text FROM gap
                UNION ALL SELECT o.outlet_id, NULL::date, NULL::date, NULL::bigint, NULL::text, NULL::bigint, NULL::text, NULL::text
                            FROM dw.dim_outlet o WHERE NOT EXISTS (SELECT 1 FROM chg WHERE chg.outlet_id = o.outlet_id))
          SELECT co.outlet_id, co.outlet_code, co.outlet_name, co.route_id, co.cluster_id, coalesce(h.zone_id, co.zone_id) AS zone_id,
                 coalesce(h.channel, co.channel) AS channel, coalesce(h.sub_channel_id, co.sub_channel_id) AS sub_channel_id,
                 coalesce(h.geo_class, co.geo_class) AS geo_class, coalesce(h.outlet_kind, co.outlet_kind) AS outlet_kind,
                 co.location_confirmed, co.status, co.updated_at, coalesce(h.vf, '-infinity'::date) AS as_from, coalesce(h.vt, 'infinity'::date) AS as_to
            FROM h JOIN dw.dim_outlet co ON co.outlet_id = h.outlet_id
        ) $alias ${on(alias, "outlet_id", outlet, date)}
    """.trimIndent()

    /**
     * A zone with its territory, division and wing as of the date (zone-level aggregates carry no route): the as-was replacement of a
     * `DISTINCT` zone chain over `dw.dim_geo`. Zones come from `app.zone`, so a zone that has since lost its routes keeps its rows.
     */
    fun zone(alias: String, zone: String, date: String, join: String = "JOIN", lo: String? = null, hi: String? = null, plain: Boolean = false): String =
        if (plain) "$join (SELECT z.id AS zone_id, z.name AS zone_name, t.id AS territory_id, t.name AS territory_name, d.id AS division_id, d.name AS division_name, d.wing_id, w.name AS wing_name FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id) $alias ON $alias.zone_id = $zone" else """
        $join (
          WITH per AS (SELECT zh.zone_id, lower(p.r) AS vf, upper(p.r) AS vt, zh.territory_id, th.division_id, dv.wing_id
                         FROM app.zone_history zh
                         JOIN app.territory_history th ON th.territory_id = zh.territory_id AND daterange(th.valid_from, th.valid_to, '[)') && daterange(zh.valid_from, zh.valid_to, '[)')
                         JOIN app.division_history dv ON dv.division_id = th.division_id
                          AND daterange(dv.valid_from, dv.valid_to, '[)') && (daterange(zh.valid_from, zh.valid_to, '[)') * daterange(th.valid_from, th.valid_to, '[)'))
                        CROSS JOIN LATERAL (SELECT daterange(zh.valid_from, zh.valid_to, '[)') * daterange(th.valid_from, th.valid_to, '[)') * daterange(dv.valid_from, dv.valid_to, '[)') AS r) p
                        WHERE ${win("lower(p.r)", "upper(p.r)", lo, hi)}),
          cov AS (SELECT zone_id, range_agg(daterange(vf, vt, '[)')) AS m FROM per GROUP BY zone_id),
          gap AS (SELECT z.id AS zone_id, lower(u) AS vf, upper(u) AS vt, z.territory_id, t.division_id, d.wing_id
                    FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division d ON d.id = t.division_id LEFT JOIN cov ON cov.zone_id = z.id
                   CROSS JOIN LATERAL unnest(datemultirange(daterange(NULL, NULL, '[)')) - coalesce(cov.m, '{}'::datemultirange)) u),
          h AS (SELECT zone_id, vf, vt, territory_id, division_id, wing_id FROM per UNION ALL SELECT zone_id, vf, vt, territory_id, division_id, wing_id FROM gap)
          SELECT h.zone_id, n_zone.name AS zone_name, h.territory_id, n_territory.name AS territory_name, h.division_id, n_division.name AS division_name,
                 h.wing_id, n_wing.name AS wing_name, coalesce(h.vf, '-infinity'::date) AS as_from, coalesce(h.vt, 'infinity'::date) AS as_to
            FROM h JOIN app.zone n_zone ON n_zone.id = h.zone_id JOIN app.territory n_territory ON n_territory.id = h.territory_id
            JOIN app.division n_division ON n_division.id = h.division_id JOIN app.wing n_wing ON n_wing.id = h.wing_id
        ) $alias ${on(alias, "zone_id", zone, date)}
    """.trimIndent()
}
