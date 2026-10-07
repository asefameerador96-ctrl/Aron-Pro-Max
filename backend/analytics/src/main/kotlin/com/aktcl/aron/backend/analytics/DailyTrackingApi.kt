package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

@Serializable
data class DailyTrackingRow(
    val route_id: Long, val route_name: String, val zone_id: Long, val user_id: Long? = null, val user_name: String? = null, val state: String,
    val target_outlets: Int, val visited_outlets: Int, val successful_calls: Int, val active_memo_count: Int, val net_mtk: Long,
    val by_category: List<CategoryVolume> = emptyList(), val tilldate_target_achievement_pct: Double? = null, val bucket: String,
)

@Serializable
data class DailyTrackingPage(val as_of: String, val business_date: String, val items: List<DailyTrackingRow>, val next_cursor: String? = null)

@Serializable
data class TrackingActionRequest(val action_uuid: String, val route_id: Long, val business_date: String, val note: String)

@Serializable
data class TrackingAction(val action_uuid: String, val route_id: Long, val business_date: String, val note: String, val created_by_user_id: Long, val created_at: String, val notified_user_ids: List<Long>)

/**
 * Daily Tracking (F-API-015). The achievement behind the buckets is the call productivity of the route-day: successful calls divided by
 * target outlets (docs/24 s12.4 strike rate), because the target-quantity achievement belongs to the deferred target programme (docs/27);
 * `tilldate_target_achievement_pct` therefore stays null. Buckets: >= 100, 90 to < 100, 80 to < 90, < 80, exception (an approved day exception
 * excuses the route), not logged in (planned, state not_started).
 */
class DailyTrackingService(private val db: Database, private val config: ServerConfig, private val clock: AronClock = AronClock.SYSTEM) {

