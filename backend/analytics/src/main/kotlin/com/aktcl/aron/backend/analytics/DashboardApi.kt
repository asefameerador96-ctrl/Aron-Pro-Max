package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import org.jdbi.v3.core.Handle
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class NodeRefDto(val type: String, val id: Long, val code: String? = null, val name: String? = null)

/** Contract `DashboardKpis`; definitions in docs/24 s12.4. Percentages 0..100 with 2 decimals, null when the denominator is 0. */
@Serializable
data class DashboardKpis(
    val target_routes: Int, val logged_in_routes: Int, val login_pct: Double?, val sales_submitted_routes: Int,
    val submit_pct_of_logged_in: Double?, val day_completion_pct: Double?, val target_outlets: Int, val visited_outlets: Int,
    val successful_calls: Int, val strike_rate_pct: Double?, val active_memo_count: Int, val gross_mtk: Long, val net_mtk: Long,
    val geo_valid_pct: Double?, val force_sale_pct: Double?, val mock_visits: Int, val suspicious_visits: Int,
    val zones_with_target_routes: Int? = null, val zones_final_submitted: Int? = null, val final_submit_pct: Double? = null,
)

@Serializable
data class CategoryVolume(
    val category_code: String, val base_unit: String, val qty_base: Long, val net_mtk: Long, val report_unit: String? = null, val qty_report: String? = null, val entry_unit: String? = null, val qty_entered: String? = null,
)

@Serializable
data class ChannelVolume(
    val code: String, val name: String? = null, val net_mtk: Long, val qty_base: Long? = null, val memo_count: Int, val successful_calls: Int,
    val memo_ratio_pct: Double? = null,
)

@Serializable
data class GeoValidationChild(val node: NodeRefDto, val visits: Int, val geo_valid_pct: Double?, val suspicious_visits: Int, val geo_valid_visits: Int)

/** Contract `GeoValidationSummary`. */
@Serializable
data class GeoValidationSummary(
    val as_of: String, val from: String, val to: String, val node: NodeRefDto, val visits: Int, val geo_valid_pct: Double?,
    val force_sale_pct: Double?, val mock_pct: Double?, val geo_mismatch_pct: Double?, val suspicious_pct: Double?,
    val signals_by_code: Map<String, Int>, val children: List<GeoValidationChild>,
    /** The numerator of [geo_valid_pct] (web request web-geo-validated-visits), never derived from the rounded percent. */
    val geo_valid_visits: Int,
)

@Serializable
data class DashboardChild(val node: NodeRefDto, val kpis: DashboardKpis)

@Serializable
data class DashboardSummary(
    val as_of: String, val from: String, val to: String, val node: NodeRefDto, val kpis: DashboardKpis,
    val by_category: List<CategoryVolume>, val by_channel: List<ChannelVolume>, val by_brand: List<ChannelVolume>, val children: List<DashboardChild>,
    val by_segment: List<ChannelVolume> = emptyList(),
)

/** Percentage with two decimals (half up), null when the denominator is 0. */
internal fun pct(n: Long, d: Long): Double? =
    if (d <= 0) null else BigDecimal(n).multiply(BigDecimal(100)).divide(BigDecimal(d), 2, RoundingMode.HALF_UP).toDouble()

/**
 * What a caller may see: all zones (national), a set of zones, or (a route-only caller, [routes] non-null) a set of routes.
 * Hashed into the cache key; the cache never crosses scopes.
 */
internal class ZoneScope(val all: Boolean, val zones: List<Long>, val routes: List<Long>? = null) {
    val hash: String by lazy {
        val m = MessageDigest.getInstance("SHA-256")
        m.update((if (all) "ALL" else zones.sorted().joinToString(",")).toByteArray())
        if (routes != null) m.update(("|R:" + routes.sorted().joinToString(",")).toByteArray())
        m.digest().joinToString("") { "%02x".format(it) }.take(24)
    }
    fun clause(col: String = "zone_id") = if (all) "true" else "$col = ANY(:zones)"
}

private data class Level(val col: String?, val nameCol: String?, val childCol: String?, val childName: String?, val childType: String?)
private val LEVELS = mapOf(
    "national" to Level(null, null, "wing_id", "wing_name", "wing"),
    "wing" to Level("wing_id", "wing_name", "division_id", "division_name", "division"),
    "division" to Level("division_id", "division_name", "territory_id", "territory_name", "territory"),
    "territory" to Level("territory_id", "territory_name", "zone_id", "zone_name", "zone"),
    "zone" to Level("zone_id", "zone_name", null, null, "route"),
)
/** [LEVELS] plus `house` (children: its zones; resolved from the reach in nodeScope), for the caller's default node only; an explicit `level=house` stays 400 (contract GeoLevelQuery). */
private val DEFAULT_LEVELS = LEVELS + ("house" to Level(null, null, "zone_id", "zone_name", "zone"))
internal fun isDashboardLevel(type: String): Boolean = type in DEFAULT_LEVELS
/** A route-only caller's default node (D-BC-DASH-ROUTE-KEYED-01): children are its routes, as under a zone. */
private val ROUTE_LEVEL = Level(null, null, null, null, "route")

