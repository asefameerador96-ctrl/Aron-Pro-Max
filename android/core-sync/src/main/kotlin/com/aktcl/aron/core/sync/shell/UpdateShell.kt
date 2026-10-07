package com.aktcl.aron.core.sync.shell

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aktcl.aron.contract.AppFlavour
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.WorkManagerSyncScheduler
import com.aktcl.aron.core.system.update.AndroidUpdater
import com.aktcl.aron.core.system.update.ApkDownloader
import com.aktcl.aron.core.system.update.DayGate
import com.aktcl.aron.core.system.update.DownloadResult
import com.aktcl.aron.core.system.update.DownloadUi
import com.aktcl.aron.core.system.update.InstallStart
import com.aktcl.aron.core.system.update.NetworkStatus
import com.aktcl.aron.core.system.update.PrefsUpdateMemory
import com.aktcl.aron.core.system.update.ReleaseInfo
import com.aktcl.aron.core.system.update.UpdateApi
import com.aktcl.aron.core.system.update.UpdateManager
import com.aktcl.aron.core.system.update.UpdatePolicy
import com.aktcl.aron.core.system.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app shells' updater (android-sys F-SYS-020; docs/requests/android-sys-app-wiring.md item 4). One per process.
 * [check] after an online login (`atLogin = true`) and on resume (`false`, 12 h throttle inside); the shell shows
 * `UpdateContent` while [prompt] is non-null, and `DayGateBanner(dayGate(...))` before a new day. The download never
 * starts on mobile data when the release is Wi-Fi only; the install waits for a running sync batch (at most 60 s).
 */
class UpdateShell(
    context: Context,
    private val components: SessionComponents,
    /** The release channel of this app. */
    flavour: AppFlavour,
    versionCode: Int,
) {
    private val app = context.applicationContext
    private val manager = UpdateManager(
        UpdateApi(components.apiClient)::check, PrefsUpdateMemory(app), flavour.wire, versionCode, Build.SUPPORTED_ABIS.toList(), components.clock::nowMs,
    )
    private val downloader by lazy { ApkDownloader(components.okHttp, File(app.filesDir, "updates"), nowMs = components.clock::nowMs) }
    private val updater by lazy { AndroidUpdater(app) }
    private val lock = Mutex()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Current)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _download = MutableStateFlow<DownloadUi>(DownloadUi.Idle)
    val download: StateFlow<DownloadUi> = _download.asStateFlow()

    /** The version the rep put off with "Later" in this process; a required update cannot be put off. */
    private val _laterFor = MutableStateFlow<Int?>(null)

    /** Never throws: offline or a failed check shows the cached answer. */
    suspend fun check(atLogin: Boolean) {
        _state.value = withContext(Dispatchers.IO) { runCatching { manager.check(atLogin) }.getOrElse { manager.state() } }
    }

    /** What the shell shows now; see [updateScreen]. Reads the stored minimum: call off the main thread. */
    suspend fun screen(state: UpdateState, laterFor: Int?, dayOpen: Boolean, serverSaidTooOld: Boolean): UpdateScreen =
        withContext(Dispatchers.IO) { updateScreen(state, laterFor, manager.dayGate(dayOpen, serverSaidTooOld)) }

    val laterFor: StateFlow<Int?> = _laterFor.asStateFlow()

    fun later(release: ReleaseInfo) { _laterFor.value = release.versionCode }

    /**
     * Settings > App update: shows the page for any available release, a silent one included (the row is where a silent
     * release is offered) and one put off with "Later"; "Later" on that page puts it off again. Also asks the server now
     * (unthrottled; a tap is the rep's own request).
     */
    fun openPage() {
        _laterFor.value = SHOW_ANY
        startCheck()
    }

    fun dayGate(dayOpen: Boolean, serverSaidTooOld: Boolean): DayGate = manager.dayGate(dayOpen, serverSaidTooOld)

    fun network(): NetworkStatus {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return NetworkStatus.OFFLINE
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return NetworkStatus.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkStatus.OFFLINE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetworkStatus.WIFI else NetworkStatus.MOBILE
    }

    /** Download and install run here, not in a screen's scope: leaving the screen never leaves a stuck "Downloading". */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts the download of [release] (resumable, SHA-256 checked); refuses mobile data for a Wi-Fi-only release. */
    fun startDownload(release: ReleaseInfo) { work.launch { download(release) } }

    /** "Update now" on the day block: a fresh check (the throttle is skipped). */
    fun startCheck() { work.launch { check(atLogin = true) } }

    /** Starts the install of the downloaded [release] once no sync batch is running. */
    fun startInstall(release: ReleaseInfo) { work.launch { install(release) } }

    internal suspend fun download(release: ReleaseInfo) = lock.withLock {
        val wifiOnly = when (val s = _state.value) { is UpdateState.Available -> s.wifiOnly; is UpdateState.Required -> s.wifiOnly; else -> true }
        if (!UpdatePolicy.mayDownload(wifiOnly, network() == NetworkStatus.WIFI)) { _download.value = DownloadUi.NeedsWifi; return@withLock }
        _download.value = DownloadUi.Downloading(0)
        _download.value = try {
            when (downloader.download(release) { _download.value = DownloadUi.Downloading(it) }) {
                is DownloadResult.Done -> DownloadUi.Ready
                is DownloadResult.NoSpace -> DownloadUi.NoSpace
                is DownloadResult.Retry, is DownloadResult.Corrupt -> DownloadUi.Failed
            }
        } catch (e: CancellationException) {
            _download.value = DownloadUi.Idle
            throw e
        } catch (_: Exception) {
            DownloadUi.Failed // never a crash; the part file stays for a resume
        }
    }

    internal suspend fun install(release: ReleaseInfo) = lock.withLock {
        _download.value = try {
            // SHA-256 of the APK and the package checks are file work: off the main thread (the work scope is IO).
            when (updater.install(downloader.apkFile(release), release, syncIdle())) {
                InstallStart.Started -> DownloadUi.Ready
                InstallStart.NeedsUnknownSources -> DownloadUi.NeedsUnknownSources
                InstallStart.SyncBusy -> DownloadUi.SyncBusy
                is InstallStart.Refused -> DownloadUi.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DownloadUi.Failed
        }
    }

    fun unknownSourcesIntent() = updater.unknownSourcesIntent()

    /** True while no record-sync job is running (the install never cuts a batch upload). */
    private fun syncIdle(): Flow<Boolean> = WorkManager.getInstance(app).getWorkInfosByTagFlow(WorkManagerSyncScheduler.TAG)
        .map { infos -> infos.none { it.state == WorkInfo.State.RUNNING } }
}

