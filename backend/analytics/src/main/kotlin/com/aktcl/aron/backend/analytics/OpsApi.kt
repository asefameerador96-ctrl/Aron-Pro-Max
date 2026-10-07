package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

@Serializable
data class SyncHealthRow(
    val user_id: Long, val username: String, val route_ids: List<Long>, val device_id: Long, val device_model: String?, val app_version: String,
    val last_contact_at: String?, val last_batch_at: String?, val pending_rows_reported: Int, val last_sync_error: String?, val rejected_count: Int,
    val quarantined_count: Int, val submit_count_mismatch: Boolean?, val held_rows_alert: Boolean, val trust_level: String? = null, val sync_p95_s: Double?,
)

@Serializable
data class PendingPhotos(val count: Int, val oldest_age_s: Long?)

@Serializable
data class SyncHealthSummary(
    val devices: Int, val devices_with_pending: Int, val held_rows_alerts: Int, val rejected: Int, val quarantined: Int, val mismatched_route_days: Int,
    val config_ack_pct: Double? = null, val pending_photos: PendingPhotos? = null, val quarantine_backlog: Int? = null,
)

@Serializable
data class SyncHealthZone(
    val zone_id: Long, val login_pct: Double?, val submit_pct: Double?, val final_submitted: Boolean, val trickle_p95_s: Double?, val quarantined: Int,
    val pending_photos: Int, val config_ack_pct: Double?,
)

@Serializable
data class SyncHealthPage(val as_of: String, val summary: SyncHealthSummary, val items: List<SyncHealthRow>, val next_cursor: String? = null, val by_zone: List<SyncHealthZone> = emptyList())

@Serializable
data class RouteDayStateDto(
    val route_id: Long, val business_date: String, val state: String, val planned: Boolean, val submit_cycle: Int, val submit_voided: Boolean, val submit_count_mismatch: Boolean?,
    val logged_in_at: String?, val sales_submitted_at: String?, val final_submitted_at: String?, val rows_awaited: Int?, val route_code: String?, val route_name: String?,
    val assigned_user_id: Long?, val acting_user_id: Long?,
)

@Serializable
data class LoginSubmitStatus(
    val as_of: String, val business_date: String, val kpis: DashboardKpis, val not_logged_in: List<RouteDayStateDto>, val logged_in_not_submitted: List<RouteDayStateDto>,
    val submitted: List<RouteDayStateDto>, val exceptions: List<RouteDayStateDto>, val zones: List<ZoneFinalDto> = emptyList(),
)

@Serializable
data class ZoneFinalDto(val zone_id: Long, val final_submitted: Boolean)

@Serializable
data class QuarantineItemDto(
    val quarantine_id: Long, val client_uuid: String, val type: String, val code: String, val status: String, val user_id: Long, val device_id: Long?, val route_id: Long?,
    val business_date: String, val received_at: String, val payload: JsonElement, val detail: String?, val resolved_by_user_id: Long?, val resolved_at: String?, val resolution_note: String?,
)

@Serializable
data class QuarantinePage(val items: List<QuarantineItemDto>, val next_cursor: String? = null)

@Serializable
data class QuarantineResolveRequest(val action: String, val fixed_record: JsonElement? = null, val reason: String)

/** Accepting a quarantined record re-ingests it (the sync module owns that path). Until it is wired, accept and accept_with_fix answer 503. */
fun interface QuarantineAcceptor {
    fun accept(quarantineId: Long, fixedRecord: JsonObject?, resolver: Long, reason: String)
    companion object {
        val UNAVAILABLE = QuarantineAcceptor { _, _, _, _ -> throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "accepting a quarantined record needs the ingest hook (docs/requests/backend-reports-quarantine-accept.md)", retryAfterS = 60, headers = mapOf("Retry-After" to "60")) }
    }
}

