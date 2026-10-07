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
data class CategoryVolume(val category_code: String, val base_unit: String, val qty_base: Long, val net_mtk: Long)

@Serializable
data class ChannelVolume(
    val code: String, val name: String? = null, val net_mtk: Long, val qty_base: Long? = null, val memo_count: Int, val successful_calls: Int,
    val memo_ratio_pct: Double? = null,
)

@Serializable
data class DashboardChild(val node: NodeRefDto, val kpis: DashboardKpis)

@Serializable
data class DashboardSummary(
    val as_of: String, val from: String, val to: String, val node: NodeRefDto, val kpis: DashboardKpis,
    val by_category: List<CategoryVolume>, val by_channel: List<ChannelVolume>, val by_brand: List<ChannelVolume>, val children: List<DashboardChild>,
)

/** Percentage with two decimals (half up), null when the denominator is 0. */
internal fun pct(n: Long, d: Long): Double? =
    if (d <= 0) null else BigDecimal(n).multiply(BigDecimal(100)).divide(BigDecimal(d), 2, RoundingMode.HALF_UP).toDouble()

/** What a caller may see: all zones (national) or a set of zones. Hashed into the cache key; the cache never crosses scopes. */
internal class ZoneScope(val all: Boolean, val zones: List<Long>) {
    val hash: String by lazy {
        val m = MessageDigest.getInstance("SHA-256")
        m.update((if (all) "ALL" else zones.sorted().joinToString(",")).toByteArray())
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

/**
 * GET /v1/dashboards/summary (F-API-014). Reads `dw` aggregates only (never the transaction log), from the read replica
 * when configured, scoped by the caller's server-side reach; cached 30 s per scope hash, node and date range.
 */
class DashboardService(
    private val db: Database,
    private val clock: AronClock = AronClock.SYSTEM,
    private val ttl: Duration = Duration.ofSeconds(30),
) {
    private val cache = ConcurrentHashMap<String, Pair<Instant, DashboardSummary>>()

    fun summary(reach: Reach, level: String?, nodeId: Long?, from: LocalDate, to: LocalDate): DashboardSummary {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 92) {
            throw ApiProblem(ProblemCode.ERR_VALIDATION, "to must be on or after from, at most 92 days", errors = listOf(FieldError("query.to", "out_of_range")))
        }
        val scope = ZoneScope(reach.national, reach.zoneIds.toList())
        val node = resolveNode(reach, level, nodeId)
        val key = "${scope.hash}|${node.first}|${node.second}|$from|$to"
        val now = clock.now()
        cache[key]?.let { (at, v) -> if (Duration.between(at, now) < ttl) return v }
        val multi = level == null && nodeId == null && !reach.national && reach.topNodes.size > 1
        val v = db.readJdbi.withHandle<DashboardSummary, Exception> { h -> compute(h, reach, scope, node, from, to, multi) }
        if (cache.size > 2_000) { cache.entries.removeIf { Duration.between(it.value.first, now) >= ttl }; if (cache.size > 2_000) cache.clear() }
        cache[key] = now to v
        return v
    }

    /** (level, id): the requested node, or the caller's top reach node. Outside the reach, or unknown, is 403 (no existence leak). */
    private fun resolveNode(reach: Reach, level: String?, nodeId: Long?): Pair<String, Long> {
        val out = ApiProblem(ProblemCode.ERR_FORBIDDEN, "node is outside your reach")
        if (level == null && nodeId == null) {
            if (reach.national) return "national" to 0L
            val top = reach.topNodes.firstOrNull() ?: throw out
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

    private fun compute(h: Handle, reach: Reach, scope: ZoneScope, node: Pair<String, Long>, from: LocalDate, to: LocalDate, multi: Boolean): DashboardSummary {
        val lv = LEVELS.getValue(node.first)
        // Zones of the node, narrowed to the reach. A caller with several top nodes and no selector reads the whole reach (labelled by the first node).
        val nodeZones: List<Long> = if (lv.col == null || multi) emptyList() else
            h.createQuery("SELECT DISTINCT zone_id FROM dw.dim_geo WHERE ${lv.col} = :id").bind("id", node.second).mapTo(Long::class.java).list()
        if (lv.col != null && !multi) {
            if (nodeZones.isEmpty() || (!reach.national && !reach.zoneIds.containsAll(nodeZones))) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "node is outside your reach")
        }
        val eff = when {
            multi -> ZoneScope(false, reach.zoneIds.toList().ifEmpty { listOf(-1L) })
            lv.col != null -> ZoneScope(false, nodeZones)
            else -> scope
        }
        val nodeRef = if (lv.col == null) NodeRefDto("national", 0, "national", "National") else
            h.createQuery("SELECT DISTINCT ${lv.nameCol} FROM dw.dim_geo WHERE ${lv.col} = :id").bind("id", node.second).mapTo(String::class.java).first()
                .let { NodeRefDto(node.first, node.second, null, it) }

        fun <T> Handle.q(sql: String, f: (org.jdbi.v3.core.statement.Query) -> T): T {
            val st = createQuery(sql).bind("f", from).bind("t", to)
            if (!eff.all) st.bindArray("zones", Long::class.javaObjectType, eff.zones)
            return f(st)
        }
        val z = eff.clause()
        val sums = "coalesce(sum(target_routes),0) tr, coalesce(sum(logged_in_routes),0) lr, coalesce(sum(sales_submitted_routes),0) sr, " +
            "coalesce(sum(target_outlets),0) tout, coalesce(sum(visited_outlets),0) vout, coalesce(sum(successful_calls),0) sc, coalesce(sum(visits),0) vis, " +
            "coalesce(sum(geo_valid_visits),0) gv, coalesce(sum(force_sale_visits),0) fs, coalesce(sum(mock_visits),0) mock, coalesce(sum(suspicious_visits),0) susp, " +
            "coalesce(sum(active_memo_count),0) memos, coalesce(sum(gross_mtk),0) gross, coalesce(sum(net_mtk),0) net, " +
            "count(*) FILTER (WHERE target_routes > 0) zt, count(*) FILTER (WHERE target_routes > 0 AND final_submitted) zf"
        val kpis = h.q("SELECT $sums FROM dw.agg_daily_zone WHERE business_date BETWEEN :f AND :t AND $z") { it.map { rs, _ -> kpisOf(rs) }.one() }
        val asOf = h.q("SELECT max(updated_at) FROM dw.agg_daily_zone WHERE business_date BETWEEN :f AND :t AND $z") {
            it.map { rs, _ -> rs.getObject(1, java.time.OffsetDateTime::class.java)?.toInstant() }.one()
        } ?: clock.now()

        val byCategory = h.q(
            """
            SELECT p.category_code, p.base_unit, sum(a.sold_qty_base + a.free_qty_base) qty, sum(a.gross_mtk) gross
              FROM dw.agg_daily_route_sku a JOIN dw.dim_product p ON p.sku_id = a.sku_id JOIN dw.dim_geo g ON g.route_id = a.route_id
             WHERE a.business_date BETWEEN :f AND :t AND ${eff.clause("g.zone_id")} GROUP BY 1, 2 ORDER BY 1, 2
            """,
        ) { it.map { rs, _ -> CategoryVolume(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) }.list() }

        val byChannel = h.q(
            """
            SELECT o.channel, sum(a.net_mtk) net, sum(a.sold_qty_base) qty, sum(a.active_memo_count) memos, count(*) FILTER (WHERE a.active_memo_count > 0) calls
              FROM dw.agg_daily_outlet a JOIN dw.dim_outlet o ON o.outlet_id = a.outlet_id
             WHERE a.business_date BETWEEN :f AND :t AND ${eff.clause("o.zone_id")} GROUP BY 1 ORDER BY 1
            """,
        ) { it.map { rs, _ -> ChannelVolume(rs.getString(1), null, rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)) }.list() }

        // BSR per brand (docs/24 s12.4): active memos containing the brand / active memos, over the same route-days.
        val totalMemos = kpis.active_memo_count.toLong()
        val byBrand = h.q(
            """
            SELECT b.brand_id, p.brand_name, sum(b.gross_mtk) net, sum(b.sold_qty_base) qty, sum(b.memo_count) memos
              FROM dw.agg_daily_route_brand b JOIN dw.dim_geo g ON g.route_id = b.route_id
              JOIN LATERAL (SELECT brand_name FROM dw.dim_product WHERE brand_id = b.brand_id ORDER BY brand_name LIMIT 1) p ON true
             WHERE b.business_date BETWEEN :f AND :t AND ${eff.clause("g.zone_id")} GROUP BY 1, 2 ORDER BY 1
            """,
        ) {
            it.map { rs, _ ->
                val memos = rs.getInt(5)
                // Successful calls of a brand are not stored per brand; one memo is one successful call here (docs/requests/backend-reports-brand-calls.md).
                ChannelVolume(rs.getLong(1).toString(), rs.getString(2), rs.getLong(3), rs.getLong(4), memos, memos, pct(memos.toLong(), totalMemos))
            }.list()
        }

        val children: List<DashboardChild> = if (lv.childCol != null && lv.childName != null) {
            h.q(
                """
                WITH zg AS (SELECT DISTINCT zone_id, zone_name, territory_id, territory_name, division_id, division_name, wing_id, wing_name FROM dw.dim_geo)
                SELECT zg.${lv.childCol} cid, zg.${lv.childName} cname, $sums
                  FROM dw.agg_daily_zone a JOIN zg ON zg.zone_id = a.zone_id
                 WHERE a.business_date BETWEEN :f AND :t AND ${eff.clause("a.zone_id")} GROUP BY 1, 2 ORDER BY 1
                """,
            ) { it.map { rs, _ -> DashboardChild(NodeRefDto(lv.childType!!, rs.getLong("cid"), null, rs.getString("cname")), kpisOf(rs)) }.list() }
        } else {
            // A zone's children are its routes: a route-day counts as one target route when planned and not excused.
            h.q(
                """
                SELECT g.route_id cid, g.route_name cname, g.route_code ccode,
                  count(*) FILTER (WHERE a.planned AND NOT a.exception_approved) tr,
                  count(*) FILTER (WHERE a.planned AND NOT a.exception_approved AND a.day_state <> 'not_started') lr,
                  count(*) FILTER (WHERE a.planned AND NOT a.exception_approved AND a.day_state IN ('sales_submitted','final_submitted')) sr,
                  coalesce(sum(a.target_outlets) FILTER (WHERE a.planned AND NOT a.exception_approved),0) tout, coalesce(sum(a.visited_outlets),0) vout,
                  coalesce(sum(a.successful_calls),0) sc, coalesce(sum(a.visits),0) vis, coalesce(sum(a.geo_valid_visits),0) gv,
                  coalesce(sum(a.force_sale_visits),0) fs, coalesce(sum(a.mock_visits),0) mock, coalesce(sum(a.suspicious_visits),0) susp,
                  coalesce(sum(a.active_memo_count),0) memos, coalesce(sum(a.gross_mtk),0) gross, coalesce(sum(a.net_mtk),0) net, 0 zt, 0 zf
                  FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                 WHERE a.business_date BETWEEN :f AND :t AND ${eff.clause("a.zone_id")} GROUP BY 1, 2, 3 ORDER BY 1
                """,
            ) { it.map { rs, _ -> DashboardChild(NodeRefDto("route", rs.getLong("cid"), rs.getString("ccode"), rs.getString("cname")), kpisOf(rs, zones = false)) }.list() }
        }
        return DashboardSummary(asOf.wire(), from.toString(), to.toString(), nodeRef, kpis, byCategory, byChannel, byBrand, children)
    }

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

class DashboardDeps(val service: DashboardService, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/** Field phones read their own home strip (`/app/home`); the dashboards are for TSO and above. */
internal val DASHBOARD_ROLES = Role.entries.toSet() - Role.SR - Role.AMO - Role.SUPPORT   // SUPPORT sees sync health only (docs/24 s8.5)

fun Route.dashboardRoutes(d: DashboardDeps) {
    authenticated(d.guard) {
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
            call.respond(d.service.summary(reach, q["level"], nodeId, from, to))
        }
    }
}
