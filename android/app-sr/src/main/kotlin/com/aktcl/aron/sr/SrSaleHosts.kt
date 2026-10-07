package com.aktcl.aron.sr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.core.printing.ui.HoldPrinter
import com.aktcl.aron.core.printing.ui.PrinterBanner
import com.aktcl.aron.core.printing.ui.PrinterIcon
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronInfoDialog
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.SunlightToggle
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
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.feature.sale.ui.EditReasonScreen
import com.aktcl.aron.feature.sale.ui.SaleRoute
import com.aktcl.aron.feature.sale.ui.StartCallPrompt
import kotlinx.coroutines.launch

/** Top bar of the sale-side screens: the printer icon and the Sunlight switch on the glass chrome, the printer banner under it. */
@Composable
private fun SrChrome(day: SrDay, sunlight: Boolean, onSunlight: (Boolean) -> Unit, withPrinter: Boolean = true) {
    val pm = day.printerManager
    if (withPrinter) HoldPrinter(pm)
    Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (withPrinter) PrinterIcon(pm)
        SunlightToggle(sunlight, onSunlight)
    }
    if (withPrinter) PrinterBanner(pm)
}

/** The call: start prompt, then the sale route; the visit closes `sold` or `zero_sale_stock_ok` when Review has saved (F-SR-060/023/025). */
@Composable
fun SaleHost(day: SrDay, sunlight: Boolean, onSunlight: (Boolean) -> Unit, onNoSale: () -> Unit, onDone: () -> Unit) {
    val open by day.visitSession.current.collectAsState()
    val v = open ?: return
    val kit = day.sale
    val scope = rememberCoroutineScope()
    var started by rememberSaveable(v.visitUuid) { mutableStateOf(false) }
    if (!started) {
        val name = day.dayData.value.outlets.firstOrNull { it.outletId == v.outletId }?.name.orEmpty()
        StartCallPrompt(name, onYes = { kit.callStarted[v.visitUuid] = day.iso(day.currentMs()); started = true }, onNo = { scope.launch { day.closeVisitAbandoned(); onDone() } })
        return
    }
    val vm = viewModel(key = "sale-" + v.visitUuid) { kit.newSaleViewModel() }
    LaunchedEffect(v.visitUuid) { kit.prepare(); vm.start(kit.saleVisit(v)) }
    Column(Modifier.fillMaxSize()) {
        SrChrome(day, sunlight, onSunlight, withPrinter = false) // SaleRoute carries the printer icon and banner
        SaleRoute(
            vm, day.printerManager, kit::skuName, modifier = Modifier.weight(1f),
            onFinished = {
                scope.launch {
                    val zero = vm.state.value.draft?.zeroSale == true
                    kit.closeVisit(if (zero) VisitOutcomeCode.ZERO_SALE else VisitOutcomeCode.SOLD)
                    onDone()
                }
            },
        )
        AronSecondaryButton(stringResource(R.string.sr_no_sale), onNoSale, Modifier.fillMaxWidth().padding(AronTokens.Space.L))
    }
}

/** The no-sale outcomes of F-SR-057; each writes the visit's one `visit_close`. */
@Composable
fun NoSaleHost(day: SrDay, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        outcomeRows.forEach { (code, label) -> AronListRow(stringResource(label), onClick = { scope.launch { day.sale.closeVisit(code); onDone() } }) }
    }
}

