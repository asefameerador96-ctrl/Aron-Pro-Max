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
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
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

enum class SrScreen { PERMISSIONS, ROUTE_PICK, HOME, ATTENDANCE, STOCK, PICKER, VISIT, FORCE, TASKS, SETTINGS, OUTLET_MENU, REQUEST_OUTLET, REQUEST_FORM }

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
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var screen by rememberSaveable { mutableStateOf(SrScreen.HOME) }
    val scope = rememberCoroutineScope()
    val data by day.dayData.collectAsState()
    val tasks by day.taskBoard.state.collectAsState()
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
        day.reload(); day.nextSequenceFromStore(); day.restoreOpenVisit(); day.taskBoard.load()
        day.attendance.restore(day.attendanceToday())
        if (screen == SrScreen.HOME && permissions.toAsk.isNotEmpty()) screen = SrScreen.PERMISSIONS
        launch { startBundleDownload(); day.taskBoard.load() }
    }
    // Once a minute: the 17:00 check-out gate, the business date, the bundle age and new tasks follow the trusted clock.
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); day.attendance.tick(); day.reload(); day.taskBoard.load() }
    }
    // Several routes planned today: the SR picks one before anything else (F-SR-065).
    val planned = data.routes.map { PlannedRoute(it.routeId, it.name, it.plannedToday, it.sequenceNo) }
    LaunchedEffect(planned, screen) {
        if (screen == SrScreen.HOME && RoutePicker.needsChoice(planned, day.chosenRouteId())) screen = SrScreen.ROUTE_PICK
    }
    BackHandler(enabled = screen != SrScreen.HOME && screen != SrScreen.PERMISSIONS) {
        screen = when (screen) { SrScreen.FORCE -> SrScreen.VISIT; SrScreen.REQUEST_FORM -> SrScreen.OUTLET_MENU; SrScreen.REQUEST_OUTLET -> SrScreen.OUTLET_MENU; else -> SrScreen.HOME }
    }

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
                        else -> onOtherTile(t)
                    }
                },
                onSettings = { screen = SrScreen.SETTINGS },
                banner = if (data.downloading) stringResource(R.string.sr_bundle_downloading) else null,
            )
        }
        SrScreen.ATTENDANCE -> AttendanceContent(attendance, "17:00", onCheckIn = { scope.launch { day.attendance.checkIn() } }, onCheckOut = { scope.launch { day.attendance.checkOut() } })
        SrScreen.SETTINGS -> SettingsContent(versionText, onLanguageSelect, onLogout)
        SrScreen.STOCK -> StockHost(day)
        SrScreen.PICKER -> {
            var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
            var opening by remember { mutableStateOf(false) }
            val all = OutletPicker.rows(data.outlets)
            OutletPickerContent(OutletPicker.rows(data.outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r ->
                if (!opening) {
                    opening = true
                    scope.launch {
                        runCatching { day.visitFlow.open(day.visitOutlet(r.outlet)) }
                        opening = false; screen = SrScreen.VISIT
                    }
                }
            })
        }
        SrScreen.VISIT -> {
            val st by day.visitFlow.state.collectAsState()
            val open by day.visitSession.current.collectAsState()
            if (open != null) {
                Column(Modifier.padding(AronTokens.Space.L)) {
                    AronBanner(stringResource(R.string.sr_visit_open), kind = BannerKind.Info)
                    // Placeholder until the sale screens (android-sr-b) own the visit end: no sale yet, so the call is abandoned.
                    AronPrimaryButton(stringResource(R.string.sr_visit_close), { scope.launch { day.closeVisitAbandoned(); screen = SrScreen.HOME } })
                }
            } else {
                VisitCheckContent(
                    st, onRefresh = { scope.launch { day.visitFlow.refresh() } }, onForceSale = { screen = SrScreen.FORCE },
                    onRetry = { scope.launch { day.visitFlow.retryCommit() } }, onMap = null,
                )
            }
        }
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
        SrScreen.OUTLET_MENU -> OutletMenuContent(onKind = { k ->
            day.requestKind = k
            screen = if (k == OutletRequestKind.NEW) SrScreen.REQUEST_FORM.also { day.requestOutlet = null } else SrScreen.REQUEST_OUTLET
        })
        SrScreen.REQUEST_OUTLET -> {
            val all = OutletPicker.rows(data.outlets)
            var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
            OutletPickerContent(OutletPicker.rows(data.outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r -> day.requestOutlet = r.outlet; screen = SrScreen.REQUEST_FORM })
        }
        SrScreen.REQUEST_FORM -> RequestHost(day, data.outlets, onDone = { screen = SrScreen.OUTLET_MENU })
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
    LaunchedEffect(Unit) { load = day.stockLoad() }
    val l = load ?: return
    version.let {}
    StockContent(
        rows = l.rows, totals = l.totals, message = message, slipNotPrinted = true,
        onIssue = { id, q -> l.setEntered(id, q.toLong()); version++ },
        onSave = {
            scope.launch {
                when (val out = l.save(day.currentMs(), day.metaProvider.meta(0L))) {
                    is SaveOutcome.Saved -> runCatching { day.capture.recordStock(out.movements) }
                        .onSuccess { l.committed(out, day.currentMs()); message = StockMessage.SAVED; day.requestSync() }
                        .onFailure { l.commitFailed(); message = StockMessage.SAVE_FAILED }
                    is SaveOutcome.Refused -> message = if (out.reason == SaveRefusal.NOTHING_ENTERED) StockMessage.NOTHING_ENTERED else StockMessage.REFUSED_SAME_VALUES
                }
                version++
            }
        },
    )
}
