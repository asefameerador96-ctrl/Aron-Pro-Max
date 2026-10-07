package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

@Serializable
data class TaskApiDto(
    val task_uuid: String, val task_type_code: String, val title: String, val description: String?,
    val assignee_user_id: Long, val assigned_by_user_id: Long, val route_id: Long?, val outlet_id: Long?, val due_date: String?,
    val status: String, val created_at: String, val resolved_at: String?, val resolution_note: String?, val source: String,
)

@Serializable
data class TaskPageDto(val items: List<TaskApiDto>, val next_cursor: String?)

@Serializable
data class TaskCreateRequest(
    val task_uuid: String, val task_type_code: String, val assignee_user_id: Long, val title: String,
    val route_id: Long? = null, val outlet_id: Long? = null, val description: String? = null, val due_date: String? = null,
) {
    init {
        require(UUID_V4.matches(task_uuid.lowercase())) { "/task_uuid: pattern" }
        require(TYPE_CODE.matches(task_type_code)) { "/task_type_code: pattern" }
        require(assignee_user_id >= 1) { "/assignee_user_id: minimum" }
        require(title.length in 1..120) { "/title: length" }
        require(route_id == null || route_id >= 1) { "/route_id: minimum" }
        require(outlet_id == null || outlet_id >= 1) { "/outlet_id: minimum" }
        require(description == null || description.length <= 1000) { "/description: length" }
        require(due_date == null || runCatching { LocalDate.parse(due_date) }.isSuccess) { "/due_date: format" }
    }
}

@Serializable
data class TaskReasonRequest(val reason: String) {
    init { require(reason.length in 10..500) { "/reason: length" } }
}

private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val TYPE_CODE = Regex("^[a-z][a-z0-9_]{1,40}$")

/**
 * Tasks (F-API-026, contract tag `tasks`): `GET /v1/tasks` in the caller's reach, `POST /v1/tasks` online and
 * idempotent by `task_uuid`, `POST /v1/tasks/{uuid}/cancel` by the creator, a TSO whose reach holds the assignee, or an
 * admin. Phones create tasks with a `task` record and resolve or reopen them with `task_event` records through
 * POST /v1/sync/batch; [TaskRecords] keeps the status in step. A task's status is the newest event's by capture time,
 * so late or reordered events end where the phone left it; a cancelled task stays cancelled.
 */
class TaskService(private val db: Database, private val reach: ReachResolver, private val clock: AronClock = AronClock.SYSTEM) {
    private fun reachOf(p: AronPrincipal): Reach =
        reach.reach(p.userId, p.role, p.scopeVersion, BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate())

