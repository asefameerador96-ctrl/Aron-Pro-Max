package com.aktcl.aron.core.sync

import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.ApplyResult
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

    /** No connection or an edge page; staged pages are kept and the next call resumes. */
    OFFLINE,

    /** 503 `ERR_BUNDLE_NOT_READY`. */
    NOT_READY,

    /** 401: the user must sign in online. */
    AUTH_REQUIRED,

    /** Any other answer, or a body that cannot be read; the stored snapshot stays. */
    FAILED,
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
    suspend fun download(forDate: String = BusinessDate.of(clock.nowMs()).toString()): BundleReport = lock.withLock {
        val ifNoneMatch = if (repo.businessDate() == forDate) repo.etag() else null
        val configVersion = meta.meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull()
        when (val r = api.bundle(forDate, ifNoneMatch, configVersion)) {
            is ApiResult.NotModified -> {
                markLoggedIn(forDate)
                BundleReport(BundleOutcome.UNCHANGED, repo.bundleVersion(), forDate)
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
                    return@withLock BundleReport(BundleOutcome.FAILED, version, code = "malformed")
                }
                val stage = File(stagingDir, version.replace(Regex("[^0-9A-Za-z._-]"), "_"))
                withContext(Dispatchers.IO) {
                    stagingDir.listFiles()?.filter { it != stage }?.forEach { it.deleteRecursively() }
                    stage.mkdirs()
                }
                val pages = HashMap<String, MutableList<JsonElement>>()
                for (paged in download.head.meta.pagedSections) {
                    for (page in 1..paged.pages) {
                        val file = File(stage, "${paged.section}-$page.json")
                        val text = withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.readText() } ?: when (val p = api.bundlePage(version, paged.section, page)) {
                            is ApiResult.Success -> p.value.also { writeAtomically(file, it) }
                            is ApiResult.Transport -> return@withLock BundleReport(BundleOutcome.OFFLINE, version, code = p.failure.name.lowercase())
                            is ApiResult.Failure -> return@withLock BundleReport(failureOutcome(p.httpStatus), version, code = p.problem.code)
                            is ApiResult.NotModified -> return@withLock BundleReport(BundleOutcome.FAILED, version, code = "page_not_modified")
                        }
                        val rows = try {
                            Json.parseToJsonElement(text).jsonObject["rows"]?.jsonArray.orEmpty()
                        } catch (e: IllegalArgumentException) {
                            withContext(Dispatchers.IO) { file.delete() }
                            return@withLock BundleReport(BundleOutcome.FAILED, version, code = "malformed_page")
                        }
                        pages.getOrPut(paged.section) { ArrayList() } += rows
                    }
                }
                val merged = merge(raw, pages)
                val bundle = try {
                    BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), merged)
                } catch (e: IllegalArgumentException) {
                    return@withLock BundleReport(BundleOutcome.FAILED, version, code = "malformed")
                }
                val applied = repo.apply(bundle, merged, download.etag ?: "\"$version\"")
                withContext(Dispatchers.IO) { stage.deleteRecursively() }
                if (bundle.meta.isPrefetch.not()) markLoggedIn(bundle.meta.validForBusinessDate)
                val outcome = if (applied == ApplyResult.APPLIED) BundleOutcome.APPLIED else BundleOutcome.OLDER_IGNORED
                BundleReport(outcome, repo.bundleVersion(), repo.businessDate())
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

        /** Paged sections whose rows belong inside `routes[]` (matched by `route_id`); others are top-level arrays. */
        private val ROUTE_NESTED = setOf("outlets", "open_memos")

        private fun iso(ms: Long) = SyncEngine.iso(ms)

        /** Merges the rows of paged sections into the bundle JSON (s4.10 Size), so the snapshot is applied as one whole. */
        internal fun merge(raw: JsonObject, pages: Map<String, List<JsonElement>>): JsonObject {
            if (pages.isEmpty()) return raw
            val out = raw.toMutableMap()
            for ((section, rows) in pages) {
                if (section in ROUTE_NESTED) {
                    val byRoute = rows.groupBy { (it as? JsonObject)?.get("route_id")?.let { id -> (id as? JsonPrimitive)?.content } }
                    out["routes"] = JsonArray(
                        (out["routes"] as? JsonArray).orEmpty().map { r ->
                            val o = r as? JsonObject ?: return@map r
                            val id = (o["route_id"] as? JsonPrimitive)?.content
                            val add = byRoute[id].orEmpty()
                            if (add.isEmpty()) o else JsonObject(o + (section to JsonArray((o[section] as? JsonArray).orEmpty() + add)))
                        },
                    )
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

    fun of(userId: Long): BundleDownloader = byUser.computeIfAbsent(userId) {
        BundleDownloader(databases.of(it), api, File(stagingRoot, "u$it"), clock)
    }
}