/** Operations views (F-SYS-026, F-API-028): sync health per device, Login and Sales Submit lists, the quarantine queue and its actions. */
class OpsService(
    private val db: Database, private val config: ServerConfig, private val clock: AronClock = AronClock.SYSTEM, private val acceptor: QuarantineAcceptor = QuarantineAcceptor.UNAVAILABLE,
) {
    private fun heldHours(): Long = runCatching { config.int("cfg.sla.pending_rows_alert_h") }.getOrDefault(4).toLong()

    // ---------------- sync health ----------------

    private val healthBase = """
        days AS (SELECT rd.route_id, coalesce(rd.acting_user_id, rd.assigned_user_id) AS uid, coalesce(rd.submit_count_mismatch, false) AS mism, rd.last_batch_at
                   FROM app.route_day rd JOIN dw.dim_geo g ON g.route_id = rd.route_id
                  WHERE rd.business_date = :d AND rd.planned AND %ZONE%),
        team AS (SELECT uid, array_agg(route_id ORDER BY route_id) AS routes, bool_or(mism) AS mism, max(last_batch_at) AS lb FROM days WHERE uid IS NOT NULL GROUP BY uid),
        rows AS (
          SELECT u.id AS user_id, u.username, t.routes, dv.id AS device_id, dv.device_info ->> 'model' AS model, coalesce(dv.app_version, '0.0.0') AS app_version, dv.last_contact_at,
                 coalesce(t.lb, (SELECT max(sb.received_at) FROM app.sync_batch sb WHERE sb.device_id = dv.id)) AS last_batch_at, coalesce(dv.pending_rows_reported, 0) AS pending,
                 (SELECT count(*) FROM app.sync_rejected r WHERE r.user_id = u.id AND r.business_date = :d AND r.stored_at IS NULL AND (r.route_id IS NULL OR r.route_id IN (SELECT gz.route_id FROM dw.dim_geo gz WHERE %ZONEGZ%)))::int AS rejected,
                 (SELECT count(*) FROM app.sync_quarantine q WHERE q.user_id = u.id AND q.business_date = :d AND q.status = 'open' AND (q.route_id IS NULL OR q.route_id IN (SELECT gz.route_id FROM dw.dim_geo gz WHERE %ZONEGZ%)))::int AS quarantined,
                 t.mism, (coalesce(dv.pending_rows_reported, 0) > 0 AND (dv.last_contact_at IS NULL OR dv.last_contact_at < :held_before OR CAST(:past_close AS boolean))) AS held, dv.trust_level,
                 (SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM m.received_at - m.committed_at)) FROM dw.fact_memo m WHERE m.user_id = u.id AND m.business_date = :d)::float8 AS p95
            FROM team t JOIN app.app_user u ON u.id = t.uid JOIN app.device_binding b ON b.user_id = t.uid AND b.status = 'active' JOIN app.device dv ON dv.id = b.device_id)
    """

    fun syncHealth(reach: Reach, date: LocalDate, level: String?, nodeId: Long?, onlyProblems: Boolean, limit: Int, after: Pair<Long, Long>?): SyncHealthPage {
        val heldBefore = clock.now().minus(Duration.ofHours(heldHours()))
        // F-SYS-084: past 17:30 Dhaka on the business date (the day's upload deadline), a phone still reporting pending rows is held too.
        val pastClose = !clock.now().isBefore(date.atTime(17, 30).atZone(java.time.ZoneId.of("Asia/Dhaka")).toInstant())
        return db.readJdbi.withHandle<SyncHealthPage, Exception> { h ->
            val node = resolveScopedNode(h, reach, level, nodeId)
            val base = "WITH " + healthBase.replace("%ZONE%", node.clause("g.zone_id")).replace("%ZONEGZ%", node.clause("gz.zone_id"))
            fun q(sql: String) = node.bind(h.createQuery(sql).bind("d", date).bind("held_before", OffsetDateTime.ofInstant(heldBefore, java.time.ZoneOffset.UTC)).bind("past_close", pastClose))
            val s = q("$base SELECT count(DISTINCT device_id)::int, count(DISTINCT device_id) FILTER (WHERE pending > 0)::int, count(DISTINCT device_id) FILTER (WHERE held)::int, (SELECT coalesce(sum(r), 0) FROM (SELECT DISTINCT user_id, rejected AS r FROM rows) x)::int, (SELECT coalesce(sum(q), 0) FROM (SELECT DISTINCT user_id, quarantined AS q FROM rows) y)::int, (SELECT count(*) FROM days WHERE mism)::int FROM rows")
                .map { rs, _ -> SyncHealthSummary(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)) }.one()
            val problem = if (onlyProblems) "AND (pending > 0 OR rejected > 0 OR quarantined > 0 OR coalesce(mism, false) OR held)" else ""
            val cursor = if (after == null) "" else "AND (user_id, device_id) > (:cu, :cd)"
            val st = q("$base SELECT * FROM rows WHERE true $problem $cursor ORDER BY user_id, device_id LIMIT :lim").bind("lim", limit + 1)
            if (after != null) st.bind("cu", after.first).bind("cd", after.second)
            val rows = st.map { rs, _ ->
                SyncHealthRow(
                    rs.getLong("user_id"), rs.getString("username"), (rs.getArray("routes").array as Array<*>).map { (it as Number).toLong() }, rs.getLong("device_id"), rs.getString("model"),
                    rs.getString("app_version"), rs.getObject("last_contact_at", OffsetDateTime::class.java)?.toInstant()?.wire(), rs.getObject("last_batch_at", OffsetDateTime::class.java)?.toInstant()?.wire(),
                    rs.getInt("pending"), null, rs.getInt("rejected"), rs.getInt("quarantined"), rs.getBoolean("mism"), rs.getBoolean("held"), rs.getString("trust_level"),
                    (rs.getObject("p95") as Double?)?.let { Math.round(it * 100) / 100.0 },
                )
            }.list()
            val page = rows.take(limit)
            val next = if (rows.size > limit) page.last().let { Base64.getUrlEncoder().withoutPadding().encodeToString("${it.user_id}|${it.device_id}".toByteArray()) } else null
            val extras = healthExtras(h, node, reach, level, date)
            SyncHealthPage(clock.now().wire(), s.copy(config_ack_pct = extras.first.configAckPct, pending_photos = extras.first.pendingPhotos, quarantine_backlog = extras.first.backlog), page, next, extras.second)
        }
    }

    private class HealthSummaryExtras(val configAckPct: Double?, val pendingPhotos: PendingPhotos, val backlog: Int)

    /**
     * v1.2 additions (F-WEB-045): config ack share, pending photos (media rows still `pending_blob`, attributed to the zone of their user's route that
     * day), unresolved quarantine backlog, and the per-zone breakdown. Zone figures come from dw.agg_daily_zone; percentages as DashboardKpis.
     */
    private fun healthExtras(h: org.jdbi.v3.core.Handle, node: ScopedNode, reach: Reach, level: String?, date: LocalDate): Pair<HealthSummaryExtras, List<SyncHealthZone>> {
        val now = OffsetDateTime.ofInstant(clock.now(), java.time.ZoneOffset.UTC)
        val zones = node.bind(h.createQuery("SELECT DISTINCT g.zone_id FROM dw.dim_geo g WHERE ${node.clause("g.zone_id")} ORDER BY 1")).mapTo(Long::class.java).list()
        val version = h.createQuery("SELECT max(config_version) FROM app.cfg_version").mapTo(Long::class.java).findOne().orElse(null) ?: 0L
        val photoSql = """
            FROM app.media m JOIN app.route_day rd ON coalesce(rd.acting_user_id, rd.assigned_user_id) = m.user_id AND rd.business_date = m.business_date
            JOIN dw.dim_geo g ON g.route_id = rd.route_id WHERE m.status = 'pending_blob' AND m.business_date = :d AND ${node.clause("g.zone_id")}
        """
        val photos = node.bind(h.createQuery("SELECT count(DISTINCT m.id)::int, min(m.received_at) $photoSql").bind("d", date)).map { rs, _ ->
            PendingPhotos(rs.getInt(1), rs.getObject(2, OffsetDateTime::class.java)?.let { Math.max(0, Duration.between(it, now).seconds) })
        }.one()
        val photosByZone = node.bind(h.createQuery("SELECT g.zone_id, count(DISTINCT m.id)::int $photoSql GROUP BY 1").bind("d", date)).map { rs, _ -> rs.getLong(1) to rs.getInt(2) }.list().toMap()
        val openQ = "FROM app.sync_quarantine q JOIN dw.dim_geo g ON g.route_id = q.route_id WHERE q.status = 'open' AND ${node.clause("g.zone_id")}"
        val routeless = if (reach.national && level == null) h.createQuery("SELECT count(*)::int FROM app.sync_quarantine WHERE status = 'open' AND route_id IS NULL").mapTo(Int::class.java).one() else 0
        val backlog = node.bind(h.createQuery("SELECT count(*)::int $openQ")).mapTo(Int::class.java).one() + routeless
        val qByZone = node.bind(h.createQuery("SELECT g.zone_id, count(*)::int $openQ AND q.business_date = :d GROUP BY 1").bind("d", date)).map { rs, _ -> rs.getLong(1) to rs.getInt(2) }.list().toMap()
        val zrows = node.bind(h.createQuery("SELECT zone_id, target_routes, logged_in_routes, sales_submitted_routes, final_submitted FROM dw.agg_daily_zone a WHERE business_date = :d AND ${node.clause("a.zone_id")}").bind("d", date))
            .map { rs, _ -> rs.getLong(1) to listOf(rs.getInt(2), rs.getInt(3), rs.getInt(4), if (rs.getBoolean(5)) 1 else 0) }.list().toMap()
        val p95 = node.bind(h.createQuery("SELECT zone_id, percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM received_at - committed_at))::float8 FROM dw.fact_memo m WHERE business_date = :d AND ${node.clause("m.zone_id")} GROUP BY 1").bind("d", date))
            .map { rs, _ -> rs.getLong(1) to rs.getDouble(2) }.list().toMap()
        fun pct(n: Int, d: Int): Double? = if (d > 0) BigDecimal(n).multiply(BigDecimal(100)).divide(BigDecimal(d), 2, java.math.RoundingMode.HALF_UP).toDouble() else null
        val byZone = zones.map { z ->
            val a = zrows[z]
            SyncHealthZone(z, a?.let { pct(it[1], it[0]) }, a?.let { pct(it[2], it[1]) }, a?.get(3) == 1, p95[z]?.let { Math.round(it * 100) / 100.0 }, qByZone[z] ?: 0, photosByZone[z] ?: 0, ackPct(h, version, date, listOf(z)))
        }
        return HealthSummaryExtras(ackPct(h, version, date, zones), photos, backlog) to byZone
    }

    /** Percentage of selling phones (those with a contact that day) whose latest applied config version is at least [version]; null when none sold. */
    fun configAckPct(version: Long, date: LocalDate, zones: List<Long>? = null): Double? = db.readJdbi.withHandle<Double?, Exception> { h -> ackPct(h, version, date, zones) }

    private fun ackPct(h: org.jdbi.v3.core.Handle, version: Long, date: LocalDate, zones: List<Long>?): Double? {
        if (zones != null && zones.isEmpty()) return null
        return h.createQuery(
            """
            WITH sellers AS (SELECT DISTINCT b.device_id FROM app.device_binding b JOIN app.route_day rd ON coalesce(rd.acting_user_id, rd.assigned_user_id) = b.user_id AND rd.business_date = :d AND rd.planned
                              JOIN dw.dim_geo g ON g.route_id = rd.route_id WHERE b.status = 'active' AND (CAST(:all AS boolean) OR g.zone_id = ANY(:zones)))
            SELECT round(100.0 * count(*) FILTER (WHERE coalesce(dv.config_version_applied, 0) >= :v) / nullif(count(*), 0), 2)
              FROM sellers s JOIN app.device dv ON dv.id = s.device_id WHERE (dv.last_contact_at AT TIME ZONE 'Asia/Dhaka')::date >= :d
            """,
        ).bind("d", date).bind("v", version).bind("all", zones == null).bindArray("zones", Long::class.javaObjectType, zones ?: listOf(-1L)).map { rs, _ -> rs.getObject(1) as java.math.BigDecimal? }.one()?.toDouble()
    }

    // ---------------- login and submit lists ----------------

    fun loginSubmit(reach: Reach, dashboards: DashboardService, date: LocalDate, level: String?, nodeId: Long?): LoginSubmitStatus {
        val summary = dashboards.summary(reach, level, nodeId, date, date)
        val days = db.readJdbi.withHandle<List<Triple<RouteDayStateDto, Boolean, Boolean>>, Exception> { h ->
            val node = resolveScopedNode(h, reach, level, nodeId)
            node.bind(
                h.createQuery(
                    """
                    SELECT rd.*, g.route_code, g.route_name,
                           EXISTS (SELECT 1 FROM app.day_exception e WHERE e.status = 'approved' AND e.voided_at IS NULL AND rd.route_id = ANY (e.route_ids) AND rd.business_date BETWEEN e.from_date AND e.to_date) AS excused
                      FROM app.route_day rd JOIN dw.dim_geo g ON g.route_id = rd.route_id WHERE rd.business_date = :d AND rd.planned AND ${node.clause("g.zone_id")} ORDER BY g.route_code
                    """,
                ).bind("d", date),
            ).map { rs, _ ->
                fun t(c: String) = rs.getObject(c, OffsetDateTime::class.java)?.toInstant()?.wire()
                Triple(
                    RouteDayStateDto(rs.getLong("route_id"), date.toString(), rs.getString("state"), rs.getBoolean("planned"), rs.getInt("submit_cycle"), rs.getBoolean("submit_voided"), rs.getObject("submit_count_mismatch") as Boolean?,
                        t("logged_in_at"), t("sales_submitted_at"), t("final_submitted_at"), rs.getObject("rows_awaited") as Int?, rs.getString("route_code"), rs.getString("route_name"),
                        rs.getObject("assigned_user_id") as Long?, rs.getObject("acting_user_id") as Long?),
                    rs.getBoolean("excused"), rs.getString("state") in setOf("sales_submitted", "final_submitted"),
                )
            }.list()
        }
        val live = days.filter { !it.second }
        val zones = db.readJdbi.withHandle<List<ZoneFinalDto>, Exception> { h ->
            val node = resolveScopedNode(h, reach, level, nodeId)
            node.bind(h.createQuery("SELECT zone_id, final_submitted FROM dw.agg_daily_zone a WHERE business_date = :d AND target_routes > 0 AND ${node.clause("a.zone_id")} ORDER BY zone_id").bind("d", date))
                .map { rs, _ -> ZoneFinalDto(rs.getLong(1), rs.getBoolean(2)) }.list()
        }
        return LoginSubmitStatus(
            summary.as_of, date.toString(), summary.kpis,
            live.filter { it.first.state == "not_started" }.map { it.first }, live.filter { it.first.state != "not_started" && !it.third }.map { it.first },
            live.filter { it.third }.map { it.first }, days.filter { it.second }.map { it.first }, zones,
        )
    }

    // ---------------- quarantine ----------------

    fun quarantine(reach: Reach, status: String?, code: String?, zoneId: Long?, from: LocalDate?, to: LocalDate?, limit: Int, afterId: Long?): QuarantinePage = db.readJdbi.withHandle<QuarantinePage, Exception> { h ->
        if (zoneId != null && !reach.coversZone(zoneId)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "zone is outside your reach")
        val where = mutableListOf("true")
        if (!reach.national) where += "q.route_id IN (SELECT route_id FROM dw.dim_geo WHERE zone_id = ANY(:rz))"
        if (zoneId != null) where += "q.route_id IN (SELECT route_id FROM dw.dim_geo WHERE zone_id = :zid)"
        if (status != null) where += "q.status = :status"
        if (code != null) where += "q.code = :code"
        if (from != null) where += "q.business_date >= :from"
        if (to != null) where += "q.business_date <= :to"
        if (afterId != null) where += "q.id < :after"
        val st = h.createQuery("SELECT q.* FROM app.sync_quarantine q WHERE ${where.joinToString(" AND ")} ORDER BY q.id DESC LIMIT :lim").bind("lim", limit + 1)
        if (!reach.national) st.bindArray("rz", Long::class.javaObjectType, reach.zoneIds.toList().ifEmpty { listOf(-1L) })
        zoneId?.let { st.bind("zid", it) }; status?.let { st.bind("status", it) }; code?.let { st.bind("code", it) }; from?.let { st.bind("from", it) }; to?.let { st.bind("to", it) }; afterId?.let { st.bind("after", it) }
        val rows = st.map { rs, _ -> toDto(rs) }.list()
        QuarantinePage(rows.take(limit), if (rows.size > limit) rows[limit - 1].quarantine_id.toString() else null)
    }

    private fun toDto(rs: java.sql.ResultSet): QuarantineItemDto {
        fun t(c: String) = rs.getObject(c, OffsetDateTime::class.java)?.toInstant()?.wire()
        return QuarantineItemDto(
            rs.getLong("id"), rs.getString("client_uuid"), rs.getString("record_type"), rs.getString("code"), rs.getString("status"), rs.getLong("user_id"), rs.getObject("device_id") as Long?,
            rs.getObject("route_id") as Long?, rs.getObject("business_date", LocalDate::class.java).toString(), t("received_at")!!, Json.parseToJsonElement(rs.getString("payload")), rs.getString("detail"),
            rs.getObject("resolved_by_user_id") as Long?, t("resolved_at"), rs.getString("resolution_note"),
        )
    }

    /** discard and return_to_device end the item here (the phone sees the decision in `resolutions[]`); accept and accept_with_fix go through the ingest hook. Every action is audited. */
    fun resolve(p: com.aktcl.aron.backend.platform.AronPrincipal, reach: Reach, id: Long, req: QuarantineResolveRequest, requestId: String?): QuarantineItemDto {
        if (req.action !in setOf("accept", "accept_with_fix", "discard", "return_to_device")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad action", errors = listOf(FieldError("/action", "invalid_value")))
        if (req.reason.trim().length !in 10..500) throw ApiProblem(ProblemCode.ERR_VALIDATION, "reason must be 10..500 characters", errors = listOf(FieldError("/reason", "out_of_range")))
        if (req.action == "accept_with_fix" && (req.fixed_record == null || req.fixed_record is JsonNull)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "fixed_record is required", errors = listOf(FieldError("/fixed_record", "required")))
        return db.jdbi.inTransaction<QuarantineItemDto, Exception> { h ->
            val row = h.createQuery("SELECT q.*, (SELECT zone_id FROM dw.dim_geo WHERE route_id = q.route_id) AS zid FROM app.sync_quarantine q WHERE q.id = :id FOR UPDATE").bind("id", id)
                .map { rs, _ -> toDto(rs) to (rs.getObject("zid") as Long?) }.findOne().orElse(null)
            // Unknown and outside the reach look the same.
            if (row == null || (!reach.national && (row.second == null || row.second !in reach.zoneIds))) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such quarantine item")
            if (row.first.status != "open") {
                // The same actor repeating the same decision gets the stored outcome (200); any other transition is a state conflict.
                val note = row.first.resolution_note.orEmpty()
                val storedAction = when (row.first.status) { "accepted" -> "accept"; "accepted_with_fix" -> "accept_with_fix"; else -> if (note.startsWith("return_to_device")) "return_to_device" else "discard" }
                if (row.first.resolved_by_user_id == p.userId && storedAction == req.action) return@inTransaction row.first
                throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "already resolved as ${row.first.status}")
            }
            when (req.action) {
                "accept", "accept_with_fix" -> { acceptor.accept(id, req.fixed_record as? JsonObject, p.userId, req.reason) }
                else -> h.createUpdate(
                    "UPDATE app.sync_quarantine SET status = 'discarded', resolution_uuid = :ru, resolved_by_user_id = :u, resolved_at = now(), resolution_note = :note WHERE id = :id",
                ).bind("ru", UUID.nameUUIDFromBytes("quarantine:$id:${req.action}:${p.userId}".toByteArray())).bind("u", p.userId).bind("note", (if (req.action == "return_to_device") "return_to_device: " else "") + req.reason.take(470)).bind("id", id).execute()
            }
            h.createUpdate(
                "INSERT INTO app.audit_log (actor_user_id, actor_username, actor_role, via, entity, entity_id, action, before, after, reason, request_id) VALUES (:u, :n, :r, 'api', 'quarantine', :id, :a, CAST(:b AS jsonb), CAST(:af AS jsonb), :reason, CAST(:rid AS uuid))",
            ).bind("u", p.userId).bind("n", p.username.take(40)).bind("r", p.role.wire).bind("id", id.toString()).bind("a", req.action)
                .bind("b", buildJsonObject { put("status", "open"); put("code", row.first.code); put("client_uuid", row.first.client_uuid) }.toString())
                .bind("af", buildJsonObject { put("status", if (req.action.startsWith("accept")) (if (req.action == "accept") "accepted" else "accepted_with_fix") else "discarded"); put("action", req.action) }.toString())
                .bind("reason", req.reason.take(500)).bind("rid", requestId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }).execute()
            h.createQuery("SELECT * FROM app.sync_quarantine WHERE id = :id").bind("id", id).map { rs, _ -> toDto(rs) }.one()
        }
    }
}

