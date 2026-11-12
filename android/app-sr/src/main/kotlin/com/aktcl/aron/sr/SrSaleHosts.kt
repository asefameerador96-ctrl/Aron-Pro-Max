package com.aktcl.aron.sr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.feature.dayclose.SalesSubmitScreen
import com.aktcl.aron.feature.memo.ui.JourneyScreen
import com.aktcl.aron.feature.memo.ui.KpiStripView
import com.aktcl.aron.feature.memo.ui.MemoDetailView
import com.aktcl.aron.feature.memo.ui.MemoMenuScreen
import com.aktcl.aron.feature.memo.ui.MoneyCardsView
import com.aktcl.aron.feature.memo.ui.ReprintDialogs
import com.aktcl.aron.feature.memo.ui.SummaryScreen
import com.aktcl.aron.feature.sale.domain.EditDenied
import com.aktcl.aron.feature.sale.domain.EditReason
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.ui.EditReasonScreen
import com.aktcl.aron.feature.sale.ui.SaleRoute
import kotlinx.coroutines.launch

/**
 * The call: start prompt, then the sale route; the visit closes `sold` or `zero_sale_stock_ok` when Review has saved (F-SR-060/023/025).
 * The frame (top bar, chip, printer) is [SrFrame]'s in SrApp; [sunlight] and [onSunlight] are kept for source compatibility
 * (the sun switch lives on Home and in the sync sheet, screens-sr s0).
 */
