package com.aktcl.aron.core.sync

import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ApplyResult
import com.aktcl.aron.core.database.repo.BundleDeltaResult
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.SyncApi
import com.aktcl.aron.core.network.TransportFailure
import com.aktcl.aron.rules.BusinessDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

enum class BundleOutcome {
    /** A new snapshot is in Room. */
    APPLIED,

    /** 304: the stored snapshot is current (the server still counts the request as the day's login, s4.9). */
    UNCHANGED,

    /** The server sent an older snapshot than the stored one; nothing changed. */
    OLDER_IGNORED,

    /** A later day's bundle (prefetch) was stored aside; today's data is untouched. */
    PREFETCH_STORED,

    /** Offline at day start: the stored prefetch of today was applied (docs/24 s4.10 Stale bundle). */
    PREFETCH_PROMOTED,

    /** No connection or an edge page; staged pages are kept and the next call resumes. */
    OFFLINE,

    /** 503 `ERR_BUNDLE_NOT_READY`. */
    NOT_READY,

    /** 401: the user must sign in online. */
    AUTH_REQUIRED,

    /** Any other answer, or a body that cannot be read; the stored snapshot stays. */
    FAILED,

    /** A delta for a new business date: the day's bundle is fetched by the day start (it is that day's login), not here. */
    NEW_DATE,
}

data class BundleReport(val outcome: BundleOutcome, val bundleVersion: String? = null, val businessDate: String? = null, val code: String? = null)

/**
 * Downloads the day bundle into the user's Room database (docs/24 s4.10; F-SYS-006). The request itself is the day's
 * login event on the server (s4.9 `logged_in`: the first bundle request of date D, 200 or 304; a prefetch never counts).
 *
 * Crash safety: nothing is written to the reference tables until the whole snapshot is in hand, and then it is applied in
 * one transaction (ReferenceRepository.apply), so a kill at any point leaves the previous snapshot intact and a re-run
 * replaces it, never duplicates it. Pages of a paged section are staged as files under [stagingDir] per bundle version, so
 * a download killed half-way resumes with the pages it already has.
 */
