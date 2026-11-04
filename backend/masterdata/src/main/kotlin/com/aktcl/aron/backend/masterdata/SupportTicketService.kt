package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Inbox
import com.aktcl.aron.backend.platform.InboxItem
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.contract.SupportTicket
import com.aktcl.aron.contract.SupportTicketEvent
import com.aktcl.aron.contract.SupportTicketRequester
import com.aktcl.aron.contract.SupportTicketSummary
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Rules shared by the ticket record handler (sync) and the support desk (REST): D-TSO-MAN-17 to 26, lead rulings R-F-03,
 * R-F-05, R-F-08, R-F-14. Config reads here go to the database on the caller's handle (not the 30 s config cache), so a
 * change of the requester roles or the push switch holds for the next record or desk action.
 */
object SupportTickets {
    /** D-TSO-MAN-22: the support desk (seeded for ADMIN and SUPERADMIN in `app.role_grant_map`; SUPPORT can be granted it). */
    const val DESK_PERMISSION = "support.ticket.manage"
    const val REQUESTER_ROLES_KEY = "cfg.support.ticket_requester_roles"
    const val PUSH_KEY = "cfg.notify.ticket_push_enabled"

    val STATUSES = listOf("new", "in_progress", "resolved", "closed", "cancelled")
    val OPEN = setOf("new", "in_progress")
    val PRIORITIES = listOf("p1", "p2", "p3", "p4")
    val PRIORITY_LABELS = mapOf("p1" to "P1 - Highest", "p2" to "P2 - High", "p3" to "P3 - Medium", "p4" to "P4 - Low")

    /** The roles the requester-roles list may name (V0134 bounds: a subset of SR, AMO, TSO; the reader enforces it). */
    private val REQUESTER_ITEMS = setOf("SR", "AMO", "TSO")
    private val DEFAULT_REQUESTER_ROLES = setOf("TSO")

    /** "Support" for every desk actor and the job (D-TSO-MAN-21): a support user is never shown by name. */
    const val SUPPORT_DISPLAY = "Support"

    fun isDesk(p: AronPrincipal): Boolean = DESK_PERMISSION in p.permissions

    /**
     * The global value of [key] at [at] (the API clock, as the config resolver compares), read on [h]: the newest
     * effective global row, else the registry default; null when the key is unknown or retired.
     */
    fun cfg(h: Handle, key: String, at: Instant): JsonElement? = h.createQuery(
        """
        SELECT COALESCE((SELECT v.value FROM app.cfg_value v
                          WHERE v.key = k.key AND v.scope_type = 'global' AND v.effective_from <= :at AND (v.effective_to IS NULL OR v.effective_to > :at)
                          ORDER BY v.effective_from DESC, v.config_version DESC, v.id DESC LIMIT 1), k.default_value)::text
          FROM app.cfg_key k WHERE k.key = :k AND k.retired_at IS NULL
        """.trimIndent(),
    ).bind("k", key).bind("at", utc(at)).mapTo(String::class.java).findOne().orElse(null)
        ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() }

    /** `cfg.support.ticket_requester_roles` (D-TSO-MAN-17): only SR, AMO and TSO count; unreadable means the default ["TSO"]. */
    fun requesterRoles(h: Handle, at: Instant): Set<String> =
        (cfg(h, REQUESTER_ROLES_KEY, at) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.filter { it in REQUESTER_ITEMS }?.toSet()
            ?: DEFAULT_REQUESTER_ROLES

    fun roleAllowed(h: Handle, role: Role, at: Instant): Boolean = role.wire in requesterRoles(h, at)

    /** `cfg.notify.ticket_push_enabled`; an unreadable switch means off (as PushNotifier treats its switches). */
    fun pushEnabled(h: Handle, at: Instant): Boolean = (cfg(h, PUSH_KEY, at) as? JsonPrimitive)?.booleanOrNull ?: false

    /**
     * The name-based, version-4-shaped UUID of the contract (updateSupportTicket; the TSO app's StableIds.v4): the first 16
     * bytes of SHA-256 of the UTF-8 name, version nibble 4, variant bits 10.
     */
    fun stableV4(name: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    /** The UUID of the event a desk op writes for [kind] after its first one (contract: `aron.support.ticket-op:<op_uuid>:<kind>`). */
    fun opEventUuid(opUuid: String, kind: String): String = stableV4("aron.support.ticket-op:$opUuid:$kind")

    /** The `created` event a stored ticket gets (one per ticket; the name keeps a retried afterStored from writing a second). */
    fun createdEventUuid(ticketUuid: String): String = stableV4("aron.support.ticket-created:$ticketUuid")

    /** The `status_change` (new to in_progress) the first desk reply on a New ticket writes before the reply itself. */
    fun replyStartUuid(eventUuid: String): String = stableV4("aron.support.ticket-reply:$eventUuid:status_change")

    /**
     * RFC 8785 canonical JSON of a desk request body (R-F-03: the op fingerprint compares the body as a JSON value, member
     * order and spacing ignored). The bodies hold strings, integers, booleans and null only (the API refuses anything
     * else before hashing); integers print as plain decimals.
     */
    fun canonical(e: JsonElement): String = StringBuilder().also { canon(e, it) }.toString()

    private fun canon(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                e.keys.sorted().forEachIndexed { i, k -> if (i > 0) out.append(','); quote(k, out); out.append(':'); canon(e.getValue(k), out) }
                out.append('}')
            }
            is JsonArray -> { out.append('['); e.forEachIndexed { i, v -> if (i > 0) out.append(','); canon(v, out) }; out.append(']') }
            is JsonPrimitive -> when {
                e.isString -> quote(e.content, out)
                else -> out.append(e.content.toLongOrNull()?.toString() ?: e.content)
            }
        }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\""); '\\' -> out.append("\\\\"); '\b' -> out.append("\\b"); '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n"); '\r' -> out.append("\\r"); '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u").append(String.format("%04x", c.code)) else out.append(c)
        }
        out.append('"')
    }

    fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    /** D-TSO-MAN-20 (screenshot 14): the support message a resolve writes; at most 62 + 22 + 120 + 2000 = 2204 characters (R-F-02). */
    fun resolutionMessage(ticketNo: String, label: String, notes: String): String =
        "[TicketID: $ticketNo] - Resolved:\nResolution Code: $label\nResolution Notes:\n\n$notes"

    fun utc(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)

    fun today(clock: AronClock): LocalDate = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
}

