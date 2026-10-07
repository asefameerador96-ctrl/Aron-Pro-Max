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
}