/** What an app shell draws for the updater. */
sealed interface UpdateScreen {
    /** The app as usual. */
    data object None : UpdateScreen

    /** The update page; [required] hides "Later". */
    data class Prompt(val release: ReleaseInfo, val required: Boolean) : UpdateScreen

    /** A new day cannot start and there is nothing this phone can install: the day-gate block (contact the supervisor). */
    data object Blocked : UpdateScreen
}

/**
 * The updater's screen rule (F-SYS-020, D24-22): a required update blocks only a NEW day; an open day finishes (the update
 * is then offered with "Later"), upload is never blocked. A silent release is shown only in Settings; "Later" hides a
 * release for the rest of the process. [SHOW_ANY] as `laterFor` (Settings > App update) shows any available release.
 */
fun updateScreen(state: UpdateState, laterFor: Int?, gate: DayGate): UpdateScreen {
    val forced = laterFor == SHOW_ANY
    val required = state as? UpdateState.Required
    if (gate == DayGate.BLOCKED_UPDATE_REQUIRED) {
        val release = required?.release ?: (state as? UpdateState.Available)?.release
        return release?.let { UpdateScreen.Prompt(it, required = true) } ?: UpdateScreen.Blocked
    }
    val release = when (state) {
        is UpdateState.Required -> state.release
        is UpdateState.Available -> state.release.takeIf { state.prompt || forced }
        UpdateState.Current -> null
    } ?: return UpdateScreen.None
    return if (release.versionCode == laterFor) UpdateScreen.None else UpdateScreen.Prompt(release, required = false)
}

/** `laterFor` value set by [UpdateShell.openPage]: no version is put off and a silent release is shown too. */
const val SHOW_ANY: Int = Int.MIN_VALUE
