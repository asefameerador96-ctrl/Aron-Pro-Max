package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoEmptyState
import com.aktcl.aron.feature.amo.AmoSecondary
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
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.database.repo.DayRefusal
import com.aktcl.aron.feature.dayclose.SalesSubmitScreen
import com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder
import com.aktcl.aron.feature.memo.ui.JourneyScreen
import com.aktcl.aron.feature.memo.ui.KpiStripView
import com.aktcl.aron.feature.memo.ui.MemoDetailView
import com.aktcl.aron.feature.memo.ui.MemoMenuScreen
import com.aktcl.aron.feature.memo.ui.MoneyCardsView
import com.aktcl.aron.feature.memo.ui.ReprintDialogs
import com.aktcl.aron.feature.memo.ui.SaleHistoryScreen
import com.aktcl.aron.feature.memo.ui.SummaryScreen
import com.aktcl.aron.feature.sale.domain.EditDenied
import com.aktcl.aron.feature.sale.domain.EditReason
import com.aktcl.aron.feature.sale.ui.EditReasonScreen
import com.aktcl.aron.feature.sale.ui.SaleRoute
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import com.aktcl.aron.core.system.permission.AndroidPermissions
import com.aktcl.aron.core.system.permission.GatedFeature
import com.aktcl.aron.core.system.permission.PermissionPolicy
import com.aktcl.aron.feature.stock.StockContent
import com.aktcl.aron.feature.stock.StockMessage
import com.aktcl.aron.feature.amo.R as AmoR

/**
 * F-AMO-006: the SR sale route on the open Control Call. No start prompt, AV, KV or SR survey: the AMO is auditing the call.
 * Review's Print (or Save) ends the sale and returns to the call menu; the visit closes when the AMO leaves the call
 * ([AmoDay.closeVisit] reads the memo). One sale per call: a second tap on Sale says so (the memo is edited from Memo).
 */
@Composable
fun AmoSaleHost(
    day: AmoDay, onDone: () -> Unit,
    /** tso-manual X4a: the call ended on the phone after the Final Submit lock refused its sale (back to the outlet list). */
    onCallEnded: () -> Unit = onDone,
) {
    val open by day.visitSession.current.collectAsState()
    val v = open ?: return
    val kit = day.sale
    // tso-manual X4a (R-W-11): the lock arrived while this call was open and refused its sale: nothing was stored or printed.
    // The lock text replaces the sale (and its "save failed" dialog); OK ends the call on the phone when its route is closed.
    // Only this call's own refusal shows here (fix round), and it is cleared after the call has ended and the screen moved on.
    // Follow-up (R-W-23): a sale refused because the supervisor day's Sales Submit stands shows the submit text the same way.
    val scope = rememberCoroutineScope()
    val lockRefused by kit.lockRefused.collectAsState()
    val submitRefused by kit.submitRefused.collectAsState()
    val refused = when (v.visitUuid) { lockRefused -> DayRefusal.FINAL_SUBMIT; submitRefused -> DayRefusal.SALES_SUBMIT; else -> null }
    if (refused != null) {
        AmoRefusalDialog(day, refused, onOk = { scope.launch { kit.afterSaleRefusal(onCallEnded, onDone) } })
        return
    }
    var already by remember(v.visitUuid) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(v.visitUuid) { already = day.callHasSale() }
    when (already) {
        null -> Unit
        true -> Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AronTokens.Space.M)) {
            androidx.compose.material3.Text(stringResource(AmoR.string.amo_sale_done), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            AmoSecondary(stringResource(AmoR.string.amo_sale_done_back), onDone, Modifier.fillMaxWidth())
        }
        false -> {
            val vm = viewModel(key = "sale-" + v.visitUuid) { kit.newSaleViewModel() }
            LaunchedEffect(v.visitUuid) { kit.prepare(); vm.start(kit.saleVisit(v)) }
            var history by rememberSaveable(v.visitUuid) { mutableStateOf(false) }
            BackHandler(enabled = history) { history = false }
            // the outlet stays on screen from quantity entry to Print (screenshots 24, 25): the retailer pill and the review's three header lines
            var header by remember(v.visitUuid) { mutableStateOf<AmoDay.SaleHeader?>(null) }
            LaunchedEffect(v.visitUuid) { header = runCatching { day.saleHeader() }.getOrNull() }
            val routeLine = header?.route?.let { stringResource(AmoR.string.amo_review_route, it) }
            val clusterLine = header?.cluster?.let { stringResource(AmoR.string.amo_review_cluster, it) }
            if (history) PreviousSalesHost(day, v.outletId) else Column(Modifier.fillMaxSize()) {
                SaleRoute(
                    vm, day.printerManager, kit::skuName, modifier = Modifier.weight(1f), onFinished = { onDone() },
                    retailerLabel = header?.pill?.let { com.aktcl.aron.core.ui.localizedDigits(it) },
                    outletLines = listOfNotNull(header?.pill?.let { com.aktcl.aron.core.ui.localizedDigits(it) }, routeLine, clusterLine), // heading = "name (code-phone-cluster)" as screenshot 25
                    onPreviousSales = { history = true }, // the button keeps the shared "পূর্বের সেল ডাটা দেখুন" (screenshots 24, 26)
                )
            }
        }
    }
}

