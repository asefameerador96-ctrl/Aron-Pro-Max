package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.backend.platform.RouteDayPlan
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.time.OffsetDateTime

/** Contract TeamMember. */
@Serializable
data class BundleTeamMember(
    val user_id: Long, val username: String, val full_name: String, val role: String, val route_ids: List<Long>, val phone_masked: String?,
)

/** Contract OutletRequestSummary (the bundle's copy of the AMO's pending list). */
@Serializable
data class BundleOutletRequest(
    val request_uuid: String, val request_type: String, val status: String, val outlet_id: Long?, val outlet_name: String?, val route_id: Long?,
    val cluster_id: Long?, val requested_by_user_id: Long, val requested_at: String, val rejection_reason: String?,
)

/** Contract SupervisorSection. */
@Serializable
data class SupervisorSectionDto(
    val zone_ids: List<Long>, val route_ids: List<Long>, val team: List<BundleTeamMember>, val pending_outlet_requests: List<BundleOutletRequest>,
    val risk_signals: List<RiskSignalDto> = emptyList(), val supervisor_day: SupervisorDayStateDto? = null,
)

/**
 * F-AMO-044: the zone-wide part of the AMO bundle and the `supervisor` section of the AMO and TSO bundle. Everything is
 * derived from the token's [Reach] (role, `user_scope`, assignments); the client sends no zone or route ids.
 *
 * AMO: the active routes of the reach's zones (at most 2,000; a 54-route zone has 3,500 to 11,000 outlets, which the bundle
 * pages above `cfg.bundle.page_threshold_rows`). TSO: the section only (team, pending requests); the field outlets of
 * several zones are a web job, not an offline list.
 */
internal object SupervisorBundle {
    const val MAX_ROUTES = 2000
    private const val MAX_TEAM = 500
    private const val MAX_REQUESTS = 2000
    private const val MAX_SIGNALS = 500

    fun isSupervisor(role: Role) = role == Role.AMO || role == Role.TSO

    /** Active routes of the reach's zones that the user does not already carry, as plans of a non-selling, non-target day. */
    fun zoneRoutePlans(h: Handle, reach: Reach, own: Set<Long>): List<RouteDayPlan> {
        if (reach.national || reach.zoneIds.isEmpty()) return emptyList() // an AMO's reach is never national; refuse a mis-scoped one
        return h.createQuery(
            "SELECT id, code, name, zone_id, visit_kind, visit_days_mask, display_label FROM app.route r WHERE r.status = 'active' AND (r.zone_id = ANY(:z) OR EXISTS (SELECT 1 FROM app.outlet o WHERE o.route_id = r.id AND o.zone_id = ANY(:z) AND o.status = 'active')) ORDER BY id LIMIT :lim",
        ).bindArray("z", Long::class.javaObjectType, reach.zoneIds.toList()).bind("lim", MAX_ROUTES + own.size).map { rs, _ ->
            RouteDayPlan(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getLong("zone_id"), rs.getString("visit_kind"),
                rs.getInt("visit_days_mask"), rs.getString("display_label"), "cover", false, 0, 0)
        }.list().filter { it.routeId !in own }.take(MAX_ROUTES)
    }

    fun section(h: Handle, userId: Long, role: Role, reach: Reach, date: LocalDate, routeIds: List<Long>): JsonObject {
        val zones = reach.zoneIds.sorted()
        val dto = SupervisorSectionDto(
            zone_ids = zones.take(100), route_ids = routeIds.take(MAX_ROUTES),
            team = team(h, userId, routeIds, date), pending_outlet_requests = pendingRequests(h, reach),
            risk_signals = if (role == Role.AMO) openSignals(h, reach) else emptyList(),
            supervisor_day = supervisorDayState(h, userId, date),
        )
        return ResponseJson.encodeToJsonElement(SupervisorSectionDto.serializer(), dto).jsonObject
    }

    /** Field users assigned (primary or cover) to a route of the zone on [date]; the phone number is masked. */
    private fun team(h: Handle, me: Long, routeIds: List<Long>, date: LocalDate): List<BundleTeamMember> {
        if (routeIds.isEmpty()) return emptyList()
        return h.createQuery(
            """
            SELECT u.id, u.username, u.full_name, u.role, array_agg(DISTINCT a.route_id ORDER BY a.route_id) AS routes,
                   CASE WHEN u.phone IS NULL THEN NULL ELSE left(u.phone, 3) || '*****' || right(u.phone, 3) END AS phone
            FROM app.route_assignment a JOIN app.app_user u ON u.id = a.user_id
            WHERE a.route_id = ANY(:r) AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d) AND u.status = 'active' AND u.id <> :me
            GROUP BY u.id ORDER BY u.full_name, u.id LIMIT :lim
            """.trimIndent(),
        ).bindArray("r", Long::class.javaObjectType, routeIds).bind("d", date).bind("me", me).bind("lim", MAX_TEAM).map { rs, _ ->
            BundleTeamMember(rs.getLong("id"), rs.getString("username"), rs.getString("full_name"), rs.getString("role"),
                (rs.getArray("routes").array as Array<*>).map { (it as Number).toLong() }.take(20), rs.getString("phone"))
        }.list()
    }