/** The reason for skipping [outlet] from the list (`visit_skip`, F-SR-057): the same outcome list, no fix, not a visit. */
@Composable
fun SkipHost(day: SrDay, outlet: com.aktcl.aron.core.database.entity.OutletEntity, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        androidx.compose.material3.Text(outlet.name, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.padding(AronTokens.Space.L))
        outcomeRows.forEach { (code, label) -> AronListRow(stringResource(label), onClick = { scope.launch { day.sale.skipOutlet(outlet, code); onDone() } }) }
    }
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
    LaunchedEffect(Unit) { kit.prepare(); vm.refresh() }
    BackHandler(enabled = st.selected != null || history != null) { if (history != null) history = null else vm.open("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SrChrome(day, sunlight, onSunlight)
        val sel = st.selected
        val h = history
        when {
            h != null -> SaleHistoryScreen(h, offlineBanner = true)
            sel == null -> MemoMenuScreen(st.rows, kit::outletName, onOpen = { vm.open(it.memoUuid) })
            else -> {
                MemoDetailView(sel, st.canMarkPaid, st.remainingDueMtk, kit::skuName, onPrint = vm::reprint, onEdit = { onEdit(sel.memo.memoUuid) }, onMarkPaid = vm::markPaid)
                AronSecondaryButton(
                    stringResource(R.string.sr_sale_history),
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
fun SummaryHost(day: SrDay, sunlight: Boolean, onSunlight: (Boolean) -> Unit) {
    val kit = day.sale
    val scope = rememberCoroutineScope()
    var bundle by remember { mutableStateOf<SummaryBundle?>(null) }
    var attempt by remember { mutableStateOf<PrintAttempt?>(null) }
    LaunchedEffect(Unit) { bundle = kit.summary() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SrChrome(day, sunlight, onSunlight)
        bundle?.let { b -> SummaryScreen(b.summary, kit::skuName, onPrint = { scope.launch { attempt = kit.printSummary(b) } }) }
    }
    PrintAttemptDialogs(day, attempt) { attempt = null }
}

@Composable
fun PrintAttemptDialogs(day: SrDay, attempt: PrintAttempt?, onClear: () -> Unit) {
    val scope = rememberCoroutineScope()
    when (val a = attempt) {
        // A tap outside the dialog or Back never records "not readable": only the two buttons answer (a wrong "no" would unlock a second original).
        is PrintAttempt.AwaitingConfirmation -> androidx.compose.material3.AlertDialog(
            onDismissRequest = {}, text = { androidx.compose.material3.Text(stringResource(com.aktcl.aron.core.printing.R.string.ui_print_readable_question)) },
            confirmButton = { androidx.compose.material3.TextButton({ scope.launch { day.printing.confirm(a, true); onClear() } }) { androidx.compose.material3.Text(stringResource(R.string.sr_yes)) } },
            dismissButton = { androidx.compose.material3.TextButton({ scope.launch { day.printing.confirm(a, false); onClear() } }) { androidx.compose.material3.Text(stringResource(R.string.sr_no)) } },
        )
        PrintAttempt.LimitReached -> AronInfoDialog(stringResource(R.string.sr_print_confirm_title), stringResource(com.aktcl.aron.core.printing.R.string.ui_print_limit_reached), stringResource(R.string.sr_ok), onClear)
        is PrintAttempt.Failed, PrintAttempt.TooLong -> AronInfoDialog(stringResource(R.string.sr_print_confirm_title), stringResource(com.aktcl.aron.core.printing.R.string.ui_print_failed), stringResource(R.string.sr_ok), onClear)
        else -> Unit
    }
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
    )
}

@Composable
fun JourneyHost(day: SrDay) {
    var j by remember { mutableStateOf<com.aktcl.aron.feature.memo.domain.Journey?>(null) }
    LaunchedEffect(Unit) { j = day.sale.journey() }
    j?.let { JourneyScreen(it) }
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
    val units = mapOf("stick" to stringResource(R.string.sr_unit_stick), "piece" to stringResource(R.string.sr_unit_piece), "dozen" to stringResource(R.string.sr_unit_dozen))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        data?.let { (strip, money) ->
            KpiStripView(strip, categoryLabel = { cats[it] ?: it }, unitLabel = { units[it] ?: it })
            MoneyCardsView(money, categoryLabel = { cats[it] ?: it })
        }
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
    LaunchedEffect(memoUuid) { kit.prepare(); denied = kit.prepareEdit(memoUuid); checked = true }
    val r = reason
    when {
        !checked -> AronEmptyState(stringResource(R.string.sr_edit_checking))
        denied != null || r == null -> EditReasonScreen(denied, onReason = { reason = it })
        else -> {
            LaunchedEffect(r) { kit.beginEdit(memoUuid, r); ready = true }
            if (ready) {
                val vm = viewModel(key = "edit-" + memoUuid) { kit.newSaleViewModel() }
                Column(Modifier.fillMaxSize()) {
                    SrChrome(day, sunlight, onSunlight, withPrinter = false)
                    SaleRoute(vm, day.printerManager, kit::skuName, modifier = Modifier.weight(1f), onFinished = { onDone() })
                }
            }
        }
    }
}
