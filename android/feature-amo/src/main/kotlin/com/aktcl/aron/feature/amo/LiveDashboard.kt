package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** The zone's live figures for one business date (F-AMO-020); a null percentage is "no basis yet" and shows as a dash. */
data class LiveFigures(
    val asOf: String, val grossMtk: Long, val netMtk: Long, val targetOutlets: Long, val visitedOutlets: Long, val successfulCalls: Long, val strikeRatePct: Double?,
    val geoValidPct: Double?, val forceSalePct: Double?, val mockVisits: Long, val suspiciousVisits: Long,
    val targetRoutes: Long, val loggedInRoutes: Long, val loginPct: Double?, val submittedRoutes: Long, val submitPct: Double?,
    val notLoggedIn: List<Long>, val loggedInNotSubmitted: List<Long>, val submitted: List<Long>,
)

object LiveDashboard {
    private fun l(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.longOrNull ?: 0L
    private fun d(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.contentOrNull != null && it !is kotlinx.serialization.json.JsonNull }?.doubleOrNull
    private fun routes(o: JsonObject?, k: String) = (o?.get(k) as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("route_id") as? JsonPrimitive)?.longOrNull }

    /**
     * Combines `GET /v1/dashboards/summary` and `/login-submit`. With [routeId] the login and submit lists (and their counts) are the
     * chosen route's; the sales, call and geo figures come from the zone node (the dashboard has no route level) and the screen says so.
     */
    fun parse(summaryJson: String?, loginJson: String?, routeId: Long? = null): LiveFigures? {
        val s = runCatching { Json.parseToJsonElement(summaryJson ?: return null) as? JsonObject }.getOrNull() ?: return null
        val k = s["kpis"] as? JsonObject ?: return null
        val ls = runCatching { Json.parseToJsonElement(loginJson ?: return@runCatching null) as? JsonObject }.getOrNull()
        fun pick(list: List<Long>) = if (routeId == null) list else list.filter { it == routeId }
        val notIn = pick(routes(ls, "not_logged_in")); val inNot = pick(routes(ls, "logged_in_not_submitted")); val done = pick(routes(ls, "submitted"))
        val lk = (ls?.get("kpis") as? JsonObject)?.takeIf { "logged_in_routes" in it } ?: k
        val filtered = routeId != null && ls != null
        return LiveFigures(
            (s["as_of"] as? JsonPrimitive)?.contentOrNull.orEmpty(), l(k, "gross_mtk"), l(k, "net_mtk"), l(k, "target_outlets"), l(k, "visited_outlets"), l(k, "successful_calls"),
            d(k, "strike_rate_pct"), d(k, "geo_valid_pct"), d(k, "force_sale_pct"), l(k, "mock_visits"), l(k, "suspicious_visits"),
            if (filtered) (notIn.size + inNot.size + done.size).toLong() else l(lk, "target_routes"), if (filtered) (inNot.size + done.size).toLong() else l(lk, "logged_in_routes"),
            if (filtered) null else d(lk, "login_pct"), if (filtered) done.size.toLong() else l(lk, "sales_submitted_routes"), if (filtered) null else d(lk, "submit_pct_of_logged_in"),
            notIn, inNot, done,
        )
    }

    /** "1.23" (two decimals, dot) or null for a dash; the screen turns digits into Bangla digits. */
    fun pct(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.2f", it) }

    /** Strike rate as the dashboard defines it: successful calls over visited outlets, two decimals (1 of 81 is 1.23). Null while nothing is visited. */
    fun strikeRate(successful: Long, visited: Long): Double? = if (visited <= 0) null else successful * 100.0 / visited
}
