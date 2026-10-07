package com.aktcl.aron.sr

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.session.SessionState
import com.aktcl.aron.core.session.UnlockMode
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.auth.LoginScreen
import com.aktcl.aron.feature.auth.LoginViewModel
import com.aktcl.aron.feature.home.HomeUser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.sync.SyncScheduler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import javax.inject.Inject

/**
 * Single activity of ARON SR (docs/24 s5.1). Day-1 shell (N-001): login, then the home placeholder. The language is
 * applied in [attachBaseContext] so every string resource resolves in Bangla or English (docs/24 s5.6).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var components: SessionComponents
    @Inject lateinit var databases: UserDatabases
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var fixManager: FixManager
    @Inject lateinit var printerManager: com.aktcl.aron.core.printing.bt.PrinterManager
    @Inject lateinit var bundleDownloaders: com.aktcl.aron.core.sync.BundleDownloaders
    @Inject lateinit var deviceRuntime: com.aktcl.aron.core.sync.device.DeviceRuntime
    @Inject lateinit var resumeConfigCheck: com.aktcl.aron.core.sync.ResumeConfigCheck
    @Inject lateinit var mediaShell: MediaShell
    @Inject lateinit var shellLogout: com.aktcl.aron.core.sync.shell.ShellLogout
    @Inject lateinit var updateShell: com.aktcl.aron.core.sync.shell.UpdateShell
    @Inject lateinit var pushShell: com.aktcl.aron.core.sync.shell.PushShell
    private var dayHolder: SrDayHolder? = null
    /** N-038: counts taps on a task notification; SrApp opens the task list on each new value. */
    private val openTasks = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun takePushIntent(intent: android.content.Intent?) {
        if (!com.aktcl.aron.core.sync.push.PushNotices.opensTasks(intent)) return
        intent?.removeExtra(com.aktcl.aron.core.sync.push.PushNotices.EXTRA_OPEN) // a recreate must not open it again
        openTasks.value += 1
        pushShell.openedTasks()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takePushIntent(intent)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /** In front: the R9 config check (F-SYS-092) and the bundle delta (F-SYS-007); both launched, never awaited. */
    override fun onResume() {
        super.onResume()
        dayHolder?.day?.value?.let { it.launchConfigCheck(); it.launchDeltaRefresh(bundleDownloaders) }
        lifecycleScope.launch { updateShell.check(atLogin = false) }
        pushShell.onResume() // N-038: a token not registered yet is tried again (local check first) // F-SYS-020, throttled to 12 h inside
    }

    /** F-SYS-022: SR keeps its data (it keeps uploading); a failure never crashes the app. */
    private fun srLogout(userId: Long) {
        lifecycleScope.launch {
            try {
                shellLogout.flow(com.aktcl.aron.core.system.logout.AppRole.SR, userId).logout()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                android.widget.Toast.makeText(this@MainActivity, com.aktcl.aron.core.system.R.string.logout_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takePushIntent(intent)
        val language = AppLocale.current(this)
        val onLanguageSelect: (AppLanguage) -> Unit = { if (AppLocale.set(this, it)) recreate() }
        val versionName = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        setContent {
            var sunlight by remember { mutableStateOf(false) }
            AronTheme(language, sunlight = sunlight) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by components.session.state.collectAsStateWithLifecycle()
                    when (val s = state) {
                        SessionState.LoggedOut -> {
                            val vm = viewModel { LoginViewModel(components.session::login) }
                            LoginScreen(vm, stringResource(R.string.app_name), versionName, onLanguageSelect)
                        }
                        is SessionState.Active -> {
                            val holder = viewModel(key = "sr-day-" + s.user.userId) { SrDayHolder() }.also { dayHolder = it }
                            val day by holder.day.collectAsStateWithLifecycle()
                            LaunchedEffect(s.user.userId) {
                                if (holder.day.value == null) {
                                    holder.day.value = SrDay(
                                        s.user.userId, applicationContext, databases.of(s.user.userId), components, scheduler, fixManager, printerManager, s.user.fullName,
                                        runCatching { com.aktcl.aron.core.database.repo.MemoNumbering(s.user.username, s.user.bindOrdinal ?: 0, s.user.memoSeqBlockSize ?: 500) }.getOrNull(),
                                        deviceRuntime, resumeConfigCheck,
                                    ).also { d ->
                                        d.launchDayConfigRefresh()
                                        // F-SYS-030/010: the camera and this user's photo queue (opened before the day is shown).
                                        d.attachMedia(mediaShell.componentsFor(s.user.userId, d::businessDate))
                                    }
                                }
                            }
                            val sunlightPref = remember(s.user.userId) { com.aktcl.aron.core.ui.SunlightPreference(applicationContext, s.user.userId.toString()) }
                            LaunchedEffect(s.user.userId) { sunlight = sunlightPref.enabled }
                            // F-SYS-020: an open day (checked in, not submitted) is never interrupted by a required update.
                            day?.let { d -> UpdateHost(updateShell, dayOpen = { d.dayOpen() }, serverSaidTooOld = s.updateRequired, onLogout = { srLogout(s.user.userId) }) {
                                SrApp(
                                    day = d,
                                    user = HomeUser(
                                        fullName = s.user.fullName, username = s.user.username, role = s.user.role,
                                        offline = s.mode == UnlockMode.OFFLINE, reauthRequired = s.reauthRequired, updateRequired = s.updateRequired,
                                    ),
                                    health = null, versionText = versionName,
                                    onLanguageSelect = onLanguageSelect,
                                    // F-SYS-022: SR keeps its data (it keeps uploading); the flow schedules the upload.
                                    onLogout = { srLogout(s.user.userId) },
                                    onOtherTile = { },
                                    sunlight = sunlight,
                                    onSunlight = { on -> sunlight = on; sunlightPref.enabled = on },
                                    startBundleDownload = { day?.downloadBundle(bundleDownloaders) },
                                    openTasks = openTasks,
                                )
                            } }
                            // Drawn after the screens so the camera covers them while a capture is open (F-SYS-030).
                            day?.media?.let { com.aktcl.aron.core.media.CameraCaptureOverlay(it.camera) }
                        }
                    }
                }
            }
        }
    }
}
