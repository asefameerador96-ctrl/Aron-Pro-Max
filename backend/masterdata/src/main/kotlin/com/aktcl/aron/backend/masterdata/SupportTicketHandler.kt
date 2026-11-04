package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.RecordOutcomeCode
import kotlinx.serialization.json.JsonPrimitive
import org.jdbi.v3.core.Handle

/**
 * The sync records `support_ticket` and `support_ticket_event` (D-TSO-MAN-17 to 24, contract 1.10.0; build contract s5
 * B1). The generic writer stores the rows (TypeRules: the event's parent is `ticket_uuid`); this handler judges them and
 * writes what follows from them, in the record's own transaction and savepoint.
 *
 * Check order (s5 B1): the replay comes first in ingest itself (a stored uuid answers as stored, before any handler runs);
 * then the requester's role must be in `cfg.support.ticket_requester_roles` (else quarantined `scope_out_of_reach`);
 * then, for a ticket, its issue type must be an active `ticket_issue_type` code on the record's business date (else
 * rejected `issue_type_unknown`); for an event, the ticket must be the token user's own, not voided, and its status must
 * allow the kind: a `comment` (1 to 2000 characters) while new or in_progress, a `close_review` only while resolved (else
 * rejected `ticket_state_invalid`). These run in [checkEarly], before ingest's parent check, so an event on another
 * user's ticket is `ticket_state_invalid` (not a scope hold); an event whose ticket is not stored yet passes here and is
 * parked `parent_missing` by the parent check (held with its ticket when that one is quarantined), then accepted on a
 * later resend. An event whose ticket was refused for good is refused with it. [check] runs the same rules again after
 * the parent check, on the locked state row, so a desk change committed in between is seen.
 *
 * [afterStored] (first store only): a ticket gets its state row (requester, role, priority from the issue type's attrs,
 * the affected user resolved from `affected_username`), its `created` event and `support_ticket.created`; a comment
 * touches `updated_at` and emits `support_ticket.commented`; a close review closes the resolved ticket (version + 1) and
 * emits `support_ticket.status_changed`. All in the record's transaction: they commit with the row or not at all.
 *
 * R-F-05: an ADMIN's accept of a quarantined ticket or event overrides the role refusal in ingest; the record then
 * reaches [afterStored] with a role that is still not allowed, which refuses the accept (409 `ERR_REQUEST_STATE`, the
 * accept rolls back and the item stays open). Only a review accept can override a refusal, so a batch never gets here.
 */
class SupportTicketHandler : RecordHandler {
    override val types: Set<String> = setOf(TICKET, EVENT)

