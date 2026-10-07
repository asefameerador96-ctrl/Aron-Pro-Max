package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.masterdata.AdminSupport.bad
import com.aktcl.aron.backend.masterdata.AdminSupport.bindOwnerReach
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Contract `VisitPlan`; the route is the route of the plan's outlets (a plan is for one route). */
@Serializable
data class VisitPlanOutletDto(val outlet_id: Long, val status: String, val completed_at: String? = null)

@Serializable
data class VisitPlanDto(val plan_uuid: String, val planner_user_id: Long, val plan_date: String, val route_id: Long, val outlets: List<VisitPlanOutletDto>)

@Serializable
data class VisitPlanPageDto(val items: List<VisitPlanDto>, val next_cursor: String?)

/**
 * POST body: the plan record (`plan_uuid` is the client uuid, `plan_date`, `note`) plus the route and the outlets chosen on
 * the Set Plan screen (the `visit_plan_outlet` records of the sync path folded into one call). The planner is the token's user.
 */
@Serializable
data class VisitPlanIn(val plan_uuid: String, val plan_date: String, val route_id: Long, val outlet_ids: List<Long>, val note: String? = null)

class VisitPlanDeps(val db: Database, val reach: ReachResolver, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val PLAN_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

/**
 * F-API-020. `GET /v1/visit-plans` (contract listVisitPlans): a TSO reads own plans, supervisors the plans of TSOs whose zone is
 * in their server-side reach. `POST /v1/visit-plans` (backlog row; the contract carries plans as sync records `visit_plan`
 * and `visit_plan_outlet`): saves a union by outlet, idempotent by `plan_uuid`.
 */
fun Route.visitPlanRoutes(d: VisitPlanDeps) {
    authenticated(d.guard) {
        get("/visit-plans") { call.respond(listPlans(call, d)) }
        post("/visit-plans") {
            val (created, body) = savePlan(call, d)
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, body)
        }
    }
}

private fun listPlans(call: ApplicationCall, d: VisitPlanDeps): VisitPlanPageDto {
    val p = call.principal
    if (p.role !in PLAN_READERS) throw AdminSupport.forbidden("visit plans are not available to this role")
    val limit = AdminSupport.limit(call)
    val (from, to) = AdminSupport.window(call)
    val planner = AdminSupport.queryId(call, "planner_user_id")
    val cursor = AdminSupport.decodeCursor(call, 2)?.let { (a, b) -> (runCatching { LocalDate.parse(a) }.getOrNull() ?: bad("query.cursor")) to (b.toLongOrNull() ?: bad("query.cursor")) }
    val today = AdminSupport.today(d.clock)
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    return d.db.jdbi.withHandle<VisitPlanPageDto, Exception> { h ->
        if (planner != null) {
            if (p.role == Role.TSO) { if (planner != p.userId) throw AdminSupport.outOfScope() } else AdminSupport.ownerInReachOrThrow(reach, h, planner, today)
        }
        val where = mutableListOf("p.voided_at IS NULL", "EXISTS (SELECT 1 FROM app.visit_plan_outlet x WHERE x.plan_client_uuid = p.client_uuid AND x.voided_at IS NULL)")
        where += if (p.role == Role.TSO) "p.user_id = :me" else AdminSupport.ownerInReach(reach, "ow")
        if (planner != null) where += "p.user_id = :planner"
        if (from != null) where += "p.plan_date >= :from"
        if (to != null) where += "p.plan_date <= :to"
        if (cursor != null) where += "(p.plan_date, p.id) < (:c_date, :c_id)"
        val q = h.createQuery(
            "SELECT p.id, p.client_uuid, p.user_id, p.plan_date FROM app.visit_plan p JOIN app.app_user ow ON ow.id = p.user_id WHERE ${where.joinToString(" AND ")} ORDER BY p.plan_date DESC, p.id DESC LIMIT :lim",
        ).bindOwnerReach(reach, today).bind("lim", limit + 1)
        if (p.role == Role.TSO) q.bind("me", p.userId)
        if (planner != null) q.bind("planner", planner)
        if (from != null) q.bind("from", from)
        if (to != null) q.bind("to", to)
        if (cursor != null) q.bind("c_date", cursor.first).bind("c_id", cursor.second)
        data class Head(val id: Long, val uuid: UUID, val user: Long, val date: LocalDate)
        val heads = q.map { rs, _ -> Head(rs.getLong("id"), rs.getObject("client_uuid", UUID::class.java), rs.getLong("user_id"), rs.getObject("plan_date", LocalDate::class.java)) }.list()
        val page = heads.take(limit)
        val items = loadPlans(h, page.map { it.uuid }).let { byUuid -> page.mapNotNull { byUuid[it.uuid] } }
        VisitPlanPageDto(items, if (heads.size > limit) AdminSupport.encodeCursor(page.last().date, page.last().id) else null)
    }
}