/** Memo menu, detail with Print, Edit and Mark paid, and the outlet's sale history (F-SR-030/031/032/054). */
@Composable
fun AmoMemoHost(day: AmoDay, onEdit: (String) -> Unit) {
    val kit = day.sale
    val vm = viewModel(key = "memo-" + day.userId) { kit.newMemoViewModel() }
    val st by vm.state.collectAsState()
    var history by remember { mutableStateOf<com.aktcl.aron.feature.memo.domain.SaleHistory?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { kit.prepare(); vm.refresh() }
    // tso-manual X4a (R-W-11): the push day_final_submitted pulls the delta; Mark Paid and Edit follow the lock while on screen
    var pulls by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { com.aktcl.aron.core.sync.push.PushRuntime.pulled.collect { pulls++; vm.refresh() } }
    val dayLock = day.dayLock.collectAsState().value
    BackHandler(enabled = st.selected != null || history != null) { if (history != null) history = null else vm.open("") }
    // the find step (route and retailer pickers, their lists) scrolls itself: nesting it in a scrolling column crashes on infinite height
    val outerScroll = st.selected != null || history != null
    Column(Modifier.fillMaxSize().then(if (outerScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        val sel = st.selected
        val h = history
        when {
            h != null -> SaleHistoryScreen(h, offlineBanner = true, skuName = kit::skuName, skuCategory = kit::skuCategoryCode)
            sel == null -> MemoFindStep(day, st.rows, kit::outletName, onOpen = { vm.open(it.memoUuid) })
            else -> {
                // an edit is a new memo on the memo's route: on a route closed by Final Submit it is disabled with the lock text;
                // checked again on every applied push pull and lock refresh (fix round), not only when Mark Paid's own check changes;
                // follow-up (R-W-23): after the supervisor day's Sales Submit it is disabled with the submit text
                var editRefused by remember(sel.memo.memoUuid) { mutableStateOf<DayRefusal?>(null) }
                LaunchedEffect(sel.memo.memoUuid, st.dayLocked, pulls, dayLock) { editRefused = kit.editRefusal(sel.memo.memoUuid) }
                val closed = dayLock?.closedRouteIds?.size ?: 0
                var memoHeader by remember(sel.memo.memoUuid) { mutableStateOf<Pair<String?, String?>>(null to null) }
                LaunchedEffect(sel.memo.memoUuid) { memoHeader = runCatching { day.memoHeader(sel.memo.outletId) }.getOrDefault(null to null) }
                MemoDetailView(
                    sel, st.canMarkPaid, st.remainingDueMtk, kit::skuName, onPrint = vm::reprint, onEdit = { onEdit(sel.memo.memoUuid) }, onMarkPaid = vm::markPaid, skuCategory = kit::skuCategoryCode,
                    outletName = memoHeader.first?.let { com.aktcl.aron.core.ui.localizedDigits(it) }, routeName = memoHeader.second, dashedDueBox = true, // screenshots 42, 43
                    editDisabledReason = when (editRefused) {
                        DayRefusal.FINAL_SUBMIT -> stringResource(R.string.amo_lock_body, com.aktcl.aron.core.ui.localizedNumber(closed.coerceAtLeast(1).toLong()))
                        DayRefusal.SALES_SUBMIT -> stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_blocked_done)
                        null -> null
                    },
                )
                AmoSecondary(
                    stringResource(AmoR.string.amo_sale_history),
                    { scope.launch { history = SaleHistoryBuilder.build(kit.history(), sel.memo.outletId) } },
                    Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L),
                )
                ReprintDialogs(vm)
            }
        }
    }
    // tso-manual X4a: a Mark Paid the lock refused (nothing stored); fix round: or the supervisor day's Sales Submit refused it
    if (st.lockRefused) AmoDayLockDialog(day, onOk = vm::dismissLockRefusal)
    if (st.submitRefused) AmoMarkPaidSubmittedDialog(onOk = vm::dismissLockRefusal)
}

/**
 * tso-manual X4a (fix round): a Mark Paid refused because the day's sales are already submitted (the button was drawn before
 * the submit landed): "Mark paid" and the Sales Submit screen's own "already submitted" words, bn and en; nothing was stored.
 */
@Composable
fun AmoMarkPaidSubmittedDialog(onOk: () -> Unit) {
    com.aktcl.aron.core.ui.aron.AronDialog(
        stringResource(com.aktcl.aron.feature.memo.R.string.memo_mark_paid), stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_blocked_done),
        stringResource(AmoR.string.amo_ok), onConfirm = onOk, onDismiss = onOk,
    )
}

