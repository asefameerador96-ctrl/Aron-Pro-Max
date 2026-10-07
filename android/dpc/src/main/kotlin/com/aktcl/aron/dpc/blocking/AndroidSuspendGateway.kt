package com.aktcl.aron.dpc.blocking

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.aktcl.aron.dpc.AronDeviceAdminReceiver

class AndroidSuspendGateway(context: Context) : SuspendGateway {
    private val app = context.applicationContext
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(app, AronDeviceAdminReceiver::class.java)

    override val ownPackage: String get() = app.packageName
    override fun isDeviceOwner(): Boolean = dpm?.isDeviceOwnerApp(app.packageName) == true

    override fun setSuspended(packages: List<String>, suspended: Boolean): List<String> =
        dpm.setPackagesSuspended(admin, packages.toTypedArray(), suspended).toList()

    @Suppress("DEPRECATION")
    override fun installed(): List<InstalledApp> {
        val pm = app.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        return pm.getInstalledApplications(PackageManager.GET_META_DATA).map {
            InstalledApp(it.packageName, it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0, it.packageName in launchable)
        }
    }
}
