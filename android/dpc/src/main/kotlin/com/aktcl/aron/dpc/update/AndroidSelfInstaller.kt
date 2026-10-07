package com.aktcl.aron.dpc.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import java.io.File

/**
 * Silent self-update as device owner (no unknown-sources prompt): a full-install session for our own package, the
 * verified APK streamed in, committed with a PendingIntent to [UpdateResultReceiver].
 */
class AndroidSelfInstaller(context: Context) : SelfInstaller {
    private val app = context.applicationContext

    override fun install(apk: File, expectedPackage: String): Boolean {
        if (expectedPackage != app.packageName) return false
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
        }
        val id = pi.createSession(params)
        return try {
            pi.openSession(id).use { s ->
                apk.inputStream().use { input -> s.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
                val result = PendingIntent.getBroadcast(
                    app, id, Intent(app, UpdateResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the installer adds the status extras
                )
                s.commit(result.intentSender)
            }
            true
        } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }
            false
        }
    }
}

/**
 * The installer's answer. On success the process is replaced and MY_PACKAGE_REPLACED re-applies the policy (with
 * `no_install_apps` back on, because the lift lives only in memory). On failure the restriction is restored at once.
 */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return // never for a device owner; nothing to show
        val dpc = DeviceOwnerPolicy.get(context)
        dpc.installingOwnUpdate = false
        runCatching { dpc.reapply() }
    }
}
