package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.wire
import kotlinx.serialization.Serializable
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * Contract 1.9.0 `SalesSubmitDigest` (D-NIGHT-DAYCLOSE-01; shapes in docs/requests/contract-v1.9-shapes-backend-core.md):
 * the `digest` member of GET /v1/sync/totals, filled by `IngestService.totals` with the principal's device. Hand-written
 * DTOs (as BatchDtos.kt); ContractConformanceTest checks the served shape against openapi.yaml.
 */

@Serializable
data class DigestDeviceCountsDto(val received: Int, val acknowledged: Int)

@Serializable
data class DigestLoginCountsDto(val acknowledged: Int)

@Serializable
data class DigestTypeCountsDto(val device: DigestDeviceCountsDto?, val login: DigestLoginCountsDto)

@Serializable
data class RejectedRecordViewDto(
    val client_uuid: String,
    val type: String,
    val business_date: String,
    val status: String,
    val code: String,
    val final: Boolean,
    val from_this_device: Boolean,
    val memo_no: String?,
    val outlet_id: Long?,
    val last_received_at: String,
)

/** Contract 1.14.0 `SubmitBusinessTotals` (SRM-B-25): the day's business figures, computed with the dashboard aggregates' rules. */
@Serializable
data class CategoryQtyBaseDto(val category_code: String, val qty_base: Long)

@Serializable
data class SubmitBusinessTotalsDto(
    val outlets_visited: Int,
    val sold: List<CategoryQtyBaseDto>,
    val stock_issued: List<CategoryQtyBaseDto>,
    val qc: List<CategoryQtyBaseDto>,
    val promotion_mtk: Long,
)

@Serializable
data class SalesSubmitDigestDto(
    val business_date: String,
    val device_uuid: String?,
    val by_type: Map<String, DigestTypeCountsDto>,
    val rejected: List<RejectedRecordViewDto>,
    val rejected_truncated: Boolean,
    val business_totals: SubmitBusinessTotalsDto? = null,
)

/**
 * The Sales Submit compare for one login and business date (D-NIGHT-DAYCLOSE-01), from `app.ingest_registry` (the
 * identity of every row the server judged, one per client_uuid, its status following later outcomes):
 * - `login.acknowledged`: stored rows (`accepted` or `voided`) of the user on the date from every device and install;
 *   equals `ServerTotals.by_type.accepted`.
 * - `device`: the same user and date, only rows the calling phone uploaded ([deviceId] from the token's device proof);
 *   `received` is any outcome, `acknowledged` stored. Null for a caller without a phone (web).
 * - `rejected`: the user's rows of the date still not stored (registry `rejected`, `parked`, `quarantined`), newest first,
 *   at most [MAX_REJECTED]. `final`: `rejected`; a parked row past its TTL (`parked_until` passed, or `finalised_at` set); a quarantine
 *   item discarded with none open (payload_conflict copies left out). `memo_no` is the row's own (`memo_no`, `against_memo_no`) or that of the memo it names
 *   (stored memo, else the memo's own refused or held copy); `outlet_id` from the payload.
 * Never another user's rows: the user comes from the token only.
 */
class SalesSubmitDigestService(private val db: Database, private val clock: com.aktcl.aron.backend.platform.AronClock = com.aktcl.aron.backend.platform.AronClock.SYSTEM) {
    fun digest(userId: Long, deviceId: Long?, deviceUuid: String?, date: LocalDate): SalesSubmitDigestDto =
        db.jdbi.withHandle<SalesSubmitDigestDto, Exception> { h -> digest(h, userId, deviceId, deviceUuid, date) }

