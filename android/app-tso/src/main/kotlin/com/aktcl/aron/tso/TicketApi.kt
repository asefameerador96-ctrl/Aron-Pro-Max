package com.aktcl.aron.tso

import android.content.Context
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.feature.tickets.Ticket
import com.aktcl.aron.feature.tickets.TicketSnapshot
import com.aktcl.aron.feature.tickets.TicketWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

// tso-manual A2 (build contract s5 A2, contract 1.10.0): the Tickets screens' online reads and their kept copies.

/**
 * The tickets' reads (`listSupportTickets`, `getSupportTicketSummary`, `getSupportTicket`): asked only when a ticket screen
 * opens or is pulled to refresh, never on a timer (constraint 3). The scope is the token's (the reporter sees its own
 * tickets, D-TSO-MAN-22): no user, zone or scope id is ever sent. No token is logged.
 *
 * Its own cache, like [TsoApi] (D-A2-18): every answer the screens show offline is kept as a file per user in
 * `cacheDir/tso_api_<userId>/tickets/`, inside the folder the logout already deletes with the dashboard copies, so a
 * shared phone never shows one rep's tickets to the next. Bounded: [MAX_FILES] files, [MAX_AGE_MS] old, [MAX_COPY_CHARS]
 * per copy.
 */
class TicketApi(private val client: AronApiClient, private val dir: File, private val fileClockMs: () -> Long = System::currentTimeMillis) {
    constructor(client: AronApiClient, context: Context, userId: Long) : this(client, File(context.cacheDir, "tso_api_$userId/tickets"))

    /** What one read gave. */
    sealed interface Read {
        data class Ok(val body: String) : Read

        /** 403 or 404: refused or not there (a detail answers 404 for an unsent ticket or one that is not the caller's). */
        data class Refused(val httpStatus: Int) : Read

        /** Offline, a timeout or a server error: whatever copy is kept stays. */
        data object Unavailable : Read
    }

    /** `GET /v1/support/tickets?limit=&cursor=`: one page of the own tickets, newest first. */
    suspend fun listSupportTickets(cursor: String? = null, limit: Int = PAGE_SIZE): Read =
        get(PATH, buildMap { put("limit", limit.coerceIn(1, PAGE_SIZE).toString()); if (cursor != null) put("cursor", cursor) })

    /** `GET /v1/support/tickets/summary`: the counts (no percentages on the wire, R-F-14). */
    suspend fun summary(): Read = get("$PATH/summary", emptyMap())

    /** `GET /v1/support/tickets/{uuid}`: one ticket with its thread. A value that is not a UUID is never put in a path. */
    suspend fun get(ticketUuid: String): Read =
        if (!UUID.matches(ticketUuid)) Read.Refused(404) else get("$PATH/${ticketUuid.lowercase()}", emptyMap())

    private suspend fun get(path: String, query: Map<String, String>): Read {
        val r = try {
            client.call(
                path = path, auth = CallAuth.Grant(Grant.FULL),
                build = { url(query.entries.fold(client.origin.path(path).newBuilder()) { b, (k, v) -> b.addQueryParameter(k, v) }.build()); get() },
                decode = { body, _ -> body },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return Read.Unavailable
        }
        return when {
            r is ApiResult.Success -> Read.Ok(r.value)
            r is ApiResult.Failure && (r.httpStatus == 403 || r.httpStatus == 404) -> Read.Refused(r.httpStatus)
            else -> Read.Unavailable
        }
    }

    // ---- kept copies (plain files, like TsoApi; read and written off the main thread by the callers' dispatcher)

    fun keepList(text: String) = remember(LIST, text)
    fun keptList(): String? = kept(LIST)
    fun forgetList() { file(LIST).delete() }

    fun keepDetail(ticketUuid: String, body: String) { if (UUID.matches(ticketUuid)) remember(DETAIL + ticketUuid.lowercase(), body) }
    fun keptDetail(ticketUuid: String): String? = if (UUID.matches(ticketUuid)) kept(DETAIL + ticketUuid.lowercase()) else null
    fun forgetDetail(ticketUuid: String) { if (UUID.matches(ticketUuid)) file(DETAIL + ticketUuid.lowercase()).delete() }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    private fun kept(key: String): String? {
        val f = file(key)
        if (!f.isFile) return null
        if (fileClockMs() - f.lastModified() > MAX_AGE_MS) { f.delete(); return null }
        return runCatching { f.readText() }.getOrNull()
    }

    private fun remember(key: String, text: String) {
        if (text.length > MAX_COPY_CHARS) { file(key).delete(); return }
        runCatching {
            dir.mkdirs()
            val now = fileClockMs()
            dir.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
            dir.listFiles()?.filter { it.name != file(key).name }?.sortedBy { it.lastModified() }
                ?.let { all -> all.take((all.size - MAX_FILES + 1).coerceAtLeast(0)).forEach { it.delete() } }
            // written whole, then renamed: a kill mid-write never leaves half a copy
            val tmp = File(dir, file(key).name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file(key))) { file(key).delete(); tmp.renameTo(file(key)) }
            file(key).setLastModified(now)
        }
    }

    companion object {
        const val PATH = "/v1/support/tickets"
        /** The contract's page maximum. */
        const val PAGE_SIZE = 200
        /** Pages read per refresh: 1000 own tickets; a longer history is cut and the summary gives the counts (D-A2-07). */
        const val MAX_PAGES = 5
        const val MAX_FILES = 40
        const val MAX_COPY_CHARS = 2_000_000
        const val MAX_AGE_MS = 14L * 24 * 3600 * 1000
        private const val LIST = "list"
        private const val DETAIL = "d_"
        val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

/**
 * The kept copy of the own tickets: the raw pages and the summary exactly as the server sent them (so the screens' one
 * mapping, [TicketWire], reads both the fresh answer and the copy), whether every page was read, and the "as of" time.
 */
object TicketCopy {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(pages: List<String>, summary: String?, complete: Boolean, asOfMs: Long): String = JsonObject(
        mapOf(
            "v" to JsonPrimitive(1),
            "as_of_ms" to JsonPrimitive(asOfMs),
            "complete" to JsonPrimitive(complete),
            "pages" to JsonArray(pages.map { json.parseToJsonElement(it) }),
            "summary" to (summary?.let { json.parseToJsonElement(it) } ?: JsonNull),
        ),
    ).toString()

    /** The snapshot of [pages] and [summary]; the summary's own `as_of` wins over [fallbackAsOfMs]. Null when no page decodes. */
    fun snapshot(pages: List<String>, summary: String?, complete: Boolean, fallbackAsOfMs: Long): TicketSnapshot? {
        val decoded = pages.map { TicketWire.page(it) ?: return null }
        val tickets: List<Ticket> = decoded.flatMap { it.first }.distinctBy { it.uuid }
        val s = summary?.let { TicketWire.summary(it) }
        return TicketSnapshot(tickets, complete, s?.first, s?.second ?: fallbackAsOfMs)
    }

    /** A kept copy back to its snapshot (the copy's own "as of"); null when unreadable. */
    fun decode(text: String): TicketSnapshot? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val pages = (o["pages"] as JsonArray).map { it.toString() }
        val summary = o["summary"]?.takeUnless { it is JsonNull }?.toString()
        val complete = (o["complete"] as? JsonPrimitive)?.booleanOrNull ?: false
        val asOf = (o["as_of_ms"] as JsonPrimitive).longOrNull ?: return null
        snapshot(pages, summary, complete, asOf)?.let { it.copy(asOfMs = asOf) }
    }.getOrNull()
}
