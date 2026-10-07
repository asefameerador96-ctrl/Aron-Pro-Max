package com.aktcl.aron.dpc.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Contract `UpdateCheck` / `AppRelease` (GET /v1/app/update-check). REQUEST: docs/requests/android-core-contract-dtos.md
// (no shared DTO yet); ManagedUpdateTest checks the members against contract/openapi.yaml.

@Serializable
data class AppReleaseDto(
    @SerialName("release_id") val releaseId: Long,
    val flavour: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Long,
    val abi: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("signing_cert_sha256") val signingCertSha256: String,
    val status: String,
    @SerialName("rollout_pct") val rolloutPct: Int,
    @SerialName("notes_en") val notesEn: String? = null,
    @SerialName("notes_bn") val notesBn: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val version: Int,
)

@Serializable
data class UpdateCheckDto(
    @SerialName("update_available") val updateAvailable: Boolean,
    val blocked: Boolean,
    @SerialName("min_version_code") val minVersionCode: Long,
    val latest: AppReleaseDto? = null,
    @SerialName("prompt_policy") val promptPolicy: String? = null,
    @SerialName("wifi_only") val wifiOnly: Boolean? = null,
)

/** What this phone runs. */
data class InstalledApp(val packageName: String, val flavour: String, val versionCode: Long, val abi: String, val signingCertSha256: String)

sealed interface UpdatePlan {
    data object NoUpdate : UpdatePlan
    data class Refused(val reason: String) : UpdatePlan
    data class Install(val release: AppReleaseDto, val wifiOnly: Boolean) : UpdatePlan
}

/**
 * Decides whether the published release may be installed (N-034): a newer version of THIS flavour and ABI, signed by
 * the same certificate (a different signer could never update in place and must never be tried), a well-formed SHA-256,
 * a plausible size and an https URL.
 */
object UpdatePlanner {
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    const val MAX_BYTES = 104_857_600L

    fun plan(check: UpdateCheckDto, me: InstalledApp): UpdatePlan {
        val r = check.latest ?: return UpdatePlan.NoUpdate
        if (!check.updateAvailable) return UpdatePlan.NoUpdate
        if (r.status != "published") return UpdatePlan.Refused("not_published")
        if (r.flavour != me.flavour) return UpdatePlan.Refused("flavour_mismatch")
        if (r.abi != "universal" && r.abi != me.abi) return UpdatePlan.Refused("abi_mismatch")
        if (r.versionCode <= me.versionCode) return UpdatePlan.NoUpdate // never a downgrade
        if (!HEX64.matches(r.sha256)) return UpdatePlan.Refused("checksum_malformed")
        if (r.signingCertSha256.lowercase() != me.signingCertSha256.lowercase()) return UpdatePlan.Refused("signer_mismatch")
        if (r.sizeBytes !in 1..MAX_BYTES) return UpdatePlan.Refused("size_invalid")
        if (!r.downloadUrl.startsWith("https://")) return UpdatePlan.Refused("url_not_https")
        return UpdatePlan.Install(r, check.wifiOnly ?: true)
    }
}

/** Streams the APK to [target], stopping past [maxBytes]; the app wires it to OkHttp. Returns false on any failure. */
fun interface ApkDownloader {
    suspend fun download(url: String, target: File, maxBytes: Long): Boolean
}

/** Writes the APK into a package-installer session and commits it (Android: [AndroidSelfInstaller]). */
fun interface SelfInstaller {
    /**
     * Streams [apk] into a session, hashing exactly the bytes written; commits only when they hash to [expectedSha256],
     * else abandons. Returns false when nothing was committed. The outcome arrives later (UpdateResultReceiver).
     */
    fun install(apk: File, expectedPackage: String, expectedSha256: String): Boolean
}

