package com.aktcl.aron.core.system.permission

/** The runtime permissions a field app asks for. The microphone is deliberately absent (F-SYS-023). */
enum class RuntimePermission {
    /** Precise location (ACCESS_FINE_LOCATION together with COARSE on Android 12+), while in use only. */
    LOCATION,
    CAMERA,
    /** BLUETOOTH_CONNECT on Android 12+; install-time BLUETOOTH below that, so always granted there. */
    BLUETOOTH,
}

enum class PermissionStatus {
    GRANTED,
    NOT_ASKED,
    /** Denied once; the system prompt can still be shown. */
    DENIED,
    /** Denied with "don't ask again" (or pinned by policy): only the app's system settings page can grant it. */
    DENIED_PERMANENTLY,
}

/** What a feature needs before it can start. Each feature needs exactly one permission, so one denial blocks only its own features. */
enum class GatedFeature(val needs: RuntimePermission) {
    ATTENDANCE(RuntimePermission.LOCATION),
    SALE(RuntimePermission.LOCATION),
    OUTLET_REQUEST(RuntimePermission.LOCATION),
    PHOTO(RuntimePermission.CAMERA),
    PRINT(RuntimePermission.BLUETOOTH),
}

/** What the blocked screen offers. */
enum class GateAction {
    /** Show the system prompt again. */
    ASK_AGAIN,
    /** Open this app's page in the system Settings (the prompt can no longer be shown). */
    OPEN_APP_SETTINGS,
    /** The permission is granted but the phone's location switch is off: open the system location settings. */
    OPEN_LOCATION_SETTINGS,
}

sealed interface GateDecision {
    data object Allowed : GateDecision

    data class Blocked(val feature: GatedFeature, val permission: RuntimePermission, val action: GateAction) : GateDecision
}

/** A snapshot of the phone's permission state, read when a gated screen opens and again on every resume. */
data class PermissionSnapshot(
    val statuses: Map<RuntimePermission, PermissionStatus>,
    /** The system location switch; null when unknown (treated as on: core-geo reports location_off on the fix itself). */
    val locationServicesOn: Boolean? = null,
    /** Approximate location granted without precise: still a denial for the geofence, explained as such. */
    val coarseOnly: Boolean = false,
) {
    fun status(p: RuntimePermission): PermissionStatus = statuses[p] ?: PermissionStatus.NOT_ASKED

    fun granted(p: RuntimePermission): Boolean = status(p) == PermissionStatus.GRANTED

    companion object {
        fun allGranted() = PermissionSnapshot(RuntimePermission.entries.associateWith { PermissionStatus.GRANTED }, locationServicesOn = true)
    }
}

/**
 * The runtime-permission gate (F-SYS-023, docs/24 s5.5 and s11.2). Pure: the Android adapter builds the snapshot, the
 * screen shows the rationale for a [GateDecision.Blocked]. Location blocks Attendance, Sale and outlet requests; camera
 * blocks only photo capture; Bluetooth blocks only printing (a sale still saves and the memo can be printed later).
 */
object PermissionPolicy {
    fun decide(feature: GatedFeature, snapshot: PermissionSnapshot): GateDecision {
        val p = feature.needs
        return when (snapshot.status(p)) {
            PermissionStatus.GRANTED ->
                if (p == RuntimePermission.LOCATION && snapshot.locationServicesOn == false) {
                    GateDecision.Blocked(feature, p, GateAction.OPEN_LOCATION_SETTINGS)
                } else {
                    GateDecision.Allowed
                }
            PermissionStatus.NOT_ASKED, PermissionStatus.DENIED -> GateDecision.Blocked(feature, p, GateAction.ASK_AGAIN)
            PermissionStatus.DENIED_PERMANENTLY -> GateDecision.Blocked(feature, p, GateAction.OPEN_APP_SETTINGS)
        }
    }

    fun allowed(feature: GatedFeature, snapshot: PermissionSnapshot): Boolean = decide(feature, snapshot) == GateDecision.Allowed

    /** Features a denial of [p] switches off: everything else keeps working. */
    fun featuresBlockedBy(p: RuntimePermission): Set<GatedFeature> = GatedFeature.entries.filter { it.needs == p }.toSet()
}
