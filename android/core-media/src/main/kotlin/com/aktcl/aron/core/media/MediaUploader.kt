package com.aktcl.aron.core.media

import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the record that owns a photo stands. */
enum class RefState {
    /** Acknowledged by the server, including an acked row already purged from the outbox. */
    ACKED,
    /** In the outbox, not yet acknowledged (pending, in flight, or quarantined awaiting a resolution). */
    PENDING,
    /** Rejected by the server: the photo has no record to belong to. */
    REJECTED,
    /** Neither in the outbox nor in its domain table: the record was never saved. */
    ABSENT,
}

/** Reads the owning record's state (docs/24 s4.11 step 1: the record always syncs before its photo). */
fun interface RecordAckProbe {
    suspend fun state(refClientUuid: String): RefState
}

/**
 * The probe on the user's database: the outbox row's state, and, when the row is gone (acked rows are purged), whether
 * the record itself exists, so an acked-then-purged record reads ACKED and never as "never saved".
 */
class OutboxRecordProbe(
    /** `outbox.state` of the record (`pending`, `in_flight`, `acked`, `rejected`, `quarantined`), or null when no row. */
    private val outboxState: suspend (clientUuid: String) -> String?,
    /** True when the record's domain row (visit, outlet_change_request, ...) exists on the phone. */
    private val recordExists: suspend (clientUuid: String) -> Boolean,
) : RecordAckProbe {
    override suspend fun state(refClientUuid: String): RefState = when (outboxState(refClientUuid)) {
        "acked" -> RefState.ACKED
        "rejected" -> RefState.REJECTED
        null -> if (recordExists(refClientUuid)) RefState.ACKED else RefState.ABSENT
        else -> RefState.PENDING
    }
}

/** Puts the `media_meta` record into the outbox (android-core, request android-sys-media-meta). True once it is queued (or was). */
fun interface MediaMetaSink {
    suspend fun enqueue(item: MediaItem): Boolean
}

enum class NetworkKind { NONE, UNMETERED, METERED }

/** One `MediaSasRequest` item. */
data class SasItem(val mediaUuid: String, val purpose: String, val sha256: String, val bytes: Int, val businessDate: String)

/** One `MediaSasResponse` item. */
data class SasTarget(
    val mediaUuid: String,
    val uploadUrl: String,
    val blobPath: String,
    val requiredHeaders: Map<String, String>,
    val alreadyUploaded: Boolean,
)

sealed interface SasResult {
    data class Ok(val targets: List<SasTarget>) : SasResult
    /** Offline, 429, 5xx, 401 after the refresh: try again on the next run. */
    data class Retry(val code: String) : SasResult
    /** 400: this batch will never be accepted as sent. */
    data class Rejected(val code: String) : SasResult
}

enum class PutResult { STORED, EXPIRED, RETRY, REJECTED }

/** `POST /v1/media/sas` (upload grant) and the blob `PUT` (no Aron headers, no token: the SAS is the credential). */
interface MediaUploadApi {
    suspend fun sas(items: List<SasItem>): SasResult
    suspend fun put(target: SasTarget, jpeg: ByteArray): PutResult
}

data class MediaRunReport(
    val uploaded: Int = 0,
    val metaQueued: Int = 0,
    /** Attached photos still waiting for their record's ack. */
    val waitingForRecord: Int = 0,
    /** Uploadable photos held back by the Wi-Fi-only rule on this network. */
    val heldForWifi: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
    /** Photos still to upload after this run (any reason). */
    val remaining: Int = 0,
    /** Earliest wall time an evidence photo held for Wi-Fi may use mobile data; null when none is held. */
    val evidenceFallbackAtMs: Long? = null,
    /** Something retryable failed this run (offline, 5xx, 429, an expired SAS, the meta sink): retry with backoff. */
    val needsRetry: Boolean = false,
    /** Photos that moved to FAILED in this run. */
    val failedForGood: Int = 0,
    /**
     * An evidence photo is past its mobile-data fallback moment but still waits (its record is not acked yet): the
     * follow-up must be able to run on mobile data.
     */
    val evidenceDueOnMobile: Boolean = false,
)

/**
 * The media queue's upload run (F-SYS-010, F-SYS-037; docs/24 s4.11). It runs in its own WorkManager job (`aron-media`), so
 * a slow or failing photo can never hold up the record sync, which knows nothing about photos. Per photo:
 * (1) wait until the owning record is acked; (2) ask for write-only SAS URLs, up to 10 at a time; (3) PUT the stored bytes
 * (re-checked against their SHA-256); (4) queue `media_meta`. Every state is stored after each step, so a kill resumes
 * from the last finished step, and every step is idempotent by media UUID (a repeated PUT writes the same blob path with the
 * same bytes; `already_uploaded` skips it).
 */