    fun page(reach: com.aktcl.aron.backend.platform.Reach, date: LocalDate, level: String?, nodeId: Long?, limit: Int, afterRoute: Long?): DailyTrackingPage {
        val (items, next) = db.readJdbi.withHandle<Pair<List<DailyTrackingRow>, Long?>, Exception> { h ->
            val node = resolveScopedNode(h, reach, level, nodeId)
            val rows = node.bind(
                h.createQuery(
                    """
                    SELECT a.route_id, g.route_name, a.zone_id, u.id AS uid, u.full_name, a.day_state, a.target_outlets, a.visited_outlets, a.successful_calls, a.active_memo_count, a.net_mtk,
                           a.exception_approved
                      FROM dw.agg_daily_route a JOIN dw.dim_geo g ON g.route_id = a.route_id
                      LEFT JOIN app.route_day rd ON rd.route_id = a.route_id AND rd.business_date = a.business_date
                      LEFT JOIN app.app_user u ON u.id = coalesce(rd.acting_user_id, rd.assigned_user_id)
                     WHERE a.business_date = :d AND (a.planned OR a.exception_approved) AND ${node.clause("a.zone_id")} AND (CAST(:after AS bigint) IS NULL OR a.route_id > :after)
                     ORDER BY a.route_id LIMIT :lim
                    """,
                ).bind("d", date).bind("after", afterRoute).bind("lim", limit + 1),
            ).map { rs, _ ->
                val target = rs.getInt("target_outlets"); val calls = rs.getInt("successful_calls"); val state = rs.getString("day_state")
                val achievement = if (target > 0) BigDecimal(calls).multiply(BigDecimal(100)).divide(BigDecimal(target), 2, RoundingMode.HALF_UP) else null
                DailyTrackingRow(
                    rs.getLong("route_id"), rs.getString("route_name"), rs.getLong("zone_id"), rs.getObject("uid") as Long?, rs.getString("full_name"), state, target,
                    rs.getInt("visited_outlets"), calls, rs.getInt("active_memo_count"), rs.getLong("net_mtk"),
                    bucket = bucket(rs.getBoolean("exception_approved"), state, achievement),
                )
            }.list()
            val page = rows.take(limit)
            val cats = if (page.isEmpty()) emptyMap() else h.createQuery(
                """
                SELECT a.route_id, p.category_code, p.base_unit, sum(a.sold_qty_base + a.free_qty_base) qty, sum(a.gross_mtk) gross
                  FROM dw.agg_daily_route_sku a JOIN dw.dim_product p ON p.sku_id = a.sku_id
                 WHERE a.business_date = :d AND a.route_id = ANY(:rids) GROUP BY 1, 2, 3 ORDER BY 1, 2
                """,
            ).bind("d", date).bindArray("rids", Long::class.javaObjectType, page.map { it.route_id })
                .map { rs, _ -> rs.getLong(1) to CategoryVolume(rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)) }.list().groupBy({ it.first }, { it.second })
            page.map { it.copy(by_category = cats[it.route_id].orEmpty()) } to (if (rows.size > limit) page.last().route_id else null)
        }
        return DailyTrackingPage(clock.now().wire(), date.toString(), items, next?.let { Base64.getUrlEncoder().withoutPadding().encodeToString("r|$it".toByteArray()) })
    }

    private fun bucket(exception: Boolean, state: String, pct: BigDecimal?): String = when {
        exception -> "exception"
        state == "not_started" -> "not_logged_in"
        pct == null -> "below_80"
        pct >= BigDecimal(100) -> "ge_100"
        pct >= BigDecimal(90) -> "from_90"
        pct >= BigDecimal(80) -> "from_80"
        else -> "below_80"
    }

    /** "Take action" note (idempotent by `action_uuid`): allowed from cfg.day.take_action_after (Dhaka) of the business date. */
    fun createAction(p: com.aktcl.aron.backend.platform.AronPrincipal, reach: com.aktcl.aron.backend.platform.Reach, req: TrackingActionRequest, requestId: String?): TrackingAction {
        val id = runCatching { UUID.fromString(req.action_uuid) }.getOrNull()?.takeIf { it.toString() == req.action_uuid }
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad action_uuid", errors = listOf(FieldError("/action_uuid", "invalid_value")))
        val date = runCatching { LocalDate.parse(req.business_date) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad business_date", errors = listOf(FieldError("/business_date", "invalid_value")))
        if (req.note.length !in 3..500) throw ApiProblem(ProblemCode.ERR_VALIDATION, "note must be 3..500 characters", errors = listOf(FieldError("/note", "out_of_range")))
        return db.jdbi.inTransaction<TrackingAction, Exception> { h ->
            // A route outside the caller's reach, or unknown, is 403 (no existence leak).
            val geo = h.createQuery("SELECT zone_id, territory_id FROM dw.dim_geo WHERE route_id = :r").bind("r", req.route_id).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.findOne().orElse(null)
            if (geo == null || (!reach.national && geo.first !in reach.zoneIds)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "route is outside your reach")
            // Replay: the same uuid by the same user returns the stored action unchanged; a different body under it is a conflict.
            h.createQuery("SELECT actor_user_id, after, at FROM app.audit_log WHERE entity = 'tracking_action' AND entity_id = :id ORDER BY id LIMIT 1").bind("id", req.action_uuid)
                .map { rs, _ -> Triple(rs.getLong(1), kotlinx.serialization.json.Json.parseToJsonElement(rs.getString(2)).let { it as kotlinx.serialization.json.JsonObject }, rs.getObject(3, OffsetDateTime::class.java).toInstant()) }
                .findOne().orElse(null)?.let { (by, after, at) ->
                    fun s(k: String) = (after[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (by != p.userId || s("route_id") != req.route_id.toString() || s("business_date") != req.business_date || s("note") != req.note) throw ApiProblem(ProblemCode.ERR_CONFLICT, "action_uuid already used for a different action")
                    return@inTransaction TrackingAction(req.action_uuid, req.route_id, req.business_date, req.note, by, at.wire(), (after["notified_user_ids"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content.toLong() })
                }
            val after = runCatching { LocalTime.parse(config.string("cfg.day.take_action_after")) }.getOrDefault(LocalTime.of(17, 0))
            val opensAt = date.atTime(after).atZone(ZoneId.of("Asia/Dhaka")).toInstant()
            if (clock.now().isBefore(opensAt)) throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "take action opens at $after Dhaka time on the business date")
            // The route's TSO and AMO: active users of those roles whose scope covers the route's zone or territory.
            val notified = h.createQuery(
                """
                SELECT DISTINCT s.user_id FROM app.user_scope s JOIN app.app_user u ON u.id = s.user_id
                 WHERE u.role IN ('TSO', 'AMO') AND u.status = 'active' AND s.valid_from <= :d AND (s.valid_to IS NULL OR s.valid_to >= :d)
                   AND ((s.node_type = 'zone' AND s.node_id = :z) OR (s.node_type = 'territory' AND s.node_id = :t)) ORDER BY 1 LIMIT 10
                """,
            ).bind("d", date).bind("z", geo.first).bind("t", geo.second).mapTo(Long::class.java).list()
            val body = buildJsonObject {
                put("route_id", req.route_id); put("business_date", req.business_date); put("note", req.note)
                put("notified_user_ids", buildJsonArray { notified.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
            // The stored row's own timestamp is the action's created_at, so a replay answers with exactly the same value.
            val now = h.createQuery(
                "INSERT INTO app.audit_log (actor_user_id, actor_username, actor_role, via, entity, entity_id, action, after, request_id) VALUES (:u, :n, :r, 'api', 'tracking_action', :id, 'create', CAST(:a AS jsonb), CAST(:rid AS uuid)) RETURNING at",
            ).bind("u", p.userId).bind("n", p.username.take(40)).bind("r", p.role.wire).bind("id", req.action_uuid).bind("a", body.toString())
                .bind("rid", requestId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }).map { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant() }.one()
            // The nudge to the route's TSO and AMO is the notify module's job: it reads this outbox event.
            h.createUpdate(
                "INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, payload) VALUES ('tracking_action.created', 'tracking_action', :id, :d, CAST(:p AS jsonb))",
            ).bind("id", req.action_uuid).bind("d", date).bind("p", body.toString()).execute()
            TrackingAction(req.action_uuid, req.route_id, req.business_date, req.note, p.userId, now.wire(), notified)
        }
    }
}

class DailyTrackingDeps(val service: DailyTrackingService, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val ACTION_ROLES = setOf(Role.TSO, Role.DMO, Role.WM, Role.ADMIN, Role.SUPERADMIN)

fun Route.dailyTrackingRoutes(d: DailyTrackingDeps) {
    authenticated(d.guard) {
        get("/dashboards/daily-tracking") {
            val p = call.principal
            if (p.role !in DASHBOARD_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "dashboards are not available to this role")
            val q = call.request.queryParameters
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            val date = q["business_date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad business_date", errors = listOf(FieldError("query.business_date", "invalid_value"))) }
                ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "business_date is required", errors = listOf(FieldError("query.business_date", "required")))
            val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad limit", errors = listOf(FieldError("query.limit", "out_of_range"))) } ?: 100
            val nodeId = q["node_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad node_id", errors = listOf(FieldError("query.node_id", "invalid_value"))) }
            val after = q["cursor"]?.let { c ->
                runCatching { String(Base64.getUrlDecoder().decode(c)).removePrefix("r|").toLong().also { require(String(Base64.getUrlDecoder().decode(c)).startsWith("r|")) } }.getOrNull()
                    ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad cursor", errors = listOf(FieldError("query.cursor", "invalid_value")))
            }
            call.respond(d.service.page(d.reach.reach(p.userId, p.role, p.scopeVersion, today), date, q["level"], nodeId, limit, after))
        }
        post("/dashboards/daily-tracking/actions") {
            val p = call.principal
            if (p.role !in ACTION_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "taking action is for TSO and above")
            val req = call.receiveStrict(TrackingActionRequest.serializer())
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            call.respond(HttpStatusCode.Created, d.service.createAction(p, d.reach.reach(p.userId, p.role, p.scopeVersion, today), req, call.requestId))
        }
    }
}