    /** Requests waiting for verification in the reach, newest first (same zone rule as the outlet-request list). */
    private fun pendingRequests(h: Handle, reach: Reach): List<BundleOutletRequest> {
        if (reach.zoneIds.isEmpty()) return emptyList()
        return h.createQuery(
            """
            SELECT q.client_uuid::text AS u, q.request_type, q.status, q.outlet_id, o.name AS outlet_name, q.route_id,
                   q.proposed ->> 'cluster_id' AS pcluster, o.cluster_id AS ocluster, q.user_id, q.captured_at
            FROM app.outlet_change_request q LEFT JOIN app.outlet o ON o.id = q.outlet_id LEFT JOIN app.route rt ON rt.id = q.route_id
            LEFT JOIN app.cluster pc ON pc.id = CASE WHEN q.proposed ->> 'cluster_id' ~ '^[0-9]{1,18}${'$'}' THEN (q.proposed ->> 'cluster_id')::bigint END
            LEFT JOIN app.app_user ru ON ru.id = q.user_id
            WHERE q.voided_at IS NULL AND q.status = 'pending'
              AND (coalesce(o.zone_id, rt.zone_id, pc.zone_id, ru.home_zone_id) = ANY(:z) OR q.route_id = ANY(:r))
            ORDER BY q.captured_at DESC, q.id DESC LIMIT :lim
            """.trimIndent(),
        ).bindArray("z", Long::class.javaObjectType, reach.zoneIds.toList()).bindArray("r", Long::class.javaObjectType, reach.routeIds.toList())
            .bind("lim", MAX_REQUESTS).map { rs, _ ->
                BundleOutletRequest(
                    rs.getString("u"), rs.getString("request_type"), rs.getString("status"), rs.getObject("outlet_id") as Long?, rs.getString("outlet_name"),
                    rs.getObject("route_id") as Long?, rs.getString("pcluster")?.toLongOrNull() ?: rs.getObject("ocluster") as Long?,
                    rs.getLong("user_id"), rs.getObject("captured_at", OffsetDateTime::class.java).toInstant().wire(), null,
                )
            }.list()
    }

    /** Open risk signals of the zone for the Exceptions screen (reviewed offline with `risk_review` records), worst first. */
    private fun openSignals(h: Handle, reach: Reach): List<RiskSignalDto> {
        if (reach.zoneIds.isEmpty()) return emptyList()
        return h.createQuery(
            """
            SELECT s.id, s.code, s.severity, s.business_date, s.subject_type, s.subject_id, s.user_id, s.route_id,
                   coalesce(s.zone_id, r.zone_id, u.home_zone_id) AS zone, s.score::float8 AS score, s.evidence::text AS evidence, s.status,
                   s.config_version, s.created_at
            FROM app.risk_signal s LEFT JOIN app.route r ON r.id = s.route_id LEFT JOIN app.app_user u ON u.id = s.user_id
            WHERE s.status = 'open' AND (coalesce(s.zone_id, r.zone_id, u.home_zone_id) = ANY(:z) OR s.route_id = ANY(:r))
            ORDER BY s.severity DESC, s.business_date DESC, s.id DESC LIMIT :lim
            """.trimIndent(),
        ).bindArray("z", Long::class.javaObjectType, reach.zoneIds.toList()).bindArray("r", Long::class.javaObjectType, reach.routeIds.toList())
            .bind("lim", MAX_SIGNALS).map { rs, _ ->
                RiskSignalDto(
                    rs.getLong("id"), rs.getString("code"), rs.getInt("severity"), rs.getObject("business_date", LocalDate::class.java).toString(),
                    rs.getString("subject_type"), rs.getString("subject_id"), rs.getObject("user_id") as Long?, rs.getObject("route_id") as Long?,
                    rs.getObject("zone") as Long?, rs.getDouble("score"), Json.parseToJsonElement(rs.getString("evidence")).jsonObject, rs.getString("status"),
                    rs.getLong("config_version"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(), null,
                )
            }.list()
    }
}
