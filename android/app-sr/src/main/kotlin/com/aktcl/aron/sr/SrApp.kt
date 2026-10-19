package com.aktcl.aron.sr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.R as CoreR
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.ui.HoldPrinter
import com.aktcl.aron.core.printing.ui.PrinterIndicator
import com.aktcl.aron.core.printing.ui.PrinterPickerDialog
import com.aktcl.aron.core.ui.aron.AronPrinterBadge
import com.aktcl.aron.core.ui.aron.AronScaffold as ParityScaffold
import com.aktcl.aron.core.ui.aron.AronTopBar as ParityTopBar
import com.aktcl.aron.core.system.permission.AndroidPermissions
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionGate
import com.aktcl.aron.core.system.permission.PermissionPolicy
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronScreenTransition
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronStatusChip
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.ChipForm
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.home.AppPermission
import com.aktcl.aron.feature.home.BundleFreshness
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.FirstBundleContent
import com.aktcl.aron.feature.home.FirstBundleState
import com.aktcl.aron.feature.home.HomeContent
import com.aktcl.aron.feature.home.HomeDayModel
import com.aktcl.aron.feature.home.HomeModel
import com.aktcl.aron.feature.home.HomeTile
import com.aktcl.aron.feature.home.HomeTiles
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.PermissionOnboardingContent
import com.aktcl.aron.feature.home.PlannedRoute
import com.aktcl.aron.feature.home.RouteKind
import com.aktcl.aron.feature.home.RoutePicker
import com.aktcl.aron.feature.home.RoutePickerContent
import com.aktcl.aron.feature.home.SettingsContent
import com.aktcl.aron.core.sync.BundleOutcome
import com.aktcl.aron.feature.outlet.ClusterOption
import com.aktcl.aron.feature.outlet.ForceReason
import com.aktcl.aron.feature.outlet.ForceSaleContent
import com.aktcl.aron.feature.outlet.ForceSaleMissing
import com.aktcl.aron.feature.outlet.ForceSaleResult
import com.aktcl.aron.feature.outlet.OutletMenuContent
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.outlet.OutletRequestContent
import com.aktcl.aron.feature.outlet.OutletRequestForm
import com.aktcl.aron.feature.outlet.OutletRequestKind
import com.aktcl.aron.feature.outlet.RequestError
import com.aktcl.aron.feature.outlet.RequestValidation
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.stock.SaveOutcome
import com.aktcl.aron.feature.stock.SaveRefusal
import com.aktcl.aron.feature.stock.StockContent
import com.aktcl.aron.feature.stock.StockMessage
import com.aktcl.aron.feature.tasks.TaskContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.aktcl.aron.feature.home.R as HomeR

enum class SrScreen { PERMISSIONS, ROUTE_PICK, HOME, ATTENDANCE, STOCK, PICKER, VISIT, FORCE, TASKS, SETTINGS, SUPPORT, OUTLET_MENU, OWN_REQUESTS, REQUEST_OUTLET, REQUEST_FORM, NO_SALE, SKIP, MEMO, EDIT, SUMMARY, SUBMIT, JOURNEY, KPI, TUTORIAL }

/** The screen tree of the SR app: Back and the top bar's back button follow the same parent mapping; depth drives the transition direction. */
object SrNav {
    fun parent(s: SrScreen): SrScreen = when (s) {
        SrScreen.FORCE, SrScreen.NO_SALE -> SrScreen.VISIT
        SrScreen.SUPPORT -> SrScreen.SETTINGS
        SrScreen.OWN_REQUESTS, SrScreen.REQUEST_FORM, SrScreen.REQUEST_OUTLET -> SrScreen.OUTLET_MENU
        SrScreen.SKIP -> SrScreen.PICKER
        SrScreen.EDIT -> SrScreen.MEMO
        else -> SrScreen.HOME
    }

    /** Home, the first-run permissions and the route choice are roots: no back, no parent. */
    fun isRoot(s: SrScreen): Boolean = s == SrScreen.HOME || s == SrScreen.PERMISSIONS || s == SrScreen.ROUTE_PICK

    fun depth(s: SrScreen): Int = if (isRoot(s)) 0 else 1 + depth(parent(s))

    /** Going deeper is a forward navigation (8 dp from the end); anything else is a back (from the start). */
    fun forward(from: SrScreen, to: SrScreen): Boolean = depth(to) > depth(from)
}

/**
 * The frame of every SR screen but Home (screens-sr s0): the glass top bar with the title, the back button (it dispatches
 * the system Back, see [rememberSystemBack], so a feature's own `BackHandler` wins exactly as it does for the gesture),
 * the printer icon where printing exists and the status chip; the printer banner under the bar; the feature content
 * below. The chip keeps its compact form beside the printer icon and drops to the dot from font scale 1.3.
 */
