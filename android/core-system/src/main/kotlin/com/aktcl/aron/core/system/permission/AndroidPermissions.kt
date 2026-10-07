package com.aktcl.aron.core.system.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** The Android side of [PermissionPolicy]: manifest names, the live status and the Settings deep links. */
object AndroidPermissions {
    /** Shared with the SR onboarding (feature-home), which records the same keys, so "asked" means asked anywhere. */
    private const val PREFS = "aron-permissions"

    fun manifestNames(p: RuntimePermission, sdkInt: Int = Build.VERSION.SDK_INT): List<String> = when (p) {
        // Android 12+ ignores a fine-only request: both location permissions are asked together. Never background.
        RuntimePermission.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        RuntimePermission.CAMERA -> listOf(Manifest.permission.CAMERA)
        RuntimePermission.BLUETOOTH -> if (sdkInt >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
    }

    /** The key the onboarding uses (feature-home `AppPermission.name`). */
    internal fun prefKey(p: RuntimePermission) = when (p) {
        RuntimePermission.LOCATION -> "PRECISE_LOCATION"
        RuntimePermission.CAMERA -> "CAMERA"
        RuntimePermission.BLUETOOTH -> "BLUETOOTH"
    }

    fun granted(context: Context, p: RuntimePermission): Boolean = when (p) {
        // Precise location is what the geofence needs; coarse alone is a denial for our purpose.
        RuntimePermission.LOCATION -> isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> manifestNames(p).all { isGranted(context, it) }
    }

    private fun isGranted(context: Context, name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    /** Call after the system prompt returned, so a later denial without a rationale hint reads as "denied for good". */
    fun markAsked(context: Context, p: RuntimePermission) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(prefKey(p), true).apply()
    }

    fun status(activity: Activity, p: RuntimePermission): PermissionStatus {
        val asked = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(prefKey(p), false)
        return when {
            granted(activity, p) -> PermissionStatus.GRANTED
            !asked -> PermissionStatus.NOT_ASKED
            manifestNames(p).any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } -> PermissionStatus.DENIED
            else -> PermissionStatus.DENIED_PERMANENTLY
        }
    }

    fun snapshot(activity: Activity): PermissionSnapshot = PermissionSnapshot(
        RuntimePermission.entries.associateWith { status(activity, it) },
        locationServicesOn = locationOn(activity),
        coarseOnly = coarseOnly(activity),
    )

    /**
     * Without an Activity (a host that is not one) the rationale hint is unknown: a missing grant reads as DENIED, so
     * the gate stays closed and offers the system prompt. Never open by default.
     */
    fun snapshotOf(context: Context): PermissionSnapshot = (context as? Activity)?.let(::snapshot) ?: PermissionSnapshot(
        RuntimePermission.entries.associateWith { if (granted(context, it)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
        locationServicesOn = locationOn(context),
        coarseOnly = coarseOnly(context),
    )

    private fun coarseOnly(context: Context) =
        !isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) && isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun locationOn(context: Context): Boolean? = runCatching {
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.let(LocationManagerCompat::isLocationEnabled)
    }.getOrNull()

    /** The app's own page in the system Settings, where a permanently denied permission is switched back on. */
    fun appSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