/**
 * The bell texts of the desk's ticket updates (D-TSO-MAN-26), Bangla and English (constraint 8). The ticket number
 * is the only variable part; the reporter opens the ticket for the details.
 */
internal object SupportTicketTexts {
    data class Text(val titleEn: String, val titleBn: String, val bodyEn: String, val bodyBn: String)

    fun of(change: String, no: String, priority: String? = null): Text = when (change) {
        "in_progress" -> Text("Ticket $no is in progress", "টিকিট $no-এর কাজ চলছে", "Support has started work on ticket $no.", "সাপোর্ট টিকিট $no-এর কাজ শুরু করেছে।")
        "resolved" -> Text("Ticket $no is resolved", "টিকিট $no সমাধান হয়েছে", "Support resolved ticket $no. Please review it and close it.", "সাপোর্ট টিকিট $no সমাধান করেছে। দেখে টিকিটটি বন্ধ করুন।")
        "cancelled" -> Text("Ticket $no was cancelled", "টিকিট $no বাতিল হয়েছে", "Support cancelled ticket $no. Open it to see the reason.", "সাপোর্ট টিকিট $no বাতিল করেছে। কারণ দেখতে টিকিটটি খুলুন।")
        "closed" -> Text("Ticket $no is closed", "টিকিট $no বন্ধ হয়েছে", "Support closed ticket $no.", "সাপোর্ট টিকিট $no বন্ধ করেছে।")
        "priority" -> SupportTickets.PRIORITY_LABELS.getValue(priority!!).let { l ->
            Text("Ticket $no priority changed", "টিকিট $no-এর অগ্রাধিকার বদলেছে", "The priority of ticket $no is now $l.", "টিকিট $no-এর অগ্রাধিকার এখন $l।")
        }
        "assign" -> Text("Ticket $no was assigned", "টিকিট $no দায়িত্ব দেওয়া হয়েছে", "Ticket $no was given to a support agent.", "টিকিট $no একজন সাপোর্ট কর্মীকে দেওয়া হয়েছে।")
        "reply" -> Text("Support replied on ticket $no", "টিকিট $no-এ সাপোর্টের উত্তর", "Support replied on ticket $no. Open it to read the reply.", "সাপোর্ট টিকিট $no-এ উত্তর দিয়েছে। উত্তর পড়তে টিকিটটি খুলুন।")
        else -> error("no text for $change")
    }
}

/** List filters of `GET /v1/support/tickets` (parsed and checked by the API). */
data class TicketFilter(
    val q: String? = null, val status: String? = null, val issueTypeCode: String? = null, val priority: String? = null,
    val requesterUserId: Long? = null, val assigneeUserId: Long? = null, val from: LocalDate? = null, val to: LocalDate? = null,
    val limit: Int = 50, val after: Pair<OffsetDateTime, Long>? = null,
)

/** One desk action (contract SupportTicketUpdate), parsed and shape-checked by the API; [sha256] is the body's fingerprint. */
data class TicketUpdate(
    val opUuid: UUID, val expectedVersion: Int, val status: String?, val priority: String?, val hasAssignee: Boolean, val assignee: Long?,
    val resolutionCode: String?, val resolutionNotes: String?, val cancelReason: String?, val sha256: ByteArray,
)

