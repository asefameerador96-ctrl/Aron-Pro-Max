package com.aktcl.aron.tso

import android.content.Context
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.feature.tso.LoginSubmitTab
import com.aktcl.aron.feature.tso.TsoDashCard
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.notifications.TsoInboxes
import com.aktcl.aron.feature.tso.targets.TargetNodeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
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
 * tso-manual A1 step 3: the same reads serve Target Status and Details (`GET /v1/dashboards/targets`, kept per month and node) and the
 * bell inbox (`GET /v1/me/notifications`, the newest page kept; `POST /v1/me/notifications/read`, never kept).
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
    suspend fun post(path: String, key: String, body: String, query: Map<String, String> = emptyMap()): TsoFetch = send(path, key, query, body)

    // ---- tso-manual A1 step 3: Target Status (D-TSO-MAN-39, 40) and the bell inbox (D-TSO-MAN-26) --------------------------------

    /** The GET helper: [key] is the cache key of the answer (null: never kept, e.g. an older inbox page), [query] its parameters. */
    suspend fun get(path: String, key: String?, query: Map<String, String> = emptyMap()): TsoFetch = send(path, key, query, null)

    /**
     * Target Status of [month] (`YYYY-MM`): the caller's top reach node and its zones (`children=zone`), category rows. The nodes come
     * from the token's reach on the server; the request names none. Cache key: the month.
     */
    suspend fun targetStatus(month: String): TsoFetch =
        get(TARGETS, "tgt_status_$month", linkedMapOf("month" to month, "product_level" to "category", "children" to "zone"))

    /**
     * Target Details of one node for [month]: its variant rows. The top node is read without `level`/`node_id` (the server takes the
     * caller's top reach node; an explicit `level=house` is refused, contract GeoLevelQuery); a zone by `level=zone&node_id=<id>`, an
     * id the server itself returned (it answers 403 outside the reach). Cache key: the node and the month.
     */
    suspend fun targetDetails(node: TargetNodeKey, month: String): TsoFetch {
        val q = linkedMapOf("month" to month)
        val level = node.level
        val id = node.id
        if (level != null && id != null) { q["level"] = level; q["node_id"] = id.toString() }
        q["product_level"] = "variant"
        return get(TARGETS, "tgt_details_${node.wire.replace(':', '_')}_$month", q)
    }

    /** The newest page of the inbox (`GET /v1/me/notifications`, 50 rows); kept, so the inbox opens offline with its "as of". */
    suspend fun notifications(): TsoFetch = get(INBOX, "inbox", linkedMapOf("limit" to "$INBOX_PAGE"))

    /** An older page, before [beforeId] (the server's `next_before_id`); never kept. */
    suspend fun olderNotifications(beforeId: Long): TsoFetch = get(INBOX, null, linkedMapOf("before_id" to "$beforeId", "limit" to "$INBOX_PAGE"))

    /** What `POST /v1/me/notifications/read` gave: the unread count left, a refusal (the read is dropped), or no answer (kept, retried). */
    sealed interface MarkRead {
        data class Done(val unread: Int) : MarkRead
        data object Rejected : MarkRead
        data object NoAnswer : MarkRead
    }

    /** Marks everything up to [upTo] read (idempotent on the server: a retry never changes more than the first call). */
    suspend fun markNotificationsRead(upTo: Long): MarkRead {
        val r = client.call(
            path = INBOX_READ, auth = CallAuth.Grant(Grant.FULL),
            build = { url(client.origin.path(INBOX_READ)); post(TsoInboxes.readBody(upTo).toRequestBody(JSON)) },
            decode = { b, _ -> b },
        )
        return when {
            r is ApiResult.Success -> TsoInboxes.readResult(r.value)?.let { MarkRead.Done(it) } ?: MarkRead.NoAnswer
            r is ApiResult.Failure && r.httpStatus in 400..499 && r.httpStatus != 401 && r.httpStatus != 408 && r.httpStatus != 429 -> MarkRead.Rejected
            else -> MarkRead.NoAnswer
        }
    }

    /** One read: fresh (kept under [key]), the kept copy when the server cannot be reached, or Gone when refused (the copy is dropped). */
    private suspend fun send(path: String, key: String?, query: Map<String, String>, body: String?): TsoFetch {
        val r = client.call(
            path = path, auth = CallAuth.Grant(Grant.FULL),
            build = {
                url(query.entries.fold(client.origin.path(path).newBuilder()) { b, (k, v) -> b.addQueryParameter(k, v) }.build())
                if (body != null) post(body.toRequestBody(JSON))
            },
            decode = { b, _ -> b },
        )
        // the kept copies are files: written, listed, deleted and read on the IO pool, never on the caller's (main) thread
        return when {
            r is ApiResult.Success -> { if (key != null) withContext(Dispatchers.IO) { remember(key, r.value) }; TsoFetch.Fresh(r.value) }
            r is ApiResult.Failure && (r.httpStatus == 403 || r.httpStatus == 404 || r.httpStatus == 501) -> { if (key != null) withContext(Dispatchers.IO) { file(key).delete() }; TsoFetch.Gone }
            key == null -> TsoFetch.Unavailable
            else -> withContext(Dispatchers.IO) { cached(key) }
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
        const val TARGETS = "/v1/dashboards/targets"
        const val INBOX = "/v1/me/notifications"
        const val INBOX_READ = "/v1/me/notifications/read"
        const val INBOX_PAGE = 50
        val JSON = "application/json".toMediaType()
        const val MAX_FILES = 60; const val MAX_COPY_CHARS = 400_000; const val MAX_AGE_MS = 3L * 24 * 3600 * 1000
    }
}

/**
 * tso-manual A1: one read per key at a time, and every caller waits for it. A call for a key that is being read joins that read
 * (a double pull, the bell tapped while the Dashboard reads the inbox) and returns when its answer is in the model; another key reads
 * in parallel (a tab switch, another node). The read runs in [scope], the signed-in day's scope, not in the caller's: a screen that
 * leaves (a tab switch, back, the bell) only stops waiting and the answer still lands; the logout cancels the scope. A failed read
 * throws to every caller and frees its key. [busy] is compose state, so a screen's refresh indicator follows its own key.
 */
class TsoReadFlights<K : Any>(private val scope: CoroutineScope) {
    private val running = HashMap<K, Deferred<Exception?>>()
    private val busy = androidx.compose.runtime.mutableStateListOf<K>()

    /** True while [key] is being read. */
    fun isBusy(key: K): Boolean = key in busy

    /** True while any key that [match] accepts is being read. */
    fun anyBusy(match: (K) -> Boolean): Boolean = busy.any(match)

    /** Reads [key] with [read], or joins the read of [key] already running. */
    suspend fun run(key: K, read: suspend () -> Unit) {
        val flight = running[key]?.takeIf { it.isActive } ?: start(key, read)
        flight.await()?.let { throw it }
    }

    private fun start(key: K, read: suspend () -> Unit): Deferred<Exception?> {
        // LAZY: the key is registered before the read can finish, so the completion below always finds it
        val flight = scope.async(start = CoroutineStart.LAZY) {
            try {
                read()
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e // to the callers, never to the day's scope (one failed read must not cancel the others)
            }
        }
        running[key] = flight
        busy += key
        flight.invokeOnCompletion {
            if (running[key] === flight) {
                running.remove(key)
                busy.remove(key)
            }
        }
        flight.start()
        return flight
    }
}
