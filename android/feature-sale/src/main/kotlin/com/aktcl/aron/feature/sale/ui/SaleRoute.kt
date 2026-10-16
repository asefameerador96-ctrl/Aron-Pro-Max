package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.ui.HoldPrinter
import com.aktcl.aron.core.printing.ui.PrinterBanner
import com.aktcl.aron.core.printing.ui.PrinterIcon
import com.aktcl.aron.core.printing.ui.SaveAndPrintDialogs
import com.aktcl.aron.core.ui.AronScreenTransition
import com.aktcl.aron.feature.sale.domain.QcEntry
import com.aktcl.aron.rules.QtyUnit

enum class SaleStage { Entry, Credit, Slide, Qc, Review }

/**
 * The sale flow of one visit: quantity entry, optional slide, QC and credit, then Review whose Print is the commit
 * (never disabled by the printer). [onFinished] runs after the saved message; navigation beyond the sale is the app's.
 * Each stage scrolls and carries its own bottom bar, so the route itself never scrolls (a scrolling parent would give
 * the stage lists an unbounded height).
 */
@Composable
fun SaleRoute(vm: SaleViewModel, printer: PrinterManager, skuNameOf: (Long) -> String, onFinished: (printed: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.state.collectAsState()
    var stage by remember { mutableStateOf(SaleStage.Entry) }
    val draft = ui.draft
    val review = ui.review
    val skus = ui.catalog.values.toList()
    HoldPrinter(printer)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) { PrinterIcon(printer) }
        PrinterBanner(printer)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // forward: Entry to Review, and Review into a sub-screen (Credit, QC, Slide); back: a sub-screen returning to Review
            AronScreenTransition(stage, forward = { from, to -> from == SaleStage.Entry || to != SaleStage.Review }) { s ->
                when (s) {
                    SaleStage.Entry -> SaleEntryScreen(
                        skus = skus, review = review,
                        qtyOf = { id -> draft?.lines?.firstOrNull { it.skuId == id }?.qtyEntered ?: 0L },
                        onQuantity = { id, q -> vm.setQuantity(id, q, QtyUnit.entries.first { it.wire == ui.catalog[id]?.baseUnit }) },
                        onReview = { stage = SaleStage.Review }, onZeroSaleConfirmed = { vm.confirmZeroSale(); stage = SaleStage.Review },
                    )
                    SaleStage.Slide -> if (review != null) SlideScreen(skus, review, { id -> draft?.slide?.firstOrNull { it.skuId == id }?.emptyPackets ?: 0L }, { id, n -> vm.setSlide(id, n) })
                    SaleStage.Qc -> if (review != null) QcScreen(
                        skus.filter { s -> draft?.lines?.any { it.skuId == s.skuId } == true }, review,
                        qtyOf = { id, g -> draft?.qc?.firstOrNull { it.skuId == id && it.faultGroup == g }?.defectQty ?: 0L },
                        onQty = { id, g, q -> vm.setQc(QcEntry(id, if (g == PRODUCTION) "production_fault" else "transport_fault", g, q)) },
                        locked = draft?.qcCompleted == true, onDone = { vm.completeQc(); stage = SaleStage.Review },
                        onCancel = { stage = SaleStage.Review },
                    )
                    SaleStage.Credit -> if (review != null) CreditPrompt(
                        review, { vm.setPaid(it); stage = SaleStage.Review },
                        // closing the credit prompt without confirming turns credit off (screens-sr s8)
                        onCancel = { vm.setPaid(null); stage = SaleStage.Review },
                    )
                    SaleStage.Review -> if (review != null) {
                        ReviewScreen(
                            review, onSave = vm::onPrintTapped,
                            onCreditToggle = { on -> if (on) stage = SaleStage.Credit else vm.setPaid(null) },
                            onQc = { stage = SaleStage.Qc },
                            // the Slide step exists only while a SKU carries an active offer (docs/27: offers deferred)
                            onSlide = if (skus.any { it.drp != null }) ({ stage = SaleStage.Slide }) else null,
                        )
                    }
                }
            }
        }
    }
    SaveAndPrintDialogs(vm.saveAndPrint, vm.viewModelScopeForDialogs(), onFinished)
}
