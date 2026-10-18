package com.aktcl.aron.tso

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aktcl.aron.core.sync.shell.ShellLogout
import com.aktcl.aron.core.system.R as SystemR
import com.aktcl.aron.core.system.logout.AppRole
import com.aktcl.aron.core.system.logout.LogoutCheck
import com.aktcl.aron.core.system.logout.LogoutResult
import com.aktcl.aron.core.system.logout.UnsentItemsDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
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
import com.aktcl.aron.core.ui.SunlightPreference
import com.aktcl.aron.core.ui.aronPageBackground
import com.aktcl.aron.feature.auth.LoginScreen
import com.aktcl.aron.feature.auth.LoginViewModel
import com.aktcl.aron.feature.tso.TsoApp
import com.aktcl.aron.feature.tso.TsoLoginTheme
import com.aktcl.aron.feature.tso.tsoLoginNeedsLightBarIcons
import com.aktcl.aron.feature.home.HomePlaceholderViewModel
import com.aktcl.aron.feature.home.HomeUser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity of ARON TSO (docs/24 s5.1): enrolment and login, then [TsoApp] (the Calm Glass Home frame, Settings and the
 * section screens). The language is applied in [attachBaseContext] so every string resource resolves in Bangla or English
 * (docs/24 s5.6); the page background is the root (no opaque surface over it) and the sunlight switch is remembered per user.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var components: SessionComponents
    @Inject lateinit var deviceEnrolment: com.aktcl.aron.core.sync.device.DeviceEnrolment
    @Inject lateinit var shellLogout: ShellLogout
    @Inject lateinit var systemShell: SystemShell
    @Inject lateinit var mediaShell: MediaShell
    @Inject lateinit var updateShell: com.aktcl.aron.core.sync.shell.UpdateShell
    @Inject lateinit var pushShell: com.aktcl.aron.core.sync.shell.PushShell
    @Inject lateinit var activityLog: com.aktcl.aron.core.sync.ActivityLog
    @Inject lateinit var telemetry: com.aktcl.aron.core.sync.DeviceTelemetry
    @Inject lateinit var imageCache: com.aktcl.aron.core.sync.ImageCache
    @Inject lateinit var databases: com.aktcl.aron.core.database.UserDatabases
    @Inject lateinit var scheduler: com.aktcl.aron.core.sync.SyncScheduler
    private val readModels by lazy { TsoReadModels(components, databases, TsoOutbox(applicationContext, components, databases, scheduler)) }
    private val locationNotice by lazy { com.aktcl.aron.core.sync.LocationNotice({ databases.of(it) }, components.trustedClock, scheduler, com.aktcl.aron.core.sync.LocationNotice.offlineProbe(applicationContext)) }

    /** F-SYS-020: an update check on every resume (throttled to 12 h inside, cached offline). */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { updateShell.check(atLogin = false) }
        pushShell.onResume() // N-038: a token not registered yet is tried again (local check first)
        lifecycleScope.launch { components.session.noteTimePassing() } // F-SYS-052: the offline window counts real uptime
        telemetry.sampleSoon() // F-SYS-081: a foreground event is a sample point
        lifecycleScope.launch { // F-SYS-024: today's sampling, then the app-open event
            val id = (components.session.settled() as? SessionState.Active)?.user?.userId ?: return@launch
            activityLog.refreshSampling(id)
            activityLog.log(id, "app", "open")
            // F-SYS-029: the cache cap from the user's bundle (cfg.app.image_cache_mb)
            runCatching {
                val db = databases.of(id)
                com.aktcl.aron.core.sync.SessionSyncRunner.configInt(com.aktcl.aron.core.database.repo.ReferenceRepository(db).config(com.aktcl.aron.core.sync.ImageCache.CFG_CAP_MB, com.aktcl.aron.core.sync.SyncEngine.iso(components.trustedClock.nowMs())))
            }.getOrNull()?.let { imageCache.setCapMb(it) }
        }
    }

    /** F-SYS-024: the buffered events become one outbox row when the app leaves the screen (they ride the next upload). */
    override fun onStop() {
        super.onStop()
        val id = (components.session.state.value as? SessionState.Active)?.user?.userId ?: return
        activityLog.log(id, "app", "close")
        activityLog.flushSoon(id) // the log's own scope: onDestroy right after must not cancel the write
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun attachBaseContext(newBase: Context) {
        // D-181: the TSO app is English-first today (cfg.app.default_locale tso = en): the first run stores English once; the rep can switch to Bangla in Settings
        newBase.getSharedPreferences("aron_ui", Context.MODE_PRIVATE).let { p -> if (!p.contains("language")) p.edit().putString("language", com.aktcl.aron.core.common.AppLanguage.EN.tag).commit() }
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Calm Glass light theme: dark status and navigation bar icons over the light page (docs/32 s2a legibility).
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        val language = AppLocale.current(this)
        val onLanguageSelect: (AppLanguage) -> Unit = { if (AppLocale.set(this, it)) recreate() }
        val versionName = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        setContent {
            // Before sign-in the sun switch is a device-level choice (no user yet); a signed-in user has their own.
            val devicePref = remember { SunlightPreference(applicationContext, "device") }
            var sunlight by remember { mutableStateOf(devicePref.enabled) }
            val state by components.session.state.collectAsStateWithLifecycle()
            // The login is the dark navy kit theme (F-TSO-001); the signed-in day keeps the Calm Glass light theme.
            val loggedOut = state !is SessionState.Active // Restoring too: the dark page is the splash of a logged-out phone
            LaunchedEffect(loggedOut) { if (loggedOut) sunlight = devicePref.enabled }
            androidx.compose.runtime.SideEffect {
                val needsLight = tsoLoginNeedsLightBarIcons(loggedOut, sunlight)
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !needsLight; isAppearanceLightNavigationBars = !needsLight }
            }
            TsoLoginTheme(language, sunlight = sunlight, loggedOut = loggedOut) {
                Box(Modifier.fillMaxSize().aronPageBackground()) {
                    when (val s = state) {
                        SessionState.Restoring -> Unit // AUD-PERF-05: the background is the splash for the few ms of the restore
                        SessionState.LoggedOut -> com.aktcl.aron.feature.auth.EnrolmentGate(
                            // Enrolment (docs/24 s10.4) before the first login of a new install; never in a signed-in day.
                            status = { com.aktcl.aron.feature.auth.EnrolStatus(deviceEnrolment.enrolled(), deviceEnrolment.pending(), deviceEnrolment.refusedCode()) },
                            submit = deviceEnrolment::submit,
                            appTitle = stringResource(R.string.app_name),
                            onLanguageSelect = onLanguageSelect,
                            sunlight = sunlight, onSunlight = { on -> sunlight = on; devicePref.enabled = on },
                        ) { onEnrol ->
                            // The bind step (OTP from /device-otp) follows the first login of an enrolled phone.
                            val vm = viewModel { LoginViewModel(components.session::login).also { it.bind = components.session::bindDevice } }
                            LoginScreen(vm, stringResource(R.string.app_name), versionName, onLanguageSelect, onEnrol, sunlight = sunlight, onSunlight = { on -> sunlight = on; devicePref.enabled = on })
                        }
                        is SessionState.Active -> {
                            // F-SYS-022: TSO is refused while anything is unsent, then wipes this user's data.
                            val logoutFlow = remember(s.user.userId) { shellLogout.flow(AppRole.TSO, s.user.userId) }
                            var unsent by remember { mutableStateOf<Int?>(null) }
                            unsent?.let { n -> UnsentItemsDialog(n, onSyncNow = { logoutFlow.syncNow(); unsent = null }, onCancel = { unsent = null }) }
                            val logoutTap: () -> Unit = {
                                    lifecycleScope.launch {
                                        // A failed count or a logout already running throws: refuse this tap, never crash.
                                        try {
                                        when (val c = logoutFlow.check()) {
                                            is LogoutCheck.Refused -> unsent = c.unsent
                                            is LogoutCheck.Proceed -> {
                                                // The kept dashboard copies do not outlive the sign-in (deleted once the logout was not refused).
                                                val uid = (components.session.state.value as? SessionState.Active)?.user?.userId
                                                val r = logoutFlow.logout()
                                                when (r) {
                                                    is LogoutResult.Refused -> unsent = r.unsent
                                                    is LogoutResult.WipeIncomplete -> Toast.makeText(this@MainActivity, SystemR.string.logout_wipe_incomplete, Toast.LENGTH_LONG).show()
                                                    else -> Unit
                                                }
                                                if (r !is LogoutResult.Refused) uid?.let { java.io.File(cacheDir, "tso_api_$it").deleteRecursively() }
                                            }
                                        }
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            Toast.makeText(this@MainActivity, SystemR.string.logout_failed, Toast.LENGTH_LONG).show()
                                        }
                                    }
                            }
                            // The bundle head check of Day 1 stays the source of the status row; nothing on Home waits for it.
                            // F-SYS-030: this user's camera and photo queue; the overlay is drawn once at the top of the day
                            val media by androidx.compose.runtime.produceState<com.aktcl.aron.core.media.MediaComponents?>(null, s.user.userId) {
                                value = runCatching { mediaShell.componentsFor(s.user.userId) { components.trustedClock.businessDate().toString() } }.getOrNull()
                            }
                            media?.let { com.aktcl.aron.core.media.CameraCaptureOverlay(it.camera) }
                            val vm = viewModel(key = "home-" + s.user.userId) {
                                HomePlaceholderViewModel(System::currentTimeMillis) { components.syncApi.bundle() }
                            }
                            val home by vm.state.collectAsStateWithLifecycle()
                            val sunlightPref = remember(s.user.userId) { SunlightPreference(applicationContext, s.user.userId.toString()) }
                            LaunchedEffect(s.user.userId) { sunlight = sunlightPref.enabled }
                            val updateState by updateShell.state.collectAsStateWithLifecycle()
                            // F-SYS-075: the location notice first.
                            com.aktcl.aron.feature.auth.LocationNoticeGate(
                                key = s.user.userId,
                                load = { locationNotice.state(s.user.userId).let { com.aktcl.aron.feature.auth.NoticeNeed(it.needed, it.required) } },
                                accept = { shownAt -> locationNotice.accept(s.user.userId, language.tag, shownAt) },
                                nowMs = components.trustedClock::nowMs,
                                onLogout = { logoutTap() },
                            ) {
                            // zone names for the pickers (scope-tree read, kept 30 min; "Zone <id>" until it lands)
                            val zoneNames by androidx.compose.runtime.produceState(emptyMap<Long, String>(), s.user.userId, language) { value = runCatching { readModels.zoneNames(s.user.userId, language == com.aktcl.aron.core.common.AppLanguage.BN) }.getOrDefault(emptyMap()) }
                            UpdateHost(updateShell, dayOpen = { false }, serverSaidTooOld = s.updateRequired, onLogout = { logoutTap() }) { androidx.compose.runtime.CompositionLocalProvider(com.aktcl.aron.feature.tso.LocalZoneNames provides zoneNames) { TsoApp(
                                user = HomeUser(
                                    fullName = s.user.fullName,
                                    username = s.user.username,
                                    role = s.user.role,
                                    offline = s.mode == UnlockMode.OFFLINE,
                                    reauthRequired = s.reauthRequired,
                                    updateRequired = s.updateRequired,
                                ),
                                businessDate = home.businessDate,
                                bundle = home.bundle,
                                versionText = versionName,
                                onLanguageSelect = onLanguageSelect,
                                onLogout = { logoutTap() },
                                sunlight = sunlight,
                                onSunlight = { on -> sunlight = on; sunlightPref.enabled = on },
                                onUpdate = { updateShell.openPage() },
                                updateAvailable = updateState is com.aktcl.aron.core.system.update.UpdateState.Available,
                                // the dashboard origin from the build config (aron.webBaseUrl); empty until it is configured
                                webOrigin = BuildConfig.WEB_BASE_URL.ifEmpty { null },
                                leave = remember(s.user.userId) { readModels.leave(s.user.userId) },
                                leaveQueue = remember(s.user.userId) { readModels.leaveQueue(s.user.userId) },
                                today = { components.trustedClock.businessDate().toString() },
                                feedback = remember(s.user.userId) { readModels.feedback(s.user.userId) },
                                feedbackQueue = remember(s.user.userId) { readModels.feedbackQueue(s.user.userId) { mediaShell.componentsFor(s.user.userId) { components.trustedClock.businessDate().toString() } } },
                                feedbackPhotos = media?.let { readModels.feedbackPhotos(it) },
                                userId = s.user.userId,
                                changePassword = remember { readModels.changePassword() },
                                support = { back ->
                                    com.aktcl.aron.core.ui.AronScreen(topBar = { com.aktcl.aron.core.ui.AronTopBar(stringResource(com.aktcl.aron.feature.home.R.string.set_support), onBack = back) }) { SupportHost(systemShell, s.user.userId, versionName) }
                                },
                                periphery = remember(s.user.userId) {
                                    com.aktcl.aron.feature.tso.PeripheryDeps(
                                        readModels.periphery(s.user.userId, readModels.plan(s.user.userId)), readModels.fix(applicationContext),
                                        online = { isOnline() }, nowMs = { components.trustedClock.nowMs() },
                                    )
                                },
                                finalSubmit = remember(s.user.userId) { readModels.finalSubmit(s.user.userId, readModels.plan(s.user.userId)) },
                                myCall = remember(s.user.userId) {
                                    val src = readModels.plan(s.user.userId)
                                    com.aktcl.aron.feature.tso.mycall.MyCallDeps(
                                        src, readModels.planQueue(s.user.userId, src), readModels.queryQueue(s.user.userId), readModels.taskQueue(s.user.userId),
                                        { readModels.rubric(s.user.userId) }, { readModels.taskTypes(s.user.userId) },
                                    )
                                },
                                nowIso = { java.time.Instant.ofEpochMilli(components.trustedClock.nowMs()).toString() },
                                screens = mapOf<com.aktcl.aron.feature.tso.TsoScreen, @androidx.compose.runtime.Composable (() -> Unit) -> Unit>(
                                    com.aktcl.aron.feature.tso.TsoScreen.DASHBOARD to { back ->
                                        val api = remember(s.user.userId) { TsoApi(components.apiClient, applicationContext, s.user.userId) }
                                        // The business day follows the trusted clock at every recomposition, not the device clock at start.
                                        val day = com.aktcl.aron.rules.BusinessDate.of(components.trustedClock.nowMs()).toString()
                                        TsoDashboardHost(api, day, back)
                                    },
                                ),
                            ) } }
                            }
                        }
                    }
                }
            }
        }
    }
}
