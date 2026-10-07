package com.aktcl.aron.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// REQUEST: (docs/requests/android-core-contract-dtos.md) these mirror contract schemas and move to shared:contract with the
// same names when the shared lane publishes them. The request side is assembled as a JsonObject in [BatchBody] so the
// stored record JSON is embedded as it was committed; only the response is decoded here, leniently (docs/24 s3.1 item 2).

/** `TimeAnchor` (contract): one server-time observation tied to a boot and its elapsed realtime. */
@Serializable
data class TimeAnchorDto(
    @SerialName("boot_count") val bootCount: Int,
    @SerialName("server_time") val serverTime: String,
    @SerialName("elapsed_ms") val elapsedMs: Long,
)

/** `RecordAck` (contract). Status and code stay strings so a value this build does not know is still handled. */
@Serializable
data class RecordAckDto(
    @SerialName("client_uuid") val clientUuid: String,
    val type: String,
    val status: String,
    val code: String? = null,
    val retryable: Boolean? = null,
    @SerialName("message_key") val messageKey: String? = null,
    @SerialName("server_id") val serverId: Long? = null,
)

/** `Resolution` (contract): a server decision on a quarantined record. */
@Serializable
data class ResolutionDto(
    @SerialName("client_uuid") val clientUuid: String,
    val type: String,
    val resolution: String,
    @SerialName("resolved_at") val resolvedAt: String,
)

@Serializable
data class AckSummaryDto(val accepted: Int = 0, val duplicate: Int = 0, val rejected: Int = 0, val quarantined: Int = 0)

/** `SyncBatchResponse` (contract). Members the engine does not interpret yet are kept as raw JSON for later rows. */
@Serializable
data class SyncBatchResponseDto(
    @SerialName("batch_uuid") val batchUuid: String,
    val replayed: Boolean,
    @SerialName("received_at") val receivedAt: String,
    val acks: List<RecordAckDto>,
    val summary: AckSummaryDto = AckSummaryDto(),
    @SerialName("server_totals") val serverTotals: List<JsonElement> = emptyList(),
    @SerialName("day_states") val dayStates: List<JsonElement> = emptyList(),
    val resolutions: List<ResolutionDto> = emptyList(),
    @SerialName("hold_s") val holdS: Int = 0,
    @SerialName("config_version") val configVersion: Long? = null,
    @SerialName("bundle_version_current") val bundleVersionCurrent: String? = null,
    val generation: String? = null,
)
