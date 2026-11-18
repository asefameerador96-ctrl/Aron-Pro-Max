package com.aktcl.aron.feature.sale.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.feature.sale.domain.EditContext
import com.aktcl.aron.feature.sale.domain.QcEntry
import com.aktcl.aron.feature.sale.domain.SaleFlow
import com.aktcl.aron.feature.sale.domain.SaleUiState
import com.aktcl.aron.feature.sale.domain.SaleVisit
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Screen model of the sale (entry, slide, QC, credit, review, Save and Print). Everything runs in [viewModelScope], so a
 * rotation never cancels a save or a print. [saveAndPrint] is built by the app shell from [SaleCommitStep] and the
 * process's `MemoPrinting`; with the printer off the sale still saves (Print later), as docs/15 requires.
 */
class SaleViewModel(
    private val flow: SaleFlow,
    val saveAndPrint: SaveAndPrint,
) : ViewModel() {
    val state: StateFlow<SaleUiState> get() = flow.state

    /** A draft that refuses a change (the sale is locked after QC, a value out of range) is shown as a notice; it never takes the app down. Any other failure is still a crash: it is a bug. */
    private val guard = CoroutineExceptionHandler { _, e ->
        if (e is IllegalStateException || e is IllegalArgumentException) flow.noteRefusal() else throw e
    }

    fun start(visit: SaleVisit) { viewModelScope.launch(guard) { flow.start(visit) } }
    fun setQuantity(skuId: Long, qty: Long, unit: QtyUnit) { viewModelScope.launch(guard) { flow.setQuantity(skuId, qty, unit) } }
    fun setSlide(skuId: Long, empties: Long) { viewModelScope.launch(guard) { flow.setSlide(skuId, empties) } }
    fun setQc(entry: QcEntry) { viewModelScope.launch(guard) { flow.setQc(entry) } }
    fun setPaid(paidMtk: Long?) { viewModelScope.launch(guard) { flow.setPaid(paidMtk) } }
    fun confirmZeroSale(reasonCode: String? = null) { viewModelScope.launch(guard) { flow.confirmZeroSale(reasonCode) } }
    fun startQcOnly() { viewModelScope.launch(guard) { flow.startQcOnly() } }
    fun cancelQcOnly() { viewModelScope.launch(guard) { flow.cancelQcOnly() } }
    fun restoreQc(entries: List<QcEntry>) { viewModelScope.launch(guard) { flow.restoreQc(entries) } }
    fun completeQc() { viewModelScope.launch(guard) { flow.completeQc() } }
    fun beginEdit(edit: EditContext) { viewModelScope.launch(guard) { flow.withEdit(edit) } }
    /** The scope the print dialogs run in: it outlives the screen's composition, so a rotation never cancels a save or a print. */
    fun viewModelScopeForDialogs() = viewModelScope

    /** Review's Print button: asks "save?" first; the commit happens on Yes (SaveAndPrint step 1). */
    fun onPrintTapped() = saveAndPrint.onPrintTapped()
}