@Composable
fun SrFrame(
    title: String,
    onBack: (() -> Unit)?,
    sync: SyncChipState?,
    onSync: (() -> Unit)?,
    modifier: Modifier = Modifier,
    printer: PrinterManager? = null,
    /** The drawable of the screen's tile art (`R.drawable.aron_art_*` of core-ui), shown beside the title. */
    icon: Int? = null,
    subtitle: String? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    if (printer != null) HoldPrinter(printer)
    val back = stringResource(com.aktcl.aron.core.ui.R.string.core_ui_back)
    // the gradient bar of the reference: back chevron, the screen's tile art and the white title; the printer state hangs on the bar's bottom edge
    ParityScaffold(
        modifier = modifier,
        topBar = {
            ParityTopBar(
                title = title, icon = icon?.let { painterResource(it) }, onBack = onBack, backLabel = back,
                hanging = if (printer != null) ({ SrPrinterBadge(printer) }) else null,
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // sync is quiet when synced: the strip only shows for offline, syncing or waiting rows (Home keeps its own chip)
            if (sync != null && sync !is SyncChipState.Synced && sync !is SyncChipState.SyncedAt) {
                Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen), horizontalArrangement = Arrangement.End) {
                    AronStatusChip(sync, form = ChipForm.Full, onClick = onSync)
                }
            }
            subtitle?.let { Text(it, Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xs), style = MaterialTheme.typography.bodySmall) }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            bottomBar()
        }
    }
}

/** The printer state of the reference (sr/stock.webp): a red crossed printer when not connected, tap opens the connect flow. */
@Composable
internal fun SrPrinterBadge(manager: PrinterManager) {
    val state by manager.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val ind = PrinterIndicator.of(state)
    AronPrinterBadge(connected = ind.green, label = stringResource(ind.banner), onClick = { picking = true })
    if (picking) PrinterPickerDialog(manager) { picking = false }
}

/**
 * The top bar's back as the system Back: it dispatches through the activity's `OnBackPressedDispatcher`, so whatever
 * `BackHandler` is enabled deepest (a memo open on the Memo screen, the Sales Submit cancel) runs first and the app's
 * own handler (the parent mapping of [SrNav]) only when none is. [fallback] runs where no dispatcher owner exists.
 */
@Composable
fun rememberSystemBack(fallback: () -> Unit): () -> Unit {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    return { if (dispatcher != null) dispatcher.onBackPressed() else fallback() }
}

/**
 * The SR day host: Home, Attendance, Stock, the Sale picker with the geo check and Force Sale, Tasks, the Outlet menu and
 * its request forms, Settings and the first-run permissions. Every screen reads the local database; nothing waits for the
 * network. Memo, Summary, Sales Submit and the sale screens belong to android-sr-b (they plug in through [onOtherTile] and
 * the open [com.aktcl.aron.feature.outlet.VisitSession]).
 */
