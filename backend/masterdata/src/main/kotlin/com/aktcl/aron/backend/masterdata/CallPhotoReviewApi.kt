package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

/** Contract `CallPhotoReviewRow` (1.14.0, SRM-A-27). */
@Serializable
data class CallPhotoReviewRowDto(
    val visit_uuid: String, val business_date: String, val opened_at: String, val outlet_id: Long, val outlet_name: String,
    val user_id: Long, val user_name: String, val route_id: Long?, val photo_uuid: String?, val skip_reason: String?, val media_status: String?,
    val taken_at: String?, val fix_lat: Double?, val fix_lng: Double?, val fix_accuracy_m: Double?, val fix_is_mock: Boolean?, val distance_m: Double?,
    val reason_code_unlisted: Boolean = false,
)

@Serializable
data class CallPhotoReviewPageDto(val items: List<CallPhotoReviewRowDto>, val next_cursor: String?)

class CallPhotoReviewDeps(val db: Database, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val REVIEWERS = setOf(Role.AMO, Role.TSO, Role.DMO, Role.WM, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

/**
 * `GET /v1/supervisor/call-photos?business_date=&route_id=` (contract getCallPhotoReview). The rows are the `sr_call` visits of the date
 * inside the caller's reach (zones and routes from the token, the same rule as MediaRead and coversRoute; a national role sees all), newest first, keyset-paged by visit id. A visit without a
 * photo and without a skip reason is listed too (photo_uuid null): a reviewer must see the missing ones.
 */
fun Route.callPhotoReviewRoutes(d: CallPhotoReviewDeps) {
    authenticated(d.guard) {
        get("/supervisor/call-photos") { call.respond(review(call, d)) }
    }
}

private fun bad(pointer: String, code: String = "invalid_value"): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

private fun review(call: io.ktor.server.application.ApplicationCall, d: CallPhotoReviewDeps): CallPhotoReviewPageDto {
    val p = call.principal
    if (p.role !in REVIEWERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the call photo review is not available to this role")
    val q = call.request.queryParameters
    val date = q["business_date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: bad("query.business_date") } ?: bad("query.business_date", "required")
    val route = q["route_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("query.route_id") }
    val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: bad("query.limit", "out_of_range") } ?: 100
    val cursor = q["cursor"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("query.cursor") }
    val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
    val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)
    if (!reach.national && reach.zoneIds.isEmpty() && reach.routeIds.isEmpty()) return CallPhotoReviewPageDto(emptyList(), null)
    val rows = d.db.readJdbi.withHandle<List<Pair<CallPhotoReviewRowDto, Long>>, Exception> { h ->
        val sql = """
            SELECT v.id, v.client_uuid::text AS vu, v.business_date, v.opened_at, v.outlet_id, o.name AS outlet_name, v.user_id, u.full_name, v.route_id,
                   CASE WHEN fm.client_uuid IS NOT NULL THEN NULL ELSE v.call_photo_uuid::text END AS photo, v.call_photo_skip_reason_code, m.status AS media_status, m.taken_at, m.fix_lat, m.fix_lng, m.fix_accuracy_m,
                   m.fix_is_mock, v.distance_m, coalesce(v.reason_code_unlisted, false) AS unlisted
              FROM app.visit v
              JOIN app.outlet o ON o.id = v.outlet_id
              JOIN app.app_user u ON u.id = v.user_id
              LEFT JOIN app.route r ON r.id = v.route_id
              LEFT JOIN app.media m ON m.client_uuid = v.call_photo_uuid AND m.user_id = v.user_id AND m.purpose = 'outlet_call' AND m.voided_at IS NULL
              LEFT JOIN app.media fm ON fm.client_uuid = v.call_photo_uuid AND fm.voided_at IS NULL AND (fm.user_id <> v.user_id OR fm.purpose <> 'outlet_call')
             WHERE v.business_date = :d AND v.visit_kind = 'sr_call' AND v.voided_at IS NULL
               ${if (reach.national) "" else "AND (coalesce(r.zone_id, o.zone_id) = ANY(:zones) OR v.route_id = ANY(:routes))"}
               ${if (route != null) "AND v.route_id = :r" else ""}
               ${if (cursor != null) "AND v.id < :c" else ""}
             ORDER BY v.id DESC LIMIT :lim
        """.trimIndent()
        val query = h.createQuery(sql).bind("d", date).bind("lim", limit + 1)
        if (!reach.national) {
            query.bindArray("zones", Long::class.javaObjectType, reach.zoneIds.toList())
            query.bindArray("routes", Long::class.javaObjectType, reach.routeIds.toList())
        }
        if (route != null) query.bind("r", route)
        if (cursor != null) query.bind("c", cursor)
        query.map { rs, _ ->
            fun dbl(c: String) = (rs.getObject(c) as Number?)?.toDouble()
            CallPhotoReviewRowDto(
                rs.getString("vu"), rs.getObject("business_date", LocalDate::class.java).toString(), rs.getObject("opened_at", OffsetDateTime::class.java).toInstant().wire(),
                rs.getLong("outlet_id"), rs.getString("outlet_name"), rs.getLong("user_id"), rs.getString("full_name"), rs.getObject("route_id") as Long?,
                rs.getString("photo"), rs.getString("call_photo_skip_reason_code"), rs.getString("media_status"),
                rs.getObject("taken_at", OffsetDateTime::class.java)?.toInstant()?.wire(), dbl("fix_lat"), dbl("fix_lng"), dbl("fix_accuracy_m"),
                rs.getObject("fix_is_mock") as Boolean?, dbl("distance_m"), rs.getBoolean("unlisted"),
            ) to rs.getLong("id")
        }.list()
    }
    val page = rows.take(limit)
    return CallPhotoReviewPageDto(page.map { it.first }, if (rows.size > limit) page.last().second.toString() else null)
}
