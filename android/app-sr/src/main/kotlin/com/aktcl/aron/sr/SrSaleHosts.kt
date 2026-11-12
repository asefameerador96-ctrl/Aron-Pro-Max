package com.aktcl.aron.sr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.feature.memo.ui.KpiStripView
import com.aktcl.aron.feature.memo.ui.MemoDetailView
import com.aktcl.aron.feature.memo.ui.MemoMenuScreen
import com.aktcl.aron.feature.memo.ui.MemoSwitchOption
import com.aktcl.aron.feature.memo.ui.MemoSwitcher
import com.aktcl.aron.feature.memo.ui.MoneyCardsView
import com.aktcl.aron.feature.memo.ui.ReprintDialogs
import com.aktcl.aron.feature.memo.ui.SaleHistoryScreen
import com.aktcl.aron.feature.memo.ui.SummaryScreen
import com.aktcl.aron.feature.sale.domain.EditDenied
import com.aktcl.aron.feature.sale.domain.EditReason
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.ui.EditReasonDialog
import com.aktcl.aron.feature.sale.ui.EditReasonScreen
import com.aktcl.aron.feature.sale.ui.SaleRoute
import com.aktcl.aron.feature.sale.ui.StartCallPrompt
import kotlinx.coroutines.launch

/**
 * The call: start prompt, then the sale route; the visit closes `sold` or `zero_sale_stock_ok` when Review has saved (F-SR-060/023/025).
 * The frame (top bar, chip, printer) is [SrFrame]'s in SrApp; [sunlight] and [onSunlight] are kept for source compatibility
 * (the sun switch lives on Home and in the sync sheet, screens-sr s0).
 */