@Composable
fun SrApp(
    day: SrDay, user: HomeUser, health: DeviceHealth?, versionText: String,
    onLanguageSelect: (AppLanguage) -> Unit, onLogout: () -> Unit, onOtherTile: (HomeTile) -> Unit,
    startBundleDownload: suspend () -> Unit,
    /** PDA to Support (F-SR-006); null in previews and tests (the tile is hidden). */
    shell: SystemShell? = null,
    sunlight: Boolean = false, onSunlight: (Boolean) -> Unit = {},
    /** N-038: a new value means the rep tapped a task notification (android-core wiring). */
    openTasks: kotlinx.coroutines.flow.MutableStateFlow<Int>? = null,
    /** Settings > App update (F-SYS-020) and Photos only on Wi-Fi (F-SYS-037); null in previews and tests (rows hidden). */
    updateShell: com.aktcl.aron.core.sync.shell.UpdateShell? = null,
    mediaShell: MediaShell? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var screen by rememberSaveable { mutableStateOf(SrScreen.HOME) }
    var skipFirstBundle by rememberSaveable { mutableStateOf(false) }
    val loaded by day.loaded.collectAsState() // the local day has been read once (survives recreation; no first-bundle flash)
    val scope = rememberCoroutineScope()
    var editMemo by rememberSaveable { mutableStateOf("") }
    var skipOutlet by remember { mutableStateOf<OutletEntity?>(null) }
    val data by day.dayData.collectAsState()
    val tasks by day.taskBoard.state.collectAsState()
    val stockAttempt by day.stockAttempt.collectAsState()
    val attendance by day.attendance.state.collectAsState()
    var permissions by remember { mutableStateOf(activity?.let(SrPermissions::state) ?: com.aktcl.aron.feature.home.PermissionGate.initial()) }
    var asking by remember { mutableStateOf<AppPermission?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asking?.let { SrPermissions.markAsked(context, it) }
        asking = null
        activity?.let { permissions = SrPermissions.state(it) }
    }
    // A resume, a boundary or a sync request bumps this; the Home hero and the chip re-read Room on it (never a timer).
    var resumeTick by remember { mutableStateOf(0) }

    // Start: local data first (never waits), then the bundle in the background, then a refresh of what Home shows.
    LaunchedEffect(Unit) {
        day.recoverPrinting(); day.resumeMedia()
        day.reload(); day.nextSequenceFromStore(); day.restoreOpenVisit(); day.taskBoard.load()
        day.attendance.restore(day.attendanceToday())
        if (screen == SrScreen.HOME && permissions.toAsk.isNotEmpty()) screen = SrScreen.PERMISSIONS
        launch { startBundleDownload(); day.taskBoard.load(); resumeTick++ }
    }
    // No polling: the day state refreshes on resume and at the real boundaries (17:00 check-out gate, Dhaka midnight).
    var boundary by remember { mutableStateOf(0) }
    LaunchedEffect(boundary) {
        delay(day.millisToNextBoundary())
        day.attendance.tick(); day.reload(); day.taskBoard.load()
        boundary++; resumeTick++
    }
    // N-038: a task notification opens the task list; a pull a push asked for refreshes it when it lands.
    // A tap is consumed once (reset to 0), and never pulls the rep out of a visit, sale or memo: it waits until they are
    // back on a list screen.
    val openTasksTap = openTasks?.collectAsState()?.value ?: 0
    LaunchedEffect(openTasksTap, screen) {
        if (openTasksTap > 0 && screen in setOf(SrScreen.HOME, SrScreen.PICKER, SrScreen.TASKS, SrScreen.SETTINGS, SrScreen.KPI, SrScreen.JOURNEY)) {
            openTasks?.value = 0
            screen = SrScreen.TASKS
            day.taskBoard.load()
        }
    }
    LaunchedEffect(Unit) { com.aktcl.aron.core.sync.push.PushRuntime.pulled.collect { day.taskBoard.load() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        scope.launch { day.attendance.tick(); day.reload(); day.taskBoard.load(); resumeTick++ }
    }
    // Several routes planned today: the SR picks one before anything else (F-SR-065).
    val planned = data.routes.map { PlannedRoute(it.routeId, it.name, it.plannedToday, it.sequenceNo) }
    LaunchedEffect(planned, screen) {
        if (screen == SrScreen.HOME && RoutePicker.needsChoice(planned, day.chosenRouteId())) screen = SrScreen.ROUTE_PICK
    }
    BackHandler(enabled = !SrNav.isRoot(screen)) { screen = SrNav.parent(screen) }
    val goBack: () -> Unit = rememberSystemBack { screen = SrNav.parent(screen) }

    // The status chip of every screen (screens-sr s0): outbox counts and the newest ack, read on resume and on every
    // screen change, never polled; connectivity is one read. Offline is a chip state, never a modal.
    val locale = SrHomeReads.locale(LocalAppLanguage.current)
    var syncSnap by remember { mutableStateOf<SrSyncSnapshot?>(null) }
    // The device line (F-SR-064) rides the same read: battery and free storage once per resume, never a receiver or a timer.
    var deviceHealth by remember { mutableStateOf<DeviceHealth?>(null) }
    LaunchedEffect(screen, resumeTick, data.businessDate, loaded) {
        if (loaded) {
            val s = runCatching { SrHomeReads.sync(day, context, locale) }.getOrNull()
            syncSnap = s
            deviceHealth = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SrHomeReads.health(context, s, day.currentMs()) }
        }
    }
    val sync = syncSnap?.chip ?: if (user.offline) SyncChipState.Offline else null
    var syncSheet by rememberSaveable { mutableStateOf(false) }
    val openSync: () -> Unit = { syncSheet = true }

    // Home's hero, KPI row and cards: one Room pass off the main thread, keyed on the day data, attendance and the resume
    // tick; the tiles never wait for it (the hero shows a skeleton until the read lands).
    val categoryLabels = mapOf(
        "cigarette" to stringResource(R.string.sr_cat_cigarette), "bidi" to stringResource(R.string.sr_cat_bidi),
        "lighter" to stringResource(R.string.sr_cat_lighter), "match" to stringResource(R.string.sr_cat_match),
    )
    val unitLabels = mapOf("stick" to stringResource(R.string.sr_unit_stick), "piece" to stringResource(R.string.sr_unit_piece), "dozen" to stringResource(R.string.sr_unit_dozen))
    var homeSnap by remember { mutableStateOf<SrHomeSnapshot?>(null) }
    val onHome = screen == SrScreen.HOME
    LaunchedEffect(data, attendance, resumeTick, onHome, loaded) {
        if (loaded && onHome) homeSnap = runCatching { SrHomeReads.snapshot(day, attendance, locale, { categoryLabels[it] ?: it }, { unitLabels[it] ?: it }) }.getOrNull()
    }

    // The camera draws only while a capture is open (and sits behind the camera permission gate of core-system).
    com.aktcl.aron.core.printing.ui.PrintAttemptDialogs(stockAttempt, onAnswer = day::answerStockPrint, onClose = day::closeStockAttempt)
    AronScreenTransition(screen, SrNav::forward, Modifier.fillMaxSize()) { s ->
        when (s) {
        SrScreen.PERMISSIONS -> {
            LaunchedEffect(permissions) { if (permissions.toAsk.isEmpty()) screen = SrScreen.HOME }
            PermissionOnboardingContent(
                permissions,
                onAllow = { p ->
                    val names = SrPermissions.manifestNames(p)
                    if (names.isEmpty()) { SrPermissions.markAsked(context, p); activity?.let { permissions = SrPermissions.state(it) } }
                    else { asking = p; permissionLauncher.launch(names.toTypedArray()) }
                },
                onOpenSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) },
            )
        }
        SrScreen.ROUTE_PICK -> RoutePickerContent(planned, RoutePicker.inUse(planned, day.chosenRouteId()), onPick = { r -> scope.launch { day.chooseRoute(r.routeId); screen = SrScreen.HOME } })
        SrScreen.HOME -> if (!loaded) {
            AronScreen(Modifier.fillMaxSize()) {} // the page background until the local day is read (no splash, no spinner)
        } else if (data.freshness is BundleFreshness.Missing && !skipFirstBundle) {
            // F-SR-001: a phone with no bundle yet shows the first-bundle download (resumable); check-in never waits for it.
            val outcome by day.bundleOutcome.collectAsState()
            FirstBundleContent(
                state = when {
                    data.downloading -> FirstBundleState.DOWNLOADING
                    outcome == BundleOutcome.OFFLINE -> FirstBundleState.OFFLINE
                    outcome == BundleOutcome.AUTH_REQUIRED -> FirstBundleState.AUTH
                    outcome == null && !data.downloading -> FirstBundleState.DOWNLOADING
                    else -> FirstBundleState.FAILED
                },
                onRetry = { scope.launch { startBundleDownload(); day.taskBoard.load() } },
                onSkip = { skipFirstBundle = true },
            )
        } else {
            val header = HomeModel.header("SR", user.fullName, user.username, data.route?.name, data.route?.visitKind, data.businessDate)
            val snap = homeSnap
            var photoNotice by rememberSaveable { mutableStateOf(false) }
            if (photoNotice) {
                com.aktcl.aron.core.ui.aron.AronDialog(
                    stringResource(R.string.sr_photo_title), stringResource(R.string.sr_photo_body), stringResource(R.string.sr_ok),
                    onConfirm = { photoNotice = false }, onDismiss = { photoNotice = false },
                )
            }
            HomeContent(
                header, HomeTiles.resolve(emptySet(), tasks.openCount), data.freshness, user.offline, health ?: deviceHealth,
                onTile = { t ->
                    when (t) {
                        HomeTile.ATTENDANCE -> screen = SrScreen.ATTENDANCE
                        HomeTile.STOCK -> { day.forgetStock(); screen = SrScreen.STOCK }
                        HomeTile.SALE -> screen = if (day.visitSession.current.value != null) SrScreen.VISIT else SrScreen.PICKER
                        HomeTile.TASKS -> screen = SrScreen.TASKS
                        HomeTile.OUTLET -> screen = SrScreen.OUTLET_MENU
                        HomeTile.MEMO -> screen = SrScreen.MEMO
                        HomeTile.SUMMARY -> screen = SrScreen.SUMMARY
                        HomeTile.SALES_SUBMIT -> screen = SrScreen.SUBMIT
                        HomeTile.SALES_JOURNEY -> screen = SrScreen.JOURNEY
                        HomeTile.KPI -> screen = SrScreen.KPI
                        HomeTile.TUTORIAL -> screen = SrScreen.TUTORIAL
                        // Photo Capture belongs to the gift and campaign programmes docs/27 defers: the tile shows, the tap says so
                        HomeTile.PHOTO_CAPTURE -> photoNotice = true
                        else -> onOtherTile(t)
                    }
                },
                onSettings = { screen = SrScreen.SETTINGS },
                banner = if (data.downloading) stringResource(R.string.sr_bundle_downloading) else null,
                hero = snap?.hero, kpis = snap?.kpis, money = snap?.money, loading = snap == null,
                dock = snap?.let { HomeDayModel.dock(it.dayState) },
                sync = sync, onSync = openSync,
                sunlight = sunlight, onSunlight = onSunlight,
                routeKindLabel = routeKindText(data.route?.visitKind),
                reauthRequired = user.reauthRequired, updateRequired = user.updateRequired,
                noRoute = data.route == null && data.freshness !is BundleFreshness.Missing,
            )
        }
        SrScreen.ATTENDANCE -> SrFrame(stringResource(HomeR.string.hm_tile_attendance), goBack, sync, openSync, icon = CoreR.drawable.aron_art_attendance) {
            PermissionGate(GatedFeature.ATTENDANCE, onBack = goBack) {
                val who = HomeModel.header("SR", user.fullName, user.username, data.route?.name, data.route?.visitKind, data.businessDate)
                AttendanceContent(
                    attendance, "17:00", onCheckIn = { scope.launch { day.attendance.checkIn(); resumeTick++ } }, onCheckOut = { scope.launch { day.attendance.checkOut(); resumeTick++ } },
                    userTitle = who.title, userSubtitle = com.aktcl.aron.core.ui.localizedDigits(who.subtitle),
                )
            }
        }
        SrScreen.SETTINGS -> {
            val update = updateShell?.state?.collectAsState()?.value
            // The switch's stored value is a SharedPreferences read: loaded off the main thread, row shown once known.
            var wifiOnly by rememberSaveable { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(mediaShell) { if (wifiOnly == null) wifiOnly = mediaShell?.let { m -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { m.wifiOnly.wifiOnly() } } }
            SettingsContent(
                versionText, onLanguageSelect, onLogout,
                onUpdate = updateShell?.let { u -> { u.openPage() } },
                updateAvailable = update is com.aktcl.aron.core.system.update.UpdateState.Available,
                photosWifiOnly = wifiOnly,
                onPhotosWifiOnly = { on -> wifiOnly = on; mediaShell?.setWifiOnly(on) },
                onSupport = shell?.let { { screen = SrScreen.SUPPORT } },
                onBack = goBack, sunlight = sunlight, onSunlight = onSunlight, sync = sync, onSync = openSync, health = health ?: deviceHealth,
            )
        }
        SrScreen.SUPPORT -> SrFrame(stringResource(HomeR.string.set_support), goBack, sync, openSync) { shell?.let { SupportHost(it, day.userId, versionText) } }
        SrScreen.STOCK -> SrFrame(stringResource(HomeR.string.hm_tile_stock), goBack, sync, openSync, printer = day.printerManager, icon = CoreR.drawable.aron_art_stock) { StockHost(day) }
        SrScreen.PICKER -> SrFrame(stringResource(HomeR.string.hm_tile_sale), goBack, sync, openSync, icon = CoreR.drawable.aron_art_sale) {
            PermissionGate(GatedFeature.SALE, onBack = goBack) {
                var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
                var opening by remember { mutableStateOf(false) }
                var skipping by rememberSaveable { mutableStateOf(false) }
                val all = OutletPicker.rows(data.outlets)
                Column {
                    // Skip: an outlet marked not reached from the list, no fix, no geo gate, not a visit (F-SR-057).
                    AronSecondaryButton(
                        stringResource(if (skipping) R.string.sr_skip_cancel else R.string.sr_skip_outlet), { skipping = !skipping },
                        Modifier.padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S),
                    )
                    OutletPickerContent(OutletPicker.rows(data.outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r ->
                        if (skipping) { skipOutlet = r.outlet; skipping = false; screen = SrScreen.SKIP }
                        else if (!opening) {
                            opening = true
                            scope.launch {
                                runCatching { day.visitFlow.open(day.visitOutlet(r.outlet)) }
                                opening = false; screen = SrScreen.VISIT
                            }
                        }
                    })
                }
            }
        }
        SrScreen.VISIT -> SrFrame(stringResource(HomeR.string.hm_tile_sale), goBack, sync, openSync, icon = CoreR.drawable.aron_art_sale) {
            PermissionGate(GatedFeature.SALE, onBack = goBack) {
                val st by day.visitFlow.state.collectAsState()
                val open by day.visitSession.current.collectAsState()
                if (open != null) {
                    SaleHost(day, sunlight, onSunlight, onNoSale = { screen = SrScreen.NO_SALE }, onDone = { screen = SrScreen.HOME })
                } else {
                    VisitCheckContent(
                        st, onRefresh = { scope.launch { day.visitFlow.refresh() } }, onForceSale = { screen = SrScreen.FORCE },
                        onRetry = { scope.launch { day.visitFlow.retryCommit() } },
                        onMap = (st as? VisitUiState.NeedsDecision)?.takeIf { it.outlet.lat != null && it.outlet.lng != null }?.let { d ->
                            { context.startActivity(OutletMapActivity.intent(context, d.outlet.name, d.outlet.lat!!, d.outlet.lng!!, d.outlet.radiusM, d.phoneLat, d.phoneLng)) }
                        },
                    )
                }
            }
        }
        SrScreen.NO_SALE -> SrFrame(stringResource(R.string.sr_no_sale), goBack, sync, openSync) { NoSaleHost(day, onDone = { screen = SrScreen.HOME }) }
        SrScreen.SKIP -> SrFrame(stringResource(R.string.sr_skip_outlet), goBack, sync, openSync) {
            skipOutlet?.let { o -> SkipHost(day, o, onDone = { screen = SrScreen.HOME }) } ?: LaunchedEffect(Unit) { screen = SrScreen.PICKER }
        }
        SrScreen.MEMO -> SrFrame(stringResource(HomeR.string.hm_tile_memo), goBack, sync, openSync, printer = day.printerManager, icon = CoreR.drawable.aron_art_memo) {
            MemoHost(day, sunlight, onSunlight, onEdit = { editMemo = it; screen = SrScreen.EDIT })
        }
        SrScreen.EDIT -> SrFrame(stringResource(HomeR.string.hm_tile_memo), goBack, sync, openSync, icon = CoreR.drawable.aron_art_memo) { EditHost(day, editMemo, sunlight, onSunlight, onDone = { screen = SrScreen.MEMO }) }
        SrScreen.SUMMARY -> SrFrame(stringResource(com.aktcl.aron.feature.memo.R.string.summary_title), goBack, sync, openSync, printer = day.printerManager, icon = CoreR.drawable.aron_art_summary) { SummaryHost(day, sunlight, onSunlight) }
        SrScreen.SUBMIT -> SrFrame(stringResource(HomeR.string.hm_tile_sales_submit), goBack, sync, openSync, icon = CoreR.drawable.aron_art_sales_submit) { SubmitHost(day) }
        SrScreen.JOURNEY -> SrFrame(stringResource(HomeR.string.hm_tile_sales_journey), goBack, sync, openSync, icon = CoreR.drawable.aron_art_sales_journey) { JourneyHost(day) }
        SrScreen.KPI -> SrFrame(stringResource(HomeR.string.hm_tile_kpi), goBack, sync, openSync, icon = CoreR.drawable.aron_art_kpi) { KpiHost(day) }
        SrScreen.TUTORIAL -> TutorialHost(day, onBack = goBack)
        SrScreen.FORCE -> SrFrame(stringResource(HomeR.string.hm_tile_sale), goBack, sync, openSync, icon = CoreR.drawable.aron_art_sale) {
            val st by day.visitFlow.state.collectAsState()
            val capture = remember { day.newCapture("outlet_capture") }
            val cap by capture.state.collectAsState()
            var reason by remember { mutableStateOf<ForceReason?>(null) }
            var missing by remember { mutableStateOf<Set<ForceSaleMissing>>(emptySet()) }
            val decision = st as? VisitUiState.NeedsDecision
            if (decision == null) {
                LaunchedEffect(Unit) { screen = SrScreen.VISIT }
            } else {
                val controller = remember { day.forceSaleController(capture) { activity?.let { SrPermissions.state(it).canOpenVisit } ?: true } }
                ForceSaleContent(
                    selected = reason, capture = cap, missing = missing,
                    noOutletLocation = decision.result.verdict == com.aktcl.aron.rules.GeoVerdict.NO_OUTLET_LOCATION,
                    onReason = { reason = it }, onShutter = { scope.launch { capture.shutter() } },
                    onConfirm = {
                        scope.launch {
                            when (val r = controller.confirm(reason, decision.outlet)) {
                                is ForceSaleResult.Invalid -> missing = r.missing
                                is ForceSaleResult.Started -> screen = SrScreen.VISIT
                                ForceSaleResult.NotAvailable -> screen = SrScreen.VISIT
                            }
                        }
                    },
                )
            }
        }
        SrScreen.TASKS -> SrFrame(stringResource(R.string.sr_tasks_title), goBack, sync, openSync) {
            LaunchedEffect(Unit) { day.taskBoard.load() }
            TaskContent(tasks, onResolve = { id -> scope.launch { day.taskBoard.resolve(id) } })
        }
        SrScreen.OWN_REQUESTS -> SrFrame(stringResource(HomeR.string.hm_tile_outlet), goBack, sync, openSync, icon = CoreR.drawable.aron_art_outlet) {
            var rows by remember { mutableStateOf<List<com.aktcl.aron.feature.outlet.OwnRequestRow>?>(null) }
            LaunchedEffect(Unit) { rows = runCatching { day.ownRequests() }.getOrDefault(emptyList()) }
            rows?.let { com.aktcl.aron.feature.outlet.OwnRequestsContent(it) }
        }
        SrScreen.OUTLET_MENU -> SrFrame(stringResource(HomeR.string.hm_tile_outlet), goBack, sync, openSync, icon = CoreR.drawable.aron_art_outlet) {
            OutletMenuContent(onOwnRequests = { screen = SrScreen.OWN_REQUESTS }, onKind = { k ->
                day.requestKind = k
                screen = if (k == OutletRequestKind.NEW) SrScreen.REQUEST_FORM.also { day.requestOutlet = null } else SrScreen.REQUEST_OUTLET
            })
        }
        SrScreen.REQUEST_OUTLET -> SrFrame(stringResource(HomeR.string.hm_tile_outlet), goBack, sync, openSync, icon = CoreR.drawable.aron_art_outlet) {
            val all = OutletPicker.rows(data.outlets)
            var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
            OutletPickerContent(OutletPicker.rows(data.outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r -> day.requestOutlet = r.outlet; screen = SrScreen.REQUEST_FORM })
        }
        SrScreen.REQUEST_FORM -> SrFrame(stringResource(HomeR.string.hm_tile_outlet), goBack, sync, openSync, icon = CoreR.drawable.aron_art_outlet) {
            PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = goBack) {
                RequestHost(day, data.outlets, onDone = { screen = SrScreen.OUTLET_MENU })
            }
        }
        }
    }
    if (syncSheet) {
        SrSyncSheet(
            snapshot = syncSnap, chip = sync, sunlight = sunlight, onSunlight = onSunlight,
            onSyncNow = { day.requestSyncNow(); resumeTick++; syncSheet = false },
            onSupport = shell?.let { { syncSheet = false; screen = SrScreen.SUPPORT } },
            onDismiss = { syncSheet = false },
        )
    }
}

