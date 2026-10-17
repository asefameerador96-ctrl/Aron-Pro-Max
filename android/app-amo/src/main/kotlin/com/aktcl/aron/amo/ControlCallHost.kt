package com.aktcl.aron.amo

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
import com.aktcl.aron.feature.outlet.ForceSaleContent
import com.aktcl.aron.feature.outlet.ForceSaleMissing
import com.aktcl.aron.feature.outlet.ForceSaleResult
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitUiState
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.amo.R as AmoR

private enum class CcStep { ROUTE, OUTLET, CHECK, FORCE, MENU }

/**
 * F-AMO-004/005/036: Control Call. Route and retailer come from the zone-wide bundle (local), the geo gate is the SR's
 * [VisitFlow] (radius check on the phone, airplane mode safe) on visit kind `amo_control_call`, the out-of-range answer is
 * Manual Override (the SR's Force Sale with the outlet photo and a location-change request, capped per day) or Refresh.
 * [onSale], [onAssessment] and [onSurvey] open the per-outlet menu entries (null hides Survey until it exists).
 */
@Composable
fun ControlCallHost(
    day: AmoDay, onBack: () -> Unit, onSale: (() -> Unit)? = null, onAssessment: (() -> Unit)? = null, onSurvey: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(CcStep.ROUTE) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var days by remember { mutableStateOf<List<RouteDay>?>(null) }
    LaunchedEffect(day) {
        days = runCatching { day.routes() }.getOrDefault(emptyList()); day.nextSequenceFromStore()
        // Kill and relaunch: a committed visit with no close is the call in progress; with none, a saved CHECK/MENU/FORCE step is dead.
        day.restoreOpenVisit()
        if (day.visitSession.current.value == null && step != CcStep.ROUTE && step != CcStep.OUTLET) step = if (routeId != null) CcStep.OUTLET else CcStep.ROUTE
    }
    // A call already open (kill and relaunch): back on its menu, never a second visit.
    val open by day.visitSession.current.collectAsState()
    LaunchedEffect(open) { if (open != null && step != CcStep.MENU && step != CcStep.FORCE) step = CcStep.MENU }
    val goBack: () -> Unit = {
        when (step) {
            CcStep.ROUTE -> onBack()
            CcStep.OUTLET -> step = CcStep.ROUTE
            CcStep.CHECK, CcStep.FORCE -> step = CcStep.OUTLET
            CcStep.MENU -> scope.launch { day.closeVisit(); step = CcStep.OUTLET }
        }
    }
    androidx.activity.compose.BackHandler(onBack = goBack)
    AronScreen(topBar = { AronTopBar(stringResource(AmoR.string.amo_cc_title), onBack = goBack) }) {
        PermissionGate(GatedFeature.SALE, onBack = onBack) {
            val list = days
            when (step) {
                CcStep.ROUTE -> AmoRoutePick(ControlCall.routes(list.orEmpty()), { routeId = it.routeId; step = CcStep.OUTLET })
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
                                runCatching { day.visitFlow.open(ControlCall.visitOutlet(r.outlet)) }
                                opening = false; step = CcStep.CHECK
                            }
                        }
                    })
                }
                CcStep.CHECK -> {
                    val st by day.visitFlow.state.collectAsState()
                    LaunchedEffect(st) { if (st is VisitUiState.Open) step = CcStep.MENU }
                    VisitCheckContent(
                        st, onRefresh = { scope.launch { day.visitFlow.refresh() } }, onForceSale = { step = CcStep.FORCE },
                        onRetry = { scope.launch { day.visitFlow.retryCommit() } }, onMap = null,
                        texts = amoVisitCheckTexts(), onBack = { step = CcStep.OUTLET },
                    )
                }
                CcStep.FORCE -> ManualOverrideStep(day, onStarted = { step = CcStep.MENU }, onBack = { step = CcStep.CHECK })
                CcStep.MENU -> ControlCallMenu(
                    onSale = { onSale?.invoke() }, onAssessment = { onAssessment?.invoke() }, onSurvey = onSurvey,
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
                ForceSaleContent(
                    selected = reason, capture = cap, missing = missing,
                    noOutletLocation = decision.result.verdict == com.aktcl.aron.rules.GeoVerdict.NO_OUTLET_LOCATION,
                    onReason = { reason = it }, onShutter = { scope.launch { capture.shutter() } },
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