/**
 * The ticket reads and the support desk's writes (D-TSO-MAN-18 to 26; contract 1.10.0 listSupportTickets,
 * getSupportTicketSummary, getSupportTicket, updateSupportTicket, addSupportTicketEvent).
 *
 * Scope is the token's: a caller without [SupportTickets.DESK_PERMISSION] reads only the tickets it raised (requester =
 * token user; another reporter's ticket answers 404, never 403, so its existence does not leak); the desk reads and works
 * every ticket. Tickets are created only by the sync record `support_ticket` (SupportTicketHandler), so there is one
 * ingest path. A desk write takes the state row's lock first, so a desk op, a desk reply and a reporter's synced comment
 * or close review on the same ticket run one after the other.
 */
class SupportTicketService(
    private val db: Database,
    private val config: ServerConfig,
    private val nudger: Nudger = Nudger.NONE,
    private val clock: AronClock = AronClock.SYSTEM,
) {
    private val log = LoggerFactory.getLogger("aron.tickets")

    // ---- reads ---------------------------------------------------------------------------------------------------

    fun list(p: AronPrincipal, f: TicketFilter): JsonObject {
        val desk = SupportTickets.isDesk(p)
        if (!desk && (f.requesterUserId != null || f.assigneeUserId != null)) {
            throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "requester_user_id and assignee_user_id are support desk filters")
        }
        return db.jdbi.withHandle<JsonObject, Exception> { h ->
            val where = mutableListOf<String>()
            val binds = LinkedHashMap<String, Any>()
            if (!desk) { where += "s.requester_user_id = :me"; binds["me"] = p.userId }
            f.q?.let { q ->
                where += "(t.ticket_no LIKE :prefix ESCAPE '!' OR t.title ILIKE :text ESCAPE '!' OR it.label_en ILIKE :text ESCAPE '!')"
                binds["prefix"] = like(q.uppercase()) + "%"; binds["text"] = "%" + like(q) + "%"
            }
            f.status?.let { where += "s.status = :status"; binds["status"] = it }
            f.issueTypeCode?.let { where += "t.issue_type_code = :issue"; binds["issue"] = it }
            f.priority?.let { where += "s.priority = :priority"; binds["priority"] = it }
            f.requesterUserId?.let { where += "s.requester_user_id = :requester"; binds["requester"] = it }
            f.assigneeUserId?.let { where += "s.assignee_user_id = :assignee"; binds["assignee"] = it }
            f.from?.let { where += "t.business_date >= :from"; binds["from"] = it }
            f.to?.let { where += "t.business_date <= :to"; binds["to"] = it }
            f.after?.let { (at, id) -> where += "(t.created_at, t.id) < (:after_at, :after_id)"; binds["after_at"] = at; binds["after_id"] = id }
            val q = h.createQuery("$TICKET_SELECT ${where.joinToString("") { " AND $it" }} ORDER BY t.created_at DESC, t.id DESC LIMIT :lim").bind("lim", f.limit + 1)
            binds.forEach { (k, v) -> q.bind(k, v) }
            val rows = q.map { rs, _ -> row(rs) }.list()
            val page = rows.take(f.limit)
            val next = if (rows.size > f.limit) page.last().let { AdminSupport.encodeCursor(micros(it.createdAtRaw), it.id) } else null
            buildJsonObject {
                put("items", JsonArray(page.map { wire(it, desk) }))
                put("next_cursor", next?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
    }

    /** D-TSO-MAN-25: counts only (R-F-14); the caller's own tickets, every ticket for the desk. */
    fun summary(p: AronPrincipal): SupportTicketSummary = db.jdbi.withHandle<SupportTicketSummary, Exception> { h ->
        val desk = SupportTickets.isDesk(p)
        val q = h.createQuery(
            "SELECT s.status, count(*) FROM app.support_ticket t JOIN app.support_ticket_state s ON s.ticket_uuid = t.client_uuid " +
                "WHERE t.voided_at IS NULL${if (desk) "" else " AND s.requester_user_id = :me"} GROUP BY s.status",
        )
        if (!desk) q.bind("me", p.userId)
        val by = q.map { rs, _ -> rs.getString(1) to rs.getInt(2) }.list().toMap()
        fun n(s: String) = by[s] ?: 0
        SupportTicketSummary(
            asOf = clock.now().wire(), total = by.values.sum(), new = n("new"), inProgress = n("in_progress"), resolved = n("resolved"),
            closed = n("closed"), cancelled = n("cancelled"), open = n("new") + n("in_progress"),
        )
    }

    /** The ticket, its thread (oldest first) and its own photos; 404 when it is not the caller's and the caller is not the desk. */
    fun detail(p: AronPrincipal, ticket: UUID): JsonObject = db.jdbi.withHandle<JsonObject, Exception> { h ->
        val desk = SupportTickets.isDesk(p)
        val t = readTicket(h, ticket)?.takeIf { desk || it.requesterId == p.userId } ?: throw notFound()
        buildJsonObject {
            put("ticket", wire(t, desk))
            put("events", JsonArray(events(h, ticket).map { ResponseJson.encodeToJsonElement(SupportTicketEvent.serializer(), it) }))
            put("attachments", JsonArray(photos(h, "support_ticket", ticket).map { JsonPrimitive(it) }))
        }
    }

    // ---- desk writes -----------------------------------------------------------------------------------------------

    /** Outcome of a desk write, with the after-commit nudge it owes. */
    private data class Written<T>(val value: T, val nudgeUser: Long? = null)

    /**
     * `PATCH /v1/support/tickets/{ticket_uuid}` (D-TSO-MAN-20, R-F-03). The API has checked the desk permission (403) and
     * the body's shape (400). Here, in one transaction on the locked state row: the ticket (404), the op replay (a stored
     * op with the same fingerprint answers the ticket as it is now and writes nothing; another fingerprint, another
     * ticket, or a uuid used by any other event is 409 ERR_CONFLICT), `expected_version` (409 ERR_CONFLICT), the codes
     * and the assignee (400), then the transition matrix (409 ERR_TICKET_TRANSITION_INVALID). An op without a change
     * answers the ticket and records nothing. Otherwise one event per change (status or resolution, then priority, then
     * assignment; the first with `client_uuid` = op_uuid and the op's fingerprint), the state row (version + 1), the
     * domain event of a status change, one audit row and one inbox row for the reporter commit together; the push nudge
     * follows the commit when `cfg.notify.ticket_push_enabled` is on.
     */
    fun update(p: AronPrincipal, ticket: UUID, u: TicketUpdate, requestId: String?): JsonObject {
        val now = clock.now()
        val today = SupportTickets.today(clock)
        val cv = config.configVersion()
        val out = db.jdbi.inTransaction<Written<JsonObject>, Exception> { h ->
            val st = lockState(h, ticket) ?: throw notFound()
            val op = u.opUuid.toString()
            // R-F-03 replay first: before the version and the transition rules, so a retry whose answer was lost replays.
            h.createQuery("SELECT ticket_uuid::text, op_sha256 FROM app.support_ticket_event WHERE client_uuid = :op").bind("op", u.opUuid)
                .map { rs, _ -> rs.getString(1) to rs.getBytes(2) }.findOne().orElse(null)?.let { (tk, sha) ->
                    if (tk != ticket.toString() || sha == null || !sha.contentEquals(u.sha256)) {
                        throw ApiProblem(ProblemCode.ERR_CONFLICT, "op_uuid $op is already used by another request or event")
                    }
                    return@inTransaction Written(wire(readTicket(h, ticket)!!, true))
                }
            if (u.expectedVersion != st.version) throw ApiProblem(ProblemCode.ERR_CONFLICT, "the ticket changed since you read it (version ${st.version}); reload and apply again")
            val resolutionLabel = u.resolutionCode?.let { code ->
                h.createQuery(
                    "SELECT label_en FROM app.code_list_item WHERE list_key = 'ticket_resolution_code' AND code = :c AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)",
                ).bind("c", code).bind("d", today).mapTo(String::class.java).findOne().orElse(null) ?: throw bad("/resolution_code", "unknown_code")
            }
            val assigneeChange = u.hasAssignee && u.assignee != st.assignee
            if (assigneeChange && u.assignee != null) checkAssignee(h, u.assignee)
            u.status?.let { to -> if (!allowed(st.status, to)) throw ApiProblem(ProblemCode.ERR_TICKET_TRANSITION_INVALID, "a ticket cannot move from ${st.status} to $to") }
            val priorityChange = u.priority != null && u.priority != st.priority
            if (u.status == null && !priorityChange && !assigneeChange) return@inTransaction Written(wire(readTicket(h, ticket)!!, true))

            // One event per change, in the contract's order; the first carries the op's uuid and fingerprint.
            data class Ev(val kind: String, val body: String?, val from: String?, val to: String?, val code: String?)
            val evs = buildList {
                when (u.status) {
                    null -> {}
                    "resolved" -> add(Ev("resolution", SupportTickets.resolutionMessage(st.ticketNo, resolutionLabel!!, u.resolutionNotes!!), st.status, "resolved", u.resolutionCode))
                    "cancelled" -> add(Ev("status_change", u.cancelReason, st.status, "cancelled", null))
                    else -> add(Ev("status_change", null, st.status, u.status, null))
                }
                if (priorityChange) add(Ev("priority_change", null, null, null, null))
                if (assigneeChange) add(Ev("assign", null, null, null, null))
            }
            evs.forEachIndexed { i, e ->
                val uuid = if (i == 0) op else SupportTickets.opEventUuid(op, e.kind)
                insertDeskEvent(h, uuid, ticket, p.userId, now, today, cv, e.kind, e.body, e.from, e.to, e.code, if (i == 0) u.sha256 else null)
            }
            val sets = mutableListOf("version = version + 1", "updated_at = :now")
            val binds = linkedMapOf<String, Any>("now" to SupportTickets.utc(now), "t" to ticket, "v" to st.version)
            when (u.status) {
                null -> {}
                "in_progress" -> sets += listOf("status = 'in_progress'", "first_response_at = COALESCE(first_response_at, :now)")
                "resolved" -> {
                    sets += listOf("status = 'resolved'", "resolved_at = :now", "resolved_by = :me", "resolution_code = :rc", "resolution_notes = :rn", "first_response_at = COALESCE(first_response_at, :now)")
                    binds["me"] = p.userId; binds["rc"] = u.resolutionCode!!; binds["rn"] = u.resolutionNotes!!
                }
                "cancelled" -> {
                    sets += listOf("status = 'cancelled'", "cancelled_at = :now", "cancelled_by = :me", "cancel_reason = :cr", "first_response_at = COALESCE(first_response_at, :now)")
                    binds["me"] = p.userId; binds["cr"] = u.cancelReason!!
                }
                "closed" -> { sets += listOf("status = 'closed'", "closed_at = :now", "closed_by = :me"); binds["me"] = p.userId }
            }
            if (priorityChange) { sets += "priority = :pr"; binds["pr"] = u.priority!! }
            if (assigneeChange) sets += if (u.assignee == null) "assignee_user_id = NULL" else "assignee_user_id = :as".also { binds["as"] = u.assignee }
            val upd = h.createUpdate("UPDATE app.support_ticket_state SET ${sets.joinToString(", ")} WHERE ticket_uuid = :t AND version = :v")
            binds.forEach { (k, v) -> upd.bind(k, v) }
            check(upd.execute() == 1) { "ticket $ticket state row did not update" }
            u.status?.let { to ->
                check(DomainEventProducer.supportTicketStatusChanged(h, ticket.toString(), op, st.status, to, u.resolutionCode, p.userId, true, today, null) == 1)
            }
            AuditWriter.write(
                h, p, "support_ticket", ticket.toString(), "desk_update",
                buildJsonObject { put("status", st.status); put("priority", st.priority); put("assignee_user_id", st.assignee); put("version", st.version) },
                buildJsonObject {
                    put("status", u.status ?: st.status); put("priority", u.priority ?: st.priority)
                    put("assignee_user_id", if (assigneeChange) u.assignee else st.assignee); put("version", st.version + 1)
                    u.resolutionCode?.let { put("resolution_code", it) }; put("op_uuid", op)
                },
                u.cancelReason, requestId,
            )
            val change = u.status ?: if (priorityChange) "priority" else "assign"
            val text = SupportTicketTexts.of(change, st.ticketNo, u.priority)
            val added = Inbox.add(h, inboxItem(st.requester, ticket, op, text))
            val push = added && SupportTickets.pushEnabled(h, now)
            Written(wire(readTicket(h, ticket)!!, true), st.requester.takeIf { push })
        }
        out.nudgeUser?.let { runCatching { nudger.nudge(it, Inbox.PUSH_REASON) }.onFailure { e -> log.warn("ticket push failed: ${e.javaClass.simpleName}") } }
        return out.value
    }

    /**
     * `POST /v1/support/tickets/{ticket_uuid}/events` (D-TSO-MAN-20, 21): a desk reply, idempotent by `event_uuid`
     * (first call true = 201; the same uuid with the same body on the same ticket answers the stored reply, false = 200;
     * any other use of the uuid is 409 ERR_CONFLICT). Replay comes before the state rule, so a retried reply still
     * answers after the ticket was resolved. Only on a new or in_progress ticket (else 409 ERR_TICKET_COMMENTS_CLOSED);
     * the first reply on a New ticket first moves it to in_progress (its own `status_change` event, the version + 1,
     * `first_response_at`). The reporter gets one inbox row (and a push when switched on) after the commit.
     */
    fun reply(p: AronPrincipal, ticket: UUID, eventUuid: UUID, body: String, requestId: String?): Pair<Boolean, SupportTicketEvent> {
        val now = clock.now()
        val today = SupportTickets.today(clock)
        val cv = config.configVersion()
        val ev = eventUuid.toString()
        val out = db.jdbi.inTransaction<Written<Pair<Boolean, SupportTicketEvent>>, Exception> { h ->
            val st = lockState(h, ticket) ?: throw notFound()
            data class Prior(val ticket: String, val kind: String, val via: String, val body: String?)
            h.createQuery("SELECT ticket_uuid::text, kind, via, body FROM app.support_ticket_event WHERE client_uuid = :e").bind("e", eventUuid)
                .map { rs, _ -> Prior(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.findOne().orElse(null)?.let { prior ->
                    if (prior.ticket != ticket.toString() || prior.kind != "comment" || prior.via != "web" || prior.body != body) {
                        throw ApiProblem(ProblemCode.ERR_CONFLICT, "event_uuid $ev is already used by another event")
                    }
                    return@inTransaction Written(false to events(h, ticket, eventUuid).single())
                }
            if (st.status !in SupportTickets.OPEN) throw ApiProblem(ProblemCode.ERR_TICKET_COMMENTS_CLOSED, "this ticket is ${st.status}; no further comments can be added")
            if (st.status == "new") {
                val start = SupportTickets.replyStartUuid(ev)
                insertDeskEvent(h, start, ticket, p.userId, now, today, cv, "status_change", null, "new", "in_progress", null, null)
                check(
                    h.createUpdate(
                        "UPDATE app.support_ticket_state SET status = 'in_progress', first_response_at = COALESCE(first_response_at, :now), version = version + 1, updated_at = :now WHERE ticket_uuid = :t AND version = :v",
                    ).bind("now", SupportTickets.utc(now)).bind("t", ticket).bind("v", st.version).execute() == 1,
                ) { "ticket $ticket state row did not update" }
                check(DomainEventProducer.supportTicketStatusChanged(h, ticket.toString(), start, "new", "in_progress", null, p.userId, true, today, null) == 1)
            } else {
                h.createUpdate("UPDATE app.support_ticket_state SET updated_at = :now, first_response_at = COALESCE(first_response_at, :now) WHERE ticket_uuid = :t")
                    .bind("now", SupportTickets.utc(now)).bind("t", ticket).execute()
            }
            insertDeskEvent(h, ev, ticket, p.userId, now, today, cv, "comment", body, null, null, null, null)
            check(DomainEventProducer.supportTicketCommented(h, ticket.toString(), ev, p.userId, true, today, null) == 1)
            AuditWriter.write(h, p, "support_ticket", ticket.toString(), "desk_reply", null, buildJsonObject { put("event_uuid", ev); put("status", if (st.status == "new") "in_progress" else st.status) }, null, requestId)
            val added = Inbox.add(h, inboxItem(st.requester, ticket, ev, SupportTicketTexts.of("reply", st.ticketNo)))
            val push = added && SupportTickets.pushEnabled(h, now)
            Written(true to events(h, ticket, eventUuid).single(), st.requester.takeIf { push })
        }
        out.nudgeUser?.let { runCatching { nudger.nudge(it, Inbox.PUSH_REASON) }.onFailure { e -> log.warn("ticket push failed: ${e.javaClass.simpleName}") } }
        return out.value
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private data class StateRow(val ticketNo: String, val requester: Long, val status: String, val priority: String, val assignee: Long?, val version: Int)

    /** The state row of a live (not voided) ticket, locked for the rest of the transaction; null when there is none. */
    private fun lockState(h: Handle, ticket: UUID): StateRow? = h.createQuery(
        "SELECT t.ticket_no, s.requester_user_id, s.status, s.priority, s.assignee_user_id, s.version FROM app.support_ticket_state s " +
            "JOIN app.support_ticket t ON t.client_uuid = s.ticket_uuid WHERE s.ticket_uuid = :t AND t.voided_at IS NULL FOR UPDATE OF s",
    ).bind("t", ticket).map { rs, _ -> StateRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getObject(5) as Long?, rs.getInt(6)) }
        .findOne().orElse(null)

    /** The D-TSO-MAN-20 matrix of the desk: Start, Resolve, Cancel from new or in_progress; Close from resolved. */
    private fun allowed(from: String, to: String): Boolean = when (to) {
        "in_progress" -> from == "new"
        "resolved", "cancelled" -> from in SupportTickets.OPEN
        "closed" -> from == "resolved"
        else -> false
    }

    /** An assignee is an active user whose role holds the desk permission now. */
    private fun checkAssignee(h: Handle, userId: Long) {
        val ok = h.createQuery(
            "SELECT count(*) FROM app.app_user u JOIN app.role_grant_map g ON g.role = u.role AND g.permission = :perm WHERE u.id = :u AND u.status = 'active'",
        ).bind("perm", SupportTickets.DESK_PERMISSION).bind("u", userId).mapTo(Long::class.java).one() > 0
        if (!ok) throw bad("/assignee_user_id", "invalid_value")
    }

    private fun insertDeskEvent(
        h: Handle, uuid: String, ticket: UUID, actor: Long, now: Instant, today: LocalDate, cv: Long,
        kind: String, body: String?, from: String?, to: String?, code: String?, sha: ByteArray?,
    ) {
        h.createUpdate(
            """
            INSERT INTO app.support_ticket_event (client_uuid, ticket_uuid, family_uuid, business_date, user_id, captured_at, config_version, received_at,
                                                  via, kind, body, from_status, to_status, resolution_code, op_sha256)
            VALUES (CAST(:u AS uuid), :t, CAST(:u AS uuid), :bd, :actor, :at, :cv, :at, 'web', :kind, :body, :from, :to, :code, :sha)
            """.trimIndent(),
        ).bind("u", uuid).bind("t", ticket).bind("bd", today).bind("actor", actor).bind("at", SupportTickets.utc(now)).bind("cv", cv)
            .bind("kind", kind).bind("body", body).bind("from", from).bind("to", to).bind("code", code).bind("sha", sha).execute()
    }

    private fun inboxItem(user: Long, ticket: UUID, eventUuid: String, t: SupportTicketTexts.Text) = InboxItem(
        userId = user, kind = "ticket_updated", refType = "support_ticket", refId = ticket.toString(),
        titleEn = t.titleEn, titleBn = t.titleBn, bodyEn = t.bodyEn, bodyBn = t.bodyBn, dedupeKey = "ticket:$ticket:$eventUuid",
    )

    internal data class TicketRow(
        val id: Long, val uuid: String, val ticketNo: String, val issueType: String, val issueEn: String, val issueBn: String?, val title: String,
        val description: String, val source: String, val priority: String, val status: String, val requesterId: Long, val requesterUsername: String,
        val requesterName: String, val requesterRole: String, val affectedUsername: String?, val assigneeId: Long?, val assigneeName: String?,
        val businessDate: LocalDate, val capturedAt: Instant, val createdAtRaw: OffsetDateTime, val updatedAt: Instant, val firstResponseAt: Instant?,
        val resolvedAt: Instant?, val closedAt: Instant?, val cancelledAt: Instant?, val resolutionCode: String?, val resolutionLabel: String?,
        val resolutionNotes: String?, val cancelReason: String?, val version: Int, val attachments: Int,
    )

    private fun readTicket(h: Handle, ticket: UUID): TicketRow? =
        h.createQuery("$TICKET_SELECT AND t.client_uuid = :t").bind("t", ticket).map { rs, _ -> row(rs) }.findOne().orElse(null)

    /** The thread (oldest first, at most 500), or only the event [only]. */
    private fun events(h: Handle, ticket: UUID, only: UUID? = null): List<SupportTicketEvent> = h.createQuery(
        """
        SELECT e.client_uuid::text AS uuid, e.kind, e.body, e.via, e.from_status, e.to_status, e.resolution_code, e.created_at, u.full_name,
               ARRAY(SELECT m.client_uuid::text FROM app.media m WHERE m.ref_type = 'support_ticket_event' AND m.ref_client_uuid = e.client_uuid
                      AND m.voided_at IS NULL ORDER BY m.id LIMIT 3) AS photos
          FROM app.support_ticket_event e JOIN app.app_user u ON u.id = e.user_id
         WHERE e.ticket_uuid = :t AND e.voided_at IS NULL${if (only != null) " AND e.client_uuid = :only" else ""}
         ORDER BY e.created_at, e.id LIMIT 500
        """.trimIndent(),
    ).bind("t", ticket).also { q -> only?.let { q.bind("only", it) } }.map { rs, _ ->
        val kind = rs.getString("kind")
        val support = rs.getString("via") != "device"
        // A close review (the reporter's Submit Review & Close) is accepted only on a resolved ticket and closes it: the
        // synced row carries no statuses of its own (the record has none), so the read states the move it made.
        val closeReview = kind == "close_review"
        SupportTicketEvent(
            eventUuid = rs.getString("uuid"), kind = kind, body = rs.getString("body"), actorIsSupport = support,
            actorDisplay = if (support) SupportTickets.SUPPORT_DISPLAY else rs.getString("full_name").take(120),
            fromStatus = rs.getString("from_status") ?: "resolved".takeIf { closeReview }, toStatus = rs.getString("to_status") ?: "closed".takeIf { closeReview },
            resolutionCode = rs.getString("resolution_code"), createdAt = instant(rs, "created_at")!!.wire(),
            attachments = (rs.getArray("photos").array as Array<*>).map { it.toString() },
        )
    }.list()

    /** Media uuids of the photos attached to [ref] (a ticket or an event), oldest first, at most 3 (D-TSO-MAN-23). */
    private fun photos(h: Handle, refType: String, ref: UUID): List<String> = h.createQuery(
        "SELECT client_uuid::text FROM app.media WHERE ref_type = :rt AND ref_client_uuid = :r AND voided_at IS NULL ORDER BY id LIMIT 3",
    ).bind("rt", refType).bind("r", ref).mapTo(String::class.java).list()

    /** The contract `SupportTicket`; the desk-only members (`assignee_user_id`, `assignee_name`) are absent for anyone else. */
    private fun wire(t: TicketRow, desk: Boolean): JsonObject {
        val dto = SupportTicket(
            ticketUuid = t.uuid, ticketNo = t.ticketNo, issueTypeCode = t.issueType, issueTypeLabelEn = t.issueEn, issueTypeLabelBn = t.issueBn,
            categoryLabelEn = "Tickets", title = t.title, description = t.description, source = t.source, priority = t.priority, status = t.status,
            requester = SupportTicketRequester(t.requesterId, t.requesterUsername, t.requesterName, t.requesterRole), affectedUsername = t.affectedUsername,
            assigneeUserId = t.assigneeId, assigneeName = t.assigneeName, businessDate = t.businessDate.toString(), capturedAt = t.capturedAt.wire(),
            createdAt = t.createdAtRaw.toInstant().wire(), updatedAt = t.updatedAt.wire(), firstResponseAt = t.firstResponseAt?.wire(),
            resolvedAt = t.resolvedAt?.wire(), closedAt = t.closedAt?.wire(), cancelledAt = t.cancelledAt?.wire(), resolutionCode = t.resolutionCode,
            resolutionLabelEn = t.resolutionLabel, resolutionNotes = t.resolutionNotes, cancelReason = t.cancelReason, version = t.version,
            attachmentCount = t.attachments,
        )
        val o = ResponseJson.encodeToJsonElement(SupportTicket.serializer(), dto) as JsonObject
        return if (desk) o else JsonObject(o - setOf("assignee_user_id", "assignee_name"))
    }

    private fun row(rs: ResultSet) = TicketRow(
        id = rs.getLong("id"), uuid = rs.getString("uuid"), ticketNo = rs.getString("ticket_no"), issueType = rs.getString("issue_type_code"),
        issueEn = rs.getString("it_en").take(120), issueBn = rs.getString("it_bn")?.take(120), title = rs.getString("title"), description = rs.getString("description"),
        source = rs.getString("source"), priority = rs.getString("priority"), status = rs.getString("status"), requesterId = rs.getLong("requester_user_id"),
        requesterUsername = rs.getString("r_username"), requesterName = rs.getString("r_name").take(120), requesterRole = rs.getString("requester_role"),
        affectedUsername = rs.getString("affected_username"), assigneeId = rs.getObject("assignee_user_id") as Long?, assigneeName = rs.getString("a_name")?.take(120),
        businessDate = rs.getObject("business_date", LocalDate::class.java), capturedAt = instant(rs, "captured_at")!!,
        createdAtRaw = rs.getObject("created_at", OffsetDateTime::class.java), updatedAt = instant(rs, "updated_at")!!,
        firstResponseAt = instant(rs, "first_response_at"), resolvedAt = instant(rs, "resolved_at"), closedAt = instant(rs, "closed_at"),
        cancelledAt = instant(rs, "cancelled_at"), resolutionCode = rs.getString("resolution_code"), resolutionLabel = rs.getString("rc_en")?.take(120),
        resolutionNotes = rs.getString("resolution_notes"), cancelReason = rs.getString("cancel_reason"), version = rs.getInt("version"),
        attachments = rs.getInt("photos"),
    )

    private fun instant(rs: ResultSet, col: String): Instant? = rs.getObject(col, OffsetDateTime::class.java)?.toInstant()

    private fun notFound() = ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such ticket")

    private fun bad(pointer: String, code: String) = ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

    companion object {
        /**
         * A LIKE pattern that matches [s] literally: `!` escapes `%`, `_` and itself (`ESCAPE '!'`; a backslash in a quoted
         * SQL literal makes Jdbi's statement parser read past the closing quote and miss the next named parameter).
         */
        internal fun like(s: String): String = s.replace("!", "!!").replace("%", "!%").replace("_", "!_")

        /** Microseconds since the epoch: the list cursor keeps `created_at` at the column's own precision. */
        internal fun micros(t: OffsetDateTime): Long = t.toEpochSecond() * 1_000_000 + t.nano / 1_000

        internal fun fromMicros(m: Long): OffsetDateTime = OffsetDateTime.ofInstant(Instant.ofEpochSecond(Math.floorDiv(m, 1_000_000L), Math.floorMod(m, 1_000_000L) * 1_000), ZoneOffset.UTC)

        /** Every ticket read: the live ticket, its state, the requester, the assignee, the code labels and the photo count. */
        private val TICKET_SELECT = """
            SELECT t.id, t.client_uuid::text AS uuid, t.ticket_no, t.issue_type_code, t.title, t.description, t.source, t.affected_username,
                   t.business_date, t.captured_at, t.created_at,
                   s.status, s.priority, s.requester_user_id, s.requester_role, s.assignee_user_id, s.first_response_at, s.resolved_at, s.closed_at,
                   s.cancelled_at, s.resolution_code, s.resolution_notes, s.cancel_reason, s.version, s.updated_at,
                   ru.username AS r_username, ru.full_name AS r_name, au.full_name AS a_name,
                   COALESCE(it.label_en, t.issue_type_code) AS it_en, it.label_bn AS it_bn, rc.label_en AS rc_en,
                   ((SELECT count(*) FROM app.media m WHERE m.ref_type = 'support_ticket' AND m.ref_client_uuid = t.client_uuid AND m.voided_at IS NULL)
                    + (SELECT count(*) FROM app.media m JOIN app.support_ticket_event e ON e.client_uuid = m.ref_client_uuid
                        WHERE m.ref_type = 'support_ticket_event' AND e.ticket_uuid = t.client_uuid AND m.voided_at IS NULL))::int AS photos
              FROM app.support_ticket t
              JOIN app.support_ticket_state s ON s.ticket_uuid = t.client_uuid
              JOIN app.app_user ru ON ru.id = s.requester_user_id
              LEFT JOIN app.app_user au ON au.id = s.assignee_user_id
              LEFT JOIN app.code_list_item it ON it.list_key = 'ticket_issue_type' AND it.code = t.issue_type_code
              LEFT JOIN app.code_list_item rc ON rc.list_key = 'ticket_resolution_code' AND rc.code = s.resolution_code
             WHERE t.voided_at IS NULL
        """.trimIndent()
    }
}
