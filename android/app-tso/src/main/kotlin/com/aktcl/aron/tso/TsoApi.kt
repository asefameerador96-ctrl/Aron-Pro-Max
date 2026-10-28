package com.aktcl.aron.tso

import android.content.Context
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.feature.tso.LoginSubmitTab
import com.aktcl.aron.feature.tso.TsoDashCard
import com.aktcl.aron.feature.tso.TsoDashboards
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** What a read gave: fresh from the server, the last answer kept on this phone, refused/absent, or nothing (offline, no copy). */
sealed interface TsoFetch {
    data class Fresh(val json: String) : TsoFetch
    data class Cached(val json: String) : TsoFetch
    data object Unavailable : TsoFetch
    data object Gone : TsoFetch
}

fun TsoFetch.jsonOrNull(): String? = when (this) { is TsoFetch.Fresh -> json; is TsoFetch.Cached -> json; else -> null }

/**
 * The TSO's online reads (F-TSO-027: a read snapshot, opened and refreshed by the user, never by a timer). Every Dashboard card reads
 * its own `POST /v1/dashboards/national/<card>` (D-TSO-MAN-09; `/v1/dashboards/summary` is not called any more) with the business
 * date as the only filter: the scope is the token's reach and the client never sends scope ids. Every answer is kept per user and
 * per card and day as a file in the cache folder (3 days, bounded) so the Dashboard still shows its last figures offline, with the
 * server's own "as of" stamp. A refused or absent resource (403/404/501) never serves an old copy. No token is logged.
 */
class TsoApi(private val client: AronApiClient, private val dir: java.io.File) {
    constructor(client: AronApiClient, context: Context, userId: Long) : this(client, java.io.File(context.cacheDir, "tso_api_$userId"))

    /** One Dashboard card for [date] (`YYYY-MM-DD`, Dhaka business date). Cache key: the card and the day. */
    suspend fun card(card: TsoDashCard, date: String): TsoFetch =
        post("/v1/dashboards/national/${card.path}", "nat_${card.key}_$date", TsoDashboards.filterBody(date))

    /** The rows of one tab of Login & Bikroy Joma Status (`login-submit/users?tab=`). Cache key: the tab and the day. */
    suspend fun loginSubmitUsers(tab: LoginSubmitTab, date: String): TsoFetch =
        post("/v1/dashboards/national/login-submit/users", "nat_users_${tab.wire}_$date", TsoDashboards.filterBody(date), mapOf("tab" to tab.wire))

    /** The POST helper of the cards: [body] is the JSON filter, [key] the per-card cache key, [query] extra query parameters. */
    suspend fun post(path: String, key: String, body: String, query: Map<String, String> = emptyMap()): TsoFetch {
        val r = client.call(
            path = path, auth = CallAuth.Grant(Grant.FULL),
            build = {
                url(query.entries.fold(client.origin.path(path).newBuilder()) { b, (k, v) -> b.addQueryParameter(k, v) }.build())
                post(body.toRequestBody(JSON))
            },
            decode = { b, _ -> b },
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

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_FILES = 60; const val MAX_COPY_CHARS = 400_000; const val MAX_AGE_MS = 3L * 24 * 3600 * 1000
    }
}
