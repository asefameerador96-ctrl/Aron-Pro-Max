package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.rules.SkuProgress
import com.aktcl.aron.rules.StarProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * Match Box Lifting Star snapshot of the SR (lane T-KPI1, N-8; screen 62; design docs/status/sr-targets-server.md s4, D-TKPI-07). One object per route whose
 * route has a target row for an ACTIVE superstar programme in the bundle month; null otherwise. Figures come from [StarProgress] (exact rationals, rounded
 * once); STD and LMS are sold quantities of the programme's SKUs (`rules.sku_category`, default match, or `rules.sku_ids`) from the route's accepted memos ([SrMemoFigures], `app.memo` / `app.memo_line`)
 * through the last closed day (never today; same rule as [SrTargets]) and over last month, divided by `rules.unit_divisor` (default 1). The day counts come
 * from [SkuProgress.dayCounts] (one function for the convention) and the route's calendar. Slabs and status texts are programme data in flat scalar keys
 * (the programme contract allows scalar rule values only): `slab1_label`, `slab1_min_bp`, `slab1_amount_mtk` .. `slab6_*`; `status1_min_bp`, `status1_en`,
 * `status1_bn` .. `status4_*`. Achieved slab = by the actual achievement (STD / target); projected amount = by the projection. The amounts are SHOWN, never
 * applied to a memo. Nothing here is written by a device; the phone may recompute with the same rules.
 *
 * The SKU rule is CLEANED before it is used or carried: `sku_ids` is read as a comma list, kept only when an id is >= 1 and exists in the catalogue ([SrMemoFigures.Dim]), made
 * distinct and sorted ASCENDING BY ID (the one documented order of `sku_ids` on the wire). The 500 cap and `sku_ids_truncated` count that CLEANED list; the STD counts every cleaned id.
 * When the rule lists ids but none survives the cleaning, the rule falls back to the category (as when the rule text cannot be read) and the fact is logged. `sku_category` is
 * carried only when it is one of [CATEGORIES] (the contract enum); any other value is omitted from the row and logged, and the STD then counts no SKU.
 */
object SrStar {
    /** The most SKU ids carried in `sku_ids` of one Star row (the STD itself counts every listed SKU). */
    const val MAX_SKU_IDS = 500

    /** The SKU categories of the contract enum (`RouteStar.sku_category`). */
    val CATEGORIES = setOf("cigarette", "bidi", "match", "lighter")

    private val log = LoggerFactory.getLogger("aron.sr-star")

    /** The cleaned explicit SKU list of a rule text: distinct, `>= 1`, present in [known], ascending by id; empty when the rule has no usable id. */
    fun cleanSkuIds(rule: String?, known: (Long) -> Boolean): List<Long> =
        rule?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty().filter { it >= 1 && known(it) }.distinct().sorted()

    class RouteInfo(val routeName: String, val srName: String?, val srUserId: Long?, val calendar: RouteCalendarDto)

