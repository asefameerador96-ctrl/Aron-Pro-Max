package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.SqlStatement
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Who is asking: the server-side reach and, for a BRAND user, the brand ids (null for every other level; docs/34 s1). */
class NationalCaller(val reach: Reach, val brandIds: Set<Long>? = null)

/** Basis points (10000 = 100 %) by integer arithmetic, half up; null when the denominator is 0. Never a float (docs/31 money gate). */
internal fun pctBp(n: Long, d: Long): Int? = if (d <= 0) null else ((n * 20_000 + d) / (2 * d)).toInt()

internal fun ratioBp(part: BigDecimal, whole: BigDecimal): Long = part.multiply(BigDecimal(10_000)).divide(whole, 0, RoundingMode.HALF_UP).toLong()

/** The day's channel order, as the brief lists it. */
internal val CHANNELS = listOf("GT", "DCC", "Astha", "RCC", "MT", "HoReCa")
internal val CATEGORY_ORDER = listOf("cigarette", "bidi", "lighter", "match")

/**
 * The filter object as SQL over `dw.dim_geo g` and `dw.dim_product p`. Scope comes from the caller's reach and is ANDed with the
 * request: ids outside the scope select nothing, and nothing the client sends can widen it (CLAUDE.md constraint 4).
 */
internal class NationalSql(private val caller: NationalCaller, private val f: NationalDashboardFilter) {
    private val r = caller.reach

    /** `AND ...` over alias g. */
    val geo: String = buildString {
        if (!r.national) append(" AND g.zone_id = ANY(:scopeZones)")
        if (f.wing_ids.isNotEmpty()) append(" AND g.wing_id = ANY(:wings)")
        if (f.division_ids.isNotEmpty()) append(" AND g.division_id = ANY(:divisions)")
        if (f.territory_ids.isNotEmpty()) append(" AND g.territory_id = ANY(:territories)")
        if (f.house_ids.isNotEmpty()) append(" AND g.house_id = ANY(:houses)")
        if (f.zone_ids.isNotEmpty()) append(" AND g.zone_id = ANY(:zones)")
    }

    /** `AND ...` over alias p. */
    val product: String = buildString {
        if (f.category_codes.isNotEmpty()) append(" AND p.category_code = ANY(:cats)")
        if (f.segment_codes.isNotEmpty()) append(" AND p.segment_code = ANY(:segs)")
        if (f.brand_codes.isNotEmpty()) append(" AND p.brand_code = ANY(:brands)")
        if (f.variant_codes.isNotEmpty()) append(" AND p.variant_code = ANY(:vars)")
        if (caller.brandIds != null) append(" AND p.brand_id = ANY(:scopeBrands)")
    }

    val hasProductFilter: Boolean = product.isNotEmpty()

    fun <T : SqlStatement<T>> bind(q: T): T {
        if (!r.national) q.bindArray("scopeZones", Long::class.javaObjectType, r.zoneIds.toList().ifEmpty { listOf(-1L) })
        if (f.wing_ids.isNotEmpty()) q.bindArray("wings", Long::class.javaObjectType, f.wing_ids)
        if (f.division_ids.isNotEmpty()) q.bindArray("divisions", Long::class.javaObjectType, f.division_ids)
        if (f.territory_ids.isNotEmpty()) q.bindArray("territories", Long::class.javaObjectType, f.territory_ids)
        if (f.house_ids.isNotEmpty()) q.bindArray("houses", Long::class.javaObjectType, f.house_ids)
        if (f.zone_ids.isNotEmpty()) q.bindArray("zones", Long::class.javaObjectType, f.zone_ids)
        if (f.category_codes.isNotEmpty()) q.bindArray("cats", String::class.java, f.category_codes)
        if (f.segment_codes.isNotEmpty()) q.bindArray("segs", String::class.java, f.segment_codes)
        if (f.brand_codes.isNotEmpty()) q.bindArray("brands", String::class.java, f.brand_codes)
        if (f.variant_codes.isNotEmpty()) q.bindArray("vars", String::class.java, f.variant_codes)
        caller.brandIds?.let { q.bindArray("scopeBrands", Long::class.javaObjectType, it.toList().ifEmpty { listOf(-1L) }) }
        return q
    }
}

