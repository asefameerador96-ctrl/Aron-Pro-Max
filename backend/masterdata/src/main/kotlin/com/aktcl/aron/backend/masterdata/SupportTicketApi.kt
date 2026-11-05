package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.SupportTicketEvent
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

class SupportTicketDeps(
    val db: Database, val config: ServerConfig, val nudger: Nudger, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM,
) {
    val service = SupportTicketService(db, config, nudger, clock)
}

/**
 * The ticket routes of contract 1.10.0 (D-TSO-MAN-18 to 26): the reporter's reads (its own tickets) and the support
 * desk's reads and writes (permission `support.ticket.manage`, `Me.support_desk`). Tickets and the reporter's comments
 * and close reviews arrive only through the sync records (SupportTicketHandler); there is no online create.
 *
 * - `GET /v1/support/tickets` (listSupportTickets): newest first (`created_at`, then id), filters q, status,
 *   issue_type_code, priority, from and to (business date), and the desk-only requester_user_id and assignee_user_id.
 * - `GET /v1/support/tickets/summary` (getSupportTicketSummary): counts by status (R-F-14: no percentages).
 * - `GET /v1/support/tickets/{ticket_uuid}` (getSupportTicket): the ticket, its thread and photos; 404 for a ticket that
 *   is not the caller's (unless desk).
 * - `PATCH /v1/support/tickets/{ticket_uuid}` (updateSupportTicket): desk only; 403, then the body (400), then
 *   [SupportTicketService.update] (404, op replay, version, codes, transitions).
 * - `POST /v1/support/tickets/{ticket_uuid}/events` (addSupportTicketEvent): desk reply, 201 new or 200 replay.
 */
