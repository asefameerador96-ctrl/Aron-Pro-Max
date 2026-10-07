package com.aktcl.aron.core.sync

import com.aktcl.aron.contract.RecordAck
import com.aktcl.aron.contract.SyncBatchResponse
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.DeviceProofSigner
import com.aktcl.aron.core.network.ProofResult
import com.aktcl.aron.core.network.ProofStrings
import com.aktcl.aron.core.network.WireJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Sends one gzip batch body; [SyncBatchApi] in production. */
fun interface BatchSender {
    suspend fun send(token: String, deviceUuid: String, batchUuid: String, gzipBody: ByteArray, headers: BatchHeaders): ApiResult<SyncBatchResponse>
}

/** Telemetry headers of one batch attempt (docs/24 s3.2); they never change the server's outcome. */
data class BatchHeaders(
    val attempt: Int,
    val pendingRows: Int,
    val lastSyncError: String?,
    val deviceTimeIso: String,
    val configVersion: Long?,
)

/**
 * `POST /v1/sync/batch` (docs/24 s3.1 item 4, s4.4): a gzip body, the batch proof of s8.3 over the exact gzip bytes, and
 * the upload grant of the user whose rows these are (CallAuth.Bearer: a switched-away user's rows go under that user's own
 * token, so the client's automatic refresh of the active user's token must not apply here; the engine refreshes).
 */
class SyncBatchApi(private val client: AronApiClient, private val signer: DeviceProofSigner?) : BatchSender {

    override suspend fun send(token: String, deviceUuid: String, batchUuid: String, gzipBody: ByteArray, headers: BatchHeaders): ApiResult<SyncBatchResponse> {
        // A Keystore miss on an enrolled device gets one more try; an unsigned batch is never final (the server rejects
        // it under require_enrolled and the identical batch is resent signed later), unlike an unsigned record.
        val proofString = ProofStrings.batch(deviceUuid, gzipBody, batchUuid, headers.attempt)
        val proof = signer?.let { s -> s.attempt(proofString).let { if (it is ProofResult.Failed) s.attempt(proofString) else it } }
            ?.let { (it as? ProofResult.Signed)?.value }
        return client.call(
            path = PATH,
            auth = CallAuth.Bearer(token),
            build = {
                header("Content-Encoding", "gzip")
                header("X-Batch-Attempt", headers.attempt.toString())
                header("X-Pending-Rows", headers.pendingRows.toString())
                header("X-Device-Time", headers.deviceTimeIso)
                headers.lastSyncError?.let { header("X-Last-Sync-Error", it) }
                headers.configVersion?.let { header("X-Config-Version", it.toString()) }
                if (proof != null) header("X-Device-Proof", proof)
                post(gzipBody.toRequestBody(JSON))
            },
            decode = { body, _ -> WireJson.responses.decodeFromString(SyncBatchResponse.serializer(), body) },
        )
    }

    companion object {
        const val PATH = "/v1/sync/batch"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
