package com.aktcl.aron.backend.analytics

/**
 * `retailer-list` (F-WEB-002): the outlets in the caller's reach, with the owner's name and phone as personal columns (masked without the pii claim).
 * Scope is the outlet's own zone and route; the active filter follows `active_status`.
 */
object RetailerListReport : ReportHandler {
    override val definition = definition(
        "retailer-list", "Retailer List", "outlet", "outlet",
        listOf(
            col("code", "Code", "string"), col("name", "Name", "string"), col("owner_name", "Owner", "string", pii = true), col("phone", "Phone", "string", pii = true),
            col("sub_channel", "Sub-channel", "string"), col("status", "Status", "string"), col("zone_name", "Zone", "string"), col("route_code", "Route", "string"),
        ),
        listOf("geo", "active_status"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT o.code, o.name, o.owner_name, o.contact_number AS phone, sc.name AS sub_channel, o.status, z.name AS zone_name, r.code AS route_code
          FROM app.outlet o JOIN app.zone z ON z.id = o.zone_id LEFT JOIN app.route r ON r.id = o.route_id LEFT JOIN app.sub_channel sc ON sc.id = o.sub_channel_id
         WHERE ${ctx.zoneClause("o.zone_id")} AND ${ctx.routeClause("coalesce(o.route_id, -1)")}
           AND (:astatus = 'all' OR (:astatus = 'active' AND o.status = 'active') OR (:astatus = 'inactive' AND o.status <> 'active'))
        """,
        mapOf("astatus" to ctx.query.active_status),
    )
}

/**
 * `sku-list` (F-WEB-008): the catalogue with the prices valid at the end of the period. Retail is the outlet price, trade the distributor price; the trade
 * price is a personal-class column (the role's price types, docs/21), so it is masked without the pii claim. Money stays integer milli-taka per `per_base_qty` base units.
 */
object SkuListReport : ReportHandler {
    override val definition = definition(
        "sku-list", "SKU List", "sales", "sku",
        listOf(
            col("code", "Code", "string"), col("name", "Name", "string"), col("category", "Category", "string"), col("base_unit", "Unit", "string"),
            col("retail_price_mtk", "Retail price", "mtk", unit = "mtk"), col("trade_price_mtk", "Trade price", "mtk", pii = true, unit = "mtk"),
            col("per_base_qty", "Price per", "integer"), col("status", "Status", "string"),
        ),
        listOf("active_status"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT s.code, s.name, s.category_code AS category, s.base_unit, rp.amount_mtk AS retail_price_mtk, tp.amount_mtk AS trade_price_mtk,
               coalesce(rp.per_base_qty, tp.per_base_qty) AS per_base_qty, s.status
          FROM app.sku s
          LEFT JOIN app.sku_price rp ON rp.sku_id = s.id AND rp.price_type = 'outlet' AND rp.valid_from <= :to AND (rp.valid_to IS NULL OR rp.valid_to > :to)
          LEFT JOIN app.sku_price tp ON tp.sku_id = s.id AND tp.price_type = 'distributor' AND tp.valid_from <= :to AND (tp.valid_to IS NULL OR tp.valid_to > :to)
         WHERE (:astatus = 'all' OR (:astatus = 'active' AND s.status = 'active') OR (:astatus = 'inactive' AND s.status <> 'active'))
        """,
        mapOf("astatus" to ctx.query.active_status),
    )
}

/**
 * `geofence-calibration` (F-WEB-067): the distance histogram of in-period SR visits per geo class and territory, with the force-sale share
 * (docs/24 s12: the evidence for the radius an admin sets). Bands in metres; a visit without a distance is not counted.
 */
object GeofenceCalibrationReport : ReportHandler {
    override val definition = definition(
        "geofence-calibration", "Geofence Calibration", "geo", "distance band x geo class x territory",
        listOf(
            col("distance_band_m", "Distance band (m)", "string"), col("geo_class", "Geo class", "string"), col("territory", "Territory", "string"),
            col("visits", "Visits", "integer"), col("force_sale_share", "Force-sale share", "decimal", unit = "%"),
        ),
        listOf("period", "geo"),
    )

    override fun spec(ctx: ReportContext) = SqlSpec(
        """
        SELECT CASE WHEN v.distance_m < 25 THEN '000-025' WHEN v.distance_m < 50 THEN '025-050' WHEN v.distance_m < 100 THEN '050-100'
                    WHEN v.distance_m < 200 THEN '100-200' WHEN v.distance_m < 500 THEN '200-500' ELSE '500+' END AS distance_band_m,
               coalesce(o.geo_class, 'unclassified') AS geo_class, g.territory_name AS territory, count(*)::int AS visits,
               round(100.0 * count(*) FILTER (WHERE v.geo_action = 'force_sale') / count(*), 2) AS force_sale_share
          FROM dw.fact_visit v JOIN dw.dim_geo g ON g.route_id = v.route_id LEFT JOIN dw.dim_outlet o ON o.outlet_id = v.outlet_id
         WHERE ${ctx.dateClause("v.business_date")} AND v.visit_kind = 'sr_call' AND NOT v.voided AND v.distance_m IS NOT NULL
           AND ${ctx.zoneClause("g.zone_id")} AND ${ctx.routeClause("v.route_id")}
         GROUP BY 1, 2, 3
        """,
    )
}
