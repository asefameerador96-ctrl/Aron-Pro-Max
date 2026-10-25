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
import com.aktcl.aron.feature.stock.SaveOutcome
import com.aktcl.aron.feature.stock.SaveRefusal
import com.aktcl.aron.feature.stock.StockContent
import com.aktcl.aron.feature.stock.StockMessage
import com.aktcl.aron.feature.amo.R as AmoR

/**
 * F-AMO-006: the SR sale route on the open Control Call. No start prompt, AV, KV or SR survey: the AMO is auditing the call.
 * Review's Print (or Save) ends the sale and returns to the call menu; the visit closes when the AMO leaves the call
 * ([AmoDay.closeVisit] reads the memo). One sale per call: a second tap on Sale says so (the memo is edited from Memo).
 */
@Composable
fun AmoSaleHost(day: AmoDay, onDone: () -> Unit) {
    val open by day.visitSession.current.collectAsState()
    val v = open ?: return
    val kit = day.sale
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
            Column(Modifier.fillMaxSize()) {
                SaleRoute(vm, day.printerManager, kit::skuName, modifier = Modifier.weight(1f), onFinished = { onDone() })
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
    BackHandler(enabled = st.selected != null || history != null) { if (history != null) history = null else vm.open("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val sel = st.selected
        val h = history
        when {
            h != null -> SaleHistoryScreen(h, offlineBanner = true, skuName = kit::skuName, skuCategory = kit::skuCategoryCode)
            sel == null -> MemoMenuScreen(st.rows, kit::outletName, onOpen = { vm.open(it.memoUuid) })
            else -> {
                MemoDetailView(sel, st.canMarkPaid, st.remainingDueMtk, kit::skuName, onPrint = vm::reprint, onEdit = { onEdit(sel.memo.memoUuid) }, onMarkPaid = vm::markPaid, skuCategory = kit::skuCategoryCode)
                AmoSecondary(
                    stringResource(AmoR.string.amo_sale_history),
                    { scope.launch { history = SaleHistoryBuilder.build(kit.history(), sel.memo.outletId) } },
                    Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L),
                )
                ReprintDialogs(vm)
            }
        }
    }
}

/** Sales Summary with its printed copy (F-SR-036); the print needs no connection and never blocks anything. */
@Composable
fun AmoSummaryHost(day: AmoDay) {
    val kit = day.sale
    var bundle by remember { mutableStateOf<SummaryBundle?>(null) }
    val attempt by day.summaryAttempt.collectAsState()
    LaunchedEffect(Unit) { bundle = kit.summary() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        bundle?.let { b -> SummaryScreen(b.summary, kit::skuName, onPrint = { day.printSummary(b) }) }
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
    LaunchedEffect(Unit) { rowGroups = runCatching { day.sale.submitRowGroups() }.getOrNull() }
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
        rejected = st.rejected, serverKnown = st.serverKnown, today = day.businessDate(), // D-NIGHT-DAYCLOSE-01
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
    LaunchedEffect(memoUuid) { kit.prepare(); denied = kit.prepareEdit(memoUuid); checked = true }
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

/** Stock issue and the slip (F-SR-013/014); the printer icon and banner are on the frame, the hold is the frame's too. */
@Composable
fun AmoStockHost(day: AmoDay) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<StockMessage?>(null) }
    var unprinted by remember { mutableStateOf(true) }
    val att by day.stockAttempt.collectAsState()
    LaunchedEffect(att) { unprinted = day.slipNotPrinted() }
    // a thrown read is an error with a retry, never a crash and never a screen stuck on the loading card
    val read = com.aktcl.aron.core.ui.aron.rememberAsyncLoad { day.stockLoad().also { unprinted = day.slipNotPrinted() } }
    com.aktcl.aron.core.ui.aron.AronLoaded(read, stringResource(com.aktcl.aron.feature.stock.R.string.stk_load_failed), stringResource(com.aktcl.aron.feature.home.R.string.fb_retry)) { l ->
        version.let {} // the content recomposes on every edit: StockLoad is a plain object, this counter is its signal
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
}