    fun load(
        h: Handle, routes: Map<Long, RouteInfo>, date: LocalDate, through: LocalDate,
        figures: Map<Long, SrMemoFigures.Figures> = SrMemoFigures.forRoutes(h, routes.keys, YearMonth.from(date).atDay(1), through),
        dim: SrMemoFigures.Dim = SrMemoFigures.dim(h), dropped: MutableSet<Long>? = null,
    ): Map<Long, JsonObject> {
        if (routes.isEmpty()) return emptyMap()
        val month = YearMonth.from(date); val first = month.atDay(1)
        val last = month.minusMonths(1)
        val progs = h.createQuery(
            """
            SELECT g.id, g.code, g.name_en, g.name_bn, g.rules::text, t.route_id, t.target_qty FROM app.route_programme_target t JOIN app.programme g ON g.id = t.programme_id
             WHERE t.route_id = ANY(:r) AND t.month = :m AND g.kind = 'superstar' AND g.status = 'active' AND g.active_from <= :d AND g.active_to >= :d ORDER BY g.id
            """,
        ).bindArray("r", Long::class.javaObjectType, routes.keys.toList()).bind("m", first).bind("d", date)
            .map { rs, _ -> Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getBigDecimal(7)) }.list()
            .groupBy { it.routeId }.also { g -> g.forEach { (r, l) -> if (l.size > 1) dropped?.add(r) } } // routes with more than one active superstar programme: the extra ones are dropped, never silently
            .mapValues { it.value.first() } // one programme per route: the lowest id
        if (progs.isEmpty()) return emptyMap()
        val users = routes.values.mapNotNull { it.srUserId }.distinct().let { ids ->
            if (ids.isEmpty()) emptyMap() else h.createQuery("SELECT id, username FROM app.app_user WHERE id = ANY(:i)").bindArray("i", Long::class.javaObjectType, ids)
                .map { rs, _ -> rs.getLong(1) to rs.getString(2) }.list().toMap()
        }
        // last month's sold quantity of every Star route in ONE read (was one query per route)
        // cut at the last CLOSED day like every other window: when last month is not closed yet (a bundle built ahead on its last day), LMS holds its closed days only and `lmwd` counts the same days
        val lastMonth = SrMemoFigures.forRoutes(h, progs.keys, last.atDay(1), minOf(last.atEndOfMonth(), through))
        val warned = HashSet<Pair<Long, String>>()
        return progs.mapValues { (routeId, p) ->
            val info = routes.getValue(routeId)
            val rules = parseRules(p.rules)
            val divisor = rules.num("unit_divisor")?.takeIf { it >= 1 } ?: 1L
            val category = rules.str("sku_category") ?: "match"
            val categoryOnWire = category.takeIf { it in CATEGORIES }
            val idRule = rules.str("sku_ids")
            val ids = cleanSkuIds(idRule) { it in dim.skus }
            // each misconfiguration is logged ONCE per programme per bundle build (this call), not once per route that carries it
            fun warnOnce(kind: String, msg: String, vararg args: Any?) { if (warned.add(p.id to kind)) log.warn(msg, *args) }
            if (categoryOnWire == null) {
                if (ids.isNotEmpty()) warnOnce("category", "programme {}: sku_category '{}' is not one of {}; omitted from the row, the STD still counts the {} listed sku_ids", p.id, category, CATEGORIES, ids.size)
                else warnOnce("category", "programme {}: sku_category '{}' is not one of {}; omitted from the row, the STD counts no SKU", p.id, category, CATEGORIES)
            }
            if (idRule != null && ids.isEmpty()) warnOnce("ids", "programme {}: sku_ids lists no usable SKU; the category rule '{}' applies", p.id, category)
            val idSet = ids.toHashSet()
            val accept: (Long) -> Boolean = if (ids.isNotEmpty()) { sku -> sku in idSet } else { sku -> dim.skus[sku]?.category == category }
            // STD: this month's accepted memos through the last closed day ([SrMemoFigures], same snapshot as every other figure); LMS: last month, same filters.
            val std = figures[routeId]?.sold(accept) ?: 0L
            val lms = lastMonth[routeId]?.sold(accept) ?: 0L
            val c = info.calendar
            val days = SkuProgress.dayCounts(SkuProgress.Calendar(c.elapsed_working_days_before_today, c.remaining_working_days_after_today, c.today_is_working))
            val res = StarProgress.compute(
                StarProgress.Input(
                    targetMilli = p.target.setScale(3).unscaledValue().toLong(), stdBase = std, lmsBase = lms,
                    unitDivisor = divisor, ewd = days.elapsed, wd = c.working_days, lmwd = c.last_month_working_days,
                ),
            )
            val rungs = (1..6).mapNotNull { n -> rules.num("slab${n}_min_bp")?.let { StarProgress.Rung(rules.str("slab${n}_label") ?: "S$n", it, rules.num("slab${n}_amount_mtk") ?: 0L) } }
            val statuses = (1..4).mapNotNull { n -> rules.num("status${n}_min_bp")?.let { StarProgress.StatusRule(it, rules.str("status${n}_en") ?: "", rules.str("status${n}_bn") ?: "") } }
            val st = StarProgress.status(statuses, res.achievementBp)
            val achieved = StarProgress.rungFor(rungs, res.actualBp); val projected = StarProgress.rungFor(rungs, res.achievementBp)
            fun d3(v: Long?) = v?.let { JsonPrimitive(BigDecimal.valueOf(it, 3).toPlainString()) } ?: JsonNull
            fun l(v: Long?) = v?.let { JsonPrimitive(it) } ?: JsonNull
            buildJsonObject {
                put("programme_id", p.id); put("programme_code", p.code); put("name_en", p.nameEn); put("name_bn", p.nameBn?.let { JsonPrimitive(it) } ?: JsonNull)
                put("month", month.toString()); put("through_date", through.toString()); put("as_of", (figures[routeId]?.asOf ?: Instant.EPOCH).wire())
                put("sr_name", info.srName?.let { JsonPrimitive(it) } ?: JsonNull); put("sr_code", info.srUserId?.let { users[it] }?.let { JsonPrimitive(it) } ?: JsonNull)
                put("route_name", info.routeName)
                put("lmwd", c.last_month_working_days); put("wd", c.working_days); put("ewd", days.elapsed)
                // WHICH SKUs the STD counts, so the phone can show "today on this phone" as a separate, labelled figure (never added into `std`): the explicit SKU list when the rules give one, else the category
                put("unit_divisor", divisor)
                if (ids.isNotEmpty()) { put("sku_ids", buildJsonArray { ids.take(MAX_SKU_IDS).forEach { add(JsonPrimitive(it)) } }); if (ids.size > MAX_SKU_IDS) put("sku_ids_truncated", true) } else if (categoryOnWire != null) put("sku_category", categoryOnWire)
                put("target", d3(res.targetMilli)); put("std", d3(res.stdMilli)); put("projection", d3(res.projectionMilli)); put("achievement_bp", l(res.achievementBp))
                put("lms", d3(res.lmsMilli)); put("lmads", d3(res.lmadsMilli)); put("pads", d3(res.padsMilli)); put("growth_bp", l(res.growthBp))
                put("status_en", st.en); put("status_bn", st.bn)
                put("achieved_slab", achieved?.label?.let { JsonPrimitive(it) } ?: JsonNull); put("slab_amount_mtk", l(achieved?.amountMtk))
                put("projected_slab", projected?.label?.let { JsonPrimitive(it) } ?: JsonNull); put("projected_amount_mtk", l(projected?.amountMtk))
            }
        }
    }

    private class Row(val id: Long, val code: String, val nameEn: String, val nameBn: String?, val rules: String, val routeId: Long, val target: BigDecimal)

    private fun parseRules(text: String): JsonObject = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toBigDecimalOrNull()?.takeIf { d -> d.stripTrailingZeros().scale() <= 0 }?.toLong() }?.takeIf { it >= 0 }
}
