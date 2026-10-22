package com.aktcl.aron.dpc.mdm

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import com.aktcl.aron.dpc.R
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.aktcl.aron.dpc.AronDeviceAdminReceiver
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** DevicePolicyManager implementation of [CommandGateway] and [KioskGateway]. */
class AndroidMdmGateway(context: Context) : CommandGateway, KioskGateway {
    private val app = context.applicationContext
    private val dpm = app.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(app, AronDeviceAdminReceiver::class.java)

    override fun isDeviceOwner() = dpm?.isDeviceOwnerApp(app.packageName) == true

    override fun lockNow(message: String?) {
        if (!message.isNullOrBlank() && Build.VERSION.SDK_INT >= 24) dpm.setDeviceOwnerLockScreenInfo(admin, message.take(300))
        dpm.lockNow()
    }

    override fun clearLockMessage() {
        if (Build.VERSION.SDK_INT >= 24) dpm.setDeviceOwnerLockScreenInfo(admin, null)
    }

    override fun showMessage(textBn: String?, textEn: String?) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.mdm_notice_channel), NotificationManager.IMPORTANCE_HIGH))
        val text = if (Locale.getDefault().language == "bn") textBn ?: textEn else textEn ?: textBn
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(app, CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(app)
        nm.notify(NOTIFICATION_ID, b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(app.getString(R.string.mdm_notice_title)).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }

    /** One balanced-power fix: the network/fused provider first, GPS only when the network has none; 20 s cap, no updates kept. */
    @Suppress("MissingPermission")
    override fun locateOnce(): CommandGateway.Fix? {
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) {
            val loc: Location? = if (Build.VERSION.SDK_INT >= 30) {
                val latch = CountDownLatch(1); var got: Location? = null
                lm.getCurrentLocation(p, null, app.mainExecutor) { got = it; latch.countDown() }
                latch.await(20, TimeUnit.SECONDS); got
            } else lm.getLastKnownLocation(p)
            if (loc != null) return CommandGateway.Fix(loc.latitude, loc.longitude, loc.accuracy.toDouble(), loc.time)
        }
        return null
    }

    override fun reboot() { dpm.reboot(admin) }

    override fun liftFactoryResetRestriction() { dpm.clearUserRestriction(admin, "no_factory_reset") }

    override fun wipe() { dpm.wipeData(0) }

    override fun setLockTaskPackages(packages: List<String>) { dpm.setLockTaskPackages(admin, packages.distinct().toTypedArray()) }

    override fun startLockTask(pkg: String) {
        val intent = app.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        val opts = if (Build.VERSION.SDK_INT >= 26) ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle() else null
        app.startActivity(intent, opts)
    }

    override fun clearLockTask() { dpm.setLockTaskPackages(admin, emptyArray()) }

    private companion object {
        const val CHANNEL = "aron_mdm"
        const val NOTIFICATION_ID = 7301
    }
}

/** Wakes the MDM applier at the next block-window boundary (inexact, like [com.aktcl.aron.dpc.blocking.HardEndAlarm]). */
object MdmAlarm {
    fun schedule(context: Context, atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 1, Intent(context, MdmWindowReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (atMs != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }
}

class MdmWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread { try { runCatching { DeviceOwnerPolicy.get(context).reevaluateBlocking() } } finally { pending.finish() } }.start()
    }
}
