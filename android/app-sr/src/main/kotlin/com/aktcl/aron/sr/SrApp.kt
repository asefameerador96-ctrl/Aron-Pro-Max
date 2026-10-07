package com.aktcl.aron.sr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.system.permission.AndroidPermissions
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionGate
import com.aktcl.aron.core.system.permission.PermissionPolicy
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.home.AppPermission
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.HomeContent
import com.aktcl.aron.feature.home.HomeModel
import com.aktcl.aron.feature.home.HomeTile
import com.aktcl.aron.feature.home.HomeTiles
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.PermissionOnboardingContent
import com.aktcl.aron.feature.home.PlannedRoute
import com.aktcl.aron.feature.home.RoutePicker
import com.aktcl.aron.feature.home.RoutePickerContent
import com.aktcl.aron.feature.home.SettingsContent
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

enum class SrScreen { PERMISSIONS, ROUTE_PICK, HOME, ATTENDANCE, STOCK, PICKER, VISIT, FORCE, TASKS, SETTINGS, SUPPORT, OUTLET_MENU, OWN_REQUESTS, REQUEST_OUTLET, REQUEST_FORM, NO_SALE, SKIP, MEMO, EDIT, SUMMARY, SUBMIT, JOURNEY, KPI }

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
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var screen by rememberSaveable { mutableStateOf(SrScreen.HOME) }
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

    // Start: local data first (never waits), then the bundle in the background, then a refresh of what Home shows.
    LaunchedEffect(Unit) {
        day.recoverPrinting(); day.resumeMedia()
        day.reload(); day.nextSequenceFromStore(); day.restoreOpenVisit(); day.taskBoard.load()
        day.attendance.restore(day.attendanceToday())
        if (screen == SrScreen.HOME && permissions.toAsk.isNotEmpty()) screen = SrScreen.PERMISSIONS
        launch { startBundleDownload(); day.taskBoard.load() }
    }
    // No polling: the day state refreshes on resume and at the real boundaries (17:00 check-out gate, Dhaka midnight).
    var boundary by remember { mutableStateOf(0) }
    LaunchedEffect(boundary) {
        delay(day.millisToNextBoundary())
        day.attendance.tick(); day.reload(); day.taskBoard.load()
        boundary++
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        scope.launch { day.attendance.tick(); day.reload(); day.taskBoard.load() }
    }
    // Several routes planned today: the SR picks one before anything else (F-SR-065).
    val planned = data.routes.map { PlannedRoute(it.routeId, it.name, it.plannedToday, it.sequenceNo) }
    LaunchedEffect(planned, screen) {
        if (screen == SrScreen.HOME && RoutePicker.needsChoice(planned, day.chosenRouteId())) screen = SrScreen.ROUTE_PICK
    }
    BackHandler(enabled = screen != SrScreen.HOME && screen != SrScreen.PERMISSIONS) {
        screen = when (screen) { SrScreen.FORCE -> SrScreen.VISIT; SrScreen.SUPPORT -> SrScreen.SETTINGS; SrScreen.OWN_REQUESTS -> SrScreen.OUTLET_MENU; SrScreen.NO_SALE -> SrScreen.VISIT; SrScreen.SKIP -> SrScreen.PICKER; SrScreen.EDIT -> SrScreen.MEMO; SrScreen.REQUEST_FORM -> SrScreen.OUTLET_MENU; SrScreen.REQUEST_OUTLET -> SrScreen.OUTLET_MENU; else -> SrScreen.HOME }
    }

    // The camera draws only while a capture is open (and sits behind the camera permission gate of core-system).
    com.aktcl.aron.core.printing.ui.PrintAttemptDialogs(stockAttempt, onAnswer = day::answerStockPrint, onClose = day::closeStockAttempt)
    when (screen) {
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
        SrScreen.HOME -> {
            val header = HomeModel.header("SR", user.fullName, user.username, data.route?.name, data.route?.visitKind, data.businessDate)
            HomeContent(
                header, HomeTiles.resolve(emptySet(), tasks.openCount), data.freshness, user.offline, health,
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
                        else -> onOtherTile(t)
                    }
                },
                onSettings = { screen = SrScreen.SETTINGS },
                banner = if (data.downloading) stringResource(R.string.sr_bundle_downloading) else null,
            )
        }
        SrScreen.ATTENDANCE -> PermissionGate(GatedFeature.ATTENDANCE, onBack = { screen = SrScreen.HOME }) {
            AttendanceContent(attendance, "17:00", onCheckIn = { scope.launch { day.attendance.checkIn() } }, onCheckOut = { scope.launch { day.attendance.checkOut() } })
        }
        SrScreen.SETTINGS -> SettingsContent(versionText, onLanguageSelect, onLogout, onSupport = shell?.let { { screen = SrScreen.SUPPORT } })
        SrScreen.SUPPORT -> shell?.let { SupportHost(it, day.userId, versionText) }
        SrScreen.STOCK -> StockHost(day)
        SrScreen.PICKER -> PermissionGate(GatedFeature.SALE, onBack = { screen = SrScreen.HOME }) {
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
        SrScreen.VISIT -> PermissionGate(GatedFeature.SALE, onBack = { screen = SrScreen.HOME }) {
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
        SrScreen.NO_SALE -> NoSaleHost(day, onDone = { screen = SrScreen.HOME })
        SrScreen.SKIP -> skipOutlet?.let { o -> SkipHost(day, o, onDone = { screen = SrScreen.HOME }) } ?: LaunchedEffect(Unit) { screen = SrScreen.PICKER }
        SrScreen.MEMO -> MemoHost(day, sunlight, onSunlight, onEdit = { editMemo = it; screen = SrScreen.EDIT })
        SrScreen.EDIT -> EditHost(day, editMemo, sunlight, onSunlight, onDone = { screen = SrScreen.MEMO })
        SrScreen.SUMMARY -> SummaryHost(day, sunlight, onSunlight)
        SrScreen.SUBMIT -> SubmitHost(day)
        SrScreen.JOURNEY -> JourneyHost(day)
        SrScreen.KPI -> KpiHost(day)
        SrScreen.FORCE -> {
            val st by day.visitFlow.state.collectAsState()
            val capture = remember { day.newCapture("outlet_capture") }
            val cap by capture.state.collectAsState()
            var reason by remember { mutableStateOf<ForceReason?>(null) }
            var missing by remember { mutableStateOf<Set<ForceSaleMissing>>(emptySet()) }
            val decision = st as? VisitUiState.NeedsDecision
            if (decision == null) { LaunchedEffect(Unit) { screen = SrScreen.VISIT }; return }
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
        SrScreen.TASKS -> {
            LaunchedEffect(Unit) { day.taskBoard.load() }
            TaskContent(tasks, onResolve = { id -> scope.launch { day.taskBoard.resolve(id) } })
        }
        SrScreen.OWN_REQUESTS -> {
            var rows by remember { mutableStateOf<List<com.aktcl.aron.feature.outlet.OwnRequestRow>?>(null) }
            LaunchedEffect(Unit) { rows = runCatching { day.ownRequests() }.getOrDefault(emptyList()) }
            rows?.let { com.aktcl.aron.feature.outlet.OwnRequestsContent(it) }
        }
        SrScreen.OUTLET_MENU -> OutletMenuContent(onOwnRequests = { screen = SrScreen.OWN_REQUESTS }, onKind = { k ->
            day.requestKind = k
            screen = if (k == OutletRequestKind.NEW) SrScreen.REQUEST_FORM.also { day.requestOutlet = null } else SrScreen.REQUEST_OUTLET
        })
        SrScreen.REQUEST_OUTLET -> {
            val all = OutletPicker.rows(data.outlets)
            var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
            OutletPickerContent(OutletPicker.rows(data.outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r -> day.requestOutlet = r.outlet; screen = SrScreen.REQUEST_FORM })
        }
        SrScreen.REQUEST_FORM -> PermissionGate(GatedFeature.OUTLET_REQUEST, onBack = { screen = SrScreen.OUTLET_MENU }) {
            RequestHost(day, data.outlets, onDone = { screen = SrScreen.OUTLET_MENU })
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

@Composable
private fun StockHost(day: SrDay) {
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<com.aktcl.aron.feature.stock.StockLoad?>(null) }
    var version by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<StockMessage?>(null) }
    var unprinted by remember { mutableStateOf(true) }
    val pm = day.printerManager
    LaunchedEffect(Unit) { load = day.stockLoad(); unprinted = day.slipNotPrinted() }
    val att by day.stockAttempt.collectAsState()
    LaunchedEffect(att) { unprinted = day.slipNotPrinted() }
    com.aktcl.aron.core.printing.ui.HoldPrinter(pm)
    val l = load ?: return
    version.let {}
    Column {
        androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = AronTokens.Space.L)) { com.aktcl.aron.core.printing.ui.PrinterIcon(pm) }
        com.aktcl.aron.core.printing.ui.PrinterBanner(pm)
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
            onPrint = if (unprinted && PermissionPolicy.allowed(GatedFeature.PRINT, AndroidPermissions.snapshotOf(androidx.compose.ui.platform.LocalContext.current))) ({ day.printUnprintedStock() }) else null,
        )
    }
}