    fun digest(h: Handle, userId: Long, deviceId: Long?, deviceUuid: String?, date: LocalDate): SalesSubmitDigestDto {
        // The device half only for a device of this user whose uuid is the one given (both come from the token's proof).
        val dev = if (deviceId == null || deviceUuid == null) null else h.createQuery(
            "SELECT d.id FROM app.device d WHERE d.id = :id AND d.device_uuid = CAST(:du AS uuid) AND EXISTS (SELECT 1 FROM app.device_binding b WHERE b.device_id = d.id AND b.user_id = :u)",
        ).bind("id", deviceId).bind("du", deviceUuid).bind("u", userId).mapTo(Long::class.java).findOne().orElse(null)
        val byType = h.createQuery(
            """
            SELECT record_type,
                   count(*) FILTER (WHERE status IN ('accepted', 'voided')),
                   count(*) FILTER (WHERE device_id = :dev),
                   count(*) FILTER (WHERE device_id = :dev AND status IN ('accepted', 'voided'))
            FROM app.ingest_registry WHERE user_id = :u AND business_date = :d
            GROUP BY record_type ORDER BY record_type
            """.trimIndent(),
        ).bind("u", userId).bind("d", date).bind("dev", dev).map { rs, _ ->
            rs.getString(1) to DigestTypeCountsDto(
                device = if (dev == null) null else DigestDeviceCountsDto(received = rs.getInt(3), acknowledged = rs.getInt(4)),
                login = DigestLoginCountsDto(acknowledged = rs.getInt(2)),
            )
        }.list().toMap(sortedMapOf())
        val rows = h.createQuery(
            """
            SELECT r.client_uuid::text AS cu, r.record_type, r.status, r.outcome_code, r.device_id, r.last_seen_at,
                   COALESCE(sr.payload, sq.payload) AS env,
                   sr.finalised_at, sr.parked_until, sr.retryable, sq.open_items, sq.discarded_items
            FROM app.ingest_registry r
            LEFT JOIN LATERAL (
                SELECT payload, finalised_at, parked_until, retryable FROM app.sync_rejected x
                WHERE x.client_uuid = r.client_uuid AND x.user_id = r.user_id AND x.stored_at IS NULL ORDER BY x.last_received_at DESC, x.id DESC LIMIT 1
            ) sr ON r.status IN ('rejected', 'parked')
            LEFT JOIN LATERAL (
                SELECT (array_agg(payload ORDER BY received_at DESC, id DESC))[1] AS payload,
                       count(*) FILTER (WHERE status = 'open') AS open_items, count(*) FILTER (WHERE status = 'discarded') AS discarded_items
                FROM app.sync_quarantine q WHERE q.client_uuid = r.client_uuid AND q.user_id = r.user_id AND q.code <> 'payload_conflict'
            ) sq ON r.status = 'quarantined'
            WHERE r.user_id = :u AND r.business_date = :d AND r.status IN ('rejected', 'parked', 'quarantined')
            ORDER BY r.last_seen_at DESC, r.client_uuid
            LIMIT :lim
            """.trimIndent(),
        ).bind("u", userId).bind("d", date).bind("lim", MAX_REJECTED + 1).map { rs, _ ->
            val now = clock.now()
            val status = rs.getString("status")
            val final = when (status) {
                "rejected" -> true
                "parked" -> rs.getObject("finalised_at") != null || rs.getObject("retryable") == false ||
                    rs.getObject("parked_until", OffsetDateTime::class.java)?.toInstant()?.let { !it.isAfter(now) } == true
                else -> rs.getLong("open_items") == 0L && rs.getLong("discarded_items") > 0L
            }
            val env = rs.getString("env")?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
            val payload = env?.get("payload") as? kotlinx.serialization.json.JsonObject
            Row(
                RejectedRecordViewDto(
                    client_uuid = rs.getString("cu"), type = rs.getString("record_type"), business_date = date.toString(), status = status,
                    code = rs.getString("outcome_code") ?: "server_error", final = final,
                    from_this_device = dev != null && (rs.getObject("device_id") as Long?) == dev,
                    memo_no = payload?.let { it.str("memo_no") ?: it.str("against_memo_no") }?.takeIf { MEMO_NO.matches(it) },
                    outlet_id = payload?.long("outlet_id")?.takeIf { it >= 1 },
                    last_received_at = rs.getObject("last_seen_at", OffsetDateTime::class.java).toInstant().wire(),
                ),
                memoRef = payload?.let { it.str("memo_client_uuid") ?: it.str("against_memo_client_uuid") }?.takeIf { UUID_RE.matches(it) },
            )
        }.list()
        val refs = rows.filter { it.view.memo_no == null && it.memoRef != null }.map { it.memoRef!! }.distinct()
        val refNo = if (refs.isEmpty()) emptyMap() else memoNumbers(h, userId, refs)
        val views = rows.take(MAX_REJECTED).map { r -> if (r.view.memo_no != null || r.memoRef == null) r.view else r.view.copy(memo_no = refNo[r.memoRef]) }
        return SalesSubmitDigestDto(date.toString(), deviceUuid?.takeIf { dev != null }, byType, views, rows.size > MAX_REJECTED, businessTotals(h, userId, date))
    }