    override fun checkEarly(h: Handle, rec: IngestRecord): RecordRefusal? {
        if (!SupportTickets.roleAllowed(h, rec.role, rec.receivedAt)) {
            return RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "requester role ${rec.role.wire} is not in ${SupportTickets.REQUESTER_ROLES_KEY}")
        }
        return judge(h, rec, lock = false)
    }

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? = judge(h, rec, lock = true)

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        if (!SupportTickets.roleAllowed(h, rec.role, rec.receivedAt)) {
            throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the requester role ${rec.role.wire} is still not in ${SupportTickets.REQUESTER_ROLES_KEY}; the record stays quarantined (R-F-05)")
        }
        when (rec.type) {
            TICKET -> ticketStored(h, rec)
            EVENT -> eventStored(h, rec)
        }
    }

    private fun judge(h: Handle, rec: IngestRecord, lock: Boolean): RecordRefusal? = when (rec.type) {
        TICKET -> issueType(h, rec)
        EVENT -> eventState(h, rec, lock)
        else -> null
    }

    private fun issueType(h: Handle, rec: IngestRecord): RecordRefusal? {
        val code = rec.payload.text("issue_type_code") ?: return null // the shape check refuses a missing member
        val active = h.createQuery(
            "SELECT count(*) FROM app.code_list_item WHERE list_key = 'ticket_issue_type' AND code = :c AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)",
        ).bind("c", code).bind("d", rec.businessDate).mapTo(Long::class.java).one() > 0
        return if (active) null else RecordRefusal(RecordOutcomeCode.ISSUE_TYPE_UNKNOWN, "issue type $code is not active on ${rec.businessDate}")
    }

    private fun eventState(h: Handle, rec: IngestRecord, lock: Boolean): RecordRefusal? {
        val ticket = rec.payload.text("ticket_uuid")?.takeIf { AdminSupport.UUID_V4.matches(it) } ?: return null // the parent check refuses it
        data class Row(val voided: Boolean, val requester: Long, val status: String)
        val row = h.createQuery(
            "SELECT t.voided_at IS NOT NULL, s.requester_user_id, s.status FROM app.support_ticket t JOIN app.support_ticket_state s ON s.ticket_uuid = t.client_uuid " +
                "WHERE t.client_uuid = CAST(:t AS uuid)" + if (lock) " FOR UPDATE OF s" else "",
        ).bind("t", ticket).map { rs, _ -> Row(rs.getBoolean(1), rs.getLong(2), rs.getString(3)) }.findOne().orElse(null)
        if (row == null) {
            // Not stored: parked by the parent check until the ticket arrives, or held with it while it is quarantined. A
            // ticket refused for good (an unknown issue type) can never arrive, so its events are refused with it.
            val refused = h.createQuery("SELECT count(*) FROM app.ingest_registry WHERE client_uuid = CAST(:t AS uuid) AND record_type = :type AND status = 'rejected'")
                .bind("t", ticket).bind("type", TICKET).mapTo(Long::class.java).one() > 0
            return if (refused) RecordRefusal(RecordOutcomeCode.TICKET_STATE_INVALID, "ticket $ticket was refused") else null
        }
        if (row.voided) return RecordRefusal(RecordOutcomeCode.TICKET_STATE_INVALID, "ticket $ticket is voided")
        if (row.requester != rec.userId) return RecordRefusal(RecordOutcomeCode.TICKET_STATE_INVALID, "ticket $ticket is another user's")
        return when (val kind = rec.payload.text("kind")) {
            "comment" -> when {
                rec.payload.text("body").let { it == null || it.isBlank() || it.length > MAX_BODY } ->
                    RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "a comment needs a body of 1 to $MAX_BODY characters")
                row.status !in SupportTickets.OPEN -> RecordRefusal(RecordOutcomeCode.TICKET_STATE_INVALID, "ticket $ticket is ${row.status}: no further comments can be added")
                else -> null
            }
            "close_review" -> if (row.status == "resolved") null else RecordRefusal(RecordOutcomeCode.TICKET_STATE_INVALID, "only a resolved ticket can be closed; ticket $ticket is ${row.status}")
            else -> RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "kind ${kind?.take(40)} is not comment or close_review")
        }
    }

    private fun ticketStored(h: Handle, rec: IngestRecord) {
        val t = rec.clientUuid
        data class Ticket(val issue: String, val affected: String?)
        val ticket = h.createQuery("SELECT issue_type_code, affected_username FROM app.support_ticket WHERE client_uuid = CAST(:t AS uuid)").bind("t", t)
            .map { rs, _ -> Ticket(rs.getString(1), rs.getString(2)) }.one()
        // D-TSO-MAN-19: the issue type's default priority (attrs.priority; p1 for all 31 Annex A types).
        val priority = h.createQuery("SELECT attrs->>'priority' FROM app.code_list_item WHERE list_key = 'ticket_issue_type' AND code = :c")
            .bind("c", ticket.issue).mapTo(String::class.java).findOne().orElse(null)?.takeIf { it in SupportTickets.PRIORITIES } ?: "p1"
        // D-TSO-MAN-24: the affected user for the desk only (login names are unique ignoring case); the ticket stays the reporter's.
        val affected = ticket.affected?.let { u ->
            h.createQuery("SELECT id FROM app.app_user WHERE lower(username) = lower(:u)").bind("u", u).mapTo(Long::class.java).findOne().orElse(null)
        }
        h.createUpdate(
            "INSERT INTO app.support_ticket_state (ticket_uuid, requester_user_id, requester_role, affected_user_id, priority, updated_at) " +
                "VALUES (CAST(:t AS uuid), :u, :role, :aff, :pr, :at) ON CONFLICT (ticket_uuid) DO NOTHING",
        ).bind("t", t).bind("u", rec.userId).bind("role", rec.role.wire).bind("aff", affected).bind("pr", priority).bind("at", SupportTickets.utc(rec.receivedAt)).execute()
        h.createUpdate(
            """
            INSERT INTO app.support_ticket_event (client_uuid, ticket_uuid, family_uuid, business_date, business_date_device, user_id, device_id, captured_at,
                                                  captured_offline, config_version, received_at, first_batch_uuid, via, kind, to_status)
            SELECT CAST(:e AS uuid), t.client_uuid, t.family_uuid, t.business_date, t.business_date_device, t.user_id, t.device_id, t.captured_at,
                   t.captured_offline, t.config_version, t.received_at, t.first_batch_uuid, 'device', 'created', 'new'
              FROM app.support_ticket t WHERE t.client_uuid = CAST(:t AS uuid)
            ON CONFLICT (client_uuid) DO NOTHING
            """.trimIndent(),
        ).bind("e", SupportTickets.createdEventUuid(t)).bind("t", t).execute()
        check(DomainEventProducer.supportTicketCreated(h, t) == 1) { "support_ticket.created for $t was not written" }
    }

    private fun eventStored(h: Handle, rec: IngestRecord) {
        val ticket = rec.payload.text("ticket_uuid")!!
        val at = SupportTickets.utc(rec.receivedAt)
        when (rec.payload.text("kind")) {
            "comment" -> {
                h.createUpdate("UPDATE app.support_ticket_state SET updated_at = :at WHERE ticket_uuid = CAST(:t AS uuid)").bind("at", at).bind("t", ticket).execute()
                check(DomainEventProducer.supportTicketCommented(h, ticket, rec.clientUuid, rec.userId, false, rec.businessDate, rec.clientUuid) == 1)
            }
            "close_review" -> {
                val n = h.createUpdate(
                    "UPDATE app.support_ticket_state SET status = 'closed', closed_at = :at, closed_by = :u, version = version + 1, updated_at = :at " +
                        "WHERE ticket_uuid = CAST(:t AS uuid) AND status = 'resolved'",
                ).bind("at", at).bind("u", rec.userId).bind("t", ticket).execute()
                // check() held the state row's lock since it saw `resolved`, so the close applies exactly once.
                check(n == 1) { "close review ${rec.clientUuid}: ticket $ticket is no longer resolved" }
                check(DomainEventProducer.supportTicketStatusChanged(h, ticket, rec.clientUuid, "resolved", "closed", null, rec.userId, false, rec.businessDate, rec.clientUuid) == 1)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val TICKET = "support_ticket"
        const val EVENT = "support_ticket_event"
        private const val MAX_BODY = 2000
    }
}