    fun list(p: AronPrincipal, status: String?, assignee: Long?, zone: Long?, from: LocalDate?, to: LocalDate?, limit: Int, cursor: Long?): TaskPageDto {
        val r = reachOf(p)
        if (zone != null && !r.coversZone(zone)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone outside your reach")
        val where = ArrayList<String>()
        if (r.ownRecordsOnly) where += "(t.assignee_user_id = :me OR t.user_id = :me)"
        else if (!r.national) where += "(t.assignee_user_id = :me OR t.user_id = :me OR ${userInZones("t.assignee_user_id")})"
        status?.let { where += "t.status = :status" }
        assignee?.let { where += "t.assignee_user_id = :assignee" }
        zone?.let { where += userInZones("t.assignee_user_id", single = true) }
        from?.let { where += "t.business_date >= :from" }
        to?.let { where += "t.business_date <= :to" }
        cursor?.let { where += "t.id < :cursor" }
        where += "t.voided_at IS NULL"
        val rows = db.readJdbi.withHandle<List<Pair<Long, TaskApiDto>>, Exception> { h ->
            h.createQuery("$SELECT WHERE ${where.joinToString(" AND ")} ORDER BY t.id DESC LIMIT :n")
                .configure(org.jdbi.v3.core.statement.SqlStatements::class.java) { it.isUnusedBindingAllowed = true }
                .bind("me", p.userId).bindArray("z", Long::class.javaObjectType, r.zoneIds.toList()).bind("today", r.businessDate)
                .bind("status", status).bind("assignee", assignee).bind("zone", zone).bind("from", from).bind("to", to).bind("cursor", cursor)
                .bind("n", limit + 1).map { rs, _ -> rs.getLong("id") to map(rs) }.list()
        }
        val page = rows.take(limit)
        val next = if (rows.size > limit) Base64.getUrlEncoder().withoutPadding().encodeToString(page.last().first.toString().toByteArray()) else null
        return TaskPageDto(page.map { it.second }, next)
    }

    fun create(p: AronPrincipal, req: TaskCreateRequest): TaskApiDto {
        if (p.role == Role.SR) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "an SR creates tasks from the phone")
        val uuid = req.task_uuid.lowercase()
        val now = clock.now()
        val r = reachOf(p)
        return db.jdbi.inTransaction<TaskApiDto, Exception> { h ->
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", uuid)
            byUuid(h, uuid)?.let { existing ->
                // A replay of the same create returns the first one; another create under the same uuid is a conflict.
                val same = existing.assigned_by_user_id == p.userId && existing.assignee_user_id == req.assignee_user_id &&
                    existing.title == req.title && existing.task_type_code == req.task_type_code
                if (!same) throw ApiProblem(ProblemCode.ERR_CONFLICT, "task_uuid already used for another task")
                return@inTransaction existing
            }
            if (!assigneeInReach(h, r, req.assignee_user_id)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "assignee outside your reach")
            req.outlet_id?.let { o ->
                val ok = h.createQuery("SELECT count(*) FROM app.outlet WHERE id = :o AND (:nat OR zone_id = ANY(:z))")
                    .bind("o", o).bind("nat", r.national).bindArray("z", Long::class.javaObjectType, r.zoneIds.toList()).mapTo(Long::class.java).one() > 0
                if (!ok) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "outlet outside your reach")
            }
            h.createUpdate(
                """
                INSERT INTO app.task (client_uuid, family_uuid, business_date, user_id, captured_at, captured_offline, schema_version, config_version,
                                      received_at, task_type_code, assignee_user_id, outlet_id, title, description, due_date, source)
                VALUES (CAST(:u AS uuid), CAST(:u AS uuid), :bd, :by, :now, false, 1, 0, :now, :type, :assignee, :outlet, :title, :desc, CAST(:due AS date), 'online')
                """.trimIndent(),
            ).bind("u", uuid).bind("bd", BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()).bind("by", p.userId).bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .bind("type", req.task_type_code).bind("assignee", req.assignee_user_id).bind("outlet", req.outlet_id).bind("title", req.title)
                .bind("desc", req.description).bind("due", req.due_date).execute()
            byUuid(h, uuid)!!
        }
    }

    fun cancel(p: AronPrincipal, uuidRaw: String, @Suppress("UNUSED_PARAMETER") reason: String): TaskApiDto {
        val uuid = uuidRaw.lowercase()
        if (!UUID_V4.matches(uuid)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "task_uuid is not a UUID", errors = listOf(FieldError("path.task_uuid", "invalid_value")))
        val r = reachOf(p)
        return db.jdbi.inTransaction<TaskApiDto, Exception> { h ->
            val t = h.createQuery("SELECT user_id, assignee_user_id, status FROM app.task WHERE client_uuid = CAST(:u AS uuid) AND voided_at IS NULL FOR UPDATE")
                .bind("u", uuid).map { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getString(3)) }.findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such task")
            val allowed = t.first == p.userId || p.role in setOf(Role.ADMIN, Role.SUPERADMIN) || (p.role == Role.TSO && assigneeInReach(h, r, t.second))
            if (!allowed) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only the creator, the zone's TSO or an admin cancels a task")
            when (t.third) {
                "cancelled" -> Unit // idempotent
                "ongoing" -> h.createUpdate("UPDATE app.task SET status = 'cancelled', status_changed_at = :now, cancelled_by = :by WHERE client_uuid = CAST(:u AS uuid)")
                    .bind("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).bind("by", p.userId).bind("u", uuid).execute()
                else -> throw ApiProblem(ProblemCode.ERR_CONFLICT, "a completed task cannot be cancelled")
            }
            byUuid(h, uuid)!!
        }
    }

    companion object {
        /** The assignee's zone (home zone or a route held today) is in :z; with [single], the one zone :zone. */
        fun userInZones(col: String, single: Boolean = false): String {
            val z = if (single) "= :zone" else "= ANY(:z)"
            return "(EXISTS (SELECT 1 FROM app.app_user u WHERE u.id = $col AND u.home_zone_id $z) OR EXISTS (SELECT 1 FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id " +
                "WHERE a.user_id = $col AND r.zone_id $z AND a.valid_from <= :today AND (a.valid_to IS NULL OR a.valid_to > :today)))"
        }

        fun assigneeInReach(h: Handle, r: Reach, userId: Long): Boolean {
            if (userId == r.userId) return true
            val active = h.createQuery("SELECT count(*) FROM app.app_user WHERE id = :u AND status = 'active'").bind("u", userId).mapTo(Long::class.java).one() > 0
            if (!active) return false
            if (r.national) return true
            if (r.ownRecordsOnly) return false
            return h.createQuery("SELECT ${userInZones(":u")}").bind("u", userId).bindArray("z", Long::class.javaObjectType, r.zoneIds.toList())
                .bind("today", r.businessDate).mapTo(Boolean::class.java).one()
        }

        private const val SELECT = """
            SELECT t.id, t.client_uuid::text AS uuid, t.task_type_code, t.title, t.description, t.assignee_user_id, t.user_id, t.outlet_id,
                   (SELECT o.route_id FROM app.outlet o WHERE o.id = t.outlet_id) AS route_id, t.due_date, t.status, t.created_at, t.source,
                   e.at AS resolved_at, e.note AS resolution_note
            FROM app.task t
            LEFT JOIN LATERAL (SELECT ev.captured_at AS at, ev.note FROM app.task_event ev WHERE ev.task_uuid = t.client_uuid AND ev.event = 'resolved' AND ev.voided_at IS NULL
                               ORDER BY ev.captured_at DESC, ev.id DESC LIMIT 1) e ON t.status = 'completed'
        """

        fun byUuid(h: Handle, uuid: String): TaskApiDto? =
            h.createQuery("$SELECT WHERE t.client_uuid = CAST(:u AS uuid)").bind("u", uuid).map { rs, _ -> map(rs) }.findOne().orElse(null)

        private fun ts(rs: ResultSet, c: String) = rs.getObject(c, OffsetDateTime::class.java)?.toInstant()?.wire()

        fun map(rs: ResultSet) = TaskApiDto(
            rs.getString("uuid"), rs.getString("task_type_code"), rs.getString("title"), rs.getString("description"),
            rs.getLong("assignee_user_id"), rs.getLong("user_id"), rs.getObject("route_id") as Long?, rs.getObject("outlet_id") as Long?,
            rs.getObject("due_date", LocalDate::class.java)?.toString(), rs.getString("status"), ts(rs, "created_at")!!,
            ts(rs, "resolved_at"), rs.getString("resolution_note"), if (rs.getString("source") == "online") "web" else "app",
        )
    }
}

