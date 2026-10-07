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
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.reference.ResolvedValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The route of the day as the SR app reads it offline. */
data class RouteDay(val route: RouteEntity, val outlets: List<OutletEntity>)

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
        val routes = bundle.routes.map { s ->
            RouteEntity(
                routeId = s.routeId, code = s.route.code, name = s.route.name, displayLabel = s.route.displayLabel,
                zoneId = s.route.zoneId, kind = s.route.kind, visitKind = s.route.visitKind, visitDaysMask = s.route.visitDaysMask,
                sequenceNo = s.route.sequenceNo, status = s.route.status, assignmentKind = s.assignmentKind,
                actingForUserId = s.actingForUserId, plannedToday = s.plannedToday, targetOutlets = s.targetOutlets,
                routeSnapshotVersion = s.routeSnapshotVersion, businessDate = date, bundleVersion = version,
            )
        }
        val outlets = bundle.routes.flatMap { it.outlets }.map { o ->
            OutletEntity(
                outletId = o.outletId, routeId = o.routeId, code = o.code, name = o.name, nameBn = o.nameBn, nameSortKey = o.nameSortKey,
                ownerName = o.ownerName, contactNumber = o.contactNumber, lat = o.lat, lng = o.lng, locationConfirmed = o.locationConfirmed,
                provisionalLat = o.provisionalLat, provisionalLng = o.provisionalLng, clusterId = o.clusterId, clusterName = o.clusterName,
                channel = o.channel, subChannelId = o.subChannelId, geoClass = o.geoClass, status = o.status, priceType = o.priceType,
                outletKind = o.outletKind, radiusM = o.radiusM, maxAccuracyM = o.maxAccuracyM, visitSequence = o.visitSequence,
                openDueMtk = o.openDueMtk, openDueAsOf = o.openDueAsOf,
                programmeFlagsJson = Json.encodeToString(ListSerializer(String.serializer()), o.programmeFlags),
                pendingRequest = o.pendingRequest,
            )
        }
        val skus = bundle.products.skus.map { k ->
            SkuEntity(
                skuId = k.id, code = k.code, variantId = k.variantId, categoryCode = k.categoryCode, name = k.name, shortName = k.shortName,
                nameBn = k.nameBn, baseUnit = k.baseUnit, basePerPack = k.basePerPack, entryUnitDefault = k.entryUnitDefault,
                reportUnit = k.reportUnit, reportFactor = k.reportFactor, sort = k.sort, status = k.status, version = k.version,
            )
        }
        val prices = bundle.prices.map { PriceEntity(it.id, it.skuId, it.priceType, it.amountMtk, it.perBaseQty, it.validFrom, it.validTo) }
        val config = bundle.config?.let { c ->
            c.values.map { configRow(it, scheduled = false) } + c.scheduled.map { configRow(it, scheduled = true) }
        }.orEmpty()
        val sections = raw?.let(::rawSections).orEmpty().flatMap { sec ->
            chunks(sec.json).mapIndexed { i, part -> BundleSectionEntity(chunkName(sec.name, i), part) }
        }
        return db.withTransaction {
            if (compare(version, dao.meta(KEY_BUNDLE_VERSION)) < 0) return@withTransaction ApplyResult.OLDER_IGNORED
            dao.clearOutlets()
            dao.clearRoutes()
            dao.clearSkus()
            dao.clearPrices()
            dao.clearConfig()
            dao.clearSections()
            dao.insertRoutes(routes)
            dao.insertOutlets(outlets)
            dao.insertSkus(skus)
            dao.insertPrices(prices)
            dao.insertConfig(config)
            dao.insertSections(sections)
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_VERSION, version))
            dao.putMeta(SyncMetaEntity(KEY_BUNDLE_DATE, date))
            dao.meta(KEY_PREFETCH_DATE)?.let { if (it <= date) clearPrefetch() } // a prefetch of this day or earlier is spent
            putOrDelete(KEY_BUNDLE_ETAG, etag)
            putOrDelete(KEY_BUNDLE_CURSOR, bundle.meta.cursor)
            putOrDelete(KEY_BUNDLE_SERVER_TIME, bundle.meta.serverTime)
            (bundle.config?.configVersion ?: bundle.meta.configVersion)?.let { dao.putMeta(SyncMetaEntity(KEY_CONFIG_VERSION, it.toString())) }
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
        private const val KEY_PREFETCH_JSON = "prefetch.json"
        private const val KEY_PREFETCH_VERSION = "prefetch.version"
        private const val KEY_PREFETCH_DATE = "prefetch.date"
        private const val KEY_PREFETCH_ETAG = "prefetch.etag"

        /** The config version the phone holds (`X-Config-Version`); the sync engine also moves it from batch responses. */
        const val KEY_CONFIG_VERSION = "sync.config_version"

        /** Characters per stored chunk: at most 1.2 MB of UTF-8 even for Bangla text (3 bytes a character). */
        private const val CHUNK_CHARS = 400_000

        private fun chunkName(key: String, i: Int) = if (i == 0) key else "$key#$i"

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

        /** Sections with tables of their own; everything else at the top level is kept raw. */
        private val TYPED = setOf("meta", "routes", "prices", "config")

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

        private fun configRow(v: ResolvedValue, scheduled: Boolean) = ConfigValueEntity(
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
                BundleSectionEntity("route.$id", JsonObject(o.filterKeys { it !in ROUTE_TYPED }).toString())
            }
            return top + perRoute
        }
    }
}
