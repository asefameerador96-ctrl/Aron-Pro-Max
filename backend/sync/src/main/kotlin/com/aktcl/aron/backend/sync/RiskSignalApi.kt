package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** Contract RiskSignal.last_review. */
@Serializable
data class RiskLastReviewDto(val action: String, val reviewer_user_id: Long, val note: String?, val at: String)

/** Contract RiskSignal. */
@Serializable
data class RiskSignalDto(
    val signal_id: Long, val code: String, val severity: Int, val business_date: String, val subject_type: String, val subject_id: String,
    val user_id: Long?, val route_id: Long?, val zone_id: Long?, val score: Double, val evidence: JsonObject, val status: String,
    val config_version: Long, val created_at: String, val last_review: RiskLastReviewDto?,
)

/** Contract RiskSignalPage. */
@Serializable
data class RiskSignalPageDto(val items: List<RiskSignalDto>, val next_cursor: String?)

/** Contract RiskReviewRequest. */
@Serializable
data class RiskReviewRequestDto(val review_uuid: String, val action: String, val note: String? = null)

class RiskSignalDeps(val db: Database, val config: ServerConfig, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/**
 * F-SYS-057 (contract listRiskSignals, reviewRiskSignal; docs/24 s11.4): supervisors read the server's risk signals and
 * review them. Field reps never see them (403). A signal is in reach when its zone (the signal's, else its route's, else
 * its user's home zone) is in the caller's zones, or its route is one of the caller's routes; national sees all; out of
 * reach is 404 on review and simply absent from the list. A review is an append-only `app.risk_signal_review` event,
 * idempotent by `review_uuid` (the same uuid again answers the signal unchanged; a uuid reused for another signal or
 * action is 409); the signal's status is the action of its latest review. Nothing is auto-reversed: no job ever changes
 * a reviewed status back.
 */
fun Route.riskSignalRoutes(d: RiskSignalDeps) {
    authenticated(d.guard) {
        get("/risk-signals") {
            val q = RiskQuery.parse(call.request.queryParameters, d.today())
            call.respond(withContext(Dispatchers.IO) { RiskSignals(d).page(call.principal, q) })
        }
        post("/risk-signals/{signal_id}/review") {
            val id = call.parameters["signal_id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "signal_id", errors = listOf(FieldError("path.signal_id", "invalid_value")))
            val req = call.receiveStrict(RiskReviewRequestDto.serializer())
            call.respond(withContext(Dispatchers.IO) { RiskSignals(d).review(call.principal, id, req) })
        }
    }
}

private fun RiskSignalDeps.today(): LocalDate = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()

internal data class RiskQuery(
    val from: LocalDate, val to: LocalDate, val status: String?, val code: String?, val minSeverity: Int?, val zoneId: Long?, val userId: Long?,
    val limit: Int, val after: Pair<LocalDate, Long>?,
) {
    companion object {
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val CURSOR = Regex("^[A-Za-z0-9_-]{1,512}$")
        val STATUSES = setOf("open", "reviewed", "dismissed", "confirmed")
        val CODES = setOf(
            "GEO_MOCK", "GEO_TELEPORT", "GEO_ZERO_JITTER", "GEO_PERFECT_ACCURACY", "GEO_SAME_POINT", "GEO_ROUTE_SINGLE_POINT", "GEO_STALE_FIX",
            "GEO_GNSS_INCONSISTENT", "GEO_GNSS_TIME_SKEW", "GEO_DEVICE_SERVER_MISMATCH", "GEO_SHORT_VISIT_GAPS", "DEVICE_NOT_OWNER", "DEVICE_INTEGRITY_FAIL",
            "DEVICE_DEBUG_ENABLED", "DEVICE_MOCK_APP_PRESENT", "DEVICE_POLICY_DRIFT", "CLOCK_SKEW", "GEO_OUT_OF_BOUNDS", "CONFIG_STAMP_REGRESS",
        )

        private fun bad(field: String, code: String = "invalid_value"): Nothing =
            throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $field", errors = listOf(FieldError("query.$field", code)))

        private fun date(q: Parameters, n: String): LocalDate? =
            q[n]?.let { s -> s.takeIf { DATE.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: bad(n) }

        private fun id(q: Parameters, n: String): Long? = q[n]?.let { it.toLongOrNull()?.takeIf { v -> v > 0 } ?: bad(n) }

        /** `from`..`to` default to the 31 days up to today (Dhaka), at most 92 days apart. */
        fun parse(q: Parameters, today: LocalDate): RiskQuery {
            var from = date(q, "from"); var to = date(q, "to")
            to = to ?: (from?.plusDays(30)?.coerceAtMost(today) ?: today)
            from = from ?: to.minusDays(30)
            if (from.isAfter(to)) bad("from", "out_of_range")
            if (from.plusDays(92).isBefore(to)) bad("to", "out_of_range")
            val status = q["status"]?.also { if (it !in STATUSES) bad("status") }
            val code = q["code"]?.also { if (it !in CODES) bad("code") }
            val sev = q["min_severity"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..4 } ?: bad("min_severity", "out_of_range") }
            val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: bad("limit", "out_of_range") } ?: 100
            val after = q["cursor"]?.let { c ->
                runCatching {
                    require(CURSOR.matches(c)); val p = String(Base64.getUrlDecoder().decode(c)).split('|'); require(p.size == 3 && p[0] == "r1")
                    LocalDate.parse(p[1]) to p[2].toLong()
                }.getOrNull() ?: bad("cursor")
            }
            return RiskQuery(from, to, status, code, sev, id(q, "zone_id"), id(q, "user_id"), limit, after)
        }

        fun cursor(d: LocalDate, id: Long): String = Base64.getUrlEncoder().withoutPadding().encodeToString("r1|$d|$id".toByteArray())
    }
}

internal class RiskSignals(private val d: RiskSignalDeps) {
    private fun reachOf(p: AronPrincipal): Reach {
        val r = d.reach.reach(p.userId, p.role, p.scopeVersion, d.today())
        // Field reps never see risk signals (the phone shows only its own mock warning).
        if (r.ownRecordsOnly) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "risk signals are for supervisors")
        return r
    }

    /** The zone a signal belongs to, and the reach filter on it (bound as :zones and :routes unless national). */
    private val zoneExpr = "coalesce(s.zone_id, r.zone_id, u.home_zone_id)"
    private fun reachClause(reach: Reach) = if (reach.national) "true" else "($zoneExpr = ANY(:zones) OR s.route_id = ANY(:routes))"
    private val from = """
        FROM app.risk_signal s LEFT JOIN app.route r ON r.id = s.route_id LEFT JOIN app.app_user u ON u.id = s.user_id
        LEFT JOIN LATERAL (SELECT v.action, v.user_id, v.note, v.captured_at FROM app.risk_signal_review v
                            WHERE v.signal_id = s.id AND v.voided_at IS NULL ORDER BY v.captured_at DESC, v.id DESC LIMIT 1) lr ON true
    """.trimIndent()
    private val cols = "s.id, s.code, s.severity, s.business_date, s.subject_type, s.subject_id, s.user_id, s.route_id, $zoneExpr AS zone, s.score::float8, s.evidence::text, s.status, s.config_version, s.created_at, lr.action, lr.user_id AS reviewer, lr.note, lr.captured_at AS reviewed_at"

    private fun row(rs: java.sql.ResultSet) = RiskSignalDto(
        rs.getLong("id"), rs.getString("code"), rs.getInt("severity"), rs.getObject("business_date", LocalDate::class.java).toString(), rs.getString("subject_type"),
        rs.getString("subject_id"), rs.getObject("user_id") as Long?, rs.getObject("route_id") as Long?, rs.getObject("zone") as Long?, rs.getDouble("score"),
        Json.parseToJsonElement(rs.getString("evidence")).jsonObject, rs.getString("status"), rs.getLong("config_version"),
        rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(),
        rs.getString("action")?.let { RiskLastReviewDto(it, rs.getLong("reviewer"), rs.getString("note"), rs.getObject("reviewed_at", OffsetDateTime::class.java).toInstant().wire()) },
    )

    private fun bindReach(q: org.jdbi.v3.core.statement.SqlStatement<*>, reach: Reach) {
        if (!reach.national) {
            q.bindArray("zones", Long::class.javaObjectType, reach.zoneIds.toList()).bindArray("routes", Long::class.javaObjectType, reach.routeIds.toList())
        }
    }

    fun page(p: AronPrincipal, q: RiskQuery): RiskSignalPageDto {
        val reach = reachOf(p)
        if (q.zoneId != null && !reach.coversZone(q.zoneId)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone_id is outside your reach")
        val sql = StringBuilder("SELECT $cols $from WHERE s.business_date BETWEEN :from AND :to AND ${reachClause(reach)}")
        if (q.status != null) sql.append(" AND s.status = :status")
        if (q.code != null) sql.append(" AND s.code = :code")
        if (q.minSeverity != null) sql.append(" AND s.severity >= :sev")
        if (q.zoneId != null) sql.append(" AND $zoneExpr = :zone")
        if (q.userId != null) sql.append(" AND s.user_id = :uid")
        if (q.after != null) sql.append(" AND (s.business_date, s.id) < (:ad, :aid)")
        sql.append(" ORDER BY s.business_date DESC, s.id DESC LIMIT :lim")
        val rows = d.db.readJdbi.withHandle<List<RiskSignalDto>, Exception> { h ->
            val query = h.createQuery(sql.toString()).bind("from", q.from).bind("to", q.to).bind("lim", q.limit + 1)
            bindReach(query, reach)
            q.status?.let { query.bind("status", it) }; q.code?.let { query.bind("code", it) }; q.minSeverity?.let { query.bind("sev", it) }
            q.zoneId?.let { query.bind("zone", it) }; q.userId?.let { query.bind("uid", it) }
            q.after?.let { query.bind("ad", it.first).bind("aid", it.second) }
            query.map { rs, _ -> row(rs) }.list()
        }
        val items = rows.take(q.limit)
        val next = if (rows.size > q.limit) items.last().let { RiskQuery.cursor(LocalDate.parse(it.business_date), it.signal_id) } else null
        return RiskSignalPageDto(items, next)
    }

    private fun one(h: Handle, id: Long, reach: Reach): RiskSignalDto? {
        val q = h.createQuery("SELECT $cols $from WHERE s.id = :id AND ${reachClause(reach)}").bind("id", id)
        bindReach(q, reach)
        return q.map { rs, _ -> row(rs) }.findOne().orElse(null)
    }

    fun review(p: AronPrincipal, id: Long, req: RiskReviewRequestDto): RiskSignalDto {
        val reach = reachOf(p)
        fun bad(f: String): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $f", errors = listOf(FieldError("/$f", "invalid_value")))
        if (!IngestService.UUID_V4.matches(req.review_uuid)) bad("review_uuid")
        if (req.action !in setOf("reviewed", "dismissed", "confirmed")) bad("action")
        if ((req.note?.length ?: 0) > 500) bad("note")
        return d.db.jdbi.inTransaction<RiskSignalDto, Exception> { h ->
            // Out of reach and unknown are the same answer.
            one(h, id, reach) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "signal not found")
            h.createQuery("SELECT id FROM app.risk_signal WHERE id = :id FOR UPDATE").bind("id", id).mapTo(Long::class.java).one()
            // Read after the lock: reviews of one signal get captured_at in the order they apply, so the stored status
            // always equals the latest review's action.
            val now = d.clock.now()
            fun prior() = h.createQuery("SELECT signal_id, action, user_id FROM app.risk_signal_review WHERE client_uuid = CAST(:u AS uuid)").bind("u", req.review_uuid)
                .map { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getLong(3)) }.findOne().orElse(null)
            fun same(t: Triple<Long, String, Long>) {
                if (t.first != id || t.second != req.action || t.third != p.userId) throw ApiProblem(ProblemCode.ERR_CONFLICT, "review_uuid was used for another review")
            }
            val prior = prior()
            if (prior != null) same(prior) else {
                val ts = OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC)
                val inserted = h.createUpdate(
                    """
                    INSERT INTO app.risk_signal_review (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, signal_id, action, note, source)
                    VALUES (CAST(:u AS uuid), CAST(:u AS uuid), :bd, :me, :at, :cv, :sid, :a, :n, 'online')
                    ON CONFLICT (client_uuid) DO NOTHING
                    """.trimIndent(),
                ).bind("u", req.review_uuid).bind("bd", d.today()).bind("me", p.userId).bind("at", ts).bind("cv", d.config.configVersion())
                    .bind("sid", id).bind("a", req.action).bind("n", req.note).execute()
                // The same uuid committed meanwhile (another signal's review, or a phone record): answer as a replay or 409.
                if (inserted == 0) { same(prior()!!); return@inTransaction one(h, id, reach)!! }
                // The status is the latest review's action; the signal itself is never deleted or re-scored.
                h.createUpdate("UPDATE app.risk_signal SET status = :a, updated_at = now() WHERE id = :id").bind("a", req.action).bind("id", id).execute()
            }
            one(h, id, reach)!!
        }
    }
}