/**
 * The ingest side of tasks: a `task` record's assignee must be in the uploader's reach (else quarantined
 * `scope_out_of_reach`); a stored `task_event` sets the task's status from the newest event by capture time
 * (resolved = completed, reopened = ongoing); a cancelled task is never reopened by a late event.
 */
class TaskRecords(private val reach: ReachResolver) : RecordHandler {
    override val types = setOf("task", "task_event")

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
        if (rec.type != "task") return null
        val assignee = rec.payload.long("assignee_user_id") ?: return null
        val r = reach.reach(rec.userId, rec.role, 0, rec.businessDate)
        return if (TaskService.assigneeInReach(h, r, assignee)) null
        else RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "assignee $assignee outside the uploader's reach")
    }

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        if (rec.type != "task_event") return
        val task = rec.payload.str("task_uuid") ?: return
        h.createQuery("SELECT id FROM app.task WHERE client_uuid = CAST(:t AS uuid) FOR UPDATE").bind("t", task).mapTo(Long::class.java).findOne()
        val newest = h.createQuery(
            "SELECT event FROM app.task_event WHERE task_uuid = CAST(:t AS uuid) AND voided_at IS NULL ORDER BY captured_at DESC, id DESC LIMIT 1",
        ).bind("t", task).mapTo(String::class.java).findOne().orElse(null) ?: return
        val want = if (newest == "resolved") "completed" else "ongoing"
        h.createUpdate("UPDATE app.task SET status = :s, status_changed_at = :now WHERE client_uuid = CAST(:t AS uuid) AND status <> 'cancelled' AND status <> :s")
            .bind("s", want).bind("now", OffsetDateTime.ofInstant(rec.receivedAt, ZoneOffset.UTC)).bind("t", task).execute()
    }
}

class TaskDeps(val service: TaskService, val guard: AuthGuardDeps)

fun Route.taskRoutes(d: TaskDeps) {
    authenticated(d.guard) {
        get("/tasks") { call.respond(withContext(Dispatchers.IO) { listTasks(call, d) }) }
        post("/tasks") {
            val req = call.receiveStrict(TaskCreateRequest.serializer())
            call.respond(withContext(Dispatchers.IO) { d.service.create(call.principal, req) })
        }
        post("/tasks/{task_uuid}/cancel") {
            val req = call.receiveStrict(TaskReasonRequest.serializer())
            val uuid = call.parameters["task_uuid"].orEmpty()
            call.respond(withContext(Dispatchers.IO) { d.service.cancel(call.principal, uuid, req.reason) })
        }
    }
}

private fun bad(pointer: String, code: String = "invalid_value"): Nothing =
    throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

private fun listTasks(call: ApplicationCall, d: TaskDeps): TaskPageDto {
    val q = call.request.queryParameters
    val status = q["status"]?.also { if (it !in setOf("ongoing", "completed", "cancelled")) bad("query.status") }
    val assignee = q["assignee_user_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("query.assignee_user_id") }
    val zone = q["zone_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("query.zone_id") }
    val from = q["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: bad("query.from") }
    val to = q["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: bad("query.to") }
    val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..200 } ?: bad("query.limit", "out_of_range") } ?: 50
    val cursor = q["cursor"]?.let { c -> runCatching { String(Base64.getUrlDecoder().decode(c)).toLong() }.getOrNull()?.takeIf { it > 0 } ?: bad("query.cursor") }
    return d.service.list(call.principal, status, assignee, zone, from, to, limit, cursor)
}
