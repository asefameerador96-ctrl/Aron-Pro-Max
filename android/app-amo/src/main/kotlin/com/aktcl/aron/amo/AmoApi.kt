package com.aktcl.aron.amo

import android.content.Context
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.feature.amo.TaskDelegation

/** What an online read gave: fresh from the server, the last answer kept on this phone, or nothing. */
sealed interface AmoFetch {
    data class Fresh(val json: String) : AmoFetch
    data class Cached(val json: String) : AmoFetch
    data object Unavailable : AmoFetch
}

/**
 * The AMO's online reads (docs/24 s3: the zone's lists that are not in the bundle). Every read keeps its last answer per user on this
 * phone so the screen still shows something offline; nothing here is ever in the way of a sale and no token is logged.
 */
class AmoApi(private val client: AronApiClient, context: Context, userId: Long) {
    private val prefs = context.getSharedPreferences("amo_api_$userId", Context.MODE_PRIVATE)

    suspend fun outletRequest(uuid: String): AmoFetch = get("/v1/outlet-requests/$uuid", "req_$uuid")

    /** `GET /v1/team/locations` (F-API-023): the last synced fix of each SR of the zone. */
    suspend fun teamLocations(businessDate: String): AmoFetch = get("/v1/team/locations", "team_loc", mapOf("business_date" to businessDate))

    /** `GET /v1/team/stock` (F-API-024): issued, sold, returned and current stock per SR and SKU for [businessDate]. */
    suspend fun teamStock(businessDate: String): AmoFetch = get("/v1/team/stock", "team_stock_$businessDate", mapOf("business_date" to businessDate))

    /** `GET /v1/tasks` created from [fromDate] on (F-API-026), all pages up to a few; the caller keeps the ones it assigned. */
    suspend fun tasks(fromDate: String): AmoFetch {
        val first = get("/v1/tasks", null, mapOf("from" to fromDate, "limit" to "200"))
        if (first !is AmoFetch.Fresh) return cached("tasks")
        // The reach holds every task of the zone: follow the cursor (a few pages) so the AMO's own are not cut off at 200.
        val pages = mutableListOf(first.json)
        var cursor = TaskDelegation.nextCursor(first.json)
        while (cursor != null && pages.size < MAX_PAGES) {
            val next = get("/v1/tasks", null, mapOf("from" to fromDate, "limit" to "200", "cursor" to cursor)) as? AmoFetch.Fresh ?: break
            pages += next.json; cursor = TaskDelegation.nextCursor(next.json)
        }
        val all = TaskDelegation.combine(pages)
        remember("tasks", all)
        return AmoFetch.Fresh(all)
    }

    private suspend fun get(path: String, key: String?, query: Map<String, String> = emptyMap()): AmoFetch {
        val r = client.call(
            path = path, auth = CallAuth.Grant(Grant.FULL),
            build = { url(query.entries.fold(client.origin.path(path).newBuilder()) { b, (k, v) -> b.addQueryParameter(k, v) }.build()); get() },
            decode = { body, _ -> body },
        )
        return when (r) {
            is ApiResult.Success -> { if (key != null) remember(key, r.value); AmoFetch.Fresh(r.value) }
            // Out of reach or gone: never serve an old copy of something the server now refuses.
            is ApiResult.Failure -> if (r.httpStatus == 403 || r.httpStatus == 404) { key?.let(::forget); AmoFetch.Unavailable } else key?.let(::cached) ?: AmoFetch.Unavailable
            else -> key?.let(::cached) ?: AmoFetch.Unavailable
        }
    }

    private fun cached(key: String): AmoFetch {
        val at = prefs.getLong("$key$AT", 0L)
        if (System.currentTimeMillis() - at > MAX_AGE_MS) { forget(key); return AmoFetch.Unavailable } // a month-old copy is not shown
        return prefs.getString(key, null)?.let { AmoFetch.Cached(it) } ?: AmoFetch.Unavailable
    }

    private fun forget(key: String) = prefs.edit().remove(key).remove("$key$AT").apply()

    /** Logout or a switched user: nothing of this user's reads stays behind. */
    fun clear() = prefs.edit().clear().apply()

    private fun remember(key: String, json: String) {
        if (prefs.all.size >= 2 * MAX_ENTRIES) prefs.edit().clear().apply() // bounded: the lists are re-read when online
        prefs.edit().putString(key, json).putLong("$key$AT", System.currentTimeMillis()).apply()
    }

    private companion object { const val MAX_PAGES = 5; const val MAX_ENTRIES = 300; const val AT = "_at"; const val MAX_AGE_MS = 30L * 24 * 3600 * 1000 }
}
