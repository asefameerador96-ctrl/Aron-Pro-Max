package com.aktcl.aron.core.database.repo

import androidx.room.withTransaction
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.ConfigValueEntity
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.PriceEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.entity.TaskEntity
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.record.RecordMapping
import com.aktcl.aron.core.database.reference.ConfigDeltaWire
import com.aktcl.aron.contract.BundleOutlet
import com.aktcl.aron.contract.ResolvedConfigValue
import com.aktcl.aron.contract.RouteSnapshot
import com.aktcl.aron.contract.Sku
import com.aktcl.aron.contract.SkuPrice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The route of the day as the SR app reads it offline. */
data class RouteDay(val route: RouteEntity, val outlets: List<OutletEntity>)

/** What [ReferenceRepository.applyConfigDelta] did. */
enum class DeltaResult { APPLIED, STALE, GAP }

/** What [ReferenceRepository.applyBundleDelta] did. */
enum class BundleDeltaResult {
    APPLIED,

    /** Already applied, or not newer than the stored bundle: nothing changed. */
    STALE,

    /** The delta does not continue the stored cursor: nothing changed, a full bundle is flagged. */
    GAP,

    /** The delta is for another business date than the stored bundle: fetch the full bundle of the day. */
    NEW_DATE,
}

/** What [ReferenceRepository.apply] did with a bundle. */
enum class ApplyResult {
    /** Written (also when the stored bundle has the same version: re-applying a snapshot is a no-op in effect). */
    APPLIED,

    /** Older than the stored bundle (an earlier date, or a lower snapshot of the same date): ignored, never rolled back. */
    OLDER_IGNORED,

    /**
     * A prefetch of a later day (`meta.is_prefetch`): kept aside, today's reference data untouched, and applied by
     * [ReferenceRepository.promotePrefetch] when that day starts.
     */
    PREFETCH_STORED,
}

/**
 * Reference data of the day bundle (docs/24 s4.10). [apply] replaces routes, outlets, SKUs, prices, config and the raw
 * sections in one transaction, so a kill mid-apply leaves the previous bundle intact; captures and the outbox are never
 * touched by it. Versions only move forward (`<date>:<snapshot_seq>`).
 */
class ReferenceRepository(private val db: AronDatabase) {
    private val dao = db.referenceDao()

