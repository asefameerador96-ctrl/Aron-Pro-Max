package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoFrame
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.core.database.repo.RouteDay
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionGate
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.feature.amo.AmoRoutePick
import com.aktcl.aron.feature.amo.CallPick
import com.aktcl.aron.feature.amo.CallRange
import com.aktcl.aron.feature.amo.AmoRoutePanel
import com.aktcl.aron.feature.amo.AmoOutletPanel
import com.aktcl.aron.feature.amo.ControlCall
import com.aktcl.aron.feature.amo.ControlCallMenu
import com.aktcl.aron.feature.amo.ManualOverrideCap
import com.aktcl.aron.feature.amo.ManualOverrideLimit
import com.aktcl.aron.feature.amo.amoVisitCheckTexts
import com.aktcl.aron.feature.outlet.ForceReason
import com.aktcl.aron.feature.outlet.ForceSaleMissing
import com.aktcl.aron.feature.outlet.ForceSaleResult
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitUiState
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.amo.R as AmoR

private enum class CcStep { PICK, CHECK, FORCE, ASSESS, JOINT, SURVEY, PRICE, SALE }
private enum class Panel { NONE, ROUTE, OUTLET }

/**
 * F-AMO-004/005/036: Control Call, Joint Call and the Sales tile (screenshots 17-23, 29-32). One picker screen holds the route and
 * retailer drop-downs, the LIVE indicator and the alphabet bar of the letters present; the range area under them is the SR's
 * [VisitFlow] verdict (radius check on the phone, airplane mode safe): out of range = the warning with Manual Override (the SR's Force
 * Sale with the outlet photo and a location-change request, capped per day) and Refresh; in range = the activity tiles. On the Sales
 * tile the check starts when the Sales tile is tapped and an out-of-range answer asks the force-sale reason first (D-AMO-MAN-08);
 * the reason lands on the visit (`geo.force_reason_code`, wired to `force_sale_reason` by the contract train).
 * Sale, Assessment, Survey and Price compliance are steps of this host.
 */
