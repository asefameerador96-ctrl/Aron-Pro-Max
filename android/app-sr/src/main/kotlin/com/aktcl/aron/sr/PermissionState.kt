package com.aktcl.aron.sr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aktcl.aron.feature.home.AppPermission
import com.aktcl.aron.feature.home.PermissionState
import com.aktcl.aron.feature.home.PermissionStatus

/** Runtime permissions of the SR app (F-SR-003): precise location, camera, Bluetooth. Never the microphone. */
object SrPermissions {
    fun manifestNames(p: AppPermission): List<String> = when (p) {
        // Android 12+ ignores a fine-only request: both location permissions are asked together.
        AppPermission.PRECISE_LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        AppPermission.CAMERA -> listOf(Manifest.permission.CAMERA)
        AppPermission.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
    }

    private fun granted(context: Context, p: AppPermission): Boolean = when (p) {
        AppPermission.PRECISE_LOCATION -> ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        else -> manifestNames(p).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Remembers that the system prompt was shown, to tell "never asked" from "blocked for good" without a rationale hint. */
    fun markAsked(context: Context, p: AppPermission) {
        context.getSharedPreferences("aron-permissions", Context.MODE_PRIVATE).edit().putBoolean(p.name, true).apply()
    }

    fun state(activity: Activity): PermissionState {
        val prefs = activity.getSharedPreferences("aron-permissions", Context.MODE_PRIVATE)
        return PermissionState(
            AppPermission.entries.associateWith { p ->
                when {
                    granted(activity, p) -> PermissionStatus.GRANTED
                    !prefs.getBoolean(p.name, false) -> PermissionStatus.NOT_ASKED
                    manifestNames(p).any { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } -> PermissionStatus.DENIED
                    else -> PermissionStatus.DENIED_PERMANENTLY
                }
            },
        )
    }
}