/** Working days of a month: not a Friday, not a global holiday or emergency day, plus declared make-up days (brief s2, D-TARGET-MIN-01). */
internal object WorkingCalendar {
    fun workingDays(h: Handle, month: YearMonth): Int {
        val first = month.atDay(1); val last = month.atEndOfMonth()
        val special = h.createQuery(
            "SELECT date, kind FROM app.calendar_holiday WHERE scope_type = 'global' AND revoked_at IS NULL AND date BETWEEN :f AND :l",
        ).bind("f", first).bind("l", last).map { rs, _ -> rs.getObject("date", LocalDate::class.java) to rs.getString("kind") }.list().toMap()
        var n = 0
        var d = first
        while (!d.isAfter(last)) {
            val kind = special[d]
            val off = d.dayOfWeek == DayOfWeek.FRIDAY || kind == "holiday" || kind == "emergency_off"
            if (!off || kind == "makeup_day") n++
            d = d.plusDays(1)
        }
        return n
    }
}

/**
 * The twelve cards of the national dashboard (docs/ui-reference/web/dashboard-brief.md). Every figure reads `dw` summary tables
 * only; geography and products are filtered through `dw.dim_geo` and `dw.dim_product`, scope from the caller's reach.
 * The map pages are in [NationalMaps].
 */