class OpsDeps(val service: OpsService, val dashboards: DashboardService, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val HEALTH_ROLES = DASHBOARD_ROLES + Role.SUPPORT
private val QUARANTINE_READERS = setOf(Role.ADMIN, Role.SUPERADMIN, Role.SUPPORT)
private val QUARANTINE_WRITERS = setOf(Role.ADMIN, Role.SUPERADMIN)

fun Route.opsRoutes(d: OpsDeps) {
    fun io.ktor.server.application.ApplicationCall.date(name: String, required: Boolean): LocalDate? = request.queryParameters[name]?.let {
        runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $name", errors = listOf(FieldError("query.$name", "invalid_value")))
    } ?: if (required) throw ApiProblem(ProblemCode.ERR_VALIDATION, "$name is required", errors = listOf(FieldError("query.$name", "required"))) else null
    fun io.ktor.server.application.ApplicationCall.limit(): Int = request.queryParameters["limit"]?.let {
        it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad limit", errors = listOf(FieldError("query.limit", "out_of_range")))
    } ?: 100
    fun io.ktor.server.application.ApplicationCall.nodeId(): Long? = request.queryParameters["node_id"]?.let {
        it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad node_id", errors = listOf(FieldError("query.node_id", "invalid_value")))
    }

    authenticated(d.guard) {
        get("/dashboards/sync-health") {
            val p = call.principal
            if (p.role !in HEALTH_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "sync health is not available to this role")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            val q = call.request.queryParameters
            val after = q["cursor"]?.let { c -> runCatching { String(Base64.getUrlDecoder().decode(c)).split('|').let { it[0].toLong() to it[1].toLong() } }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad cursor", errors = listOf(FieldError("query.cursor", "invalid_value"))) }
            call.respond(d.service.syncHealth(d.reach.reach(p.userId, p.role, p.scopeVersion, today), call.date("business_date", true)!!, q["level"], call.nodeId(), q["only_problems"] == "true", call.limit(), after))
        }
        get("/dashboards/login-submit") {
            val p = call.principal
            if (p.role !in DASHBOARD_ROLES) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "dashboards are not available to this role")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            call.respond(d.service.loginSubmit(d.reach.reach(p.userId, p.role, p.scopeVersion, today), d.dashboards, call.date("business_date", true)!!, call.request.queryParameters["level"], call.nodeId()))
        }
        get("/admin/quarantine") {
            val p = call.principal
            if (p.role !in QUARANTINE_READERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the quarantine queue is for operations roles")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            val q = call.request.queryParameters
            val zone = q["zone_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad zone_id", errors = listOf(FieldError("query.zone_id", "invalid_value"))) }
            val after = q["cursor"]?.let { it.toLongOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad cursor", errors = listOf(FieldError("query.cursor", "invalid_value"))) }
            q["status"]?.let { if (it !in setOf("open", "accepted", "accepted_with_fix", "discarded")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad status", errors = listOf(FieldError("query.status", "invalid_value"))) }
            call.respond(d.service.quarantine(d.reach.reach(p.userId, p.role, p.scopeVersion, today), q["status"], q["code"], zone, call.date("from", false), call.date("to", false), call.limit(), after))
        }
        post("/admin/quarantine/{quarantine_id}/resolve") {
            val p = call.principal
            if (p.role !in QUARANTINE_WRITERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "resolving quarantine is for ADMIN and SUPERADMIN")
            val id = call.parameters["quarantine_id"]?.toLongOrNull()?.takeIf { it >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad quarantine_id", errors = listOf(FieldError("path.quarantine_id", "invalid_value")))
            val req = call.receiveStrict(QuarantineResolveRequest.serializer())
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            call.respond(d.service.resolve(p, d.reach.reach(p.userId, p.role, p.scopeVersion, today), id, req, call.requestId))
        }
    }
}
