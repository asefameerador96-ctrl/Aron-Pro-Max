package com.aktcl.aron.amo

import android.content.Context
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.feature.amo.TaskDelegation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
    private val dir = java.io.File(context.cacheDir, "amo_api_$userId")

    suspend fun outletRequest(uuid: String): AmoFetch = get("/v1/outlet-requests/$uuid", "req_$uuid")

    /** `GET /v1/team/locations` (F-API-023): the last synced fix of each SR of the zone. */
    suspend fun teamLocations(businessDate: String): AmoFetch = get("/v1/team/locations", "team_loc_$businessDate", mapOf("business_date" to businessDate))

    /**
     * `GET /v1/dashboards/login-submit` (Bikroy Joma status): the day's full KPI block (sales, calls, location checks, login and submit)
     * and the route lists behind the counts, in one answer (F-API-016). [zoneId] null is the caller's whole reach.
     */
    suspend fun liveDashboard(date: String, zoneId: Long?): AmoFetch = get("/v1/dashboards/login-submit", "live_${zoneId ?: 0}_$date", mapOf("business_date" to date) + zoneQuery(zoneId))

    private fun zoneQuery(zoneId: Long?) = if (zoneId == null) emptyMap() else mapOf("level" to "zone", "node_id" to zoneId.toString())

    /** `GET /v1/team/stock` (F-API-024): issued, sold, returned and current stock per SR and SKU for [businessDate]. */
    suspend fun teamStock(businessDate: String): AmoFetch = get("/v1/team/stock", "team_stock_$businessDate", mapOf("business_date" to businessDate))

    /** `GET /v1/tasks` created from [fromDate] on (F-API-026), all pages up to a few; the caller keeps the ones it assigned. */
    /** A report page by page (a few pages of 500 rows), joined; the kept copy is the joined answer when it is small enough. */
    suspend fun report(key: String, from: String, to: String): AmoFetch {
        val cacheKey = "rep_${key}_${from}_$to"
        val first = post("/v1/reports/$key/query", com.aktcl.aron.feature.amo.Reports.body(from, to, 1))
        if (first !is AmoFetch.Fresh) return cached(cacheKey)
        val pages = mutableListOf(first.json)
        val total = com.aktcl.aron.feature.amo.Reports.parse(first.json)?.totalRows ?: 0
        var page = 2
        while (pages.size < MAX_PAGES && (pages.size * 500) < total) {
            val next = post("/v1/reports/$key/query", com.aktcl.aron.feature.amo.Reports.body(from, to, page++)) as? AmoFetch.Fresh ?: break
            pages += next.json
        }
        val all = com.aktcl.aron.feature.amo.Reports.combine(pages)
        remember(cacheKey, all)
        return AmoFetch.Fresh(all)
    }

    private suspend fun post(path: String, body: String): AmoFetch {
        val r = client.call(
            path = path, auth = CallAuth.Grant(Grant.FULL),
            build = { post(body.toRequestBody("application/json".toMediaType())) }, decode = { b, _ -> b },
        )
        return if (r is ApiResult.Success) AmoFetch.Fresh(r.value) else AmoFetch.Unavailable
    }

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

    private fun file(key: String) = java.io.File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    private fun cached(key: String): AmoFetch {
        val f = file(key)
        if (!f.isFile) return AmoFetch.Unavailable
        if (System.currentTimeMillis() - f.lastModified() > ttl(key)) { f.delete(); return AmoFetch.Unavailable } // an old copy is not shown
        return runCatching { AmoFetch.Cached(f.readText()) }.getOrDefault(AmoFetch.Unavailable)
    }

    private fun forget(key: String) { file(key).delete() }

    /** Logout or a switched user: nothing of this user's reads stays behind. */
    fun clear() { dir.deleteRecursively() }

    // Kept copies are files in the cache folder (not preferences, which load whole): bounded by age per family, by count and by size.
    private fun remember(key: String, json: String) {
        if (json.length > MAX_COPY_CHARS) { forget(key); return }
        runCatching {
            dir.mkdirs()
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.let { all -> all.take((all.size - MAX_FILES + 1).coerceAtLeast(0)).forEach { it.delete() } }
            file(key).writeText(json)
        }
    }

    /** Dated lists (team, dashboard, stock) are useful for a few days; a request's details for a month. */
    private fun ttl(key: String) = if (key.startsWith("req_")) MAX_AGE_MS else DATED_AGE_MS

    private companion object {
        const val MAX_PAGES = 5; const val MAX_FILES = 150; const val MAX_COPY_CHARS = 600_000
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000; const val DATED_AGE_MS = 3L * 24 * 3600 * 1000
    }
}