/** Sales Summary with its printed copy (F-SR-036); the print needs no connection and never blocks anything. */
@Composable
fun AmoSummaryHost(day: AmoDay) {
    val kit = day.sale
    var bundle by remember { mutableStateOf<SummaryBundle?>(null) }
    val attempt by day.summaryAttempt.collectAsState()
    LaunchedEffect(Unit) { bundle = kit.summary() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        bundle?.let { b -> SummaryScreen(b.summary, kit::skuName, onPrint = { day.printSummary(b) }, emptyText = stringResource(AmoR.string.amo_summary_empty), printWhenEmpty = true) }
    }
    com.aktcl.aron.core.printing.ui.PrintAttemptDialogs(
        attempt, onAnswer = day::answerSummaryPrint, onClose = day::closeSummaryAttempt,
    )
}

/** Sales Submit with the reconciliation table and the Sync data button (F-SR-034/035). */
@Composable
fun AmoSubmitHost(day: AmoDay) {
    // a call in progress must be closed first: its records belong before the day's last record
    val openCall by day.visitSession.current.collectAsState()
    if (openCall != null) {
        Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen)) { androidx.compose.material3.Text(stringResource(AmoR.string.amo_submit_open_call), style = androidx.compose.material3.MaterialTheme.typography.bodyLarge) }
        return
    }
    val vm = viewModel(key = "submit-" + day.userId) { day.sale.newSubmitViewModel() }
    val st by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    // DISPLAY ONLY: the manual's nine rows, from the admin's AMO reconciliation rows (the gate and the day_submit rows stay per record type)
    var rowGroups by remember { mutableStateOf<List<List<String>>?>(null) }
    val priceCounted = st.counts.any { it.recordType == com.aktcl.aron.feature.amo.AmoReconciliation.PRICE_TYPE && (it.device != 0 || (it.server ?: 0) != 0) }
    LaunchedEffect(priceCounted) { rowGroups = com.aktcl.aron.core.common.orNullKeepingCancellation { day.sale.submitRowGroups(priceCounted) } }
    val recordLabels = mapOf(
        "visit" to stringResource(AmoR.string.amo_rec_visit), "memo" to stringResource(AmoR.string.amo_rec_memo), "stock_movement" to stringResource(AmoR.string.amo_rec_stock_movement),
        "qc_line" to stringResource(AmoR.string.amo_rec_qc_line), "memo_discount" to stringResource(AmoR.string.amo_rec_memo_discount), "visit_close" to stringResource(AmoR.string.amo_rec_visit_close),
        "due_collection" to stringResource(AmoR.string.amo_rec_due_collection), "attendance_event" to stringResource(AmoR.string.amo_rec_attendance_event), "visit_skip" to stringResource(AmoR.string.amo_rec_visit_skip),
        "distribution_check" to stringResource(AmoR.string.amo_rec_distribution_check), "distribution_check_line" to stringResource(AmoR.string.amo_rec_distribution_check_line),
        "price_compliance_check" to stringResource(AmoR.string.amo_rec_price_compliance_check), "call_assessment" to stringResource(AmoR.string.amo_rec_call_assessment),
        "call_assessment_answer" to stringResource(AmoR.string.amo_rec_call_assessment_answer), "survey_response" to stringResource(AmoR.string.amo_rec_survey_response),
    )
    SalesSubmitScreen(
        online = st.online, counts = st.counts, gate = st.gate, progress = st.progress, typeLabel = { recordLabels[it] ?: it },
        onSync = vm::sync, onSubmit = vm::submit, syncing = st.syncing, rowGroups = rowGroups,
        rejected = st.rejected, dismissed = st.dismissed, serverKnown = st.serverKnown, today = day.businessDate(), vendorDialogs = true, // D-NIGHT-DAYCLOSE-01; row 66
    )
}

