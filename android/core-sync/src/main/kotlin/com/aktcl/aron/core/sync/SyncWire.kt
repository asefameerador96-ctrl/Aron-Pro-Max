package com.aktcl.aron.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The batch response and its acks are shared:contract's generated `SyncBatchResponse` and `RecordAck` (N-002). These two
// are not generated there yet (the response carries them as JsonElement). REQUEST: docs/requests/android-core-contract-dtos.md.
// The request is assembled as a JsonObject in SyncEngine so the stored record JSON is embedded exactly as it was committed.

/** `TimeAnchor` (contract): one server-time observation tied to a boot and its elapsed realtime. */
@Serializable
data class TimeAnchorDto(
    @SerialName("boot_count") val bootCount: Int,
    @SerialName("server_time") val serverTime: String,
    @SerialName("elapsed_ms") val elapsedMs: Long,
)

/** `Resolution` (contract): a server decision on a quarantined record. */
@Serializable
data class ResolutionDto(
    @SerialName("client_uuid") val clientUuid: String,
    val type: String,
    val resolution: String,
    @SerialName("resolved_at") val resolvedAt: String,
)