@Composable
fun SaleHost(day: SrDay, sunlight: Boolean, onSunlight: (Boolean) -> Unit, onNoSale: () -> Unit, onDone: () -> Unit) {
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
        val name = day.dayData.collectAsState().value.outlets.firstOrNull { it.outletId == v.outletId }?.name.orEmpty()
        val geo by day.visitSession.geo.collectAsState()
        val g = geo
        val start = { kit.callStarted[v.visitUuid] = day.iso(day.currentMs()); started = true }
        if (g == null) {
            // restored after a relaunch: no fix on the phone to show, so the plain prompt
            StartCallPrompt(name, onYes = start, onNo = { leave { day.closeVisitAbandoned() } })
        } else {
            // the confirm step (D-MAP-02): distance, radius, verdict and the lite map from the fix the visit already took
            val context = androidx.compose.ui.platform.LocalContext.current
            val mapSlot = rememberOutletMapSlot(day.isOnline())
            VisitCheckContent(
                VisitUiState.Open(v), onRefresh = {}, onForceSale = {}, onRetry = {},
                onMap =
                    { context.startActivity(OutletMapActivity.intent(context, g.outletName, g.outletLat, g.outletLng, g.radiusM, g.phoneLat, g.phoneLng)) },
                onStartCall = start, onNoSale = onNoSale, geo = g, mapSlot = mapSlot, geoPanel = true,
                onBack = { leave { day.closeVisitAbandoned() } },
            )
        }
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
    val outlet = day.dayData.collectAsState().value.outlets.firstOrNull { it.outletId == v.outletId }
    val route = day.dayData.collectAsState().value.route
    Column(Modifier.fillMaxSize()) {
        // the outlet stays on screen from quantity entry to the commit-and-print tap (parity audit E1/R1): as the retailer pill on the list
        // and as the three header lines of the review (outlet and code, route, cluster)
        SaleRoute(
            vm, day.printerManager, kit::skuName, modifier = Modifier.weight(1f), printerAssist = true,
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
fun MemoHost(day: SrDay, sunlight: Boolean, onSunlight: (Boolean) -> Unit, onEdit: (String) -> Unit) {
    val kit = day.sale
    val vm = viewModel(key = "memo-" + day.userId) { kit.newMemoViewModel() }
    val st by vm.state.collectAsState()
    var history by remember { mutableStateOf<com.aktcl.aron.feature.memo.domain.SaleHistory?>(null) }
    val scope = rememberCoroutineScope()
    // SRM-B-09/11: the outlet as the sale picker names it: name (code-phone-cluster), digits per language
    val outlets = day.dayData.collectAsState().value.outlets
    val labels = outlets.associate { it.outletId to com.aktcl.aron.core.ui.localizedDigits(com.aktcl.aron.feature.outlet.OutletPicker.label(it)) }
    val outletLabel: (Long) -> String = { id -> labels[id] ?: kit.outletName(id) }
    LaunchedEffect(Unit) { kit.prepare(); vm.refresh() }
    // tso-manual X4a (R-W-11): the push day_final_submitted pulls the delta; Mark Paid and Edit follow the lock while on screen
    var pulls by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { com.aktcl.aron.core.sync.push.PushRuntime.pulled.collect { pulls++; vm.refresh() } }
    BackHandler(enabled = st.selected != null || history != null) { if (history != null) history = null else vm.open("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val sel = st.selected
        val h = history
        when {
            !st.loaded -> com.aktcl.aron.core.ui.aron.AronLoadingScreen()
            h != null -> SaleHistoryScreen(h, offlineBanner = true, skuName = kit::skuName, skuCategory = kit::skuCategoryCode)
            sel == null -> MemoMenuScreen(st.rows.map { it.copy(dueMtk = st.remainingByMemo[it.memoUuid] ?: it.dueMtk) }, outletLabel, onOpen = { vm.open(it.memoUuid) }, banner = stringResource(com.aktcl.aron.feature.memo.R.string.memo_banner_manual))
            else -> {
                // an edit is a new memo: on a route-day closed by Final Submit it is disabled with the lock text (Print stays);
                // checked again on every applied push pull (fix round), not only when Mark Paid's own check changes;
                // follow-up (R-W-23): after the SR's own Sales Submit it is disabled with the submit text
                var editRefused by remember(sel.memo.memoUuid) { mutableStateOf<DayRefusal?>(null) }
                LaunchedEffect(sel.memo.memoUuid, st.dayLocked, pulls) { editRefused = kit.editRefusal(sel.memo.memoUuid) }
                MemoDetailView(
                    sel, st.canMarkPaid, st.remainingDueMtk, kit::skuName, onPrint = vm::reprint, onEdit = { onEdit(sel.memo.memoUuid) }, onMarkPaid = vm::markPaid, skuCategory = kit::skuCategoryCode,
                    // SRM-B-11/19/10/12/14/20: the outlet heads the memo, the due box is the dashed one with the button beside the sentence,
                    // the selector and letter bar, the category quantities, the banner with Print and Edit in a row, the "marked as paid" modal
                    outletName = outletLabel(sel.memo.outletId), dashedDueBox = true, dueSideBySide = true, subtotalQuantities = true, letterFilter = true,
                    switcher = MemoSwitcher(
                        st.rows.map { MemoSwitchOption(it.memoUuid, outletLabel(it.outletId), st.remainingByMemo[it.memoUuid] ?: it.dueMtk) }, sel.memo.memoUuid, onSelect = { vm.open(it) },
                    ),
                    detailBanner = stringResource(com.aktcl.aron.feature.memo.R.string.memo_banner_manual), buttonsInRow = true,
                    paidDialog = st.paidDone, onPaidDialogOk = vm::dismissPaidDone,
                    editDisabledReason = when (editRefused) {
                        DayRefusal.FINAL_SUBMIT -> stringResource(R.string.sr_lock_body)
                        DayRefusal.SALES_SUBMIT -> stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_blocked_done)
                        null -> null
                    },
                )
                AronSecondaryButton(
                    stringResource(R.string.sr_sale_history),
                    { scope.launch { history = SaleHistoryBuilder.build(kit.history(), sel.memo.outletId) } },
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
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { bundle = kit.summary() }
    val b = bundle
    if (b == null) com.aktcl.aron.core.ui.aron.AronLoadingScreen() else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // SRM-B-22: the category rows in the app language, never the raw codes
        SummaryScreen(b.summary, kit::skuName, onPrint = { day.printSummary(b) }, categoryLabel = { com.aktcl.aron.feature.memo.ui.MemoCategories.label(context, it).ifBlank { it } })
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
        business = st.business, serverBusiness = st.serverBusiness, categoryLabel = { com.aktcl.aron.feature.memo.ui.MemoCategories.label(context, it).ifBlank { it } },
        vendorDialogs = true, checkoutHint = true, // SRM-B-27/29: the manual's dues modal and the success modal over the table (shots 40, 41)
    )
}

/** The outlet line of the journey: `Name (code-phone-cluster)` with the parts that exist (screenshot 59); the Bangla name when the app is in Bangla. */
internal fun journeyOutletLine(o: OutletEntity?, outletId: Long, bn: Boolean): String {
    if (o == null) return "#$outletId"
    val name = if (bn) (o.nameBn?.takeIf { it.isNotBlank() } ?: o.name) else o.name
    val tail = listOfNotNull(o.code.takeIf { it.isNotBlank() }, o.contactNumber?.takeIf { it.isNotBlank() }, o.clusterName.takeIf { it.isNotBlank() }).joinToString("-")
    return if (tail.isEmpty()) name else "$name ($tail)"
}

/** The Sales Journey: today's calls in time order from the device's own rows, offline (SRM-B-82 to 85). */
@Composable
fun JourneyHost(day: SrDay, locale: com.aktcl.aron.rules.UiLocale) {
    var calls by remember { mutableStateOf<List<com.aktcl.aron.feature.memo.ui.SalesJourneyCall>?>(null) }
    LaunchedEffect(Unit) {
        calls = day.sale.journeyCalls().map { (row, o) -> com.aktcl.aron.feature.memo.ui.SalesJourneyCall(row, journeyOutletLine(o, row.input.outletId, locale == com.aktcl.aron.rules.UiLocale.BN)) }
    }
    calls?.let { com.aktcl.aron.feature.memo.ui.SalesJourneyScreen(it, locale) } ?: com.aktcl.aron.core.ui.aron.AronLoadingScreen()
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
        denied != null -> EditReasonScreen(denied, onReason = { reason = it }, reasons = EditReason.manual)
        // SRM-B-16 (shot 37): the reason is a modal with the dropdown, বাতিল and জমা দিন; জমা দিন starts the edit
        r == null -> { Box(Modifier.fillMaxSize()); EditReasonDialog(onSubmit = { reason = it }, onCancel = onDone) }
        else -> {
            LaunchedEffect(r) { kit.beginEdit(memoUuid, r); ready = true }
            if (ready) {
                val vm = viewModel(key = "edit-" + memoUuid) { kit.newSaleViewModel() }
                // SRM-B-18: the outlet, route and cluster lines of a normal sale
                val data = day.dayData.collectAsState().value
                val editOutlet = data.outlets.firstOrNull { it.outletId == vm.state.collectAsState().value.draft?.outletId }
                Column(Modifier.fillMaxSize()) {
                    SaleRoute(
                        vm, day.printerManager, kit::skuName, modifier = Modifier.weight(1f),
                        retailerLabel = editOutlet?.let { com.aktcl.aron.core.ui.localizedDigits(com.aktcl.aron.feature.outlet.OutletPicker.label(it)) },
                        outletLines = listOfNotNull(
                            editOutlet?.let { "${it.name} (${com.aktcl.aron.core.ui.localizedDigits(it.code)})" },
                            data.route?.name?.let { stringResource(R.string.sr_review_route, it) },
                            editOutlet?.clusterName?.let { stringResource(R.string.sr_review_cluster, it) },
                        ),
                        onFinished = { onDone() },
                    )
                }
            }
        }
    }
}