/**
 * GET /v1/dashboards/summary (F-API-014). Reads `dw` aggregates only (never the transaction log; the SKU master for report units), from the read replica
 * when configured, scoped by the caller's server-side reach; cached [ttl] (5 s) per scope hash, the caller's top nodes, node (and whether it was named or defaulted), date range and, for a brand caller, brands.
 * D-NIGHT-SYNC-01: the cache was 30 s (docs/25 F-API-014), which alone could hold a synced memo off the tile for longer
 * than the 15 s goal; 5 s still folds the 30 s refetches of every open tab of one scope into one query.
 */
class DashboardService(
    private val db: Database,
    private val clock: AronClock = AronClock.SYSTEM,
    private val ttl: Duration = Duration.ofSeconds(5),
) {
    private val cache = ConcurrentHashMap<String, Pair<Instant, DashboardSummary>>()

    /**
     * [brands] non-null is a BRAND caller (docs/34 rule 16, "own brands only"; the ids come from the server's brand scope, never from the
     * request): every brand-bearing figure is cut to those brands and every figure without a brand dimension is hidden, see [compute].
     */
    fun summary(reach: Reach, level: String?, nodeId: Long?, from: LocalDate, to: LocalDate, brands: Set<Long>? = null): DashboardSummary {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 92) {
            throw ApiProblem(ProblemCode.ERR_VALIDATION, "to must be on or after from, at most 92 days", errors = listOf(FieldError("query.to", "out_of_range")))
        }
        val scope = ZoneScope(reach.national, reach.zoneIds.toList(), if (level == null && nodeId == null) routeOnlyNode(reach)?.routes else null)
        val node = resolveNode(reach, level, nodeId)
        val key = "${scope.hash}|${node.first}|${node.second}|$from|$to|${brands?.sorted()?.joinToString(",", "B:") ?: ""}|${topNodesKey(reach)}|${if (level == null && nodeId == null) "D" else ""}"
        val now = clock.now()
        cache[key]?.let { (at, v) -> if (Duration.between(at, now) < ttl) return v }
        val multi = level == null && nodeId == null && !reach.national && reach.topNodes.count { it.type in DEFAULT_LEVELS } > 1
        val v = db.readJdbi.withHandle<DashboardSummary, Exception> { h -> compute(h, reach, scope, node, from, to, multi, brands) }
        if (cache.size > 2_000) { cache.entries.removeIf { Duration.between(it.value.first, now) >= ttl }; if (cache.size > 2_000) cache.clear() }
        cache[key] = now to v
        return v
    }

    /**
     * GET /v1/dashboards/geo-validation (contract getGeoValidation, docs/05, docs/24 s11): for the node (the caller's top
     * node by default, 403 outside the reach) and dates, the visits and the geo-valid, force-sale and mock rates of the
     * `dw.agg_daily_zone` rollup (the same figures as the summary), the device/server verdict mismatch rate over the
     * visits the server re-checked (`dw.fact_visit`), the suspicious-visit rate, and the risk signals raised on those
     * dates in those zones by code (dismissed ones left out; a signal with no zone counts at national level only).
     * Children as in the summary (routes under a zone). Cached [ttl] per scope, node, range, the caller's top nodes and whether the node was named or defaulted (a default read of several top nodes is the whole reach, `multi`), like the summary.
     */
    fun geoValidation(reach: Reach, level: String?, nodeId: Long?, from: LocalDate, to: LocalDate): GeoValidationSummary {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 92) {
            throw ApiProblem(ProblemCode.ERR_VALIDATION, "to must be on or after from, at most 92 days", errors = listOf(FieldError("query.to", "out_of_range")))
        }
        val scope = ZoneScope(reach.national, reach.zoneIds.toList(), if (level == null && nodeId == null) routeOnlyNode(reach)?.routes else null)
        val node = resolveNode(reach, level, nodeId)
        val key = "${scope.hash}|${node.first}|${node.second}|$from|$to|${topNodesKey(reach)}|${if (level == null && nodeId == null) "D" else ""}"
        val now = clock.now()
        geoCache[key]?.let { (at, v) -> if (Duration.between(at, now) < ttl) return v }
        val multi = level == null && nodeId == null && !reach.national && reach.topNodes.count { it.type in DEFAULT_LEVELS } > 1
        val v = db.readJdbi.withHandle<GeoValidationSummary, Exception> { h -> computeGeo(h, nodeScope(h, reach, scope, node, multi, from, to), from, to) }
        if (geoCache.size > 2_000) { geoCache.entries.removeIf { Duration.between(it.value.first, now) >= ttl }; if (geoCache.size > 2_000) geoCache.clear() }
        geoCache[key] = now to v
        return v
    }

    private val geoCache = ConcurrentHashMap<String, Pair<Instant, GeoValidationSummary>>()

    private fun computeGeo(h: Handle, ns: NodeScope, from: LocalDate, to: LocalDate): GeoValidationSummary {
        fun <T> q(sql: String, f: (org.jdbi.v3.core.statement.Query) -> T): T = f(ns.bind(h.createQuery(sql).bind("f", from).bind("t", to)))
        // A route-only caller reads the route-days of its routes (dw.v_route_day, the zone rollup's rows at route grain).
        val days = "${if (ns.routeOnly) "dw.v_route_day" else "dw.agg_daily_zone"} a ${ns.chain("zn", "a.zone_id", "a.business_date")}"
        val inScope = ns.clause("a.zone_id", "a.route_id") + ns.inNode("zn")
        data class Totals(val vis: Long, val gv: Long, val fs: Long, val mock: Long, val susp: Long)
        val t = q(
            "SELECT coalesce(sum(visits),0), coalesce(sum(geo_valid_visits),0), coalesce(sum(force_sale_visits),0), coalesce(sum(mock_visits),0), " +
                "coalesce(sum(suspicious_visits),0) FROM $days WHERE a.business_date BETWEEN :f AND :t AND $inScope",
        ) { it.map { rs, _ -> Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)) }.one() }
        val asOf = q("SELECT max(a.updated_at) FROM $days WHERE a.business_date BETWEEN :f AND :t AND $inScope") {
            it.map { rs, _ -> rs.getObject(1, java.time.OffsetDateTime::class.java)?.toInstant() }.one()
        } ?: clock.now()
        // Same visits as the rollup (SR calls, not voided); only those the server re-checked have a verdict to compare.
        val (checked, mismatched) = q(
            "SELECT count(*) FILTER (WHERE server_verdict IS NOT NULL), count(*) FILTER (WHERE server_verdict IS NOT NULL AND server_verdict <> device_verdict) " +
                "FROM dw.fact_visit a ${ns.chain("zn", "a.zone_id", "a.business_date")} WHERE a.business_date BETWEEN :f AND :t AND NOT a.voided AND a.visit_kind = 'sr_call' AND $inScope",
        ) { it.map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.one() }
        // For a route-only caller, signals on its routes only: a signal with no route (a user- or device-level one) stays out.
        val signals = q(
            "SELECT a.code, count(*) FROM app.risk_signal a ${ns.chain("zn", "a.zone_id", "a.business_date")} WHERE a.business_date BETWEEN :f AND :t AND a.status <> 'dismissed' AND $inScope GROUP BY a.code ORDER BY a.code",
        ) { it.map { rs, _ -> rs.getString(1) to rs.getInt(2) }.list().toMap() }
        val lv = ns.lv
        val asWas = AsWasSql.probe(h, from)
        val children: List<GeoValidationChild> = if (lv.childCol != null && lv.childName != null) {
            q(
                """
                SELECT zg.${lv.childCol} cid, zg.${lv.childName} cname, coalesce(sum(a.visits),0) vis, coalesce(sum(a.geo_valid_visits),0) gv,
                       coalesce(sum(a.suspicious_visits),0) susp
                  FROM dw.agg_daily_zone a ${AsWasSql.zone("zg", "a.zone_id", "a.business_date", lo = ":f", hi = ":t", plain = asWas.geo)}
                 WHERE a.business_date BETWEEN :f AND :t AND ${ns.clause("a.zone_id", "a.route_id")}${ns.inNode("zg")} GROUP BY 1, 2 ORDER BY 1
                """,
            ) { it.map { rs, _ -> GeoValidationChild(NodeRefDto(lv.childType!!, rs.getLong("cid"), null, rs.getString("cname")), rs.getInt("vis"), pct(rs.getLong("gv"), rs.getLong("vis")), rs.getInt("susp"), rs.getInt("gv")) }.list() }
        } else {
            q(
                """
                SELECT g.route_id cid, g.route_name cname, g.route_code ccode, coalesce(sum(a.visits),0) vis, coalesce(sum(a.geo_valid_visits),0) gv,
                       coalesce(sum(a.suspicious_visits),0) susp
                  FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                 WHERE a.business_date BETWEEN :f AND :t AND ${ns.clause("a.zone_id", "a.route_id")} GROUP BY 1, 2, 3 ORDER BY 1
                """,
            ) { it.map { rs, _ -> GeoValidationChild(NodeRefDto("route", rs.getLong("cid"), rs.getString("ccode"), rs.getString("cname")), rs.getInt("vis"), pct(rs.getLong("gv"), rs.getLong("vis")), rs.getInt("susp"), rs.getInt("gv")) }.list() }
        }
        return GeoValidationSummary(
            asOf.wire(), from.toString(), to.toString(), ns.ref, t.vis.toInt(), pct(t.gv, t.vis), pct(t.fs, t.vis), pct(t.mock, t.vis),
            pct(mismatched, checked), pct(t.susp, t.vis), signals, children, t.gv.toInt(),
        )
    }

    /** (level, id): the requested node, or the caller's top reach node. Outside the reach, or unknown, is 403 (no existence leak). */
    private fun resolveNode(reach: Reach, level: String?, nodeId: Long?): Pair<String, Long> {
        val out = ApiProblem(ProblemCode.ERR_FORBIDDEN, "node is outside your reach")
        if (level == null && nodeId == null) {
            if (reach.national) return "national" to 0L
            // Cover routes outside the caller's zones are reach nodes too, but not a dashboard level: label with a geo node.
            // Route assignments only: the caller's routes, read route-keyed (D-BC-DASH-ROUTE-KEYED-01; zone figures would show other routes).
            routeOnlyNode(reach)?.let { return "route" to it.ref.id }
            val top = reach.topNodes.firstOrNull { it.type in DEFAULT_LEVELS } ?: throw out
            return top.type to top.id
        }
        val lv = level ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "node_id needs level", errors = listOf(FieldError("query.level", "required")))
        if (lv !in LEVELS) throw ApiProblem(ProblemCode.ERR_VALIDATION, "level must be national, wing, division, territory or zone", errors = listOf(FieldError("query.level", "invalid_value")))
        if (lv == "national") {
            if (!reach.national) throw out
            return "national" to 0L
        }
        return lv to (nodeId?.takeIf { it > 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "node_id required", errors = listOf(FieldError("query.node_id", "required"))))
    }

    /** [routes] non-null: a route-only caller, every read filters by route (D-BC-DASH-ROUTE-KEYED-01); otherwise by the zones of [eff]. */
    private class NodeScope(val lv: Level, val eff: ZoneScope, val ref: NodeRefDto, val routes: List<Long>? = null, val member: Pair<String, Long>? = null) {
        /**
         * Exact as-was membership (D-REPORTS-NODEASWAS-01): for a wing, division or territory whose membership changed in the window, [eff] holds the
         * zones it had on ANY date of the window (a prefilter, and the reach check) and a row counts only when the zone it was written under was in
         * the node on the row's own date: the zone chain of that zone and date, joined as [alias], must name the node. No change: no join, no filter.
         */
        fun chain(alias: String, zone: String, date: String) = if (member == null) "" else AsWasSql.zone(alias, zone, date, lo = ":f", hi = ":t")
        fun inNode(alias: String) = if (member == null) "" else " AND $alias.${member.first} = :nodeId"
        val routeOnly: Boolean get() = routes != null
        fun clause(zoneCol: String, routeCol: String) = if (routes != null) "$routeCol = ANY(:routes)" else eff.clause(zoneCol)
        fun bind(st: org.jdbi.v3.core.statement.Query): org.jdbi.v3.core.statement.Query = st.also {
            if (routes != null) it.bindArray("routes", Long::class.javaObjectType, routes)
            else if (!eff.all) it.bindArray("zones", Long::class.javaObjectType, eff.zones)
            if (member != null) it.bind("nodeId", member.second)
        }
    }

    /** The zones a node covers, narrowed to the reach (403 outside it), and its label; shared by the summary and geo-validation reads. */
    private fun nodeScope(h: Handle, reach: Reach, scope: ZoneScope, node: Pair<String, Long>, multi: Boolean, from: LocalDate, to: LocalDate): NodeScope {
        if (node.first == "route") {
            val rn = routeOnlyNode(reach) ?: throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "node is outside your reach")
            return NodeScope(ROUTE_LEVEL, ZoneScope(false, emptyList()), rn.ref, rn.routes)
        }
        val lv = DEFAULT_LEVELS.getValue(node.first)
        if (node.first == "house") {
            // D-BC-DASH-HOUSE-01: the house's zones as the reach has them (`app.zone.house_id`; `dw.dim_geo` folds same-name houses
            // into one). With other top nodes too these are the whole reach, as the multi-node default of [resolveScopedNode].
            val top = reach.topNodes.first { it.type == "house" }
            val zones = reach.zoneIds.toList().ifEmpty { throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "node is outside your reach") }
            return NodeScope(lv, ZoneScope(false, zones), NodeRefDto("house", top.id, top.code, top.name))
        }
        // Zones of the node, narrowed to the reach. A caller with several top nodes and no selector reads the whole reach (labelled by the first node).
        // As was (D-REPORTS-NODEASWAS-01): the zones the node had on any date of the window, not the zones it has now, so a drill-down on an old
        // date reads the node as it was. Window never changed: the current structure answers (the plain path, the old SQL).
        val members = if (lv.col == null || multi) null else memberZones(h, lv.col, listOf(node.second), from, to)
        if (members != null && !members.allowed(reach, listOf(node.second))) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "node is outside your reach")
        val nodeZones: List<Long> = members?.readable(if (reach.national) null else reach.zoneIds) ?: emptyList()
        val eff = when {
            multi -> ZoneScope(false, reach.zoneIds.toList().ifEmpty { listOf(-1L) })
            lv.col != null -> ZoneScope(false, nodeZones)
            else -> scope
        }
        // The label is the node's own current name (a node that has lost every zone since still has one).
        val nodeRef = if (lv.col == null) NodeRefDto("national", 0, "national", "National") else
            h.createQuery("SELECT name FROM app.${node.first} WHERE id = :id").bind("id", node.second).mapTo(String::class.java).first()
                .let { NodeRefDto(node.first, node.second, null, it) }
        return NodeScope(lv, eff, nodeRef, member = if (members != null && !members.plain) lv.col!! to node.second else null)
    }

    private fun compute(h: Handle, reach: Reach, scope: ZoneScope, node: Pair<String, Long>, from: LocalDate, to: LocalDate, multi: Boolean, brands: Set<Long>? = null): DashboardSummary {
        val ns = nodeScope(h, reach, scope, node, multi, from, to)
        val lv = ns.lv
        val nodeRef = ns.ref
        val asWas = AsWasSql.probe(h, from)

        // A BRAND caller (D-REPORTS-BRANDSCOPE-01): `:scopeBrands` is bound only where a query uses it (an unused binding is an error).
        fun <T> Handle.q(sql: String, f: (org.jdbi.v3.core.statement.Query) -> T): T = f(ns.bind(createQuery(sql).bind("f", from).bind("t", to)).also {
            if (brands != null && sql.contains(":scopeBrands")) it.bindArray("scopeBrands", Long::class.javaObjectType, brands.toList().ifEmpty { listOf(-1L) })
        })
        val brandOnly = brands != null
        val sums = "coalesce(sum(target_routes),0) tr, coalesce(sum(logged_in_routes),0) lr, coalesce(sum(sales_submitted_routes),0) sr, " +
            "coalesce(sum(target_outlets),0) tout, coalesce(sum(visited_outlets),0) vout, coalesce(sum(successful_calls),0) sc, coalesce(sum(visits),0) vis, " +
            "coalesce(sum(geo_valid_visits),0) gv, coalesce(sum(force_sale_visits),0) fs, coalesce(sum(mock_visits),0) mock, coalesce(sum(suspicious_visits),0) susp, " +
            "coalesce(sum(active_memo_count),0) memos, coalesce(sum(gross_mtk),0) gross, coalesce(sum(net_mtk),0) net, " +
            "count(*) FILTER (WHERE target_routes > 0) zt, count(*) FILTER (WHERE target_routes > 0 AND final_submitted) zf"
        // A route-only caller (D-BC-DASH-ROUTE-KEYED-01): the zone rollup's rules applied to its route-days (dw.v_route_day). The
        // final-submit KPIs stay in zone-days: the zone-days its target routes were stamped with, and of those the final-submitted ones.
        val routeSums = "count(*) FILTER (WHERE is_target_route) tr, count(*) FILTER (WHERE is_target_route AND successful_login) lr, " +
            "count(*) FILTER (WHERE is_target_route AND sales_submitted) sr, coalesce(sum(target_outlets) FILTER (WHERE is_target_route),0) tout, " +
            "coalesce(sum(visited_outlets),0) vout, coalesce(sum(successful_calls),0) sc, coalesce(sum(visits),0) vis, " +
            "coalesce(sum(geo_valid_visits),0) gv, coalesce(sum(force_sale_visits),0) fs, coalesce(sum(mock_visits),0) mock, coalesce(sum(suspicious_visits),0) susp, " +
            "coalesce(sum(active_memo_count),0) memos, coalesce(sum(gross_mtk),0) gross, coalesce(sum(net_mtk),0) net, " +
            "count(DISTINCT (zone_id, business_date)) FILTER (WHERE is_target_route) zt, " +
            "count(DISTINCT (zone_id, business_date)) FILTER (WHERE is_target_route AND zone_final_submitted) zf"
        // The zone rollup is joined to its zone chain of the day only when the node's membership changed in the window (see NodeScope.chain).
        val zoneDays = "dw.agg_daily_zone a ${ns.chain("zn", "a.zone_id", "a.business_date")}"
        val days = if (ns.routeOnly) "SELECT $routeSums FROM dw.v_route_day" else "SELECT $sums FROM $zoneDays"
        val inScope = if (ns.routeOnly) ns.clause("zone_id", "route_id") else ns.clause("a.zone_id", "a.route_id") + ns.inNode("zn")
        // The all-brand figures are not read for a BRAND caller (nothing of them is shown; its KPIs come from by_brand).
        val kpis by lazy { h.q("$days WHERE business_date BETWEEN :f AND :t AND $inScope") { it.map { rs, _ -> kpisOf(rs) }.one() } }
        if (!brandOnly) kpis   // read now, before any other result set is open on this handle
        val asOf = h.q("SELECT max(a.updated_at) FROM ${if (ns.routeOnly) "dw.v_route_day a" else zoneDays} WHERE a.business_date BETWEEN :f AND :t AND $inScope") {
            it.map { rs, _ -> rs.getObject(1, java.time.OffsetDateTime::class.java)?.toInstant() }.one()
        } ?: clock.now()

        // Report unit and quantity (contract CategoryVolume) only when every SKU of the group has the same report unit; the
        // quantity is the exact sum of qty_base / report_factor (base units per report unit) rounded once to 3 decimals, half up.
        // net_mtk is the sale-line value: free, DRP and offer lines are offset by their discounts, and memo-level QC
        // settlements and rounding are not attributed to a SKU (the same basis as by_brand and by_segment).
        val byCategory = h.q(
            """
            WITH k AS (
              SELECT p.category_code, p.base_unit, s.report_unit, s.report_factor, p.base_per_pack, sum(a.sold_qty_base + a.free_qty_base) qty, sum(a.gross_mtk) gross
                FROM dw.agg_daily_route_sku a ${AsWasSql.product("p", "a.sku_id", "a.business_date", lo = ":f", hi = ":t", plain = asWas.product)} ${AsWasSql.geo("g", "a.route_id", "a.business_date", lo = ":f", hi = ":t", plain = asWas.geo)}
                JOIN app.sku s ON s.id = a.sku_id
               WHERE a.business_date BETWEEN :f AND :t AND ${ns.clause("g.zone_id", "a.route_id")}${ns.inNode("g")}${if (brandOnly) " AND p.brand_id = ANY(:scopeBrands)" else ""} GROUP BY 1, 2, 3, 4, 5)
            SELECT category_code, base_unit, sum(qty) qty, sum(gross) gross,
                   CASE WHEN count(*) = count(report_unit) AND count(DISTINCT report_unit) = 1 THEN min(report_unit) END ru,
                   CASE WHEN count(*) = count(report_unit) AND count(DISTINCT report_unit) = 1 THEN round(sum(qty / report_factor), 3) END qr,
                   CASE WHEN base_unit = 'dozen' THEN 'dozen' ELSE 'pack' END eu,
                   round(sum(qty::numeric / CASE WHEN base_unit = 'dozen' THEN 1 ELSE base_per_pack END), 3) qe
              FROM k GROUP BY 1, 2 ORDER BY 1, 2
            """,
        ) {
            it.map { rs, _ ->
                CategoryVolume(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getBigDecimal(6)?.setScale(3, RoundingMode.HALF_UP)?.toPlainString(),
                    rs.getString(7), rs.getBigDecimal(8).setScale(3, RoundingMode.HALF_UP).toPlainString())
            }.list()
        }

        // The segment rollup carries no brand, so a BRAND caller reads the SKU rollup (the product tree as it was on each day, brand filter on the
        // brand then) and gets no memo count: memos of the caller's brands in one segment cannot be added across brands without double counting.
        val bySegment = h.q(
            if (brandOnly) {
                """
                SELECT p.segment_id, p.segment_name, sum(a.gross_mtk) net, sum(a.sold_qty_base) qty, 0 memos
                  FROM dw.agg_daily_route_sku a ${AsWasSql.product("p", "a.sku_id", "a.business_date", lo = ":f", hi = ":t", plain = asWas.product)} ${AsWasSql.geo("g", "a.route_id", "a.business_date", lo = ":f", hi = ":t", plain = asWas.geo)}
                 WHERE a.business_date BETWEEN :f AND :t AND ${ns.clause("g.zone_id", "a.route_id")}${ns.inNode("g")} AND p.brand_id = ANY(:scopeBrands) GROUP BY 1, 2 HAVING sum(a.gross_mtk) <> 0 OR sum(a.sold_qty_base) <> 0 ORDER BY 1
                """
            } else {
                """
                SELECT a.segment_id, p.segment_name, sum(a.gross_mtk) net, sum(a.sold_qty_base) qty, sum(a.memo_count) memos
                  FROM dw.agg_daily_route_segment a ${AsWasSql.geo("g", "a.route_id", "a.business_date", lo = ":f", hi = ":t", plain = asWas.geo)}
                  JOIN LATERAL (SELECT coalesce((SELECT name FROM app.product_node WHERE id = a.segment_id), 'n' || a.segment_id) AS segment_name) p ON true
                 WHERE a.business_date BETWEEN :f AND :t AND ${ns.clause("g.zone_id", "a.route_id")}${ns.inNode("g")} GROUP BY 1, 2 ORDER BY 1
                """
            },
        ) {
            it.map { rs, _ ->
                val memos = rs.getInt(5)
                // As for brands, one memo is one successful call of the segment.
                ChannelVolume(rs.getLong(1).toString(), rs.getString(2), rs.getLong(3), rs.getLong(4), memos, memos, if (brandOnly) null else pct(memos.toLong(), kpis.active_memo_count.toLong()))
            }.list()
        }

        // A route-only caller: the channel frozen on the memo at capture, by the route that sold (dw.v_route_day_channel), so a later
        // outlet move or reclassification never pulls in another route's sales; memos with no channel on record are `unknown`.
        // A BRAND caller gets no channel split: the outlet rollups carry no brand (docs/34, the cards without a brand dimension are hidden).
        val byChannel = if (brandOnly) emptyList() else h.q(
            if (ns.routeOnly) {
                """
                SELECT coalesce(c.outlet_channel, 'unknown'), sum(c.net_mtk) net, sum(c.active_memo_count) memos, sum(c.successful_calls) calls
                  FROM dw.v_route_day_channel c
                 WHERE c.business_date BETWEEN :f AND :t AND c.route_id = ANY(:routes) GROUP BY 1 ORDER BY 1
                """
            } else {
                """
                SELECT o.channel, sum(a.net_mtk) net, sum(a.active_memo_count) memos, count(*) FILTER (WHERE a.active_memo_count > 0) calls
                  FROM dw.agg_daily_outlet a ${AsWasSql.outlet("o", "a.outlet_id", "a.business_date", lo = ":f", hi = ":t", plain = asWas.outlet)} ${ns.chain("zn", "o.zone_id", "a.business_date")}
                 WHERE a.business_date BETWEEN :f AND :t AND ${ns.eff.clause("o.zone_id")}${ns.inNode("zn")} GROUP BY 1 ORDER BY 1
                """
            },
        ) { it.map { rs, _ -> ChannelVolume(rs.getString(1), null, rs.getLong(2), null, rs.getInt(3), rs.getInt(4)) }.list() }   // qty_base null: base units differ per category and are never added (the channel STD is the national dashboard's cigarette card)

        // BSR per brand (docs/24 s12.4): active memos containing the brand / active memos, over the same route-days.
        val totalMemos = if (brandOnly) 0L else kpis.active_memo_count.toLong()
        val byBrand = h.q(
            """
            SELECT b.brand_id, p.brand_name, sum(b.gross_mtk) net, sum(b.sold_qty_base) qty, sum(b.memo_count) memos
              FROM dw.agg_daily_route_brand b ${AsWasSql.geo("g", "b.route_id", "b.business_date", lo = ":f", hi = ":t", plain = asWas.geo)}
              JOIN LATERAL (SELECT coalesce((SELECT name FROM app.product_node WHERE id = b.brand_id), 'n' || b.brand_id) AS brand_name) p ON true
             WHERE b.business_date BETWEEN :f AND :t AND ${ns.clause("g.zone_id", "b.route_id")}${ns.inNode("g")}${if (brandOnly) " AND b.brand_id = ANY(:scopeBrands)" else ""} GROUP BY 1, 2 ORDER BY 1
            """,
        ) {
            it.map { rs, _ ->
                val memos = rs.getInt(5)
                // Successful calls of a brand are not stored per brand; one memo is one successful call here (docs/requests/backend-reports-brand-calls.md).
                // A BRAND caller gets no share: memos / all memos would let it back out the total memo count of every brand.
                ChannelVolume(rs.getLong(1).toString(), rs.getString(2), rs.getLong(3), rs.getLong(4), memos, memos, if (brandOnly) null else pct(memos.toLong(), totalMemos))
            }.list()
        }

        val children: List<DashboardChild> = if (brandOnly) {
            // Brand-scoped value per child (the BRAND caller's own brands, the placement as it was on each day); every other figure is hidden.
            val byRoute = lv.childCol == null || lv.childName == null
            h.q(
                """
                SELECT ${if (byRoute) "g.route_id cid, g.route_name cname, g.route_code ccode" else "g.${lv.childCol} cid, g.${lv.childName} cname, NULL ccode"}, sum(b.gross_mtk) gross
                  FROM dw.agg_daily_route_brand b ${AsWasSql.geo("g", "b.route_id", "b.business_date", lo = ":f", hi = ":t", plain = asWas.geo)}
                 WHERE b.business_date BETWEEN :f AND :t AND ${ns.clause("g.zone_id", "b.route_id")}${ns.inNode("g")} AND b.brand_id = ANY(:scopeBrands)
                 GROUP BY 1, 2, 3 ORDER BY 1
                """,
            ) { it.map { rs, _ -> DashboardChild(NodeRefDto(lv.childType!!, rs.getLong("cid"), rs.getString("ccode"), rs.getString("cname")), brandKpis(rs.getLong("gross"))) }.list() }
        } else if (lv.childCol != null && lv.childName != null) {
            h.q(
                """
                SELECT zg.${lv.childCol} cid, zg.${lv.childName} cname, $sums
                  FROM dw.agg_daily_zone a ${AsWasSql.zone("zg", "a.zone_id", "a.business_date", lo = ":f", hi = ":t", plain = asWas.geo)}
                 WHERE a.business_date BETWEEN :f AND :t AND ${ns.clause("a.zone_id", "a.route_id")}${ns.inNode("zg")} GROUP BY 1, 2 ORDER BY 1
                """,
            ) { it.map { rs, _ -> DashboardChild(NodeRefDto(lv.childType!!, rs.getLong("cid"), null, rs.getString("cname")), kpisOf(rs)) }.list() }
        } else {
            // A zone's children are its routes: a route-day counts as one target route when planned and not excused.
            val login = DashboardSummaries.successfulLoginSql(h)
            h.q(
                """
                SELECT g.route_id cid, g.route_name cname, g.route_code ccode,
                  count(*) FILTER (WHERE a.planned AND NOT a.exception_approved) tr,
                  count(*) FILTER (WHERE a.planned AND NOT a.exception_approved AND $login) lr,
                  count(*) FILTER (WHERE a.planned AND NOT a.exception_approved AND a.day_state IN ('sales_submitted','final_submitted') AND $login) sr,
                  coalesce(sum(a.target_outlets) FILTER (WHERE a.planned AND NOT a.exception_approved),0) tout, coalesce(sum(a.visited_outlets),0) vout,
                  coalesce(sum(a.successful_calls),0) sc, coalesce(sum(a.visits),0) vis, coalesce(sum(a.geo_valid_visits),0) gv,
                  coalesce(sum(a.force_sale_visits),0) fs, coalesce(sum(a.mock_visits),0) mock, coalesce(sum(a.suspicious_visits),0) susp,
                  coalesce(sum(a.active_memo_count),0) memos, coalesce(sum(a.gross_mtk),0) gross, coalesce(sum(a.net_mtk),0) net, 0 zt, 0 zf
                  FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                 WHERE a.business_date BETWEEN :f AND :t AND ${ns.clause("a.zone_id", "a.route_id")} GROUP BY 1, 2, 3 ORDER BY 1
                """,
            ) { it.map { rs, _ -> DashboardChild(NodeRefDto("route", rs.getLong("cid"), rs.getString("ccode"), rs.getString("cname")), kpisOf(rs, zones = false)) }.list() }
        }
        val shown = if (brandOnly) brandKpis(byBrand.sumOf { it.net_mtk }) else kpis
        return DashboardSummary(asOf.wire(), from.toString(), to.toString(), nodeRef, shown, byCategory, byChannel, byBrand, children, bySegment)
    }

    /**
     * What a BRAND caller sees of the KPIs: the sale value of its own brands (the by_brand basis: sale lines, before memo-level settlements) and
     * nothing else; routes, logins, visits, geo and memo counts have no brand dimension and are hidden (docs/34 s1; zero, percentages null).
     */
    private fun brandKpis(value: Long) = DashboardKpis(
        target_routes = 0, logged_in_routes = 0, login_pct = null, sales_submitted_routes = 0, submit_pct_of_logged_in = null, day_completion_pct = null,
        target_outlets = 0, visited_outlets = 0, successful_calls = 0, strike_rate_pct = null, active_memo_count = 0, gross_mtk = value, net_mtk = value,
        geo_valid_pct = null, force_sale_pct = null, mock_visits = 0, suspicious_visits = 0,
    )

    private fun kpisOf(rs: java.sql.ResultSet, zones: Boolean = true): DashboardKpis {
        val tr = rs.getLong("tr"); val lr = rs.getLong("lr"); val sr = rs.getLong("sr"); val vis = rs.getLong("vis")
        val zt = rs.getInt("zt"); val zf = rs.getInt("zf")
        return DashboardKpis(
            target_routes = tr.toInt(), logged_in_routes = lr.toInt(), login_pct = pct(lr, tr), sales_submitted_routes = sr.toInt(),
            submit_pct_of_logged_in = pct(sr, lr), day_completion_pct = pct(sr, tr), target_outlets = rs.getInt("tout"), visited_outlets = rs.getInt("vout"),
            successful_calls = rs.getInt("sc"), strike_rate_pct = pct(rs.getLong("sc"), rs.getLong("tout")), active_memo_count = rs.getInt("memos"),
            gross_mtk = rs.getLong("gross"), net_mtk = rs.getLong("net"), geo_valid_pct = pct(rs.getLong("gv"), vis), force_sale_pct = pct(rs.getLong("fs"), vis),
            mock_visits = rs.getInt("mock"), suspicious_visits = rs.getInt("susp"),
            zones_with_target_routes = if (zones) zt else null, zones_final_submitted = if (zones) zf else null,
            final_submit_pct = if (zones) pct(zf.toLong(), zt.toLong()) else null,
        )
    }
}