@Composable
fun SaleHost(day: SrDay, sunlight: Boolean, onSunlight: (Boolean) -> Unit, onNoSale: () -> Unit, onDone: () -> Unit, onSaleData: (Boolean) -> Unit = {}) {
    val open by day.visitSession.current.collectAsState()
    val v = open ?: return
    val kit = day.sale
    val scope = rememberCoroutineScope()
    // tso-manual X4a (R-W-11): the lock arrived while this call was open and refused its sale: nothing was stored or printed.
    // The lock text replaces the sale (and its "save failed" dialog); OK ends the call on the phone and goes Home (banner).
    // Only this call's own refusal shows here (fix round), and it is cleared after the call has ended and Home is shown.
    // Follow-up (R-W-23): a sale refused because the SR's own Sales Submit stands shows the submit text the same way.
    val lockRefused by kit.lockRefused.collectAsState()
    val submitRefused by kit.submitRefused.collectAsState()
    val saveRefused = when (v.visitUuid) { lockRefused -> DayRefusal.FINAL_SUBMIT; submitRefused -> DayRefusal.SALES_SUBMIT; else -> null }
    if (saveRefused != null) {
        SrRefusalDialog(saveRefused, onOk = { scope.launch { kit.afterSaleRefusal(onDone) } })
        return
    }
    // tso-manual X4a follow-up (R-W-23): a call whose route-day no longer takes its records (the zone's Final Submit, or the
    // SR's own Sales Submit made while the call was open) cannot go on: the matching text at once, or when the rep leaves it
    // (No, back, the close after Review); OK ends it on the phone with no close written and goes Home. Nothing throws.
    var closed by remember(v.visitUuid) { mutableStateOf<DayRefusal?>(null) }
    LaunchedEffect(v.visitUuid) { closed = kit.openCallRefusal() }
    val leave: (suspend () -> Unit) -> Unit = { close -> scope.launch { kit.openCallRefusal()?.let { closed = it } ?: run { close(); onDone() } } }
    closed?.let { why ->
        SrRefusalDialog(why, onOk = { scope.launch { kit.endRefusedCall(); onDone() } })
        return
    }
    var started by rememberSaveable(v.visitUuid) { mutableStateOf(false) }
    if (!started) {
        val outletRow = day.dayData.collectAsState().value.outlets.firstOrNull { it.outletId == v.outletId }
        val geo by day.visitSession.geo.collectAsState()
        val g = geo
        val start = { kit.callStarted[v.visitUuid] = day.iso(day.currentMs()); started = true }
        // the manual's call start (s8 / 19, D-SR-MAN-S4-01): Sale History and today's target, then "আপনি কি কল শুরু করতে চান?" with হ্যাঁ and না;
        // না keeps the outlet on screen (the visit is not abandoned; the back arrow does that), and the cart bar says "start the call first"
        var history by remember(v.visitUuid) { mutableStateOf<com.aktcl.aron.feature.memo.domain.SaleHistory?>(null) }
        var showTarget by rememberSaveable(v.visitUuid) { mutableStateOf(false) }
        var startFirst by rememberSaveable(v.visitUuid) { mutableStateOf(false) }
        val h = history
        // the frame's title is "সেল ডাটা" while that screen is up (the frame is SrApp's)
        LaunchedEffect(h != null) { onSaleData(h != null) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSaleData(false) } }
        BackHandler(enabled = h != null) { history = null }
        if (h != null) {
            com.aktcl.aron.feature.memo.ui.SaleDataScreen(
                h, today = day.businessDate(), outletLabel = outletRow?.let { com.aktcl.aron.feature.outlet.OutletPicker.label(it) }, offlineBanner = true,
                skuName = kit::skuName, skuCategory = kit::skuCategoryCode, oldest = kit.historyOldest(),
            )
            return
        }
        val q = com.aktcl.aron.feature.outlet.CallQuestion(
            outletLabel = com.aktcl.aron.core.ui.localizedDigits(kit.callOutletLabel(outletRow, v.outletId)),
            onSaleHistory = { scope.launch { history = kit.saleHistory(v.outletId) } },
            onTodayTarget = { showTarget = true },
            onProceedBlocked = { startFirst = true },
        )
        if (g == null) {
            // restored after a relaunch: no fix on the phone to show, so no distance panel; the same question
            VisitCheckContent(
                VisitUiState.Open(v), onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = null, onStartCall = start, onNoSale = onNoSale,
                onBack = { leave { day.closeVisitAbandoned() } }, callQuestion = q,
            )
        } else {
            // the confirm step (D-MAP-02): distance, radius, verdict and the lite map from the fix the visit already took
            val context = androidx.compose.ui.platform.LocalContext.current
            val mapSlot = rememberOutletMapSlot(day.isOnline())
            VisitCheckContent(
                VisitUiState.Open(v), onRefresh = {}, onForceSale = {}, onRetry = {},
                onMap =
                    { context.startActivity(OutletMapActivity.intent(context, g.outletName, g.outletLat, g.outletLng, g.radiusM, g.phoneLat, g.phoneLng)) },
                onStartCall = start, onNoSale = onNoSale, geo = g, mapSlot = mapSlot, geoPanel = true,
                onBack = { leave { day.closeVisitAbandoned() } }, callQuestion = q,
            )
        }
        if (startFirst) com.aktcl.aron.feature.outlet.StartCallFirstDialog(onOk = { startFirst = false })
        if (showTarget) {
            // the outlet's target for the month is not in the bundle yet (contract outlet_targets[], TRAIN 2): the popup says so
            com.aktcl.aron.feature.sale.ui.OutletTargetPopup(
                outletRow?.let { com.aktcl.aron.core.ui.localizedDigits(com.aktcl.aron.feature.outlet.OutletPicker.label(it)) }.orEmpty(), target = null, onOkay = { showTarget = false },
            )
        }
        return
    }
    var photoDone by rememberSaveable(v.visitUuid) { mutableStateOf(!kit.callPhotoRequired) }
    if (!photoDone) {
        val capture = remember(v.visitUuid) { day.newCapture("outlet_call") }
        val cs by capture.state.collectAsState()
        com.aktcl.aron.feature.outlet.CallPhotoStep(cs, onShutter = { scope.launch { capture.shutter() } }, onContinue = { photoDone = true }, onSkip = { photoDone = true })
        return
    }
    // AV, then KV, then the POSM survey, then the sale (F-SR-020/021); every item reads local data and a missing one is skipped.
    var contentDone by rememberSaveable(v.visitUuid) { mutableStateOf(false) }
    if (!contentDone) {
        // X4a fix round (R-W-23): a survey Save the route-day refused shows the matching text; OK ends the call, as at entry
        CallContentHost(day, v.visitUuid, v.outletId, onRefused = { closed = it }) { contentDone = true }
        return
    }
    val vm = viewModel(key = "sale-" + v.visitUuid) { kit.newSaleViewModel() }
    LaunchedEffect(v.visitUuid) { kit.prepare(); vm.start(kit.saleVisit(v)) }
    // the quantity popup's আগের দিন and SDLW: this outlet's sales already on the phone (eight local days, SDLW is seven back); ADS stays N/A until the bundle carries it
    var skuStats by remember(v.visitUuid) { mutableStateOf<Map<Long, com.aktcl.aron.feature.sale.domain.SkuStats>>(emptyMap()) }
    LaunchedEffect(v.visitUuid) { skuStats = kit.skuStats(v.outletId) }
    val outlet = day.dayData.collectAsState().value.outlets.firstOrNull { it.outletId == v.outletId }
    val route = day.dayData.collectAsState().value.route
    Column(Modifier.fillMaxSize()) {
        // the outlet stays on screen from quantity entry to the commit-and-print tap (parity audit E1/R1): as the retailer pill on the list
        // and as the three header lines of the review (outlet and code, route, cluster)
        SaleRoute(
            vm, day.printerManager, kit::skuName, modifier = Modifier.weight(1f),
            zeroSaleReasonPicker = true, quantityPopup = true, skuStats = skuStats,
            retailerLabel = outlet?.let { com.aktcl.aron.core.ui.localizedDigits(com.aktcl.aron.feature.outlet.OutletPicker.label(it)) },
            outletLines = listOfNotNull(
                outlet?.let { "${it.name} (${com.aktcl.aron.core.ui.localizedDigits(it.code)})" },
                route?.name?.let { stringResource(R.string.sr_review_route, it) },
                outlet?.clusterName?.let { stringResource(R.string.sr_review_cluster, it) },
            ),
            onFinished = {
                val zero = vm.state.value.draft?.zeroSale == true
                leave { kit.closeVisit(if (zero) VisitOutcomeCode.ZERO_SALE else VisitOutcomeCode.SOLD) }
            },
        )
        AronSecondaryButton(stringResource(R.string.sr_no_sale), onNoSale, Modifier.fillMaxWidth().padding(AronTokens.Space.L))
    }
}

