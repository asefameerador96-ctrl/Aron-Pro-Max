package com.aktcl.aron.dpc.blocking

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
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
            // An updated system app (Play Store, Chrome, the OEM clock) keeps FLAG_SYSTEM: it is still a system app.
            InstalledApp(it.packageName, it.flags and ApplicationInfo.FLAG_SYSTEM != 0, it.packageName in launchable)
        }
    }

    /** Resolved on every call (a rep may change the default dialler or keyboard); a failure throws so the caller fails open. */
    override fun protectedPackages(): Set<String> {
        val pm = app.packageManager
        fun handlers(i: Intent) = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
        val diallers = handlers(Intent(Intent.ACTION_DIAL)) + handlers(Intent(Intent.ACTION_DIAL, Uri.parse("tel:999"))) +
            handlers(Intent(Intent.ACTION_CALL_BUTTON)) + listOfNotNull(app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage)
        // A phone that can call but shows no dialler is a resolver we cannot trust: block nothing rather than guess.
        check(diallers.isNotEmpty() || !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) { "no dialler resolved" }
        val out = diallers.toMutableSet()
        out += handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        out += handlers(Intent(Settings.ACTION_SETTINGS))
        out += handlers(Intent(Settings.ACTION_WIFI_SETTINGS))
        out += handlers(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        out += handlers(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        Telephony.Sms.getDefaultSmsPackage(app)?.let { out += it }
        app.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { out += it.packageName }
        // Alarms and timers (a hidden alarm notification is a missed alarm), and the WebView provider other apps render with.
        out += handlers(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        out += handlers(Intent(AlarmClock.ACTION_SET_TIMER))
        WebView.getCurrentWebViewPackage()?.packageName?.let { out += it }
        return out
    }
}