/**
 * The AMO/TSO apps' `risk_review` sync record (BC-79 follow-up): the same rules as the online review. [check] refuses a
 * signal that does not exist or is outside the reviewer's reach on the record's business date (quarantined
 * `scope_out_of_reach`, kept for the helpdesk; a field rep's review is always out of reach); [afterStored] sets the
 * signal's status to this review's action unless a later review (by captured_at, then id) already stands, so a late
 * or out-of-order upload never moves the status backwards.
 */
class RiskReviewRecords(private val reach: ReachResolver) : com.aktcl.aron.backend.platform.RecordHandler {
    override val types = setOf("risk_review")

    override fun check(h: Handle, rec: com.aktcl.aron.backend.platform.IngestRecord): com.aktcl.aron.backend.platform.RecordRefusal? {
        val out = com.aktcl.aron.backend.platform.RecordRefusal(com.aktcl.aron.contract.RecordOutcomeCode.SCOPE_OUT_OF_REACH, "signal not in reach")
        val id = (rec.payload["signal_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return out
        val r = reach.reach(rec.userId, rec.role, 0, rec.businessDate)
        if (r.ownRecordsOnly) return out
        val zone = h.createQuery(
            "SELECT coalesce(s.zone_id, r.zone_id, u.home_zone_id), s.route_id FROM app.risk_signal s LEFT JOIN app.route r ON r.id = s.route_id LEFT JOIN app.app_user u ON u.id = s.user_id WHERE s.id = :id",
        ).bind("id", id).map { rs, _ -> (rs.getObject(1) as Long?) to (rs.getObject(2) as Long?) }.findOne().orElse(null) ?: return out
        val ok = r.national || (zone.first != null && zone.first in r.zoneIds) || (zone.second != null && zone.second in r.routeIds)
        return if (ok) null else out
    }

    override fun afterStored(h: Handle, rec: com.aktcl.aron.backend.platform.IngestRecord, serverId: Long?) {
        h.createUpdate(
            """
            UPDATE app.risk_signal s SET status = v.action, updated_at = now()
              FROM app.risk_signal_review v
             WHERE v.client_uuid = CAST(:c AS uuid) AND s.id = v.signal_id
               AND NOT EXISTS (SELECT 1 FROM app.risk_signal_review w WHERE w.signal_id = v.signal_id AND w.voided_at IS NULL AND (w.captured_at, w.id) > (v.captured_at, v.id))
            """.trimIndent(),
        ).bind("c", rec.clientUuid).execute()
    }
}
