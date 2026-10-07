package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ContractInfo
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class SalesSubmitRequest(
    val client_uuid: String, val scope: String, val route_id: Long? = null, val business_date: String, val submit_cycle: Int,
    val device_counts: JsonObject, val device_money: JsonObject, val submitted_with_dues: Boolean, val dues_outstanding_mtk: Long,
)

@Serializable
data class SupervisorDayStateDto(val user_id: Long, val business_date: String, val checked_in: Boolean, val sales_submitted: Boolean, val submit_cycle: Int)

@Serializable
data class DayStateListDto(val route_days: List<RouteDayStateDto>, val supervisor_day: SupervisorDayStateDto?)

@Serializable
data class FinalSubmitRequest(val client_uuid: String, val zone_id: Long, val business_date: String)

@Serializable
data class FinalSubmitResultDto(
    val zone_id: Long, val business_date: String, val submitted_at: String, val kind: String, val route_count: Int, val memo_count: Int, val net_mtk: Long,
)

@Serializable
data class FinalSubmitPreviewRouteDto(
    val route_id: Long, val route_code: String, val route_name: String, val kind: String, val ff_user_id: Long?, val ff_user_name: String?,
    val state: String, val had_data: Boolean, val memo_count: Int, val net_mtk: Long, val pending_rows_reported: Int?,
)

@Serializable
data class FinalSubmitPreviewDto(
    val zone_id: Long, val business_date: String, val already_submitted: Boolean, val submitted_at: String?, val submitted_by: String?,
    val routes: List<FinalSubmitPreviewRouteDto>, val warnings: List<String>,
)

class DayDeps(val guard: AuthGuardDeps, val service: DayService)

/** backend:sync day routes (docs/24 s4.9; contract tag `day`). Submit void, reopen, cover and exceptions are dropped rows. */
fun Route.dayRoutes(d: DayDeps) {
    authenticated(d.guard) {
        post("/day/sales-submit") {
            val req = call.receiveStrict(SalesSubmitRequest.serializer())
            call.respond(withContext(Dispatchers.IO) { d.service.salesSubmit(call.principal, req) })
        }
        get("/day/final-submit/preview") {
            val q = call.request.queryParameters
            val zone = q["zone_id"]?.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "zone_id", errors = listOf(FieldError("query.zone_id", "invalid_value")))
            val date = q["business_date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { _ -> DATE.matches(it) } }
                ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "business_date must be YYYY-MM-DD", errors = listOf(FieldError("query.business_date", "invalid_value")))
            call.respond(withContext(Dispatchers.IO) { d.service.preview(call.principal, zone, date) })
        }
        post("/day/final-submit") {
            val req = call.receiveStrict(FinalSubmitRequest.serializer())
            call.respond(withContext(Dispatchers.IO) { d.service.finalSubmit(call.principal, req) })
        }
    }
}

private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** The AMO/TSO day state of [userId] on [date] (contract SupervisorDayState); null when the user has no such day. */
internal fun supervisorDayState(h: Handle, userId: Long, date: LocalDate): SupervisorDayStateDto? =
    h.createQuery("SELECT checked_in_at IS NOT NULL AS ci, sales_submitted_at IS NOT NULL AS ss, submit_cycle FROM app.supervisor_day WHERE user_id = :u AND business_date = :d")
        .bind("u", userId).bind("d", date)
        .map { rs, _ -> SupervisorDayStateDto(userId, date.toString(), rs.getBoolean("ci"), rs.getBoolean("ss"), rs.getInt("submit_cycle")) }.findOne().orElse(null)


/**
 * Sales Submit online and Final Submit (F-API-008, F-API-009, F-API-039; docs/24 s4.9 rules 1, 3, 4).
 *
 * - **Sales Submit** online is the same `day_submit` record the phone queues offline, run through [IngestService] as a
 *   one-record batch whose `batch_uuid` is derived from the record's `client_uuid`: the registry makes it idempotent
 *   (a replay answers `duplicate`, the state is read back) and the settle rule is the one of the record (state
 *   `submit_pending_rows` until the server totals reach the device counts or `cfg.day.submit_settle_timeout_min`).
 * - **Final Submit** closes a zone-day once (`final_submit_once`): the same `client_uuid` replays the first success,
 *   another one gets 409 `ERR_DAY_ALREADY_FINAL_SUBMITTED` with `context.submitted_at`. Allowed to the TSO and to the
 *   roles in `cfg.day.final_submit_delegate_roles`, for a zone in the caller's reach on that date (server-side scope).
 *   Every route-day of the zone and date gets `final_submitted_at`; rows that arrive later are accepted and counted
 *   (`late_rows_after_final`, [DayStates]).
 */
