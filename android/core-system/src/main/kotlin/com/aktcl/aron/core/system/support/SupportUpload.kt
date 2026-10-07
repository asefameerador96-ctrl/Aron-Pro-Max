package com.aktcl.aron.core.system.support

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.Grant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// Local mirrors of contract SupportUploadRequest / SupportUpload (REQUEST: docs/requests/android-core-contract-dtos.md).
@Serializable
internal data class SupportUploadRequestDto(
    @SerialName("upload_uuid") val uploadUuid: String,
    val bytes: Int,
    val sha256: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("last_sync_at") val lastSyncAt: String?,
    @SerialName("pending_rows") val pendingRows: Int,
)

@Serializable
internal data class SupportUploadDto(
    @SerialName("upload_uuid") val uploadUuid: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("blob_path") val blobPath: String,
    @SerialName("expires_at") val expiresAt: String,
)

enum class SupportStep { SENT, RETRY, REFUSED }

/** `POST /v1/support/pda-upload` then the blob PUT. */
fun interface SupportApi {
    suspend fun send(job: SupportJob, file: ByteArray): Pair<SupportStep, String?>
}

class SupportHttpApi(private val client: AronApiClient, baseClient: OkHttpClient) : SupportApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val blob = baseClient.newBuilder().callTimeout(120, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    override suspend fun send(job: SupportJob, file: ByteArray): Pair<SupportStep, String?> {
        val body = json.encodeToString(
            SupportUploadRequestDto.serializer(),
            SupportUploadRequestDto(job.uploadUuid, job.bytes, job.sha256, job.appVersion, job.lastSyncAt, job.pendingRows),
        )
        val r = client.call(
            path = "/v1/support/pda-upload",
            auth = CallAuth.Grant(Grant.FULL),
            build = { post(body.toRequestBody("application/json".toMediaType())) },
            decode = { text, _ -> json.decodeFromString(SupportUploadDto.serializer(), text) },
        )
        val target = when (r) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return (if (r.httpStatus == 400 || r.httpStatus == 413) SupportStep.REFUSED else SupportStep.RETRY) to "http_${r.httpStatus}"
            is ApiResult.Transport -> return SupportStep.RETRY to r.failure.name.lowercase()
            is ApiResult.NotModified -> return SupportStep.RETRY to "unexpected_304"
        }
        val url = target.uploadUrl.toHttpUrlOrNull()?.takeIf { it.isHttps || it.host in setOf("localhost", "127.0.0.1", "::1") }
            ?: return SupportStep.REFUSED to "insecure_url"
        val req = Request.Builder().url(url).put(file.toRequestBody("application/octet-stream".toMediaType()))
            .header("x-ms-blob-type", "BlockBlob").build()
        return try {
            runInterruptible(Dispatchers.IO) {
                blob.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200, 201 -> SupportStep.SENT to null
                        403, 408, 429, 500, 502, 503, 504 -> SupportStep.RETRY to "put_${resp.code}" // 403: the SAS expired, ask again
                        else -> SupportStep.REFUSED to "put_${resp.code}"
                    }
                }
            }
        } catch (_: IOException) {
            SupportStep.RETRY to "io"
        }
    }
}

/**
 * Sends the queued support file (F-SYS-021): Wi-Fi only when `cfg.support.pda_upload_wifi_only`, otherwise any validated
 * network; offline it stays QUEUED with its visible state and goes on reconnect (the job waits for the network).
 */
class SupportUploader(
    private val queue: SupportQueue,
    private val api: SupportApi,
    private val nowMs: () -> Long,
) {
    /** Returns true when a file is still waiting (retry with backoff under the job's network constraint). */
    suspend fun run(onWifi: Boolean, online: Boolean, wifiOnly: Boolean): Boolean {
        val job = queue.current()?.takeIf { it.state == SupportState.QUEUED } ?: return false
        if (!online || (wifiOnly && !onWifi)) return true // still queued: the job comes back when the network does
        val file = withContext(Dispatchers.IO) { queue.read(job.uploadUuid) }
        if (file == null || SupportQueue.sha256(file) != job.sha256) {
            queue.update(job.uploadUuid) { it.copy(state = SupportState.FAILED, lastError = "local_file_damaged") }
            return false
        }
        val (step, code) = api.send(job, file)
        when (step) {
            SupportStep.SENT -> queue.update(job.uploadUuid) { it.copy(state = SupportState.SENT, sentAtMs = nowMs(), lastError = null) }
            SupportStep.REFUSED -> queue.update(job.uploadUuid) { it.copy(state = SupportState.FAILED, attempts = it.attempts + 1, lastError = code) }
            SupportStep.RETRY -> queue.update(job.uploadUuid) {
                // Bounded: after [GIVE_UP_AFTER] failed attempts (days, with WorkManager's backoff capped at 5 h) it stops
                // and shows the failure; the rep can tap Send again to build a fresh file.
                if (it.attempts + 1 >= GIVE_UP_AFTER) it.copy(state = SupportState.FAILED, attempts = it.attempts + 1, lastError = code)
                else it.copy(attempts = it.attempts + 1, lastError = code)
            }
        }
        return step == SupportStep.RETRY && (queue.current()?.state == SupportState.QUEUED)
    }

    companion object {
        /** From this many failed attempts the screen shows the failure (while it keeps retrying). */
        const val SHOW_FAILURE_AFTER = 3
        const val GIVE_UP_AFTER = 30
    }
}

/** The app shell installs this once: the signed-in user's uploader and the Wi-Fi-only rule. */
object SupportRuntime {
    class Wiring(val uploader: suspend () -> SupportUploader?, val wifiOnly: () -> Boolean)
    @Volatile var wiring: Wiring? = null

    const val WORK = "aron-support"

    fun schedule(workManager: WorkManager, wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<SupportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()
        // REPLACE: a new file or a changed network rule supersedes a queued attempt (the queue keeps only the newest file).
        workManager.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }
}

class SupportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val w = SupportRuntime.wiring ?: return Result.success()
        val uploader = w.uploader() ?: return Result.success()
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val onWifi = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        // No attempt cap here: the uploader bounds the attempts per file and then marks it FAILED (visible), so a
        // waiting file is never silently dropped; WorkManager's exponential backoff (max 5 h) keeps this from polling.
        return if (uploader.run(onWifi, online, w.wifiOnly())) Result.retry() else Result.success()
    }
}