class MediaUploader(
    private val store: MediaStore,
    private val api: MediaUploadApi,
    private val probe: RecordAckProbe,
    private val sink: MediaMetaSink,
    private val clock: WallClock,
    private val config: () -> MediaConfig = { MediaConfig() },
    /** The rep's Wi-Fi-only switch (F-SYS-037); defaults to `cfg.media.wifi_only_default`. */
    private val wifiOnly: () -> Boolean = { config().wifiOnlyDefault },
) {
    /** Set by a run when a retryable step failed; read into the report. Runs of one user never overlap ([lockFor]). */
    private var retry = false
    private var failedForGood = 0

    /**
     * One run. Runs for the same user are serialised process-wide (the Wi-Fi job, the any-network job and the fallback
     * job may start together), so a photo is never sent twice by two jobs.
     */
    suspend fun run(network: NetworkKind): MediaRunReport = lockFor(store.dir).withLock { runLocked(network) }

    private suspend fun runLocked(network: NetworkKind): MediaRunReport {
        retry = false
        failedForGood = 0
        val now = clock.nowMs()
        var removed = housekeeping(now)
        var waiting = 0
        var held = 0
        var failed = 0
        var fallbackAt: Long? = null
        var evidenceDue = false
        val ready = mutableListOf<MediaItem>()

        for (item in store.all().filter { it.state == MediaState.ATTACHED }) {
            val ref = item.ref ?: continue
            val state = probe.state(ref.refClientUuid)
            val seen = item.refSeen || state == RefState.ACKED || state == RefState.PENDING
            val cur = if (seen && !item.refSeen) store.update(item.mediaUuid) { it.copy(refSeen = true) } ?: continue else item
            val recordDone = state == RefState.ACKED || (state == RefState.ABSENT && cur.refSeen) // acked rows are purged later
            if (!recordDone) {
                if (expired(cur, state, now)) { store.delete(cur.mediaUuid); removed++ } else {
                    waiting++
                    if (state == RefState.PENDING && networkGate(cur, NetworkKind.METERED, now) == null) evidenceDue = true
                }
                continue
            }
            when (val gate = networkGate(cur, network, now)) {
                null -> ready += cur
                else -> { held++; if (gate > 0) fallbackAt = minOf(fallbackAt ?: gate, gate) }
            }
        }

        var uploaded = 0
        if (network == NetworkKind.NONE && ready.isNotEmpty()) retry = true // the validated network went away: try again
        if (network != NetworkKind.NONE) {
            for (chunk in ready.chunked(SAS_BATCH)) {
                val stop = uploadChunk(chunk) { ok -> if (ok) uploaded++ else failed++ }
                if (stop) { retry = true; break }
            }
        }

        var metaQueued = 0
        for (item in store.all().filter { it.state == MediaState.UPLOADED }) {
            val queued = attempt(false) { sink.enqueue(item) }
            if (queued) { store.update(item.mediaUuid) { it.copy(state = MediaState.META_QUEUED) }; metaQueued++ } else retry = true
        }

        val remaining = store.all().count { it.state == MediaState.ATTACHED || it.state == MediaState.UPLOADED }
        return MediaRunReport(uploaded, metaQueued, waiting, held, failed, removed, remaining, fallbackAt, retry, failedForGood, evidenceDue)
    }

    /**
     * Null when [item] may upload on [network] now. Otherwise the wall time from which an evidence photo may use mobile
     * data (0 when it never will on this network).
     */
    internal fun networkGate(item: MediaItem, network: NetworkKind, now: Long): Long? {
        if (network == NetworkKind.UNMETERED) return null
        if (!wifiOnly()) return if (network == NetworkKind.METERED) null else 0
        val h = config().evidenceMobileFallbackH
        if (!item.evidence || h <= 0) return 0
        val from = item.createdAtMs + h * HOUR_MS
        return if (network == NetworkKind.METERED && now >= from) null else from
    }

    /** Returns true when the run should stop trying further chunks (the server or network is not answering). */
    private suspend fun uploadChunk(chunk: List<MediaItem>, count: (Boolean) -> Unit): Boolean {
        val byUuid = chunk.associateBy { it.mediaUuid }
        val result = attempt<SasResult>(SasResult.Retry("exception")) { api.sas(chunk.map { SasItem(it.mediaUuid, it.ref!!.purpose, it.sha256, it.bytes, it.businessDate) }) }
        when (result) {
            is SasResult.Retry -> { chunk.forEach { note(it, result.code) }; return true }
            is SasResult.Rejected -> {
                // A 400 refuses the whole request: ask again photo by photo, so only the photo at fault is counted.
                if (chunk.size > 1) {
                    for (one in chunk) if (uploadChunk(listOf(one), count)) return true
                    return false
                }
                chunk.forEach { permanent(it, result.code); count(false) }
                return false
            }
            is SasResult.Ok -> Unit
        }
        val answered = result.targets.map { it.mediaUuid }.toSet()
        chunk.filter { it.mediaUuid !in answered }.forEach { note(it, "sas_missing"); retry = true }
        for (target in result.targets.distinctBy { it.mediaUuid }) {
            val item = byUuid[target.mediaUuid] ?: continue // never act on a target we did not ask for
            if (!BLOB_PATH.matches(target.blobPath) || !target.blobPath.endsWith("/${item.mediaUuid}.jpg")) {
                permanent(item, "bad_blob_path"); count(false); continue
            }
            if (target.alreadyUploaded) { markUploaded(item, target.blobPath); count(true); continue }
            val file = store.photoFile(item.mediaUuid)
            val bytes = if (!file.isFile) null else try { file.readBytes() } catch (_: java.io.IOException) { note(item, "read_failed"); retry = true; continue }
            if (bytes == null || PhotoCompressor.sha256Hex(bytes) != item.sha256) {
                // The stored file no longer matches what was captured: never upload different bytes under this uuid.
                fail(item, "local_file_damaged"); count(false); continue
            }
            when (attempt(PutResult.RETRY) { api.put(target, bytes) }) {
                PutResult.STORED -> { markUploaded(item, target.blobPath); count(true) }
                PutResult.EXPIRED -> { note(item, "sas_expired"); retry = true }
                PutResult.REJECTED -> { permanent(item, "put_rejected"); count(false) }
                PutResult.RETRY -> { note(item, "put_retry"); return true }
            }
        }
        return false
    }

    /** Any failure of a remote step is a retry, but cancellation (WorkManager stopping the job) always propagates. */
    private inline fun <T> attempt(onFailure: T, block: () -> T): T = try {
        block()
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (_: Exception) {
        onFailure
    }

    private suspend fun markUploaded(item: MediaItem, blobPath: String) {
        store.update(item.mediaUuid) { it.copy(state = MediaState.UPLOADED, blobPath = blobPath, uploadedAtMs = clock.nowMs(), lastError = null) }
    }

    private suspend fun note(item: MediaItem, code: String) {
        store.update(item.mediaUuid) { it.copy(attempts = it.attempts + 1, lastError = code) }
    }

    /** A refusal of this photo alone: a few more tries on later runs (its own counter), then FAILED. */
    private suspend fun permanent(item: MediaItem, code: String) {
        val next = store.update(item.mediaUuid) { it.copy(refusals = it.refusals + 1, lastError = code) } ?: return
        if (next.refusals >= MAX_PERMANENT_ATTEMPTS) fail(next, code)
    }

    private suspend fun fail(item: MediaItem, code: String) {
        store.update(item.mediaUuid) { it.copy(state = MediaState.FAILED, attempts = it.attempts + 1, lastError = code) }
        failedForGood++
    }

    /**
     * Past the grace period with a record that will never be acked: never seen in the outbox (the save never happened) or
     * rejected by the server. A pending or quarantined record keeps its photo however long it takes.
     */
    private fun expired(item: MediaItem, state: RefState, now: Long) =
        (state == RefState.REJECTED || !item.refSeen) && now - (item.attachedAtMs ?: item.createdAtMs) > config().pendingPhotoGraceDays * DAY_MS

    private suspend fun housekeeping(now: Long): Int {
        var n = store.sweepUnfinished()
        for (item in store.all()) {
            val drop = when (item.state) {
                MediaState.CAPTURED -> now - item.createdAtMs > UNCLAIMED_KEEP_MS // a retake's leftover or an abandoned form
                MediaState.META_QUEUED -> now - (item.uploadedAtMs ?: item.createdAtMs) > config().localKeepDays * DAY_MS
                MediaState.FAILED -> now - item.createdAtMs > FAILED_KEEP_DAYS * DAY_MS
                else -> false
            }
            if (drop) { store.delete(item.mediaUuid); n++ }
        }
        return n
    }

    companion object {
        const val SAS_BATCH = 10
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val UNCLAIMED_KEEP_MS = DAY_MS
        const val MAX_PERMANENT_ATTEMPTS = 3
        /** A failed photo stays this long for the support upload (F-SYS-021), then goes. */
        const val FAILED_KEEP_DAYS = 14L

        private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        /** The process-wide run lock of one user's media queue. */
        fun lockFor(dir: java.io.File): Mutex = locks.computeIfAbsent(dir.absolutePath) { Mutex() }
        /** MediaMetaPayload.blob_path pattern of the contract. */
        val BLOB_PATH = Regex("^photos/\\d{4}-\\d{2}-\\d{2}/[0-9a-f-]{36}/[0-9a-f-]{36}\\.jpg$")
    }
}
