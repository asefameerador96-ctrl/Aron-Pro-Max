package com.aktcl.aron.feature.tso.mycall

import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** The SR assigned to a route today (`Route.assignees`, role SR); null = the route shows "SR Not Set". */
data class RouteSr(val userId: Long, val fullName: String)

data class PlanRoute(val id: Long, val code: String, val name: String, val zoneId: Long, val label: String?, val sr: RouteSr? = null)

/** [channel]: the outlet's channel code (GT, DCC, Astha, RCC, MT, HoReCa); the 5-line card of screenshot 36 shows it (D-TSO-MAN-35). */
data class PlanOutlet(val id: Long, val code: String, val name: String, val nameBn: String?, val owner: String, val cluster: String?, val phone: String?, val channel: String? = null) {
    fun title(bangla: Boolean) = if (bangla && !nameBn.isNullOrBlank()) nameBn else name
}

enum class PlanStatus { PENDING, COMPLETED }

data class PlannedOutlet(val outletId: Long, val status: PlanStatus, val completedAt: String? = null)

/** A visit plan as `GET /v1/visit-plans` returns it (contract `VisitPlan`), plus the local overlay (unsent plan, local completion). */
data class VisitPlanItem(val planUuid: String, val planDate: String, val routeId: Long, val outlets: List<PlannedOutlet>, val sent: Boolean = true)

/** One Set Plan save: the union is by outlet; only outlets not yet in the plan produce new records. */
data class PlanSave(val date: String, val routeId: Long, val outletIds: Set<Long>)

/**
 * Deterministic ids make Set Plan idempotent and a union (F-TSO-013): the same planner, date and route always map to the same
 * plan uuid, and a plan plus an outlet always to the same plan-outlet uuid, so a second save sends records the server already
 * has (ignored by uuid) and only the new outlets count.
 */
object PlanIds {
    fun plan(plannerUserId: Long, date: String, routeId: Long): String = v3("aron.tso.plan:$plannerUserId:$date:$routeId")
    fun planOutlet(planUuid: String, outletId: Long): String = v3("aron.tso.plan-outlet:$planUuid:$outletId")
    private fun v3(name: String) = com.aktcl.aron.feature.tso.StableIds.v4(name)
}

enum class PlanError { NO_ROUTE, NO_OUTLETS, DATE_FORMAT, DATE_PAST, DATE_FAR }

/** `cfg.tso.plan_max_days_ahead` when the bundle has none (registry default 60, bounds 1..90). */
const val MAX_PLAN_AHEAD_DAYS = 60L

/** The days-ahead window of Set Plan and Assign Task from the config value; outside the registry bounds 1..90 the default. */
fun planDaysAhead(cfg: Int?): Long = cfg?.takeIf { it in 1..90 }?.toLong() ?: MAX_PLAN_AHEAD_DAYS

fun validatePlan(date: String, routeId: Long?, selected: Set<Long>, today: String, maxAheadDays: Long = MAX_PLAN_AHEAD_DAYS): List<PlanError> {
    val e = mutableListOf<PlanError>()
    if (routeId == null) e += PlanError.NO_ROUTE
    if (selected.isEmpty()) e += PlanError.NO_OUTLETS
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    if (d == null) e += PlanError.DATE_FORMAT else {
        val t = LocalDate.parse(today)
        if (d.isBefore(t)) e += PlanError.DATE_PAST
        if (d.isAfter(t.plusDays(maxAheadDays))) e += PlanError.DATE_FAR
    }
    return e
}

/** The zones of the TSO's routes in id order; a zone name is not in the contract's reads, so the label is the id (request filed). */
fun zonesOf(routes: List<PlanRoute>): List<Long> = routes.map { it.zoneId }.distinct().sorted()

/** Tabs of My Visit Plan: pending first-to-do order; a locally completed outlet counts as completed at once (offline). */
fun splitPlan(plan: VisitPlanItem?, locallyDone: Set<Long>): Pair<List<Long>, List<Long>> {
    val all = plan?.outlets.orEmpty()
    val done = all.filter { it.status == PlanStatus.COMPLETED || it.outletId in locallyDone }.map { it.outletId }.distinct()
    val pending = all.map { it.outletId }.distinct().filter { it !in done }
    return pending to done
}

