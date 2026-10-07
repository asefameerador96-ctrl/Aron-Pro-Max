package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Asynchronous exports (xlsx above the sync limit, every pdf): implemented with F-SYS-064 once `app.report_export` exists (docs/requests/backend-reports-export-tables.md). */
fun interface ExportJobs {
    fun enqueue(key: String, format: String, ctx: ReportContext, rows: Int): ExportJob

    companion object {
        val UNAVAILABLE = ExportJobs { _, _, _, _ -> throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "asynchronous exports are not enabled yet", retryAfterS = 60, headers = mapOf("Retry-After" to "60")) }
    }
}

class ReportDeps(
    val db: Database, val engine: ReportEngine, val reach: ReachResolver, val guard: AuthGuardDeps,
    val clock: AronClock = AronClock.SYSTEM, val jobs: ExportJobs = ExportJobs.UNAVAILABLE,
)

private val XLSX = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
private val ADMIN_VIEWERS = setOf(Role.ADMIN, Role.SUPERADMIN, Role.SUPPORT)

fun Route.reportRoutes(d: ReportDeps) {
    authenticated(d.guard) {
        get("/reports") {
            val p = call.principal
            call.respond(ReportDefinitionList(d.engine.definitions().filter { p.role in d.engine.handler(it.report_key).roles }))
        }

        post("/reports/{report_key}/query") {
            val p = call.principal
            val key = call.parameters["report_key"].orEmpty()
            val handler = d.engine.handler(key)
            // Role first, then the body: a caller who may not run the report learns nothing about its filters.
            if (p.role !in handler.roles) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "this report is not available to your role")
            val q = call.receiveStrict(ReportQuery.serializer())
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)
            val format = q.output.format
            val result = d.db.readJdbi.withHandle<Any, Exception> { h ->
                val ctx = d.engine.context(h, key, q, reach, p, today)
                if (format == "json") return@withHandle d.engine.json(h, key, ctx)
                val rows = d.engine.count(h, key, ctx)
                if (rows > d.engine.exportMaxRows()) throw ApiProblem(ProblemCode.ERR_REPORT_TOO_LARGE, "the report has $rows rows; narrow the filters")
                val exportId = UUID.randomUUID().toString()
                val def = handler.definition
                val cols = d.engine.visibleColumns(def, ctx.pii)
                val watermark = "Exported by ${p.username} (${p.role.wire}) at ${d.clock.now().wire()} · export $exportId"
                when {
                    format == "pdf" || (format == "xlsx" && rows > d.engine.syncMaxRows()) -> {
                        val job = d.jobs.enqueue(key, format, ctx, rows)
                        d.db.jdbi.useHandle<Exception> { w -> d.engine.logExport(w, job.export_id, key, format, ctx, rows, call.requestId) }
                        job
                    }
                    format == "xlsx" -> {
                        val all = ArrayList<Map<String, JsonElement>>(rows)
                        d.engine.stream(h, key, ctx) { all += it }
                        d.db.jdbi.useHandle<Exception> { w -> d.engine.logExport(w, exportId, key, format, ctx, all.size, call.requestId) }
                        XlsxBody(ReportOutputs.xlsxBytes(def, cols, all, watermark), "$key-${ctx.from}_${ctx.to}.xlsx")
                    }
                    else -> {   // print
                        val all = ArrayList<Map<String, JsonElement>>(rows)
                        d.engine.stream(h, key, ctx) { all += it }
                        d.db.jdbi.useHandle<Exception> { w -> d.engine.logExport(w, exportId, key, format, ctx, all.size, call.requestId) }
                        ReportOutputs.printHtml(def, cols, all.iterator(), watermark).first
                    }
                }
            }
            when (result) {
                is ReportResult -> call.respond(result)
                is ExportJob -> call.respond(HttpStatusCode.Accepted, result)
                is XlsxBody -> { call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${result.name}\""); call.respondBytes(result.bytes, XLSX) }
                else -> call.respondText(result as String, ContentType.Text.Html)
            }
        }

        get("/report-exports") {
            val p = call.principal
            val q = call.request.queryParameters
            fun lng(n: String) = q[n]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $n", errors = listOf(FieldError("query.$n", "invalid_value"))) }
            val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..200 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad limit", errors = listOf(FieldError("query.limit", "out_of_range"))) } ?: 50
            // Everyone sees their own exports; the admin roles see everyone's and may narrow by user.
            val userFilter = if (p.role in ADMIN_VIEWERS) lng("user_id") else p.userId
            val cursor = q["cursor"]?.let { it.toLongOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad cursor", errors = listOf(FieldError("query.cursor", "invalid_value"))) }
            val key = q["report_key"]
            fun date(n: String) = q[n]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $n", errors = listOf(FieldError("query.$n", "invalid_value"))) }
            val from = date("from"); val to = date("to")
            val page = d.db.readJdbi.withHandle<ExportLogPage, Exception> { h ->
                val sql = StringBuilder("SELECT id, entity_id, actor_user_id, actor_username, after, at FROM app.audit_log WHERE entity = 'report_export'")
                if (userFilter != null) sql.append(" AND actor_user_id = :uid")
                if (key != null) sql.append(" AND after->>'report_key' = :key")
                if (from != null) sql.append(" AND business_date >= :from")
                if (to != null) sql.append(" AND business_date <= :to")
                if (cursor != null) sql.append(" AND id < :cur")
                sql.append(" ORDER BY id DESC LIMIT :lim")
                val st = h.createQuery(sql.toString()).bind("lim", limit + 1)
                userFilter?.let { st.bind("uid", it) }; key?.let { st.bind("key", it) }; from?.let { st.bind("from", it) }; to?.let { st.bind("to", it) }; cursor?.let { st.bind("cur", it) }
                val rows = st.map { rs, _ ->
                    val after = Json.parseToJsonElement(rs.getString("after")).jsonObject
                    rs.getLong("id") to ExportLogEntry(
                        rs.getString("entity_id"), after.str("report_key"), rs.getLong("actor_user_id"), rs.getString("actor_username"), after.str("format"),
                        (after["filters"] as? JsonObject)?.toMap() ?: emptyMap(), after["rows"]?.jsonPrimitive?.content?.toInt() ?: 0,
                        after["pii"]?.jsonPrimitive?.content == "true", rs.getObject("at", java.time.OffsetDateTime::class.java).toInstant().wire(),
                    )
                }.list()
                ExportLogPage(rows.take(limit).map { it.second }, if (rows.size > limit) rows[limit - 1].first.toString() else null)
            }
            call.respond(page)
        }

        get("/report-exports/{export_id}") {
            // Job status arrives with F-SYS-064 (asynchronous exports).
            throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such export job")
        }
    }
}

private class XlsxBody(val bytes: ByteArray, val name: String)
private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