/** Plans by uuid with outlets and completion (an outlet is completed when its Visit Query, a retailer_questionnaire assessment, was submitted). */
private fun loadPlans(h: Handle, uuids: List<UUID>): Map<UUID, VisitPlanDto> {
    if (uuids.isEmpty()) return emptyMap()
    data class Row(val plan: UUID, val outlet: Long, val route: Long?, val completed: OffsetDateTime?)
    val rows = h.createQuery(
        "SELECT po.plan_client_uuid, po.outlet_id, o.route_id, " +
            "(SELECT min(a.captured_at) FROM app.call_assessment a WHERE a.visit_plan_outlet_client_uuid = po.client_uuid AND a.kind = 'retailer_questionnaire' AND a.voided_at IS NULL) AS completed_at " +
            "FROM app.visit_plan_outlet po JOIN app.outlet o ON o.id = po.outlet_id WHERE po.plan_client_uuid = ANY(:u) AND po.voided_at IS NULL ORDER BY po.id",
    ).bindArray("u", UUID::class.java, uuids)
        .map { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getObject(3) as Long?, rs.getObject(4, OffsetDateTime::class.java)) }.list()
    val heads = h.createQuery("SELECT client_uuid, user_id, plan_date FROM app.visit_plan WHERE client_uuid = ANY(:u)").bindArray("u", UUID::class.java, uuids)
        .map { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getObject(3, LocalDate::class.java)) }.list()
    val byPlan = rows.groupBy { it.plan }
    return heads.mapNotNull { (uuid, user, date) ->
        val outlets = byPlan[uuid] ?: return@mapNotNull null
        val route = outlets.mapNotNull { it.route }.minOrNull() ?: return@mapNotNull null
        uuid to VisitPlanDto(
            uuid.toString(), user, date.toString(), route,
            outlets.map { VisitPlanOutletDto(it.outlet, if (it.completed != null) "completed" else "pending", it.completed?.toInstant()?.wire()) },
        )
    }.toMap()
}

