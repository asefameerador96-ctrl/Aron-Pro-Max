package com.aktcl.aron.amo

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
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.SunlightPreference
import com.aktcl.aron.core.ui.aronPageBackground
import com.aktcl.aron.feature.auth.LoginScreen
import com.aktcl.aron.feature.auth.LoginViewModel
import com.aktcl.aron.feature.amo.AmoApp
import com.aktcl.aron.feature.amo.AmoLeaf
import com.aktcl.aron.feature.amo.AmoScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.aktcl.aron.feature.home.HomePlaceholderViewModel
import com.aktcl.aron.feature.home.HomeUser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity of ARON AMO (docs/24 s5.1): enrolment and login, then [AmoApp] (the Calm Glass Home frame, Settings and the
 * section screens). The language is applied in [attachBaseContext] so every string resource resolves in Bangla or English
 * (docs/24 s5.6); the page background is the root (no opaque surface over it) and the sunlight switch is remembered per user.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var components: SessionComponents
    @Inject lateinit var deviceEnrolment: com.aktcl.aron.core.sync.device.DeviceEnrolment
    @Inject lateinit var shellLogout: ShellLogout
    @Inject lateinit var updateShell: com.aktcl.aron.core.sync.shell.UpdateShell
    @Inject lateinit var systemShell: SystemShell
    @Inject lateinit var pushShell: com.aktcl.aron.core.sync.shell.PushShell
    @Inject lateinit var activityLog: com.aktcl.aron.core.sync.ActivityLog
    @Inject lateinit var telemetry: com.aktcl.aron.core.sync.DeviceTelemetry
    @Inject lateinit var imageCache: com.aktcl.aron.core.sync.ImageCache
    @Inject lateinit var skuPackshots: com.aktcl.aron.core.sync.SkuPackshots
    /** D-AMO-BUNDLE-01: the day-bundle download per user (F-SYS-006); the SR app has called it since its first build. */
    @Inject lateinit var bundleDownloaders: com.aktcl.aron.core.sync.BundleDownloaders
    private val packshotProvider by lazy { com.aktcl.aron.core.ui.aron.BitmapPackshotProvider(skuPackshots::bitmap, skuPackshots::cached) }
    @Inject lateinit var databases: com.aktcl.aron.core.database.UserDatabases
    @Inject lateinit var scheduler: com.aktcl.aron.core.sync.SyncScheduler
    @Inject lateinit var mediaShell: MediaShell
    @Inject lateinit var printerManager: com.aktcl.aron.core.printing.bt.PrinterManager
    @Inject lateinit var fixManager: com.aktcl.aron.core.geo.FixManager
    @Inject lateinit var deviceRuntime: com.aktcl.aron.core.sync.device.DeviceRuntime
    private val locationNotice by lazy { com.aktcl.aron.core.sync.LocationNotice({ databases.of(it) }, components.trustedClock, scheduler, com.aktcl.aron.core.sync.LocationNotice.offlineProbe(applicationContext)) }

    /** F-SYS-020: an update check on every resume (throttled to 12 h inside, cached offline). */
    /** D-AMO-BUNDLE-01: bumped on every resume; the day's bundle effect re-runs on it (a delta at most every 30 minutes, a full one on a new day). */
    private var resumeTick by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick++
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

    override fun attachBaseContext(newBase: Context) {
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
            var sunlight by remember { mutableStateOf(false) }
            AronTheme(language, sunlight = sunlight) {
                Box(Modifier.fillMaxSize().aronPageBackground()) {
                    val state by components.session.state.collectAsStateWithLifecycle()
                    when (val s = state) {
                        SessionState.Restoring -> Unit // AUD-PERF-05: the background is the splash for the few ms of the restore
                        SessionState.LoggedOut -> com.aktcl.aron.feature.auth.EnrolmentGate(
                            // Enrolment (docs/24 s10.4) before the first login of a new install; never in a signed-in day.
                            status = { com.aktcl.aron.feature.auth.EnrolStatus(deviceEnrolment.enrolled(), deviceEnrolment.pending(), deviceEnrolment.refusedCode()) },
                            submit = deviceEnrolment::submit,
                            appTitle = stringResource(R.string.app_name),
                            onLanguageSelect = onLanguageSelect,
                        ) { onEnrol ->
                            // The bind step (OTP from /device-otp) follows the first login of an enrolled phone.
                            val vm = viewModel { LoginViewModel(components.session::login).also { it.bind = components.session::bindDevice } }
                            // R1: the footer reads "ARON AMO v<version>", never the vendor credit
                            LoginScreen(vm, stringResource(R.string.app_name), versionName, onLanguageSelect, onEnrol, variant = com.aktcl.aron.feature.auth.LoginVariant.Amo, footerText = stringResource(com.aktcl.aron.feature.amo.R.string.amo_shell_login_footer, stringResource(R.string.app_name), BuildConfig.VERSION_NAME))
                        }
                        is SessionState.Active -> AronTheme(language, sunlight = sunlight, amo = true) { // D-AMO-MAN-03: the AMO look on every signed-in screen
                            // F-SYS-022: AMO keeps its data; it keeps uploading.
                            val logoutFlow = remember(s.user.userId) { shellLogout.flow(AppRole.AMO, s.user.userId) }
                            var unsent by remember { mutableStateOf<Int?>(null) }
                            unsent?.let { n -> UnsentItemsDialog(n, onSyncNow = { logoutFlow.syncNow(); unsent = null }, onCancel = { unsent = null }) }
                            val logoutTap: () -> Unit = {
                                    lifecycleScope.launch {
                                        // A failed count or a logout already running throws: refuse this tap, never crash.
                                        try {
                                        when (val c = logoutFlow.check()) {
                                            is LogoutCheck.Refused -> unsent = c.unsent
                                            is LogoutCheck.Proceed -> {
                                                // The kept copies of the zone's lists (sales, stock, tasks) do not outlive the sign-in.
                                                (components.session.state.value as? SessionState.Active)?.user?.userId?.let { java.io.File(cacheDir, "amo_api_$it").deleteRecursively() }
                                                when (val r = logoutFlow.logout()) {
                                                is LogoutResult.Refused -> unsent = r.unsent
                                                is LogoutResult.WipeIncomplete -> Toast.makeText(this@MainActivity, SystemR.string.logout_wipe_incomplete, Toast.LENGTH_LONG).show()
                                                else -> Unit
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
                            val vm = viewModel(key = "home-" + s.user.userId) {
                                // conditional on the held bundle: a phone that has today's bundle gets a 304, not the whole zone again
                                HomePlaceholderViewModel(System::currentTimeMillis) {
                                    components.syncApi.bundle(ifNoneMatch = runCatching { com.aktcl.aron.core.database.repo.ReferenceRepository(databases.of(s.user.userId)).etag() }.getOrNull())
                                }
                            }
                            val home by vm.state.collectAsStateWithLifecycle()
                            val sunlightPref = remember(s.user.userId) { SunlightPreference(applicationContext, s.user.userId.toString()) }
                            LaunchedEffect(s.user.userId) { sunlight = sunlightPref.enabled }
                            // F-AMO-001: the Outlet badge and the menu variant come from the stored supervisor section (local read).
                            var reach by remember(s.user.userId) { mutableStateOf(com.aktcl.aron.feature.amo.AmoReach.NONE) }
                            // D-AMO-BUNDLE-01: bumped when the day's bundle was applied to Room, so the zone line and the menu re-read it.
                            var bundleTick by remember(s.user.userId) { mutableStateOf(0) }
                            LaunchedEffect(s.user.userId, home.bundle, bundleTick) {
                                reach = runCatching {
                                    com.aktcl.aron.feature.amo.AmoReach.parse(com.aktcl.aron.core.database.repo.ReferenceRepository(databases.of(s.user.userId)).section("supervisor"), s.user.userId)
                                }.getOrDefault(com.aktcl.aron.feature.amo.AmoReach.NONE)
                            }
                            // F-AMO-003: the day object (attendance first); one per user, restored from Room after a kill.
                            var day by remember(s.user.userId) { mutableStateOf<AmoDay?>(null) }
                            LaunchedEffect(s.user.userId) {
                                val d = AmoDay(
                                    s.user.userId, applicationContext, databases.of(s.user.userId), components, scheduler, fixManager, printerManager, s.user.fullName,
                                    runCatching { com.aktcl.aron.core.database.repo.MemoNumbering(s.user.username, s.user.bindOrdinal ?: 0, s.user.memoSeqBlockSize ?: 500) }.getOrNull(), deviceRuntime,
                                )
                                runCatching { d.reload(); d.attendance.restore(d.attendanceToday()) }
                                // F-SYS-030/010: the camera and this user's photo queue (Manual Override, Update Base, outlet requests).
                                runCatching { d.attachMedia(mediaShell.componentsFor(s.user.userId, d::businessDate)) }
                                day = d
                            }
                            // D-AMO-SHELL-10/11: the four Home target cards (`GET /v1/supervisor/call-targets`), read in the background after sign-in, whenever
                            // a bundle is applied and on resume while the numbers are stale or failed; never in the way of the screen. The kept copy (Room, else
                            // the last good values) is held per MONTH inside CachedCallTargets, so a failed read never blanks the cards and never shows another month.
                            var targets by remember(s.user.userId) { mutableStateOf<com.aktcl.aron.feature.amo.CallTargets?>(null) }
                            var targetsStatus by remember(s.user.userId) { mutableStateOf(com.aktcl.aron.feature.amo.CallTargetsStatus.Fresh) }
                            var targetsRetryTick by remember(s.user.userId) { mutableStateOf(0) }
                            // coming back to the foreground: a session left open across Dhaka midnight shows the new date; a stale or failed card set tries again (8 s cap, no polling)
                            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                vm.onResume()
                                if (targetsStatus != com.aktcl.aron.feature.amo.CallTargetsStatus.Fresh) targetsRetryTick++
                            }
                            LaunchedEffect(day, home.businessDate, bundleTick, targetsRetryTick) {
                                val d = day ?: return@LaunchedEffect
                                val r = runCatching { d.callTargets.load(home.businessDate.take(7)) }.getOrNull() ?: return@LaunchedEffect
                                targets = r.targets
                                targetsStatus = r.status
                            }
                            // D-AMO-BUNDLE-01 (F-SYS-006): the day's bundle goes into Room in the background, as the SR app does at day start
                            // and on resume: the full bundle when this phone holds none for today, otherwise a delta at most every 30 minutes.
                            // The screens already show the local data; Stock and the zone line re-read when a bundle was applied.
                            LaunchedEffect(day, resumeTick) {
                                val d = day ?: return@LaunchedEffect
                                val outcome = d.downloadBundle(bundleDownloaders)
                                if (outcome == com.aktcl.aron.core.sync.BundleOutcome.APPLIED || outcome == com.aktcl.aron.core.sync.BundleOutcome.PREFETCH_PROMOTED) bundleTick++
                            }
                            val dayScope = rememberCoroutineScope()
                            val screens: Map<AmoScreen, @Composable (() -> Unit) -> Unit> = day?.let { d ->
                                mapOf<AmoScreen, @Composable (() -> Unit) -> Unit>(
                                    AmoScreen.SALES_SUBMIT to { back ->
                                        com.aktcl.aron.feature.amo.AmoFrame(stringResource(com.aktcl.aron.feature.home.R.string.hm_tile_sales_submit), back, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_sales_submit) { AmoSubmitHost(d) }
                                    },
                                    AmoScreen.SUPPORT to { back ->
                                        com.aktcl.aron.feature.amo.AmoFrame(stringResource(com.aktcl.aron.feature.home.R.string.set_support), back, icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_gear) { SupportHost(systemShell, d.userId, versionName) }
                                    },
                                    // D-PRINT-02: the same diagnostics as the SR app; no HoldPrinter, the screen opens its own link
                                    AmoScreen.PRINTER to { back ->
                                        com.aktcl.aron.feature.amo.AmoFrame(stringResource(com.aktcl.aron.feature.home.R.string.set_printer), back, icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_gear) {
                                            com.aktcl.aron.core.printing.ui.PrinterDiagnosticsScreen(printerManager, versionName)
                                        }
                                    },
                                    AmoScreen.OUTLET to { back -> AmoOutletHub(d, back) },
                                    AmoScreen.REPORT to { back -> AmoReportHub(d, back) },
                                    // D-AMO-MAN-02: one Home tile per destination; the hosts open straight on the tile's own screen
                                    AmoScreen.TEAM_LOCATION to { back -> AmoTeamHub(d, reach, back, start = AmoLeaf.TEAM_LOCATION) },
                                    AmoScreen.TEAM_PERFORMANCE to { back -> AmoTeamHub(d, reach, back, start = AmoLeaf.TEAM_PERFORMANCE) },
                                    AmoScreen.TASK_DELEGATION to { back -> AmoTeamHub(d, reach, back, start = AmoLeaf.TASK_DELEGATION) },
                                    AmoScreen.LIVE_DASHBOARD to { back -> AmoTeamHub(d, reach, back, start = AmoLeaf.LIVE_DASHBOARD) },
                                    AmoScreen.SR_STOCK to { back -> AmoTeamHub(d, reach, back, start = AmoLeaf.SR_STOCK) },
                                    AmoScreen.EXCEPTIONS to { back -> AmoTeamHub(d, reach, back, startExceptions = true) },
                                    AmoScreen.SALES_JOURNEY to { back -> AmoJourneyHost(d, back) },
                                    AmoScreen.STOCK to { back -> AmoSaleHub(d, back, start = AmoLeaf.STOCK) },
                                    AmoScreen.SALE to { back -> AmoSaleHub(d, back, start = AmoLeaf.SALE) },
                                    AmoScreen.MEMO to { back -> AmoSaleHub(d, back, start = AmoLeaf.MEMO) },
                                    AmoScreen.SUMMARY to { back -> AmoSaleHub(d, back, start = AmoLeaf.SUMMARY) },
                                    AmoScreen.CONTROL_CALL to { back -> ControlCallHost(d, back) },
                                    AmoScreen.JOINT_CALL to { back -> ControlCallHost(d, back, kind = com.aktcl.aron.feature.amo.AmoVisitKind.JOINT_CALL) },
                                    AmoScreen.ATTENDANCE to { back ->
                                        val attendance by d.attendance.state.collectAsStateWithLifecycle()
                                        // D-NIGHT-CHECKOUT-01: the local Sales Submit is read on every visit (also back from Sales Submit)
                                        LaunchedEffect(d) { d.attendance.refreshSubmit() }
                                        val navigate = com.aktcl.aron.feature.amo.LocalAmoNavigate.current
                                        // screenshots 14 to 16: the user card "<name> (<username>)" over "<zone> AMO, <date>"
                                        val attendanceHeader = com.aktcl.aron.feature.amo.AmoHomeHeader.of(HomeUser(s.user.fullName, s.user.username, s.user.role, false, false, false), home.businessDate, reach.zoneName, stringResource(com.aktcl.aron.feature.amo.R.string.amo_shell_header_zone), stringResource(com.aktcl.aron.feature.amo.R.string.amo_shell_header_plain))
                                        com.aktcl.aron.feature.amo.AmoAttendance(
                                            attendance, d.checkoutAtLabel(), onCheckIn = { dayScope.launch { d.attendance.checkIn() } },
                                            onCheckOut = { dayScope.launch { d.attendance.checkOut() } }, onBack = back,
                                            onOpenSalesSubmit = { navigate(AmoScreen.SALES_SUBMIT) },
                                            offline = !d.online(),
                                            userTitle = attendanceHeader.title, userSubtitle = attendanceHeader.subtitle,
                                        )
                                    },
                                )
                            } ?: emptyMap()
                            val updateState by updateShell.state.collectAsStateWithLifecycle()
                            // F-SYS-075: the location notice first.
                            com.aktcl.aron.feature.auth.LocationNoticeGate(
                                key = s.user.userId,
                                load = { locationNotice.state(s.user.userId).let { com.aktcl.aron.feature.auth.NoticeNeed(it.needed, it.required) } },
                                accept = { shownAt -> locationNotice.accept(s.user.userId, language.tag, shownAt) },
                                nowMs = components.trustedClock::nowMs,
                                onLogout = { logoutTap() },
                            ) {
                            UpdateHost(updateShell, dayOpen = { false }, serverSaidTooOld = s.updateRequired, onLogout = { logoutTap() }) { AmoApp(
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
                                reach = reach, screens = screens, targets = targets, targetsStatus = targetsStatus,
                                updateAvailable = updateState is com.aktcl.aron.core.system.update.UpdateState.Available,
                                // tso-manual X4a (R-W-11, A3 ask 1): the routes closed by the zone's Final Submit; empty while all are open
                                banners = amoDayLockBanners(day),
                            ) }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
