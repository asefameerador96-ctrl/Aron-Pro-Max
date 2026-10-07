package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
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
import com.aktcl.aron.backend.platform.requestId
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Contract `LeaveApplication`. */
@Serializable
data class LeaveDto(
    val leave_uuid: String, val user_id: Long, val leave_type_code: String, val from_date: String, val days: Int, val to_date: String, val reason: String,
    val status: String, val decided_by_user_id: Long? = null, val decided_at: String? = null,
)

@Serializable
data class LeavePageDto(val items: List<LeaveDto>, val next_cursor: String?)

/** POST body: the `leave_application` record payload plus its client uuid. The applicant is the token's user. */
@Serializable
data class LeaveIn(val leave_uuid: String, val leave_type_code: String, val from_date: String, val days: Int, val reason: String)

/** Contract `DecisionRequest`. */
@Serializable
data class LeaveDecisionIn(val decision: String, val note: String? = null)

class LeaveDeps(val db: Database, val reach: ReachResolver, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val LEAVE_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.ADMIN, Role.SUPERADMIN)
/** Matrix of docs/24 s8.5: leave decisions are A for DMO, WM, ADMIN and SUPERADMIN (the DMO is the TSO's manager, D24-55). */
private val LEAVE_DECIDERS = setOf(Role.DMO, Role.WM, Role.ADMIN, Role.SUPERADMIN)

/**
 * F-API-022. `GET /v1/leave` (listLeave) and `POST /v1/leave/{leave_uuid}/decision` (decideLeave) per contract;
 * `POST /v1/leave` (backlog row; the contract carries applications as sync records `leave_application`) applies,
 * idempotent by `leave_uuid`.
 */
fun Route.leaveRoutes(d: LeaveDeps) {
    authenticated(d.guard) {
        get("/leave") { call.respond(listLeave(call, d)) }
        post("/leave") {
            val (created, body) = applyLeave(call, d)
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, body)
        }
        post("/leave/{leave_uuid}/decision") { call.respond(decide(call, d)) }
    }
}

private const val COLS = "l.client_uuid, l.user_id, l.leave_type_code, l.from_date, l.days, l.to_date, l.reason, l.status, l.decided_by, l.decided_at"

private fun mapLeave(rs: ResultSet) = LeaveDto(
    rs.getObject("client_uuid", UUID::class.java).toString(), rs.getLong("user_id"), rs.getString("leave_type_code"), rs.getObject("from_date", LocalDate::class.java).toString(),
    rs.getInt("days"), rs.getObject("to_date", LocalDate::class.java).toString(), rs.getString("reason"), rs.getString("status"),
    rs.getObject("decided_by") as Long?, rs.getObject("decided_at", OffsetDateTime::class.java)?.toInstant()?.wire(),
)

private fun listLeave(call: ApplicationCall, d: LeaveDeps): LeavePageDto {
    val p = call.principal
    if (p.role !in LEAVE_READERS) throw AdminSupport.forbidden("leave is not available to this role")
    val limit = AdminSupport.limit(call)
    val status = call.request.queryParameters["status"]?.also { if (it !in setOf("pending", "approved", "rejected")) bad("query.status") }
    val (from, to) = AdminSupport.window(call)
    val cursor = AdminSupport.decodeCursor(call, 2)?.let { (a, b) -> AdminSupport.date(a, "query.cursor") to (b.toLongOrNull() ?: bad("query.cursor")) }
    val today = AdminSupport.today(d.clock)
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    return d.db.jdbi.withHandle<LeavePageDto, Exception> { h ->
        val where = mutableListOf("l.voided_at IS NULL")
        where += if (p.role == Role.TSO) "l.user_id = :me" else AdminSupport.ownerInReach(reach, "ow")
        if (status != null) where += "l.status = :status"
        if (from != null) where += "l.to_date >= :from"
        if (to != null) where += "l.from_date <= :to"
        if (cursor != null) where += "(l.from_date, l.id) < (:c_date, :c_id)"
        val q = h.createQuery("SELECT l.id, $COLS FROM app.leave_application l JOIN app.app_user ow ON ow.id = l.user_id WHERE ${where.joinToString(" AND ")} ORDER BY l.from_date DESC, l.id DESC LIMIT :lim")
            .bindOwnerReach(reach, today).bind("lim", limit + 1)
        if (p.role == Role.TSO) q.bind("me", p.userId)
        if (status != null) q.bind("status", status)
        if (from != null) q.bind("from", from)
        if (to != null) q.bind("to", to)
        if (cursor != null) q.bind("c_date", cursor.first).bind("c_id", cursor.second)
        val rows = q.map { rs, _ -> Triple(mapLeave(rs), rs.getLong("id"), rs.getObject("from_date", LocalDate::class.java)) }.list()
        val page = rows.take(limit)
        LeavePageDto(page.map { it.first }, if (rows.size > limit) AdminSupport.encodeCursor(page.last().third, page.last().second) else null)
    }
}