object PlanJson {
    private fun str(o: JSONObject, k: String): String { require(!o.isNull(k)) { "$k null" }; return o.getString(k) }

    fun parseRoutes(body: String): List<PlanRoute>? = runCatching {
        val a = JSONObject(body).getJSONArray("items")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            require(str(o, "status") == "active"); PlanRoute(o.getLong("id"), str(o, "code"), str(o, "name"), o.getLong("zone_id"), if (o.isNull("display_label")) null else o.optString("display_label"), srOf(o)) } }.getOrNull() }
    }.getOrNull()

    private fun srOf(o: JSONObject): RouteSr? = runCatching {
        val a = o.optJSONArray("assignees") ?: return null
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("role") == "SR" }?.let { RouteSr(it.getLong("user_id"), it.getString("full_name")) }
    }.getOrNull()

    fun parseOutlets(body: String): List<PlanOutlet>? = runCatching {
        val a = JSONObject(body).getJSONArray("items")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            PlanOutlet(o.getLong("id"), str(o, "code"), str(o, "name"), if (o.isNull("name_bn")) null else o.optString("name_bn").ifBlank { null }, str(o, "owner_name"),
                if (o.has("cluster_name") && !o.isNull("cluster_name")) o.optString("cluster_name") else null,
                if (o.isNull("contact_number")) null else o.optString("contact_number").ifBlank { null },
                if (!o.has("channel") || o.isNull("channel")) null else o.optString("channel").ifBlank { null }) } }.getOrNull() }
    }.getOrNull()

    fun nextCursor(body: String): String? = runCatching { JSONObject(body).let { if (it.isNull("next_cursor")) null else it.optString("next_cursor").ifEmpty { null } } }.getOrNull()

    fun parsePlans(body: String): List<VisitPlanItem>? = runCatching {
        val a = JSONObject(body).getJSONArray("items")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            val outs = o.getJSONArray("outlets")
            VisitPlanItem(str(o, "plan_uuid"), str(o, "plan_date"), o.getLong("route_id"), (0 until outs.length()).map { j ->
                val x = outs.getJSONObject(j)
                PlannedOutlet(x.getLong("outlet_id"), if (str(x, "status") == "completed") PlanStatus.COMPLETED else PlanStatus.PENDING, if (x.isNull("completed_at")) null else x.optString("completed_at"))
            })
        } }.getOrNull() }
    }.getOrNull()

    fun routesToCache(r: List<PlanRoute>): String = JSONObject().put("items", JSONArray(r.map {
        JSONObject().put("id", it.id).put("code", it.code).put("name", it.name).put("zone_id", it.zoneId).put("status", "active").put("display_label", it.label ?: JSONObject.NULL)
            .put("assignees", JSONArray(listOfNotNull(it.sr?.let { sr -> JSONObject().put("user_id", sr.userId).put("full_name", sr.fullName).put("role", "SR").put("username", "x") }))) })).toString()

    fun outletsToCache(o: List<PlanOutlet>): String = JSONObject().put("items", JSONArray(o.map {
        JSONObject().put("id", it.id).put("code", it.code).put("name", it.name).put("name_bn", it.nameBn ?: JSONObject.NULL).put("owner_name", it.owner)
            .put("cluster_name", it.cluster ?: JSONObject.NULL).put("contact_number", it.phone ?: JSONObject.NULL).put("channel", it.channel ?: JSONObject.NULL) })).toString()

    fun plansToCache(p: List<VisitPlanItem>): String = JSONObject().put("items", JSONArray(p.filter { it.sent }.map { pl ->
        JSONObject().put("plan_uuid", pl.planUuid).put("plan_date", pl.planDate).put("route_id", pl.routeId).put("outlets", JSONArray(pl.outlets.map {
            JSONObject().put("outlet_id", it.outletId).put("status", it.status.name.lowercase()).put("completed_at", it.completedAt ?: JSONObject.NULL) })) })).toString()
}