/**
 * The no-sale outcomes of F-SR-057; each writes the visit's one `visit_close`. tso-manual X4a follow-up (R-W-23): when the
 * call's route-day no longer takes the close (the Final Submit lock, or the SR's own Sales Submit), the matching text shows
 * and OK ends the call on the phone with no close written; nothing throws.
 */
@Composable
fun NoSaleHost(day: SrDay, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var refused by remember { mutableStateOf<DayRefusal?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        outcomeRows.forEach { (code, label) ->
            AronListRow(stringResource(label), onClick = { scope.launch { day.sale.openCallRefusal()?.let { refused = it } ?: run { day.sale.closeVisit(code); onDone() } } })
        }
    }
    refused?.let { SrRefusalDialog(it, onOk = { scope.launch { day.sale.endRefusedCall(); onDone() } }) }
}

/** The reason for skipping [outlet] from the list (`visit_skip`, F-SR-057): the same outcome list, no fix, not a visit. */
@Composable
fun SkipHost(day: SrDay, outlet: com.aktcl.aron.core.database.entity.OutletEntity, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    // tso-manual X4a: the skip was refused by the Final Submit lock; follow-up (R-W-23, ask 4): or by the SR's own Sales Submit
    var refused by remember { mutableStateOf<DayRefusal?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        androidx.compose.material3.Text(outlet.name, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.padding(AronTokens.Space.L))
        outcomeRows.forEach { (code, label) -> AronListRow(stringResource(label), onClick = { scope.launch { if (day.sale.skipOutlet(outlet, code) { refused = it }) onDone() } }) }
    }
    refused?.let { SrRefusalDialog(it, onOk = onDone) }
}

private val outcomeRows = listOf(
    VisitOutcomeCode.CLOSED to R.string.sr_outcome_closed, VisitOutcomeCode.OWNER_ABSENT to R.string.sr_outcome_owner_absent,
    VisitOutcomeCode.REFUSED to R.string.sr_outcome_refused, VisitOutcomeCode.COMPETITOR_EXCLUSIVE to R.string.sr_outcome_competitor,
    VisitOutcomeCode.NOT_REACHED to R.string.sr_outcome_not_reached,
)

