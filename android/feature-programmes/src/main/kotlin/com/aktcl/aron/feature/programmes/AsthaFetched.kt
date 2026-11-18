package com.aktcl.aron.feature.programmes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One month of one route as the server answered it online: its raw rows (kept as sent, parsed on read) and the server's `as_of` of that answer. An empty [items] is a month the server holds nothing for. */
class CachedMonth(val routeId: Long, val month: String, val asOf: String?, val items: JsonArray, val readDate: String? = null)

/**
 * The Astha months read online, kept on the phone (task: quarter chips with fetched months). An app-private file per route and month
 * (`r<route>_<month>.json`), no database change. A file is written whole to a temporary name and moved into place, so a kill in the middle leaves the
 * old file or none, never half of one; an unreadable file is ignored, never a crash. At most [KEEP_MONTHS] months are kept per route (the newest).
 * Nothing here holds a secret or a person's data beyond the route's own targets, and the files live in the app's private storage.
 */
class AsthaMonthCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun file(routeId: Long, month: String) = File(dir, "r${routeId}_$month.json")

    fun put(routeId: Long, month: String, asOf: String?, items: JsonArray, readDate: String? = DhakaTime.dateOf(asOf)) {
        if (!MONTH.matches(month)) return
        dir.mkdirs()
        val body = JsonObject(mapOf("v" to JsonPrimitive(1), "route_id" to JsonPrimitive(routeId), "month" to JsonPrimitive(month), "as_of" to (asOf?.let { JsonPrimitive(it) } ?: JsonNull), "read_date" to (readDate?.let { JsonPrimitive(it) } ?: JsonNull), "items" to items)).toString()
        val tmp = File(dir, "r${routeId}_$month.json.tmp")
        tmp.writeText(body)
        Files.move(tmp.toPath(), file(routeId, month).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        prune(routeId)
    }

    fun read(routeId: Long, month: String): CachedMonth? = runCatching {
        val o = json.parseToJsonElement(file(routeId, month).readText()) as? JsonObject ?: return null
        if ((o["route_id"] as? JsonPrimitive)?.longOrNull != routeId || (o["month"] as? JsonPrimitive)?.contentOrNull != month) return null
        CachedMonth(routeId, month, (o["as_of"] as? JsonPrimitive)?.contentOrNull, o["items"] as? JsonArray ?: return null, (o["read_date"] as? JsonPrimitive)?.contentOrNull)
    }.getOrNull()

    /** Every month kept for the given routes, oldest first. */
    fun readAll(routeIds: Collection<Long>): List<CachedMonth> = routeIds.distinct().flatMap { r -> monthsOf(r).mapNotNull { read(r, it) } }

    private fun monthsOf(routeId: Long): List<String> = dir.listFiles().orEmpty().mapNotNull { f ->
        Regex("^r${routeId}_(\\d{4}-(0[1-9]|1[0-2]))\\.json$").matchEntire(f.name)?.groupValues?.get(1)
    }.sorted()

    private fun prune(routeId: Long) { monthsOf(routeId).dropLast(KEEP_MONTHS).forEach { file(routeId, it).delete() } }

    companion object {
        const val KEEP_MONTHS = 24
        private val MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")
    }
}

/** What one page request came to; [Page] carries the raw `AsthaTargetPage` body. */
sealed interface PageOutcome {
    data class Page(val body: String) : PageOutcome
    /** No connection (offline, timeout, edge page): say "connect once", never an error. */
    data object Offline : PageOutcome
    /** The server answered with a refusal or a body that could not be read. */
    data object Failed : PageOutcome
}

/** The state of the read of one quarter, for the screen. */
enum class QuarterFetch { LOADING, OFFLINE, FAILED }

/**
 * Reads one quarter of one or more routes online, page by page, and keeps every month of it in [cache] (a month with no rows is kept as an empty month, so "the server has
 * nothing" differs from "never downloaded"). A route is written only when all its pages came, so the cache never holds half a month; a route that fails leaves the older files
 * alone. [fetchPage] is the one network call (quarter, route, cursor), a thin wrapper of `SyncApi.listAsthaTargets`.
 */
