package com.aktcl.aron.tso

import androidx.room.withTransaction
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.feature.tso.leave.LeaveJson
import com.aktcl.aron.feature.tso.leave.LeaveSource
import com.aktcl.aron.feature.tso.leave.answered
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
class TsoReadModels(private val env: TsoEnv, private val databases: UserDatabases, private val outbox: TsoOutbox? = null) {
    constructor(components: SessionComponents, databases: UserDatabases, outbox: TsoOutbox? = null) : this(SessionTsoEnv(components), databases, outbox)

    private val client: AronApiClient get() = env.client

    private fun asOfNow(): String =
        SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Dhaka") }.format(Date(env.nowMs()))

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
            dao.putMeta(SyncMetaEntity("tso.cache.$key.ts", env.nowMs().toString()))
        }
    }

    /** The cached body of slow-changing reference reads (routes, a route's outlets) while it is younger than [ttlMs]: the data pack is not spent on every screen open (docs/04). */
    private suspend fun cacheFresh(userId: Long, key: String, ttlMs: Long): String? = runCatching {
        val dao = meta(userId)
        val ts = dao.meta("tso.cache.$key.ts")?.toLongOrNull() ?: return null
        if (env.nowMs() - ts in 0 until ttlMs) dao.meta("tso.cache.$key") else null
    }.getOrNull()

    /** tso-manual A3: one config value of the user's bundle (the raw JSON text), null when unreadable or absent. */
    private suspend fun configRaw(userId: Long, key: String): String? = runCatching {
        com.aktcl.aron.core.database.repo.ReferenceRepository(databases.of(userId)).config(key, java.time.Instant.ofEpochMilli(env.nowMs()).toString())
    }.getOrNull()

    private suspend fun configInt(userId: Long, key: String): Int? = configRaw(userId, key)?.trim('"', ' ')?.toIntOrNull()

    /** "HH:mm" in Asia/Dhaka on trusted time: the "as of" of an answer worked out on the phone. */
    fun asOfClock(): String = asOfNow()

    /** Zone names of the TSO's reach (`GET /v1/admin/geo/zone`, scope from the token), kept 30 minutes; empty when never read (the screens then say "Zone <id>"). */
    suspend fun zoneNames(userId: Long, bangla: Boolean): Map<Long, String> {
        val body = cacheFresh(userId, "geo.zone", REFERENCE_TTL_MS)
            ?: get("/v1/admin/geo/zone?limit=500")?.also { cachePut(userId, "geo.zone", it, asOfNow()) }
            ?: cacheGet(userId, "geo.zone")?.first
        return com.aktcl.aron.feature.tso.ZoneNamesJson.parse(body, bangla)
    }

    /**
     * One GET of [path], which may end in a query (`/v1/leave?limit=100`). The query goes into the URL's query: the client's
     * contract path is an encoded path, where OkHttp writes `?` as `%3F`, so `/v1/routes?limit=500` would be asked as the
     * path `/v1/routes%3Flimit=500` (tso-manual X4b fix round, found by TsoAssignTaskOfflineTest; TsoReadModelsUrlTest).
     */
    private suspend fun get(path: String): String? {
        val contractPath = path.substringBefore('?')
        val query = path.substringAfter('?', "").ifEmpty { null }
        val r = runCatching {
            val c = client
            c.call(
                path = contractPath, auth = CallAuth.Grant(Grant.FULL),
                build = { url(c.origin.path(contractPath).newBuilder().encodedQuery(query).build()); get() },
                decode = { body, _ -> body },
            )
        }.getOrNull()
        return (r as? ApiResult.Success)?.value
    }

    fun leave(userId: Long): LeaveSource = object : LeaveSource {
        override suspend fun cached(): LeaveSource.Cached? {
            val (body, asOf) = cacheGet(userId, "leave") ?: return null
            return LeaveJson.parsePage(body)?.let { LeaveSource.Cached(it.items, asOf) }
        }

        /**
         * The mirror rows written with each queued application (same transaction as the outbox row). A row the server refused
         * on sync (outbox state rejected) carries its outcome code, so the card says "Not accepted: <reason>" (D-TSO-MAN-33);
         * one the server holds for review (outbox state quarantined, e.g. scope_out_of_reach) says "Held for review: <reason>".
         * A code without a leave text is worded by the stored bundle's `reason_texts` (R-W-10), so it reads offline too.
         */
        override suspend fun unsent(): List<com.aktcl.aron.feature.tso.leave.LeaveItem> = runCatching {
            val box = databases.of(userId).outboxDao()
            // the whole section, all its stored chunks (ReferenceRepository.section); unreadable or absent gives no texts
            val reasons = com.aktcl.aron.feature.tso.leave.LeaveReasonTexts.parse(runCatching { com.aktcl.aron.core.database.repo.ReferenceRepository(databases.of(userId)).section("reason_texts") }.getOrNull())
            meta(userId).metaWithPrefix(LEAVE_MIRROR).mapNotNull { LeaveJson.parseUnsent(it.value) }.map { item ->
                val row = runCatching { box.byClientUuid(item.leaveUuid) }.getOrNull()
                val answer = when (row?.state) {
                    com.aktcl.aron.core.database.entity.OutboxState.REJECTED -> com.aktcl.aron.feature.tso.leave.LeaveSyncAnswer.REFUSED
                    com.aktcl.aron.core.database.entity.OutboxState.QUARANTINED -> com.aktcl.aron.feature.tso.leave.LeaveSyncAnswer.HELD
                    else -> null
                }
                item.answered(answer, row?.lastCode, reasons)
            }
        }.getOrDefault(emptyList())

        /** The bundle code list `leave_type` (stored with the login snapshot, so the form works offline). */
        override suspend fun leaveTypes() = com.aktcl.aron.feature.tso.leave.LeaveTypes.parse(runCatching { meta(userId).section("code_lists") }.getOrNull())

        /** `cfg.leave.allow_past_days` / `allow_ahead_days` from the stored config; the defaults when unreadable. */
        override suspend fun window(): com.aktcl.aron.feature.tso.leave.LeaveWindow {
            val ref = runCatching { com.aktcl.aron.core.database.repo.ReferenceRepository(databases.of(userId)) }.getOrNull() ?: return com.aktcl.aron.feature.tso.leave.LeaveWindow()
            val now = java.time.Instant.ofEpochMilli(env.nowMs()).toString()
            suspend fun days(key: String, default: Long) = runCatching { ref.config(key, now) }.getOrNull()?.trim('"', ' ')?.toLongOrNull()?.takeIf { it in 0..3650 } ?: default
            return com.aktcl.aron.feature.tso.leave.LeaveWindow(days("cfg.leave.allow_past_days", com.aktcl.aron.feature.tso.leave.MAX_BACKDATE_DAYS), days("cfg.leave.allow_ahead_days", com.aktcl.aron.feature.tso.leave.MAX_AHEAD_DAYS))
        }

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

        /** tso-manual A3: `cfg.feedback.max_images` (0..1, R-F-06) of the bundle. */
        override suspend fun maxImages() = com.aktcl.aron.feature.tso.feedback.feedbackMaxImages(configInt(userId, "cfg.feedback.max_images"))

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
    fun feedbackQueue(userId: Long, media: (suspend () -> com.aktcl.aron.core.media.MediaComponents?)? = null): com.aktcl.aron.feature.tso.feedback.FeedbackQueue = com.aktcl.aron.feature.tso.feedback.FeedbackQueue { uuid, createdAt, p ->
        val box = checkNotNull(outbox) { "no outbox" }
        // F-SYS-030: the photo is claimed for its record BEFORE the record is committed, so the media worker uploads it; a failure here fails the save (retry-safe: same uuid)
        p.photoUuid?.let { photo -> checkNotNull(media?.invoke()) { "no media queue" }.attach(photo, com.aktcl.aron.core.media.MediaRef("feedback", "feedback", uuid), null) }
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

        /**
         * tso-manual X4b (R-W-12 (9); fix round, D-X4b-08): the routes with their SR, in this order. (1) An online read younger
         * than 30 minutes is newer than the bundle, so it wins and a mid-day SR change shows (no request). (2) The bundle's
         * routes when every route's snapshot carries its SR (`sr_user_id` / `sr_name`, R-W-06): offline, no request. (3) A route
         * whose snapshot does not carry it: the online read (`GET /v1/routes`, kept 30 minutes). (4) Offline: the bundle where it
         * carries the SR, the last online read kept for the others; a route neither names is vacant.
         */
        override suspend fun routes(): List<com.aktcl.aron.feature.tso.mycall.PlanRoute>? {
            cacheFresh(userId, "routes", REFERENCE_TTL_MS)?.let { c -> J.parseRoutes(c)?.let { return it } }
            val bundle = bundleRoutesWithSr()
            if (bundle != null && bundle.all { it.carried }) return bundle.map { it.route }
            val fresh = getPages("/v1/routes")?.map { J.parseRoutes(it) ?: return@map null }
            if (fresh != null && fresh.none { it == null }) {
                val all = fresh.filterNotNull().flatten().distinctBy { it.id }
                cachePut(userId, "routes", J.routesToCache(all), asOfNow()); return all
            }
            val last = cacheGet(userId, "routes")?.let { c -> J.parseRoutes(c.first) }
            if (bundle != null && bundle.any { it.carried }) return TsoBundleReads.fill(bundle, last)
            last?.let { return it }
            // nothing online and nothing kept: the routes of the bundle (no SR names there)
            return bundle?.map { it.route }?.takeIf { it.isNotEmpty() }
        }

        /** The stored bundle's active routes of the business date (trusted time), each with what its snapshot says about its SR. */
        private suspend fun bundleRoutesWithSr(): List<TsoBundleReads.BundleRoute>? = runCatching {
            val db = databases.of(userId)
            val rows = db.referenceDao().routesFor(env.businessDate())
            if (rows.isEmpty()) return@runCatching null
            val repo = com.aktcl.aron.core.database.repo.ReferenceRepository(db)
            val extras = rows.associate { it.routeId to repo.section("route.${it.routeId}") }
            TsoBundleReads.routesWithSr(rows) { extras[it] }
        }.getOrNull()

        override suspend fun outlets(routeId: Long): List<com.aktcl.aron.feature.tso.mycall.PlanOutlet>? {
            // the bundle first (D-TSO-BUNDLE-OUTLETS): local, instant and offline; the online read only when the bundle has none
            runCatching { TsoBundleReads.outlets(meta(userId).outletsOf(routeId)) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            cacheFresh(userId, "outlets.$routeId", REFERENCE_TTL_MS)?.let { c -> J.parseOutlets(c)?.let { return it } }
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
                val db = databases.of(userId)
                // re-read inside a transaction: a save that added an outlet since the read above keeps its row
                db.withTransaction {
                    for (m in local) {
                        val key = PLAN_MIRROR + m.planDate + "." + m.routeId
                        val now = db.referenceDao().meta(key)?.let { parsePlanMirror(it) } ?: continue
                        if (server.any { it.planUuid == now.planUuid && it.outlets.map { o -> o.outletId }.containsAll(now.outlets.map { o -> o.outletId }) }) db.referenceDao().deleteMeta(key)
                    }
                }
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

        /** tso-manual A3: `cfg.tso.plan_max_days_ahead` (1..90) of the bundle (D-TSO-MAN-35). */
        override suspend fun planMaxDaysAhead() = com.aktcl.aron.feature.tso.mycall.planDaysAhead(configInt(userId, "cfg.tso.plan_max_days_ahead")).toInt()
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
        // the local plan row is the UNION of what it holds, what the server list showed and this save, computed inside the transaction
        val merge: (String?) -> String = { existing ->
            val held = runCatching { org.json.JSONObject(existing ?: "{}").optJSONArray("outlets") }.getOrNull()?.let { arr -> (0 until arr.length()).map { arr.getLong(it) } }.orEmpty()
            val union = (held + known + save.outletIds).toSortedSet().toList()
            org.json.JSONObject().put("plan_uuid", planUuid).put("plan_date", save.date).put("route_id", save.routeId).put("outlets", org.json.JSONArray(union)).toString()
        }
        box.enqueueAll(userId, recs, merges = listOf((PLAN_MIRROR + save.date + "." + save.routeId) to merge))
        (save.outletIds - known).size
    }

    /** F-TSO-015: call_assessment (retailer_questionnaire) + one answer per question, family self; the plan outlet is marked done locally. */
    fun queryQueue(userId: Long): com.aktcl.aron.feature.tso.mycall.QueryQueue = com.aktcl.aron.feature.tso.mycall.QueryQueue { q ->
        val box = checkNotNull(outbox) { "no outbox" }
        val a = com.aktcl.aron.feature.tso.mycall.QueryIds.assessment(q.planUuid, q.outletId)
        val score = q.answers.values.count { it }
        val boolCount = q.rubric.criteria.count { !it.text }
        val recs = mutableListOf(TsoOutbox.Rec(
            com.aktcl.aron.contract.RecordType.CALL_ASSESSMENT, a,
            kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive("retailer_questionnaire"))
                put("visit_client_uuid", kotlinx.serialization.json.JsonNull)
                put("visit_plan_outlet_client_uuid", kotlinx.serialization.json.JsonPrimitive(com.aktcl.aron.feature.tso.mycall.PlanIds.planOutlet(q.planUuid, q.outletId)))
                put("rubric_id", kotlinx.serialization.json.JsonPrimitive(q.rubric.id)); put("rubric_version", kotlinx.serialization.json.JsonPrimitive(q.rubric.version))
                put("total_score", kotlinx.serialization.json.JsonPrimitive(score)); put("max_score", kotlinx.serialization.json.JsonPrimitive(boolCount))
                put("delegate_task", kotlinx.serialization.json.JsonPrimitive(q.delegate))
            }, routeId = q.routeId,
        ))
        // only the questions the rep answered get an answer record (answers are optional)
        q.rubric.criteria.forEach { c ->
            val yes = q.answers[c.id]; val text = q.texts[c.id]
            if ((c.text && text == null) || (!c.text && yes == null)) return@forEach
            recs += TsoOutbox.Rec(
                com.aktcl.aron.contract.RecordType.CALL_ASSESSMENT_ANSWER, com.aktcl.aron.feature.tso.mycall.QueryIds.answer(a, c.id),
                kotlinx.serialization.json.buildJsonObject {
                    put("assessment_client_uuid", kotlinx.serialization.json.JsonPrimitive(a)); put("criterion_id", kotlinx.serialization.json.JsonPrimitive(c.id))
                    if (c.text) put("answer_text", kotlinx.serialization.json.JsonPrimitive(text)) else put("answer_bool", kotlinx.serialization.json.JsonPrimitive(yes))
                }, familyUuid = a, rank = 1, routeId = q.routeId,
            )
        }
        val mirrors = listOfNotNull(DONE_MIRROR + q.planUuid + "." + q.outletId to "1", if (q.delegate) TASK_PENDING + q.planUuid + "." + q.outletId to "1" else null)
        box.enqueueAll(userId, recs, mirrors) > 0
    }

    /** F-TSO-016: a `task` record for the SR on the route (self family, rank 0). */
    fun taskQueue(userId: Long): com.aktcl.aron.feature.tso.mycall.TaskQueue = com.aktcl.aron.feature.tso.mycall.TaskQueue { t ->
        val box = checkNotNull(outbox) { "no outbox" }
        // the record and the end of its pending flag commit together: a kill can never leave a flag for a task that exists
        box.enqueueAll(
            userId,
            listOf(TsoOutbox.Rec(
                com.aktcl.aron.contract.RecordType.TASK, t.uuid,
                kotlinx.serialization.json.buildJsonObject {
                    put("task_type_code", kotlinx.serialization.json.JsonPrimitive(t.typeCode)); put("assignee_user_id", kotlinx.serialization.json.JsonPrimitive(t.assigneeUserId))
                    put("outlet_id", kotlinx.serialization.json.JsonPrimitive(t.outletId)); put("title", kotlinx.serialization.json.JsonPrimitive(t.title))
                    put("description", t.comment?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
                    put("due_date", kotlinx.serialization.json.JsonPrimitive(t.dueDate)); put("source_visit_client_uuid", kotlinx.serialization.json.JsonNull)
                }, routeId = t.routeId,
            )),
            deletes = listOfNotNull(t.planUuid?.let { TASK_PENDING + it + "." + t.outletId }),
        ) > 0
    }

    // ---- My Periphery (F-TSO-011/012) ----

    fun periphery(userId: Long, plan: com.aktcl.aron.feature.tso.mycall.PlanSource): com.aktcl.aron.feature.tso.periphery.PeripherySource = object : com.aktcl.aron.feature.tso.periphery.PeripherySource {
        private val J = com.aktcl.aron.feature.tso.periphery.PeripheryJson

        override suspend fun zones() = runCatching { plan.routes() }.getOrNull()?.let { com.aktcl.aron.feature.tso.mycall.zonesOf(it) }

        override suspend fun routeCodes(): Map<Long, String> = runCatching { plan.routes() }.getOrNull().orEmpty().associate { it.id to it.code }

        /** tso-manual A3: the Retailer radii and the stale age of My Team from the bundle config (D-TSO-MAN-34). */
        override suspend fun config(): com.aktcl.aron.feature.tso.periphery.PeripheryConfig {
            val radii = configRaw(userId, "cfg.tso.periphery_radius_options_m")?.let { raw ->
                runCatching { org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getInt(it) } } }.getOrNull()
            }
            return com.aktcl.aron.feature.tso.periphery.PeripheryConfig.of(radii, configInt(userId, "cfg.tso.team_location_max_age_min"))
        }

        /**
         * tso-manual A3: the outlets of the bundle (the zone-wide routes and outlets of the TSO's reach, D-TSO-BUNDLE-OUTLETS)
         * for the offline Retailer search; the routes of today's business date, narrowed to [zoneId].
         */
        override suspend fun bundleOutlets(zoneId: Long?): List<com.aktcl.aron.feature.tso.periphery.BundleOutlet>? = runCatching {
            val dao = meta(userId)
            val routes = dao.routesFor(com.aktcl.aron.rules.BusinessDate.of(env.nowMs()).toString()).filter { zoneId == null || it.zoneId == zoneId }
            TsoBundleReads.nearbyOutlets(routes, routes.associate { it.routeId to dao.outletsOf(it.routeId) })
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        override suspend fun nearby(lat: Double, lng: Double, radiusM: Int, zoneId: Long?): com.aktcl.aron.feature.tso.periphery.Stamped<com.aktcl.aron.feature.tso.periphery.Nearby>? {
            val key = "nearby.${zoneId ?: 0}.$radiusM." + com.aktcl.aron.feature.tso.periphery.positionKey(lat, lng)
            val path = "/v1/outlets/nearby?lat=$lat&lng=$lng&radius_m=$radiusM" + (zoneId?.let { "&zone_id=$it" } ?: "")
            val fresh = get(path)?.let { J.nearby(it) }
            if (fresh != null) { cachePut(userId, key, J.nearbyToCache(fresh), asOfNow()); return com.aktcl.aron.feature.tso.periphery.Stamped(fresh, asOfNow(), false) }
            return cacheGet(userId, key)?.let { (body, asOf) -> J.nearby(body)?.let { com.aktcl.aron.feature.tso.periphery.Stamped(it, asOf, true) } }
        }

        override suspend fun team(zoneId: Long?, date: String): com.aktcl.aron.feature.tso.periphery.Stamped<List<com.aktcl.aron.feature.tso.periphery.TeamMember>>? {
            val key = "team.${zoneId ?: 0}.$date"
            val fresh = get("/v1/team/locations?business_date=$date" + (zoneId?.let { "&zone_id=$it" } ?: ""))?.let { J.team(it) }
            if (fresh != null) { cachePut(userId, key, J.teamToCache(fresh), asOfNow()); return com.aktcl.aron.feature.tso.periphery.Stamped(fresh, asOfNow(), false) }
            return cacheGet(userId, key)?.let { (body, asOf) -> J.team(body)?.let { com.aktcl.aron.feature.tso.periphery.Stamped(it, asOf, true) } }
        }
    }

    /** One balanced-power location on demand: Play services first, the platform provider as the fallback (core-geo). */
    fun fix(context: android.content.Context): com.aktcl.aron.feature.tso.periphery.PeripheryFix {
        val access = com.aktcl.aron.core.geo.AndroidLocationAccess(context)
        val source = com.aktcl.aron.core.geo.FallbackLocationSource(com.aktcl.aron.core.geo.FusedLocationSource(context), com.aktcl.aron.core.geo.PlatformLocationSource(context))
        return com.aktcl.aron.feature.tso.periphery.PeripheryFix {
            when (access.state(requirePrecise = false)) {
                com.aktcl.aron.core.geo.LocationAccessState.PERMISSION_DENIED -> com.aktcl.aron.feature.tso.periphery.FixResult.PermissionNeeded
                com.aktcl.aron.core.geo.LocationAccessState.LOCATION_OFF -> com.aktcl.aron.feature.tso.periphery.FixResult.LocationOff
                com.aktcl.aron.core.geo.LocationAccessState.OK -> when (val r = source.currentLocation(com.aktcl.aron.core.geo.FixPriority.BALANCED, 20_000L)) {
                    is com.aktcl.aron.core.geo.SourceResult.Located -> com.aktcl.aron.feature.tso.periphery.FixResult.Ok(r.location.lat, r.location.lng, r.location.accuracyM, r.location.isMock)
                    else -> com.aktcl.aron.feature.tso.periphery.FixResult.Failed
                }
            }
        }
    }

    /** F-TSO-018: the one compressed photo of a feedback, through the shared camera and media queue. */
    fun feedbackPhotos(media: com.aktcl.aron.core.media.MediaComponents, resolver: android.content.ContentResolver? = null): com.aktcl.aron.feature.tso.feedback.FeedbackPhotos = object : com.aktcl.aron.feature.tso.feedback.FeedbackPhotos {
        override suspend fun take(photoUuid: String): String? = media.capture(photoUuid)?.let { photoUuid }
        override suspend fun discard(photoUuid: String) { media.discard(photoUuid) }

        /**
         * tso-manual A3 (D-TSO-MAN-36, D-A3-14): A2's gallery import ([com.aktcl.aron.core.media.GalleryImporter], the core of
         * `MediaComponents.importFromGallery`) stores the picture compressed and EXIF-free under [photoUuid]; Save claims it
         * for the feedback ([feedbackQueue] attaches it before the record commits), so a removed picture or an abandoned form
         * is discarded and never uploads.
         */
        override suspend fun fromGallery(photoUuid: String, feedbackUuid: String, uri: android.net.Uri): com.aktcl.aron.feature.tso.feedback.GalleryPick {
            val r = resolver ?: return com.aktcl.aron.feature.tso.feedback.GalleryPick.UNREADABLE
            return try {
                media.gallery.import(photoUuid, com.aktcl.aron.core.media.MediaRef("feedback", "feedback", feedbackUuid)) { r.openInputStream(uri) }
                com.aktcl.aron.feature.tso.feedback.GalleryPick.OK
            } catch (e: com.aktcl.aron.core.media.GalleryImportException) {
                when (e.reason) {
                    com.aktcl.aron.core.media.GalleryImportException.Reason.NO_SPACE -> com.aktcl.aron.feature.tso.feedback.GalleryPick.NO_SPACE
                    com.aktcl.aron.core.media.GalleryImportException.Reason.TOO_LARGE -> com.aktcl.aron.feature.tso.feedback.GalleryPick.TOO_LARGE
                    com.aktcl.aron.core.media.GalleryImportException.Reason.UNREADABLE -> com.aktcl.aron.feature.tso.feedback.GalleryPick.UNREADABLE
                }
            }
        }
    }

    /** F-TSO-024: the online password change (204); the secrets go into the request body only, never a log or a field of ours. */
    fun changePassword(): com.aktcl.aron.feature.tso.password.ChangePasswordApi = com.aktcl.aron.feature.tso.password.ChangePasswordApi { current, new ->
        val json = org.json.JSONObject().put("current_password", current).put("new_password", new).toString()
        val r = runCatching { client.call(path = "/v1/auth/change-password", auth = CallAuth.Grant(Grant.FULL), build = { post(json.toRequestBody("application/json".toMediaType())) }, decode = { body, _ -> body }) }.getOrNull()
        when {
            r is ApiResult.Success -> com.aktcl.aron.feature.tso.password.CpOutcome.Ok
            r is ApiResult.Transport -> com.aktcl.aron.feature.tso.password.CpOutcome.Offline
            r is ApiResult.Failure && r.httpStatus == 401 -> com.aktcl.aron.feature.tso.password.CpOutcome.WrongCurrent
            r is ApiResult.Failure && r.httpStatus == 429 -> com.aktcl.aron.feature.tso.password.CpOutcome.RateLimited
            r is ApiResult.Failure && r.httpStatus == 400 -> com.aktcl.aron.feature.tso.password.CpOutcome.Policy
            else -> com.aktcl.aron.feature.tso.password.CpOutcome.Failed
        }
    }

    /** F-TSO-010/021, D-TSO-MAN-27/28/32: the filter options, the preview read and the one online POST; no queue, scope from the token only. */
    fun finalSubmit(userId: Long, plan: com.aktcl.aron.feature.tso.mycall.PlanSource): com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi = object : com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi {
        private fun why(r: ApiResult<*>): com.aktcl.aron.feature.tso.finalsubmit.FsFailure = when (r) {
            is ApiResult.Transport -> com.aktcl.aron.feature.tso.finalsubmit.FsFailure.OFFLINE
            is ApiResult.Failure -> if (r.httpStatus == 403) com.aktcl.aron.feature.tso.finalsubmit.FsFailure.FORBIDDEN else if (r.httpStatus in 400..499) com.aktcl.aron.feature.tso.finalsubmit.FsFailure.REFUSED else com.aktcl.aron.feature.tso.finalsubmit.FsFailure.SERVER
            else -> com.aktcl.aron.feature.tso.finalsubmit.FsFailure.SERVER
        }

        override suspend fun zones(): List<Long>? = runCatching { plan.routes() }.getOrNull()?.let { com.aktcl.aron.feature.tso.mycall.zonesOf(it) }

        /**
         * Wing to zone of the TSO's reach (`GET /v1/dashboards/national/filter-options`, D-TSO-MAN-32; the server cuts it to the
         * token's reach, the phone sends no ids). Kept in the user's database, so the pickers open offline with the last copy.
         */
        override suspend fun filterOptions(): com.aktcl.aron.feature.tso.finalsubmit.FsFilterOptions? {
            val fresh = get("/v1/dashboards/national/filter-options")?.takeIf { com.aktcl.aron.feature.tso.finalsubmit.FsGeo.parseFilterOptions(it) != null }
            if (fresh != null) cachePut(userId, "fs.filter", fresh, asOfNow())
            return com.aktcl.aron.feature.tso.finalsubmit.FsGeo.parseFilterOptions(fresh ?: cacheGet(userId, "fs.filter")?.first)
        }

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
            // D-TSO-MAN-28: this uuid's submit was reopened since; the screen reads the preview again (its submit_seq gives the next uuid)
            if (r is ApiResult.Failure && r.httpStatus == 409 && r.problem.code == "ERR_DAY_FINAL_SUBMIT_REOPENED") return com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome.Reopened
            if (r !is ApiResult.Success) return com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome.Failed(why(r))
            return com.aktcl.aron.feature.tso.finalsubmit.FsJson.result(r.value)?.let { com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome.Ok(it) }
                ?: com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome.Failed(com.aktcl.aron.feature.tso.finalsubmit.FsFailure.SERVER)
        }
    }

    suspend fun rubric(userId: Long) = com.aktcl.aron.feature.tso.mycall.RubricJson.retailer(runCatching { meta(userId).section("rubrics") }.getOrNull())
    suspend fun taskTypes(userId: Long) = com.aktcl.aron.feature.tso.mycall.TaskTypes.parse(runCatching { meta(userId).section("code_lists") }.getOrNull())

    private companion object { const val REFERENCE_TTL_MS = 30L * 60 * 1000; const val PLAN_MIRROR = "tso.unsent.plan."; const val TASK_PENDING = "tso.task.pending."; const val DONE_MIRROR = "tso.visit.done."; const val FEEDBACK_MIRROR = "tso.unsent.feedback."; const val LEAVE_MIRROR = "tso.unsent.leave." }
}
