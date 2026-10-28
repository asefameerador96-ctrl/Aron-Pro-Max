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
    @Inject lateinit var skuPackshots: com.aktcl.aron.core.sync.SkuPackshots
    private val packshotProvider by lazy { com.aktcl.aron.core.ui.aron.BitmapPackshotProvider(skuPackshots::bitmap, skuPackshots::cached) }
    @Inject lateinit var databases: com.aktcl.aron.core.database.UserDatabases
    @Inject lateinit var scheduler: com.aktcl.aron.core.sync.SyncScheduler
    private val outbox by lazy { TsoOutbox(applicationContext, components, databases, scheduler) }
    private val readModels by lazy { TsoReadModels(components, databases, outbox) }
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
            // PARITY-SPEC s2.6: pack pictures of the Stock and Sale rows (thumbnail cache, then the dev-only bundled art).
            androidx.compose.runtime.CompositionLocalProvider(com.aktcl.aron.core.ui.aron.LocalPackshotProvider provides packshotProvider) {
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
                            // tso-manual A1: the TSO login of screenshot 5 (D-TSO-MAN-15); the sun switch and the version are in Settings
                            LoginScreen(vm, stringResource(R.string.app_name), versionName, onLanguageSelect, onEnrol, sunlight = sunlight, onSunlight = { on -> sunlight = on; devicePref.enabled = on }, variant = com.aktcl.aron.feature.auth.LoginVariant.Tso)
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
                                                if (r !is LogoutResult.Refused) {
                                                    uid?.let { java.io.File(cacheDir, "tso_api_$it").deleteRecursively() }
                                                    // SH-17: no retained screen state (half-typed forms, client uuids, the dashboard model) outlives the sign-in
                                                    viewModelStore.clear()
                                                }
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
                            val mediaState = androidx.compose.runtime.produceState<com.aktcl.aron.core.media.MediaComponents?>(null, s.user.userId) {
                                value = runCatching { mediaShell.componentsFor(s.user.userId) { components.trustedClock.businessDate().toString() } }.getOrNull()
                            }
                            mediaState.value?.let { com.aktcl.aron.core.media.CameraCaptureOverlay(it.camera) }
                            // a1-stubs: one host per signed-in user; the lanes' hosts build their ports from it (A2 TicketsHost, A3 AreasHost)
                            val host = remember(s.user.userId) {
                                TsoHost(
                                    applicationContext, components, databases, outbox, readModels, s.user.userId, s.user.username,
                                    online = { isOnline() }, media = { mediaState.value },
                                    mediaFor = { mediaShell.componentsFor(s.user.userId) { components.trustedClock.businessDate().toString() } },
                                )
                            }
                            val dashboard = remember(host) { dashboardScreens(host) }
                            val areas = remember(host) { areaScreens(AreasHostDeps(host)) }
                            val tickets = remember(host) { ticketScreens(TicketsHostDeps(host)) }
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
                                changePassword = remember { readModels.changePassword() },
                                support = { back ->
                                    com.aktcl.aron.core.ui.AronScreen(topBar = { com.aktcl.aron.core.ui.AronTopBar(stringResource(com.aktcl.aron.feature.home.R.string.set_support), onBack = back) }) { SupportHost(systemShell, s.user.userId, versionName) }
                                },
                                // A1: the Dashboard (root) and Login & Bikroy Joma Status; A3 areas; A2 tickets
                                screens = dashboard + areas + tickets,
                            ) } }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * What every feature host of the signed-in TSO day can use (A1 dashboard, targets and inbox; A2 [ticketScreens]; A3
 * [areaScreens]), built once per signed-in user by [MainActivity]. A host builds its ports from these handles, so a lane
 * never edits MainActivity to wire a screen.
 */
class TsoHost(
    val context: Context,
    val components: SessionComponents,
    val databases: com.aktcl.aron.core.database.UserDatabases,
    /** The one TSO outbox (records and local mirror rows in one transaction, idempotent by client uuid). */
    val outbox: TsoOutbox,
    val readModels: TsoReadModels,
    val userId: Long,
    /** The login name: part of every retained screen-state key, so a shared phone never hands one rep's state to the next. */
    val userKey: String,
    /** Validated connectivity right now (asked on demand: no listener, no polling). */
    val online: () -> Boolean,
    /** This user's camera and photo queue once ready, else null; a compose state read, so a screen recomposes when it lands. */
    val media: () -> com.aktcl.aron.core.media.MediaComponents?,
    /** This user's camera and photo queue, created on demand (outside composition). */
    val mediaFor: suspend () -> com.aktcl.aron.core.media.MediaComponents,
) {
    /** Dhaka business date `YYYY-MM-DD` from trusted time. */
    val today: () -> String = { components.trustedClock.businessDate().toString() }
    /** Trusted epoch milliseconds. */
    val nowMs: () -> Long = { components.trustedClock.nowMs() }
    /** Trusted now as RFC 3339. */
    val nowIso: () -> String = { java.time.Instant.ofEpochMilli(components.trustedClock.nowMs()).toString() }
}
