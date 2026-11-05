package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import kotlinx.serialization.json.JsonPrimitive
import org.jdbi.v3.core.Handle

/**
 * N-049: the transactional outbox producer for ingested records (docs/24 s12.5 item 4, docs/data-events.md v1 payloads).
 * Runs in the record's own savepoint on first store only, so a record and its event commit or roll back together and a
 * duplicate or replay never emits a second event. Payloads are built from the stored row (never from the raw body), carry
 * ids, codes and milli-taka only, and set `payload_version` explicitly. The route-day state events
 * (`route_day.state_changed`) belong to the day state machine (F-SYS-016), not to this producer.
 */
class DomainEventProducer : RecordHandler {
    override val types = setOf("memo", "memo_void", "visit_close", "stock_movement")

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        if (serverId == null) return
        val n = when (rec.type) {
            "memo" -> emit(h, rec, serverId, "memo.created", "memo", "m.client_uuid::text", "memo m",
                "jsonb_build_object('memo_uuid', m.client_uuid, 'visit_uuid', m.visit_client_uuid, 'route_id', m.route_id, 'outlet_id', m.outlet_id, 'user_id', m.user_id, " +
                    "'acting_for_user_id', m.acting_for_user_id, 'memo_kind', m.memo_kind, 'gross_mtk', m.gross_mtk, 'net_mtk', m.net_mtk, 'paid_mtk', m.paid_mtk, 'due_mtk', m.due_mtk, 'line_count', m.line_count)")
            "memo_void" -> emit(h, rec, serverId, "memo.voided", "memo", "m.memo_client_uuid::text", "memo_void m",
                "jsonb_build_object('memo_uuid', m.memo_client_uuid, 'route_id', m.route_id, 'voided_at', m.captured_at, 'reason_code', CAST(:reason AS text))")
            "visit_close" -> emit(h, rec, serverId, "visit.closed", "visit", "m.client_uuid::text", "visit m",
                "jsonb_build_object('visit_uuid', m.client_uuid, 'route_id', m.route_id, 'outlet_id', m.outlet_id, 'user_id', m.user_id, 'visit_kind', m.visit_kind, " +
                    "'planned', m.planned, 'verdict', m.verdict, 'fix_is_mock', m.fix_is_mock, 'distance_m', m.distance_m)", "m.close_client_uuid")
            "stock_movement" -> emit(h, rec, serverId, "stock.moved", "stock_movement", "m.client_uuid::text", "stock_movement m",
                "jsonb_build_object('movement_uuid', m.client_uuid, 'route_id', m.route_id, 'user_id', m.user_id, 'sku_id', m.sku_id, 'kind', m.kind, 'qty_base', m.qty_base)")
            else -> return
        }
        check(n == 1) { "domain event for ${rec.type} ${rec.clientUuid} was not written ($n rows)" }
    }

    private fun emit(h: Handle, rec: IngestRecord, id: Long, event: String, aggregate: String, aggregateId: String, from: String, payload: String, matchCol: String = "m.client_uuid"): Int =
        h.createUpdate(
            "INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload, source_client_uuid) " +
                "SELECT '$event', 1, '$aggregate', $aggregateId, m.business_date, $payload, CAST(:src AS uuid) FROM app.$from WHERE m.id = :id AND $matchCol = CAST(:src AS uuid)",
        ).bind("id", id).bind("src", rec.clientUuid).bind("reason", (rec.payload["reason_code"] as? JsonPrimitive)?.content).execute()

    // tso-manual B1: the three support_ticket.* v1 types (D-TSO-MAN-38, V0134 payload schemas). Written in the transaction
    // of the change by SupportTicketHandler (sync: the ticket, the reporter's comment and close review) and
    // SupportTicketService (the desk's status changes and replies); ids and codes only, never the title, a comment,
    // notes or a reason (free text).
    companion object {
        /** `support_ticket.created` from the stored ticket and its state row; returns the rows written (1). */
        fun supportTicketCreated(h: Handle, ticketUuid: String): Int = h.createUpdate(
            "INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload, source_client_uuid) " +
                "SELECT 'support_ticket.created', 1, 'support_ticket', t.client_uuid::text, t.business_date, " +
                "jsonb_build_object('ticket_uuid', t.client_uuid, 'ticket_no', t.ticket_no, 'issue_type_code', t.issue_type_code, 'priority', s.priority, " +
                "'requester_user_id', s.requester_user_id, 'requester_role', s.requester_role, 'business_date', t.business_date, 'source', t.source, " +
                "'has_affected_user', t.affected_username IS NOT NULL), t.client_uuid " +
                "FROM app.support_ticket t JOIN app.support_ticket_state s ON s.ticket_uuid = t.client_uuid WHERE t.client_uuid = CAST(:t AS uuid)",
        ).bind("t", ticketUuid).execute()

        /** `support_ticket.status_changed`; [sourceClientUuid] is the device record that made the change (null for the desk). */
        fun supportTicketStatusChanged(
            h: Handle, ticketUuid: String, eventUuid: String, from: String, to: String, resolutionCode: String?, actorUserId: Long, actorIsSupport: Boolean,
            businessDate: java.time.LocalDate, sourceClientUuid: String?,
        ): Int = ticketEvent(
            h, "support_ticket.status_changed", ticketUuid, businessDate, sourceClientUuid,
            kotlinx.serialization.json.buildJsonObject {
                put("ticket_uuid", JsonPrimitive(ticketUuid)); put("event_uuid", JsonPrimitive(eventUuid)); put("from_status", JsonPrimitive(from)); put("to_status", JsonPrimitive(to))
                put("resolution_code", JsonPrimitive(resolutionCode)); put("actor_user_id", JsonPrimitive(actorUserId)); put("actor_is_support", JsonPrimitive(actorIsSupport))
            },
        )

        /** `support_ticket.commented` (the comment text is not in the payload). */
        fun supportTicketCommented(
            h: Handle, ticketUuid: String, eventUuid: String, actorUserId: Long, actorIsSupport: Boolean, businessDate: java.time.LocalDate, sourceClientUuid: String?,
        ): Int = ticketEvent(
            h, "support_ticket.commented", ticketUuid, businessDate, sourceClientUuid,
            kotlinx.serialization.json.buildJsonObject {
                put("ticket_uuid", JsonPrimitive(ticketUuid)); put("event_uuid", JsonPrimitive(eventUuid)); put("actor_user_id", JsonPrimitive(actorUserId)); put("actor_is_support", JsonPrimitive(actorIsSupport))
            },
        )

        private fun ticketEvent(h: Handle, type: String, ticketUuid: String, businessDate: java.time.LocalDate, source: String?, payload: kotlinx.serialization.json.JsonObject): Int =
            h.createUpdate(
                "INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload, source_client_uuid) " +
                    "VALUES (:type, 1, 'support_ticket', :agg, :bd, CAST(:p AS jsonb), CAST(:src AS uuid))",
            ).bind("type", type).bind("agg", ticketUuid).bind("bd", businessDate).bind("p", payload.toString()).bind("src", source).execute()

        /**
         * Event types written by a service path rather than by a record's first store (append-only; the catalogue row is
         * `app.domain_event_type`, the payload docs/data-events.md v1). Each is written in its fact's own transaction.
         */
        val SERVICE_TYPES: Set<String> = setOf(
            // tso-manual B3 (D-TSO-MAN-38): the zone-day close and reopen (backend:sync DayService, AutoFinalSubmitJob) and the leave decision (LeaveDecided)
            "zone_day.final_submitted", "zone_day.reopened", "leave.decided",
        )

        /** One outbox row of a [SERVICE_TYPES] event (payload_version 1) on the caller's handle and transaction. */
        fun emitService(h: Handle, eventType: String, aggregateType: String, aggregateId: String, businessDate: java.time.LocalDate, sourceUuid: String?, payload: kotlinx.serialization.json.JsonObject) {
            require(eventType in SERVICE_TYPES) { "$eventType is not a service event type" }
            h.createUpdate(
                "INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload, source_client_uuid) " +
                    "VALUES (:t, 1, :at, :a, :d, CAST(:p AS jsonb), CAST(:s AS uuid))",
            ).bind("t", eventType).bind("at", aggregateType).bind("a", aggregateId).bind("d", businessDate).bind("p", payload.toString()).bind("s", sourceUuid).execute()
        }
    }
}