private suspend fun savePlan(call: ApplicationCall, d: VisitPlanDeps): Pair<Boolean, VisitPlanDto> {
    val p = call.principal
    if (p.role != Role.TSO) throw AdminSupport.forbidden("only a TSO sets a visit plan")
    val req = call.receiveStrict(VisitPlanIn.serializer())
    val planUuid = AdminSupport.uuid(req.plan_uuid, "body.plan_uuid")
    val planDate = AdminSupport.date(req.plan_date, "body.plan_date")
    if (req.route_id < 1) bad("body.route_id")
    if (req.outlet_ids.isEmpty() || req.outlet_ids.size > 500) bad("body.outlet_ids", "out_of_range")
    if (req.outlet_ids.any { it < 1 }) bad("body.outlet_ids")
    if (req.note != null && req.note.length > 500) bad("body.note", "length")
    val outletIds = req.outlet_ids.distinct()
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    val now = AdminSupport.utc(d.clock)
    val today = AdminSupport.today(d.clock)
    val cfgVersion = d.config.configVersion()
    return d.db.jdbi.inTransaction<Pair<Boolean, VisitPlanDto>, Exception> { h ->
        // One writer per planner and date: concurrent saves of the same union serialise instead of racing.
        h.createQuery("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").bind("k", "visit_plan:${p.userId}:$planDate").mapToMap().list()
        data class OutletRow(val id: Long, val route: Long?, val zone: Long)
        val outlets = h.createQuery("SELECT o.id, o.route_id, o.zone_id FROM app.outlet o WHERE o.id = ANY(:ids)").bindArray("ids", Long::class.javaObjectType, outletIds)
            .map { rs, _ -> OutletRow(rs.getLong(1), rs.getObject(2) as Long?, rs.getLong(3)) }.list()
        // An unknown outlet and one outside the caller's reach are the same answer (no existence leak).
        if (outlets.size != outletIds.size || outlets.any { !(reach.national || it.zone in reach.zoneIds || (it.route != null && it.route in reach.routeIds)) }) throw AdminSupport.outOfScope("outlet outside your reach")
        if (outlets.any { it.route != req.route_id }) bad("body.outlet_ids", "not_on_route")

        val existing = h.createQuery("SELECT user_id, plan_date FROM app.visit_plan WHERE client_uuid = :u").bind("u", planUuid)
            .map { rs, _ -> rs.getLong(1) to rs.getObject(2, LocalDate::class.java) }.findOne().orElse(null)
        val target: UUID = when {
            existing != null -> {
                if (existing.first != p.userId || existing.second != planDate) throw ApiProblem(ProblemCode.ERR_CONFLICT, "plan_uuid already stores a different plan")
                planUuid
            }
            else -> h.createQuery(
                "SELECT p.client_uuid FROM app.visit_plan p WHERE p.user_id = :u AND p.plan_date = :d AND p.voided_at IS NULL AND EXISTS " +
                    "(SELECT 1 FROM app.visit_plan_outlet po JOIN app.outlet o ON o.id = po.outlet_id WHERE po.plan_client_uuid = p.client_uuid AND o.route_id = :r) ORDER BY p.id LIMIT 1",
            ).bind("u", p.userId).bind("d", planDate).bind("r", req.route_id).mapTo(UUID::class.java).findOne().orElse(null)
        } ?: planUuid
        var changed = 0
        if (existing == null && target == planUuid) {
            changed += h.createUpdate(
                "INSERT INTO app.visit_plan (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, plan_date, note) VALUES (:u, :u, :bd, :uid, :at, :cv, :pd, :note) ON CONFLICT (client_uuid) DO NOTHING",
            ).bind("u", planUuid).bind("bd", today).bind("uid", p.userId).bind("at", now).bind("cv", cfgVersion).bind("pd", planDate).bind("note", req.note).execute()
        }
        for (oid in outletIds) {
            // The outlet row's client uuid is derived from (plan, outlet): the same outlet saved twice, or by two plan uuids, is one row.
            val rowUuid = UUID.nameUUIDFromBytes("visit_plan_outlet:$target:$oid".toByteArray())
            changed += h.createUpdate(
                "INSERT INTO app.visit_plan_outlet (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, plan_client_uuid, outlet_id) " +
                    "SELECT :u, :u, :bd, :uid, :at, :cv, :plan, :oid WHERE NOT EXISTS (SELECT 1 FROM app.visit_plan_outlet x WHERE x.plan_client_uuid = :plan AND x.outlet_id = :oid AND x.voided_at IS NULL) ON CONFLICT (client_uuid) DO NOTHING",
            ).bind("u", rowUuid).bind("bd", today).bind("uid", p.userId).bind("at", now).bind("cv", cfgVersion).bind("plan", target).bind("oid", oid).execute()
        }
        val dto = loadPlans(h, listOf(target))[target] ?: throw ApiProblem(ProblemCode.ERR_INTERNAL, "plan not readable after save")
        (changed > 0) to dto
    }
}
