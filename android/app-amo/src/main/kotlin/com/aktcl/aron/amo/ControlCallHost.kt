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
import com.aktcl.aron.core.database.repo.RouteDay
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionGate
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.feature.amo.AmoRoutePick
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

private enum class CcStep { ROUTE, OUTLET, CHECK, FORCE, MENU, ASSESS, JOINT, SURVEY, PRICE, SALE }

/**
 * F-AMO-004/005/036: Control Call. Route and retailer come from the zone-wide bundle (local), the geo gate is the SR's
 * [VisitFlow] (radius check on the phone, airplane mode safe) on visit kind `amo_control_call`, the out-of-range answer is
 * Manual Override (the SR's Force Sale with the outlet photo and a location-change request, capped per day) or Refresh.
 * Sale, Assessment, Survey and Price compliance are steps of this host; [startWithSale] (the standalone Sale tile) opens the sale as soon as the call is open.
 */
@Composable
fun ControlCallHost(
    day: AmoDay, onBack: () -> Unit, startWithSale: Boolean = false,
    /** `amo_control_call` (menu: Sale, assessment, survey) or `amo_joint_call` (goes straight to the SR call assessment, F-AMO-011). */
    kind: String = com.aktcl.aron.feature.amo.AmoVisitKind.CONTROL_CALL,
) {
    val joint = kind == com.aktcl.aron.feature.amo.AmoVisitKind.JOINT_CALL
    val inCall = if (joint) CcStep.JOINT else if (startWithSale) CcStep.SALE else CcStep.MENU
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(CcStep.ROUTE) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var days by remember { mutableStateOf<List<RouteDay>?>(null) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(day) {
        submitted = runCatching { day.daySubmitted() }.getOrDefault(false)
        days = runCatching { day.routes() }.getOrDefault(emptyList()); day.nextSequenceFromStore()
        // Kill and relaunch: a committed visit with no close is the call in progress; with none, a saved CHECK/MENU/FORCE step is dead.
        day.restoreOpenVisit()
        if (day.visitSession.current.value == null && step != CcStep.ROUTE && step != CcStep.OUTLET) step = if (routeId != null) CcStep.OUTLET else CcStep.ROUTE
    }
    // A call already open (kill and relaunch): back on its menu, never a second visit.
    val open by day.visitSession.current.collectAsState()
    LaunchedEffect(open) { if (open != null && step != inCall && step != CcStep.FORCE && step != CcStep.ASSESS && step != CcStep.SURVEY && step != CcStep.PRICE && step != CcStep.SALE) step = inCall }
    val goBack: () -> Unit = {
        when (step) {
            CcStep.ROUTE -> onBack()
            CcStep.OUTLET -> step = CcStep.ROUTE
            CcStep.CHECK, CcStep.FORCE -> step = CcStep.OUTLET
            CcStep.ASSESS, CcStep.SURVEY, CcStep.PRICE, CcStep.SALE -> step = CcStep.MENU
            CcStep.MENU, CcStep.JOINT -> scope.launch { day.closeVisit(); step = CcStep.OUTLET }
        }
    }
    androidx.activity.compose.BackHandler(onBack = goBack)
    AmoFrame(stringResource(if (joint) AmoR.string.amo_jc_title else AmoR.string.amo_cc_title), goBack, icon = if (joint) com.aktcl.aron.core.ui.R.drawable.aron_ic_joint_call else com.aktcl.aron.core.ui.R.drawable.aron_ic_control_call) {
        PermissionGate(GatedFeature.SALE, onBack = onBack) {
            val list = days
            when (step) {
                CcStep.ROUTE -> if (submitted && open == null) com.aktcl.aron.feature.amo.DaySubmittedNotice(onBack) else AmoRoutePick(ControlCall.routes(list.orEmpty()), { routeId = it.routeId; step = CcStep.OUTLET })
                CcStep.OUTLET -> {
                    var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
                    var opening by remember { mutableStateOf(false) }
                    val outlets = ControlCall.outlets(list.orEmpty(), routeId ?: -1L)
                    val all = OutletPicker.rows(outlets)
                    OutletPickerContent(OutletPicker.rows(outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r ->
                        if (!opening) {
                            opening = true
                            scope.launch {
                                day.routeId = r.outlet.routeId
                                runCatching { day.visitFlow.open(ControlCall.visitOutlet(r.outlet, kind)) }
                                opening = false; step = CcStep.CHECK
                            }
                        }
                    })
                }
                CcStep.CHECK -> {
                    val st by day.visitFlow.state.collectAsState()
                    LaunchedEffect(st) { if (st is VisitUiState.Open) step = inCall }
                    VisitCheckContent(
                        st, onRefresh = { scope.launch { day.visitFlow.refresh() } }, onForceSale = { step = CcStep.FORCE },
                        onRetry = { scope.launch { day.visitFlow.retryCommit() } }, onMap = null,
                        texts = amoVisitCheckTexts(), onBack = { step = CcStep.OUTLET },
                        geoPanel = true, mapSlot = rememberAmoOutletMapSlot(day.online()),
                    )
                }
                CcStep.FORCE -> ManualOverrideStep(day, onStarted = { step = inCall }, onBack = { step = CcStep.CHECK })
                CcStep.JOINT -> JointCallStep(day, onSaved = { scope.launch { day.closeVisit(); step = CcStep.OUTLET } })
                CcStep.ASSESS -> PerfAssessmentStep(day, onSaved = { scope.launch { day.closeVisit(); step = CcStep.OUTLET } })
                CcStep.SALE -> AmoSaleHost(day, onDone = { step = CcStep.MENU })
                CcStep.SURVEY -> SurveyStep(day, onDone = { step = CcStep.MENU })
                CcStep.PRICE -> PriceStep(day, onSaved = { step = CcStep.MENU })
                CcStep.MENU -> ControlCallMenu(
                    onSale = { step = CcStep.SALE }, onAssessment = { step = CcStep.ASSESS }, onSurvey = { step = CcStep.SURVEY }, onPrice = { step = CcStep.PRICE },
                    onDone = { scope.launch { day.closeVisit(); step = CcStep.OUTLET } },
                )
            }
        }
    }
}

/** F-AMO-005: the out-of-range answer. Outlet photo, location-change request, the call proceeds; the day's limit applies. */
@Composable
private fun ManualOverrideStep(day: AmoDay, onStarted: () -> Unit, onBack: () -> Unit) {
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
            var reason by remember { mutableStateOf<ForceReason?>(ForceReason.LOCATION_CHANGE) }
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
private fun PerfAssessmentStep(day: AmoDay, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var brands by remember { mutableStateOf<List<com.aktcl.aron.feature.amo.AmoBrand>?>(null) }
    var state by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver<com.aktcl.aron.feature.amo.PerfState, Any?>(
        save = { listOf(it.present.toList(), it.oos.toList(), it.posm) },
        restore = { @Suppress("UNCHECKED_CAST") com.aktcl.aron.feature.amo.PerfState((it[0] as List<Long>).toSet(), (it[1] as List<Long>).toSet(), it[2] as Boolean?) },
    )) { mutableStateOf(com.aktcl.aron.feature.amo.PerfState()) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(day) { brands = com.aktcl.aron.feature.amo.PerfAssessment.brands(runCatching { day.reference.section("products") }.getOrNull()) }
    val visit by day.visitSession.current.collectAsState()
    com.aktcl.aron.feature.amo.PerfAssessmentContent(
        brands.orEmpty(), state, saved, saving,
        { state = state.toggleDistributed(it) }, { state = state.toggleOos(it) }, { state = state.withPosm(it) },
        onSave = {
            val v = visit
            if (!saving && !saved && v != null) {
                saving = true
                scope.launch {
                    val ok = runCatching { day.saveAssessment(v.visitUuid, brands.orEmpty(), state) }.isSuccess
                    saving = false; saved = ok
                    if (ok) { kotlinx.coroutines.delay(900); onSaved() }
                }
            }
        },
    )
}

/** F-AMO-011: the SR call assessment of an open Joint Call; the SR is the route's assigned one, read-only. */
@Composable
private fun JointCallStep(day: AmoDay, onSaved: () -> Unit) {
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
    LaunchedEffect(day, routeId) {
        rubric = com.aktcl.aron.feature.amo.JointCall.rubric(runCatching { day.reference.section("rubrics") }.getOrNull())
        sr = routeId?.let { com.aktcl.aron.feature.amo.JointCall.assignedSr(runCatching { day.reference.section("supervisor") }.getOrNull(), it) }
    }
    val visit by day.visitSession.current.collectAsState()
    com.aktcl.aron.feature.amo.JointCallContent(
        rubric, sr, stars, saved, saving, { id, n -> stars = stars.star(id, n) },
        onSave = {
            val r = rubric
            val v = visit
            if (!saving && !saved && v != null && r != null) {
                saving = true
                scope.launch {
                    val ok = runCatching { day.saveJointCall(v.visitUuid, r, stars, sr?.userId) }.isSuccess
                    saving = false; saved = ok
                    if (ok) { kotlinx.coroutines.delay(900); onSaved() }
                }
            }
        },
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
    LaunchedEffect(day, visit?.visitUuid) { loaded = visit?.let { runCatching { day.pendingSurvey(it.visitUuid) }.getOrNull() }; checked = true }
    if (!checked) return
    val questions = remember(loaded, bn) { loaded?.let { com.aktcl.aron.feature.amo.AmoSurvey.questions(it.second, bn) }.orEmpty() }
    var answers by rememberSaveable(stateSaver = AnswersSaver) { mutableStateOf<Map<String, com.aktcl.aron.feature.outlet.SurveyAnswer>>(emptyMap()) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
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
                    val ok = runCatching { day.saveSurvey(v.visitUuid, l.first, com.aktcl.aron.feature.outlet.PosmSurvey.rows(v.visitUuid, l.first.surveyId, l.first.version, defs, answers)) }.isSuccess
                    saving = false
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
    LaunchedEffect(day, bn, visit?.visitUuid) {
        tolerance = day.priceToleranceBp()
        val done = visit?.let { runCatching { day.pricedSkus(it.visitUuid) }.getOrNull() }.orEmpty()
        rows = runCatching { day.priceRows(bn) }.getOrDefault(emptyList()).filter { it.skuId !in done } // already recorded SKUs are not asked again
    }
    val list = rows ?: return // loading: the local read is a few ms, nothing to show yet
    com.aktcl.aron.feature.amo.PriceComplianceContent(
        list, typed, tolerance, saved, saving, { id, t -> typed = typed + (id to t) },
        onSave = {
            val v = visit
            if (!saving && !saved && v != null) {
                saving = true
                scope.launch {
                    val ok = runCatching { day.savePriceCompliance(v.visitUuid, list, typed, tolerance) }.isSuccess
                    saving = false; saved = ok
                    if (ok) { kotlinx.coroutines.delay(900); onSaved() }
                }
            }
        },
    )
}
