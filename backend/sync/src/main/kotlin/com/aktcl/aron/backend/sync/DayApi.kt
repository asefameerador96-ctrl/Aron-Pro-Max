package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuditLog
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ConfigScopeType
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
    /** `app.route_day.planned` on the date; null while the route has no route-day row (contract 1.7.0). */
    val planned: Boolean?,
    // tso-manual B3 (contract 1.10.0, D-TSO-MAN-27): the card title "<route name> <kind label>", the "FF:" line and the chip.
    /** `app.route.route_class`: normal, manual, dummy_br or dummy_institutional. */
    val route_class: String? = null,
    /** `app.route.visit_kind` as stored (daily, 3f, 2f); null for a route without one (an AMO route). */
    val visit_kind: String? = null,
    /** Role of the field user of the "FF:" line; null with ff_user_id null ("SR Not Set"). */
    val ff_role: String? = null,
    /** uploaded, web_entry, not_uploaded or not_due ([DayService.uploadState]). */
    val upload_state: String = UPLOAD_NOT_DUE,
)

@Serializable
data class FinalSubmitPreviewDto(
    val zone_id: Long, val business_date: String, val already_submitted: Boolean, val submitted_at: String?, val submitted_by: String?,
    val routes: List<FinalSubmitPreviewRouteDto>, val warnings: List<String>,
    // tso-manual B3 (contract 1.10.0, D-TSO-MAN-27, 28)
    /** Reopened Final Submits of the zone-day: the app derives the next client_uuid from it. */
    val submit_seq: Int = 0,
    /** The due routes not uploaded (upload_state not_uploaded), named in the app's warning; the submit is never blocked. */
    val warn_route_ids: List<Long> = emptyList(),
)

/** POST /v1/day/reopen (contract DayReopenRequest; D-TSO-MAN-29). */
@Serializable
data class DayReopenRequest(val zone_id: Long, val business_date: String, val reason: String, val client_uuid: String? = null)

@Serializable
data class DayReopenResultDto(val zone_id: Long, val business_date: String, val reopened_at: String, val reopened_by: Long)

const val UPLOAD_UPLOADED = "uploaded"
const val UPLOAD_WEB_ENTRY = "web_entry"
const val UPLOAD_NOT_UPLOADED = "not_uploaded"
const val UPLOAD_NOT_DUE = "not_due"

