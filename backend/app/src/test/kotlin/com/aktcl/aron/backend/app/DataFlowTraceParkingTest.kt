package com.aktcl.aron.backend.app

import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.test.Test

/**
 * Parking and the half-stored memo (docs/16 DQ-03, docs/24 s2): a child whose parent has not landed is `rejected parent_missing`, retryable, kept in
 * `sync_rejected` with `parked_until` (the config's `parked_ttl_days` ahead); the phone's resend is processed again; once the parent has landed the
 * child is accepted and the totals converge; a child still parentless after the TTL (the business date out of the sync window) is quarantined for the helpdesk, not kept retryable. And the
 * half-stored memo: a line the server refuses for good takes the memo's header and the other lines to the quarantine (arithmetic_mismatch), nothing of it is in the memo tables and the totals do not move.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DataFlowTraceParkingTest : DataFlowTraceSupport() {
    override val traceName get() = "data-flow-trace-parking"
    private lateinit var parts: List<ObjectNode>   // visit, memo, lines..., visit_close of the first sale
    private lateinit var memo: Memo

    private fun rejectedRow(client: String) = rows("SELECT code, retryable, attempts, parked_until, (parked_until - TIMESTAMPTZ '${day}T10:30:00Z') AS ahead FROM app.sync_rejected WHERE client_uuid = '$client'::uuid ORDER BY first_received_at").lastOrNull()

    @Test
    @Order(1)
    fun aChildAheadOfItsParentIsParkedAndResentUntilTheParentLands() {
        h("1 Parked child: parent_missing, parked_until, resend, convergence")
        prepareSrDay()
        val codes = route.path("outlets").map { it.path("code").asText() }.sorted()
        memo = Memo(outletByCode(codes[0]), codes[0], listOf(Line(pick("cigarette", 20), 2), Line(pick("bidi"), 1), Line(pick("match"), 2)), paidShare = 100, minute = 0)
        val (r, a) = radiusOf(memo.outlet)
        parts = recordsOf(sr, memo, gulPins.getValue(memo.outlet), r, a, 1)
        val lines = parts.filter { it.path("type").asText() == "memo_line" }
        val ttlDays = sqlLong("SELECT coalesce((SELECT (value #>> '{}')::bigint FROM app.cfg_value WHERE key = 'cfg.sync.parked_ttl_days' ORDER BY config_version DESC LIMIT 1), 7)")
        sr.counts["memo_line"] = lines.size
        val first = send(sr, listOf(lines[0]), uuid())
        hop("a line ahead of its memo is rejected parent_missing, retryable", "ack" to ackOf(first, "memo_line"), "expected" to "rejected parent_missing")
        val row1 = rejectedRow(lines[0].path("client_uuid").asText())!!
        hop("sync_rejected keeps it retryable with parked_until the TTL ahead", "retryable/ahead_days" to "${row1["retryable"]}/${(row1["ahead"] as org.postgresql.util.PGInterval).days}", "expected" to "true/$ttlDays")
        send(sr, listOf(lines[0]), uuid())
        hop("a resend counts as another attempt", "attempts" to (rejectedRow(lines[0].path("client_uuid").asText())!!["attempts"] as Number).toInt(), "expected" to 2)
        hop("nothing of the memo is stored yet", "memo/lines rows" to "${sqlLong("SELECT count(*) FROM app.memo WHERE client_uuid = '${memo.memo}'::uuid")}/${sqlLong("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${memo.memo}'::uuid")}", "expected" to "0/0")
        val rest = send(sr, parts.filter { it !== lines[0] }, uuid())
        hop("the visit, the memo and the other lines land", "bad rows" to acks(rest).count { it == "rejected" || it == "quarantined" }, "expected" to 0)
        val third = send(sr, listOf(lines[0]), uuid())
        hop("the parked line, resent after its parent, is accepted", "ack" to ackOf(third, "memo_line").trim(), "expected" to "accepted")
        hop("all lines stored once", "lines" to sqlLong("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${memo.memo}'::uuid"), "expected" to lines.size.toLong())
        val money = send(sr, parts, uuid()).body!!.path("server_totals").single { it.path("business_date").asText() == day }.path("money")
        hop("server_totals gross and sold quantity converge to the device", "server" to "${money.path("gross_mtk").asLong()} ${money.path("sold_qty_base_by_sku").fields().asSequence().associate { it.key to it.value.asLong() }.toSortedMap()}",
            "device" to "${memo.gross} ${memo.lines.groupBy { it.sku.id.toString() }.mapValues { e -> e.value.sumOf { it.qtyBase } }.toSortedMap()}")
    }

    @Test
    @Order(2)
    fun aParentlessChildIsParkedUntilTheTtlAndThenQuarantined() {
        h("2 Parking expiry: parked until the TTL, then out of the business-date window (quarantined for the helpdesk)")
        val codes = route.path("outlets").map { it.path("code").asText() }.sorted()
        val m2 = Memo(outletByCode(codes[1]), codes[1], listOf(Line(pick("lighter"), 1)), paidShare = 100, minute = 40)
        val (r, a) = radiusOf(m2.outlet)
        val parts2 = recordsOf(sr, m2, gulPins.getValue(m2.outlet), r, a, 2)
        val orphan = parts2.first { it.path("type").asText() == "memo_line" }
        hop("the orphan line is parked while the TTL runs", "ack" to ackOf(send(sr, listOf(orphan), uuid()), "memo_line"), "expected" to "rejected parent_missing")
        val ttlDays = sqlLong("SELECT coalesce((SELECT (value #>> '{}')::bigint FROM app.cfg_value WHERE key = 'cfg.sync.parked_ttl_days' ORDER BY config_version DESC LIMIT 1), 7)")
        fun orphanAt(days: Long): Pair<String, String?> {
            clockShift = Duration.ofDays(days)
            // A day and more later the access token is expired: the phone signs in again before it resends.
            val fresh = login(sr.username, "app_sr", sr.deviceUuid)
            val phone = Device(sr.username, sr.deviceUuid, sr.deviceId, sr.routeId, sr.bundleVersion, sr.configVersion, fresh).also { it.seq = sr.seq }
            val a = send(phone, listOf(orphan), uuid())
            return ackOf(a, "memo_line").trim() to a.body?.path("acks")?.firstOrNull { it.path("type").asText() == "memo_line" }?.path("retryable")?.asText()
        }
        val inside = orphanAt(ttlDays - 1)
        hop("one day before the TTL the orphan is still parked: rejected parent_missing, retryable", "ack/retryable" to "${inside.first}/${inside.second}", "expected" to "rejected parent_missing/true")
        val late = orphanAt(ttlDays + 1)
        hop("one day after the TTL the orphan resent is no longer parked: the business date is out of the window, so it is quarantined for the helpdesk, not kept retryable forever", "ack" to late.first, "expected" to "quarantined business_date_out_of_window")
        clockShift = Duration.ZERO
    }

    @Test
    @Order(3)
    fun aLineRefusedForGoodNeverLeavesAHalfStoredMemo() {
        h("3 Half-stored memo: a line refused for good takes its memo to the quarantine; no header without its lines")
        val codes = route.path("outlets").map { it.path("code").asText() }.sorted()
        val m3 = Memo(outletByCode(codes[2]), codes[2], listOf(Line(pick("cigarette", 10), 2), Line(pick("bidi"), 2)), paidShare = 100, minute = 80)
        val (r, a) = radiusOf(m3.outlet)
        val parts3 = recordsOf(sr, m3, gulPins.getValue(m3.outlet), r, a, 3)
        val lines = parts3.filter { it.path("type").asText() == "memo_line" }
        val bad = lines[1].deepCopy().also { (it.path("payload") as ObjectNode).put("sku_id", 999_999_999L) }
        val ans = send(sr, parts3.map { if (it === lines[1]) bad else it }, uuid())
        note("answers of the memo, its lines and the close", detail(ans))
        fun ackOfUuid(uuid: String) = ans.body!!.path("acks").first { it.path("client_uuid").asText() == uuid }.let { it.path("status").asText() + " " + it.path("code").asText("") + " retryable=" + it.path("retryable").asText() }
        hop("the line with an unknown SKU is refused for good", "ack" to ackOfUuid(bad.path("client_uuid").asText()), "expected" to "rejected unknown_sku retryable=false")
        hop("the memo is quarantined, its arithmetic no longer matches its lines", "ack" to ackOfUuid(m3.memo), "expected" to "quarantined arithmetic_mismatch retryable=null")
        hop("no half-stored memo: neither the header nor a line is in the memo tables", "memo/lines rows" to "${sqlLong("SELECT count(*) FROM app.memo WHERE client_uuid = '${m3.memo}'::uuid")}/${sqlLong("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '${m3.memo}'::uuid")}", "expected" to "0/0")
        hop("the helpdesk holds the memo in the quarantine", "open quarantine rows" to sqlLong("SELECT count(*) FROM app.sync_quarantine WHERE client_uuid = '${m3.memo}'::uuid AND status = 'open'"), "expected" to 1L)
        val money = send(sr, listOf(parts3[0]), uuid()).body!!.path("server_totals").single { it.path("business_date").asText() == day }.path("money")
        hop("meanwhile server_totals count the first memo only (gross and active memos)", "server" to "${money.path("gross_mtk").asLong()}/${money.path("active_memo_count").asInt()}", "device" to "${memo.gross}/1")
    }
}
