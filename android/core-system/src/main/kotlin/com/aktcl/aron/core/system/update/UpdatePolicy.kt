package com.aktcl.aron.core.system.update

/** `AppRelease` of the contract, the members the phone uses. */
data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val abi: String,
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val signingCertSha256: String,
    val notesEn: String? = null,
    val notesBn: String? = null,
)

/** `UpdateCheck` of the contract. */
data class UpdateInfo(
    val updateAvailable: Boolean,
    val blocked: Boolean,
    val minVersionCode: Int,
    val latest: ReleaseInfo?,
    /** silent, prompt or force_after_date (default prompt). */
    val promptPolicy: String = "prompt",
    val wifiOnly: Boolean = true,
)

/** What the app does with the answer. */
sealed interface UpdateState {
    /** Nothing to do. */
    data object Current : UpdateState

    /** A newer release exists. [prompt] false = silent (shown only in Settings). */
    data class Available(val release: ReleaseInfo, val prompt: Boolean, val wifiOnly: Boolean) : UpdateState

    /**
     * This build is below the minimum: a NEW day cannot start (login of a new business date), an open offline day finishes
     * (`cfg.release.finish_offline_day_before_force`). Upload always continues. [release] is what to install.
     */
    data class Required(val release: ReleaseInfo?, val wifiOnly: Boolean) : UpdateState
}

/** The day gate's answer. */
enum class DayGate { OPEN, FINISH_OPEN_DAY_ONLY, BLOCKED_UPDATE_REQUIRED }

/**
 * The updater's rules (F-SYS-020, docs/17 s10.2, docs/24 s3.7, D24-22). Pure: no Android, no network.
 * - Versions compare as integer version codes per flavour.
 * - `min_version_code` blocks a new day only; an open offline day finishes; neither blocks upload or wipes data.
 * - `blocked` (the installed code is in `blocked_version_codes`) stops new captures only; that gate is android-core's
 *   (`SessionState.Active.updateRequired`); it is not evaluated here. `force_after_date` is shown as a prompt.
 */
object UpdatePolicy {
    fun evaluate(installedCode: Int, info: UpdateInfo, supportedAbis: List<String>): UpdateState {
        val release = info.latest?.takeIf { it.versionCode > installedCode && abiFits(it.abi, supportedAbis) }
        // A required update must itself reach the minimum, or installing it would leave the day blocked: then there is
        // nothing to install (the screen says to contact the supervisor).
        if (installedCode < info.minVersionCode) return UpdateState.Required(release?.takeIf { it.versionCode >= info.minVersionCode }, info.wifiOnly)
        if (!info.updateAvailable || release == null) return UpdateState.Current
        return UpdateState.Available(release, prompt = info.promptPolicy != "silent", wifiOnly = info.wifiOnly)
    }

    /**
     * May the rep start (or keep) a day? [dayOpen] is true when today's business day has already started on this phone
     * (bundle applied and a day record exists) and is not yet submitted.
     */
    fun dayGate(installedCode: Int, minVersionCode: Int, dayOpen: Boolean, finishOfflineDayBeforeForce: Boolean = true): DayGate = when {
        installedCode >= minVersionCode -> DayGate.OPEN
        dayOpen && finishOfflineDayBeforeForce -> DayGate.FINISH_OPEN_DAY_ONLY
        else -> DayGate.BLOCKED_UPDATE_REQUIRED
    }

    /** The release's ABI must be one this phone runs; `universal` fits every phone. */
    fun abiFits(abi: String, supportedAbis: List<String>) = abi == "universal" || abi in supportedAbis

    /** The ABI to ask for: the phone's first supported one the release channel builds (docs/24 s5.7 splits). */
    fun preferredAbi(supportedAbis: List<String>): String = supportedAbis.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "universal"

    /** May the download start on this network? (`cfg.release.update_wifi_only`; never forced on mobile data.) */
    fun mayDownload(wifiOnly: Boolean, onWifi: Boolean): Boolean = onWifi || !wifiOnly

    /** At login always; on foreground at most once per [intervalMs] (12 h). */
    fun shouldCheck(lastCheckMs: Long?, nowMs: Long, atLogin: Boolean, intervalMs: Long = 12 * 3_600_000L): Boolean =
        atLogin || lastCheckMs == null || nowMs - lastCheckMs >= intervalMs || nowMs < lastCheckMs
}
