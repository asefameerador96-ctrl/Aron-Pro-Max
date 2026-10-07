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

    override fun install(apk: File, expectedPackage: String, expectedSha256: String): Boolean {
        if (expectedPackage != app.packageName) return false
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // A device owner installs silently; say so explicitly on API 31+ so no confirmation is ever needed.
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        return try {
            pi.openSession(id).use { s ->
                // Hash exactly the bytes written into the session (no window between verifying and installing).
                val md = java.security.MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { input ->
                    s.openWrite("base.apk", 0, apk.length()).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                        s.fsync(out)
                    }
                }
                if (md.digest().joinToString("") { "%02x".format(it) } != expectedSha256) { s.abandon(); return false }
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
        // Whatever the answer, the lift ends here: no_install_apps is back on before anything else happens.
        DeviceOwnerPolicy.get(context).liftForOwnUpdate(false)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Not silent (a dev phone that is not device owner): hand the system's confirmation to the rep.
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
