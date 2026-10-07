package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ResponseJson
import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/*
 * GET /v1/sync/delta and GET /v1/sync/bundle/page (contract getBundleDelta, getBundlePage; docs/24 s4.10 Delta and Size).
 *
 * A delta is the difference between the snapshot the phone holds (named by its cursor) and the bundle the server builds
 * now. The server keeps no bundle rows, only a fingerprint of each snapshot it served (one 64-bit hash per keyed row),
 * so it can name the rows that changed (upsert, full row) and the keys that went (delete) without the old content.
 * Fingerprints live in a bounded in-memory cache per replica (BC-71): a cursor whose fingerprint is not here (another
 * replica, a restart, evicted) or a change a delta cannot carry (user, calendar, product tree, surveys, content, ...)
 * answers 410 `ERR_BUNDLE_CURSOR_EXPIRED`, and the phone fetches the full bundle of the day, which is always correct.
 */

@Serializable
data class DeltaMeta(
    val bundle_version: String,
    val base_cursor: String,
    val cursor: String,
    val valid_for_business_date: String,
    val config_version: Long,
    val server_time: String,
)

@Serializable
data class SectionDelta(val upsert: List<JsonElement>, val delete: List<JsonElement>)

@Serializable
data class BundleDeltaDto(
    val meta: DeltaMeta,
    val sections: Map<String, SectionDelta>,
    val code_lists: List<CodeList>,
    val templates: List<PrintTemplate>,
    val routes_added: List<RouteSnapshot>,
    val routes_removed: List<Long>,
    val day_states: List<RouteDayStateDto>,
    val resolutions: List<Resolution>,
)

@Serializable
data class BundlePageDto(val bundle_version: String, val section: String, val page: Int, val pages: Int, val rows: List<JsonElement>)

/** The decoded opaque cursor `c1|<date>|<seq>|<epoch ms>` (BundleService.cursor). */
data class DeltaCursor(val date: LocalDate, val seq: Long, val at: Instant) {
    companion object {
        private val PATTERN = Regex("^[A-Za-z0-9_-]{8,256}$")

        fun parse(raw: String?): DeltaCursor {
            val bad = ApiProblem(ProblemCode.ERR_VALIDATION, "since must be a delta cursor", errors = listOf(FieldError("query.since", if (raw == null) "required" else "invalid_value")))
            if (raw == null || !PATTERN.matches(raw)) throw bad
            val parts = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8) }.getOrNull()?.split('|') ?: throw bad
            if (parts.size != 4 || parts[0] != "c1") throw bad
            val date = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: throw bad
            val seq = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: throw bad
            val at = parts[3].toLongOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() } ?: throw bad
            return DeltaCursor(date, seq, at)
        }
    }
}

/**
 * One hash per keyed row of a bundle, grouped as the delta names them. `rest` covers every member a delta cannot carry;
 * `config` and `device_policy_version` are left out because the config delta carries them (`GET /v1/config/delta`).
 */
class BundleFingerprint(
    val outlets: Map<Long, Long>,
    val memos: Map<String, Long>,
    val prices: Map<Long, Long>,
    val skus: Map<Long, Long>,
    val tasks: Map<String, Long>,
    val offers: Map<String, Long>,
    val codeLists: Map<String, Long>,
    val templates: Map<String, Long>,
    /** Route-level members of each RouteSnapshot (everything but outlets, open memos and day state). */
    val routes: Map<Long, Long>,
    val dayStates: Map<Long, Long>,
    val rest: Long,
) {
    companion object {
        fun of(b: Bundle): BundleFingerprint = BundleFingerprint(
            outlets = b.routes.flatMap { it.outlets }.associate { it.outlet_id to h(BundleOutlet.serializer(), it) },
            memos = b.routes.flatMap { it.open_memos }.associate { it.memo_client_uuid to h(OpenMemo.serializer(), it) },
            prices = b.prices.associate { it.id to h(SkuPrice.serializer(), it) },
            skus = b.products.skus.associate { it.id to h(SkuDto.serializer(), it) },
            tasks = b.tasks.associate { it.task_uuid to h(TaskDto.serializer(), it) },
            offers = b.offers.mapNotNull { o -> offerKey(o)?.let { it to hash(o) } }.toMap(),
            codeLists = b.code_lists.associate { it.list_key to h(CodeList.serializer(), it) },
            templates = b.templates.associate { it.kind to h(PrintTemplate.serializer(), it) },
            routes = b.routes.associate { it.route_id to hash(routeLevel(it)) },
            dayStates = b.routes.associate { it.route_id to h(RouteDayStateDto.serializer(), it.day_state) },
            rest = hash(
                JsonObject(
                    ResponseJson.encodeToJsonElement(Bundle.serializer(), b).jsonObject.filterKeys { it in REST_MEMBERS } +
                        ("products.nodes" to ResponseJson.encodeToJsonElement(BundleProducts.serializer(), b.products).jsonObject.getValue("nodes")),
                ),
            ),
        )

        /** Bundle members a delta cannot carry: a change in any of them means fetch the full bundle. */
        private val REST_MEMBERS = setOf(
            "user", "calendar", "surveys", "rubrics", "supervisor", "reason_texts", "programmes", "content", "tutorials",
        )

        internal fun routeLevel(r: RouteSnapshot): JsonElement =
            JsonObject(ResponseJson.encodeToJsonElement(RouteSnapshot.serializer(), r).jsonObject - setOf("outlets", "open_memos", "day_state"))

        /** An offer is keyed by its numeric `id`; one without is left out (a delete key must be an id or a uuid). */
        internal fun offerKey(o: JsonObject): String? = (o["id"] as? JsonPrimitive)?.content?.takeIf { it.toLongOrNull()?.let { n -> n >= 1 } == true }

        private fun <T> h(s: kotlinx.serialization.KSerializer<T>, v: T): Long = hash(ResponseJson.encodeToJsonElement(s, v))

        internal fun hash(e: JsonElement): Long =
            ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(ResponseJson.encodeToString(JsonElement.serializer(), e).toByteArray(Charsets.UTF_8))).long
    }
}

