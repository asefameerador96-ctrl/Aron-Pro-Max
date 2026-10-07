package com.aktcl.aron.core.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/** Where a photo is in its life. Transitions only move forward. */
@Serializable
enum class MediaState {
    /** Compressed and stored; not yet claimed by a record (a retake or a cancelled form leaves it here). */
    @SerialName("captured") CAPTURED,
    /** Claimed by a record; waits for that record's ack, then uploads. */
    @SerialName("attached") ATTACHED,
    /** The blob is in storage; the media_meta record is next. */
    @SerialName("uploaded") UPLOADED,
    /** media_meta is in the outbox: the photo is done here (the file is kept `local_keep_days` for display). */
    @SerialName("meta_queued") META_QUEUED,
}

/** The fix taken at the shutter (docs/17 s8.6): kept in the queue and in media_meta, never in the JPEG. */
@Serializable
data class PhotoStamp(
    val lat: Double?,
    val lng: Double?,
    @SerialName("accuracy_m") val accuracyM: Double?,
    @SerialName("is_mock") val isMock: Boolean,
    /** The geo_fix row stored with the owning record, which media_meta.fix is built from. */
    @SerialName("fix_client_uuid") val fixClientUuid: String? = null,
)

/** The record that owns a photo: `ref_type` and `ref_client_uuid` of MediaMetaPayload. */
@Serializable
data class MediaRef(
    /** MediaPurpose: force_sale, outlet_capture, outlet_verification, survey, feedback, support, gift_photo. */
    val purpose: String,
    /** RecordType of the owning record (visit, outlet_change_request, ...). */
    @SerialName("ref_type") val refType: String,
    @SerialName("ref_client_uuid") val refClientUuid: String,
)

@Serializable
data class MediaItem(
    @SerialName("media_uuid") val mediaUuid: String,
    @SerialName("business_date") val businessDate: String,
    /** ISO-8601 UTC instant of the shutter (trusted clock). */
    @SerialName("taken_at") val takenAt: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    val sha256: String,
    val phash: String?,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val state: MediaState = MediaState.CAPTURED,
    val ref: MediaRef? = null,
    val stamp: PhotoStamp? = null,
    @SerialName("attached_at_ms") val attachedAtMs: Long? = null,
    /** True once the owning record was seen in the outbox (so its later purge reads as "acked", not "never saved"). */
    @SerialName("ref_seen") val refSeen: Boolean = false,
    @SerialName("blob_path") val blobPath: String? = null,
    @SerialName("uploaded_at_ms") val uploadedAtMs: Long? = null,
    val attempts: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
) {
    val evidence: Boolean get() = ref?.purpose in EVIDENCE_PURPOSES

    companion object {
        /** Evidence photos (docs/24 s5.5): camera-only, and they fall back to mobile data after the grace period. */
        val EVIDENCE_PURPOSES = setOf("force_sale", "outlet_capture", "outlet_verification")
    }
}

/**
 * The media queue of one user (docs/24 s5.2 `files/media/`): `<uuid>.jpg` plus a `<uuid>.json` sidecar, each written to a
 * temporary file, synced and renamed, so a kill at any instant leaves either the old or the new state, never a torn one.
 * Idempotent by media UUID. Small by design (tens of photos a day), so a directory listing is the index.
 */
class MediaStore(val dir: File) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        dir.mkdirs()
    }

    fun photoFile(uuid: String) = File(dir, "${checked(uuid)}.jpg")
    fun thumbnailFile(uuid: String) = File(dir, "${checked(uuid)}.thumb.jpg")
    private fun sidecar(uuid: String) = File(dir, "${checked(uuid)}.json")

    /** Stores a new photo. A second put of the same UUID changes nothing and returns the stored item. */
    suspend fun put(item: MediaItem, jpeg: ByteArray, thumbnail: ByteArray?): MediaItem = lock.withLock {
        readSidecar(item.mediaUuid)?.let { return it }
        require(jpeg.size == item.bytes && PhotoCompressor.sha256Hex(jpeg) == item.sha256) { "photo bytes do not match the item" }
        atomicWrite(photoFile(item.mediaUuid), jpeg)
        thumbnail?.let { atomicWrite(thumbnailFile(item.mediaUuid), it) }
        // The sidecar is written last: a photo without a sidecar is an unfinished capture and is swept by [sweepUnfinished].
        atomicWrite(sidecar(item.mediaUuid), json.encodeToString(MediaItem.serializer(), item).toByteArray())
        item
    }

    suspend fun get(uuid: String): MediaItem? = lock.withLock { readSidecar(uuid) }

    suspend fun all(): List<MediaItem> = lock.withLock {
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { readSidecar(it.name.removeSuffix(".json")) }
            .sortedBy { it.createdAtMs }
    }

    /** Applies [change] atomically; returns the new item, or null when the UUID is unknown. */
    suspend fun update(uuid: String, change: (MediaItem) -> MediaItem): MediaItem? = lock.withLock {
        val cur = readSidecar(uuid) ?: return null
        val next = change(cur)
        require(next.mediaUuid == cur.mediaUuid && next.sha256 == cur.sha256) { "identity of a stored photo cannot change" }
        if (next != cur) atomicWrite(sidecar(uuid), json.encodeToString(MediaItem.serializer(), next).toByteArray())
        next
    }

    /** Removes a photo and its sidecar (sidecar first, so a kill in between leaves a swept orphan, never a dangling item). */
    suspend fun delete(uuid: String) = lock.withLock {
        sidecar(uuid).delete()
        photoFile(uuid).delete()
        thumbnailFile(uuid).delete()
    }

    /** Deletes the photo only if [condition] holds, checked and done under one lock (no attach can slip in between). */
    suspend fun deleteIf(uuid: String, condition: (MediaItem) -> Boolean): Boolean = lock.withLock {
        val cur = readSidecar(uuid) ?: return false
        if (!condition(cur)) return false
        sidecar(uuid).delete(); photoFile(uuid).delete(); thumbnailFile(uuid).delete()
        true
    }

    /** Deletes photos whose sidecar was never written and stray temporary files (a kill during [put]). */
    suspend fun sweepUnfinished(): Int = lock.withLock {
        var n = 0
        dir.listFiles().orEmpty().forEach { f ->
            val orphanPhoto = f.name.endsWith(".jpg") && !File(dir, f.name.substringBefore('.') + ".json").exists()
            if (f.name.endsWith(".tmp") || orphanPhoto) { if (f.delete()) n++ }
        }
        n
    }

    fun readPhoto(uuid: String): ByteArray = photoFile(uuid).readBytes()

    private fun readSidecar(uuid: String): MediaItem? {
        val f = sidecar(uuid)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(MediaItem.serializer(), f.readText()) }.getOrNull()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not store ${target.name}" }
        }
    }

    private fun checked(uuid: String): String {
        require(UUID_V4.matches(uuid)) { "media uuid must be a lower-case UUID v4" }
        return uuid
    }

    companion object {
        private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        /** The per-user directory under the app's private files (docs/24 s5.2). */
        fun forUser(filesDir: File, userId: Long) = MediaStore(File(filesDir, "media/u$userId"))
    }
}
