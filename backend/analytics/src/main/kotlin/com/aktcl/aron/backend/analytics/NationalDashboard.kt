package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.WorkingCalendarRepo
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.NodePath
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.WorkingDays
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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

/** Basis points (10000 = 100 %): [Percent.bp] (F3, rounded once, half up); null when the denominator is 0. Never a float (docs/31 money gate). */
internal fun pctBp(n: Long, d: Long): Int? = if (d <= 0) null else Percent.bp(n, d)?.let { Math.toIntExact(it) }

/**
 * The national route set (D-TSO-MAN-09): normal SR route-days, planned, not excused, over `dw.agg_daily_route a` and the
 * as-was `dw.dim_geo g`. Target routes, logins, Bikroy Joma, CPR and Geo Fencing of the national cards and of the summary
 * count these route-days only, so an AMO, manual or dummy route and an excused route-day never move them.
 */
internal const val NATIONAL_ROUTE_SET = "a.planned AND NOT a.exception_approved AND g.route_class = 'normal' AND g.route_kind = 'sr'"

/**
 * A zone-day is final-submitted when its zone-day projection holds a Final Submit time (D-TSO-MAN-30). With the dw-only midnight
 * stamp removed, `dw.agg_daily_zone.final_submitted_at` is rebuilt only from the live `app.final_submit` rows (the first one with
 * `reopened_at` NULL, the midnight job's `via = 'job'` rows included; [DashboardSummaries.rebuildZoneDay]), and a submit, a reopen
 * or the job dirties the zone-day through its route-days. One formula for the national card and the summary, read from the summary
 * tables like every other card. [zone] and [date] are SQL.
 */
internal fun zoneSubmittedSql(zone: String, date: String) =
    "EXISTS (SELECT 1 FROM dw.agg_daily_zone fz WHERE fz.zone_id = $zone AND fz.business_date = $date AND fz.final_submitted_at IS NOT NULL)"

/** A non-negative thousandths value as the contract's Decimal3 ("150.000"). */
internal fun decimal3(thousandths: Long): String = "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')}"

internal fun ratioBp(part: BigDecimal, whole: BigDecimal): Long = part.multiply(BigDecimal(10_000)).divide(whole, 0, RoundingMode.HALF_UP).toLong()

/** The day's channel order, as the brief lists it. */
internal val CHANNELS = listOf("GT", "DCC", "Astha", "RCC", "MT", "HoReCa")
internal val CATEGORY_ORDER = listOf("cigarette", "bidi", "lighter", "match")

/** Unit families of the segment card, in the order the card lists them. */
internal val UNIT_ORDER = listOf("stick", "piece", "dozen")
internal const val BRAND_CPR_DENOMINATOR = "cfg.kpi.brand_cpr_denominator.tso"
internal val BRAND_CPR_DENOMINATORS = setOf("successful_calls", "target_outlets")
internal const val MAX_BRANDS = 100
internal const val MAX_SEGMENTS = 50
/** Contract `NationalLoginSubmitUsers.items` maxItems: a longer tab answers the first rows with `truncated` true. */
internal const val MAX_LOGIN_SUBMIT_ROWS = 2000
internal val LOGIN_SUBMIT_TABS = setOf("not_logged_in", "not_uploaded")

/**
 * The filter object as SQL over `dw.dim_geo g` and `dw.dim_product p`. Scope comes from the caller's reach and is ANDed with the
 * request: ids outside the scope select nothing, and nothing the client sends can widen it (CLAUDE.md constraint 4).
 */
internal class NationalSql(private val caller: NationalCaller, private val f: NationalDashboardFilter) {
    private val r = caller.reach

    /**
     * A caller whose reach holds route assignments and no zone (a route-only TSO) is read by its routes, never by a zone
     * (D-BC-DASH-ROUTE-KEYED-01, s5 B2): its routes' zones would show other routes' figures. Null for every other caller.
     */
    private val scopeRoutes: List<Long>? = if (!r.national && r.zoneIds.isEmpty() && r.routeIds.isNotEmpty()) r.routeIds.sorted() else null