    /**
     * Applies [bundle]. [raw] is the whole bundle JSON (with paged sections merged in): every top-level section without a
     * table of its own, and each route's open memos, plan, targets and day state, are stored as raw JSON. [etag] is kept
     * for the next `If-None-Match`.
     */
    suspend fun apply(bundle: BundleReference, raw: JsonObject? = null, etag: String? = null, asDay: Boolean = !bundle.meta.isPrefetch): ApplyResult {
        val date = bundle.meta.validForBusinessDate
        if (!asDay) return storePrefetch(bundle, requireNotNull(raw) { "a prefetch is stored as raw JSON" }, etag)
        val version = bundle.meta.bundleVersion
        val routes = bundle.routes.map { routeRow(it, date, version) }
        val outlets = bundle.routes.flatMap { it.outlets }.map(::outletRow)
        val skus = bundle.products.skus.map(::skuRow)
        val prices = bundle.prices.map(::priceRow)
        val config = bundle.config?.let { c ->
            c.values.map { configRow(it, scheduled = false) } + c.scheduled.map { configRow(it, scheduled = true) }
        }.orEmpty()
        val tasks = (raw?.get("tasks") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::taskRow) }
        val sections = raw?.let(::rawSections).orEmpty().flatMap { sec ->
            chunks(sec.json).mapIndexed { i, part -> BundleSectionEntity(chunkName(sec.name, i), part) }
        }
        return db.withTransaction {
            if (compare(version, dao.meta(KEY_BUNDLE_VERSION)) < 0) return@withTransaction ApplyResult.OLDER_IGNORED
            dao.clearOutlets()
            dao.clearRoutes()
            dao.clearSkus()
            dao.clearPrices()
            // Config moves forward only: a delta may already have brought it past this bundle's version (F-SYS-092).
            val heldConfig = dao.meta(KEY_CONFIG_VERSION)?.toLongOrNull()
            val bundleConfig = bundle.config?.configVersion ?: bundle.meta.configVersion
            val keepConfig = heldConfig != null && bundleConfig != null && bundleConfig < heldConfig
            if (!keepConfig) dao.clearConfig()
            dao.clearSections()
            dao.insertRoutes(routes)
            dao.insertOutlets(outlets)
            dao.insertSkus(skus)
            dao.insertPrices(prices)
            if (!keepConfig) dao.insertConfig(config)
            dao.insertSections(sections)
            dao.clearTasks()
            dao.insertTasks(tasks)
            dao.reapplyLocalResolutions()
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_VERSION, version))
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_DATE, date))
            dao.deleteMeta(KEY_BUNDLE_REFRESH) // a whole snapshot answers a gap
            dao.meta(KEY_PREFETCH_DATE)?.let { if (it <= date) clearPrefetch() } // a prefetch of this day or earlier is spent
            putOrDelete(KEY_BUNDLE_ETAG, etag)
            putOrDelete(KEY_BUNDLE_CURSOR, bundle.meta.cursor)
            putOrDelete(KEY_BUNDLE_SERVER_TIME, bundle.meta.serverTime)
            if (!keepConfig) bundleConfig?.let { dao.putMeta(SyncMetaEntity(KEY_CONFIG_VERSION, it.toString())) }
            ApplyResult.APPLIED
        }
    }

    /** Keeps a later day's snapshot aside (sync_meta), replacing an older prefetch; today's tables are not touched. */
    private suspend fun storePrefetch(bundle: BundleReference, raw: JsonObject, etag: String?): ApplyResult = db.withTransaction {
        val version = bundle.meta.bundleVersion
        if (compare(version, dao.meta(KEY_BUNDLE_VERSION)) <= 0) return@withTransaction ApplyResult.OLDER_IGNORED
        if (compare(version, dao.meta(KEY_PREFETCH_VERSION)) < 0) return@withTransaction ApplyResult.OLDER_IGNORED
        putChunked(KEY_PREFETCH_JSON, raw.toString())
        dao.putMeta(SyncMetaEntity(KEY_PREFETCH_VERSION, version))
        dao.putMeta(SyncMetaEntity(KEY_PREFETCH_DATE, bundle.meta.validForBusinessDate))
        putOrDelete(KEY_PREFETCH_ETAG, etag)
        ApplyResult.PREFETCH_STORED
    }

    /**
     * Applies a config delta (docs/24 s4.10 Config delta) in one transaction: changed values and scheduled values replace
     * those of their keys, removed keys go, outlet radius changes update the outlets, calendar changes are kept raw
     * (`calendar_changes`), a policy change is flagged (`device_policy.refresh_needed`), and the phone's config version
     * moves to `to_version`. A delta not newer than the stored version is ignored (STALE); one that starts after it is refused
     * and flags a bundle refresh (GAP).
     */
    /**
     * [ack]: when given and the delta changes keys marked `requires_ack`, a `config_ack` record is queued in the same
     * transaction (F-SYS-053), so the ack exists exactly when the values do and rides the next sync.
     */
    suspend fun applyConfigDelta(delta: ConfigDeltaWire, ack: ConfigAckStamp? = null): DeltaResult = db.withTransaction {
        val current = dao.meta(KEY_CONFIG_VERSION)?.toLongOrNull()
        if (current != null && delta.toVersion <= current) return@withTransaction DeltaResult.STALE
        // A delta must start at (or before) what the phone holds; a gap means changes in between are missing.
        if (current == null || delta.fromVersion > current) {
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_REFRESH, "true"))
            return@withTransaction DeltaResult.GAP
        }
        for ((key, rows) in delta.values.groupBy { it.key }) {
            dao.deleteConfig(key, scheduled = false)
            dao.insertConfig(rows.map { configRow(it, scheduled = false) })
        }
        for ((key, rows) in delta.scheduled.groupBy { it.key }) {
            dao.deleteConfig(key, scheduled = true)
            dao.insertConfig(rows.map { configRow(it, scheduled = true) })
        }
        delta.removedKeys.forEach { dao.deleteConfigKey(it) }
        delta.outletRadiusChanges.forEach { dao.updateOutletRadius(it.outletId, it.radiusM, it.maxAccuracyM) }
        if (delta.calendarChanges.isNotEmpty()) {
            val previous = section("calendar_changes")?.let { Json.parseToJsonElement(it) as? JsonArray }.orEmpty()
            dao.insertSections(listOf(BundleSectionEntity("calendar_changes", JsonArray(previous + delta.calendarChanges).toString())))
        }
        if (delta.policyChanged) dao.putMeta(SyncMetaEntity(KEY_POLICY_REFRESH, "true"))
        dao.putMeta(SyncMetaEntity(KEY_CONFIG_VERSION, delta.toVersion.toString()))
        val ackKeys = delta.values.filter { it.requiresAck }.map { it.key }
        if (ack != null && ackKeys.isNotEmpty()) {
            db.outboxDao().insert(listOf(RecordMapping.configAck(ack.clientUuid, ack.meta, delta.toVersion, ack.appliedAt, ackKeys, ack.appliedAt)))
        }
        DeltaResult.APPLIED
    }

    /**
     * Applies a `BundleDelta` (F-SYS-007, docs/24 s4.10 Delta) in ONE transaction: the old reference data or the new, never a
     * mix. Only the rows named change: outlets, prices, SKUs and tasks by key; open memos, day states and route extras in
     * the route sections; code lists, templates, offers and the supervisor's team and pending requests in their raw
     * sections; whole routes added or removed. Captured records (memos with the prices they were sold at, the outbox) are
     * never touched, so a price change applies to new memos only. Nothing here counts as the day's login.
     * A delta for another date, from another base cursor or not newer than the stored bundle is not applied (see
     * [BundleDeltaResult]); [BundleDeltaResult.GAP] also flags a full bundle refresh.
     */
    suspend fun applyBundleDelta(raw: JsonObject): BundleDeltaResult {
        val meta = raw["meta"] as? JsonObject ?: return BundleDeltaResult.GAP
        fun m(k: String) = (meta[k] as? JsonPrimitive)?.contentOrNull
        val version = m("bundle_version") ?: return BundleDeltaResult.GAP
        val date = m("valid_for_business_date") ?: return BundleDeltaResult.GAP
        val base = m("base_cursor")
        val cursor = m("cursor") ?: return BundleDeltaResult.GAP
        val sections = raw["sections"] as? JsonObject ?: JsonObject(emptyMap())
        fun upserts(name: String) = ((sections[name] as? JsonObject)?.get("upsert") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun deletes(name: String) = ((sections[name] as? JsonObject)?.get("delete") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        // Parsed before the transaction: a malformed delta fails here and changes nothing.
        val outletUps = upserts("outlets").map { DELTA_JSON.decodeFromJsonElement(BundleOutlet.serializer(), it) }
        val priceUps = upserts("prices").map { DELTA_JSON.decodeFromJsonElement(SkuPrice.serializer(), it) }
        val skuUps = upserts("skus").map { DELTA_JSON.decodeFromJsonElement(Sku.serializer(), it) }
        val taskUps = upserts("tasks").map { requireNotNull(taskRow(it)) { "a task without task_uuid" } }
        val added = (raw["routes_added"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .map { it to DELTA_JSON.decodeFromJsonElement(RouteSnapshot.serializer(), it) }
        val removed = (raw["routes_removed"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
        val dayStates = (raw["day_states"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val resolutions = (raw["resolutions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val uuid = (o["client_uuid"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val res = (o["resolution"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            uuid to res
        }
        val moveMemos = outletUps.isNotEmpty() || deletes("outlets").isNotEmpty() || added.isNotEmpty() || removed.isNotEmpty() ||
            upserts("open_memos").isNotEmpty() || deletes("open_memos").isNotEmpty()
        return db.withTransaction {
            if (dao.meta(KEY_BUNDLE_DATE) != date) return@withTransaction BundleDeltaResult.NEW_DATE
            val held = dao.meta(KEY_BUNDLE_CURSOR)
            // Already applied (same cursor) or older than the held bundle. A delta of the same snapshot that only moves day
            // states (a submit void) has a new cursor and is applied.
            if (held == cursor || compare(version, dao.meta(KEY_BUNDLE_VERSION)) < 0) return@withTransaction BundleDeltaResult.STALE
            if (base == null || held != base) {
                dao.putMeta(SyncMetaEntity(KEY_BUNDLE_REFRESH, "true"))
                return@withTransaction BundleDeltaResult.GAP
            }
            // Open memos of every route, read before any route goes, so a moved or removed route never loses a due.
            val heldMemos = if (moveMemos) openMemosByRoute() else emptyMap()
            // Whole routes first, so row upserts of the same delta land on top of them.
            for (id in removed) {
                dao.deleteOutletsOfRoute(id)
                dao.deleteRoute(id)
                dao.deleteSection("route.$id")
            }
            for ((json, snap) in added) {
                dao.deleteOutletsOfRoute(snap.routeId)
                dao.insertRoutes(listOf(routeRow(snap, date, version)))
                dao.insertOutlets(snap.outlets.map(::outletRow))
                putSection("route.${snap.routeId}", routeExtras(json).toString())
            }
            deletes("outlets").mapNotNull { it.toLongOrNull() }.takeIf { it.isNotEmpty() }?.let { dao.deleteOutlets(it) }
            if (outletUps.isNotEmpty()) dao.insertOutlets(outletUps.map(::outletRow))
            deletes("prices").mapNotNull { it.toLongOrNull() }.takeIf { it.isNotEmpty() }?.let { dao.deletePrices(it) }
            if (priceUps.isNotEmpty()) dao.insertPrices(priceUps.map(::priceRow))
            deletes("skus").mapNotNull { it.toLongOrNull() }.takeIf { it.isNotEmpty() }?.let { dao.deleteSkus(it) }
            if (skuUps.isNotEmpty()) dao.insertSkus(skuUps.map(::skuRow))
            deletes("tasks").takeIf { it.isNotEmpty() }?.let { dao.deleteTasks(it) }
            if (taskUps.isNotEmpty()) dao.insertTasks(taskUps)
            dao.reapplyLocalResolutions() // a resolution not yet acked survives a server copy of the task
            if (moveMemos) placeOpenMemos(heldMemos, added.map { it.second }, upserts("open_memos"), deletes("open_memos"))
            for (st in dayStates) {
                val routeId = (st["route_id"] as? JsonPrimitive)?.contentOrNull ?: continue
                editSection("route.$routeId") { JsonObject(it + ("day_state" to st)) }
            }
            editListSection("code_lists", raw["code_lists"] as? JsonArray, "list_key")
            editListSection("templates", raw["templates"] as? JsonArray, "kind")
            // Offers, targets and achievements are deferred programmes (docs/27): the hooks keep the raw rows current.
            editKeyed("offers", null, "id", upserts("offers"), deletes("offers"))
            editKeyed("supervisor", "team", "user_id", upserts("team"), deletes("team"))
            editKeyed("supervisor", "pending_outlet_requests", "request_uuid", upserts("pending_outlet_requests"), deletes("pending_outlet_requests"))
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_VERSION, version))
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_CURSOR, cursor))
            m("server_time")?.let { dao.putMeta(SyncMetaEntity(KEY_BUNDLE_SERVER_TIME, it)) }
            // Resolutions are stashed in the same transaction as the cursor move: the server sends each one once.
            for (res in resolutions) dao.putMeta(SyncMetaEntity(KEY_RESOLUTION_PREFIX + res.first, res.second))
            BundleDeltaResult.APPLIED
        }
    }

    /** Every route section's open memos, by route id (read before a delta changes routes). */
    private suspend fun openMemosByRoute(): Map<Long, List<JsonObject>> =
        dao.routeSectionNames().mapNotNull { it.removePrefix("route.").toLongOrNull() }.associateWith { id ->
            section("route.$id")?.let { runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.get("open_memos") as? JsonArray }.getOrNull() }
                .orEmpty().mapNotNull { it as? JsonObject }
        }

    /**
     * Open memos live in their route's section (`route.<id>.open_memos`), placed by their outlet's route as the outlet table
     * says after the delta: a memo follows an outlet that moved, delta upserts replace by memo_client_uuid, deletes remove,
     * and an added route's own snapshot memos are kept. A memo whose outlet is in none of the user's routes stays where it
     * was while that route is held (the due must stay collectable offline).
     */
    private suspend fun placeOpenMemos(held: Map<Long, List<JsonObject>>, addedRoutes: List<RouteSnapshot>, upserts: List<JsonObject>, deletes: List<String>) {
        fun uuid(o: JsonObject) = (o["memo_client_uuid"] as? JsonPrimitive)?.contentOrNull
        fun outlet(o: JsonObject) = (o["outlet_id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val gone = deletes.toSet()
        val pool = LinkedHashMap<String, Pair<JsonObject, Long?>>() // uuid -> memo, route it was in
        for ((route, memos) in held) for (m in memos) uuid(m)?.let { pool[it] = m to route }
        for (u in upserts) uuid(u)?.let { pool[it] = u to pool[it]?.second }
        gone.forEach { pool.remove(it) }
        val routeIds = dao.routeSectionNames().mapNotNull { it.removePrefix("route.").toLongOrNull() }
        val routeOfOutlet = HashMap<Long, Long>()
        for (id in routeIds) dao.outletsOf(id).forEach { routeOfOutlet[it.outletId] = id }
        val placed = routeIds.associateWith { ArrayList<JsonObject>() }
        val addedIds = addedRoutes.map { it.routeId }.toSet()
        for ((_, entry) in pool) {
            val (memo, was) = entry
            val target = outlet(memo)?.let(routeOfOutlet::get) ?: was?.takeIf { it in placed && it !in addedIds }
            target?.let { placed.getValue(it) += memo }
        }
        for (id in routeIds) {
            // An added route keeps the open memos its snapshot carried, plus any that now belong to it.
            val own = if (id in addedIds) {
                val fromSnapshot = section("route.$id")?.let { (Json.parseToJsonElement(it) as? JsonObject)?.get("open_memos") as? JsonArray }
                    .orEmpty().mapNotNull { it as? JsonObject }.filter { uuid(it) !in gone }
                val have = fromSnapshot.mapNotNull(::uuid).toSet()
                fromSnapshot + placed.getValue(id).filter { uuid(it) !in have }
            } else {
                placed.getValue(id)
            }
            editSection("route.$id") { r -> JsonObject(r + ("open_memos" to JsonArray(own))) }
        }
    }

    private suspend fun putSection(name: String, json: String) {
        dao.deleteSection(name)
        dao.insertSections(chunks(json).mapIndexed { i, part -> BundleSectionEntity(chunkName(name, i), part) })
    }

    /** Rewrites a raw JSON-object section; a missing or unreadable one is left alone. */
    private suspend fun editSection(name: String, edit: (JsonObject) -> JsonObject) {
        val current = section(name)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return
        putSection(name, edit(current).toString())
    }

    /** Replaces whole entries of a list section by [key] (code lists by `list_key`, templates by `kind`). */
    private suspend fun editListSection(name: String, changed: JsonArray?, key: String) {
        if (changed.isNullOrEmpty()) return
        val current = section(name)?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()
        fun k(e: JsonElement) = ((e as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
        val replaced = changed.mapNotNull(::k).toSet()
        putSection(name, JsonArray(current.filter { k(it) !in replaced } + changed).toString())
    }

    /** Upserts and deletes rows keyed by [key] in a raw list: the section itself, or member [member] of an object section. */
    private suspend fun editKeyed(name: String, member: String?, key: String, upserts: List<JsonObject>, deletes: List<String>) {
        if (upserts.isEmpty() && deletes.isEmpty()) return
        val text = section(name) ?: return
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return
        fun k(e: JsonElement) = ((e as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
        val gone = deletes.toSet() + upserts.mapNotNull(::k)
        fun merge(list: JsonArray?) = JsonArray(list.orEmpty().filter { k(it) !in gone } + upserts)
        val updated = if (member == null) merge(parsed as? JsonArray) else {
            val o = parsed as? JsonObject ?: return
            JsonObject(o + (member to merge(o[member] as? JsonArray)))
        }
        putSection(name, updated.toString())
    }

    /** The stored prefetch's date and ETag (for a conditional prefetch request), or null. */
    suspend fun prefetch(): Pair<String, String?>? = dao.meta(KEY_PREFETCH_DATE)?.let { it to dao.meta(KEY_PREFETCH_ETAG) }

    /**
     * Applies the stored prefetch when its date is [businessDate] (the day starts, typically offline on a cached bundle,
     * docs/24 s4.10 Stale bundle). A prefetch of an earlier date is discarded. Returns null when there is none for the day.
     */
    suspend fun promotePrefetch(businessDate: String): ApplyResult? {
        val date = dao.meta(KEY_PREFETCH_DATE) ?: return null
        if (date < businessDate) { clearPrefetch(); return null }
        if (date != businessDate) return null
        val text = getChunked(KEY_PREFETCH_JSON) ?: run { clearPrefetch(); return null }
        val raw = Json.parseToJsonElement(text) as JsonObject
        val result = apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw, dao.meta(KEY_PREFETCH_ETAG), asDay = true)
        clearPrefetch()
        return result
    }

    private suspend fun clearPrefetch() = db.withTransaction {
        deleteChunked(KEY_PREFETCH_JSON)
        listOf(KEY_PREFETCH_VERSION, KEY_PREFETCH_DATE, KEY_PREFETCH_ETAG).forEach { dao.deleteMeta(it) }
    }

    // A row larger than Android's 2 MB CursorWindow cannot be read back, so long text is stored in chunks: `<key>`,
    // `<key>#1`, `<key>#2`, ... (a whole day bundle as JSON is easily 10 MB; F-SYS-006 checker).
    private suspend fun putChunked(key: String, text: String) {
        deleteChunked(key)
        chunks(text).forEachIndexed { i, part -> dao.putMeta(SyncMetaEntity(chunkName(key, i), part)) }
    }

    private suspend fun getChunked(key: String): String? {
        val first = dao.meta(key) ?: return null
        val sb = StringBuilder(first)
        var i = 1
        while (true) sb.append(dao.meta(chunkName(key, i++)) ?: break)
        return sb.toString()
    }

    private suspend fun deleteChunked(key: String) {
        dao.metaWithPrefix("$key#").forEach { dao.deleteMeta(it.key) }
        dao.deleteMeta(key)
    }

    private suspend fun putOrDelete(key: String, value: String?) =
        if (value != null) dao.putMeta(SyncMetaEntity(key, value)) else dao.deleteMeta(key)

    /** The routes of [businessDate] with their outlets in visit order; planned routes first. */
    suspend fun routesOfDay(businessDate: String): List<RouteDay> = db.withTransaction {
        dao.routesFor(businessDate).map { RouteDay(it, dao.outletsOf(it.routeId)) }
    }

    suspend fun skus(): List<SkuEntity> = dao.activeSkus()

    suspend fun bundleVersion(): String? = dao.meta(KEY_BUNDLE_VERSION)

    /** The config version the phone holds (bundle or delta), 0 before the first bundle: the envelope's `config_version`. */
    suspend fun configVersionHeld(): Long = dao.meta(KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L

    /** The business date the stored bundle is valid for. */
    suspend fun businessDate(): String? = dao.meta(KEY_BUNDLE_DATE)

    suspend fun etag(): String? = dao.meta(KEY_BUNDLE_ETAG)

    /** The price of [skuId] at [priceType] on [businessDate] (a price change applies to the dates it covers only). */
    suspend fun priceOn(skuId: Long, priceType: String, businessDate: String): PriceEntity? = dao.priceOn(skuId, priceType, businessDate)

    /**
     * The value of config [key] (JSON text) in force at [nowIso] (trusted time, RFC 3339 UTC with milliseconds): the latest
     * scheduled value whose window contains [nowIso], else the resolved value; null when the bundle does not carry the key.
     */
    suspend fun config(key: String, nowIso: String): String? {
        val rows = dao.configRows(key)
        val scheduled = rows.filter { it.scheduled && it.effectiveFrom != null && it.effectiveFrom <= nowIso && (it.effectiveTo == null || nowIso < it.effectiveTo) }
            .maxByOrNull { it.effectiveFrom!! }
        return (scheduled ?: rows.firstOrNull { !it.scheduled })?.valueJson
    }

    suspend fun tasks(): List<TaskEntity> = dao.tasks()

    /** A raw bundle section (see [apply]), or `route.<id>` for a route's extras. */
    suspend fun section(name: String): String? {
        val first = dao.section(name) ?: return null
        val sb = StringBuilder(first)
        var i = 1
        while (true) sb.append(dao.section(chunkName(name, i++)) ?: break)
        return sb.toString()
    }

    companion object {
        const val KEY_BUNDLE_VERSION = "bundle_version"
        const val KEY_BUNDLE_DATE = "bundle_business_date"
        const val KEY_BUNDLE_ETAG = "bundle.etag"
        const val KEY_BUNDLE_CURSOR = "bundle.cursor"
        const val KEY_BUNDLE_SERVER_TIME = "bundle.server_time"

        /** Set when a config delta says the device policy changed; the policy lane fetches it. */
        const val KEY_POLICY_REFRESH = "device_policy.refresh_needed"

        /** Set when the server answers 410 to a config delta: only a full bundle brings the phone up to date. */
        const val KEY_BUNDLE_REFRESH = "bundle.refresh_needed"

        /** sync_meta prefix of server resolutions waiting for their quarantined row (written by deltas and batch answers). */
        const val KEY_RESOLUTION_PREFIX = "sync.resolution."
        private const val KEY_PREFETCH_JSON = "prefetch.json"
        private const val KEY_PREFETCH_VERSION = "prefetch.version"
        private const val KEY_PREFETCH_DATE = "prefetch.date"
        private const val KEY_PREFETCH_ETAG = "prefetch.etag"

        /** The config version the phone holds (`X-Config-Version`); the sync engine also moves it from batch responses. */
        const val KEY_CONFIG_VERSION = "sync.config_version"

        /** Characters per stored chunk: at most 1.2 MB of UTF-8 even for Bangla text (3 bytes a character). */
        private const val CHUNK_CHARS = 400_000

        internal fun chunkName(key: String, i: Int) = if (i == 0) key else "$key#$i"

        /** Splits [text] into chunks, never between the two halves of a surrogate pair (SQLite stores UTF-8). */
        internal fun chunks(text: String, size: Int = CHUNK_CHARS): List<String> {
            require(size >= 2) { "a chunk must hold a surrogate pair" }
            if (text.length <= size) return listOf(text)
            val out = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(text.length, start + size)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                out += text.substring(start, end)
                start = end
            }
            return out
        }

        private val DELTA_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** Sections with tables of their own; everything else at the top level is kept raw. */
        private val TYPED = setOf("meta", "routes", "prices", "config", "tasks")

        /** RouteSnapshot members stored in tables; the rest of each snapshot is kept raw as `route.<id>`. */
        private val ROUTE_TYPED = setOf("route", "outlets")

        /**
         * Orders bundle versions `<date>:<snapshot_seq>`: 1 when [candidate] is newer than [current] (or nothing is stored,
         * or the stored value cannot be read), 0 when equal, -1 when older.
         */
        fun compare(candidate: String, current: String?): Int {
            val c = parse(candidate) ?: return 1
            val s = current?.let(::parse) ?: return 1
            return compareValuesBy(c, s, { it.first }, { it.second }).coerceIn(-1, 1)
        }

        private fun parse(v: String): Pair<String, Long>? {
            val i = v.lastIndexOf(':')
            if (i <= 0) return null
            val seq = v.substring(i + 1).toLongOrNull() ?: return null
            return v.substring(0, i) to seq
        }

        internal fun routeRow(s: RouteSnapshot, date: String, version: String) = RouteEntity(
            routeId = s.routeId, code = s.route.code, name = s.route.name, displayLabel = s.route.displayLabel,
            zoneId = s.route.zoneId, kind = s.route.kind, visitKind = s.route.visitKind, visitDaysMask = s.route.visitDaysMask,
            sequenceNo = s.route.sequenceNo, status = s.route.status, assignmentKind = s.assignmentKind,
            actingForUserId = s.actingForUserId, plannedToday = s.plannedToday, targetOutlets = s.targetOutlets,
            routeSnapshotVersion = s.routeSnapshotVersion, businessDate = date, bundleVersion = version,
        )

        internal fun outletRow(o: BundleOutlet) = OutletEntity(
            outletId = o.outletId, routeId = o.routeId, code = o.code, name = o.name, nameBn = o.nameBn, nameSortKey = o.nameSortKey,
            ownerName = o.ownerName, contactNumber = o.contactNumber, lat = o.lat, lng = o.lng, locationConfirmed = o.locationConfirmed,
            provisionalLat = o.provisionalLat, provisionalLng = o.provisionalLng, clusterId = o.clusterId, clusterName = o.clusterName,
            channel = o.channel, subChannelId = o.subChannelId, geoClass = o.geoClass, status = o.status, priceType = o.priceType,
            outletKind = o.outletKind, radiusM = o.radiusM, maxAccuracyM = o.maxAccuracyM, visitSequence = o.visitSequence,
            openDueMtk = o.openDueMtk, openDueAsOf = o.openDueAsOf,
            programmeFlagsJson = Json.encodeToString(ListSerializer(String.serializer()), o.programmeFlags),
            pendingRequest = o.pendingRequest,
        )

        internal fun skuRow(k: Sku) = SkuEntity(
            skuId = k.id, code = k.code, variantId = k.variantId, categoryCode = k.categoryCode, name = k.name, shortName = k.shortName,
            nameBn = k.nameBn, baseUnit = k.baseUnit, basePerPack = k.basePerPack, entryUnitDefault = k.entryUnitDefault,
            reportUnit = k.reportUnit, reportFactor = k.reportFactor, sort = k.sort, status = k.status, version = k.version,
        )

        internal fun priceRow(p: SkuPrice) = PriceEntity(p.id, p.skuId, p.priceType, p.amountMtk, p.perBaseQty, p.validFrom, p.validTo)

        /** The raw part of a route snapshot (everything but the route and its outlets), stored as `route.<id>`. */
        internal fun routeExtras(o: JsonObject): JsonObject = JsonObject(o.filterKeys { it !in ROUTE_TYPED })

        internal fun taskRow(o: JsonObject): TaskEntity? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val uuid = str("task_uuid") ?: return null
            return TaskEntity(
                taskUuid = uuid, taskTypeCode = str("task_type_code") ?: "", title = str("title") ?: "", description = str("description"),
                outletId = (o["outlet_id"] as? JsonPrimitive)?.content?.toLongOrNull(), dueDate = str("due_date"),
                status = str("status") ?: "ongoing", resolvedAt = str("resolved_at"), json = o.toString(),
            )
        }

        private fun configRow(v: ResolvedConfigValue, scheduled: Boolean) = ConfigValueEntity(
            key = v.key, valueJson = v.value.toString(), scopeType = v.scopeType, scopeId = v.scopeId, effectiveFrom = v.effectiveFrom,
            effectiveTo = v.effectiveTo, configVersion = v.configVersion, requiresAck = v.requiresAck, scheduled = scheduled,
        )

        private fun rawSections(raw: JsonObject): List<BundleSectionEntity> {
            val top = raw.filterKeys { it !in TYPED }.map { (k, v) ->
                if (k == "products") {
                    BundleSectionEntity("products", JsonObject((v as? JsonObject)?.filterKeys { it != "skus" }.orEmpty()).toString())
                } else {
                    BundleSectionEntity(k, v.toString())
                }
            }
            val perRoute = (raw["routes"] as? JsonArray).orEmpty().mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                val id = (o["route_id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                BundleSectionEntity("route.$id", routeExtras(o).toString())
            }
            return top + perRoute
        }
    }
}

/** What a `config_ack` needs from the caller (trusted time and a fresh client uuid); see [ReferenceRepository.applyConfigDelta]. */
data class ConfigAckStamp(val clientUuid: String, val meta: com.aktcl.aron.core.database.entity.CaptureMeta, val appliedAt: String)
