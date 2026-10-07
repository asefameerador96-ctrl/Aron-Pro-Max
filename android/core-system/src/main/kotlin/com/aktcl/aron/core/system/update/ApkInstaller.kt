package com.aktcl.aron.core.system.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/** An APK's signers as SHA-256 hex: several signers, or one signer's rotation history (oldest first). */
data class Signers(val multiple: Boolean, val certs: List<String>)

/** Why a downloaded APK may not be installed. */
enum class ApkCheck { OK, UNREADABLE, WRONG_PACKAGE, WRONG_VERSION, WRONG_SIGNER }

sealed interface InstallStart {
    /** The OS installer was asked; the system shows its own confirmation (a non-enrolled phone). */
    data object Started : InstallStart
    /** "Install unknown apps" is off for this app: show the Bangla guidance and [AndroidUpdater.unknownSourcesIntent]. */
    data object NeedsUnknownSources : InstallStart
    data class Refused(val check: ApkCheck) : InstallStart
    /** A sync batch was still uploading after 60 s: try again in a moment (never install mid-batch). */
    data object SyncBusy : InstallStart
}

/**
 * The Android side of the updater (F-SYS-020): the archive must be this app, the offered version and signed by the same
 * certificate as the installed app and the release record; then a PackageInstaller session hands it to the OS. The
 * local database is not touched: Room's migrations run at the new version's first open and keep every row (docs/24 s5.2).
 */
class AndroidUpdater(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()

    /** This app's "Install unknown apps" page. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installedVersionCode(): Long = packageInfo(pm.getPackageInfo(context.packageName, signingFlags()))!!.let(::versionCodeOf)

    fun verify(apk: File, release: ReleaseInfo): ApkCheck {
        val archive = runCatching { pm.getPackageArchiveInfo(apk.path, signingFlags()) }.getOrNull() ?: return ApkCheck.UNREADABLE
        if (archive.packageName != context.packageName) return ApkCheck.WRONG_PACKAGE
        if (versionCodeOf(archive) != release.versionCode.toLong()) return ApkCheck.WRONG_VERSION
        val installed = signers(pm.getPackageInfo(context.packageName, signingFlags()))
        val offered = signers(archive)
        if (!signerAccepted(installed, offered, release.signingCertSha256)) return ApkCheck.WRONG_SIGNER
        return ApkCheck.OK
    }

    /**
     * Installs [apk] after [verify]. Waits at most 60 s for [syncIdle] to report true so a batch upload is never cut by
     * the app being replaced (docs/17 s10.2).
     */
    suspend fun install(apk: File, release: ReleaseInfo, syncIdle: Flow<Boolean>): InstallStart {
        if (!canInstall()) return InstallStart.NeedsUnknownSources
        // Belt and braces right before the OS sees it: the exact bytes of the release, and never the same or an older build.
        if (!apk.isFile || apk.length() != release.sizeBytes || ApkDownloader.sha256(apk) != release.sha256) return InstallStart.Refused(ApkCheck.UNREADABLE)
        if (release.versionCode.toLong() <= installedVersionCode()) return InstallStart.Refused(ApkCheck.WRONG_VERSION)
        val check = verify(apk, release)
        if (check != ApkCheck.OK) return InstallStart.Refused(check)
        withTimeoutOrNull(SYNC_WAIT_MS) { syncIdle.first { it } } ?: return InstallStart.SyncBusy
        withContext(Dispatchers.IO) {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("aron.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val intent = Intent(context, InstallStatusReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        }
        return InstallStart.Started
    }

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Signers {
        fun digest(sig: android.content.pm.Signature) = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return Signers(false, emptyList())
            return if (si.hasMultipleSigners()) Signers(true, si.apkContentsSigners.orEmpty().map(::digest))
            else Signers(false, si.signingCertificateHistory.orEmpty().map(::digest)) // oldest first, current last
        }
        val sigs = info.signatures.orEmpty().map(::digest)
        return Signers(sigs.size > 1, sigs)
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun packageInfo(info: PackageInfo?) = info

    companion object {
        const val SYNC_WAIT_MS = 60_000L

        /**
         * The signer rule. Several signers: the exact same set as the installed app, and the release record names one.
         * One signer with a rotation history (oldest first, current last): the installed app's current signer must be in
         * the offered history (a planned key rotation stays installable), and the offered current signer must be the one
         * the release record names.
         */
        fun signerAccepted(installed: Signers, offered: Signers, releaseCertSha256: String): Boolean {
            if (installed.certs.isEmpty() || offered.certs.isEmpty()) return false
            val release = releaseCertSha256.lowercase()
            if (installed.multiple || offered.multiple) {
                return installed.multiple == offered.multiple && installed.certs.toSet() == offered.certs.toSet() && release in offered.certs
            }
            return installed.certs.last() in offered.certs && offered.certs.last() == release
        }
    }
}

/** Receives the installer's status; when the OS needs the user's confirmation, it opens the system dialog. Not exported. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
            ?: return
        // Only the system installer's own confirmation activity is started (intent-redirection guard).
        val target = confirm.resolveActivity(context.packageManager) ?: return
        val info = runCatching { context.packageManager.getApplicationInfo(target.packageName, 0) }.getOrNull() ?: return
        if (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) return
        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