/** Memo menu, detail with Print, Edit and Mark paid, and the outlet's sale history (F-SR-030/031/032/054). */
@Composable
fun MemoHost(day: SrDay, sunlight: Boolean, onSunlight: (Boolean) -> Unit, onEdit: (String) -> Unit, onSaleData: (Boolean) -> Unit = {}) {
    val kit = day.sale
    val vm = viewModel(key = "memo-" + day.userId) { kit.newMemoViewModel() }
    val st by vm.state.collectAsState()
    var history by remember { mutableStateOf<com.aktcl.aron.feature.memo.domain.SaleHistory?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kit.prepare(); vm.refresh() }
    // tso-manual X4a (R-W-11): the push day_final_submitted pulls the delta; Mark Paid and Edit follow the lock while on screen
    var pulls by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { com.aktcl.aron.core.sync.push.PushRuntime.pulled.collect { pulls++; vm.refresh() } }
    LaunchedEffect(history != null) { onSaleData(history != null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSaleData(false) } }
    BackHandler(enabled = st.selected != null || history != null) { if (history != null) history = null else vm.open("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val sel = st.selected
        val h = history
        when {
            !st.loaded -> com.aktcl.aron.core.ui.aron.AronLoadingScreen()
            h != null -> com.aktcl.aron.feature.memo.ui.SaleDataScreen(
                h, today = day.businessDate(), outletLabel = kit.outletName(sel?.memo?.outletId ?: 0L), offlineBanner = true,
                skuName = kit::skuName, skuCategory = kit::skuCategoryCode, oldest = kit.historyOldest(),
            )
            sel == null -> MemoMenuScreen(st.rows, kit::outletName, onOpen = { vm.open(it.memoUuid) })
            else -> {
                // an edit is a new memo: on a route-day closed by Final Submit it is disabled with the lock text (Print stays);
                // checked again on every applied push pull (fix round), not only when Mark Paid's own check changes;
                // follow-up (R-W-23): after the SR's own Sales Submit it is disabled with the submit text
                var editRefused by remember(sel.memo.memoUuid) { mutableStateOf<DayRefusal?>(null) }
                LaunchedEffect(sel.memo.memoUuid, st.dayLocked, pulls) { editRefused = kit.editRefusal(sel.memo.memoUuid) }
                MemoDetailView(
                    sel, st.canMarkPaid, st.remainingDueMtk, kit::skuName, onPrint = vm::reprint, onEdit = { onEdit(sel.memo.memoUuid) }, onMarkPaid = vm::markPaid, skuCategory = kit::skuCategoryCode,
                    editDisabledReason = when (editRefused) {
                        DayRefusal.FINAL_SUBMIT -> stringResource(R.string.sr_lock_body)
                        DayRefusal.SALES_SUBMIT -> stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_blocked_done)
                        null -> null
                    },
                )
                AronSecondaryButton(
                    stringResource(R.string.sr_sale_history),
                    { scope.launch { history = kit.saleHistory(sel.memo.outletId) } },
                    Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L),
                )
                ReprintDialogs(vm)
            }
        }
    }
    // tso-manual X4a: a Mark Paid the lock refused (nothing stored); fix round: or the day's Sales Submit refused it
    if (st.lockRefused) SrDayLockDialog(onOk = vm::dismissLockRefusal)
    if (st.submitRefused) SrMarkPaidSubmittedDialog(onOk = vm::dismissLockRefusal)
}

/**
 * tso-manual X4a (fix round): a Mark Paid refused because today's sales are already submitted (the button was drawn before
 * the submit landed): "Mark paid" and the Sales Submit screen's own "already submitted" words, bn and en; nothing was stored.
 */
@Composable
fun SrMarkPaidSubmittedDialog(onOk: () -> Unit) {
    com.aktcl.aron.core.ui.aron.AronDialog(
        stringResource(com.aktcl.aron.feature.memo.R.string.memo_mark_paid), stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_blocked_done),
        stringResource(R.string.sr_ok), onConfirm = onOk, onDismiss = onOk,
    )
}

/** Sales Summary with its printed copy (F-SR-036); the print needs no connection and never blocks anything. */
@Composable
fun SummaryHost(day: SrDay, sunlight: Boolean, onSunlight: (Boolean) -> Unit) {
    val kit = day.sale
    var bundle by remember { mutableStateOf<SummaryBundle?>(null) }
    val attempt by day.summaryAttempt.collectAsState()
    LaunchedEffect(Unit) { bundle = kit.summary() }
    val b = bundle
    if (b == null) com.aktcl.aron.core.ui.aron.AronLoadingScreen() else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SummaryScreen(b.summary, kit::skuName, onPrint = { day.printSummary(b) })
    }
    com.aktcl.aron.core.printing.ui.PrintAttemptDialogs(
        attempt, onAnswer = day::answerSummaryPrint, onClose = day::closeSummaryAttempt,
    )
}

/** Sales Submit with the reconciliation table and the Sync data button (F-SR-034/035). */
@Composable
fun SubmitHost(day: SrDay) {
    val vm = viewModel(key = "submit-" + day.userId) { day.sale.newSubmitViewModel() }
    val st by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    val recordLabels = mapOf(
        "visit" to stringResource(R.string.sr_rec_visit), "memo" to stringResource(R.string.sr_rec_memo), "stock_movement" to stringResource(R.string.sr_rec_stock_movement),
        "qc_line" to stringResource(R.string.sr_rec_qc_line), "memo_discount" to stringResource(R.string.sr_rec_memo_discount), "visit_close" to stringResource(R.string.sr_rec_visit_close),
        "due_collection" to stringResource(R.string.sr_rec_due_collection), "attendance_event" to stringResource(R.string.sr_rec_attendance_event), "visit_skip" to stringResource(R.string.sr_rec_visit_skip),
    )
    SalesSubmitScreen(
        online = st.online, counts = st.counts, gate = st.gate, progress = st.progress, typeLabel = { recordLabels[it] ?: it },
        onSync = vm::sync, onSubmit = vm::submit, syncing = st.syncing,
        rejected = st.rejected, dismissed = st.dismissed, serverKnown = st.serverKnown, today = day.businessDate(), // D-NIGHT-DAYCLOSE-01
    )
}

