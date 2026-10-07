package com.aktcl.aron.core.geo

// The phone side of the contract `GeoFix` (docs/24 s11.1). Wire values match contract/openapi.yaml exactly;
// FixModelContractTest checks every enum against the YAML.

/** Contract `FixPurpose`. */
enum class FixPurpose(val wire: String) {
    ATTENDANCE_IN("attendance_in"), ATTENDANCE_OUT("attendance_out"), VISIT_OPEN("visit_open"), FORCE_SALE("force_sale"),
    OUTLET_CAPTURE("outlet_capture"), OUTLET_VERIFICATION("outlet_verification"), MEMO_EDIT("memo_edit"),
    MEMO_VOID("memo_void"), DUE_COLLECTION("due_collection"), REFRESH("refresh"), BREADCRUMB("breadcrumb"),
    GIFT_PHOTO("gift_photo"), REDEMPTION("redemption"),
}

/** Contract `GeoFix.fix_status`. */
enum class FixStatus(val wire: String) {
    OK("ok"), TIMEOUT("timeout"), PERMISSION_DENIED("permission_denied"), LOCATION_OFF("location_off"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
}

/** Contract `LocationProvider`. */
enum class FixProvider(val wire: String) {
    FUSED("fused"), GPS("gps"), NETWORK("network"), PASSIVE("passive"), UNKNOWN("unknown");

    companion object {
        /** Maps `Location.getProvider()`; anything else (null, a test provider name) is [UNKNOWN]. */
        fun of(androidProvider: String?): FixProvider = entries.firstOrNull { it.wire == androidProvider } ?: UNKNOWN
    }
}

/** Contract `GeoFix.request_priority`, chosen by `cfg.geo.fix_accuracy_mode` (`balanced` default, `high`). */
enum class FixPriority(val wire: String) {
    BALANCED("balanced"), HIGH_ACCURACY("high_accuracy");

    companion object {
        /** `cfg.geo.fix_accuracy_mode` value to priority; unknown values fall back to the battery-friendly default. */
        fun ofConfig(mode: String?): FixPriority = if (mode == "high") HIGH_ACCURACY else BALANCED
    }
}

/** Contract `FixDeviceState`: integrity facts read at the moment of the fix. */
data class FixDeviceState(
    val deviceOwner: Boolean,
    val devOptionsEnabled: Boolean,
    val adbEnabled: Boolean,
    val autoTimeEnabled: Boolean,
    val mockAppPresent: Boolean,
    val integrityRef: String? = null,
)

/**
 * One location as the provider returned it. [elapsedRealtimeNanos] is `Location.getElapsedRealtimeNanos()`; [timeMs] is
 * `Location.getTime()` (GNSS-derived when the provider is `gps`); [isMock] is `isMock()` on API 31+, `isFromMockProvider()`
 * below.
 */
data class RawLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double?,
    val altitudeM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val provider: String?,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val isMock: Boolean,
)

/**
 * A fix ready to be stored as `geo_fix` and embedded in its record: every member of the contract `GeoFix` except the
 * client UUID and owner, which the capture assigns. [isMock] is always set; it is false only when the provider said so
 * or when there was no location at all.
 */
data class TakenFix(
    val purpose: FixPurpose,
    val fixStatus: FixStatus,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val verticalAccuracyM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val provider: FixProvider,
    /** `fix_time` as an ISO-8601 UTC instant with milliseconds, or null without a location. */
    val fixTime: String?,
    val fixElapsedRealtimeMs: Long?,
    val fixAgeMs: Long?,
    val timeToFixMs: Long?,
    val requestPriority: FixPriority,
    val isMock: Boolean,
    val reused: Boolean,
    val refreshCount: Int,
    /** `GnssSummary` JSON collected during the fix window (N-025), or null. */
    val gnssJson: String?,
    val device: FixDeviceState,
) {
    val isOk: Boolean get() = fixStatus == FixStatus.OK
}