fun Route.supportTicketRoutes(d: SupportTicketDeps) {
    authenticated(d.guard) {
        get("/support/tickets") {
            val f = filter(call)
            call.respond(withContext(Dispatchers.IO) { d.service.list(call.principal, f) })
        }
        get("/support/tickets/summary") {
            call.respond(withContext(Dispatchers.IO) { d.service.summary(call.principal) })
        }
        get("/support/tickets/{ticket_uuid}") {
            val t = ticketUuid(call)
            call.respond(withContext(Dispatchers.IO) { d.service.detail(call.principal, t) })
        }
        patch("/support/tickets/{ticket_uuid}") {
            val p = call.principal
            if (!SupportTickets.isDesk(p)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "ticket changes are for the support desk")
            val t = ticketUuid(call)
            val u = parseUpdate(call.receiveStrict(JsonObject.serializer()))
            val rid = call.requestId
            call.respond(withContext(Dispatchers.IO) { d.service.update(p, t, u, rid) })
        }
        post("/support/tickets/{ticket_uuid}/events") {
            val p = call.principal
            if (!SupportTickets.isDesk(p)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "replies are for the support desk; the reporter comments from the app")
            val t = ticketUuid(call)
            val body = call.receiveStrict(JsonObject.serializer())
            body.keys.firstOrNull { it !in setOf("event_uuid", "body") }?.let { bad("/$it", "unknown_member") }
            val ev = (body["event_uuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { AdminSupport.UUID_V4.matches(it) }
                ?.let(UUID::fromString) ?: bad("/event_uuid", if (body["event_uuid"] == null) "required" else "invalid_value")
            val text = (body["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("/body", if (body["body"] == null) "required" else "invalid_type")
            if (text.isBlank() || text.length > MAX_TEXT) bad("/body", "length")
            val rid = call.requestId
            val (created, event) = withContext(Dispatchers.IO) { d.service.reply(p, t, ev, text, rid) }
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, ResponseJson.encodeToJsonElement(SupportTicketEvent.serializer(), event))
        }
    }
}

private const val MAX_TEXT = 2000
private val CODE = Regex("^[a-z][a-z0-9_]{1,40}$")
private val UPDATE_MEMBERS = setOf("op_uuid", "expected_version", "status", "priority", "assignee_user_id", "resolution_code", "resolution_notes", "cancel_reason")
private val DESK_STATUSES = setOf("in_progress", "resolved", "closed", "cancelled")

private fun bad(pointer: String, code: String = "invalid_value"): Nothing =
    throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

private fun ticketUuid(call: ApplicationCall): UUID =
    call.parameters["ticket_uuid"]?.lowercase()?.takeIf { AdminSupport.UUID_V4.matches(it) }?.let(UUID::fromString) ?: bad("path.ticket_uuid")

private fun filter(call: ApplicationCall): TicketFilter {
    val q = call.request.queryParameters
    val text = q["q"]?.let { s -> s.takeIf { it.length in 2..40 && '\u0000' !in it } ?: bad("query.q", "length") }
    val status = q["status"]?.also { if (it !in SupportTickets.STATUSES) bad("query.status") }
    val issue = q["issue_type_code"]?.also { if (!CODE.matches(it)) bad("query.issue_type_code") }
    val priority = q["priority"]?.also { if (it !in SupportTickets.PRIORITIES) bad("query.priority") }
    val (from, to) = AdminSupport.window(call)
    val cursor = AdminSupport.decodeCursor(call, 2)?.let { (m, id) ->
        val micros = m.toLongOrNull() ?: bad("query.cursor")
        val rowId = id.toLongOrNull()?.takeIf { it >= 1 } ?: bad("query.cursor")
        SupportTicketService.fromMicros(micros) to rowId
    }
    return TicketFilter(
        q = text, status = status, issueTypeCode = issue, priority = priority,
        requesterUserId = AdminSupport.queryId(call, "requester_user_id"), assigneeUserId = AdminSupport.queryId(call, "assignee_user_id"),
        from = from, to = to, limit = AdminSupport.limit(call, default = 50, max = 200), after = cursor,
    )
}

/**
 * The contract's SupportTicketUpdate, checked member by member (unknown member, type, bound, at least one change, and
 * the members a resolve or a cancel needs and nothing else), then fingerprinted as canonical JSON (R-F-03).
 */
internal fun parseUpdate(b: JsonObject): TicketUpdate {
    b.keys.firstOrNull { it !in UPDATE_MEMBERS }?.let { bad("/$it", "unknown_member") }
    fun str(k: String): String? = when (val v = b[k]) {
        null -> null
        is JsonPrimitive -> if (v.isString) v.content else bad("/$k", "invalid_type")
        else -> bad("/$k", "invalid_type")
    }
    fun int(k: String): Long? = when (val v = b[k]) {
        null -> null
        is JsonNull -> bad("/$k", "invalid_type")
        is JsonPrimitive -> if (!v.isString && Regex("^-?[0-9]{1,18}$").matches(v.content)) v.content.toLong() else bad("/$k", "invalid_type")
        else -> bad("/$k", "invalid_type")
    }
    val op = str("op_uuid")?.takeIf { AdminSupport.UUID_V4.matches(it) }?.let(UUID::fromString) ?: bad("/op_uuid", if (b["op_uuid"] == null) "required" else "invalid_value")
    val version = int("expected_version") ?: bad("/expected_version", "required")
    if (version !in 1..Int.MAX_VALUE.toLong()) bad("/expected_version", "out_of_range")
    val status = str("status")?.also { if (it !in DESK_STATUSES) bad("/status") }
    val priority = str("priority")?.also { if (it !in SupportTickets.PRIORITIES) bad("/priority") }
    val hasAssignee = "assignee_user_id" in b
    val assignee = if (b["assignee_user_id"] is JsonNull) null else int("assignee_user_id")?.also { if (it < 1) bad("/assignee_user_id", "out_of_range") }
    val code = str("resolution_code")?.also { if (!CODE.matches(it)) bad("/resolution_code") }
    val notes = str("resolution_notes")?.also { if (it.isBlank() || it.length > MAX_TEXT) bad("/resolution_notes", "length") }
    val reason = str("cancel_reason")?.also { if (it.isBlank() || it.trim().length < 3 || it.length > 500) bad("/cancel_reason", "length") }
    if (status == null && priority == null && !hasAssignee && code == null && notes == null && reason == null) bad("/status", "required")
    if (status == "resolved") {
        if (code == null) bad("/resolution_code", "required")
        if (notes == null) bad("/resolution_notes", "required")
    } else {
        if (code != null) bad("/resolution_code", "not_allowed")
        if (notes != null) bad("/resolution_notes", "not_allowed")
    }
    if (status == "cancelled") { if (reason == null) bad("/cancel_reason", "required") } else if (reason != null) bad("/cancel_reason", "not_allowed")
    if ('\u0000' in (notes ?: "") || '\u0000' in (reason ?: "")) bad("/resolution_notes", "invalid_character")
    return TicketUpdate(
        opUuid = op, expectedVersion = version.toInt(), status = status, priority = priority, hasAssignee = hasAssignee, assignee = assignee,
        resolutionCode = code, resolutionNotes = notes, cancelReason = reason, sha256 = SupportTickets.sha256(SupportTickets.canonical(b)),
    )
}