/** Bounded LRU of fingerprints per (user, business date, snapshot seq) on this replica. */
class FingerprintCache(private val capacity: Int = 1024) {
    private val map = object : LinkedHashMap<String, BundleFingerprint>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BundleFingerprint>?) = size > capacity
    }

    private fun key(userId: Long, date: LocalDate, seq: Long) = "$userId|$date|$seq"

    fun get(userId: Long, date: LocalDate, seq: Long): BundleFingerprint? = synchronized(this) { map[key(userId, date, seq)] }

    /** The fingerprint is computed outside the lock, so one large bundle never holds up the others. */
    fun putIfAbsent(userId: Long, date: LocalDate, seq: Long, fp: () -> BundleFingerprint) {
        val k = key(userId, date, seq)
        if (synchronized(this) { k in map }) return
        val v = fp()
        synchronized(this) { map.putIfAbsent(k, v) }
    }
}

/** Builds the `BundleDelta` body from the base fingerprint and the current bundle; null when a delta cannot carry it. */
internal object DeltaDiff {
    const val MAX_ROUTES = 20
    const val MAX_DAY_STATES = 40
    const val MAX_ROWS = 5000
    const val MAX_CODE_LISTS = 30
    const val MAX_TEMPLATES = 20

    data class Body(
        val sections: Map<String, SectionDelta>,
        val codeLists: List<CodeList>,
        val templates: List<PrintTemplate>,
        val routesAdded: List<RouteSnapshot>,
        val routesRemoved: List<Long>,
        val dayStates: List<RouteDayStateDto>,
    )