class DashboardDeps(
    val service: DashboardService, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM,
    /** A BRAND caller's brand ids, read server-side (the same resolver as the national dashboard); null for every other level. */
    val brands: BrandScopeResolver = BrandScopeResolver { _, _ -> null },
)

/** Field phones read their own home strip (`/app/home`); the dashboards are for TSO and above. */
internal val DASHBOARD_ROLES = Role.entries.toSet() - Role.SR - Role.AMO - Role.SUPPORT   // SUPPORT sees sync health only (docs/24 s8.5)

/**
 * The caller's scope nodes as part of the response-cache key (D-REPORTS-NODEASWAS-09): whether an empty node is the caller's depends on them
 * ([NodeMembers.allowed]), and the zone set in `scope.hash` does not carry them, so two callers with the same zones must not share an answer.
 */
internal fun topNodesKey(reach: Reach): String = reach.topNodes.joinToString(",") { "${it.type}:${it.id}" }

/** A BRAND user without brands must see nothing, never everything (fail closed). */
internal fun requireBrandScope(role: Role, brandIds: Set<Long>?) {
    if (role == Role.BRAND && brandIds == null) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "no brand scope")
}

/** Visits, geo and mock rates have no brand dimension: a BRAND caller reads none of them (the portal gate refuses the route too; defence in depth). */
internal fun refuseBrandCaller(role: Role) {
    if (role == Role.BRAND) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "geo validation is not available to this role")
}