/** Sale edit (F-SR-033): the gate and a fresh fix first, then the reason, then the pre-filled sale route; Print supersedes the memo. */
@Composable
fun AmoEditHost(day: AmoDay, memoUuid: String, onDone: () -> Unit) {
    val kit = day.sale
    var denied by remember(memoUuid) { mutableStateOf<EditDenied?>(null) }
    var checked by remember(memoUuid) { mutableStateOf(false) }
    var reason by remember(memoUuid) { mutableStateOf<EditReason?>(null) }
    var ready by remember(memoUuid) { mutableStateOf(false) }
    // tso-manual X4a (R-W-11): an edit on a route closed by Final Submit is not started (no fix is taken) and says why;
    // one the lock refuses at the save shows the same text, nothing stored or printed. Follow-up (R-W-23): the same with
    // the submit text after the supervisor day's Sales Submit.
    var startRefused by remember(memoUuid) { mutableStateOf<DayRefusal?>(null) }
    val lockRefused by kit.lockRefused.collectAsState()
    val submitRefused by kit.submitRefused.collectAsState()
    LaunchedEffect(memoUuid) { kit.prepare(); val why = kit.editRefusal(memoUuid); if (why != null) startRefused = why else denied = kit.prepareEdit(memoUuid); checked = true }
    // only this edit's own refusal (fix round); cleared after leaving, so the edit's "save failed" never shows in between
    val refused = startRefused ?: when (memoUuid) { lockRefused -> DayRefusal.FINAL_SUBMIT; submitRefused -> DayRefusal.SALES_SUBMIT; else -> null }
    if (refused != null) {
        AmoRefusalDialog(day, refused, onOk = { onDone(); kit.clearLockRefusal() })
        return
    }
    val r = reason
    when {
        !checked -> AmoEmptyState(stringResource(AmoR.string.amo_edit_checking))
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

/**
 * Stock issue and the slip (F-SR-013/014); the printer icon and banner are on the frame, the hold is the frame's too.
 * tso-manual X4a (R-W-22): a Save the zone's Final Submit refuses (in a call on a closed route) stores and queues nothing
 * and shows the lock text; no slip is offered for it. Fix round: no stock slip prints after the lock, also not the slip of
 * a Save stored before it in a call on that route: Print is not drawn while the next slip's route is final
 * ([AmoDay.stockSlipPrintable]), re-read when the lock lands, and the print itself checks again.
 */
@Composable
fun AmoStockHost(day: AmoDay) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<StockMessage?>(null) }
    var refused by remember { mutableStateOf<DayRefusal?>(null) }
    var unprinted by remember { mutableStateOf(true) }
    var printable by remember { mutableStateOf(false) }
    val att by day.stockAttempt.collectAsState()
    val lock by day.dayLock.collectAsState()
    LaunchedEffect(att, lock) { unprinted = day.slipNotPrinted(); printable = day.stockSlipPrintable() }
    // a thrown read is an error with a retry, never a crash and never a screen stuck on the loading card
    // D-AMO-BUNDLE-01: the first bundle may land while this screen is open; the read is keyed on the bundles applied since the day opened.
    val epoch by day.bundleEpoch.collectAsState()
    val read = com.aktcl.aron.core.ui.aron.rememberAsyncLoad(epoch) { day.stockLoad().also { unprinted = day.slipNotPrinted(); printable = day.stockSlipPrintable() } }
    com.aktcl.aron.core.ui.aron.AronLoaded(read, stringResource(com.aktcl.aron.feature.stock.R.string.stk_load_failed), stringResource(com.aktcl.aron.feature.home.R.string.fb_retry)) { l ->
        version.let {} // the content recomposes on every edit: StockLoad is a plain object, this counter is its signal
    StockContent(
        rows = l.rows, totals = l.totals, message = message, slipNotPrinted = unprinted,
        onIssue = { id, q -> l.setEntered(id, q.toLong()); version++ },
        onSave = {
            scope.launch {
                val r = day.saveStock(l)
                message = r.message; refused = r.refused
                if (r.saved) { unprinted = day.slipNotPrinted(); printable = day.stockSlipPrintable(); runCatching { day.requestSync() } }
                version++
            }
        },
        // Print never blocks Save: it is offered after a Save and may be retried later (Q-UI-03); never after the lock (R-W-22).
        onPrint = if (unprinted && printable && PermissionPolicy.allowed(GatedFeature.PRINT, AndroidPermissions.snapshotOf(LocalContext.current))) ({ day.printUnprintedStock() }) else null,
    )
    }
    refused?.let { AmoRefusalDialog(day, it, onOk = { refused = null }) }
}