class AsthaQuarterFetcher(private val cache: AsthaMonthCache, private val fetchPage: suspend (quarter: String, routeId: Long, cursor: String?) -> PageOutcome) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when every route was read and kept; else why not ([QuarterFetch.OFFLINE] wins over [QuarterFetch.FAILED] only when nothing else failed). */
    suspend fun fetch(quarter: String, routeIds: Collection<Long>, months: List<String>): QuarterFetch? {
        var worst: QuarterFetch? = null
        for (route in routeIds.distinct()) {
            when (val r = readRoute(quarter, route)) {
                is RouteRead.Ok -> {
                    // the months the quarter holds (as the caller computed them from the configured start month) and any other month the server sent rows for: the server's own month list is authoritative
                    val sent = r.items.mapNotNull { ((it as? JsonObject)?.get("month") as? JsonPrimitive)?.contentOrNull }
                    (months + sent).distinct().forEach { m ->
                        cache.put(route, m, r.asOf, JsonArray(r.items.filter { ((it as? JsonObject)?.get("month") as? JsonPrimitive)?.contentOrNull == m }))
                    }
                }
                RouteRead.Offline -> if (worst != QuarterFetch.FAILED) worst = QuarterFetch.OFFLINE
                RouteRead.Failed -> worst = QuarterFetch.FAILED
            }
        }
        return worst
    }

    private sealed interface RouteRead {
        data class Ok(val asOf: String?, val items: List<JsonElement>) : RouteRead
        data object Offline : RouteRead
        data object Failed : RouteRead
    }

    private suspend fun readRoute(quarter: String, route: Long): RouteRead {
        val items = ArrayList<JsonElement>()
        var asOf: String? = null
        var cursor: String? = null
        val seen = HashSet<String>()
        repeat(MAX_PAGES) {
            val body = when (val p = fetchPage(quarter, route, cursor)) {
                is PageOutcome.Page -> p.body
                PageOutcome.Offline -> return RouteRead.Offline
                PageOutcome.Failed -> return RouteRead.Failed
            }
            val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return RouteRead.Failed
            val page = o["items"] as? JsonArray ?: return RouteRead.Failed
            // only rows of this route that parse as a target row are kept (a malformed one is skipped, as the bundle reader does)
            page.forEach { e -> val ro = e as? JsonObject; if (ro != null && AsthaModel.row(ro)?.routeId == route) items += ro }
            asOf = asOf ?: (o["as_of"] as? JsonPrimitive)?.contentOrNull
            val next = (o["next_cursor"] as? JsonPrimitive)?.contentOrNull
            if (next == null) return RouteRead.Ok(asOf, items)
            if (!seen.add(next)) return RouteRead.Failed // a cursor that repeats would loop for ever
            cursor = next
        }
        return RouteRead.Failed // more pages than any real quarter has
    }

    companion object { const val MAX_PAGES = 40 }
}

/** Puts the months kept on the phone beside the bundle's own figures. */
object AsthaFetched {
    /**
     * [data] (the bundle's rows, already with this phone's memos) plus the cached months, by SOURCE PER MONTH with no timestamp deciding anything: a month the bundle holds comes from the bundle
     * (with the phone's own memos, rule B), whole or cut short (a cut month keeps its rows and a "list cut short" note and is never replaced by a read, which carries no local memos); a month the
     * bundle does not hold comes from the kept online read, as the server's figure at that read with its own date.
     */
    fun merge(data: AsthaData, cached: List<CachedMonth>): AsthaData {
        if (cached.isEmpty()) return data
        val bundleMonths = data.rows.map { it.month }.toSet()
        var rows = data.rows
        val fetched = LinkedHashMap<String, FetchedMonth>()
        for (c in cached) {
            if (c.month in bundleMonths) continue
            rows = rows + c.items.mapNotNull { (it as? JsonObject)?.let(AsthaModel::row) }.filter { it.routeId == c.routeId && it.month == c.month }
            // routes of one month may be read at different times: the earliest read date is the honest one (it decides whether the month was open)
            val prev = fetched[c.month]
            fetched[c.month] = if (prev == null || (c.readDate ?: "") < (prev.readDate ?: "")) FetchedMonth(c.asOf, c.readDate) else prev
        }
        return data.copy(rows = rows, fetchedMonths = fetched)
    }
}