private fun codeKnown(h: Handle, listKey: String, code: String, on: LocalDate): Boolean {
    val total = h.createQuery("SELECT count(*) FROM app.code_list_item WHERE list_key = :k").bind("k", listKey).mapTo(Int::class.java).one()
    if (total == 0) return true // list not seeded yet: the pattern check is all there is (reported as a gap)
    return h.createQuery("SELECT count(*) FROM app.code_list_item WHERE list_key = :k AND code = :c AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)")
        .bind("k", listKey).bind("c", code).bind("d", on).mapTo(Int::class.java).one() > 0
}

private suspend fun applyLeave(call: ApplicationCall, d: LeaveDeps): Pair<Boolean, LeaveDto> {
    val p = call.principal
    if (p.role != Role.TSO) throw AdminSupport.forbidden("only a TSO applies for leave")
    val req = call.receiveStrict(LeaveIn.serializer())
    val id = AdminSupport.uuid(req.leave_uuid, "body.leave_uuid")
    if (!AdminSupport.CODE.matches(req.leave_type_code)) bad("body.leave_type_code")
    val from = AdminSupport.date(req.from_date, "body.from_date")
    if (req.days !in 1..365) bad("body.days", "out_of_range")
    AdminSupport.noNul("body.reason", req.reason, req.leave_type_code)
    val reason = req.reason.trim()
    if (reason.isEmpty() || req.reason.length > 500) bad("body.reason", "length")
    val now = AdminSupport.utc(d.clock)
    val cfgVersion = d.config.configVersion()
    val today = AdminSupport.today(d.clock)
    return d.db.jdbi.inTransaction<Pair<Boolean, LeaveDto>, Exception> { h ->
        if (!codeKnown(h, "leave_type", req.leave_type_code, from)) bad("body.leave_type_code", "unknown_code")
        // A TSO cannot hold two pending or approved applications over the same days (a replay of the same uuid is not an overlap).
        val overlap = h.createQuery("SELECT count(*) FROM app.leave_application l WHERE l.user_id = :uid AND l.client_uuid <> :u AND l.voided_at IS NULL AND l.status IN ('pending','approved') AND l.from_date <= :to AND l.to_date >= :f")
            .bind("uid", p.userId).bind("u", id).bind("f", from).bind("to", from.plusDays(req.days - 1L)).mapTo(Int::class.java).one()
        if (overlap > 0) throw ApiProblem(ProblemCode.ERR_CONFLICT, "you already have leave over these days", errors = listOf(com.aktcl.aron.backend.platform.FieldError("body.from_date", "overlap")))
        val inserted = h.createUpdate(
            "INSERT INTO app.leave_application (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, leave_type_code, from_date, days, reason) " +
                "VALUES (:u, :u, :bd, :uid, :at, :cv, :t, :f, :days, :r) ON CONFLICT (client_uuid) DO NOTHING",
        ).bind("u", id).bind("bd", today).bind("uid", p.userId).bind("at", now).bind("cv", cfgVersion).bind("t", req.leave_type_code).bind("f", from).bind("days", req.days).bind("r", reason).execute()
        val row = h.createQuery("SELECT l.id, $COLS FROM app.leave_application l WHERE l.client_uuid = :u").bind("u", id).map { rs, _ -> mapLeave(rs) }.one()
        // The same uuid with different content, or another user's uuid, is a conflict, never a silent overwrite.
        if (row.user_id != p.userId || row.leave_type_code != req.leave_type_code || row.from_date != from.toString() || row.days != req.days || row.reason != reason) {
            throw ApiProblem(ProblemCode.ERR_CONFLICT, "leave_uuid already stores a different application")
        }
        (inserted > 0) to row
    }
}