/** Push reason of a zone-day close (tso-manual B3, D-TSO-MAN-31): the SR and AMO apps of the zone pull the day state and lock capture. */
const val PUSH_DAY_FINAL_SUBMITTED = "day_final_submitted"

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
        // tso-manual B3: the audited admin reopen (D-TSO-MAN-29).
        post("/day/reopen") {
            val req = call.receiveStrict(DayReopenRequest.serializer())
            val requestId = call.requestId
            call.respond(withContext(Dispatchers.IO) { d.service.reopen(call.principal, req, requestId) })
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
 * - **Final Submit** closes a zone-day once (`final_submit_once`): the same `client_uuid` replays the first success
 *   while that close is live; once the zone-day was reopened, a replay of it is 409 `ERR_DAY_FINAL_SUBMIT_REOPENED`
 *   (D-TSO-MAN-28: the app reads the preview again, whose `submit_seq` gives the next uuid). Another uuid gets 409
 *   `ERR_DAY_ALREADY_FINAL_SUBMITTED` with `context.submitted_at`. Allowed to the TSO and to the roles in
 *   `cfg.day.final_submit_delegate_roles`, for a zone in the caller's reach on that date (server-side scope). Not-uploaded
 *   due routes warn and never block (D-TSO-MAN-27). Every route-day of the zone and date gets `final_submitted_at`;
 *   ingest then holds or refuses its figure rows (D-TSO-MAN-31, [IngestService]). After the commit, the SR and AMO
 *   phones with a route-day in the zone get the push `day_final_submitted`; the close emits `zone_day.final_submitted`.
 * - **Reopen** (D-TSO-MAN-29): an audited admin action within `cfg.day.reopen_window_days`, idempotent by its optional
 *   `client_uuid` ([reopen]).
 */
class DayService(
    private val db: Database,
    private val config: ServerConfig,
    private val reach: ReachResolver,
    /** Online Sales Submit only; null for the midnight [AutoFinalSubmitJob], which only closes zone-days (tso-manual B3). */
    private val ingest: IngestService?,
    private val clock: AronClock = AronClock.SYSTEM,
    /** tso-manual B3: the push after a close (backend:notify; [Nudger.NONE] in tests that do not look). */
    private val nudger: Nudger = Nudger.NONE,
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
                checkNotNull(ingest) { "Sales Submit needs the ingest service" }.ingest(Uploader(p.userId, p.role, p.scopeVersion, deviceId, deviceUuid, key?.let(DeviceProof::publicKey)), onlineBatch(req, deviceUuid))
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
        // A midnight job's close has no submitting user (V0135): it is still a close, with no name.
        val fs = h.createQuery(
            """
            SELECT f.submitted_at, u.full_name FROM app.final_submit f LEFT JOIN app.app_user u ON u.id = f.submitted_by
            WHERE f.zone_id = :z AND f.business_date = :d AND f.reopened_at IS NULL
            """.trimIndent(),
        ).bind("z", zone).bind("d", date).map { rs, _ -> rs.getObject(1, OffsetDateTime::class.java).toInstant().wire() to rs.getString(2) }.findOne().orElse(null)
        val routes = h.createQuery(
            """
            SELECT r.id, r.code, r.name, r.kind, r.route_class, r.visit_kind, rd.state, rd.rows_awaited, ff.id AS ff_id, ff.full_name AS ff_name, ff.role AS ff_role,
                   rd.planned, rd.planned IS DISTINCT FROM false AS due_by_plan,
                   rd.sales_submitted_at IS NOT NULL AND NOT rd.submit_voided AS uploaded,
                   EXISTS (SELECT 1 FROM app.web_entry_route_day w WHERE w.route_id = r.id AND w.business_date = :d AND w.replaced_at IS NULL AND w.voided_at IS NULL) AS web_entry,
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
            val state = rs.getString("state") ?: "not_started"
            val hadData = rs.getBoolean("had_data")
            val pending = rs.getObject("rows_awaited") as Int?
            val routeClass = rs.getString("route_class")
            // Every active route is listed (F-TSO-010), but only a route due that day warns: planned on the date or with
            // data. An unplanned route the SR's first bundle marked logged in, with nothing on it, was not due (lead ruling
            // 2026-10-22, docs/requests/backend-core-qa-1-answer.md). A route with no route-day yet (planner not run, SR
            // Not Set), or one that moved past logged in or reports pending rows, still counts as due. A dummy BR or
            // Institutional route without data is never due (D-TSO-MAN-27).
            val due = if (routeClass in DUMMY_CLASSES && !hadData) false
                else rs.getBoolean("due_by_plan") || hadData || state !in setOf("not_started", "logged_in") || (pending ?: 0) > 0
            FinalSubmitPreviewRouteDto(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("kind"), rs.getObject("ff_id") as Long?, rs.getString("ff_name"),
                state, hadData, rs.getInt("memos"), rs.getLong("net"), pending, rs.getObject("planned") as Boolean?,
                routeClass, rs.getString("visit_kind"), rs.getString("ff_role")?.takeIf { rs.getObject("ff_id") != null },
                uploadState(uploaded = rs.getBoolean("uploaded"), webEntry = rs.getBoolean("web_entry"), due = due),
            )
        }.list()
        // D-TSO-MAN-27: the due routes not uploaded are named in the app's warning; the warnings keep their 1.7.0 meaning
        // (route-day states), counted over those routes only (a saved Web Entry stands for the route).
        val warn = routes.filter { it.upload_state == UPLOAD_NOT_UPLOADED }
        val warnings = buildList {
            if (warn.any { it.state !in SUBMITTED }) add("routes_not_sales_submitted")
            if (warn.any { it.state == "submit_pending_rows" }) add("routes_with_pending_rows")
            if (warn.any { it.state == "not_started" }) add("routes_not_logged_in")
        }
        return FinalSubmitPreviewDto(
            zone, date.toString(), fs != null, fs?.first, fs?.second, routes, warnings,
            submit_seq = submitSeq(h, zone, date), warn_route_ids = warn.map { it.route_id },
        )
    }

    /** D-TSO-MAN-28: the reopened closes of the zone-day (the next close's client_uuid is derived from it). */
    private fun submitSeq(h: Handle, zone: Long, date: LocalDate): Int =
        h.createQuery("SELECT count(*) FROM app.final_submit WHERE zone_id = :z AND business_date = :d AND reopened_at IS NOT NULL")
            .bind("z", zone).bind("d", date).mapTo(Int::class.java).one()

    fun finalSubmit(p: AronPrincipal, req: FinalSubmitRequest): FinalSubmitResultDto {
        if (!IngestService.UUID_V4.matches(req.client_uuid)) throw invalid("/client_uuid")
        if (req.zone_id < 1) throw invalid("/zone_id")
        val date = runCatching { LocalDate.parse(req.business_date) }.getOrNull()?.takeIf { DATE.matches(req.business_date) } ?: throw invalid("/business_date")
        // Replay first: the caller's own stored submit answers before the role and the delegate list are re-judged (a
        // delegate role removed from cfg.day.final_submit_delegate_roles after the submit must not turn a retry into 403).
        replay(h = null, req.client_uuid, p.userId)?.let { return it.checked(req, date) }
        val delegates = runCatching { config.value("cfg.day.final_submit_delegate_roles").jsonArray.map { it.jsonPrimitive.content }.toSet() }.getOrDefault(emptySet())
        val kind = when {
            p.role == Role.TSO -> "manual"
            p.role.wire in delegates -> "delegated"
            else -> throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "Final Submit is the TSO's (or a delegate role's)")
        }
        val today = LocalDate.ofInstant(clock.now(), DHAKA)
        if (date.isAfter(today)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "a zone-day after today cannot be final-submitted", errors = listOf(FieldError("/business_date", "invalid_value")))
        requireZone(p, req.zone_id, date)
        val (result, recipients) = db.jdbi.inTransaction<Pair<FinalSubmitResultDto, List<Long>>, Exception> { h ->
            // Same uuid racing (double click, timeout retry): the uuid lock first, then the zone-day lock, always in
            // this order; the loser of either race sees the winner's row below.
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", "final-uuid:${req.client_uuid}")
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", zoneDayLock(req.zone_id, date))
            // KD-2: the clock after the zone-day lock (a submit queued behind another never stamps before it), cut to the
            // microsecond the column stores, so the answer and a replay of the stored row agree to the millisecond.
            val now = clock.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            replay(h, req.client_uuid, null)?.let { found ->
                if (found.submitter != p.userId) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid was used by another submit")
                return@inTransaction found.checked(req, date) to emptyList<Long>()
            }
            liveClose(h, req.zone_id, date)?.let { at ->
                throw ApiProblem(ProblemCode.ERR_DAY_ALREADY_FINAL_SUBMITTED, "the zone-day is already final-submitted", context = mapOf("submitted_at" to JsonPrimitive(at.wire())))
            }
            closeLocked(h, req.zone_id, date, req.client_uuid, p.userId, if (p.isPhone) "app" else "web", kind, now)
        }
        // After the commit only: the SR and AMO phones of the zone pull the day state and lock capture (D-TSO-MAN-31).
        // Best effort; a lost push only delays the lock until the phone's next pull (ingest holds the rows anyway).
        recipients.forEach { u -> runCatching { nudger.nudge(u, PUSH_DAY_FINAL_SUBMITTED) } }
        return result
    }

    /** The submitted_at of the live close of the zone-day, or null when it is open. */
    internal fun liveClose(h: Handle, zone: Long, date: LocalDate): java.time.Instant? =
        h.createQuery("SELECT submitted_at FROM app.final_submit WHERE zone_id = :z AND business_date = :d AND reopened_at IS NULL")
            .bind("z", zone).bind("d", date).mapTo(OffsetDateTime::class.java).findOne().orElse(null)?.toInstant()

    /**
     * The locked part of a close (the TSO's, a delegate's, or the midnight job's with [by] null and [via] job): the caller
     * holds the zone-day advisory lock ([zoneDayLock]) and has checked that no close is live. Writes the close with the
     * preview the submitter saw, gives every listed route a route-day, closes the zone's route-days in route_id order
     * (KD-1), moves their states and marks them dirty, and emits `zone_day.final_submitted` in the same transaction.
     * Returns the answer and the SR and AMO users with a route-day in the zone (the push goes after the commit).
     */
    internal fun closeLocked(h: Handle, zone: Long, date: LocalDate, clientUuid: String, by: Long?, via: String, kind: String, now: java.time.Instant): Pair<FinalSubmitResultDto, List<Long>> {
        val preview = previewIn(h, zone, date)
        val states = JsonArray(preview.routes.map { r ->
            buildJsonObject { put("route_id", r.route_id); put("state", r.state); put("upload_state", r.upload_state); put("memo_count", r.memo_count); put("net_mtk", r.net_mtk) }
        })
        h.createUpdate(
            """
            INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via, route_states)
            VALUES (CAST(:u AS uuid), :z, :d, :by, :at, :via, CAST(:rs AS jsonb))
            """.trimIndent(),
        ).bind("u", clientUuid).bind("z", zone).bind("d", date).bind("by", by).bind("at", ts(now))
            .bind("via", via).bind("rs", states.toString()).execute()
        // Every listed route gets a route-day (planned by the planner when assigned, else unplanned), so a row that
        // arrives later finds it closed.
        val listed = preview.routes.map { it.route_id }
        if (listed.isNotEmpty()) {
            planner.planIn(h, date, listed)
            h.createUpdate(
                "INSERT INTO app.route_day (route_id, business_date, planned) SELECT r, :d, false FROM unnest(CAST(:r AS bigint[])) r ON CONFLICT (route_id, business_date) DO NOTHING",
            ).bind("d", date).bindArray("r", Long::class.javaObjectType, listed).execute()
        }
        // KD-1: the zone's route-days in route_id order, the order ingest takes them in (DayStates.afterStored).
        h.createQuery("SELECT 1 FROM app.route_day WHERE business_date = :d AND route_id IN (SELECT id FROM app.route WHERE zone_id = :z) ORDER BY route_id FOR NO KEY UPDATE")
            .bind("d", date).bind("z", zone).mapTo(Int::class.java).list()
        val routeIds = h.createUpdate(
            """
            UPDATE app.route_day SET final_submitted_at = COALESCE(final_submitted_at, :at)
            WHERE business_date = :d AND route_id IN (SELECT id FROM app.route WHERE zone_id = :z)
            RETURNING route_id
            """.trimIndent(),
        ).bind("at", ts(now)).bind("d", date).bind("z", zone).executeAndReturnGeneratedKeys("route_id").mapTo(Long::class.java).list()
        routeIds.sorted().forEach { DayStates.advance(h, it, date); h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'final_submit')", it, date) }
        emitZoneDay(h, "zone_day.final_submitted", zone, date, clientUuid, buildJsonObject {
            put("zone_id", zone); put("business_date", date.toString()); put("final_submit_uuid", clientUuid); put("via", via)
            put("submitted_by", by); put("submit_seq", preview.submit_seq); put("route_count", preview.routes.size)
            put("not_uploaded_route_count", preview.warn_route_ids.size)
        })
        // The zone's field phones (D-TSO-MAN-31): the SR and AMO users of its route-days, and every active AMO whose scope
        // covers the zone on the date (the D-TSO-MAN-12 rule): an AMO sells on the outlet's SR route, so an AMO without a
        // route-day here still captures on the zone's closed route-days. Scope rows are effective-dated, valid_to exclusive.
        val recipients = h.createQuery(
            """
            SELECT u.id FROM app.route_day rd JOIN app.route r ON r.id = rd.route_id
            JOIN app.app_user u ON u.id IN (rd.assigned_user_id, rd.acting_user_id)
            WHERE r.zone_id = :z AND rd.business_date = :d AND u.role IN ('SR', 'AMO') AND u.status = 'active'
            UNION
            SELECT u.id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id
            JOIN app.user_scope s ON s.valid_from <= :d AND (s.valid_to IS NULL OR s.valid_to > :d)
                 AND ((s.node_type = 'zone' AND s.node_id = z.id) OR (s.node_type = 'house' AND s.node_id = z.house_id)
                      OR (s.node_type = 'territory' AND s.node_id = z.territory_id) OR (s.node_type = 'division' AND s.node_id = t.division_id)
                      OR (s.node_type = 'wing' AND s.node_id = dv.wing_id) OR s.node_type = 'national')
            JOIN app.app_user u ON u.id = s.user_id AND u.role = 'AMO' AND u.status = 'active'
            WHERE z.id = :z
            ORDER BY 1
            """.trimIndent(),
        ).bind("z", zone).bind("d", date).mapTo(Long::class.java).list()
        return FinalSubmitResultDto(zone, date.toString(), now.wire(), kind, preview.routes.size, preview.routes.sumOf { it.memo_count }, preview.routes.sumOf { it.net_mtk }) to recipients
    }

    /** One `zone_day.*` outbox row (docs/data-events.md v1), aggregate `<zone_id>:<business_date>`, in the caller's transaction. */
    private fun emitZoneDay(h: Handle, type: String, zone: Long, date: LocalDate, source: String?, payload: JsonObject) {
        h.createUpdate(
            """
            INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload, source_client_uuid)
            VALUES (:t, 1, 'zone_day', :a, :d, CAST(:p AS jsonb), CAST(:s AS uuid))
            """.trimIndent(),
        ).bind("t", type).bind("a", "$zone:$date").bind("d", date).bind("p", payload.toString()).bind("s", source).execute()
    }

    /** A stored close found by its client_uuid: the submitter (null for the midnight job), the answer, and whether it was reopened since. */
    private class Replay(val submitter: Long?, val result: FinalSubmitResultDto, val reopenedAt: java.time.Instant?)

    /**
     * The first success of [clientUuid], rebuilt from the stored row; `kind` from the submitter's role (TSO: manual, else
     * delegated; the midnight job: auto). With [onlyBy], only that user's submit is found (a replay never shows another
     * caller's zone-day).
     */
    private fun replay(h: Handle?, clientUuid: String, onlyBy: Long?): Replay? {
        fun q(h: Handle) = h.createQuery(
            """
            SELECT f.zone_id, f.business_date, f.submitted_at, f.route_states::text, f.submitted_by, u.role, f.via, f.reopened_at
            FROM app.final_submit f LEFT JOIN app.app_user u ON u.id = f.submitted_by
            WHERE f.client_uuid = CAST(:u AS uuid) AND (CAST(:by AS bigint) IS NULL OR f.submitted_by = :by)
            """.trimIndent(),
        ).bind("u", clientUuid).bind("by", onlyBy).map { rs, _ ->
            val zone = rs.getLong(1)
            val d = rs.getObject(2, LocalDate::class.java)
            val rows = kotlinx.serialization.json.Json.parseToJsonElement(rs.getString(4)).jsonArray.map { it as JsonObject }
            fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val kind = when {
                rs.getString(7) == "job" -> "auto"
                rs.getString(6) == Role.TSO.wire -> "manual"
                else -> "delegated"
            }
            Replay(
                rs.getObject(5) as Long?,
                FinalSubmitResultDto(
                    zone, d.toString(), rs.getObject(3, OffsetDateTime::class.java).toInstant().wire(), kind,
                    rows.size, rows.sumOf { it.long("memo_count") }.toInt(), rows.sumOf { it.long("net_mtk") },
                ),
                rs.getObject(8, OffsetDateTime::class.java)?.toInstant(),
            )
        }.findOne().orElse(null)
        return if (h != null) q(h) else db.jdbi.withHandle<Replay?, Exception> { q(it) }
    }

    /**
     * The replayed result must be for the zone-day the request names, and its close must still be live: once the
     * zone-day was reopened, the old uuid is spent (D-TSO-MAN-28, CR-3) and the app reads the preview again.
     */
    private fun Replay.checked(req: FinalSubmitRequest, date: LocalDate): FinalSubmitResultDto {
        if (result.zone_id != req.zone_id || result.business_date != date.toString()) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid was used for another zone-day")
        reopenedAt?.let { at ->
            throw ApiProblem(
                ProblemCode.ERR_DAY_FINAL_SUBMIT_REOPENED, "this Final Submit was reopened; read the preview again for the next submit",
                context = mapOf("submitted_at" to JsonPrimitive(result.submitted_at), "reopened_at" to JsonPrimitive(at.wire())),
            )
        }
        return result
    }

    // ---------------- reopen (tso-manual B3, D-TSO-MAN-29) ----------------

    /**
     * `POST /v1/day/reopen`: an audited admin reopen of a final-submitted zone-day.
     * - Check order: the body (400); the caller's own earlier reopen with the same `client_uuid` answers as stored (a
     *   uuid used for another zone-day or by another user is 409 `ERR_CONFLICT`); the role in `cfg.day.reopen_roles`
     *   resolved at the zone's wing, then global (default ADMIN, SUPERADMIN; 403); the zone in the caller's reach on the
     *   date (403); the date within `cfg.day.reopen_window_days` of today, not after it (409 `ERR_REQUEST_STATE`); a live
     *   close (409 `ERR_DAY_NOT_FINAL_SUBMITTED`).
     * - Locks: the reopen uuid, then the zone-day lock Final Submit takes, then the zone's route-days in route_id order,
     *   then the live close row (row locks in the order ingest and the quarantine accept take them: route-day first).
     * - Effect, in one transaction: the live close carries the reopen (`reopened_at`, `reopened_by`, `reopen_reason`,
     *   `reopen_client_uuid`), the route-days lose `final_submitted_at` and go back to their own state ([DayStates.reopen])
     *   and are marked dirty, an audit row (entity `zone_day`) and the event `zone_day.reopened`. A later replay of the
     *   reopened close's uuid is 409 `ERR_DAY_FINAL_SUBMIT_REOPENED`; rows held `after_final_submit` stay held for review.
     */
    fun reopen(p: AronPrincipal, req: DayReopenRequest, requestId: String?): DayReopenResultDto {
        req.client_uuid?.let { if (!IngestService.UUID_V4.matches(it)) throw invalid("/client_uuid") }
        if (req.zone_id < 1) throw invalid("/zone_id")
        val date = runCatching { LocalDate.parse(req.business_date) }.getOrNull()?.takeIf { DATE.matches(req.business_date) } ?: throw invalid("/business_date")
        if (req.reason.length !in 10..500 || req.reason.isBlank()) throw invalid("/reason")
        req.client_uuid?.let { cu -> db.jdbi.withHandle<DayReopenResultDto?, Exception> { h -> reopenReplay(h, cu, p, req.zone_id, date) }?.let { return it } }
        // The registry lets cfg.day.reopen_roles be set per wing (V0006 scopes global, wing): the zone's wing wins over global.
        val roles = runCatching {
            db.jdbi.withHandle<Set<String>?, Exception> { h ->
                val wing = h.createQuery("SELECT dv.wing_id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id WHERE z.id = :z")
                    .bind("z", req.zone_id).mapTo(Long::class.javaObjectType).findOne().orElse(null)
                val at = clock.now()
                ScopedConfig.load(h, at, at, setOf(REOPEN_ROLES_KEY)).value(REOPEN_ROLES_KEY, ScopedConfig.Chain.of(ConfigScopeType.WING to wing))
                    ?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            }
        }.getOrNull() ?: DEFAULT_REOPEN_ROLES
        if (p.role.wire !in roles) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "reopening a zone-day is for the roles in cfg.day.reopen_roles")
        if (!reach.reach(p.userId, p.role, p.scopeVersion, date).coversZone(req.zone_id)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone outside your reach")
        val today = LocalDate.ofInstant(clock.now(), DHAKA)
        val window = runCatching { config.int("cfg.day.reopen_window_days") }.getOrDefault(3).coerceIn(0, 31).toLong()
        if (date.isAfter(today) || date.isBefore(today.minusDays(window))) {
            throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "the zone-day is outside the reopen window of $window days", context = mapOf("reopen_window_days" to JsonPrimitive(window)))
        }
        return db.jdbi.inTransaction<DayReopenResultDto, Exception> { h ->
            req.client_uuid?.let { h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", "reopen-uuid:$it") }
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 3))", zoneDayLock(req.zone_id, date))
            req.client_uuid?.let { cu -> reopenReplay(h, cu, p, req.zone_id, date)?.let { return@inTransaction it } }
            val now = clock.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            // The zone's route-days in route_id order (the order Final Submit and ingest take them in) BEFORE the close
            // row: an ingest or an ADMIN accept holds a route-day and then updates final_submit.late_rows
            // (DayStates.afterStored), so taking the close row first could deadlock with it (40P01).
            val zoneRoutes = h.createQuery("SELECT route_id FROM app.route_day WHERE business_date = :d AND route_id IN (SELECT id FROM app.route WHERE zone_id = :z) ORDER BY route_id FOR NO KEY UPDATE")
                .bind("d", date).bind("z", req.zone_id).mapTo(Long::class.java).list()
            val live = h.createQuery(
                "SELECT client_uuid::text, submitted_at, via, submitted_by, late_rows FROM app.final_submit WHERE zone_id = :z AND business_date = :d AND reopened_at IS NULL FOR UPDATE",
            ).bind("z", req.zone_id).bind("d", date).map { rs, _ ->
                buildJsonObject {
                    put("final_submit_uuid", rs.getString(1)); put("submitted_at", rs.getObject(2, OffsetDateTime::class.java).toInstant().wire())
                    put("via", rs.getString(3)); put("submitted_by", rs.getObject(4) as Long?); put("late_rows", rs.getInt(5))
                }
            }.findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_DAY_NOT_FINAL_SUBMITTED, "the zone-day is not final-submitted")
            val closeUuid = (live["final_submit_uuid"] as JsonPrimitive).content
            h.createUpdate(
                """
                UPDATE app.final_submit SET reopened_at = :at, reopened_by = :by, reopen_reason = :why, reopen_client_uuid = CAST(:ru AS uuid)
                WHERE client_uuid = CAST(:u AS uuid) AND reopened_at IS NULL
                """.trimIndent(),
            ).bind("at", ts(now)).bind("by", p.userId).bind("why", req.reason).bind("ru", req.client_uuid).bind("u", closeUuid).execute()
            val reopened = DayStates.reopen(h, zoneRoutes, date)
            AuditLog.write(
                h, p, "zone_day", "${req.zone_id}:$date", "reopen", live,
                buildJsonObject { put("reopened_at", now.wire()); put("reopen_uuid", req.client_uuid); put("route_day_count", reopened.size) },
                req.reason, requestId, via = if (p.isPhone) "device" else "web",
            )
            emitZoneDay(h, "zone_day.reopened", req.zone_id, date, req.client_uuid, buildJsonObject {
                put("zone_id", req.zone_id); put("business_date", date.toString()); put("final_submit_uuid", closeUuid)
                put("reopen_uuid", req.client_uuid); put("reopened_by", p.userId); put("route_day_count", reopened.size)
            })
            DayReopenResultDto(req.zone_id, date.toString(), now.wire(), p.userId)
        }
    }

    /** The stored reopen of [clientUuid]: as stored when it is the caller's of the same zone-day, else 409; null when unused. */
    private fun reopenReplay(h: Handle, clientUuid: String, p: AronPrincipal, zone: Long, date: LocalDate): DayReopenResultDto? {
        val row = h.createQuery("SELECT zone_id, business_date, reopened_at, reopened_by FROM app.final_submit WHERE reopen_client_uuid = CAST(:u AS uuid)")
            .bind("u", clientUuid).map { rs, _ ->
                DayReopenResultDto(rs.getLong(1), rs.getObject(2, LocalDate::class.java).toString(), rs.getObject(3, OffsetDateTime::class.java).toInstant().wire(), rs.getLong(4))
            }.findOne().orElse(null) ?: return null
        if (row.zone_id != zone || row.business_date != date.toString()) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid was used for another zone-day's reopen")
        if (row.reopened_by != p.userId) throw ApiProblem(ProblemCode.ERR_CONFLICT, "client_uuid was used by another reopen")
        return row
    }

    private fun invalid(pointer: String) = ApiProblem(ProblemCode.ERR_VALIDATION, pointer.removePrefix("/"), errors = listOf(FieldError(pointer, "invalid_value")))

    private fun ts(i: java.time.Instant) = OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC)

    companion object {
        private const val ACTIVE_MEMO = "m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL"
        private val SUBMITTED = setOf("sales_submitted", "final_submitted")
        private val DHAKA: java.time.ZoneId = java.time.ZoneId.of("Asia/Dhaka")
        // tso-manual B3
        /** Dummy BR and Institutional routes: never due without data (D-TSO-MAN-27). */
        private val DUMMY_CLASSES = setOf("dummy_br", "dummy_institutional")
        /** cfg.day.reopen_roles when unreadable (the V0134 default). */
        private val DEFAULT_REOPEN_ROLES = setOf("ADMIN", "SUPERADMIN")
        private const val REOPEN_ROLES_KEY = "cfg.day.reopen_roles"

        /** The key of the zone-day advisory lock (class 3) that Final Submit, the midnight job and reopen take. */
        fun zoneDayLock(zone: Long, date: LocalDate): String = "final:$zone:$date"

        /**
         * D-TSO-MAN-27: a route-day whose sales are submitted is uploaded; else a saved Web Entry stands for it; else a due
         * route is not uploaded (amber chip, named in the warning) and any other is not due.
         */
        fun uploadState(uploaded: Boolean, webEntry: Boolean, due: Boolean): String = when {
            uploaded -> UPLOAD_UPLOADED
            webEntry -> UPLOAD_WEB_ENTRY
            due -> UPLOAD_NOT_UPLOADED
            else -> UPLOAD_NOT_DUE
        }

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
