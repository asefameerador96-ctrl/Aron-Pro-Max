package com.aktcl.aron.dpc.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import com.aktcl.aron.dpc.AronDeviceAdminReceiver

/** [DpmGateway] over DevicePolicyManager for this app as device owner. */
class AndroidDpmGateway(context: Context) : DpmGateway {
    private val app = context.applicationContext
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(app, AronDeviceAdminReceiver::class.java)

    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val ownPackage: String get() = app.packageName

    override fun isDeviceOwner(): Boolean = dpm?.isDeviceOwnerApp(app.packageName) == true

    override fun setRestriction(androidKey: String, on: Boolean) {
        if (on) dpm.addUserRestriction(admin, androidKey) else dpm.clearUserRestriction(admin, androidKey)
    }

    /** The effective restrictions of the policy's keys (whoever set them), as UserManager reports them. */
    override fun restrictions(): Set<String> {
        val um = app.getSystemService(UserManager::class.java) ?: return emptySet()
        return KNOWN.filter { um.hasUserRestriction(it) }.toSet()
    }

    override fun setGlobalSetting(name: String, value: String) = dpm.setGlobalSetting(admin, name, value)

    @Suppress("DEPRECATION")
    override fun setAutoTimeRequired(required: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) dpm.setAutoTimeEnabled(admin, required)
        else dpm.setAutoTimeRequired(admin, required)
    }

    override fun setLocationEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            dpm.setLocationEnabled(admin, enabled)
        } else {
            @Suppress("DEPRECATION")
            dpm.setSecureSetting(admin, Settings.Secure.LOCATION_MODE, if (enabled) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString() else "0")
        }
    }

    override fun setUninstallBlocked(blocked: Boolean) = dpm.setUninstallBlocked(admin, app.packageName, blocked)
    override fun isUninstallBlocked(): Boolean = dpm.isUninstallBlocked(admin, app.packageName)

    override fun setUserControlDisabled(disabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) dpm.setUserControlDisabledPackages(admin, if (disabled) listOf(app.packageName) else emptyList())
    }

    override fun isIgnoringBatteryOptimizations(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true

    override fun requestsPermission(permission: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

    override fun setPermissionGrantState(permission: String, state: Int): Boolean =
        dpm.setPermissionGrantState(admin, app.packageName, permission, state)

    override fun permissionGrantState(permission: String): Int = dpm.getPermissionGrantState(admin, app.packageName, permission)

    private companion object {
        val KNOWN = listOf(
            "no_debugging_features", "no_install_unknown_sources", "no_install_unknown_sources_globally", "no_install_apps",
            "no_factory_reset", "no_safe_boot", "no_add_user", "no_config_date_time", "no_usb_file_transfer", "no_config_location",
        )
    }
}
