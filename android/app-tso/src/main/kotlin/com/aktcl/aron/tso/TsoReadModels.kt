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

    private companion object { const val FEEDBACK_MIRROR = "tso.unsent.feedback."; const val LEAVE_MIRROR = "tso.unsent.leave." }
}
