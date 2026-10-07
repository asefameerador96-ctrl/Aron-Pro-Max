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
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Contract `Feedback`. */
@Serializable
data class FeedbackDto(
    val feedback_uuid: String, val user_id: Long, val category_code: String, val title: String, val description: String, val photo_uuid: String?, val created_at: String, val status: String,
)

@Serializable
data class FeedbackPageDto(val items: List<FeedbackDto>, val next_cursor: String?)

/** POST body: the `feedback` record payload plus its client uuid. The author is the token's user. */
@Serializable
data class FeedbackIn(val feedback_uuid: String, val category_code: String, val title: String, val description: String, val photo_uuid: String? = null)

/** Contract `FeedbackStatusWrite`. */
@Serializable
data class FeedbackStatusIn(val status: String, val reason: String)

class FeedbackDeps(val db: Database, val reach: ReachResolver, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val FEEDBACK_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
private val FEEDBACK_TRIAGE = setOf(Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
private val FEEDBACK_STATUS = setOf("new", "in_progress", "resolved", "closed")

/**
 * F-API-032. `GET /v1/feedback` (listFeedback; the contract path of the "GET /admin/feedback" row) and
 * `PATCH /v1/feedback/{feedback_uuid}` (updateFeedbackStatus, audited with a reason); `POST /v1/feedback` (backlog row;
 * the contract carries feedback as the sync record `feedback`) stores one item, idempotent by `feedback_uuid`.
 */
fun Route.feedbackRoutes(d: FeedbackDeps) {
    authenticated(d.guard) {
        get("/feedback") { call.respond(listFeedback(call, d)) }
        post("/feedback") {
            val (created, body) = submit(call, d)
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, body)
        }
        patch("/feedback/{feedback_uuid}") { call.respond(setStatus(call, d)) }
    }
}

private const val FB_COLS = "f.client_uuid, f.user_id, f.category_code, f.title, f.description, f.photo_uuid, f.created_at, COALESCE(s.status, 'new') AS status"
private const val FB_FROM = "app.feedback f LEFT JOIN app.feedback_status s ON s.feedback_client_uuid = f.client_uuid"

private fun mapFeedback(rs: ResultSet) = FeedbackDto(
    rs.getObject("client_uuid", UUID::class.java).toString(), rs.getLong("user_id"), rs.getString("category_code"), rs.getString("title"), rs.getString("description"),
    rs.getObject("photo_uuid", UUID::class.java)?.toString(), rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(), rs.getString("status"),
)

private fun listFeedback(call: ApplicationCall, d: FeedbackDeps): FeedbackPageDto {
    val p = call.principal
    if (p.role !in FEEDBACK_READERS) throw AdminSupport.forbidden("feedback is not available to this role")
    val limit = AdminSupport.limit(call)
    val (from, to) = AdminSupport.window(call)
    val cursor = AdminSupport.decodeCursor(call, 1)?.let { (a) -> a.toLongOrNull()?.takeIf { it >= 1 } ?: bad("query.cursor") }
    val today = AdminSupport.today(d.clock)
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    return d.db.jdbi.withHandle<FeedbackPageDto, Exception> { h ->
        val where = mutableListOf("f.voided_at IS NULL")
        where += if (p.role == Role.TSO) "f.user_id = :me" else AdminSupport.ownerInReach(reach, "ow")
        if (from != null) where += "f.business_date >= :from"
        if (to != null) where += "f.business_date <= :to"
        if (cursor != null) where += "f.id < :c"
        val q = h.createQuery("SELECT f.id, $FB_COLS FROM $FB_FROM JOIN app.app_user ow ON ow.id = f.user_id WHERE ${where.joinToString(" AND ")} ORDER BY f.id DESC LIMIT :lim")
            .bindOwnerReach(reach, today).bind("lim", limit + 1)
        if (p.role == Role.TSO) q.bind("me", p.userId)
        if (from != null) q.bind("from", from)
        if (to != null) q.bind("to", to)
        if (cursor != null) q.bind("c", cursor)
        val rows = q.map { rs, _ -> mapFeedback(rs) to rs.getLong("id") }.list()
        val page = rows.take(limit)
        FeedbackPageDto(page.map { it.first }, if (rows.size > limit) AdminSupport.encodeCursor(page.last().second) else null)
    }
}

private fun readOne(h: Handle, id: UUID): FeedbackDto? =
    h.createQuery("SELECT f.id, $FB_COLS FROM $FB_FROM WHERE f.client_uuid = :u AND f.voided_at IS NULL").bind("u", id).map { rs, _ -> mapFeedback(rs) }.findOne().orElse(null)

private suspend fun submit(call: ApplicationCall, d: FeedbackDeps): Pair<Boolean, FeedbackDto> {
    val p = call.principal
    if (p.role != Role.TSO) throw AdminSupport.forbidden("only a TSO sends feedback")
    val req = call.receiveStrict(FeedbackIn.serializer())
    val id = AdminSupport.uuid(req.feedback_uuid, "body.feedback_uuid")
    if (!AdminSupport.CODE.matches(req.category_code)) bad("body.category_code")
    val title = req.title.trim(); val desc = req.description.trim()
    if (title.isEmpty() || req.title.length > 120) bad("body.title", "length")
    if (desc.isEmpty() || req.description.length > 2000) bad("body.description", "length")
    val photo = req.photo_uuid?.let { AdminSupport.uuid(it, "body.photo_uuid") }
    val now = AdminSupport.utc(d.clock)
    val today = AdminSupport.today(d.clock)
    val cfgVersion = d.config.configVersion()
    return d.db.jdbi.inTransaction<Pair<Boolean, FeedbackDto>, Exception> { h ->
        val known = h.createQuery("SELECT count(*) FROM app.code_list_item WHERE list_key = 'feedback_category'").mapTo(Int::class.java).one()
        if (known > 0 && h.createQuery("SELECT count(*) FROM app.code_list_item WHERE list_key = 'feedback_category' AND code = :c AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)")
                .bind("c", req.category_code).bind("d", today).mapTo(Int::class.java).one() == 0
        ) bad("body.category_code", "unknown_code")
        val inserted = h.createUpdate(
            "INSERT INTO app.feedback (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, category_code, title, description, photo_uuid) VALUES (:u, :u, :bd, :uid, :at, :cv, :c, :t, :d, CAST(:ph AS uuid)) ON CONFLICT (client_uuid) DO NOTHING",
        ).bind("u", id).bind("bd", today).bind("uid", p.userId).bind("at", now).bind("cv", cfgVersion).bind("c", req.category_code).bind("t", title).bind("d", desc).bind("ph", photo).execute()
        val row = readOne(h, id) ?: throw ApiProblem(ProblemCode.ERR_CONFLICT, "feedback_uuid is not available")
        if (row.user_id != p.userId || row.category_code != req.category_code || row.title != title || row.description != desc || row.photo_uuid != photo?.toString()) {
            throw ApiProblem(ProblemCode.ERR_CONFLICT, "feedback_uuid already stores different feedback")
        }
        (inserted > 0) to row
    }
}

private suspend fun setStatus(call: ApplicationCall, d: FeedbackDeps): FeedbackDto {
    val p = call.principal
    if (p.role !in FEEDBACK_TRIAGE) throw AdminSupport.forbidden("not allowed to triage feedback")
    val id = AdminSupport.uuid(call.parameters["feedback_uuid"].orEmpty(), "path.feedback_uuid")
    val req = call.receiveStrict(FeedbackStatusIn.serializer())
    if (req.status !in FEEDBACK_STATUS) bad("body.status")
    val reason = AdminSupport.reason(req.reason, "body.reason")
    val reach = AdminSupport.reach(d.reach, call, d.clock)
    val today = AdminSupport.today(d.clock)
    val now = AdminSupport.utc(d.clock)
    return d.db.jdbi.inTransaction<FeedbackDto, Exception> { h ->
        val before = readOne(h, id) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "feedback not found")
        AdminSupport.ownerInReachOrThrow(reach, h, before.user_id, today)
        if (before.status == req.status) return@inTransaction before // replay: nothing changes, no second audit row
        h.createUpdate(
            "INSERT INTO app.feedback_status (feedback_client_uuid, status, updated_at, updated_by) VALUES (:u, :s, :at, :by) ON CONFLICT (feedback_client_uuid) DO UPDATE SET status = :s, updated_at = :at, updated_by = :by",
        ).bind("u", id).bind("s", req.status).bind("at", now).bind("by", p.userId).execute()
        AuditWriter.write(h, p, "feedback", id.toString(), "set_status", buildJsonObject { put("status", before.status) }, buildJsonObject { put("status", req.status) }, reason, call.requestId)
        readOne(h, id)!!
    }
}
