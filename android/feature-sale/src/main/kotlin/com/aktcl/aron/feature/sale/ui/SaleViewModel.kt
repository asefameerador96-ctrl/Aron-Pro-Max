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

    fun start(visit: SaleVisit) { viewModelScope.launch { flow.start(visit) } }
    fun setQuantity(skuId: Long, qty: Long, unit: QtyUnit) { viewModelScope.launch { flow.setQuantity(skuId, qty, unit) } }
    fun setSlide(skuId: Long, empties: Long) { viewModelScope.launch { flow.setSlide(skuId, empties) } }
    fun setQc(entry: QcEntry) { viewModelScope.launch { flow.setQc(entry) } }
    fun setPaid(paidMtk: Long?) { viewModelScope.launch { flow.setPaid(paidMtk) } }
    fun confirmZeroSale() { viewModelScope.launch { flow.confirmZeroSale() } }
    fun completeQc() { viewModelScope.launch { flow.completeQc() } }
    fun beginEdit(edit: EditContext) { viewModelScope.launch { flow.withEdit(edit) } }
    /** The scope the print dialogs run in: it outlives the screen's composition, so a rotation never cancels a save or a print. */
    fun viewModelScopeForDialogs() = viewModelScope

    /** Review's Print button: asks "save?" first; the commit happens on Yes (SaveAndPrint step 1). */
    fun onPrintTapped() = saveAndPrint.onPrintTapped()
}