private suspend fun decide(call: ApplicationCall, d: LeaveDeps): LeaveDto {
    val p = call.principal
    if (p.role !in LEAVE_DECIDERS) throw AdminSupport.forbidden("not allowed to decide leave")
    val id = AdminSupport.uuid(call.parameters["leave_uuid"].orEmpty(), "path.leave_uuid")
    val req = call.receiveStrict(LeaveDecisionIn.serializer())
    if (req.decision !in setOf("approve", "reject")) bad("body.decision")
    if (req.note != null && req.note.length > 500) bad("body.note", "length")
    val wanted = if (req.decision == "approve") "approved" else "rejected"
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    val today = AdminSupport.today(d.clock)
    val now = AdminSupport.utc(d.clock)
    return d.db.jdbi.inTransaction<LeaveDto, Exception> { h ->
        val row = h.createQuery("SELECT l.id, $COLS FROM app.leave_application l WHERE l.client_uuid = :u AND l.voided_at IS NULL FOR UPDATE OF l").bind("u", id).map { rs, _ -> mapLeave(rs) }.findOne().orElse(null)
            ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "leave application not found")
        // Out of reach and unknown are told apart only after the reach check: a caller outside the reach learns nothing.
        try { AdminSupport.ownerInReachOrThrow(reach, h, row.user_id, today) } catch (e: ApiProblem) { if (e.code == ProblemCode.ERR_OUT_OF_SCOPE) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "leave application not found") else throw e }
        val applicantRole = h.createQuery("SELECT role FROM app.app_user WHERE id = :u").bind("u", row.user_id).mapTo(String::class.java).one()
        if (applicantRole != "TSO") throw ApiProblem(ProblemCode.ERR_VALIDATION, "only TSO leave is decided here", errors = listOf(com.aktcl.aron.backend.platform.FieldError("path.leave_uuid", "not_a_tso_leave")))
        if (row.user_id == p.userId) throw AdminSupport.forbidden("a user does not decide own leave")
        if (row.status == wanted) return@inTransaction row // replay of the same decision changes nothing
        if (row.status != "pending") throw ApiProblem(ProblemCode.ERR_CONFLICT, "leave is already ${row.status}")
        h.createUpdate("UPDATE app.leave_application SET status = :s, decided_by = :by, decided_at = :at, decision_note = :n WHERE client_uuid = :u AND status = 'pending'")
            .bind("s", wanted).bind("by", p.userId).bind("at", now).bind("n", req.note?.trim()?.ifEmpty { null }).bind("u", id).execute()
        AuditWriter.write(h, p, "leave_application", id.toString(), "decide", buildJsonObject { put("status", row.status) }, buildJsonObject { put("status", wanted); put("applicant_user_id", row.user_id) }, req.note?.trim()?.ifEmpty { null }, call.requestId)
        h.createQuery("SELECT l.id, $COLS FROM app.leave_application l WHERE l.client_uuid = :u").bind("u", id).map { rs, _ -> mapLeave(rs) }.one()
    }
}