fun Route.dashboardRoutes(d: DashboardDeps) {
    authenticated(d.guard) { dashboardHandlers(d) }
}

/** The handlers without the guard (a test mounts them with a principal of its own, past the portal gate, to prove the handler's own role checks). */
internal fun Route.dashboardHandlers(d: DashboardDeps) {
    get("/dashboards/summary") {
        val p = call.principal
        if (p.role !in DASHBOARD_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "dashboards are not available to this role")
        val q = call.request.queryParameters
        val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
        fun date(n: String, default: LocalDate) = q[n]?.let { s ->
            runCatching { LocalDate.parse(s) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $n", errors = listOf(FieldError("query.$n", "invalid_value")))
        } ?: default
        val from = date("from", today)
        val to = date("to", from)
        val nodeId = q["node_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad node_id", errors = listOf(FieldError("query.node_id", "invalid_value"))) }
        val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)
        val brandIds = d.brands.brandIds(p.userId, p.role)
        requireBrandScope(p.role, brandIds)
        call.respond(d.service.summary(reach, q["level"], nodeId, from, to, brandIds))
    }
    get("/dashboards/geo-validation") {
        val p = call.principal
        if (p.role !in DASHBOARD_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "dashboards are not available to this role")
        val q = call.request.queryParameters
        val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
        fun date(n: String, default: LocalDate) = q[n]?.let { s ->
            runCatching { LocalDate.parse(s) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $n", errors = listOf(FieldError("query.$n", "invalid_value")))
        } ?: default
        val from = date("from", today)
        val to = date("to", from)
        val nodeId = q["node_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad node_id", errors = listOf(FieldError("query.node_id", "invalid_value"))) }
        refuseBrandCaller(p.role)
        val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)
        call.respond(d.service.geoValidation(reach, q["level"], nodeId, from, to))
    }
}