    fun diff(base: BundleFingerprint, now: Bundle): Body? {
        val cur = BundleFingerprint.of(now)
        if (cur.rest != base.rest) return null
        // Routes new to the phone or whose route-level members changed come whole (the phone replaces the route and its outlets).
        val added = now.routes.filter { base.routes[it.route_id] != cur.routes[it.route_id] }
        val addedIds = added.map { it.route_id }.toSet()
        val removed = base.routes.keys.filter { it !in cur.routes }
        val dayStates = now.routes.filter { it.route_id !in addedIds && base.dayStates[it.route_id] != cur.dayStates[it.route_id] }.map { it.day_state }

        val sections = LinkedHashMap<String, SectionDelta>()
        fun <K, T> keyed(name: String, rows: List<T>, key: (T) -> K, baseMap: Map<K, Long>, curMap: Map<K, Long>, enc: (T) -> JsonElement, wire: (K) -> JsonElement) {
            val up = rows.filter { baseMap[key(it)] != curMap[key(it)] }.map(enc)
            val del = baseMap.keys.filter { it !in curMap }.map(wire)
            if (up.isNotEmpty() || del.isNotEmpty()) sections[name] = SectionDelta(up, del)
        }
        // Outlets and open memos of routes sent whole are inside routes_added; deletes are global (a row that left every route).
        keyed("outlets", now.routes.filter { it.route_id !in addedIds }.flatMap { it.outlets }, { it.outlet_id }, base.outlets, cur.outlets,
            { ResponseJson.encodeToJsonElement(BundleOutlet.serializer(), it) }, { JsonPrimitive(it) })
        keyed("open_memos", now.routes.filter { it.route_id !in addedIds }.flatMap { it.open_memos }, { it.memo_client_uuid }, base.memos, cur.memos,
            { ResponseJson.encodeToJsonElement(OpenMemo.serializer(), it) }, { JsonPrimitive(it) })
        keyed("prices", now.prices, { it.id }, base.prices, cur.prices, { ResponseJson.encodeToJsonElement(SkuPrice.serializer(), it) }, { JsonPrimitive(it) })
        keyed("skus", now.products.skus, { it.id }, base.skus, cur.skus, { ResponseJson.encodeToJsonElement(SkuDto.serializer(), it) }, { JsonPrimitive(it) })
        keyed("tasks", now.tasks, { it.task_uuid }, base.tasks, cur.tasks, { ResponseJson.encodeToJsonElement(TaskDto.serializer(), it) }, { JsonPrimitive(it) })
        keyed("offers", now.offers.filter { BundleFingerprint.offerKey(it) != null }, { BundleFingerprint.offerKey(it)!! }, base.offers, cur.offers, { it }, { k -> JsonPrimitive(k.toLong()) })

        // Code lists and templates are replaced whole; one that disappeared cannot be expressed (no delete member).
        if (base.codeLists.keys.any { it !in cur.codeLists } || base.templates.keys.any { it !in cur.templates }) return null
        val codeLists = now.code_lists.filter { base.codeLists[it.list_key] != cur.codeLists[it.list_key] }
        val templates = now.templates.filter { base.templates[it.kind] != cur.templates[it.kind] }

        if (added.size > MAX_ROUTES || removed.size > MAX_ROUTES || dayStates.size > MAX_DAY_STATES ||
            codeLists.size > MAX_CODE_LISTS || templates.size > MAX_TEMPLATES ||
            sections.values.any { it.upsert.size > MAX_ROWS || it.delete.size > MAX_ROWS }
        ) return null
        return Body(sections, codeLists, templates, added, removed, dayStates)
    }
}

/** Paging of large sections (docs/24 s4.10 Size): which sections are paged, their rows in order, and the bundle without them. */
internal object BundlePaging {
    /**
     * Sections this build pages. `open_memos` is not paged: an `OpenMemo` row carries no `route_id` (additionalProperties
     * false), so the phone could not place a paged memo in its route; `content` rows are not among the page row types.
     * Both stay whole in the bundle (an SR's are small; the AMO zone-wide bundle is F-AMO-044).
     */
    private val PAGEABLE = listOf("outlets", "prices", "offers", "tasks")

    fun rows(b: Bundle, section: String): List<JsonElement> = when (section) {
        "outlets" -> b.routes.flatMap { r -> r.outlets.map { ResponseJson.encodeToJsonElement(BundleOutlet.serializer(), it) } }
        "prices" -> b.prices.map { ResponseJson.encodeToJsonElement(SkuPrice.serializer(), it) }
        "offers" -> b.offers
        "tasks" -> b.tasks.map { ResponseJson.encodeToJsonElement(TaskDto.serializer(), it) }
        else -> emptyList()
    }

    fun paged(b: Bundle, threshold: Int, pageRows: Int): List<PagedSection> = PAGEABLE.mapNotNull { s ->
        val n = rows(b, s).size
        if (n > threshold) PagedSection(s, (n + pageRows - 1) / pageRows, n) else null
    }

    fun strip(b: Bundle, paged: List<PagedSection>): Bundle {
        if (paged.isEmpty()) return b
        val names = paged.map { it.section }.toSet()
        return b.copy(
            routes = b.routes.map { r ->
                if ("outlets" in names) r.copy(outlets = emptyList()) else r
            },
            prices = if ("prices" in names) emptyList() else b.prices,
            offers = if ("offers" in names) emptyList() else b.offers,
            tasks = if ("tasks" in names) emptyList() else b.tasks,
        )
    }

    fun page(rows: List<JsonElement>, page: Int, pageRows: Int): List<JsonElement> =
        rows.drop((page - 1) * pageRows).take(pageRows)

    fun parseVersion(raw: String?): Pair<LocalDate, Long>? {
        val m = raw?.let { Regex("^(\\d{4}-\\d{2}-\\d{2}):(\\d{1,9})$").matchEntire(it) } ?: return null
        val d = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return null
        return d to m.groupValues[2].toLong()
    }
}
