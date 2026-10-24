package com.aktcl.aron.core.sync

import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * D-NIGHT-DAYCLOSE-01, contract 1.9 queue row 4 (shape fixed in docs/requests/contract-v1.9-shapes-backend-core.md on
 * lane/backend-core): the `digest` member of `GET /v1/sync/totals`. LANE-LOCAL PARSER until the generated 1.9 DTOs land
 * (then swap to `SyncTotalsResponse.digest`); a server without the member answers null and the screen keeps the login totals.
 */
data class SubmitDigest(
    /** Per record type, the rows of THIS phone the server holds an outcome for (`device.received`); null without a phone token. */
    val deviceReceived: Map<String, Int>?,
    val rejected: List<Rejected>,
    val truncated: Boolean,
) {
    /** `RejectedRecordView`: a row of this login and date the server did not store. */
    data class Rejected(
        val clientUuid: String,
        val type: String,
        val businessDate: String,
        /** `rejected`, `parked` or `quarantined`. */
        val status: String,
        val code: String,
        val final: Boolean,
        val fromThisDevice: Boolean,
        val memoNo: String?,
        val outletId: Long?,
    )

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The `digest` of a totals answer; null when absent or unreadable (never throws). */
        fun parse(body: String): SubmitDigest? = runCatching {
            val d = (JSON.parseToJsonElement(body) as JsonObject)["digest"] as? JsonObject ?: return null
            val byType = d["by_type"] as? JsonObject
            val device = byType?.let { m ->
                val out = HashMap<String, Int>()
                var any = false
                m.forEach { (t, v) ->
                    val dev = (v as? JsonObject)?.get("device") as? JsonObject
                    if (dev != null) { any = true; out[t] = (dev["received"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
                }
                out.takeIf { any }
            }
            val rejected = (d["rejected"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                Rejected(
                    clientUuid = s("client_uuid") ?: return@mapNotNull null, type = s("type") ?: return@mapNotNull null,
                    businessDate = s("business_date") ?: return@mapNotNull null, status = s("status") ?: return@mapNotNull null,
                    code = s("code") ?: return@mapNotNull null,
                    final = (o["final"] as? JsonPrimitive)?.content == "true",
                    fromThisDevice = (o["from_this_device"] as? JsonPrimitive)?.content == "true",
                    memoNo = s("memo_no"), outletId = (o["outlet_id"] as? JsonPrimitive)?.content?.toLongOrNull(),
                )
            }
            SubmitDigest(device, rejected, (d["rejected_truncated"] as? JsonPrimitive)?.content == "true")
        }.getOrNull()
    }
}

/** `GET /v1/sync/totals?business_date=` read for its `digest` only (the Server column keeps the stored `server_totals`). */
fun interface SubmitDigestApi {
    suspend fun fetch(token: String, businessDate: String): ApiResult<SubmitDigest?>
}

class SyncTotalsDigestApi(private val client: AronApiClient) : SubmitDigestApi {
    override suspend fun fetch(token: String, businessDate: String): ApiResult<SubmitDigest?> = client.call(
        path = PATH,
        auth = CallAuth.Bearer(token),
        build = { url(client.origin.path(PATH).newBuilder().addQueryParameter("business_date", businessDate).build()); get() },
        decode = { body, _ -> SubmitDigest.parse(body) },
    )

    companion object {
        const val PATH = "/v1/sync/totals"
    }
}

/**
 * One digest per user and date for the Sales Submit screen, read when the screen asks (open, Sync data, Submit) and kept
 * for [maxAgeMs] so a submit does not wait on a second round trip. Offline, refused or an older server: null (the screen
 * then compares with the login totals, D-TSO-DAYCLOSE-SCREEN-01). Never throws.
 */
class SubmitDigestSource(
    private val userId: Long,
    private val auth: UploadAuth,
    private val api: SubmitDigestApi,
    private val nowMs: () -> Long,
    private val maxAgeMs: Long = 30_000,
) {
    @Volatile private var cached: Triple<String, Long, SubmitDigest>? = null

    suspend fun digest(businessDate: String): SubmitDigest? {
        cached?.let { (d, at, v) -> if (d == businessDate && nowMs() - at in 0 until maxAgeMs) return v }
        return try {
            val token = auth.token(userId) ?: return null
            (api.fetch(token, businessDate) as? ApiResult.Success)?.value?.also { cached = Triple(businessDate, nowMs(), it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** A source that always answers [digest] (previews, and feature tests without the network module). */
        fun answering(digest: SubmitDigest?): SubmitDigestSource = SubmitDigestSource(
            0,
            object : UploadAuth { override suspend fun token(userId: Long) = "fixed"; override suspend fun refresh(userId: Long, rejected: String?) = false },
            { _, _ -> ApiResult.Success(digest, com.aktcl.aron.core.network.ResponseMeta(200, null, null, null, null, null, null, null)) },
            { 0L },
        )
    }
}