@Composable
fun ControlCallHost(
    day: AmoDay, onBack: () -> Unit, startWithSale: Boolean = false,
    /** `amo_control_call` (tiles: Sale, assessment, survey) or `amo_joint_call` (the SR call assessment tile, F-AMO-011). */
    kind: String = com.aktcl.aron.feature.amo.AmoVisitKind.CONTROL_CALL,
) {
    val joint = kind == com.aktcl.aron.feature.amo.AmoVisitKind.JOINT_CALL
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(CcStep.PICK) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var outletId by rememberSaveable { mutableStateOf<Long?>(null) }
    var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    var forceReason by rememberSaveable { mutableStateOf(ForceReason.LOCATION_CHANGE) }
    var days by remember { mutableStateOf<List<RouteDay>?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var calledToday by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showPrice by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    // the retailer whose range check THIS screen started: a verdict left in the shared visit flow by an earlier retailer or call is never shown for another (checker findings 2-4)
    var checked by rememberSaveable { mutableStateOf<Long?>(null) } // survives a rotation (the retailer id is saveable too)
    var lockNotice by remember { mutableStateOf<DayRefusal?>(null) }
    lockNotice?.let { AmoRefusalDialog(day, it, onOk = { lockNotice = null }) }
    LaunchedEffect(day) {
        submitted = runCatching { day.daySubmitted() }.getOrDefault(false)
        days = runCatching { day.routes() }.getOrDefault(emptyList()); day.nextSequenceFromStore()
        showPrice = day.priceComplianceTile()
        // Kill and relaunch: a committed visit with no close is the call in progress; with none, a saved CHECK/FORCE step is dead.
        day.restoreOpenVisit()
        if (day.visitSession.current.value == null && step != CcStep.PICK) { step = CcStep.PICK; outletId = null }
    }
    // A call already open (kill and relaunch): back on its screen, never a second visit; the drop-downs show its route and retailer.
    val open by day.visitSession.current.collectAsState()
    LaunchedEffect(open) {
        calledToday = com.aktcl.aron.core.common.orDefaultKeepingCancellation(emptySet()) { day.calledTodayOutletIds() }
        open?.let { v ->
            if (v.routeId != 0L) routeId = v.routeId
            outletId = v.outletId
            if (startWithSale && step == CcStep.PICK) step = CcStep.SALE
        }
    }
    // tso-manual X4a follow-up (R-W-23): a call whose close the route-day refused (the zone's Final Submit, or the supervisor
    // day's Sales Submit) ended on the phone with no close written; the picker shows why, never a crash
    var callEnded by remember { mutableStateOf<DayRefusal?>(null) }
    val leaveCall: () -> Unit = { scope.launch { callEnded = day.closeVisit(); step = CcStep.PICK; outletId = null; chip = OutletPicker.ALL_CHIP } }
    callEnded?.let { AmoRefusalDialog(day, it, onOk = { callEnded = null }) }
    // X4a fix round (R-W-23, checker low): the entry guard, as the SR sale host's. A call in progress whose route-day no longer takes
    // its records ends on the phone at once, before any step is drafted
    LaunchedEffect(open) {
        if (open != null) runCatching { day.endCallIfClosed() }.getOrNull()?.let { callEnded = it; step = CcStep.PICK; outletId = null }
    }
    val goBack: () -> Unit = {
        when {
            step == CcStep.PICK && panel != Panel.NONE -> panel = Panel.NONE
            step == CcStep.PICK -> if (open != null) leaveCall() else onBack()
            step == CcStep.CHECK || step == CcStep.FORCE -> { step = CcStep.PICK; outletId = null }
            else -> step = CcStep.PICK
        }
    }
    androidx.activity.compose.BackHandler(onBack = goBack)
    val titleRes = when { joint -> AmoR.string.amo_cp_joint_title; startWithSale -> AmoR.string.amo_cp_sales_title; else -> AmoR.string.amo_cc_title }
    AmoFrame(stringResource(titleRes), goBack, icon = if (joint) com.aktcl.aron.core.ui.R.drawable.aron_ic_joint_call else com.aktcl.aron.core.ui.R.drawable.aron_ic_control_call) {
        PermissionGate(GatedFeature.SALE, onBack = onBack) {
            val list = days
            when (step) {
                CcStep.PICK -> if (submitted && open == null) com.aktcl.aron.feature.amo.DaySubmittedNotice(onBack) else PickStep(
                    day, list.orEmpty(), kind, startWithSale, routeId, outletId, checked, chip, panel, calledToday, showPrice, opening,
                    onRoute = { routeId = it; outletId = null; chip = OutletPicker.ALL_CHIP; panel = Panel.NONE },
                    onOutlet = { o ->
                        outletId = o.outletId; panel = Panel.NONE; checked = null
                        // Control and Joint Call check the range as soon as the retailer is chosen; on Sales the tile starts the check
                        if (!startWithSale && !opening) { opening = true; scope.launch { if (day.openCall(o, kind) { lockNotice = it }) checked = o.outletId; opening = false } }
                    },
                    onChip = { chip = it }, onPanel = { panel = it },
                    onSalesTile = { o ->
                        // a call for this very retailer is already open (Back from the sale, a relaunch): go on to the sale, never a dead tile
                        if (open?.outletId == o.outletId) step = CcStep.SALE
                        else if (!opening) { opening = true; scope.launch { if (day.openCall(o, kind) { lockNotice = it }) checked = o.outletId; opening = false } }
                    },
                    onOverride = { forceReason = ForceReason.LOCATION_CHANGE; step = CcStep.FORCE },
                    onReason = { forceReason = it; step = CcStep.FORCE },
                    onSale = { step = CcStep.SALE }, onAssess = { step = CcStep.ASSESS }, onJoint = { step = CcStep.JOINT }, onSurvey = { step = CcStep.SURVEY }, onPrice = { step = CcStep.PRICE },
                    onNonStandard = { step = CcStep.CHECK },
                )
                CcStep.CHECK -> {
                    val st by day.visitFlow.state.collectAsState()
                    LaunchedEffect(st) {
                        if (st is VisitUiState.Open) step = if (startWithSale) CcStep.SALE else CcStep.PICK
                        // Sales path: an out-of-range answer after the card (location back on, Refresh) goes to the picker, whose reason dialog
                        // asks "why" before any override; the card never records a reason the AMO did not choose (D-AMO-CALLS-02, D-AMO-MAN-08)
                        else if (st is VisitUiState.NeedsDecision && startWithSale) { checked = outletId; step = CcStep.PICK }
                    }
                    // tso-manual X4a (R-W-11): the lock landed while the fix was read and the committer refused the call (nothing
                    // stored): the lock text instead of the generic failed commit and its retry
                    val failed = st as? VisitUiState.CommitFailed
                    var lockedOut by remember(failed) { mutableStateOf<DayRefusal?>(null) }
                    LaunchedEffect(failed) { lockedOut = day.commitRefusal(failed) }
                    lockedOut?.let { AmoRefusalDialog(day, it, onOk = { day.routeId = null; step = CcStep.PICK; outletId = null }) }
                    VisitCheckContent(
                        st, onRefresh = { scope.launch { day.visitFlow.refresh() } }, onForceSale = { if (startWithSale) { checked = outletId; step = CcStep.PICK } else { forceReason = ForceReason.LOCATION_CHANGE; step = CcStep.FORCE } },
                        onRetry = { scope.launch { day.visitFlow.retryCommit() } }, onMap = null,
                        texts = amoVisitCheckTexts(), onBack = { step = CcStep.PICK; outletId = null },
                        geoPanel = true, mapSlot = rememberAmoOutletMapSlot(day.online()),
                    )
                }
                CcStep.FORCE -> ManualOverrideStep(day, forceReason, onStarted = { step = if (startWithSale) CcStep.SALE else CcStep.PICK }, onBack = { step = CcStep.PICK })
                CcStep.JOINT -> JointCallStep(day, onSavedOk = { step = CcStep.PICK })
                CcStep.ASSESS -> PerfAssessmentStep(day, onSavedOk = leaveCall)
                CcStep.SALE -> AmoSaleHost(day, onDone = { step = if (startWithSale) CcStep.PICK else CcStep.PICK }, onCallEnded = { step = CcStep.PICK; outletId = null })
                CcStep.SURVEY -> SurveyStep(day, onDone = { step = CcStep.PICK })
                CcStep.PRICE -> PriceStep(day, onSaved = { step = CcStep.PICK })
            }
        }
    }
}

/** The picker screen (17-23, 29-30): drop-downs, alphabet bar, range area and the tiles of the call. */
@Composable
private fun PickStep(
    day: AmoDay, days: List<RouteDay>, kind: String, salesPath: Boolean, routeId: Long?, outletId: Long?, checked: Long?, chip: String, panel: Panel,
    calledToday: Set<Long>, showPrice: Boolean, opening: Boolean,
    onRoute: (Long) -> Unit, onOutlet: (OutletEntity) -> Unit, onChip: (String) -> Unit, onPanel: (Panel) -> Unit, onSalesTile: (OutletEntity) -> Unit,
    onOverride: () -> Unit, onReason: (ForceReason) -> Unit,
    onSale: () -> Unit, onAssess: () -> Unit, onJoint: () -> Unit, onSurvey: () -> Unit, onPrice: () -> Unit, onNonStandard: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val joint = kind == com.aktcl.aron.feature.amo.AmoVisitKind.JOINT_CALL
    val flowState by day.visitFlow.state.collectAsState()
    val open by day.visitSession.current.collectAsState()
    // only a verdict this screen asked for, for the retailer on screen
    val st: VisitUiState = if (checked != null && checked == outletId) flowState else VisitUiState.Idle
    val routes = ControlCall.routes(days)
    val outlets = ControlCall.outlets(days, routeId ?: -1L)
    val route = routes.firstOrNull { it.routeId == routeId }
    val outlet = outlets.firstOrNull { it.outletId == outletId }
    val rows = CallPick.rows(outlets, calledToday, chip)
    val selectedRow = CallPick.rows(outlets, calledToday).firstOrNull { it.outlet.outletId == outletId }
    when (panel) {
        Panel.ROUTE -> { AmoRoutePanel(routes, routeId, { onRoute(it.routeId) }); return }
        Panel.OUTLET -> { AmoOutletPanel(rows, outletId, { onOutlet(it.outlet) }); return }
        Panel.NONE -> Unit
    }
    if (routes.isEmpty()) { AmoRoutePick(routes, {}); return }
    // Blocked location, a failed commit or a mock block need the full check card
    val standard = st is VisitUiState.Idle || st is VisitUiState.ReadingFix || st is VisitUiState.NeedsDecision || st is VisitUiState.Open
    LaunchedEffect(st, outletId) { if (outletId != null && !standard) onNonStandard() }
    var asking by remember(outletId) { mutableStateOf(false) }
    val checking = outlet != null && (st is VisitUiState.ReadingFix || opening)
    val needs = st as? VisitUiState.NeedsDecision
    // a call open in the session (also one restored after a kill and relaunch, when the flow state is Idle) for the retailer on screen
    val callOpen = outlet != null && open?.outletId == outlet.outletId
    val range: CallRange = com.aktcl.aron.feature.amo.CallRangeRule.of(
        outlet?.outletId, callOpen, checking, needs?.takeIf { outlet != null && it.outlet.outletId == outlet.outletId }?.let { it.forceSaleAvailable to it.refreshLeft }, salesPath, checked,
    )
    // Sales tile: an out-of-range answer asks for the reason before the override photo (D-AMO-MAN-08)
    if (salesPath && asking) com.aktcl.aron.feature.amo.AmoForceReasonDialog(
        onReason = { code -> asking = false; onReason(if (code == "internet_problem") ForceReason.INTERNET_PROBLEM else ForceReason.LOCATION_CHANGE) },
        onDismiss = { asking = false },
    )
    val tiles = buildList {
        if (joint) add(com.aktcl.aron.feature.amo.MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_joint_call, AmoR.string.amo_cp_joint_tile, com.aktcl.aron.feature.amo.AmoCallTags.MENU_ASSESS, onJoint))
        else if (salesPath) add(com.aktcl.aron.feature.amo.MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_sale, AmoR.string.amo_cc_menu_sale, com.aktcl.aron.feature.amo.AmoCallTags.MENU_SALE, { outlet?.let(onSalesTile) }))
        else {
            add(com.aktcl.aron.feature.amo.MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_sale, AmoR.string.amo_cc_menu_sale, com.aktcl.aron.feature.amo.AmoCallTags.MENU_SALE, onSale))
            add(com.aktcl.aron.feature.amo.MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_assessment, AmoR.string.amo_cc_menu_assessment, com.aktcl.aron.feature.amo.AmoCallTags.MENU_ASSESS, onAssess))
            add(com.aktcl.aron.feature.amo.MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_survey, AmoR.string.amo_cc_menu_survey, com.aktcl.aron.feature.amo.AmoCallTags.MENU_SURVEY, onSurvey))
            if (showPrice) add(com.aktcl.aron.feature.amo.MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_price_compliance, AmoR.string.amo_cc_menu_price, com.aktcl.aron.feature.amo.AmoCallTags.MENU_PRICE, onPrice))
        }
    }
    com.aktcl.aron.feature.amo.AmoCallPickContent(
        routeText = route?.shortTitle, outletText = selectedRow?.label, outletHasBase = selectedRow?.hasBase == true,
        letters = CallPick.letters(outlets), chip = chip, live = outlet != null, range = range,
        enabledOutlet = route != null, locked = callOpen || checking, // L1: no retailer switch while a range check runs
        onOpenRoute = { onPanel(Panel.ROUTE) }, onOpenOutlet = { onPanel(Panel.OUTLET) }, onChip = onChip,
        onOverride = { if (salesPath) asking = true else onOverride() }, onRefresh = { scope.launch { runCatching { day.visitFlow.refresh() } } },
        below = { com.aktcl.aron.feature.amo.AmoMenuTiles(tiles) },
    )
}