sealed interface UpdateOutcome {
    data object NothingToDo : UpdateOutcome
    data class Deferred(val reason: String) : UpdateOutcome
    data class Refused(val reason: String) : UpdateOutcome
    data class Committed(val versionCode: Long) : UpdateOutcome
    /** `prompt_policy` prompt / force_after_date: verified and ready; the app asks the rep, then calls run(accepted = true). */
    data class AwaitingUser(val versionCode: Long, val notesBn: String?, val notesEn: String?) : UpdateOutcome
}

/**
 * The managed update (docs/24 s10.2 D24-36): plan, wait for Wi-Fi when required and for a quiet moment (no open sale,
 * no batch in flight: [canInstallNow]), download, verify size and SHA-256 BEFORE anything is installed, then lift
 * `no_install_apps` for our own install only and commit a silent device-owner install. Pending rows survive: the update
 * replaces the code in place, the databases stay. A wrong checksum deletes the file and refuses.
 */
class ManagedUpdater(
    private val dir: File,
    private val downloader: ApkDownloader,
    private val installer: SelfInstaller,
    private val unmetered: () -> Boolean,
    private val canInstallNow: () -> Boolean,
    private val liftInstallRestriction: (Boolean) -> Unit,
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /**
     * One run at a time. [userAccepted]: the rep tapped "update" after an [UpdateOutcome.AwaitingUser]. A blocked
     * installed build (`check.blocked`) installs without waiting for the rep.
     */
    suspend fun run(check: UpdateCheckDto, me: InstalledApp, userAccepted: Boolean = false): UpdateOutcome = mutex.withLock {
        runLocked(check, me, userAccepted)
    }

    private suspend fun runLocked(check: UpdateCheckDto, me: InstalledApp, userAccepted: Boolean): UpdateOutcome {
        val plan = UpdatePlanner.plan(check, me)
        val release = when (plan) {
            // Nothing to install: any downloaded APK (up to 100 MB) is no longer needed on a 2 GB phone.
            UpdatePlan.NoUpdate -> { dir.listFiles()?.forEach { it.delete() }; return UpdateOutcome.NothingToDo }
            is UpdatePlan.Refused -> { dir.listFiles()?.forEach { it.delete() }; return UpdateOutcome.Refused(plan.reason) }
            is UpdatePlan.Install -> {
                if (plan.wifiOnly && !unmetered()) return UpdateOutcome.Deferred("waiting_for_wifi")
                plan.release
            }
        }
        dir.mkdirs()
        dir.listFiles()?.filter { it.name != "${release.versionCode}.apk" }?.forEach { it.delete() } // stale downloads
        val apk = File(dir, "${release.versionCode}.apk")
        if (!(apk.isFile && verify(apk, release))) {
            apk.delete()
            val ok = try { downloader.download(release.downloadUrl, apk, release.sizeBytes) } catch (e: Exception) { false }
            if (!ok) { apk.delete(); return UpdateOutcome.Deferred("download_failed") }
            if (!verify(apk, release)) { apk.delete(); return UpdateOutcome.Refused(CHECKSUM_MISMATCH) }
        }
        val needsRep = check.promptPolicy != "silent" && !check.blocked
        if (needsRep && !userAccepted) return UpdateOutcome.AwaitingUser(release.versionCode, release.notesBn, release.notesEn)
        if (!canInstallNow()) return UpdateOutcome.Deferred("busy") // the verified file is kept for the next try
        liftInstallRestriction(true)
        val committed = try { installer.install(apk, me.packageName, release.sha256) } catch (e: Exception) { false }
        if (!committed) { liftInstallRestriction(false); return UpdateOutcome.Deferred("install_session_failed") }
        return UpdateOutcome.Committed(release.versionCode)
    }

    companion object {
        const val CHECKSUM_MISMATCH = "checksum_mismatch"

        fun verify(apk: File, r: AppReleaseDto): Boolean =
            apk.length() == r.sizeBytes && sha256Hex(apk.inputStream()) == r.sha256

        fun sha256Hex(input: InputStream): String = input.use { s ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