class NationalDashboardService(
    internal val db: Database,
    internal val clock: AronClock = AronClock.SYSTEM,
) {
    internal fun today(): LocalDate = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()

    internal fun dateOf(f: NationalDashboardFilter): LocalDate =
        f.date?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() ?: throw bad("filter.date") } ?: today()

    internal fun dateOf(s: String?): LocalDate =
        s?.let { v -> runCatching { LocalDate.parse(v) }.getOrNull() ?: throw bad("query.date") } ?: today()

    private fun bad(field: String) = ApiProblem(ProblemCode.ERR_VALIDATION, "bad $field", errors = listOf(FieldError(field, "invalid_value")))

    internal fun denyBrandUser(caller: NationalCaller) {
        if (caller.brandIds != null) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "this card has no brand dimension and is hidden from brand users")
    }

    internal fun <T> read(f: (Handle) -> T): T {
        return db.readJdbi.withHandle<T, Exception> { h ->
            h.getConfig(org.jdbi.v3.core.statement.SqlStatements::class.java).isUnusedBindingAllowed = true
            f(h)
        }
    }

    /** Oldest `last_updated_at` among [tables]; null when any has never been written. */
    internal fun lastUpdated(h: Handle, tables: List<String>): String? {
        val rows = h.createQuery("SELECT table_name, last_updated_at FROM dw.summary_freshness WHERE table_name = ANY(:t)")
            .bindArray("t", String::class.java, tables).map { rs, _ -> rs.getString("table_name") to rs.getTimestamp("last_updated_at").toInstant() }.list().toMap()
        if (tables.isEmpty() || tables.any { it !in rows }) return null
        return rows.values.min().toString()
    }

    internal fun ensureReady(h: Handle) {
        if (!DashboardSummaries.ready(h)) throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "dashboard summary tables are not migrated yet")
    }

    internal data class Meta(val date: String, val till: Boolean, val asOf: String, val last: String?)

    internal fun meta(h: Handle, date: LocalDate, tables: List<String>): Meta =
        Meta(date.toString(), date == today(), clock.now().toString(), lastUpdated(h, tables))

    // ---------------------------------------------------------------- sales cards + "i" pop-up

    fun sales(caller: NationalCaller, f: NationalDashboardFilter): NationalSales {
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val sold = sql.bind(h.createQuery(
                """
                SELECT p.category_code, p.base_unit, sum(a.sold_qty_base) AS qty
                  FROM dw.agg_daily_route_sku a JOIN dw.dim_geo g ON g.route_id = a.route_id JOIN dw.dim_product p ON p.sku_id = a.sku_id
                 WHERE a.business_date = :d ${sql.geo} ${sql.product} GROUP BY 1, 2
                """,
            ).bind("d", date)).map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getLong(3)) }.list()
            val cats = sql.bind(h.createQuery("SELECT DISTINCT p.category_code, p.base_unit FROM dw.dim_product p WHERE true ${sql.product}")).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
            val month = YearMonth.from(date)
            val wd = WorkingCalendar.workingDays(h, month)
            val targets = sql.bind(h.createQuery(
                """
                SELECT p.category_code, sum(t.std_target) AS total, count(DISTINCT t.scope_id) AS routes
                  FROM app.target t JOIN app.target_set ts ON ts.id = t.target_set_id AND ts.status = 'active' JOIN dw.dim_geo g ON g.route_id = t.scope_id JOIN dw.dim_product p ON p.sku_id = t.product_id
                 WHERE t.month = :m AND t.scope_type = 'route' AND t.product_level = 'sku' AND t.superseded_by_revision_id IS NULL ${sql.geo} ${sql.product}
                 GROUP BY 1
                """,
            ).bind("m", month.atDay(1))).map { rs, _ -> rs.getString(1) to (rs.getBigDecimal(2) to rs.getInt(3)) }.list().toMap()
            val soldBy = sold.associate { it.first to it.third }
            val cards = cats.sortedBy { CATEGORY_ORDER.indexOf(it.first).let { i -> if (i < 0) 99 else i } }.map { (cat, unit) ->
                val total = soldBy[cat] ?: 0L
                val tgt = targets[cat]
                val daily = if (tgt == null || wd == 0) null else tgt.first.divide(BigDecimal(wd), 3, RoundingMode.HALF_UP)
                NationalSalesCard(
                    category_code = cat, base_unit = unit, total_sales = total, daily_target = daily?.toPlainString(), sales_so_far = total,
                    remaining = daily?.let { (it - BigDecimal(total)).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP).toPlainString() },
                    achievement_pct_bp = daily?.takeIf { it.signum() > 0 }?.let { ratioBp(BigDecimal(total), it) },
                    target_routes = tgt?.second ?: 0,
                )
            }
            val m = meta(h, date, listOf("agg_daily_route_sku"))
            NationalSales(m.date, m.till, m.asOf, m.last, wd, cards)
        }
    }

    // ---------------------------------------------------------------- Live CPR

    fun cpr(caller: NationalCaller, f: NationalDashboardFilter): NationalCpr {
        denyBrandUser(caller)
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val targetOutlets = sql.bind(h.createQuery(
                """
                SELECT coalesce(sum(a.target_outlets), 0) FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                 WHERE a.business_date = :d AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr' ${sql.geo}
                """,
            ).bind("d", date)).mapTo(Long::class.java).one().toInt()
            val calls = if (!sql.hasProductFilter) {
                sql.bind(h.createQuery(
                    """
                    SELECT coalesce(sum(a.successful_calls), 0) FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                     WHERE a.business_date = :d AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr' ${sql.geo}
                    """,
                ).bind("d", date)).mapTo(Long::class.java).one().toInt()
            } else {
                sql.bind(h.createQuery(
                    """
                    SELECT count(DISTINCT (s.route_id, s.outlet_id)) FROM dw.agg_daily_outlet_sku s JOIN dw.dim_geo g ON g.route_id = s.route_id
                      JOIN dw.dim_product p ON p.sku_id = s.sku_id JOIN dw.agg_daily_route a ON a.route_id = s.route_id AND a.business_date = s.business_date
                     WHERE s.business_date = :d AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr' ${sql.geo} ${sql.product}
                    """,
                ).bind("d", date)).mapTo(Long::class.java).one().toInt()
            }
            val m = meta(h, date, listOf("agg_daily_route", "agg_daily_outlet_sku"))
            NationalCpr(m.date, m.till, m.asOf, m.last, targetOutlets, calls, pctBp(calls.toLong(), targetOutlets.toLong()))
        }
    }

    // ---------------------------------------------------------------- channel cards

    private fun shares(rows: Map<String, Long>): List<ChannelShare> {
        val total = rows.values.sum()
        return CHANNELS.map { c -> ChannelShare(c, rows[c] ?: 0L, pctBp(rows[c] ?: 0L, total)) }
    }

    fun channelCalls(caller: NationalCaller, f: NationalDashboardFilter): NationalChannelCalls {
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val rows = sql.bind(h.createQuery(
                """
                SELECT o.channel, count(DISTINCT (s.route_id, s.outlet_id)) AS n
                  FROM dw.agg_daily_outlet_sku s JOIN dw.dim_geo g ON g.route_id = s.route_id JOIN dw.dim_product p ON p.sku_id = s.sku_id
                  JOIN dw.dim_outlet o ON o.outlet_id = s.outlet_id JOIN dw.agg_daily_route a ON a.route_id = s.route_id AND a.business_date = s.business_date
                 WHERE s.business_date = :d AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr' ${sql.geo} ${sql.product} GROUP BY 1
                """,
            ).bind("d", date)).map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
            val m = meta(h, date, listOf("agg_daily_outlet_sku", "agg_daily_route"))
            NationalChannelCalls(m.date, m.till, m.asOf, m.last, shares(rows), rows.filterKeys { it in CHANNELS }.values.sum().toInt(), rows.filterKeys { it !in CHANNELS }.values.sum().toInt())
        }
    }

    fun channelStd(caller: NationalCaller, f: NationalDashboardFilter): NationalChannelStd {
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val rows = sql.bind(h.createQuery(
                """
                SELECT o.channel, sum(s.sold_qty_base) AS q
                  FROM dw.agg_daily_outlet_sku s JOIN dw.dim_geo g ON g.route_id = s.route_id JOIN dw.dim_product p ON p.sku_id = s.sku_id
                  JOIN dw.dim_outlet o ON o.outlet_id = s.outlet_id
                 WHERE s.business_date = :d AND p.category_code = 'cigarette' ${sql.geo} ${sql.product} GROUP BY 1
                """,
            ).bind("d", date)).map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
            val m = meta(h, date, listOf("agg_daily_outlet_sku"))
            NationalChannelStd(m.date, m.till, m.asOf, m.last, shares(rows), rows.values.sum())
        }
    }

    // ---------------------------------------------------------------- Final Submit, Login / Submit

    fun finalSubmit(caller: NationalCaller, f: NationalDashboardFilter): NationalFinalSubmit {
        denyBrandUser(caller)
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val total = sql.bind(h.createQuery("SELECT count(DISTINCT g.zone_id) FROM dw.dim_geo g WHERE g.status = 'active' ${sql.geo}")).mapTo(Long::class.java).one().toInt()
            val (service, submitted) = sql.bind(h.createQuery(
                """
                WITH svc AS (SELECT DISTINCT a.zone_id FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                              WHERE a.business_date = :d AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr' ${sql.geo})
                SELECT count(*), count(*) FILTER (WHERE z.final_submitted_at IS NOT NULL)
                  FROM svc LEFT JOIN dw.agg_daily_zone z ON z.zone_id = svc.zone_id AND z.business_date = :d
                """,
            ).bind("d", date)).map { rs, _ -> rs.getInt(1) to rs.getInt(2) }.one()
            val m = meta(h, date, listOf("agg_daily_zone", "agg_daily_route"))
            NationalFinalSubmit(m.date, m.till, m.asOf, m.last, total, service, submitted, service - submitted, pctBp(submitted.toLong(), service.toLong()))
        }
    }

    fun loginSubmit(caller: NationalCaller, f: NationalDashboardFilter): NationalLoginSubmit {
        denyBrandUser(caller)
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val (target, login, submitted) = sql.bind(h.createQuery(
                """
                SELECT count(*), count(*) FILTER (WHERE a.logged_in_at IS NOT NULL AND a.downloaded_at IS NOT NULL),
                       count(*) FILTER (WHERE a.logged_in_at IS NOT NULL AND a.downloaded_at IS NOT NULL AND a.sales_submitted_at IS NOT NULL)
                  FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                 WHERE a.business_date = :d AND a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr' ${sql.geo}
                """,
            ).bind("d", date)).map { rs, _ -> Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }.one()
            val m = meta(h, date, listOf("agg_daily_route"))
            NationalLoginSubmit(m.date, m.till, m.asOf, m.last, target, login, pctBp(login.toLong(), target.toLong()), submitted, pctBp(submitted.toLong(), login.toLong()))
        }
    }

    // ---------------------------------------------------------------- Breakdown STD / Memo

    fun breakdown(caller: NationalCaller, req: NationalBreakdownRequest): NationalBreakdown {
        if (req.measure !in setOf("std", "memo")) throw bad("measure")
        if (req.dimension !in setOf("segment", "brand", "variant")) throw bad("dimension")
        val f = req.filter; val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            // A memo count per node is exact only when no narrower product filter applies: the per-route tables count every memo holding any SKU of the node.
            val narrower = when (req.dimension) {
                "segment" -> f.brand_codes.isNotEmpty() || f.variant_codes.isNotEmpty() || caller.brandIds != null
                "brand" -> f.variant_codes.isNotEmpty()
                else -> false
            }
            if (req.measure == "memo" && narrower) throw ApiProblem(ProblemCode.ERR_VALIDATION, "memo counts by ${req.dimension} cannot be combined with a narrower product filter",
                errors = listOf(FieldError("dimension", "unsupported_combination")))
            val nodeCols = when (req.dimension) { "segment" -> "p.segment_id, p.segment_code, p.segment_name"; "brand" -> "p.brand_id, p.brand_code, p.brand_name"; else -> "p.variant_id, p.variant_code, p.variant_name" }
            val items: List<BreakdownItem>
            var category: String? = null
            var unit: String? = null
            if (req.measure == "std") {
                // STD mixes sticks, pieces and dozens: one category per answer (the requested one, else the biggest).
                category = req.category_code ?: sql.bind(h.createQuery(
                    """
                    SELECT p.category_code FROM dw.agg_daily_route_sku a JOIN dw.dim_geo g ON g.route_id = a.route_id JOIN dw.dim_product p ON p.sku_id = a.sku_id
                     WHERE a.business_date = :d ${sql.geo} ${sql.product} GROUP BY 1 ORDER BY sum(a.sold_qty_base) DESC, 1 LIMIT 1
                    """,
                ).bind("d", date)).mapTo(String::class.java).findOne().orElse(null)
                unit = category?.let { c -> h.createQuery("SELECT min(base_unit) FROM dw.dim_product WHERE category_code = :c").bind("c", c).mapTo(String::class.java).findOne().orElse(null) }
                items = if (category == null) emptyList() else sql.bind(h.createQuery(
                    """
                    SELECT $nodeCols, sum(a.sold_qty_base) AS v
                      FROM dw.agg_daily_route_sku a JOIN dw.dim_geo g ON g.route_id = a.route_id JOIN dw.dim_product p ON p.sku_id = a.sku_id
                     WHERE a.business_date = :d AND p.category_code = :cat ${sql.geo} ${sql.product} GROUP BY 1, 2, 3 HAVING sum(a.sold_qty_base) > 0 ORDER BY v DESC, 3
                    """,
                ).bind("d", date).bind("cat", category)).map { rs, _ -> BreakdownItem(rs.getString(2), rs.getString(3), rs.getLong(4)) }.list()
            } else {
                // Memo counts are distinct memos per item (never added across items): the per-route tables count each memo once per node.
                val (table, col) = when (req.dimension) { "segment" -> "dw.agg_daily_route_segment" to "segment_id"; "brand" -> "dw.agg_daily_route_brand" to "brand_id"; else -> "dw.agg_daily_route_variant" to "variant_id" }
                items = sql.bind(h.createQuery(
                    """
                    SELECT n.$col, n.code, n.name, sum(a.memo_count) AS v
                      FROM $table a JOIN dw.dim_geo g ON g.route_id = a.route_id
                      JOIN (SELECT DISTINCT $nodeCols FROM dw.dim_product p WHERE true ${sql.product}) n(${col}, code, name) ON n.$col = a.$col
                     WHERE a.business_date = :d ${sql.geo} GROUP BY 1, 2, 3 HAVING sum(a.memo_count) > 0 ORDER BY v DESC, 3
                    """,
                ).bind("d", date)).map { rs, _ -> BreakdownItem(rs.getString(2), rs.getString(3), rs.getLong(4)) }.list()
            }
            val tables = when (req.measure) { "std" -> listOf("agg_daily_route_sku"); else -> listOf(if (req.dimension == "variant") "agg_daily_route_variant" else "agg_daily_route_sku") }
            val m = meta(h, date, tables)
            NationalBreakdown(m.date, m.till, m.asOf, m.last, req.measure, req.dimension, category, unit, items)
        }
    }

    // ---------------------------------------------------------------- Geo Fencing

    fun geoFencing(caller: NationalCaller, f: NationalDashboardFilter): NationalGeoFencing {
        denyBrandUser(caller)
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val (total, valid) = sql.bind(h.createQuery(
                """
                SELECT coalesce(sum(a.completed_calls), 0), coalesce(sum(a.completed_geo_valid_calls), 0)
                  FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id WHERE a.business_date = :d ${sql.geo}
                """,
            ).bind("d", date)).map { rs, _ -> rs.getInt(1) to rs.getInt(2) }.one()
            val m = meta(h, date, listOf("agg_daily_route"))
            NationalGeoFencing(m.date, m.till, m.asOf, m.last, total, valid, pctBp(valid.toLong(), total.toLong()))
        }
    }

    // ---------------------------------------------------------------- filter panel and freshness

    fun filterOptions(caller: NationalCaller): NationalFilterOptions {
        val sql = NationalSql(caller, NationalDashboardFilter())
        return read { h ->
            ensureReady(h)
            val date = today()
            fun nodes(q: String, map: (java.sql.ResultSet) -> OptionNode) = sql.bind(h.createQuery(q)).map { rs, _ -> map(rs) }.list()
            val wings = nodes("SELECT DISTINCT w.id, w.code, w.name FROM dw.dim_geo g JOIN app.wing w ON w.id = g.wing_id WHERE true ${sql.geo} ORDER BY 3") { OptionNode(it.getLong(1), it.getString(2), it.getString(3)) }
            val divisions = nodes("SELECT DISTINCT d.id, d.code, d.name, d.wing_id FROM dw.dim_geo g JOIN app.division d ON d.id = g.division_id WHERE true ${sql.geo} ORDER BY 3") { OptionNode(it.getLong(1), it.getString(2), it.getString(3), wing_id = it.getLong(4)) }
            val territories = nodes("SELECT DISTINCT t.id, t.code, t.name, t.division_id FROM dw.dim_geo g JOIN app.territory t ON t.id = g.territory_id WHERE true ${sql.geo} ORDER BY 3") { OptionNode(it.getLong(1), it.getString(2), it.getString(3), division_id = it.getLong(4)) }
            val houses = nodes("SELECT DISTINCT hs.id, hs.code, hs.name, hs.territory_id FROM dw.dim_geo g JOIN app.house hs ON hs.id = g.house_id WHERE true ${sql.geo} ORDER BY 3") { OptionNode(it.getLong(1), it.getString(2), it.getString(3), territory_id = it.getLong(4)) }
            val zones = nodes("SELECT DISTINCT z.id, z.code, z.name, g.house_id, z.territory_id FROM dw.dim_geo g JOIN app.zone z ON z.id = g.zone_id WHERE true ${sql.geo} ORDER BY 3") {
                OptionNode(it.getLong(1), it.getString(2), it.getString(3), house_id = it.getObject(4) as Long?, territory_id = it.getLong(5))
            }
            fun codes(q: String, map: (java.sql.ResultSet) -> OptionCode) = sql.bind(h.createQuery(q)).map { rs, _ -> map(rs) }.list()
            val categories = codes("SELECT DISTINCT p.category_code, p.category_name FROM dw.dim_product p WHERE true ${sql.product} ORDER BY 1") { OptionCode(it.getString(1), it.getString(2)) }
            val segments = codes("SELECT DISTINCT p.segment_code, p.segment_name, p.category_code FROM dw.dim_product p WHERE p.segment_code IS NOT NULL ${sql.product} ORDER BY 2") { OptionCode(it.getString(1), it.getString(2), category_code = it.getString(3)) }
            val brands = codes("SELECT DISTINCT p.brand_code, p.brand_name, p.segment_code FROM dw.dim_product p WHERE p.brand_code IS NOT NULL ${sql.product} ORDER BY 2") { OptionCode(it.getString(1), it.getString(2), segment_code = it.getString(3)) }
            val variants = codes("SELECT DISTINCT p.variant_code, p.variant_name, p.brand_code FROM dw.dim_product p WHERE p.variant_code IS NOT NULL ${sql.product} ORDER BY 2") { OptionCode(it.getString(1), it.getString(2), brand_code = it.getString(3)) }
            val m = meta(h, date, emptyList())
            NationalFilterOptions(m.date, m.till, m.asOf, m.last, wings, divisions, territories, houses, zones, categories, segments, brands, variants)
        }
    }

    fun freshness(): NationalFreshness = read { h ->
        ensureReady(h)
        val rows = h.createQuery("SELECT table_name, last_updated_at FROM dw.summary_freshness").map { rs, _ -> rs.getString(1) to rs.getTimestamp(2).toInstant() }.list().toMap()
        val tables = DashboardSummaries.FRESHNESS_TABLES.map { FreshnessTable(it, rows[it]?.toString()) }
        val oldest: Instant? = if (DashboardSummaries.FRESHNESS_TABLES.all { it in rows }) rows.filterKeys { it in DashboardSummaries.FRESHNESS_TABLES }.values.min() else null
        NationalFreshness(today().toString(), true, clock.now().toString(), oldest?.toString(), tables)
    }
}