class BundleDownloader(
    private val db: AronDatabase,
    private val api: SyncApi,
    private val stagingDir: File,
    private val clock: WallClock,
) {
    private val repo = ReferenceRepository(db)
    private val meta = db.referenceDao()
    private val lock = Mutex()

    /** Fetches and applies the bundle of [forDate] (default: today's Dhaka business date). Offline it changes nothing. */
    suspend fun download(forDate: String = BusinessDate.of(clock.nowMs()).toString()): BundleReport = lock.withLock { downloadLocked(forDate) }

    private suspend fun downloadLocked(forDate: String): BundleReport {
        val today = BusinessDate.of(clock.nowMs()).toString()
        val isDay = forDate <= today // a later date is a prefetch: stored aside, never the day's login
        val prefetchEtag = repo.prefetch()?.takeIf { it.first == forDate }?.second
        // The morning request is conditional on last evening's prefetch of the same day: a 304 promotes it (no second download).
        val onPrefetch = isDay && repo.businessDate() != forDate && prefetchEtag != null
        val ifNoneMatch = when {
            !isDay -> prefetchEtag
            onPrefetch -> prefetchEtag
            else -> repo.etag().takeIf { repo.businessDate() == forDate }
        }
        val configVersion = meta.meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull()
        val report = fetch(forDate, isDay, onPrefetch, ifNoneMatch, configVersion)
        if (isDay && report.outcome !in setOf(BundleOutcome.APPLIED, BundleOutcome.UNCHANGED, BundleOutcome.OLDER_IGNORED) && repo.businessDate() != forDate) {
            // No fresh bundle for a new day: start it on the stored prefetch, if one was fetched for it.
            if (repo.promotePrefetch(forDate) == ApplyResult.APPLIED) {
                return BundleReport(BundleOutcome.PREFETCH_PROMOTED, repo.bundleVersion(), repo.businessDate(), report.code)
            }
        }
        return report
    }

    /**
     * Brings the held bundle of today up to date with `GET /v1/sync/delta` (F-SYS-007): only changed rows, applied in one
     * transaction, never the day's login. A 410 or a cursor gap fetches the full bundle of the SAME date (that day is
     * already logged in); a delta of a new date ([BundleOutcome.NEW_DATE]) is left to the day start. Resolutions in the
     * delta are stashed for the sync engine, which applies them by client_uuid.
     */
    suspend fun refreshDelta(): BundleReport = lock.withLock {
        val held = repo.businessDate() ?: return@withLock BundleReport(BundleOutcome.FAILED, code = "no_bundle")
        val today = BusinessDate.of(clock.nowMs()).toString()
        if (held != today) return@withLock BundleReport(BundleOutcome.NEW_DATE, repo.bundleVersion(), held)
        val cursor = meta.meta(ReferenceRepository.KEY_BUNDLE_CURSOR) ?: return@withLock downloadLocked(held)
        val configVersion = meta.meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull()
        when (val r = api.bundleDelta(cursor, held, configVersion)) {
            is ApiResult.NotModified -> BundleReport(BundleOutcome.UNCHANGED, repo.bundleVersion(), held)
            is ApiResult.Transport -> BundleReport(
                if (r.failure == TransportFailure.MALFORMED) BundleOutcome.FAILED else BundleOutcome.OFFLINE, code = r.failure.name.lowercase(),
            )
            is ApiResult.Failure -> when (r.httpStatus) {
                409 -> BundleReport(BundleOutcome.NEW_DATE, repo.bundleVersion(), held, r.problem.code)
                410 -> downloadLocked(held)
                else -> BundleReport(failureOutcome(r.httpStatus), code = r.problem.code ?: "http_${r.httpStatus}")
            }
            is ApiResult.Success -> {
                val raw = try {
                    Json.parseToJsonElement(r.value).jsonObject
                } catch (e: IllegalArgumentException) {
                    return@withLock BundleReport(BundleOutcome.FAILED, code = "malformed")
                }
                val result = try {
                    repo.applyBundleDelta(raw)
                } catch (e: IllegalArgumentException) { // a row that is not the contract's: nothing was written
                    return@withLock BundleReport(BundleOutcome.FAILED, code = "malformed")
                }
                when (result) {
                    BundleDeltaResult.APPLIED -> {
                        stashResolutions(raw)
                        BundleReport(BundleOutcome.APPLIED, repo.bundleVersion(), held)
                    }
                    BundleDeltaResult.STALE -> BundleReport(BundleOutcome.UNCHANGED, repo.bundleVersion(), held)
                    BundleDeltaResult.GAP -> downloadLocked(held)
                    BundleDeltaResult.NEW_DATE -> BundleReport(BundleOutcome.NEW_DATE, repo.bundleVersion(), held)
                }
            }
        }
    }

    /** After a sync run: a delta only when the server said its current bundle is newer than the held one (s4.10). */
    suspend fun refreshIfServerNewer(): BundleReport? {
        val current = meta.meta(SyncEngine.KEY_BUNDLE_CURRENT) ?: return null
        val held = repo.bundleVersion() ?: return null
        if (ReferenceRepository.compare(current, held) <= 0) return null
        return refreshDelta()
    }

    /**
     * App in front: a delta at most every [minIntervalMs] (`cfg.bundle.delta_min_interval_min`, 30 min) on trusted time.
     * The gate is spent only by an answered request, so an offline resume tries again at the next one.
     */
    suspend fun refreshOnForeground(minIntervalMs: Long = 30 * 60_000L): BundleReport? {
        val now = clock.nowMs()
        val last = meta.meta(KEY_DELTA_FOREGROUND_AT)?.toLongOrNull()
        if (last != null && now >= last && now - last < minIntervalMs) return null
        return refreshDelta().also {
            if (it.outcome != BundleOutcome.OFFLINE) meta.putMeta(SyncMetaEntity(KEY_DELTA_FOREGROUND_AT, now.toString()))
        }
    }

    private suspend fun stashResolutions(raw: JsonObject) {
        for (e in (raw["resolutions"] as? JsonArray).orEmpty()) {
            val o = e as? JsonObject ?: continue
            val uuid = (o["client_uuid"] as? JsonPrimitive)?.content ?: continue
            val res = (o["resolution"] as? JsonPrimitive)?.content ?: continue
            if (AckRules.resolution(res) != null) meta.putMeta(SyncMetaEntity(SyncEngine.RESOLUTION_PREFIX + uuid, res))
        }
    }

    private suspend fun fetch(forDate: String, isDay: Boolean, onPrefetch: Boolean, ifNoneMatch: String?, configVersion: Long?): BundleReport {
        return when (val r = api.bundle(forDate, ifNoneMatch, configVersion)) {
            is ApiResult.NotModified -> {
                if (onPrefetch) {
                    if (repo.promotePrefetch(forDate) == ApplyResult.APPLIED) {
                        markLoggedIn(forDate)
                        return BundleReport(BundleOutcome.PREFETCH_PROMOTED, repo.bundleVersion(), repo.businessDate())
                    }
                    // The stored prefetch could not be used: ask again without a condition, never report a stale day as current.
                    return fetch(forDate, isDay, onPrefetch = false, ifNoneMatch = null, configVersion = configVersion)
                }
                if (isDay) markLoggedIn(forDate)
                BundleReport(BundleOutcome.UNCHANGED, if (isDay) repo.bundleVersion() else repo.prefetch()?.first, forDate)
            }
            is ApiResult.Transport -> BundleReport(
                if (r.failure == TransportFailure.MALFORMED) BundleOutcome.FAILED else BundleOutcome.OFFLINE,
                code = r.failure.name.lowercase(),
            )
            is ApiResult.Failure -> BundleReport(failureOutcome(r.httpStatus), code = r.problem.code ?: "http_${r.httpStatus}")
            is ApiResult.Success -> {
                val download = r.value
                val version = download.head.meta.bundleVersion
                val raw = try {
                    Json.parseToJsonElement(download.rawJson).jsonObject
                } catch (e: IllegalArgumentException) { // SerializationException is one too
                    return BundleReport(BundleOutcome.FAILED, version, code = "malformed")
                }
                val stage = File(stagingDir, version.replace(Regex("[^0-9A-Za-z._-]"), "_"))
                withContext(Dispatchers.IO) {
                    // Only an older stage of the same day is stale; a day download and a prefetch keep their own stages.
                    val day = stage.name.substringBefore('_')
                    stagingDir.listFiles()?.filter { it != stage && (it.name.substringBefore('_') == day || it.name.substringBefore('_') < day) }
                        ?.forEach { it.deleteRecursively() }
                    stage.mkdirs()
                }
                val pages = HashMap<String, MutableList<JsonElement>>()
                for (paged in download.head.meta.pagedSections) {
                    for (page in 1..paged.pages) {
                        val file = File(stage, "${paged.section}-$page.json")
                        val text = withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.readText() } ?: when (val p = api.bundlePage(version, paged.section, page)) {
                            is ApiResult.Success -> p.value.also { writeAtomically(file, it) }
                            is ApiResult.Transport -> return BundleReport(BundleOutcome.OFFLINE, version, code = p.failure.name.lowercase())
                            is ApiResult.Failure -> return BundleReport(failureOutcome(p.httpStatus), version, code = p.problem.code)
                            is ApiResult.NotModified -> return BundleReport(BundleOutcome.FAILED, version, code = "page_not_modified")
                        }
                        val rows = try {
                            val o = Json.parseToJsonElement(text).jsonObject
                            // A page must be the one asked for: same snapshot, section and number, with its rows member.
                            require((o["bundle_version"] as? JsonPrimitive)?.content == version)
                            require((o["section"] as? JsonPrimitive)?.content == paged.section)
                            require((o["page"] as? JsonPrimitive)?.content == page.toString())
                            o["rows"]!!.jsonArray
                        } catch (e: RuntimeException) {
                            withContext(Dispatchers.IO) { file.delete() }
                            return BundleReport(BundleOutcome.FAILED, version, code = "malformed_page")
                        }
                        pages.getOrPut(paged.section) { ArrayList() }.addAll(rows)
                    }
                    val got = pages[paged.section].orEmpty().size
                    if (paged.rows > 0 && got != paged.rows) {
                        withContext(Dispatchers.IO) { stage.deleteRecursively() }
                        return BundleReport(BundleOutcome.FAILED, version, code = "page_rows_mismatch")
                    }
                }
                val merged = merge(raw, pages)
                val bundle = try {
                    BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), merged)
                } catch (e: IllegalArgumentException) {
                    return BundleReport(BundleOutcome.FAILED, version, code = "malformed")
                }
                val applied = repo.apply(bundle, merged, download.etag ?: "\"$version\"", asDay = isDay)
                withContext(Dispatchers.IO) { stage.deleteRecursively() }
                if (isDay) markLoggedIn(forDate)
                when (applied) {
                    ApplyResult.APPLIED -> BundleReport(BundleOutcome.APPLIED, repo.bundleVersion(), repo.businessDate())
                    ApplyResult.OLDER_IGNORED -> BundleReport(BundleOutcome.OLDER_IGNORED, repo.bundleVersion(), repo.businessDate())
                    ApplyResult.PREFETCH_STORED -> BundleReport(BundleOutcome.PREFETCH_STORED, version, bundle.meta.validForBusinessDate)
                }
            }
        }
    }

    /** True once a bundle request of [businessDate] was answered (the day counts as logged in on the server). */
    suspend fun loggedIn(businessDate: String): Boolean = meta.meta(KEY_LOGGED_IN + businessDate) != null

    private suspend fun markLoggedIn(businessDate: String) =
        meta.putMeta(SyncMetaEntity(KEY_LOGGED_IN + businessDate, iso(clock.nowMs())))

    private fun failureOutcome(status: Int) = when (status) {
        401 -> BundleOutcome.AUTH_REQUIRED
        503 -> BundleOutcome.NOT_READY
        else -> BundleOutcome.FAILED
    }

    private suspend fun writeAtomically(file: File, text: String) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        check(tmp.renameTo(file)) { "could not stage ${file.name}" }
    }

    companion object {
        const val KEY_LOGGED_IN = "bundle.logged_in."
        const val KEY_DELTA_FOREGROUND_AT = "bundle.delta_foreground_at"

        /** Paged sections whose rows belong inside `routes[]` (matched by `route_id`). */
        private val ROUTE_NESTED = setOf("outlets", "open_memos")

        /** Paged sections that live inside `supervisor` (SupervisorSection). */
        private val SUPERVISOR_NESTED = setOf("team", "pending_outlet_requests")

        private fun iso(ms: Long) = SyncEngine.iso(ms)

        /** Merges the rows of paged sections into the bundle JSON (s4.10 Size), so the snapshot is applied as one whole. */
        internal fun merge(raw: JsonObject, pages: Map<String, List<JsonElement>>): JsonObject {
            if (pages.isEmpty()) return raw
            val out = raw.toMutableMap()
            for ((section, rows) in pages) {
                if (section in SUPERVISOR_NESTED) {
                    val sup = (out["supervisor"] as? JsonObject).orEmpty()
                    out["supervisor"] = JsonObject(sup + (section to JsonArray((sup[section] as? JsonArray).orEmpty() + rows)))
                } else if (section in ROUTE_NESTED) {
                    val byRoute = rows.groupBy { (it as? JsonObject)?.get("route_id")?.let { id -> (id as? JsonPrimitive)?.content } }
                    val known = (out["routes"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("route_id") as? JsonPrimitive)?.content }.toSet()
                    // Rows of a route not in routes[] (an AMO's team routes) are kept, not dropped.
                    val orphans = byRoute.filterKeys { it !in known }.values.flatten()
                    if (orphans.isNotEmpty()) out["${section}_other_routes"] = JsonArray((out["${section}_other_routes"] as? JsonArray).orEmpty() + orphans)
                    out["routes"] = JsonArray(
                        (out["routes"] as? JsonArray).orEmpty().map { r ->
                            val o = r as? JsonObject ?: return@map r
                            val id = (o["route_id"] as? JsonPrimitive)?.content
                            val add = byRoute[id].orEmpty()
                            if (add.isEmpty()) o else JsonObject(o + (section to JsonArray((o[section] as? JsonArray).orEmpty() + add)))
                        },
                    )
                } else if (out[section] is JsonObject) {
                    out["${section}_rows"] = JsonArray(rows) // an object section (programmes): never replaced by an array
                } else {
                    out[section] = JsonArray((out[section] as? JsonArray).orEmpty() + rows)
                }
            }
            return JsonObject(out)
        }
    }
}

/** One [BundleDownloader] per user (its database and its staging folder under noBackupFilesDir). */
class BundleDownloaders(
    private val stagingRoot: File,
    private val databases: com.aktcl.aron.core.database.UserDatabases,
    private val api: SyncApi,
    private val clock: WallClock,
) {
    private val byUser = java.util.concurrent.ConcurrentHashMap<Long, BundleDownloader>()

    suspend fun of(userId: Long): BundleDownloader = byUser[userId] ?: databases.of(userId).let { db ->
        byUser.computeIfAbsent(userId) { BundleDownloader(db, api, File(stagingRoot, "u$it"), clock) }
    }
}
