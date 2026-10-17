package com.aktcl.aron.feature.tso

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A sales category tile (F-TSO-002): the quantity in the unit the business reports it in. */
data class CategorySales(val code: String, val baseUnit: String, val qtyBase: Long, val reportUnit: String?, val qtyReport: Double?, val netMtk: Long)
/** A channel, segment or brand row of the aggregate cards. */
data class SliceRow(val code: String, val name: String, val netMtk: Long, val qtyBase: Long?, val memos: Long, val calls: Long, val ratioPct: Double?)
data class ZoneRef(val id: Long, val name: String)

/** The territory's figures for one day, as served by `GET /v1/dashboards/summary` (no target, docs/27). [asOf] is the server's stamp. */
data class TsoDash(
    val asOf: String, val categories: List<CategorySales>, val channels: List<SliceRow>, val segments: List<SliceRow>, val brands: List<SliceRow>,
    val targetOutlets: Long, val visitedOutlets: Long, val successfulCalls: Long, val strikeRatePct: Double?,
    val netMtk: Long, val activeMemos: Long, val zones: List<ZoneRef>,
    /** The node the figures are for (the territory, or the chosen zone). */
    val nodeName: String? = null,
)

/** A route that has not logged in or not uploaded, with the SR (the pair the drill-down shows). */
data class RouteDayRow(val routeId: Long, val routeCode: String?, val routeName: String?, val state: String)
data class LoginSubmit(
    val asOf: String, val targetRoutes: Long, val loggedIn: Long, val loginPct: Double?, val submitted: Long, val submitPct: Double?,
    val notLoggedIn: List<RouteDayRow>, val loggedInNotSubmitted: List<RouteDayRow>, val submittedRoutes: List<RouteDayRow>,
)

/** Pure model and number rules of the TSO dashboard cards (F-TSO-002..007, F-TSO-027); no timer, no network. */
object TsoDashboards {
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun l(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun d(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun obj(json: String?) = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull()

    private fun slices(a: JsonElementList?) = a.orEmpty().mapNotNull { e ->
        val r = e as? JsonObject ?: return@mapNotNull null
        SliceRow(s(r, "code") ?: return@mapNotNull null, s(r, "name") ?: s(r, "code").orEmpty(), l(r, "net_mtk") ?: 0, l(r, "qty_base"), l(r, "memo_count") ?: 0, l(r, "successful_calls") ?: 0, d(r, "memo_ratio_pct"))
    }

    fun summary(json: String?): TsoDash? {
        val o = obj(json) ?: return null
        val k = o["kpis"] as? JsonObject ?: return null
        val cats = (o["by_category"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            CategorySales(s(r, "category_code") ?: return@mapNotNull null, s(r, "base_unit").orEmpty(), l(r, "qty_base") ?: 0, s(r, "report_unit"), s(r, "qty_report")?.toDoubleOrNull(), l(r, "net_mtk") ?: 0)
        }
        val zones = (o["children"] as? JsonArray).orEmpty().mapNotNull { e ->
            val n = (e as? JsonObject)?.get("node") as? JsonObject ?: return@mapNotNull null
            // Only zones can be chosen: a territory whose children are routes has no zone filter (a route id sent as a zone would be refused or wrong).
            if (s(n, "type") != "zone") return@mapNotNull null
            ZoneRef(l(n, "id") ?: return@mapNotNull null, s(n, "name") ?: s(n, "code") ?: return@mapNotNull null)
        }
        return TsoDash(
            s(o, "as_of").orEmpty(), cats, slices(o["by_channel"] as? JsonArray), slices(o["by_segment"] as? JsonArray), slices(o["by_brand"] as? JsonArray),
            l(k, "target_outlets") ?: 0, l(k, "visited_outlets") ?: 0, l(k, "successful_calls") ?: 0, d(k, "strike_rate_pct"), l(k, "net_mtk") ?: 0, l(k, "active_memo_count") ?: 0, zones,
            ((o["node"] as? JsonObject)?.let { s(it, "name") }),
        )
    }

    private fun routes(o: JsonObject, k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { e ->
        val r = e as? JsonObject ?: return@mapNotNull null
        RouteDayRow(l(r, "route_id") ?: return@mapNotNull null, s(r, "route_code"), s(r, "route_name"), s(r, "state").orEmpty())
    }

    fun loginSubmit(json: String?): LoginSubmit? {
        val o = obj(json) ?: return null
        val k = o["kpis"] as? JsonObject ?: return null
        return LoginSubmit(
            s(o, "as_of").orEmpty(), l(k, "target_routes") ?: 0, l(k, "logged_in_routes") ?: 0, d(k, "login_pct"), l(k, "sales_submitted_routes") ?: 0, d(k, "submit_pct_of_logged_in"),
            routes(o, "not_logged_in"), routes(o, "logged_in_not_submitted"), routes(o, "submitted"),
        )
    }

    /** One decimal, dot (the screen swaps the digits): 4.5 for 2 of 44. */
    fun oneDecimal(v: Double): String = BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toPlainString()

    /** Call rate: successful calls over the territory's target outlets (the planned outlets, not a business target: the server's strike_rate_pct is the same ratio), percent; null without outlets. */
    fun strikeRate(successful: Long, targetOutlets: Long): Double? = if (targetOutlets <= 0) null else successful * 100.0 / targetOutlets

    /** 1,110.5: the whole part grouped by the caller, then the decimal separator and one digit (never "0.0" for a small positive value). */
    fun fractionDigit(v: Double): String = oneDecimal(v).substringAfter('.')

    /** The unit a category is reported in when the server sent none (an empty tile keeps its own unit). */
    fun defaultUnit(code: String): String = when (code) { "lighter" -> "piece"; "match" -> "dozen"; else -> "stick" }

    /** Shares of a donut: each row's part of the total in percent (zero total gives no shares); negatives count as zero. */
    fun shares(values: List<Long>): List<Double> {
        val pos = values.map { it.coerceAtLeast(0) }; val total = pos.sum()
        return if (total <= 0) pos.map { 0.0 } else pos.map { it * 100.0 / total }
    }

    enum class Scale { NONE, LAKH, CRORE }
    data class Compact(val text: String, val scale: Scale)

    /** Lakh and crore display of a large count with the digits the caller localises: 36,100,000 is "3.61" crore; below a lakh the number stays whole with thousands grouping. */
    fun compact(n: Long): Compact = when {
        n >= 10_000_000 -> Compact(BigDecimal(n).divide(BigDecimal(10_000_000), 2, RoundingMode.HALF_UP).toPlainString(), Scale.CRORE)
        n >= 100_000 -> Compact(BigDecimal(n).divide(BigDecimal(100_000), 2, RoundingMode.HALF_UP).toPlainString(), Scale.LAKH)
        else -> Compact(n.toString(), Scale.NONE)
    }

    /** "as of hh:mm" in Dhaka time from the server's stamp; null when the stamp is missing or unreadable. */
    fun asOfClock(iso: String): String? = runCatching { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Dhaka")).format(Instant.parse(iso)) }.getOrNull()

    /** The share of a brand's memos over active memos, as served; null shows a dash. */
    fun brandRatio(row: SliceRow, activeMemos: Long): Double? = row.ratioPct ?: if (activeMemos > 0) row.memos * 100.0 / activeMemos else null
}

private typealias JsonElementList = JsonArray