    /**
     * Contract 1.14.0 (SRM-B-25). The same rules as `Aggregator` (`dw.agg_daily_route`, `agg_daily_route_sku`), read straight from the
     * stored rows of the login and date rather than from the worker's summaries, so the figure never lags the sync it is compared with:
     * a visit counts when it is an `sr_call` and not abandoned; a memo when active with a line; sold quantity is the `sale` lines only;
     * issued stock is `stock_movement` kind `issue`; QC is the stored `qc_line` quantity; promotion is offer + DRP discount.
     */
    fun businessTotals(h: Handle, userId: Long, date: LocalDate): SubmitBusinessTotalsDto {
        val visited = h.createQuery(
            "SELECT count(DISTINCT outlet_id) FROM app.visit WHERE user_id = :u AND business_date = :d AND visit_kind = 'sr_call' AND voided_at IS NULL AND outcome_code IS DISTINCT FROM 'abandoned'",
        ).bind("u", userId).bind("d", date).mapTo(Int::class.java).one()
        fun byCategory(sql: String) = h.createQuery(sql).bind("u", userId).bind("d", date)
            .map { rs, _ -> CategoryQtyBaseDto(rs.getString("cat"), rs.getLong("q")) }.list()
        val sold = byCategory(
            """
            SELECT coalesce(s.category_code, '') AS cat, sum(ml.qty_base)::bigint AS q
              FROM app.memo_line ml
              JOIN app.memo m ON m.client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
              JOIN app.sku s ON s.id = ml.sku_id
             WHERE m.user_id = :u AND m.business_date = :d AND m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL
               AND ml.voided_at IS NULL AND ml.line_kind = 'sale'
             GROUP BY 1 HAVING sum(ml.qty_base) > 0 ORDER BY 1
            """.trimIndent(),
        )
        val issued = byCategory(
            "SELECT coalesce(s.category_code, '') AS cat, sum(sm.qty_base)::bigint AS q FROM app.stock_movement sm JOIN app.sku s ON s.id = sm.sku_id " +
                "WHERE sm.user_id = :u AND sm.business_date = :d AND sm.voided_at IS NULL AND sm.kind IN ('issue', 'adjustment') GROUP BY 1 HAVING sum(sm.qty_base) <> 0 ORDER BY 1",
        )
        val qc = byCategory(
            "SELECT coalesce(s.category_code, '') AS cat, sum(q.qty_base)::bigint AS q FROM app.qc_line q JOIN app.sku s ON s.id = q.sku_id " +
                "WHERE q.user_id = :u AND q.business_date = :d AND q.voided_at IS NULL GROUP BY 1 HAVING sum(q.qty_base) > 0 ORDER BY 1",
        )
        val promo = h.createQuery(
            "SELECT coalesce(sum(offer_discount_mtk + drp_discount_mtk), 0)::bigint FROM app.memo m WHERE m.user_id = :u AND m.business_date = :d AND m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL",
        ).bind("u", userId).bind("d", date).mapTo(Long::class.java).one()
        return SubmitBusinessTotalsDto(visited, sold, issued, qc, promo)
    }

    private class Row(val view: RejectedRecordViewDto, val memoRef: String?)

    /** The memo number of each named memo: the stored memo, else its own refused or held copy (the caller's own rows only). */
    private fun memoNumbers(h: Handle, userId: Long, uuids: List<String>): Map<String, String> = h.createQuery(
        """
        SELECT u::text, COALESCE(
            (SELECT m.memo_no FROM app.memo m WHERE m.client_uuid = u AND m.user_id = :user LIMIT 1),
            (SELECT x.payload -> 'payload' ->> 'memo_no' FROM app.sync_rejected x WHERE x.client_uuid = u AND x.user_id = :user ORDER BY x.last_received_at DESC LIMIT 1),
            (SELECT q.payload -> 'payload' ->> 'memo_no' FROM app.sync_quarantine q WHERE q.client_uuid = u AND q.user_id = :user ORDER BY q.received_at DESC LIMIT 1))
        FROM unnest(CAST(:u AS uuid[])) AS u
        """.trimIndent(),
    ).bindArray("u", String::class.java, uuids).bind("user", userId).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
        .mapNotNull { (k, v) -> v?.takeIf { MEMO_NO.matches(it) }?.let { k to it } }.toMap()

    companion object {
        const val MAX_REJECTED = 200
        private val MEMO_NO = Regex("^[a-z][a-z0-9]{3,31}-\\d{6}-\\d{3,4}$")
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
