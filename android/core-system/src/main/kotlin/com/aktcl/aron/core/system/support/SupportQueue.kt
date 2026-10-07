package com.aktcl.aron.core.system.support

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class SupportState {
    /** Built and stored; waits for a network ("will be sent when online"). */
    @SerialName("queued") QUEUED,
    @SerialName("sent") SENT,
    /** The server refused it for good (too large, bad request): shown, and the rep can build a new one. */
    @SerialName("failed") FAILED,
}

@Serializable
data class SupportJob(
    @SerialName("upload_uuid") val uploadUuid: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("last_sync_at") val lastSyncAt: String?,
    @SerialName("pending_rows") val pendingRows: Int,
    val bytes: Int,
    val sha256: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    val state: SupportState = SupportState.QUEUED,
    @SerialName("sent_at_ms") val sentAtMs: Long? = null,
    val attempts: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
)

/**
 * The PDA to Support queue of one user (F-SYS-021): the encrypted file plus its state, each written temp-then-rename, so a
 * kill never leaves half a job. Idempotent by `upload_uuid` (the server returns the same blob path for a repeat). Only the
 * newest job is kept: a new send replaces an older unsent or finished one.
 */
class SupportQueue(val dir: File) {
    private val lock = locks.computeIfAbsent(dir.absolutePath) { Mutex() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init { dir.mkdirs() }

    private fun dataFile(uuid: String) = File(dir, "$uuid.bin")
    private fun stateFile(uuid: String) = File(dir, "$uuid.json")

    suspend fun enqueue(uploadUuid: String, sealed: ByteArray, appVersion: String, lastSyncAt: String?, pendingRows: Int, nowMs: Long): SupportJob = lock.withLock {
        readJob(uploadUuid)?.let { return it }
        val job = SupportJob(uploadUuid, appVersion, lastSyncAt, pendingRows, sealed.size, sha256(sealed), nowMs)
        atomicWrite(dataFile(uploadUuid), sealed)
        atomicWrite(stateFile(uploadUuid), json.encodeToString(SupportJob.serializer(), job).toByteArray())
        // Keep only the newest job.
        dir.listFiles().orEmpty().filter { !it.name.startsWith(uploadUuid) }.forEach { it.delete() }
        job
    }

    suspend fun current(): SupportJob? = lock.withLock {
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { readJob(it.name.removeSuffix(".json")) }.maxByOrNull { it.createdAtMs }
    }

    suspend fun update(uuid: String, change: (SupportJob) -> SupportJob): SupportJob? = lock.withLock {
        val cur = readJob(uuid) ?: return null
        val next = change(cur)
        atomicWrite(stateFile(uuid), json.encodeToString(SupportJob.serializer(), next).toByteArray())
        // A sent file is not kept on the phone (it holds unsent payloads in encrypted form; support has it now).
        if (next.state == SupportState.SENT) dataFile(uuid).delete()
        next
    }

    fun read(uuid: String): ByteArray? = dataFile(uuid).takeIf { it.isFile }?.readBytes()

    private fun readJob(uuid: String): SupportJob? =
        stateFile(uuid).takeIf { it.isFile }?.let { runCatching { json.decodeFromString(SupportJob.serializer(), it.readText()) }.getOrNull() }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "could not store ${target.name}" } }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun forUser(filesDir: File, userId: Long) = SupportQueue(File(filesDir, "support/u$userId"))

        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