    /** `AND ...` over alias g. */
    val geo: String = buildString {
        if (!r.national) append(if (scopeRoutes != null) " AND g.route_id = ANY(:scopeRoutes)" else " AND g.zone_id = ANY(:scopeZones)")
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

    /** True when the request names any geography (wing, division, territory, house or zone). */
    val hasGeoFilter: Boolean = f.wing_ids.isNotEmpty() || f.division_ids.isNotEmpty() || f.territory_ids.isNotEmpty() || f.house_ids.isNotEmpty() || f.zone_ids.isNotEmpty()

    fun <T : SqlStatement<T>> bind(q: T): T {
        if (!r.national) {
            if (scopeRoutes != null) q.bindArray("scopeRoutes", Long::class.javaObjectType, scopeRoutes)
            else q.bindArray("scopeZones", Long::class.javaObjectType, r.zoneIds.toList().ifEmpty { listOf(-1L) })
        }
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

/**
 * Working days of a month at the national (global) node with an explicit weekend (ISO 1..7): the one count of
 * D-TSO-MAN-08 ([WorkingDays] over [WorkingCalendarRepo.marks]); scoped declarations do not reach the global node. The cards
 * themselves count at the lowest common node of their scope ([NationalDashboardService.workingDaysAt]).
 */
internal object WorkingCalendar {
    val DEFAULT_WEEKEND: Set<Int> = setOf(DayOfWeek.FRIDAY.value)

    fun workingDays(h: Handle, month: YearMonth, weekend: Set<Int> = DEFAULT_WEEKEND): Int {
        val cal = WorkingDays.resolve(NodePath.GLOBAL, WorkingDays.weekendOf(weekend), WorkingCalendarRepo.marks(h, month.atDay(1), month.atEndOfMonth()))
        return WorkingDays.month(kotlinx.datetime.YearMonth(month.year, month.monthValue), cal)
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
    private val config: ServerConfig? = null,
) {
    /**
     * The global `cfg.calendar.weekend_days` (the national figure has no zone; zone-scoped overrides are ignored, D-DASH-06). The registry
     * allows 0..3 days, so a list that is empty (or holds only numbers outside 1..7) means no weekend day, as in the portal's calendar and the
     * bundle; only a missing or unreadable value falls back to Friday.
     */
    internal fun weekendDays(): Set<Int> = config?.let { c ->
        runCatching { c.value("cfg.calendar.weekend_days").jsonArray.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }.filter { it in 1..7 }.toSet() }.getOrNull()
    } ?: WorkingCalendar.DEFAULT_WEEKEND

    /** W of [month] at [path] (D-TSO-MAN-08): the node's weekend, the declarations on its path, the one count. */
    internal fun workingDaysAt(h: Handle, month: YearMonth, path: NodePath): Int = WorkingCalendarRepo.load(h, month, clock.now(), config).workingDays(path)

    /**
     * The node of a card's working days (D-TSO-MAN-08): the lowest common ancestor of the effective scope (the filter cut to
     * the token's reach). A national caller without a geography filter is the global node, whatever the zones in the
     * database; otherwise the zones the scope selects decide (one zone gives the zone, one territory the territory, mixed
     * gives global).
     */
    internal fun scopePath(h: Handle, caller: NationalCaller, sql: NationalSql): NodePath {
        if (caller.reach.national && !sql.hasGeoFilter) return NodePath.GLOBAL
        val paths = sql.bind(h.createQuery("SELECT DISTINCT g.wing_id, g.division_id, g.territory_id, g.zone_id FROM dw.dim_geo g WHERE true ${sql.geo}"))
            .map { rs, _ -> NodePath(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }.list()
        return WorkingCalendarRepo.commonPath(paths)
    }

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
            val asWas = AsWasSql.probe(h, date)
            val sold = sql.bind(h.createQuery(
                """
                SELECT p.category_code, p.base_unit, sum(a.sold_qty_base) AS qty
                  FROM dw.agg_daily_route_sku a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)} ${AsWasSql.product("p", "a.sku_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.product)}
                 WHERE a.business_date = :d ${sql.geo} ${sql.product} GROUP BY 1, 2
                """,
            ).bind("d", date)).map { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getLong(3)) }.list()
            val cats = sql.bind(h.createQuery("SELECT DISTINCT p.category_code, p.base_unit FROM ${AsWasSql.productAll("p", lo = ":d", hi = ":d", plain = asWas.product)} WHERE true ${sql.product}").bind("d", date)).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
            val month = YearMonth.from(date)
            val wd = workingDaysAt(h, month, scopePath(h, caller, sql))
            val targets = sql.bind(h.createQuery(
                """
                SELECT p.category_code, sum(t.qty_base) AS total, count(DISTINCT t.route_id) AS routes
                  FROM app.route_monthly_target t ${AsWasSql.geo("g", "t.route_id", "CAST(:d AS date)", lo = ":d", hi = ":d", plain = asWas.geo)} ${AsWasSql.product("p", "t.sku_id", "CAST(:d AS date)", lo = ":d", hi = ":d", plain = asWas.product)}
                 WHERE t.month = :m ${sql.geo} ${sql.product}
                 GROUP BY 1
                """,
            ).bind("m", month.atDay(1)).bind("d", date)).map { rs, _ -> rs.getString(1) to (rs.getLong(2) to rs.getInt(3)) }.list().toMap()
            val soldBy = sold.associate { it.first to it.third }
            val w = wd.toLong()
            val cards = cats.sortedBy { CATEGORY_ORDER.indexOf(it.first).let { i -> if (i < 0) 99 else i } }.map { (cat, unit) ->
                val total = soldBy[cat] ?: 0L
                val tgt = targets[cat]
                val monthly = tgt?.first
                // Contract 1.10.0, D-TSO-MAN-08/10, R-F-13: every figure from the integers T, A, W, each rounded once; W = 0 gives no daily
                // target and no ring ("—"). The daily target is round_half_up(1000 T / W) thousandths; the ring is exact:
                // round_half_up(10000 A W / T), never A over the rounded daily target.
                val daily = if (monthly == null || w == 0L) null else Math.addExact(Math.multiplyExact(2_000L, monthly), w) / Math.multiplyExact(2L, w)
                NationalSalesCard(
                    category_code = cat, base_unit = unit, total_sales = total, daily_target = daily?.let(::decimal3), sales_so_far = total,
                    remaining = daily?.let { decimal3(maxOf(it - Math.multiplyExact(total, 1_000L), 0L)) },
                    achievement_pct_bp = if (monthly == null || w == 0L) null else Percent.bp(Math.multiplyExact(total, w), monthly),
                    target_routes = tgt?.second ?: 0, monthly_target_qty = monthly,
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
            val (targetOutlets, calls) = cprFigures(h, sql, date, AsWasSql.probe(h, date))
            val m = meta(h, date, listOf("agg_daily_route", "agg_daily_outlet_sku"))
            NationalCpr(m.date, m.till, m.asOf, m.last, targetOutlets, calls, pctBp(calls.toLong(), targetOutlets.toLong()))
        }
    }

    /** Target outlets and successful calls of the national route set (the CPR card; Brand CPR's denominators). */
    private fun cprFigures(h: Handle, sql: NationalSql, date: LocalDate, asWas: AsWasSql.Plain): Pair<Int, Int> {
        val targetOutlets = sql.bind(h.createQuery(
            """
            SELECT coalesce(sum(a.target_outlets), 0) FROM dw.agg_daily_route a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
             WHERE a.business_date = :d AND $NATIONAL_ROUTE_SET ${sql.geo}
            """,
        ).bind("d", date)).mapTo(Long::class.java).one().toInt()
        val calls = if (!sql.hasProductFilter) {
            sql.bind(h.createQuery(
                """
                SELECT coalesce(sum(a.successful_calls), 0) FROM dw.agg_daily_route a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
                 WHERE a.business_date = :d AND $NATIONAL_ROUTE_SET ${sql.geo}
                """,
            ).bind("d", date)).mapTo(Long::class.java).one().toInt()
        } else {
            sql.bind(h.createQuery(
                """
                SELECT count(DISTINCT (s.route_id, s.outlet_id)) FROM dw.agg_daily_outlet_sku s ${AsWasSql.geo("g", "s.route_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
                  ${AsWasSql.product("p", "s.sku_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.product)} JOIN dw.agg_daily_route a ON a.route_id = s.route_id AND a.business_date = s.business_date
                 WHERE s.business_date = :d AND $NATIONAL_ROUTE_SET ${sql.geo} ${sql.product}
                """,
            ).bind("d", date)).mapTo(Long::class.java).one().toInt()
        }
        return targetOutlets to calls
    }

    // ---------------------------------------------------------------- Brand CPR (contract 1.10.0)

    /** `cfg.kpi.brand_cpr_denominator.tso` (D-TSO-MAN-06): successful_calls (default, the screenshot) or target_outlets. */
    internal fun brandCprDenominator(): String =
        runCatching { config?.string(BRAND_CPR_DENOMINATOR) }.getOrNull()?.takeIf { it in BRAND_CPR_DENOMINATORS } ?: "successful_calls"

    /**
     * Brand CPR (D-TSO-MAN-06, screenshot 7): per brand, the distinct (route, outlet) pairs of the national route set with an
     * effective sale line of the brand (`dw.agg_daily_outlet_sku`: sale lines of active memos), over the configured
     * denominator; both ratios and both denominators are sent, so the switch is one config change. Brands that sold, at
     * most 100, by outlets_bought descending, then brand_code. BRAND users get 403: the denominators have no brand dimension.
     */
    fun brandCpr(caller: NationalCaller, f: NationalDashboardFilter): NationalBrandCpr {
        denyBrandUser(caller)
        val date = dateOf(f); val sql = NationalSql(caller, f)
        val denominator = brandCprDenominator()
        return read { h ->
            ensureReady(h)
            val asWas = AsWasSql.probe(h, date)
            val (targetOutlets, calls) = cprFigures(h, sql, date, asWas)
            val rows = sql.bind(h.createQuery(
                """
                SELECT p.brand_code, p.brand_name, count(DISTINCT (s.route_id, s.outlet_id)) AS n
                  FROM dw.agg_daily_outlet_sku s ${AsWasSql.geo("g", "s.route_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
                  ${AsWasSql.product("p", "s.sku_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.product)} JOIN dw.agg_daily_route a ON a.route_id = s.route_id AND a.business_date = s.business_date
                 WHERE s.business_date = :d AND p.brand_code IS NOT NULL AND $NATIONAL_ROUTE_SET ${sql.geo} ${sql.product}
                 GROUP BY 1, 2 ORDER BY n DESC, 1 LIMIT $MAX_BRANDS
                """,
            ).bind("d", date)).map { rs, _ -> Triple(rs.getString(1), rs.getString(2) ?: rs.getString(1), rs.getLong(3)) }.list()
            val den = if (denominator == "target_outlets") targetOutlets.toLong() else calls.toLong()
            val brands = rows.map { (code, name, n) ->
                BrandCprRow(code, name, Math.toIntExact(n), Percent.bp(n, den), Percent.bp(n, calls.toLong()), Percent.bp(n, targetOutlets.toLong()))
            }
            val m = meta(h, date, listOf("agg_daily_route", "agg_daily_outlet_sku"))
            NationalBrandCpr(m.date, m.till, m.asOf, m.last, targetOutlets, calls, denominator, brands)
        }
    }

    // ---------------------------------------------------------------- By Segment Value Contribution (contract 1.10.0)

    /**
     * Segment shares (D-TSO-MAN-11, screenshot 7) over the sales card's rows (`dw.agg_daily_route_sku`, every route in scope,
     * sale lines only): value share = the segment's sale-line gross milli-taka / the total of every segment; volume share =
     * the segment's sold base units / the total of its unit family (stick: cigarette and bidi; piece; dozen), never added
     * across units. Segments that sold, at most 50, by value descending then segment_code. A BRAND user reads its brands.
     */
    fun segmentShare(caller: NationalCaller, f: NationalDashboardFilter): NationalSegmentShare {
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val asWas = AsWasSql.probe(h, date)
            data class Seg(val code: String, val name: String, val cat: String, val unit: String, val value: Long, val qty: Long)
            val all = sql.bind(h.createQuery(
                """
                SELECT p.segment_code, min(p.segment_name), p.category_code, p.base_unit, sum(a.gross_mtk) AS v, sum(a.sold_qty_base) AS q
                  FROM dw.agg_daily_route_sku a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)} ${AsWasSql.product("p", "a.sku_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.product)}
                 WHERE a.business_date = :d AND p.segment_code IS NOT NULL ${sql.geo} ${sql.product}
                 GROUP BY p.segment_code, p.category_code, p.base_unit HAVING sum(a.gross_mtk) > 0 OR sum(a.sold_qty_base) > 0
                """,
            ).bind("d", date)).map { rs, _ -> Seg(rs.getString(1), rs.getString(2) ?: rs.getString(1), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6)) }.list()
            val total = all.fold(0L) { acc, s -> Math.addExact(acc, s.value) }
            val families = all.groupBy { it.unit }.mapValues { (_, v) -> v.fold(0L) { acc, s -> Math.addExact(acc, s.qty) } }
            val segments = all.sortedWith(compareByDescending<Seg> { it.value }.thenBy { it.code }).take(MAX_SEGMENTS).map { s ->
                SegmentShareRow(s.code, s.name, s.cat, s.unit, s.value, pctBp(s.value, total), s.qty, pctBp(s.qty, families.getValue(s.unit)))
            }
            val m = meta(h, date, listOf("agg_daily_route_sku"))
            NationalSegmentShare(m.date, m.till, m.asOf, m.last, total, UNIT_ORDER.filter { it in families }.map { SegmentFamily(it, families.getValue(it)) }, segments)
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
            val asWas = AsWasSql.probe(h, date)
            val rows = sql.bind(h.createQuery(
                """
                SELECT o.channel, count(DISTINCT (s.route_id, s.outlet_id)) AS n
                  FROM dw.agg_daily_outlet_sku s ${AsWasSql.geo("g", "s.route_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)} ${AsWasSql.product("p", "s.sku_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.product)}
                  ${AsWasSql.outlet("o", "s.outlet_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.outlet)} JOIN dw.agg_daily_route a ON a.route_id = s.route_id AND a.business_date = s.business_date
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
            val asWas = AsWasSql.probe(h, date)
            val rows = sql.bind(h.createQuery(
                """
                SELECT o.channel, sum(s.sold_qty_base) AS q
                  FROM dw.agg_daily_outlet_sku s ${AsWasSql.geo("g", "s.route_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)} ${AsWasSql.product("p", "s.sku_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.product)}
                  ${AsWasSql.outlet("o", "s.outlet_id", "s.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.outlet)}
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
            val asWas = AsWasSql.probe(h, date)
            val total = sql.bind(h.createQuery("SELECT count(DISTINCT g.zone_id) FROM dw.dim_geo g WHERE g.status = 'active' ${sql.geo}")).mapTo(Long::class.java).one().toInt()
            // D-TSO-MAN-30: a service zone (a route-day of the national route set) counts as submitted by its live app.final_submit rows (the
            // midnight job's included) through the zone-day projection; the dw-only stamp is gone, so this % and the summary's are one formula.
            val (service, submitted) = sql.bind(h.createQuery(
                """
                WITH svc AS (SELECT DISTINCT a.zone_id FROM dw.agg_daily_route a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
                              WHERE a.business_date = :d AND $NATIONAL_ROUTE_SET ${sql.geo})
                SELECT count(*), count(*) FILTER (WHERE ${zoneSubmittedSql("svc.zone_id", "CAST(:d AS date)")}) FROM svc
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
            val asWas = AsWasSql.probe(h, date)
            // Brief s3.4: a successful login is a login AND a download AND a check-in (DashboardSummaries.successfulLoginSql).
            val ok = DashboardSummaries.successfulLoginSql(h)
            val (target, login, submitted) = sql.bind(h.createQuery(
                """
                SELECT count(*), count(*) FILTER (WHERE $ok),
                       count(*) FILTER (WHERE $ok AND a.sales_submitted_at IS NOT NULL)
                  FROM dw.agg_daily_route a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
                 WHERE a.business_date = :d AND $NATIONAL_ROUTE_SET ${sql.geo}
                """,
            ).bind("d", date)).map { rs, _ -> Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }.one()
            val m = meta(h, date, listOf("agg_daily_route"))
            NationalLoginSubmit(
                m.date, m.till, m.asOf, m.last, target, login, pctBp(login.toLong(), target.toLong()), submitted, pctBp(submitted.toLong(), login.toLong()),
                submit_of_target_pct_bp = pctBp(submitted.toLong(), target.toLong()),   // D-TSO-MAN-07: the TSO app's Bikroy Joma % (1 of 5 = 20 %)
            )
        }
    }

    /**
     * The rows behind the Login / Submit Status card (D-TSO-MAN-12, screenshot 8), over the card's target routes (the national
     * route set): `not_logged_in` = routes without a successful login, `not_uploaded` = routes with one and no Bikroy Joma. Per
     * route one row for the route-day SR (the acting SR, else the assigned one; vacant: user null, "SR Not Set"), then one row
     * per active AMO whose `user_scope` covers the route's zone on the date (by the zone's current wing, division, territory,
     * house), by username; AMO rows follow the route's state, not the AMO's own login. Dep Name = the zone's name. Order: route
     * name, route id, SR row first, AMOs by username. At most [MAX_LOGIN_SUBMIT_ROWS] rows with `truncated`; `route_count`
     * counts every route of the tab, so it equals target - successful_login (or successful_login - submitted) of the card.
     */
    fun loginSubmitUsers(caller: NationalCaller, tab: String?, f: NationalDashboardFilter): NationalLoginSubmitUsers {
        denyBrandUser(caller)
        val t = tab ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "tab is required", errors = listOf(FieldError("query.tab", "required")))
        if (t !in LOGIN_SUBMIT_TABS) throw ApiProblem(ProblemCode.ERR_VALIDATION, "tab must be not_logged_in or not_uploaded", errors = listOf(FieldError("query.tab", "invalid_value")))
        val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val asWas = AsWasSql.probe(h, date)
            val ok = DashboardSummaries.successfulLoginSql(h)
            val inTab = if (t == "not_logged_in") "NOT ($ok)" else "($ok) AND a.sales_submitted_at IS NULL"
            val routes = """
                SELECT DISTINCT a.route_id, g.route_code, coalesce(g.route_name, g.route_code) AS route_name, g.zone_id, coalesce(g.zone_name, '') AS zone_name
                  FROM dw.agg_daily_route a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
                 WHERE a.business_date = :d AND $NATIONAL_ROUTE_SET AND $inTab ${sql.geo}
            """
            val routeCount = sql.bind(h.createQuery("SELECT count(DISTINCT route_id) FROM ($routes) r").bind("d", date)).mapTo(Long::class.java).one().toInt()
            val dated = "s.valid_from <= :d AND (s.valid_to IS NULL OR s.valid_to > :d)"
            val rows = sql.bind(h.createQuery(
                """
                WITH r AS ($routes),
                zs AS (SELECT DISTINCT r.zone_id, z.house_id, z.territory_id, t.division_id, dv.wing_id
                         FROM r JOIN app.zone z ON z.id = r.zone_id JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id),
                cover AS (SELECT zs.zone_id, s.user_id FROM zs JOIN app.user_scope s ON s.node_type = 'zone' AND s.node_id = zs.zone_id AND $dated
                          UNION SELECT zs.zone_id, s.user_id FROM zs JOIN app.user_scope s ON s.node_type = 'house' AND s.node_id = zs.house_id AND $dated
                          UNION SELECT zs.zone_id, s.user_id FROM zs JOIN app.user_scope s ON s.node_type = 'territory' AND s.node_id = zs.territory_id AND $dated
                          UNION SELECT zs.zone_id, s.user_id FROM zs JOIN app.user_scope s ON s.node_type = 'division' AND s.node_id = zs.division_id AND $dated
                          UNION SELECT zs.zone_id, s.user_id FROM zs JOIN app.user_scope s ON s.node_type = 'wing' AND s.node_id = zs.wing_id AND $dated
                          UNION SELECT zs.zone_id, s.user_id FROM zs JOIN app.user_scope s ON s.node_type = 'national' AND $dated),
                x AS (SELECT r.*, 0 AS ord, u.id AS uid, u.username, u.full_name, 'SR' AS role
                        FROM r LEFT JOIN app.route_day rd ON rd.route_id = r.route_id AND rd.business_date = :d
                        LEFT JOIN app.app_user u ON u.id = coalesce(rd.acting_user_id, rd.assigned_user_id)
                      UNION ALL
                      SELECT r.*, 1, u.id, u.username, u.full_name, 'AMO'
                        FROM r JOIN cover c ON c.zone_id = r.zone_id JOIN app.app_user u ON u.id = c.user_id AND u.role = 'AMO' AND u.status = 'active')
                SELECT route_id, route_code, route_name, zone_id, zone_name, uid, username, full_name, role FROM x
                 ORDER BY route_name, route_id, ord, username LIMIT ${MAX_LOGIN_SUBMIT_ROWS + 1}
                """,
            ).bind("d", date)).map { rs, _ ->
                LoginSubmitUserRow(
                    rs.getLong("route_id"), rs.getString("route_code"), rs.getString("route_name"), rs.getLong("zone_id"), rs.getString("zone_name"),
                    rs.getObject("uid") as Long?, rs.getString("username"), rs.getString("full_name"), rs.getString("role"),
                )
            }.list()
            val m = meta(h, date, listOf("agg_daily_route"))
            NationalLoginSubmitUsers(m.date, m.till, m.asOf, m.last, t, routeCount, rows.size > MAX_LOGIN_SUBMIT_ROWS, rows.take(MAX_LOGIN_SUBMIT_ROWS))
        }
    }

    // ---------------------------------------------------------------- Breakdown STD / Memo

    fun breakdown(caller: NationalCaller, req: NationalBreakdownRequest): NationalBreakdown {
        if (req.measure !in setOf("std", "memo")) throw bad("measure")
        if (req.dimension !in setOf("segment", "brand", "variant")) throw bad("dimension")
        val f = req.filter; val date = dateOf(f); val sql = NationalSql(caller, f)
        return read { h ->
            ensureReady(h)
            val asWas = AsWasSql.probe(h, date)
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
                // STD mixes sticks, pieces and dozens: one category per answer (the requested one, else the first in the brief's order that has sales: quantities of
                // different units are never compared).
                category = req.category_code ?: sql.bind(h.createQuery(
                    """
                    SELECT p.category_code FROM dw.agg_daily_route_sku a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)} ${AsWasSql.product("p", "a.sku_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.product)}
                     WHERE a.business_date = :d ${sql.geo} ${sql.product} GROUP BY 1 ORDER BY array_position(ARRAY['cigarette','bidi','lighter','match'], p.category_code), 1 LIMIT 1
                    """,
                ).bind("d", date)).mapTo(String::class.java).findOne().orElse(null)
                unit = category?.let { c -> sql.bind(h.createQuery("SELECT min(p.base_unit) FROM ${AsWasSql.productAll("p", lo = ":d", hi = ":d", plain = asWas.product)} WHERE p.category_code = :c ${sql.product}").bind("c", c).bind("d", date)).mapTo(String::class.java).findOne().orElse(null) }
                items = if (category == null) emptyList() else sql.bind(h.createQuery(
                    """
                    SELECT $nodeCols, sum(a.sold_qty_base) AS v
                      FROM dw.agg_daily_route_sku a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)} ${AsWasSql.product("p", "a.sku_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.product)}
                     WHERE a.business_date = :d AND p.category_code = :cat ${sql.geo} ${sql.product} GROUP BY 1, 2, 3 HAVING sum(a.sold_qty_base) > 0 ORDER BY v DESC, 3
                    """,
                ).bind("d", date).bind("cat", category)).map { rs, _ -> BreakdownItem(rs.getString(2), rs.getString(3), rs.getLong(4)) }.list()
            } else {
                // Memo counts are distinct memos per item (never added across items): the per-route tables count each memo once per node.
                val (table, col) = when (req.dimension) { "segment" -> "dw.agg_daily_route_segment" to "segment_id"; "brand" -> "dw.agg_daily_route_brand" to "brand_id"; else -> "dw.agg_daily_route_variant" to "variant_id" }
                items = sql.bind(h.createQuery(
                    """
                    SELECT n.$col, n.code, n.name, sum(a.memo_count) AS v
                      FROM $table a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
                      JOIN (SELECT DISTINCT $nodeCols FROM ${AsWasSql.productAll("p", lo = ":d", hi = ":d", plain = asWas.product)} WHERE true ${sql.product}) n(${col}, code, name) ON n.$col = a.$col
                     WHERE a.business_date = :d ${sql.geo} GROUP BY 1, 2, 3 HAVING sum(a.memo_count) > 0 ORDER BY v DESC, 3
                    """,
                ).bind("d", date)).map { rs, _ -> BreakdownItem(rs.getString(2), rs.getString(3), rs.getLong(4)) }.list()
            }
            // Freshness comes from the table the figures are read from.
            val tables = when (req.measure) { "std" -> listOf("agg_daily_route_sku"); else -> listOf("agg_daily_route_${req.dimension}") }
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
            val asWas = AsWasSql.probe(h, date)
            // D-TSO-MAN-09: completed SR calls of the national route set (an abandoned or open visit is no call), like the summary's geo_valid_pct.
            val (total, valid) = sql.bind(h.createQuery(
                """
                SELECT coalesce(sum(a.completed_calls), 0), coalesce(sum(a.completed_geo_valid_calls), 0)
                  FROM dw.agg_daily_route a ${AsWasSql.geo("g", "a.route_id", "a.business_date", "JOIN", lo = ":d", hi = ":d", plain = asWas.geo)}
                 WHERE a.business_date = :d AND $NATIONAL_ROUTE_SET ${sql.geo}
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
            val houses = nodes("SELECT DISTINCT hs.id, hs.code, hs.name, hs.territory_id FROM dw.dim_geo g JOIN app.house hs ON hs.id = g.house_id WHERE hs.is_active ${sql.geo} ORDER BY 3") { OptionNode(it.getLong(1), it.getString(2), it.getString(3), territory_id = it.getLong(4)) }
            val zones = nodes("SELECT DISTINCT z.id, z.code, z.name, g.house_id, z.territory_id FROM dw.dim_geo g JOIN app.zone z ON z.id = g.zone_id LEFT JOIN app.house hz ON hz.id = z.house_id WHERE (z.house_id IS NULL OR hz.is_active) ${sql.geo} ORDER BY 3") {
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