class DayService(
    private val db: Database,
    private val config: ServerConfig,
    private val reach: ReachResolver,
    private val ingest: IngestService,
    private val clock: AronClock = AronClock.SYSTEM,
) {
    private val planner = RouteDayPlanningJob(db, config, clock)

    fun salesSubmit(p: AronPrincipal, req: SalesSubmitRequest): DayStateListDto {
        val deviceId = p.deviceId
        val deviceUuid = p.deviceUuid
        if (!p.isPhone || deviceId == null || deviceUuid == null) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "Sales Submit is for the field apps")
        if (!IngestService.UUID_V4.matches(req.client_uuid)) throw invalid("/client_uuid")
        val date = runCatching { LocalDate.parse(req.business_date) }.getOrNull()?.takeIf { DATE.matches(req.business_date) } ?: throw invalid("/business_date")
        when (req.scope) {
            "route_day" -> if (req.route_id == null || req.route_id < 1) throw invalid("/route_id")
            "supervisor_day" -> if (req.route_id != null) throw invalid("/route_id")
            else -> throw invalid("/scope")
        }
        if (req.submit_cycle !in 1..50) throw invalid("/submit_cycle")
        if (req.dues_outstanding_mtk < 0) throw invalid("/dues_outstanding_mtk")
        // A retry of a submit already stored (the first response was lost) skips the ingest: the synthesized record
        // carries the server's time, so a second copy would not be byte-identical. The state is read back either way.
        if (!storedFor(p.userId, req.client_uuid)) {
            val key = db.jdbi.withHandle<String?, Exception> { h ->
                h.createQuery("SELECT public_key_jwk::text FROM app.device WHERE id = :d AND device_uuid = CAST(:u AS uuid)").bind("d", deviceId).bind("u", deviceUuid)
                    .mapTo(String::class.java).findOne().orElse(null)
            }
            val res = try {
                ingest.ingest(Uploader(p.userId, p.role, p.scopeVersion, deviceId, deviceUuid, key?.let(DeviceProof::publicKey)), onlineBatch(req, deviceUuid))
            } catch (e: ApiProblem) {
                // Two copies racing: the first one's record is stored; the second answers its state.
                if (e.code != ProblemCode.ERR_SYNC_BATCH_UUID_REUSED || !storedFor(p.userId, req.client_uuid)) throw e
                null
            }
            res?.acks?.single()?.let { ack ->
                if (ack.status != "accepted" && ack.status != "duplicate") {
                    if (ack.code == "schema_invalid") throw ApiProblem(ProblemCode.ERR_VALIDATION, "the Sales Submit does not match the day_submit record shape")
                    throw ApiProblem(ProblemCode.ERR_CONFLICT, "Sales Submit not stored: ${ack.status} ${ack.code ?: ""}".trim(), context = mapOf("record_status" to JsonPrimitive(ack.status), "record_code" to JsonPrimitive(ack.code)))
                }
            }
        } else if (!storedAs(p.userId, req.client_uuid, "day_submit")) {
            throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid was used for another record")
        }
        return db.jdbi.withHandle<DayStateListDto, Exception> { h ->
            if (req.scope == "supervisor_day") DayStateListDto(emptyList(), supervisorDay(h, p.userId, date))
            else DayStateListDto(routeDayStates(h, req.route_id!!, date), null)
        }
    }

    private fun storedFor(userId: Long, clientUuid: String): Boolean = db.jdbi.withHandle<Boolean, Exception> { h ->
        h.createQuery("SELECT EXISTS (SELECT 1 FROM app.ingest_registry WHERE client_uuid = CAST(:c AS uuid) AND user_id = :u AND status IN ('accepted','voided'))")
            .bind("c", clientUuid).bind("u", userId).mapTo(Boolean::class.java).one()
    }

    private fun storedAs(userId: Long, clientUuid: String, type: String): Boolean = db.jdbi.withHandle<Boolean, Exception> { h ->
        h.createQuery("SELECT EXISTS (SELECT 1 FROM app.ingest_registry WHERE client_uuid = CAST(:c AS uuid) AND user_id = :u AND record_type = :t)")
            .bind("c", clientUuid).bind("u", userId).bind("t", type).mapTo(Boolean::class.java).one()
    }

    /** The route-day as it is now (never the stored batch response, which a replay would return). */
    private fun routeDayStates(h: Handle, route: Long, date: LocalDate): List<RouteDayStateDto> =
        h.createQuery(
            """
            SELECT state, planned, submit_cycle, submit_voided, submit_count_mismatch, logged_in_at, sales_submitted_at, final_submitted_at
            FROM app.route_day WHERE route_id = :r AND business_date = :d
            """.trimIndent(),
        ).bind("r", route).bind("d", date).map { rs, _ ->
            fun t(c: String) = rs.getObject(c, OffsetDateTime::class.java)?.toInstant()?.wire()
            RouteDayStateDto(
                route, date.toString(), rs.getString("state"), rs.getBoolean("planned"), rs.getInt("submit_cycle"), rs.getBoolean("submit_voided"),
                rs.getObject("submit_count_mismatch") as Boolean?, t("logged_in_at"), t("sales_submitted_at"), t("final_submitted_at"),
            )
        }.list()

    /** The online submit as the record the outbox would carry; counts the request does not hold are zero (online means the day's outbox is empty). */
    private fun onlineBatch(req: SalesSubmitRequest, deviceUuid: String): SyncBatchRequest {
        val now = clock.now()
        val record = buildJsonObject {
            put("type", "day_submit"); put("client_uuid", req.client_uuid); put("family_uuid", req.client_uuid); put("rank", 0)
            put("schema_version", 1); put("business_date", req.business_date); put("captured_at", now.wire())
            put("captured_elapsed_ms", 0); put("boot_count", 0); put("clock_offset_ms", 0); put("captured_offline", false)
            req.route_id?.let { put("route_id", it) }
            put("config_version", 1)
            put("payload", buildJsonObject {
                put("scope", req.scope); put("submit_cycle", req.submit_cycle); put("device_counts", req.device_counts)
                put("device_money", req.device_money); put("rejected_count", 0); put("quarantined_count", 0); put("pending_count", 0)
                put("submitted_with_dues", req.submitted_with_dues); put("dues_outstanding_mtk", req.dues_outstanding_mtk)
                put("retailers_with_dues", 0); put("stock_slip_printed", false)
            })
        }
        return SyncBatchRequest(
            batch_uuid = derivedV4("aron-online-sales-submit\n" + req.client_uuid), device_uuid = deviceUuid, schema_version = ContractInfo.SCHEMA_VERSION,
            app_version = "online", trigger = "manual", sent_at_device = now.wire(), pending_rows = 0, time_anchors = emptyList(),
            device_counts = emptyMap(), records = listOf(record),
        )
    }

    private fun supervisorDay(h: Handle, userId: Long, date: LocalDate): SupervisorDayStateDto? = supervisorDayState(h, userId, date)

    /** The zone must be in the caller's reach on the date; field roles never see a zone-day. */
    private fun requireZone(p: AronPrincipal, zone: Long, date: LocalDate) {
        if (p.role == Role.SR || p.role == Role.AMO) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the zone-day is the TSO's")
        if (!reach.reach(p.userId, p.role, p.scopeVersion, date).coversZone(zone)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone outside your reach")
    }

    fun preview(p: AronPrincipal, zone: Long, date: LocalDate): FinalSubmitPreviewDto {
        requireZone(p, zone, date)
        return db.jdbi.withHandle<FinalSubmitPreviewDto, Exception> { h -> previewIn(h, zone, date) }
    }

    private fun previewIn(h: Handle, zone: Long, date: LocalDate): FinalSubmitPreviewDto {
        val fs = h.createQuery(
            """
            SELECT f.submitted_at, u.full_name FROM app.final_submit f JOIN app.app_user u ON u.id = f.submitted_by
            WHERE f.zone_id = :z AND f.business_date = :d AND f.reopened_at IS NULL
            """.trimIndent(),
        ).bind("z", zone).bind("d", date).map { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant().wire() to rs.getString(2) }.findOne().orElse(null)
        val routes = h.createQuery(
            """
            SELECT r.id, r.code, r.name, r.kind, rd.state, rd.rows_awaited, ff.id AS ff_id, ff.full_name AS ff_name,
                   (SELECT count(*) FROM app.memo m WHERE m.route_id = r.id AND m.business_date = :d AND $ACTIVE_MEMO) AS memos,
                   (SELECT coalesce(sum(m.net_mtk), 0) FROM app.memo m WHERE m.route_id = r.id AND m.business_date = :d AND $ACTIVE_MEMO) AS net,
                   EXISTS (SELECT 1 FROM app.visit v WHERE v.route_id = r.id AND v.business_date = :d AND v.voided_at IS NULL)
                     OR EXISTS (SELECT 1 FROM app.memo m WHERE m.route_id = r.id AND m.business_date = :d AND m.voided_at IS NULL) AS had_data
            FROM app.route r
            LEFT JOIN app.route_day rd ON rd.route_id = r.id AND rd.business_date = :d
            LEFT JOIN app.app_user ff ON ff.id = COALESCE(rd.acting_user_id, rd.assigned_user_id,
                (SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'primary'
                   AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d) ORDER BY a.valid_from DESC, a.id DESC LIMIT 1))
            WHERE r.zone_id = :z AND (r.status = 'active' OR rd.id IS NOT NULL)
            ORDER BY r.code
            LIMIT 200
            """.trimIndent(),
        ).bind("z", zone).bind("d", date).map { rs, _ ->
            FinalSubmitPreviewRouteDto(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("kind"), rs.getObject("ff_id") as Long?, rs.getString("ff_name"),
                rs.getString("state") ?: "not_started", rs.getBoolean("had_data"), rs.getInt("memos"), rs.getLong("net"), rs.getObject("rows_awaited") as Int?,
            )
        }.list()
        val warnings = buildList {
            if (routes.any { it.state !in SUBMITTED }) add("routes_not_sales_submitted")
            if (routes.any { it.state == "submit_pending_rows" }) add("routes_with_pending_rows")
            if (routes.any { it.state == "not_started" }) add("routes_not_logged_in")
        }
        return FinalSubmitPreviewDto(zone, date.toString(), fs != null, fs?.first, fs?.second, routes, warnings)
    }

    fun finalSubmit(p: AronPrincipal, req: FinalSubmitRequest): FinalSubmitResultDto {
        if (!IngestService.UUID_V4.matches(req.client_uuid)) throw invalid("/client_uuid")
        if (req.zone_id < 1) throw invalid("/zone_id")
        val date = runCatching { LocalDate.parse(req.business_date) }.getOrNull()?.takeIf { DATE.matches(req.business_date) } ?: throw invalid("/business_date")
        val delegates = runCatching { config.value("cfg.day.final_submit_delegate_roles").jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrDefault(emptySet())
        val kind = when {
            p.role == Role.TSO -> "manual"
            p.role.wire in delegates -> "delegated"
            else -> throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "Final Submit is the TSO's (or a delegate role's)")
        }
        val now = clock.now()
        val today = LocalDate.ofInstant(now, java.time.ZoneId.of("Asia/Dhaka"))
        if (date.isAfter(today)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "a zone-day after today cannot be final-submitted", errors = listOf(FieldError("/business_date", "invalid_value")))
        // A retry of the caller's own submit answers the first success (whatever the scope is now).
        replay(h = null, req.client_uuid, p.userId)?.let { return it.checked(req, date) }
        requireZone(p, req.zone_id, date)
        return db.jdbi.inTransaction<FinalSubmitResultDto, Exception> { h ->
            // Same uuid racing (double click, timeout retry): the uuid lock first, then the zone-day lock, always in
            // this order; the loser of either race sees the winner's row below.
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", "final-uuid:${req.client_uuid}")
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", "final:${req.zone_id}:$date")
            replay(h, req.client_uuid, null)?.let { found ->
                if (found.first != p.userId) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid was used by another submit")
                return@inTransaction found.checked(req, date)
            }
            h.createQuery("SELECT submitted_at FROM app.final_submit WHERE zone_id = :z AND business_date = :d AND reopened_at IS NULL")
                .bind("z", req.zone_id).bind("d", date).mapTo(OffsetDateTime::class.java).findOne().orElse(null)?.let { at ->
                    throw ApiProblem(ProblemCode.ERR_DAY_ALREADY_FINAL_SUBMITTED, "the zone-day is already final-submitted", context = mapOf("submitted_at" to JsonPrimitive(at.toInstant().wire())))
                }
            val preview = previewIn(h, req.zone_id, date)
            val states = JsonArray(preview.routes.map { r ->
                buildJsonObject { put("route_id", r.route_id); put("state", r.state); put("memo_count", r.memo_count); put("net_mtk", r.net_mtk) }
            })
            h.createUpdate(
                """
                INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via, route_states)
                VALUES (CAST(:u AS uuid), :z, :d, :by, :at, :via, CAST(:rs AS jsonb))
                """.trimIndent(),
            ).bind("u", req.client_uuid).bind("z", req.zone_id).bind("d", date).bind("by", p.userId).bind("at", ts(now))
                .bind("via", if (p.isPhone) "app" else "web").bind("rs", states.toString()).execute()
            // Every listed route gets a route-day (planned by the planner when assigned, else unplanned), so a row that
            // arrives later finds it closed and counts as late.
            val listed = preview.routes.map { it.route_id }
            if (listed.isNotEmpty()) {
                planner.planIn(h, date, listed)
                h.createUpdate(
                    "INSERT INTO app.route_day (route_id, business_date, planned) SELECT r, :d, false FROM unnest(CAST(:r AS bigint[])) r ON CONFLICT (route_id, business_date) DO NOTHING",
                ).bind("d", date).bindArray("r", Long::class.javaObjectType, listed).execute()
            }
            val routeIds = h.createUpdate(
                """
                UPDATE app.route_day SET final_submitted_at = COALESCE(final_submitted_at, :at)
                WHERE business_date = :d AND route_id IN (SELECT id FROM app.route WHERE zone_id = :z)
                RETURNING route_id
                """.trimIndent(),
            ).bind("at", ts(now)).bind("d", date).bind("z", req.zone_id).executeAndReturnGeneratedKeys("route_id").mapTo(Long::class.java).list()
            routeIds.forEach { DayStates.advance(h, it, date); h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'final_submit')", it, date) }
            FinalSubmitResultDto(req.zone_id, date.toString(), now.wire(), kind, preview.routes.size, preview.routes.sumOf { it.memo_count }, preview.routes.sumOf { it.net_mtk })
        }
    }

    /**
     * The first success of [clientUuid]: (submitter, result). Rebuilt from the stored row; `kind` from the submitter's
     * role (TSO: manual, else delegated). With [onlyBy], only that user's submit is found (a replay never shows another
     * caller's zone-day).
     */
    private fun replay(h: Handle?, clientUuid: String, onlyBy: Long?): Pair<Long, FinalSubmitResultDto>? {
        fun q(h: Handle) = h.createQuery(
            """
            SELECT f.zone_id, f.business_date, f.submitted_at, f.route_states::text, f.submitted_by, u.role
            FROM app.final_submit f JOIN app.app_user u ON u.id = f.submitted_by
            WHERE f.client_uuid = CAST(:u AS uuid) AND (CAST(:by AS bigint) IS NULL OR f.submitted_by = :by)
            """.trimIndent(),
        ).bind("u", clientUuid).bind("by", onlyBy).map { rs, _ ->
            val zone = rs.getLong(1)
            val d = rs.getObject(2, LocalDate::class.java)
            val rows = kotlinx.serialization.json.Json.parseToJsonElement(rs.getString(4)).jsonArray.map { it as JsonObject }
            fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            rs.getLong(5) to FinalSubmitResultDto(
                zone, d.toString(), rs.getObject(3, OffsetDateTime::class.java).toInstant().wire(), if (rs.getString(6) == Role.TSO.wire) "manual" else "delegated",
                rows.size, rows.sumOf { it.long("memo_count") }.toInt(), rows.sumOf { it.long("net_mtk") },
            )
        }.findOne().orElse(null)
        return if (h != null) q(h) else db.jdbi.withHandle<Pair<Long, FinalSubmitResultDto>?, Exception> { q(it) }
    }

    /** The replayed result must be for the zone-day the request names. */
    private fun Pair<Long, FinalSubmitResultDto>.checked(req: FinalSubmitRequest, date: LocalDate): FinalSubmitResultDto {
        if (second.zone_id != req.zone_id || second.business_date != date.toString()) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid was used for another zone-day")
        return second
    }

    private fun invalid(pointer: String) = ApiProblem(ProblemCode.ERR_VALIDATION, pointer.removePrefix("/"), errors = listOf(FieldError(pointer, "invalid_value")))

    private fun ts(i: java.time.Instant) = OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC)

    companion object {
        private const val ACTIVE_MEMO = "m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL"
        private val SUBMITTED = setOf("sales_submitted", "final_submitted")

        /** A deterministic UUID with the v4 layout (the batch contract requires v4) from SHA-256 of [name]. */
        fun derivedV4(name: String): String {
            val b = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
            b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val bb = ByteBuffer.wrap(b)
            return UUID(bb.long, bb.long).toString()
        }
    }
}
