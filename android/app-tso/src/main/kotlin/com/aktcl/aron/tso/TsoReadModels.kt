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
class TsoReadModels(private val components: SessionComponents, private val databases: UserDatabases) {
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

        // REQUEST: docs/requests/android-tso-record-mapping.md (F-TSO-009: unsent leave applications come from the outbox)
        override suspend fun unsent() = emptyList<com.aktcl.aron.feature.tso.leave.LeaveItem>()

        override suspend fun refresh(): LeaveSource.Cached? {
            val body = get("/v1/leave") ?: return null
            val page = LeaveJson.parsePage(body) ?: return null
            val asOf = asOfNow()
            cachePut(userId, "leave", LeaveJson.toCache(page.items), asOf)
            return LeaveSource.Cached(page.items, asOf)
        }
    }
}