@Composable
fun JourneyHost(day: SrDay) {
    var j by remember { mutableStateOf<com.aktcl.aron.feature.memo.domain.Journey?>(null) }
    LaunchedEffect(Unit) { j = day.sale.journey() }
    j?.let { JourneyScreen(it) } ?: com.aktcl.aron.core.ui.aron.AronLoadingScreen()
}

/** The KPI strip and the two money cards of F-SR-010/069, read from Room only. */
@Composable
fun KpiHost(day: SrDay) {
    var data by remember { mutableStateOf<Pair<com.aktcl.aron.feature.memo.domain.KpiStrip, com.aktcl.aron.feature.memo.domain.HomeMoney>?>(null) }
    LaunchedEffect(Unit) { data = day.sale.kpi() }
    val cats = mapOf(
        "cigarette" to stringResource(R.string.sr_cat_cigarette), "bidi" to stringResource(R.string.sr_cat_bidi),
        "lighter" to stringResource(R.string.sr_cat_lighter), "match" to stringResource(R.string.sr_cat_match),
    )
    val units = listOf("packet", "box", "dozen", "stick", "piece").associateWith { com.aktcl.aron.core.ui.entryUnitLabel(it) }
    val loaded = data
    if (loaded == null) com.aktcl.aron.core.ui.aron.AronLoadingScreen() else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val (strip, money) = loaded
        KpiStripView(strip, categoryLabel = { cats[it] ?: it }, unitLabel = { units[it] ?: it })
        MoneyCardsView(money, categoryLabel = { cats[it] ?: it })
    }
}

/** Sale edit (F-SR-033): the gate and a fresh fix first, then the reason, then the pre-filled sale route; Print supersedes the memo. */
@Composable
fun EditHost(day: SrDay, memoUuid: String, sunlight: Boolean, onSunlight: (Boolean) -> Unit, onDone: () -> Unit) {
    val kit = day.sale
    var denied by remember(memoUuid) { mutableStateOf<EditDenied?>(null) }
    var checked by remember(memoUuid) { mutableStateOf(false) }
    var reason by remember(memoUuid) { mutableStateOf<EditReason?>(null) }
    var ready by remember(memoUuid) { mutableStateOf(false) }
    // tso-manual X4a (R-W-11): an edit on a route-day closed by Final Submit is not started (no fix is taken) and says why;
    // one the lock refuses at the save shows the same text, nothing stored or printed. Follow-up (R-W-23): the same with the
    // submit text after the SR's own Sales Submit.
    var startRefused by remember(memoUuid) { mutableStateOf<DayRefusal?>(null) }
    val lockRefused by kit.lockRefused.collectAsState()
    val submitRefused by kit.submitRefused.collectAsState()
    LaunchedEffect(memoUuid) { kit.prepare(); val why = kit.editRefusal(memoUuid); if (why != null) startRefused = why else denied = kit.prepareEdit(memoUuid); checked = true }
    // only this edit's own refusal (fix round); cleared after leaving, so the edit's "save failed" never shows in between
    val refused = startRefused ?: when (memoUuid) { lockRefused -> DayRefusal.FINAL_SUBMIT; submitRefused -> DayRefusal.SALES_SUBMIT; else -> null }
    if (refused != null) {
        SrRefusalDialog(refused, onOk = { onDone(); kit.clearLockRefusal() })
        return
    }
    val r = reason
    when {
        !checked -> com.aktcl.aron.core.ui.aron.AronLoadingScreen(label = stringResource(R.string.sr_edit_checking))
        denied != null || r == null -> EditReasonScreen(denied, onReason = { reason = it })
        else -> {
            LaunchedEffect(r) { kit.beginEdit(memoUuid, r); ready = true }
            if (ready) {
                val vm = viewModel(key = "edit-" + memoUuid) { kit.newSaleViewModel() }
                Column(Modifier.fillMaxSize()) {
                    SaleRoute(vm, day.printerManager, kit::skuName, modifier = Modifier.weight(1f), onFinished = { onDone() })
                }
            }
        }
    }
}
