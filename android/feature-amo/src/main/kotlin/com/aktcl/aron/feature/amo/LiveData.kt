package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.Money
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Sales of one category for the day in base units (sticks, pieces, dozens), as the server's `CategoryVolume` carries them. */
data class CategorySales(
    val code: String, val baseUnit: String, val qtyBase: Long, val memos: Long? = null,
    /** `qty_entered` (Decimal3, exact) in `entry_unit` (pack | dozen): the field-unit figure, summed per SKU on the server; null when the server sent none. */
    val entered: String? = null, val enteredUnit: String? = null,
) {
    /** The field-unit figure and its label key, or null (then [qtyBase] is shown in base units with the base unit word). */
    fun field(): Pair<java.math.BigDecimal, String>? {
        val v = FieldQty.parse(entered) ?: return null
        val key = UnitWords.key(enteredUnit, code) ?: return null
        return v to key
    }
}

/**
 * The Live Dashboard of screenshot 39 (D-AMO-MAN-11, R11), built from ONE `GET /v1/dashboards/login-submit` answer whose reach is the
 * token's own zone. Every rate is computed here once from integers with the shared rules: strike = successful calls / target outlets,
 * geo = geo-validated / total calls (two decimals, as the rings print them), login and Bikroy Joma whole percents. Latin digits (the vendor prints Latin here).
 */
data class LiveData(
    val asOf: String, val categories: List<CategorySales>, val memoCount: Long, val netMtk: Long,
    val targetOutlets: Long, val successfulCalls: Long, val geoValid: Long?, val photoValid: Long?, val totalCalls: Long?,
    val targetRoutes: Long, val loggedInRoutes: Long, val submittedRoutes: Long,
) {
    val strikeBp: Long? get() = Percent.bp(successfulCalls, targetOutlets)
    val geoBp: Long? get() = if (geoValid == null || totalCalls == null) null else Percent.bp(geoValid, totalCalls)
    val loginPct: Long? get() = Percent.whole(loggedInRoutes, targetRoutes)
    val submitPct: Long? get() = Percent.whole(submittedRoutes, loggedInRoutes)

    fun strikeText(l: UiLocale = UiLocale.EN) = Percent.text(strikeBp, 2, l)
    fun geoText(l: UiLocale = UiLocale.EN) = Percent.text(geoBp, 2, l)
    fun loginText(l: UiLocale = UiLocale.EN) = Percent.text(loginPct, 0, l)
    fun submitText(l: UiLocale = UiLocale.EN) = Percent.text(submitPct, 0, l)

    /** "161.50": the day's net sales in taka with two decimals, no currency sign (screenshot 39 prints none). */
    fun totalTaka(): String = Money.formatTaka(netMtk, 2)

    /** The four category columns in screen order; a category the server did not send is 0. */
    fun columns(): List<CategorySales> = TeamTargets.categoryOrder.map { c -> categories.firstOrNull { it.code == c } ?: CategorySales(c, "", 0L) } +
        categories.filter { it.code !in TeamTargets.categoryOrder }
}

object LiveDataParser {
    private fun l(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull ?: 0L
    /** A nullable count (the server sends null for a non-AMO caller): null prints as a dash. */
    private fun n(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun routes(o: JsonObject?, k: String) = (o?.get(k) as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("route_id") as? JsonPrimitive)?.longOrNull }

    /** [routeId] null is every route of the zone; a route keeps only its own row of the route lists. Unreadable: null. */
    fun parse(json: String?, routeId: Long? = null): LiveData? {
        val o = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull() ?: return null
        val k = o["kpis"] as? JsonObject ?: return null
        fun pick(x: List<Long>) = if (routeId == null) x else x.filter { it == routeId }
        val notIn = pick(routes(o, "not_logged_in")); val inNot = pick(routes(o, "logged_in_not_submitted")); val done = pick(routes(o, "submitted"))
        val perRoute = routeId != null
        val cats = (o["sales_by_category"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            CategorySales((c["category_code"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null, (c["base_unit"] as? JsonPrimitive)?.contentOrNull.orEmpty(), l(c, "qty_base"), n(c, "memos"), (c["qty_entered"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull, (c["entry_unit"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull)
        }
        return LiveData(
            (o["as_of"] as? JsonPrimitive)?.contentOrNull.orEmpty(), cats, l(k, "active_memo_count"), l(k, "net_mtk"),
            l(k, "target_outlets"), l(k, "successful_calls"), n(o, "geo_valid_visits"), n(o, "photo_valid_visits"), n(o, "visits_total"),
            if (perRoute) (notIn.size + inNot.size + done.size).toLong() else l(k, "target_routes"),
            if (perRoute) (inNot.size + done.size).toLong() else l(k, "logged_in_routes"),
            if (perRoute) done.size.toLong() else l(k, "sales_submitted_routes"),
        )
    }
}
