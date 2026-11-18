package com.aktcl.aron.dpc.release

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import com.aktcl.aron.dpc.AronDeviceAdminReceiver

/** [OwnerGateway] over DevicePolicyManager. Every call needs this app to be the device owner; the caller checks first. */
class AndroidOwnerGateway(
    context: Context,
    private val dpm: DevicePolicyManager? = context.applicationContext.getSystemService(DevicePolicyManager::class.java),
) : OwnerGateway {
    private val app = context.applicationContext
    private val admin = ComponentName(app, AronDeviceAdminReceiver::class.java)

    override fun isDeviceOwner(): Boolean = dpm?.isDeviceOwnerApp(app.packageName) == true

    @Suppress("DEPRECATION")
    override fun visiblePackages(): List<String> =
        app.packageManager.getInstalledApplications(0).map { it.packageName }.filter { it != app.packageName }

    override fun unsuspend(packages: List<String>): List<String> =
        if (packages.isEmpty()) emptyList() else dpm!!.setPackagesSuspended(admin, packages.toTypedArray(), false).toList()

    override fun stillSuspended(pkg: String): Boolean = try {
        dpm!!.isPackageSuspended(admin, pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override fun clearLockTask() { dpm!!.setLockTaskPackages(admin, emptyArray()) }

    override fun clearRestrictions() {
        // Only restrictions are cleared. The phone's automatic date and time stays as it is (ON): the old policy forced it ON,
        // and switching it OFF here would make every later capture report auto time off to the risk rules.
        for (k in RESTRICTIONS) runCatching { dpm!!.clearUserRestriction(admin, k) }
    }

    override fun turnAdbOff() { dpm!!.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "0") }

    override fun unblockUninstallAndUserControl() {
        runCatching { dpm!!.setUninstallBlocked(admin, app.packageName, false) }
        runCatching { if (Build.VERSION.SDK_INT >= 30) dpm!!.setUserControlDisabledPackages(admin, emptyList()) }
    }

    @Suppress("DEPRECATION")
    override fun resetPermissionPins() {
        val requested = runCatching { app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions }.getOrNull().orEmpty()
        for (p in requested) runCatching { dpm!!.setPermissionGrantState(admin, app.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) }
    }

    @Suppress("DEPRECATION")
    override fun clearDeviceOwner() { dpm!!.clearDeviceOwnerApp(app.packageName) }

    private companion object {
        // Every restriction the old policy code (policy and kiosk) could set; clearing one that is not set is harmless.
        val RESTRICTIONS = listOf(
            "no_debugging_features", "no_install_unknown_sources", "no_install_unknown_sources_globally", "no_install_apps",
            "no_factory_reset", "no_safe_boot", "no_add_user", "no_config_date_time", "no_usb_file_transfer", "no_config_location",
        )
    }
}

/** Builds the release for the apps: `DeviceOwnerReleaseRunner.run(context)` from `Application.onCreate`, off the main thread. */
object DeviceOwnerReleaseRunner {
    fun run(context: Context): DeviceOwnerRelease.Outcome {
        val app = context.applicationContext
        // Debuggable (lab) builds keep USB debugging; release builds undo what the old policy forced.
        val debuggable = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return DeviceOwnerRelease(AndroidOwnerGateway(app), java.io.File(app.noBackupFilesDir, "dpc"), restoreAdb = !debuggable).run()
    }
}
