package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.WorkingCalendarRepo
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.NodeRef
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.contract.TargetAchievement
import com.aktcl.aron.contract.TargetAchievementNode
import com.aktcl.aron.contract.TargetAchievementRow
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.NodePath
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.TillDateBasis
import com.aktcl.aron.rules.TillDateDays
import com.aktcl.aron.rules.Targets
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jdbi.v3.core.Handle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Target Status (contract 1.10.0 `getTargetAchievement`, `GET /v1/dashboards/targets`; D-TSO-MAN-05, 08, 39, 40; lead
 * rulings R-F-01, R-F-15, R-F-18, R-F-24). For a node of the caller's reach and a month:
 * - T (target_qty_base): the `app.route_monthly_target` rows of the month on the node's routes; A (achieved_qty_base):
 *   `dw.agg_daily_route_sku.sold_qty_base` (sale lines only) of business dates 1st..`day` on the same routes. Routes are
 *   placed by their current zone (`dw.dim_geo`), so T and A of a route always land on the same node.
 * - The till-date fraction e / n follows `cfg.kpi.tilldate_basis.tso` (AMO callers: `.amo_team`), calendar days by
 *   default; on the working-days basis n is W at the node's own path ([WorkingCalendarRepo], [com.aktcl.aron.rules.WorkingDays]),
 *   so each child zone carries its own e and n (R-F-24).
 * - Item = variant (a SKU row is its own item): item T' = Targets.tillDate(T_item, e, n), rounded once per item at the
 *   node; every row above an item (category, brand) is the SUM of its item rows, never its own total rounded (R-F-01:
 *   the Cigarette card of the screenshot is 33064 at 25/31 and 33114 at 21/26).
 * - n = 0 at a node: that node's till-date members are null and `tilldate_target_qty` is "0.000" (R-F-15, R-F-24).
 * Every percentage is Percent.bp of the integers, rounded once; the Decimal3 members and `achievement_pct` keep their
 * meaning for the AMO app (T, A, T', max(T - A, 0) in base units; A / T as a percent with two decimals, from the same bp).
 */
class TargetAchievementService(private val db: Database, private val config: ServerConfig?, private val clock: AronClock = AronClock.SYSTEM) {

    private class Product(
        val skuId: Long, val skuName: String, val variantId: Long, val variantName: String, val brandId: Long, val brandName: String,
        val categoryId: Long, val categoryName: String, val categoryCode: String, val baseUnit: String, val sortKey: List<Comparable<*>>,
    )

    private class Category(val id: Long, val name: String, val code: String, val unit: String)

    /** T and A of one node by SKU. */
    private class Figures(val target: Map<Long, Long>, val achieved: Map<Long, Long>)

    fun achievement(
        role: Role, reach: Reach, monthParam: String?, level: String?, nodeId: Long?, productLevelParam: String?, childrenParam: String?,
    ): TargetAchievement {
        val month = monthParam?.let { m -> if (MONTH.matches(m)) YearMonth.parse(m) else throw bad("query.month", "invalid_value") } ?: throw bad("query.month", "required")
        val productLevel = productLevelParam ?: "category"
        if (productLevel !in PRODUCT_LEVELS) throw bad("query.product_level", "invalid_value")
        if (childrenParam != null && childrenParam != "zone") throw bad("query.children", "invalid_value")
        val today = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
        val current = YearMonth.from(today)
        // The date the figures run to: today in the current month, the last day of a past month, the first day of a future one.
        val day = when {
            month == current -> today
            month < current -> month.atEndOfMonth()
            else -> month.atDay(1)
        }
        val basis = TillDateBasis.fromWire(runCatching { config?.string(if (role == Role.AMO) BASIS_AMO else BASIS_TSO) }.getOrNull())
        return db.readJdbi.withHandle<TargetAchievement, Exception> { h ->
            val node = resolveScopedNode(h, reach, level, nodeId, ProblemCode.ERR_OUT_OF_SCOPE)
            val withChildren = childrenParam == "zone"
            // R-F-18: children only at a territory or below (a house groups zones of one territory; a route-only caller's node is its routes).
            if (withChildren && node.ref.type !in CHILD_PARENTS) throw bad("query.children", "invalid_value")
            val byZone = figuresByZone(h, node, month, day)
            val products = products(h, byZone.values.flatMap { it.target.keys + it.achieved.keys }.toSet())
            val categories = if (productLevel == "category") categories(h) else emptyList()
            val cals = if (basis == TillDateBasis.WORKING_DAYS) WorkingCalendarRepo.load(h, month, clock.now(), config) else null
            val zones = byZone.keys + coveredZones(h, node)
            val zonePaths = if (cals != null) WorkingCalendarRepo.zonePaths(h, zones) else emptyMap()
            fun days(path: () -> NodePath): TillDateDays =
                Targets.days(basis, kotlinx.datetime.YearMonth(month.year, month.monthValue), day.toKotlinLocalDate(), cals?.at(path()))
            val parentDays = days { parentPath(h, reach, level, nodeId, node, zonePaths) }
            val all = Figures(sum(byZone.values.map { it.target }), sum(byZone.values.map { it.achieved }))
            val children = if (!withChildren) null else childZones(h, node, byZone.keys).take(MAX_CHILDREN).map { z ->
                val f = byZone[z.id] ?: Figures(emptyMap(), emptyMap())
                val d = days { zonePaths[z.id] ?: NodePath.GLOBAL }
                TargetAchievementNode(z, d.elapsed, d.period, rows(productLevel, f, d, products, categories))
            }
            TargetAchievement(
                month = month.toString(), asOf = clock.now().wire(), node = NodeRef(node.ref.type, node.ref.id, node.ref.code, node.ref.name),
                tilldateBasis = basis.wire, rows = rows(productLevel, all, parentDays, products, categories), day = day.toString(),
                elapsedDays = parentDays.elapsed, periodDays = parentDays.period, lastUpdatedAt = lastUpdated(h), children = children,
            )
        }
    }

    /** T and A per zone and SKU on the node's routes (route-keyed for a route-only caller, D-BC-DASH-ROUTE-KEYED-01). */
    private fun figuresByZone(h: Handle, node: ScopedNode, month: YearMonth, day: LocalDate): Map<Long, Figures> {
        val t = node.bind(h.createQuery(
            """
            SELECT g.zone_id, t.sku_id, sum(t.qty_base) FROM app.route_monthly_target t JOIN dw.dim_geo g ON g.route_id = t.route_id
             WHERE t.month = :m AND ${node.clause("g.zone_id", "t.route_id")} GROUP BY 1, 2
            """,
        ).bind("m", month.atDay(1))).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.list()
        val a = node.bind(h.createQuery(
            """
            SELECT g.zone_id, a.sku_id, sum(a.sold_qty_base) FROM dw.agg_daily_route_sku a JOIN dw.dim_geo g ON g.route_id = a.route_id
             WHERE a.business_date BETWEEN :f AND :d AND ${node.clause("g.zone_id", "a.route_id")} GROUP BY 1, 2
            """,
        ).bind("f", month.atDay(1)).bind("d", day)).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getLong(3)) }.list()
        val zones = (t.map { it.first } + a.map { it.first }).toSet()
        return zones.associateWith { z ->
            // The contract's A is >= 0; a negative month total (only a data error could make one) shows as nothing achieved.
            Figures(t.filter { it.first == z }.associate { it.second to it.third }, a.filter { it.first == z }.associate { it.second to it.third.coerceAtLeast(0L) })
        }
    }

    private fun sum(maps: List<Map<Long, Long>>): Map<Long, Long> = buildMap { maps.forEach { m -> m.forEach { (k, v) -> put(k, Math.addExact(get(k) ?: 0L, v)) } } }

    /** The zones the node covers (its own zones, or the zones of a route-only caller's routes). */
    private fun coveredZones(h: Handle, node: ScopedNode): Set<Long> = when {
        node.routes != null -> if (node.routes.isEmpty()) emptySet() else h.createQuery("SELECT DISTINCT zone_id FROM dw.dim_geo WHERE route_id = ANY(:r)")
            .bindArray("r", Long::class.javaObjectType, node.routes).mapTo(Long::class.java).set()
        else -> node.zones.orEmpty().filter { it > 0 }.toSet()
    }

    /**
     * The path of the node's working days. A wing, division, territory or zone node chosen alone counts at its own path, so
     * a zone's declaration does not count for its territory (D-TSO-MAN-08, s4.2 "zone 24, territory 25"). A house, a
     * route-only caller's routes, or a caller's several top nodes read together count at the lowest common ancestor of the
     * zones they cover.
     */
    private fun parentPath(h: Handle, reach: Reach, level: String?, nodeId: Long?, node: ScopedNode, zonePaths: Map<Long, NodePath>): NodePath {
        if (node.zones == null && node.routes == null) return NodePath.GLOBAL
        val several = level == null && nodeId == null && !reach.national && reach.topNodes.size > 1
        if (node.ref.type in OWN_PATH && !several) return WorkingCalendarRepo.pathOf(h, node.ref.type, node.ref.id) ?: NodePath.GLOBAL
        return WorkingCalendarRepo.commonPath(coveredZones(h, node).mapNotNull { zonePaths[it] })
    }

    /** The zone cards: every zone the node covers in the caller's reach (a zone node answers itself), by name. */
    private fun childZones(h: Handle, node: ScopedNode, withFigures: Set<Long>): List<NodeRef> {
        val ids = coveredZones(h, node).ifEmpty { withFigures }
        if (ids.isEmpty()) return emptyList()
        return h.createQuery("SELECT id, code, name FROM app.zone WHERE id = ANY(:z) ORDER BY name, id").bindArray("z", Long::class.javaObjectType, ids.toList())
            .map { rs, _ -> NodeRef("zone", rs.getLong(1), rs.getString(2), rs.getString(3)) }.list()
    }

    private fun products(h: Handle, skuIds: Set<Long>): Map<Long, Product> {
        if (skuIds.isEmpty()) return emptyMap()
        return h.createQuery(
            """
            SELECT p.sku_id, coalesce(s.name, p.short_name), p.variant_id, p.variant_name, p.brand_id, p.brand_name, p.category_id, p.category_name,
                   p.category_code, p.base_unit, coalesce(sg.sort, 0), coalesce(b.sort, 0), coalesce(v.sort, 0), coalesce(s.sort, 0), p.sku_code
              FROM dw.dim_product p LEFT JOIN app.sku s ON s.id = p.sku_id LEFT JOIN app.product_node v ON v.id = p.variant_id
              LEFT JOIN app.product_node b ON b.id = p.brand_id LEFT JOIN app.product_node sg ON sg.id = p.segment_id
             WHERE p.sku_id = ANY(:s)
            """,
        ).bindArray("s", Long::class.javaObjectType, skuIds.toList()).map { rs, _ ->
            val cat = rs.getString(9)
            Product(
                rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getLong(7), rs.getString(8), cat, rs.getString(10),
                // Catalogue order (D-TSO-MAN-40): category as the brief lists them, then segment, brand, variant and SKU by their sort, name and id.
                listOf(categoryRank(cat), rs.getInt(11), rs.getInt(12), rs.getString(6), rs.getLong(5), rs.getInt(13), rs.getString(4), rs.getLong(3), rs.getInt(14), rs.getString(15)),
            )
        }.list().associateBy { it.skuId }
    }

    /** Every category of the catalogue: the Target Status card shows its four bars even where a category has no target. */
    private fun categories(h: Handle): List<Category> = h.createQuery(
        "SELECT category_id, min(category_name), category_code, min(base_unit) FROM dw.dim_product GROUP BY category_id, category_code",
    ).map { rs, _ -> Category(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.list()
        .sortedWith(compareBy<Category>({ categoryRank(it.code) }, { it.name }, { it.id }))

    private fun categoryRank(code: String): Int = CATEGORY_ORDER.indexOf(code).let { if (it < 0) 99 else it }

    private class Acc(val id: Long, val name: String, val unit: String, val categoryCode: String, val order: List<Comparable<*>>) {
        var t = 0L; var a = 0L; var td = 0L
    }

    /** The rows of one node at [level]: item till-date rounded once per item, every row the sum of its items (R-F-01). */
    private fun rows(level: String, f: Figures, days: TillDateDays, products: Map<Long, Product>, categories: List<Category>): List<TargetAchievementRow> {
        val noPeriod = days.period == 0
        // Items: variants (a SKU row is its own item); T and A summed over the node's SKUs of the item, then T' rounded once.
        val items = (f.target.keys + f.achieved.keys).mapNotNull { products[it] }.groupBy { if (level == "sku") it.skuId else it.variantId }
        val acc = LinkedHashMap<Long, Acc>()
        if (level == "category") categories.forEach { c -> acc[c.id] = Acc(c.id, c.name, c.unit, c.code, listOf(categoryRank(c.code), c.name, c.id)) }
        for ((_, skus) in items) {
            val first = skus.first()   // every SKU of an item shares its variant, brand and category
            val t = skus.sumOf { f.target[it.skuId] ?: 0L }
            val a = skus.sumOf { f.achieved[it.skuId] ?: 0L }
            val td = if (noPeriod) 0L else Targets.tillDate(t, days) ?: 0L
            val (id, name) = when (level) {
                "category" -> first.categoryId to first.categoryName
                "brand" -> first.brandId to first.brandName
                "variant" -> first.variantId to first.variantName
                else -> first.skuId to first.skuName
            }
            val row = acc.getOrPut(id) { Acc(id, name, first.baseUnit, first.categoryCode, first.sortKey) }
            row.t = Math.addExact(row.t, t); row.a = Math.addExact(row.a, a); row.td = Math.addExact(row.td, td)
        }
        val rows = acc.values.filter { level == "category" || it.t > 0 || it.a > 0 }
        val ordered = if (level == "category") rows else rows.sortedWith { x, y -> compareKeys(x.order, y.order) }
        return ordered.map { r ->
            val td: Long? = if (noPeriod) null else r.td
            val bp = Targets.detailsBp(r.a, r.t)
            TargetAchievementRow(
                productLevel = level, productId = r.id, name = r.name.take(120), unit = r.unit,
                targetQty = d3(r.t), achievedQty = d3(r.a), tilldateTargetQty = d3(td ?: 0L),
                achievementPct = bp?.let { BigDecimal.valueOf(it, 2).toDouble() }, remainingQty = d3(Targets.remaining(r.t, r.a)),
                memoTarget = null, memoAchieved = null, categoryCode = r.categoryCode,
                targetQtyBase = r.t, achievedQtyBase = r.a, tilldateTargetQtyBase = td, remainingQtyBase = Targets.remaining(r.t, r.a),
                remainingTilldateQtyBase = td?.let { Targets.remaining(it, r.a) }, achievementPctBp = bp, tilldateAchievementPctBp = td?.let { Percent.bp(r.a, it) },
            )
        }
    }

    private fun lastUpdated(h: Handle): String? =
        h.createQuery("SELECT last_updated_at FROM dw.summary_freshness WHERE table_name = 'agg_daily_route_sku'")
            .map { rs, _ -> rs.getTimestamp(1)?.toInstant()?.toString() }.findOne().orElse(null)

    private fun bad(field: String, reason: String) = ApiProblem(ProblemCode.ERR_VALIDATION, "bad ${field.removePrefix("query.")}", errors = listOf(FieldError(field, reason)))

    companion object {
        const val BASIS_TSO = "cfg.kpi.tilldate_basis.tso"
        const val BASIS_AMO = "cfg.kpi.tilldate_basis.amo_team"
        const val MAX_CHILDREN = 200
        private val MONTH = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")
        private val PRODUCT_LEVELS = setOf("category", "brand", "variant", "sku")
        /** R-F-18: the node types `children=zone` is allowed at (territory or below). */
        private val CHILD_PARENTS = setOf("territory", "house", "zone", "route")
        private val OWN_PATH = setOf("wing", "division", "territory", "zone")

        /** A base-unit integer as the contract's Decimal3 ("40999.000"). */
        internal fun d3(v: Long): String = "$v.000"

        @Suppress("UNCHECKED_CAST")
        private fun compareKeys(x: List<Comparable<*>>, y: List<Comparable<*>>): Int {
            for (i in 0 until minOf(x.size, y.size)) {
                val c = (x[i] as Comparable<Any>).compareTo(y[i] as Any)
                if (c != 0) return c
            }
            return x.size - y.size
        }
    }
}

class TargetAchievementDeps(val service: TargetAchievementService, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM) {
    /** Mounts the route on [route]: one appended line in Wiring.kt's routes block, with no import edit (hot-file rule, s5). */
    fun install(route: Route) = route.targetAchievementRoutes(this)
}

/**
 * Who reads Target Status (docs/24 s8.5 "Dashboards": R for TSO and above, AMO in its app). SR has its targets in the bundle,
 * SUPPORT reads sync health only; a BRAND user is refused (no brand-filtered target read yet; the guard refuses it too).
 */
internal val TARGET_ROLES: Set<Role> = (DASHBOARD_ROLES + Role.AMO) - Role.BRAND

fun Route.targetAchievementRoutes(d: TargetAchievementDeps) {
    authenticated(d.guard) {
        get("/dashboards/targets") {
            val p = call.principal
            if (p.role !in TARGET_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "target status is not available to this role")
            val q = call.request.queryParameters
            val nodeId = q["node_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad node_id", errors = listOf(FieldError("query.node_id", "invalid_value"))) }
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)
            val answer = d.service.achievement(p.role, reach, q["month"], q["level"], nodeId, q["product_level"], q["children"])
            // `children` is absent unless asked for (contract: an array, never null); every other required-nullable member stays an explicit null.
            val body = ResponseJson.encodeToJsonElement(TargetAchievement.serializer(), answer).jsonObject
            call.respond(if (body["children"] is JsonNull) JsonObject(body - "children") else body)
        }
    }
}