/** F-AMO-005: the out-of-range answer. Outlet photo, location-change request, the call proceeds; the day's limit applies. */
@Composable
private fun ManualOverrideStep(day: AmoDay, reason: ForceReason, onStarted: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var capped by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(day) { capped = runCatching { ManualOverrideCap.reached(day.visitsToday(), day.manualOverrideMax()) }.getOrDefault(true) } // fails closed
    when (capped) {
        null -> Unit
        true -> ManualOverrideLimit(day.manualOverrideMax(), onBack)
        false -> {
            val st by day.visitFlow.state.collectAsState()
            val capture = remember { day.newCapture("outlet_capture") }
            val cap by capture.state.collectAsState()
            var missing by remember { mutableStateOf<Set<ForceSaleMissing>>(emptySet()) }
            var confirming by remember { mutableStateOf(false) }
            val decision = st as? VisitUiState.NeedsDecision
            if (decision == null) {
                LaunchedEffect(Unit) { onBack() }
            } else {
                val controller = remember {
                    day.forceSaleController(capture) { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
                }
                com.aktcl.aron.feature.amo.ManualOverrideContent(
                    capture = cap, missing = missing, onShutter = { scope.launch { capture.shutter() } },
                    onConfirm = {
                        if (!confirming) {
                            confirming = true
                            scope.launch {
                                try {
                                    when (val r = runCatching { controller.confirm(reason, decision.outlet) }.getOrDefault(ForceSaleResult.NotAvailable)) {
                                        is ForceSaleResult.Invalid -> missing = r.missing
                                        is ForceSaleResult.Started -> onStarted()
                                        ForceSaleResult.NotAvailable -> onBack()
                                    }
                                } finally { confirming = false }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** F-AMO-007/008/009: SR Perf. Assessment of the open Control Call. Save writes the records in one go and returns to the outlet picker (route kept). */
@Composable
private fun PerfAssessmentStep(day: AmoDay, onSavedOk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var brands by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.AmoBrand>?>(null) }
    var state by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<com.aktcl.aron.feature.amo.PerfState, Any?>(
        save = { listOf(it.present.toList(), it.oos.toList(), it.posm) },
        restore = { @Suppress("UNCHECKED_CAST") com.aktcl.aron.feature.amo.PerfState((it[0] as List<Long>).toSet(), (it[1] as List<Long>).toSet(), it[2] as Boolean?) },
    )) { mutableStateOf(com.aktcl.aron.feature.amo.PerfState()) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<DayRefusal?>(null) } // tso-manual X4a follow-up (R-W-23)
    refused?.let { AmoRefusalDialog(day, it, onOk = { refused = null }) }
    var header by remember { mutableStateOf<Pair<String?, String?>>(null to null) }
    var posmMissing by remember { mutableStateOf(false) }
    LaunchedEffect(day) {
        brands = com.aktcl.aron.feature.amo.PerfAssessment.brands(runCatching { day.reference.section("products") }.getOrNull())
        header = runCatching { day.callHeader() }.getOrDefault(null to null)
    }
    val visit by day.visitSession.current.collectAsState()
    com.aktcl.aron.feature.amo.PerfAssessmentContent(
        brands.orEmpty(), state, saved, saving,
        { state = state.toggleDistributed(it) }, { state = state.toggleOos(it) }, { state = state.withPosm(it); posmMissing = false },
        onSave = {
            val v = visit
            // POSM is the one required answer (R9); brands are optional
            if (state.posm == null) posmMissing = true
            else if (!saving && !saved && v != null) {
                saving = true
                scope.launch {
                    val out = day.saveCallStep { day.saveAssessment(v.visitUuid, brands.orEmpty(), state) }
                    val ok = out.saved
                    saving = false; saved = ok; refused = out.refused
                }
            }
        },
        outletLabel = header.first, routeName = header.second, posmError = posmMissing, onSavedOk = onSavedOk,
    )
}

/** F-AMO-011: the SR call assessment of an open Joint Call; the SR is the route's assigned one, read-only. */
@Composable
private fun JointCallStep(day: AmoDay, onSavedOk: () -> Unit) {
    // the open visit carries the route (it survives a kill and relaunch; the host's saved route does not)
    val openVisit by day.visitSession.current.collectAsState()
    val routeId = openVisit?.routeId?.takeIf { it != 0L }
    val scope = rememberCoroutineScope()
    var rubric by remember { mutableStateOf<com.aktcl.aron.feature.amo.JointRubric?>(null) }
    var sr by remember { mutableStateOf<com.aktcl.aron.feature.amo.AssignedSr?>(null) }
    var stars by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<com.aktcl.aron.feature.amo.JointState, Long>(
        save = { s -> s.stars.flatMap { listOf(it.key, it.value.toLong()) } },
        restore = { l -> com.aktcl.aron.feature.amo.JointState(l.chunked(2).associate { it[0] to it[1].toInt() }) },
    )) { mutableStateOf(com.aktcl.aron.feature.amo.JointState()) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<DayRefusal?>(null) } // tso-manual X4a follow-up (R-W-23)
    refused?.let { AmoRefusalDialog(day, it, onOk = { refused = null }) }
    var header by remember { mutableStateOf<Pair<String?, String?>>(null to null) }
    var defaultStar by remember { mutableStateOf<Int?>(1) }
    LaunchedEffect(day, routeId) {
        header = runCatching { day.callHeader() }.getOrDefault(null to null)
        defaultStar = runCatching { day.jointDefaultStar() }.getOrDefault(1)
        rubric = com.aktcl.aron.feature.amo.JointCall.rubric(runCatching { day.reference.section("rubrics") }.getOrNull())
        sr = routeId?.let { com.aktcl.aron.feature.amo.JointCall.assignedSr(runCatching { day.reference.section("supervisor") }.getOrNull(), it) }
    }
    val visit by day.visitSession.current.collectAsState()
    com.aktcl.aron.feature.amo.JointCallContent(
        rubric, sr, stars, saved, saving, { id, n -> stars = stars.star(id, n) },
        onSave = {
            val r = rubric
            val v = visit
            if (!saving && !saved && v != null && r != null && stars.canSave(r, defaultStar)) {
                saving = true
                scope.launch {
                    val out = day.saveCallStep { day.saveJointCall(v.visitUuid, r, stars, sr?.userId, defaultStar) }
                    val ok = out.saved
                    saving = false; saved = ok; refused = out.refused
                }
            }
        },
        outletLabel = header.first, routeName = header.second, defaultStar = defaultStar, onSavedOk = onSavedOk,
    )
}

private const val SEP = "\u0001"

/** Answers as "key SEP bool SEP photoUuid" (this screen sets only those two fields); survives rotation and process death. */
private val AnswersSaver = androidx.compose.runtime.saveable.Saver<Map<String, com.aktcl.aron.feature.outlet.SurveyAnswer>, ArrayList<String>>(
    save = { m -> ArrayList(m.map { (k, a) -> listOf(k, a.bool?.toString().orEmpty(), a.photoUuid.orEmpty()).joinToString(SEP) }) },
    restore = { l ->
        l.mapNotNull { row ->
            val p = row.split(SEP)
            if (p.size < 3) null else p[0] to com.aktcl.aron.feature.outlet.SurveyAnswer(bool = p[1].toBooleanStrictOrNull(), photoUuid = p[2].ifEmpty { null })
        }.toMap()
    },
)

/** F-AMO-043/010: the survey of the open Control Call. Submit confirms, writes all answers at once and returns to the menu; the call is never blocked. */
@Composable
private fun SurveyStep(day: AmoDay, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val visit by day.visitSession.current.collectAsState()
    val bn = com.aktcl.aron.core.ui.LocalAppLanguage.current == com.aktcl.aron.core.common.AppLanguage.BN
    var loaded by remember { mutableStateOf<Pair<com.aktcl.aron.core.database.entity.SurveyEntity, List<com.aktcl.aron.core.database.entity.SurveyQuestionEntity>>?>(null) }
    var checked by remember { mutableStateOf(false) }
    // a superseded run is cancelled before it can write loaded/checked over the new visit's survey (D-AMO-CALLS-37)
    LaunchedEffect(day, visit?.visitUuid) { loaded = visit?.let { com.aktcl.aron.core.common.orNullKeepingCancellation { day.pendingSurvey(it.visitUuid) } }; checked = true }
    if (!checked) return
    val questions = remember(loaded, bn) { loaded?.let { com.aktcl.aron.feature.amo.AmoSurvey.questions(it.second, bn) }.orEmpty() }
    var answers by rememberSaveable(stateSaver = AnswersSaver) { mutableStateOf<Map<String, com.aktcl.aron.feature.outlet.SurveyAnswer>>(emptyMap()) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<DayRefusal?>(null) } // tso-manual X4a follow-up (R-W-23)
    refused?.let { AmoRefusalDialog(day, it, onOk = { refused = null }) }
    fun set(key: String, f: (com.aktcl.aron.feature.outlet.SurveyAnswer) -> com.aktcl.aron.feature.outlet.SurveyAnswer) {
        answers = answers + (key to f(answers[key] ?: com.aktcl.aron.feature.outlet.SurveyAnswer()))
    }
    com.aktcl.aron.feature.amo.AmoSurveyContent(
        questions, answers, confirming, saving,
        onYes = { k -> set(k) { it.copy(bool = true) } }, onNo = { k -> set(k) { it.copy(bool = false) } },
        onPhoto = { k ->
            scope.launch {
                val shot = runCatching { day.photoPipeline.captureAndCompress(com.aktcl.aron.core.common.ClientIds.newUuid()) }.getOrNull() ?: return@launch
                set(k) { it.copy(photoUuid = shot.photoUuid) }
            }
        },
        onSubmit = { confirming = true },
        onConfirm = {
            val l = loaded; val v = visit
            confirming = false
            if (!saving && l != null && v != null) {
                saving = true
                scope.launch {
                    val defs = questions.map { it.question }
                    val out = day.saveCallStep { day.saveSurvey(v.visitUuid, l.first, com.aktcl.aron.feature.outlet.PosmSurvey.rows(v.visitUuid, l.first.surveyId, l.first.version, defs, answers)) }
                    val ok = out.saved
                    saving = false; refused = out.refused
                    if (ok) onDone() // a failed write keeps the form (and the answers) on screen for another Submit
                }
            }
        },
        onDismiss = { confirming = false }, onSkip = onDone,
    )
}

/** F-AMO-035: price compliance of the open Control Call. Save writes one check per typed SKU, then back to the menu. */
@Composable
private fun PriceStep(day: AmoDay, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bn = com.aktcl.aron.core.ui.LocalAppLanguage.current == com.aktcl.aron.core.common.AppLanguage.BN
    var rows by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.PriceRow>?>(null) }
    var tolerance by remember { mutableStateOf(com.aktcl.aron.feature.amo.PriceCompliance.DEFAULT_TOLERANCE_BP) }
    var typed by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<Map<Long, String>, Any>(
        save = { m -> m.flatMap { listOf(it.key, it.value) } },
        restore = { l -> l.chunked(2).associate { (it[0] as Long) to (it[1] as String) } },
    )) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val visit by day.visitSession.current.collectAsState()
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<DayRefusal?>(null) } // tso-manual X4a follow-up (R-W-23)
    refused?.let { AmoRefusalDialog(day, it, onOk = { refused = null }) }
    LaunchedEffect(day, bn, visit?.visitUuid) {
        tolerance = day.priceToleranceBp()
        val done = visit?.let { runCatching { day.pricedSkus(it.visitUuid) }.getOrNull() }.orEmpty()
        rows = runCatching { day.priceRows(bn) }.getOrDefault(emptyList()).filter { it.skuId !in done } // already recorded SKUs are not asked again
    }
    val list = rows ?: run { com.aktcl.aron.core.ui.aron.AronLoadingScreen(); return } // loading: the local read is a few ms
    com.aktcl.aron.feature.amo.PriceComplianceContent(
        list, typed, tolerance, saved, saving, { id, t -> typed = typed + (id to t) },
        onSave = {
            val v = visit
            if (!saving && !saved && v != null) {
                saving = true
                scope.launch {
                    val out = day.saveCallStep { day.savePriceCompliance(v.visitUuid, list, typed, tolerance) }
                    val ok = out.saved
                    saving = false; saved = ok; refused = out.refused
                    if (ok) { kotlinx.coroutines.delay(900); onSaved() }
                }
            }
        },
    )
}