/** Previous sale data (screenshots 24, 26): the date steps from today back 90 days; the lines are read when the date changes. */
@Composable
fun PreviousSalesHost(day: AmoDay, outletId: Long) {
    val today = remember { day.businessDate() }
    var date by rememberSaveable { mutableStateOf(today) }
    var history by remember { mutableStateOf<com.aktcl.aron.feature.amo.SaleHistory?>(null) }
    var label by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(outletId) { label = com.aktcl.aron.core.common.orNullKeepingCancellation { day.callHeader().first } }
    LaunchedEffect(outletId, date) { history = null; history = day.saleHistory(outletId, date) }
    com.aktcl.aron.feature.amo.PreviousSalesContent(date, today, history, onPick = { date = it }, outletLabel = label)
}

/**
 * Memo find (screenshots 42-43): route, then retailer (the outlet's outstanding due beside "বাকি"), then the Memo tile opens that retailer's
 * memos of today (reprint, edit, mark paid on the detail). Local reads only; nothing here waits on the network.
 */
@Composable
private fun MemoFindStep(day: AmoDay, rows: List<com.aktcl.aron.feature.memo.domain.MemoMenuRow>, outletName: (Long) -> String, onOpen: (com.aktcl.aron.feature.memo.domain.MemoMenuRow) -> Unit) {
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var outletId by rememberSaveable { mutableStateOf<Long?>(null) }
    var chip by rememberSaveable { mutableStateOf(com.aktcl.aron.feature.outlet.OutletPicker.ALL_CHIP) }
    var panel by rememberSaveable { mutableStateOf(0) } // 0 none, 1 routes, 2 outlets
    var shown by rememberSaveable { mutableStateOf(false) }
    var days by remember { mutableStateOf<List<com.aktcl.aron.core.database.repo.RouteDay>?>(null) }
    var dues by remember { mutableStateOf<Map<Long, List<com.aktcl.aron.feature.amo.LocalDue>>>(emptyMap()) }
    LaunchedEffect(day) {
        days = runCatching { day.routes() }.getOrDefault(emptyList())
        val today = day.businessDate()
        // every memo of every day the phone keeps, the replaced versions too, with their Mark Paid records: MemoFind nets edits and collections by memo
        dues = runCatching { com.aktcl.aron.feature.memo.ui.RoomMemoStore(day.database).allWithCollections() }.getOrDefault(emptyList()).let { all ->
            all.groupBy({ it.first.outletId }, { (m, c) -> com.aktcl.aron.feature.amo.LocalDue(m.memoUuid, m.businessDate, m.committedAtIso, m.dueMtk, m.supersedesUuid, c) })
        }
    }
    BackHandler(enabled = shown || panel != 0) { if (panel != 0) panel = 0 else shown = false }
    val list = days ?: return
    val routes = com.aktcl.aron.feature.amo.ControlCall.routes(list)
    val outlets = com.aktcl.aron.feature.amo.ControlCall.outlets(list, routeId ?: -1L)
    val pick = com.aktcl.aron.feature.amo.CallPick.rows(outlets, emptySet(), chip)
    val outlet = outlets.firstOrNull { it.outletId == outletId }
    if (shown && outlet != null) {
        com.aktcl.aron.feature.memo.ui.MemoMenuScreen(rows.filter { it.outletId == outlet.outletId }, outletName, onOpen)
        return
    }
    if (panel == 1) { com.aktcl.aron.feature.amo.AmoRoutePanel(routes, routeId, { routeId = it.routeId; outletId = null; chip = com.aktcl.aron.feature.outlet.OutletPicker.ALL_CHIP; panel = 0 }); return }
    if (panel == 2) { com.aktcl.aron.feature.amo.AmoOutletPanel(pick, outletId, { outletId = it.outlet.outletId; panel = 0 }); return }
    val label = outlet?.let { o ->
        val r = com.aktcl.aron.feature.amo.MemoFind.due(o.openDueMtk, o.openDueAsOf, dues[o.outletId].orEmpty())
        com.aktcl.aron.feature.outlet.OutletPicker.label(o) + (if (r.outstandingMtk > 0) "  " + stringResource(AmoR.string.amo_mf_due, com.aktcl.aron.feature.sale.ui.money(r.outstandingMtk)) else "") +
            (if (r.overpaidMtk > 0) "  " + stringResource(AmoR.string.amo_mf_overpaid, com.aktcl.aron.feature.sale.ui.money(r.overpaidMtk)) else "") // L-c: the excess is said, never netted away
    }
    com.aktcl.aron.feature.amo.AmoCallPickContent(
        routeText = routes.firstOrNull { it.routeId == routeId }?.shortTitle, outletText = label, outletHasBase = false,
        letters = com.aktcl.aron.feature.amo.CallPick.letters(outlets), chip = chip, live = false,
        range = if (outlet != null) com.aktcl.aron.feature.amo.CallRange.In else com.aktcl.aron.feature.amo.CallRange.Idle, enabledOutlet = routeId != null, locked = false,
        onOpenRoute = { panel = 1 }, onOpenOutlet = { panel = 2 }, onChip = { chip = it }, onOverride = {}, onRefresh = {},
        below = { com.aktcl.aron.feature.amo.AmoMenuTiles(listOf(com.aktcl.aron.feature.amo.MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_memo, com.aktcl.aron.feature.home.R.string.hm_tile_memo, "amo_mf_tile") { shown = true })) },
    )
}