/** AKTCL's route kind wording, kept in English on every language (screens-sr s2 header note). */
@Composable
private fun routeKindText(wire: String?): String? = when (RouteKind.of(wire)) {
    RouteKind.DAILY -> stringResource(R.string.sr_route_kind_daily)
    RouteKind.THREE_F -> stringResource(R.string.sr_route_kind_3f)
    RouteKind.TWO_F -> stringResource(R.string.sr_route_kind_2f)
    null -> null
}

/**
 * The sync detail sheet (screens-sr s19): the chip's state in full, the sun switch (one tap from every screen), the
 * pending count, the newest ack, "sync now" (disabled offline with its reason) and the support shortcut. It reads the
 * same counters as the chip; no polling, no dialog, never blocking.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SrSyncSheet(snapshot: SrSyncSnapshot?, chip: SyncChipState?, sunlight: Boolean, onSunlight: (Boolean) -> Unit, onSyncNow: () -> Unit, onSupport: (() -> Unit)?, onDismiss: () -> Unit) {
    val c = LocalAronColors.current
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surfaceSolid, contentColor = c.textPrimary) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Text(stringResource(R.string.sr_sync_title), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            // the same chip as the screens show (Home falls back to Offline for an offline-unlocked session before the first read)
            AronStatusChip(chip ?: SyncChipState.Synced, form = ChipForm.Full)
            AronListRow(
                stringResource(HomeR.string.hm_sunlight), Modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(sunlight) },
                subtitle = stringResource(HomeR.string.set_sunlight_hint), onClick = { onSunlight(!sunlight) },
                leading = { Icon(AronIcons.Sun, contentDescription = null, tint = c.accent, modifier = Modifier.size(AronTokens.Size.Icon)) },
                trailingContent = {
                    Switch(
                        sunlight, onCheckedChange = onSunlight, modifier = Modifier.clearAndSetSemantics { },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.textOnAccent, uncheckedTrackColor = c.stateDisabledFill, uncheckedThumbColor = c.textSecondary, uncheckedBorderColor = c.borderSolid),
                    )
                },
                flat = true,
            )
            val pending = snapshot?.pending ?: 0
            Text(
                if (pending > 0) stringResource(R.string.sr_sync_pending, localizedNumber(pending.toLong())) else stringResource(R.string.sr_sync_all_done),
                style = MaterialTheme.typography.bodyLarge, color = c.textPrimary,
            )
            Text(
                snapshot?.lastSyncText?.let { stringResource(R.string.sr_sync_last, it) } ?: stringResource(HomeR.string.hm_health_never_synced),
                style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
            )
            val online = snapshot?.online ?: false
            if (!online) Text(stringResource(R.string.sr_sync_offline_reason), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            AronPrimaryButton(stringResource(R.string.sr_sync_now), onSyncNow, enabled = online, icon = AronIcons.Sync)
            if (onSupport != null) AronTextButton(stringResource(HomeR.string.set_support), onSupport, Modifier.fillMaxWidth())
            Box(Modifier.size(AronTokens.Space.Xl))
        }
    }
}

@Composable
private fun RequestHost(day: SrDay, outlets: List<OutletEntity>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kind = day.requestKind
    val outlet = day.requestOutlet
    var form by remember(kind, outlet?.outletId) {
        mutableStateOf(
            OutletRequestForm(
                kind = kind, outletId = outlet?.outletId, requestUuid = day.outletRequests.newRequestUuid(), currentClusterId = outlet?.clusterId,
                name = if (kind == OutletRequestKind.INFO) outlet?.name else null, ownerName = if (kind == OutletRequestKind.INFO) outlet?.ownerName else null,
                mobile = if (kind == OutletRequestKind.INFO) outlet?.contactNumber else null, openDueMtk = outlet?.openDueMtk ?: 0,
            ),
        )
    }
    val capture = remember(kind, outlet?.outletId) { day.newCapture("outlet_capture") }
    val cap by capture.state.collectAsState()
    var errors by remember { mutableStateOf<Set<RequestError>>(emptySet()) }
    var saved by remember { mutableStateOf(false) }
    val clusters = remember(outlets) { outlets.map { ClusterOption(it.clusterId, it.clusterName) }.distinctBy { it.clusterId } }
    OutletRequestContent(
        form = form, errors = errors, clusters = clusters, capture = cap, saved = saved, warnOpenDues = form.openDueMtk > 0,
        onChange = { form = it }, onShutter = { scope.launch { capture.shutter() } },
        onSave = {
            scope.launch {
                val toSend = form.copy(fix = cap.fix, photoUuids = listOfNotNull(cap.photo?.photoUuid))
                when (val r = day.outletRequests.submit(toSend)) {
                    is RequestValidation.Invalid -> { errors = r.errors; saved = false }
                    is RequestValidation.Ok -> { errors = emptySet(); saved = true; onDone() }
                }
            }
        },
    )
}

/** Stock issue and the slip (F-SR-013/014); the printer icon and banner are on the frame, the hold is the frame's too. */
@Composable
private fun StockHost(day: SrDay) {
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<com.aktcl.aron.feature.stock.StockLoad?>(null) }
    var version by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<StockMessage?>(null) }
    var unprinted by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { load = day.stockLoad(); unprinted = day.slipNotPrinted() }
    val att by day.stockAttempt.collectAsState()
    LaunchedEffect(att) { unprinted = day.slipNotPrinted() }
    val l = load ?: return
    version.let {}
    StockContent(
        rows = l.rows, totals = l.totals, message = message, slipNotPrinted = unprinted,
        onIssue = { id, q -> l.setEntered(id, q.toLong()); version++ },
        onSave = {
            scope.launch {
                when (val out = l.save(day.currentMs(), day.metaProvider.meta(0L))) {
                    is SaveOutcome.Saved -> runCatching { day.capture.recordStock(out.movements) }
                        .onSuccess { l.committed(out, day.currentMs()); message = StockMessage.SAVED; unprinted = day.slipNotPrinted(); day.requestSync() }
                        .onFailure { l.commitFailed(); message = StockMessage.SAVE_FAILED }
                    is SaveOutcome.Refused -> message = if (out.reason == SaveRefusal.NOTHING_ENTERED) StockMessage.NOTHING_ENTERED else StockMessage.REFUSED_SAME_VALUES
                }
                version++
            }
        },
        // Print never blocks Save: it is offered after a Save and may be retried later (Q-UI-03).
        onPrint = if (unprinted && PermissionPolicy.allowed(GatedFeature.PRINT, AndroidPermissions.snapshotOf(LocalContext.current))) ({ day.printUnprintedStock() }) else null,
    )
}
