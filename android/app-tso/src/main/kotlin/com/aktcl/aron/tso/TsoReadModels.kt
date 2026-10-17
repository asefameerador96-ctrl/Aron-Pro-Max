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
            val body = get("/v1/leave") ?: return null
            val page = LeaveJson.parsePage(body) ?: return null
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

    private companion object { const val LEAVE_MIRROR = "tso.unsent.leave." }
}
