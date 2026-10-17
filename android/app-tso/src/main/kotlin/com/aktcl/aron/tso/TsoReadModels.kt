package com.aktcl.aron.tso

import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.feature.tso.leave.LeaveJson
import com.aktcl.aron.feature.tso.leave.LeaveSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The TSO read models of the host (F-TSO-027): one online GET per screen visit, no polling timer; the last answer is kept in
 * the user's encrypted database (`sync_meta`, key `tso.cache.*`), so it is wiped with the database at logout (F-SYS-022)
 * and a screen opens offline with "as of hh:mm". Never throws: an offline or refused call returns null.
 */
class TsoReadModels(private val components: SessionComponents, private val databases: UserDatabases, private val outbox: TsoOutbox? = null) {
    private val client: AronApiClient get() = components.apiClient

    private fun asOfNow(): String =
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }.format(Date(components.trustedClock.nowMs()))

    private suspend fun meta(userId: Long) = databases.of(userId).referenceDao()

    private suspend fun cacheGet(userId: Long, key: String): Pair<String, String>? = runCatching {
        val dao = meta(userId)
        val body = dao.meta("tso.cache.$key") ?: return null
        body to (dao.meta("tso.cache.$key.asof") ?: "")
    }.getOrNull()

    private suspend fun cachePut(userId: Long, key: String, body: String, asOf: String) {
        runCatching {
            val dao = meta(userId)
            dao.putMeta(SyncMetaEntity("tso.cache.$key", body)); dao.putMeta(SyncMetaEntity("tso.cache.$key.asof", asOf))
        }
    }

    private suspend fun get(path: String): String? {
        val r = runCatching { client.call(path = path, auth = CallAuth.Grant(Grant.FULL), build = { get() }, decode = { body, _ -> body }) }.getOrNull()
        return (r as? ApiResult.Success)?.value
    }

    fun leave(userId: Long): LeaveSource = object : LeaveSource {
        override suspend fun cached(): LeaveSource.Cached? {
            val (body, asOf) = cacheGet(userId, "leave") ?: return null
            return LeaveJson.parsePage(body)?.let { LeaveSource.Cached(it.items, asOf) }
        }

        /** The mirror rows written with each queued application (same transaction as the outbox row). */
        override suspend fun unsent(): List<com.aktcl.aron.feature.tso.leave.LeaveItem> = runCatching {
            meta(userId).metaWithPrefix(LEAVE_MIRROR).mapNotNull { LeaveJson.parseUnsent(it.value) }
        }.getOrDefault(emptyList())

        override suspend fun refresh(): LeaveSource.Cached? {
            // follow next_cursor (at most 5 pages of 100): a TSO's history can exceed one page; a failure on a later page keeps the whole refresh out
            val all = mutableListOf<com.aktcl.aron.feature.tso.leave.LeaveItem>()
            var cursor: String? = null
            for (pageNo in 1..5) {
                val body = get("/v1/leave?limit=100" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")) ?: return null
                val pg = LeaveJson.parsePage(body) ?: return null
                all += pg.items; cursor = pg.nextCursor
                if (cursor == null) break
            }
            val page = LeaveJson.Page(all.distinctBy { it.leaveUuid }, null)
            val asOf = asOfNow()
            cachePut(userId, "leave", LeaveJson.toCache(page.items), asOf)
            // the server has these: their "not sent yet" mirror rows are done
            runCatching { page.items.forEach { meta(userId).deleteMeta(LEAVE_MIRROR + it.leaveUuid) } }
            return LeaveSource.Cached(page.items, asOf)
        }
    }

    /** F-TSO-009: saves the application to the outbox and its mirror in one transaction; the sync engine sends it later. */
    fun leaveQueue(userId: Long): com.aktcl.aron.feature.tso.leave.LeaveQueue = com.aktcl.aron.feature.tso.leave.LeaveQueue { uuid, p ->
        val box = checkNotNull(outbox) { "no outbox" }
        val item = com.aktcl.aron.feature.tso.leave.LeaveItem(
            uuid, p.leaveTypeCode, p.fromDate, com.aktcl.aron.feature.tso.leave.leaveToDate(p.fromDate, p.days), p.days, p.reason,
            com.aktcl.aron.feature.tso.leave.LeaveStatus.PENDING, sent = false,
        )
        box.enqueue(
            userId, com.aktcl.aron.contract.RecordType.LEAVE_APPLICATION, uuid,
            kotlinx.serialization.json.buildJsonObject {
                put("leave_type_code", kotlinx.serialization.json.JsonPrimitive(p.leaveTypeCode))
                put("from_date", kotlinx.serialization.json.JsonPrimitive(p.fromDate))
                put("days", kotlinx.serialization.json.JsonPrimitive(p.days))
                put("reason", kotlinx.serialization.json.JsonPrimitive(p.reason))
            },
            mirrorKey = LEAVE_MIRROR + uuid, mirrorValue = LeaveJson.unsentToJson(item),
        )
    }

    /** F-TSO-018/029: categories come from the login snapshot (bundle section code_lists), so they work offline. */
    fun feedback(userId: Long): com.aktcl.aron.feature.tso.feedback.FeedbackSource = object : com.aktcl.aron.feature.tso.feedback.FeedbackSource {
        override suspend fun categories() = com.aktcl.aron.feature.tso.feedback.CodeLists.feedbackCategories(runCatching { meta(userId).section("code_lists") }.getOrNull())

        override suspend fun cached(): com.aktcl.aron.feature.tso.feedback.FeedbackSource.Cached? {
            val (body, asOf) = cacheGet(userId, "feedback") ?: return null
            return com.aktcl.aron.feature.tso.feedback.FeedbackJson.parsePage(body)?.let { com.aktcl.aron.feature.tso.feedback.FeedbackSource.Cached(it.items, asOf) }
        }

        override suspend fun unsent() = runCatching {
            meta(userId).metaWithPrefix(FEEDBACK_MIRROR).mapNotNull { com.aktcl.aron.feature.tso.feedback.FeedbackJson.parseUnsent(it.value) }
        }.getOrDefault(emptyList())

        override suspend fun refresh(): com.aktcl.aron.feature.tso.feedback.FeedbackSource.Cached? {
            val all = mutableListOf<com.aktcl.aron.feature.tso.feedback.FeedbackItem>()
            var cursor: String? = null
            for (pageNo in 1..5) {
                val body = get("/v1/feedback?limit=100" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")) ?: return null
                val pg = com.aktcl.aron.feature.tso.feedback.FeedbackJson.parsePage(body) ?: return null
                all += pg.items; cursor = pg.nextCursor
                if (cursor == null) break
            }
            val items = all.distinctBy { it.feedbackUuid }
            val asOf = asOfNow()
            cachePut(userId, "feedback", com.aktcl.aron.feature.tso.feedback.FeedbackJson.toCache(items), asOf)
            runCatching { items.forEach { meta(userId).deleteMeta(FEEDBACK_MIRROR + it.feedbackUuid) } }
            return com.aktcl.aron.feature.tso.feedback.FeedbackSource.Cached(items, asOf)
        }
    }

    /** F-TSO-018: outbox row (record type feedback, family self, rank 0) plus the mirror row, one transaction. */
    fun feedbackQueue(userId: Long): com.aktcl.aron.feature.tso.feedback.FeedbackQueue = com.aktcl.aron.feature.tso.feedback.FeedbackQueue { uuid, createdAt, p ->
        val box = checkNotNull(outbox) { "no outbox" }
        val item = com.aktcl.aron.feature.tso.feedback.FeedbackItem(uuid, p.categoryCode, p.title, p.description, createdAt, com.aktcl.aron.feature.tso.feedback.FeedbackStatus.NEW, sent = false, hasPhoto = p.photoUuid != null)
        box.enqueue(
            userId, com.aktcl.aron.contract.RecordType.FEEDBACK, uuid,
            kotlinx.serialization.json.buildJsonObject {
                put("category_code", kotlinx.serialization.json.JsonPrimitive(p.categoryCode))
                put("title", kotlinx.serialization.json.JsonPrimitive(p.title))
                put("description", kotlinx.serialization.json.JsonPrimitive(p.description))
                put("photo_uuid", p.photoUuid?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            },
            mirrorKey = FEEDBACK_MIRROR + uuid, mirrorValue = com.aktcl.aron.feature.tso.feedback.FeedbackJson.unsentToJson(item),
        )
    }

    // ---- My Call (F-TSO-013..016) ----

    private suspend fun getPages(path: String, maxPages: Int = 5): List<String>? {
        val bodies = mutableListOf<String>(); var cursor: String? = null
        for (i in 1..maxPages) {
            val sep = if ('?' in path) "&" else "?"
            val body = get(path + sep + "limit=500" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")) ?: return null
            bodies += body; cursor = com.aktcl.aron.feature.tso.mycall.PlanJson.nextCursor(body)
            if (cursor == null) break
        }
        return bodies
    }

    fun plan(userId: Long): com.aktcl.aron.feature.tso.mycall.PlanSource = object : com.aktcl.aron.feature.tso.mycall.PlanSource {
        private val J = com.aktcl.aron.feature.tso.mycall.PlanJson

        override suspend fun routes(): List<com.aktcl.aron.feature.tso.mycall.PlanRoute>? {
            val fresh = getPages("/v1/routes")?.map { J.parseRoutes(it) ?: return@map null }
            if (fresh != null && fresh.none { it == null }) {
                val all = fresh.filterNotNull().flatten().distinctBy { it.id }
                cachePut(userId, "routes", J.routesToCache(all), asOfNow()); return all
            }
            return cacheGet(userId, "routes")?.let { J.parseRoutes(it.first) }
        }

        override suspend fun outlets(routeId: Long): List<com.aktcl.aron.feature.tso.mycall.PlanOutlet>? {
            val fresh = getPages("/v1/outlets?route_id=$routeId")?.map { J.parseOutlets(it) }
            if (fresh != null && fresh.none { it == null }) {
                val all = fresh.filterNotNull().flatten().distinctBy { it.id }
                cachePut(userId, "outlets.$routeId", J.outletsToCache(all), asOfNow()); return all
            }
            return cacheGet(userId, "outlets.$routeId")?.let { J.parseOutlets(it.first) }
        }

        override suspend fun plans(date: String): List<com.aktcl.aron.feature.tso.mycall.VisitPlanItem>? {
            val body = get("/v1/visit-plans?from=$date&to=$date&planner_user_id=$userId")
            val server = body?.let { J.parsePlans(it) }
            if (server != null) cachePut(userId, "plans.$date", J.plansToCache(server), asOfNow())
            val base = server ?: cacheGet(userId, "plans.$date")?.let { J.parsePlans(it.first) }
            val local = runCatching { meta(userId).metaWithPrefix(PLAN_MIRROR + date + ".") }.getOrDefault(emptyList()).mapNotNull { parsePlanMirror(it.value) }
            if (base == null && local.isEmpty()) return null
            // a server plan that already holds every local outlet retires its mirror row
            if (server != null) runCatching {
                local.forEach { m -> if (server.any { it.planUuid == m.planUuid && it.outlets.map { o -> o.outletId }.containsAll(m.outlets.map { o -> o.outletId }) }) meta(userId).deleteMeta(PLAN_MIRROR + m.planDate + "." + m.routeId) }
            }
            val merged = (base.orEmpty()).toMutableList()
            for (m in local) {
                val i = merged.indexOfFirst { it.planUuid == m.planUuid }
                if (i < 0) merged += m else {
                    val have = merged[i].outlets.map { it.outletId }.toSet()
                    merged[i] = merged[i].copy(outlets = merged[i].outlets + m.outlets.filter { it.outletId !in have }, sent = merged[i].sent && m.outlets.all { it.outletId in have })
                }
            }
            return merged
        }

        override suspend fun taskPending(planUuid: String): Set<Long> = runCatching {
            meta(userId).metaWithPrefix(TASK_PENDING + planUuid + ".").mapNotNull { it.key.substringAfterLast('.').toLongOrNull() }.toSet()
        }.getOrDefault(emptySet())

        override suspend fun locallyDone(planUuid: String): Set<Long> = runCatching {
            meta(userId).metaWithPrefix(DONE_MIRROR + planUuid + ".").mapNotNull { it.key.substringAfterLast('.').toLongOrNull() }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun parsePlanMirror(t: String): com.aktcl.aron.feature.tso.mycall.VisitPlanItem? = runCatching {
        val o = org.json.JSONObject(t); val a = o.getJSONArray("outlets")
        com.aktcl.aron.feature.tso.mycall.VisitPlanItem(o.getString("plan_uuid"), o.getString("plan_date"), o.getLong("route_id"),
            (0 until a.length()).map { com.aktcl.aron.feature.tso.mycall.PlannedOutlet(a.getLong(it), com.aktcl.aron.feature.tso.mycall.PlanStatus.PENDING) }, sent = false)
    }.getOrNull()

    /** F-TSO-013: visit_plan + visit_plan_outlet records (deterministic uuids: a repeat is a union), one transaction. */
    fun planQueue(userId: Long, source: com.aktcl.aron.feature.tso.mycall.PlanSource): com.aktcl.aron.feature.tso.mycall.PlanQueue = com.aktcl.aron.feature.tso.mycall.PlanQueue { save ->
        val box = checkNotNull(outbox) { "no outbox" }
        val planUuid = com.aktcl.aron.feature.tso.mycall.PlanIds.plan(userId, save.date, save.routeId)
        val known = runCatching { source.plans(save.date) }.getOrNull().orEmpty().filter { it.planUuid == planUuid }.flatMap { p -> p.outlets.map { it.outletId } }.toSet()
        val recs = mutableListOf(TsoOutbox.Rec(
            com.aktcl.aron.contract.RecordType.VISIT_PLAN, planUuid,
            kotlinx.serialization.json.buildJsonObject { put("plan_date", kotlinx.serialization.json.JsonPrimitive(save.date)); put("note", kotlinx.serialization.json.JsonNull) },
            routeId = save.routeId,
        ))
        save.outletIds.sorted().forEach { id ->
            recs += TsoOutbox.Rec(
                com.aktcl.aron.contract.RecordType.VISIT_PLAN_OUTLET, com.aktcl.aron.feature.tso.mycall.PlanIds.planOutlet(planUuid, id),
                kotlinx.serialization.json.buildJsonObject { put("plan_client_uuid", kotlinx.serialization.json.JsonPrimitive(planUuid)); put("outlet_id", kotlinx.serialization.json.JsonPrimitive(id)) },
                familyUuid = planUuid, rank = 1, routeId = save.routeId,
            )
        }
        val union = (known + save.outletIds).sorted()
        val mirror = org.json.JSONObject().put("plan_uuid", planUuid).put("plan_date", save.date).put("route_id", save.routeId).put("outlets", org.json.JSONArray(union)).toString()
        box.enqueueAll(userId, recs, listOf(PLAN_MIRROR + save.date + "." + save.routeId to mirror))
        (save.outletIds - known).size
    }

    /** F-TSO-015: call_assessment (retailer_questionnaire) + one answer per question, family self; the plan outlet is marked done locally. */
    fun queryQueue(userId: Long): com.aktcl.aron.feature.tso.mycall.QueryQueue = com.aktcl.aron.feature.tso.mycall.QueryQueue { q ->
        val box = checkNotNull(outbox) { "no outbox" }
        val a = com.aktcl.aron.feature.tso.mycall.QueryIds.assessment(q.planUuid, q.outletId)
        val score = q.answers.values.count { it }
        val recs = mutableListOf(TsoOutbox.Rec(
            com.aktcl.aron.contract.RecordType.CALL_ASSESSMENT, a,
            kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive("retailer_questionnaire"))
                put("visit_client_uuid", kotlinx.serialization.json.JsonNull)
                put("visit_plan_outlet_client_uuid", kotlinx.serialization.json.JsonPrimitive(com.aktcl.aron.feature.tso.mycall.PlanIds.planOutlet(q.planUuid, q.outletId)))
                put("rubric_id", kotlinx.serialization.json.JsonPrimitive(q.rubric.id)); put("rubric_version", kotlinx.serialization.json.JsonPrimitive(q.rubric.version))
                put("total_score", kotlinx.serialization.json.JsonPrimitive(score)); put("max_score", kotlinx.serialization.json.JsonPrimitive(q.rubric.criteria.size))
                put("delegate_task", kotlinx.serialization.json.JsonPrimitive(q.delegate))
            }, routeId = q.routeId,
        ))
        q.rubric.criteria.forEach { c ->
            recs += TsoOutbox.Rec(
                com.aktcl.aron.contract.RecordType.CALL_ASSESSMENT_ANSWER, com.aktcl.aron.feature.tso.mycall.QueryIds.answer(a, c.id),
                kotlinx.serialization.json.buildJsonObject {
                    put("assessment_client_uuid", kotlinx.serialization.json.JsonPrimitive(a)); put("criterion_id", kotlinx.serialization.json.JsonPrimitive(c.id))
                    put("answer_bool", kotlinx.serialization.json.JsonPrimitive(q.answers.getValue(c.id)))
                }, familyUuid = a, rank = 1, routeId = q.routeId,
            )
        }
        val mirrors = listOfNotNull(DONE_MIRROR + q.planUuid + "." + q.outletId to "1", if (q.delegate) TASK_PENDING + q.planUuid + "." + q.outletId to "1" else null)
        box.enqueueAll(userId, recs, mirrors) > 0
    }

    /** F-TSO-016: a `task` record for the SR on the route (self family, rank 0). */
    fun taskQueue(userId: Long): com.aktcl.aron.feature.tso.mycall.TaskQueue = com.aktcl.aron.feature.tso.mycall.TaskQueue { t ->
        val box = checkNotNull(outbox) { "no outbox" }
        val done = box.enqueue(
            userId, com.aktcl.aron.contract.RecordType.TASK, t.uuid,
            kotlinx.serialization.json.buildJsonObject {
                put("task_type_code", kotlinx.serialization.json.JsonPrimitive(t.typeCode)); put("assignee_user_id", kotlinx.serialization.json.JsonPrimitive(t.assigneeUserId))
                put("outlet_id", kotlinx.serialization.json.JsonPrimitive(t.outletId)); put("title", kotlinx.serialization.json.JsonPrimitive(t.title))
                put("description", t.comment?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                put("due_date", kotlinx.serialization.json.JsonPrimitive(t.dueDate)); put("source_visit_client_uuid", kotlinx.serialization.json.JsonNull)
            }, routeId = t.routeId,
        )
        // the task is queued (or was already, same uuid): Assign Task is no longer pending for this outlet
        t.planUuid?.let { p -> runCatching { meta(userId).deleteMeta(TASK_PENDING + p + "." + t.outletId) } }
        done
    }

    /** F-TSO-010/021: preview read and the one online POST; no queue, scope from the token only. */
    fun finalSubmit(userId: Long, plan: com.aktcl.aron.feature.tso.mycall.PlanSource): com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi = object : com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi {
        private fun why(r: ApiResult<*>): com.aktcl.aron.feature.tso.finalsubmit.FsFailure = when (r) {
            is ApiResult.Transport -> com.aktcl.aron.feature.tso.finalsubmit.FsFailure.OFFLINE
            is ApiResult.Failure -> if (r.httpStatus == 403) com.aktcl.aron.feature.tso.finalsubmit.FsFailure.FORBIDDEN else if (r.httpStatus in 400..499) com.aktcl.aron.feature.tso.finalsubmit.FsFailure.REFUSED else com.aktcl.aron.feature.tso.finalsubmit.FsFailure.SERVER
            else -> com.aktcl.aron.feature.tso.finalsubmit.FsFailure.SERVER
        }

        override suspend fun zones(): List<Long>? = runCatching { plan.routes() }.getOrNull()?.let { com.aktcl.aron.feature.tso.mycall.zonesOf(it) }

        override suspend fun preview(zoneId: Long, date: String): com.aktcl.aron.feature.tso.finalsubmit.FsPreviewOutcome {
            val r = client.call(path = "/v1/day/final-submit/preview", auth = CallAuth.Grant(Grant.FULL), build = {
                url(client.origin.path("/v1/day/final-submit/preview").newBuilder().addQueryParameter("zone_id", zoneId.toString()).addQueryParameter("business_date", date).build()); get()
            }, decode = { body, _ -> body })
            if (r !is ApiResult.Success) return com.aktcl.aron.feature.tso.finalsubmit.FsPreviewOutcome.Failed(why(r))
            return com.aktcl.aron.feature.tso.finalsubmit.FsJson.preview(r.value)?.let { com.aktcl.aron.feature.tso.finalsubmit.FsPreviewOutcome.Ok(it) }
                ?: com.aktcl.aron.feature.tso.finalsubmit.FsPreviewOutcome.Failed(com.aktcl.aron.feature.tso.finalsubmit.FsFailure.SERVER)
        }

        override suspend fun submit(clientUuid: String, zoneId: Long, date: String): com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome {
            val json = org.json.JSONObject().put("client_uuid", clientUuid).put("zone_id", zoneId).put("business_date", date).toString()
            val r = client.call(path = "/v1/day/final-submit", auth = CallAuth.Grant(Grant.FULL), build = {
                post(json.toRequestBody("application/json".toMediaType()))
            }, decode = { body, _ -> body })
            if (r is ApiResult.Failure && r.httpStatus == 409 && r.problem.code == "ERR_DAY_ALREADY_FINAL_SUBMITTED") return com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome.AlreadySubmitted
            if (r !is ApiResult.Success) return com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome.Failed(why(r))
            return com.aktcl.aron.feature.tso.finalsubmit.FsJson.result(r.value)?.let { com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome.Ok(it) }
                ?: com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome.Failed(com.aktcl.aron.feature.tso.finalsubmit.FsFailure.SERVER)
        }
    }

    suspend fun rubric(userId: Long) = com.aktcl.aron.feature.tso.mycall.RubricJson.retailer(runCatching { meta(userId).section("rubrics") }.getOrNull())
    suspend fun taskTypes(userId: Long) = com.aktcl.aron.feature.tso.mycall.TaskTypes.parse(runCatching { meta(userId).section("code_lists") }.getOrNull())

    private companion object { const val PLAN_MIRROR = "tso.unsent.plan."; const val TASK_PENDING = "tso.task.pending."; const val DONE_MIRROR = "tso.visit.done."; const val FEEDBACK_MIRROR = "tso.unsent.feedback."; const val LEAVE_MIRROR = "tso.unsent.leave." }
}
