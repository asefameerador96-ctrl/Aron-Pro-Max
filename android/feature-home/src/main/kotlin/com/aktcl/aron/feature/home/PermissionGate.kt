package com.aktcl.aron.feature.home

/** The runtime permissions the SR app asks for at first run (F-SR-003). The microphone is deliberately absent. */
enum class AppPermission(val manifestName: String) {
    PRECISE_LOCATION("android.permission.ACCESS_FINE_LOCATION"),
    CAMERA("android.permission.CAMERA"),
    BLUETOOTH("android.permission.BLUETOOTH_CONNECT"),

    /** Optional (never a gate): lets a task push show on a phone that is not device-owner enrolled (N-038). Android 13+ only. */
    NOTIFICATIONS("android.permission.POST_NOTIFICATIONS"),
}

enum class PermissionStatus { GRANTED, DENIED, DENIED_PERMANENTLY, NOT_ASKED }

data class PermissionState(val statuses: Map<AppPermission, PermissionStatus>) {
    /** Location is mandatory for the visit screen (docs/24 s11.2); camera and Bluetooth gate their own screens only. */
    val canOpenVisit: Boolean get() = statuses[AppPermission.PRECISE_LOCATION] == PermissionStatus.GRANTED
    val canTakePhoto: Boolean get() = statuses[AppPermission.CAMERA] == PermissionStatus.GRANTED
    val canPrint: Boolean get() = statuses[AppPermission.BLUETOOTH] == PermissionStatus.GRANTED
    /** What the onboarding still has to ask, in order, with Bangla rationale shown before each request. */
    val toAsk: List<AppPermission> get() = AppPermission.entries.filter { (statuses[it] ?: PermissionStatus.NOT_ASKED) == PermissionStatus.NOT_ASKED }
    /** Denied for good: the screen sends the rep to the system settings page. */
    val needsSettings: List<AppPermission> get() = statuses.filterValues { it == PermissionStatus.DENIED_PERMANENTLY }.keys.toList()
}

object PermissionGate {
    fun requested(): List<String> = AppPermission.entries.map { it.manifestName }
    fun initial() = PermissionState(AppPermission.entries.associateWith { PermissionStatus.NOT_ASKED })
}
