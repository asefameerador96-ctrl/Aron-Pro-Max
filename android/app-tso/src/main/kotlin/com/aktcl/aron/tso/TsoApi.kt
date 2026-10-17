package com.aktcl.aron.tso

import android.content.Context
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant

/** What a read gave: fresh from the server, the last answer kept on this phone, refused/absent, or nothing (offline, no copy). */
sealed interface TsoFetch {
    data class Fresh(val json: String) : TsoFetch
    data class Cached(val json: String) : TsoFetch
    data object Unavailable : TsoFetch
    data object Gone : TsoFetch
}

fun TsoFetch.jsonOrNull(): String? = when (this) { is TsoFetch.Fresh -> json; is TsoFetch.Cached -> json; else -> null }

/**
 * The TSO's online reads (F-TSO-027: a read snapshot, opened and refreshed by the user, never by a timer). Every answer is kept per user as a
 * file in the cache folder (3 days, bounded) so the dashboard still shows its last figures offline, with the server's own "as of" stamp.
 * A refused or absent resource (403/404/501) never serves an old copy. No token is logged.
 */
class TsoApi(private val client: AronApiClient, context: Context, userId: Long) {
    private val dir = java.io.File(context.cacheDir, "tso_api_$userId")

    /** `GET /v1/dashboards/summary` for one day of the TSO's territory; [zoneId] narrows to one zone (a selector, the scope stays the token's). */
    suspend fun summary(date: String, zoneId: Long?): TsoFetch = get("/v1/dashboards/summary", "sum_${zoneId ?: 0}_$date", mapOf("from" to date, "to" to date) + zone(zoneId))

    /** `GET /v1/dashboards/login-submit`: the login and submit counts and the route lists behind them. */
    suspend fun loginSubmit(date: String, zoneId: Long?): TsoFetch = get("/v1/dashboards/login-submit", "login_${zoneId ?: 0}_$date", mapOf("business_date" to date) + zone(zoneId))

    private fun zone(zoneId: Long?) = if (zoneId == null) emptyMap() else mapOf("level" to "zone", "node_id" to zoneId.toString())

    private suspend fun get(path: String, key: String, query: Map<String, String>): TsoFetch {
        val r = client.call(
            path = path, auth = CallAuth.Grant(Grant.FULL),
            build = { url(query.entries.fold(client.origin.path(path).newBuilder()) { b, (k, v) -> b.addQueryParameter(k, v) }.build()); get() },
            decode = { body, _ -> body },
        )
        return when {
            r is ApiResult.Success -> { remember(key, r.value); TsoFetch.Fresh(r.value) }
            r is ApiResult.Failure && (r.httpStatus == 403 || r.httpStatus == 404 || r.httpStatus == 501) -> { file(key).delete(); TsoFetch.Gone }
            else -> cached(key)
        }
    }

    private fun file(key: String) = java.io.File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    private fun cached(key: String): TsoFetch {
        val f = file(key)
        if (!f.isFile) return TsoFetch.Unavailable
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) { f.delete(); return TsoFetch.Unavailable }
        return runCatching { TsoFetch.Cached(f.readText()) }.getOrDefault(TsoFetch.Unavailable)
    }

    private fun remember(key: String, json: String) {
        if (json.length > MAX_COPY_CHARS) { file(key).delete(); return }
        runCatching {
            dir.mkdirs()
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.let { all -> all.take((all.size - MAX_FILES + 1).coerceAtLeast(0)).forEach { it.delete() } }
            file(key).writeText(json)
        }
    }

    /** Logout: nothing of this user's reads stays behind. */
    fun clear() { dir.deleteRecursively() }

    private companion object { const val MAX_FILES = 60; const val MAX_COPY_CHARS = 400_000; const val MAX_AGE_MS = 3L * 24 * 3600 * 1000 }
}
