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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.SaleNotice
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.ui.HoldPrinter
import com.aktcl.aron.core.printing.ui.PrinterBanner
import com.aktcl.aron.core.printing.ui.PrinterIcon
import com.aktcl.aron.core.printing.ui.SaveAndPrintDialogs
import com.aktcl.aron.core.ui.AronScreenTransition
import com.aktcl.aron.feature.sale.domain.QcEntry
import com.aktcl.aron.feature.sale.domain.QcEntryModel

enum class SaleStage { Entry, Credit, Slide, Qc, Review }

/**
 * The sale flow of one visit: quantity entry, optional slide, QC and credit, then Review whose Print is the commit
 * (never disabled by the printer). [onFinished] runs after the saved message; navigation beyond the sale is the app's.
 * Each stage scrolls and carries its own bottom bar, so the route itself never scrolls (a scrolling parent would give
 * the stage lists an unbounded height).
 */
@Composable
fun SaleRoute(
    vm: SaleViewModel, printer: PrinterManager, skuNameOf: (Long) -> String, onFinished: (printed: Boolean) -> Unit, modifier: Modifier = Modifier,
    /** The retailer pill of the list screen (`name (code-phone-cluster)`) and the review header lines; null/empty leave them out. */
    retailerLabel: String? = null, outletLines: List<String> = emptyList(),
    /** The previous-sales button of the entry screen (the AMO's "Sale History", 24/26); null hides it, as in the SR app. [historyLabel] overrides its text. */
    onPreviousSales: (() -> Unit)? = null, historyLabel: String? = null,
    /** The SR asks for the zero-sale reason after "yes" (manual s10); the AMO keeps the one-step confirm. */
    zeroSaleReasonPicker: Boolean = false,
    /** The SR's quantity popup and its reference figures (manual s8 / 22); off for the AMO. */
    quantityPopup: Boolean = false, skuStats: Map<Long, com.aktcl.aron.feature.sale.domain.SkuStats> = emptyMap(),
    /** D-SR-MAN-S3-02: ask the nearby-devices permission on entering and toast "printer connected" (the SR manual, screenshot 17); AMO keeps false. */
    printerAssist: Boolean = false,
    /** P-SR2 (manuals 25, 26): the vendor's QC pages (tiles, entry table with সংরক্ষণ / বাতিল / ডিলিট); off for the AMO. [onQcEntryPage] tells the host when the entry page is open. */
    vendorQc: Boolean = false, onQcEntryPage: (Boolean) -> Unit = {},
    /** The SR host's bar title follows the stage ("QC", "QC এন্ট্রি", "স্লাইড", manuals 25, 26, 33): the stage is reported when it changes. */
    onStage: (SaleStage) -> Unit = {},
    /** The way from the "add today's stock first" banner to the Stock screen; null shows the banner without the button. */
    onOpenStock: (() -> Unit)? = null,
    /** The AMO host's options (D-PAR-AMO-07): an entry header card and the print-later info card. SR passes the defaults and is unchanged. */
    entryHeaderCard: (@Composable () -> Unit)? = null, printLaterCard: Boolean = false, amoSkuCard: Boolean = false, creditAsDialog: Boolean = false, totalsQuantity: Boolean = false,
) {
    val ui by vm.state.collectAsState()
    var stage by remember { mutableStateOf(SaleStage.Entry) }
    var qcOnlyStarted by remember { mutableStateOf(false) }
    var qcBefore by remember { mutableStateOf(emptyList<QcEntry>()) }
    val draft = ui.draft
    val review = ui.review
    val skus = ui.catalog.values.toList()
    HoldPrinter(printer, assist = printerAssist)
    // QC submitted: the sale cannot change, so the entry and slide lists are never shown again (after a rotation or a relaunch the stage is back on Entry): Review opens instead
    val locked = draft?.qcCompleted == true
    val shown = if (locked && (stage == SaleStage.Entry || stage == SaleStage.Slide)) SaleStage.Review else stage
    androidx.compose.runtime.LaunchedEffect(shown) { onStage(shown) }
    // The vendor's QC tile page has no Cancel button: Back is its only way out, and it keeps the landed two-step screen's safeguard (lead review B2).
    //  - QC submitted: nothing to put back; Back just leaves for Review (restoreQc on a locked sale would raise the "QC locked" notice on a plain Back);
    //  - QC typed and not submitted: ask "discard everything entered in QC?" first (Yes = the old Cancel, No keeps everything);
    //  - nothing typed: the old Cancel at once (a QC-only sale is dropped, otherwise what was typed since Review is put back).
    var askQcDiscard by remember { mutableStateOf(false) }
    val leaveQc = { if (qcOnlyStarted) { qcOnlyStarted = false; vm.cancelQcOnly(); stage = SaleStage.Entry } else { vm.restoreQc(qcBefore); stage = SaleStage.Review } }
    androidx.activity.compose.BackHandler(enabled = vendorQc && stage == SaleStage.Qc) {
        when {
            locked -> stage = SaleStage.Review
            review?.qc?.isNotEmpty() == true -> askQcDiscard = true
            else -> leaveQc()
        }
    }
    if (askQcDiscard && vendorQc && stage == SaleStage.Qc) {
        com.aktcl.aron.core.ui.AronConfirmDialog(
            stringResource(R.string.qc_confirm_title), stringResource(R.string.qc_discard_message),
            stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
            onConfirm = { askQcDiscard = false; leaveQc() }, onDismiss = { askQcDiscard = false }, manual = true,
        )
    }
    Column(modifier.fillMaxSize()) {
        ui.notice?.let { n ->
            AronBanner(
                stringResource(when (n) { SaleNotice.Locked -> R.string.qc_locked; SaleNotice.QcDropped -> R.string.qc_dropped_notice; SaleNotice.Refused -> R.string.sale_change_refused }),
                Modifier.padding(horizontal = 12.dp, vertical = AronTokens.Space.S).testTag(SaleTags.NOTICE), kind = BannerKind.Info,
            )
        }
        // the printer state hangs on the host's top bar (the kit badge); nothing printer-shaped is drawn in here any more
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // forward: Entry to Review, and Review into a sub-screen (Credit, QC, Slide); back: a sub-screen returning to Review
            AronScreenTransition(if (quantityPopup && shown == SaleStage.Credit) SaleStage.Review else shown, forward = { from, to -> from == SaleStage.Entry || to != SaleStage.Review }) { s ->
                when (s) {
                    SaleStage.Entry -> SaleEntryScreen(
                        skus = skus, review = review,
                        // a line held in another unit (an old memo that is not whole packets) shows 0: the rep re-enters it, a tap never multiplies it
                        qtyOf = { id -> draft?.lines?.firstOrNull { it.skuId == id && it.unit == ui.catalog[id]?.entry?.unit?.wire }?.qtyEntered ?: 0L },
                        onQuantity = { id, q -> vm.setQuantity(id, q, checkNotNull(ui.catalog[id]) { "unknown SKU" }.entry.unit) },
                        onReview = { stage = SaleStage.Review },
                        // an edit never starts a QC-only sale: completed QC would lock the edit for good (F-SR-033)
                        onQcOnly = if (draft?.edit == null) ({ qcBefore = emptyList(); qcOnlyStarted = true; vm.startQcOnly(); stage = SaleStage.Qc }) else null,
                        onZeroSaleConfirmed = { vm.confirmZeroSale(); stage = SaleStage.Review },
                        quantityPopup = quantityPopup, skuStats = skuStats, onOpenStock = onOpenStock,
                        onZeroSaleReason = if (zeroSaleReasonPicker) ({ r -> vm.confirmZeroSale(r.code); stage = SaleStage.Review }) else null,
                        retailerLabel = retailerLabel,
                        onPreviousSales = onPreviousSales, historyLabel = historyLabel, headerCard = entryHeaderCard, amoSkuCard = amoSkuCard,
                    )
                    SaleStage.Slide -> if (review != null && vendorQc) SlideManualScreen(
                        skus, review, { id -> draft?.slide?.firstOrNull { it.skuId == id }?.emptyPackets ?: 0L }, { id, n -> vm.setSlide(id, n) },
                        onSubmit = { stage = SaleStage.Review }, outletLines = outletLines,
                    ) else if (review != null) SlideScreen(skus, review, { id -> draft?.slide?.firstOrNull { it.skuId == id }?.emptyPackets ?: 0L }, { id, n -> vm.setSlide(id, n) })
                    SaleStage.Qc -> if (review != null && vendorQc) QcManualScreen(
                        if (draft?.lines.isNullOrEmpty()) skus else skus.filter { s -> draft?.lines?.any { it.skuId == s.skuId } == true }, review,
                        qtyOf = { id, code -> draft?.qc?.firstOrNull { it.skuId == id && it.faultTypeCode == code }?.defectQty ?: 0L },
                        onQty = { id, code, q -> QcEntryModel.groupOf(code)?.let { g -> vm.setQc(QcEntry(id, code, g, q)) } },
                        capMtkOf = { id -> ui.qcCapMtkBySku[id] },
                        onClearSku = { id -> QcEntryModel.APP_TYPES.forEach { t -> vm.setQc(QcEntry(id, t.code, t.group, 0)) } },
                        locked = draft?.qcCompleted == true, onDone = { qcOnlyStarted = false; vm.completeQc(); stage = SaleStage.Review },
                        onEntryPage = onQcEntryPage,
                        // বাতিল on the entry page: ONE restore with this SKU's opening counts and every other SKU's lines as they are (not one write per fault type)
                        onRestoreSku = { id, counts ->
                            vm.restoreQc(
                                draft?.qc.orEmpty().filter { it.skuId != id } +
                                    counts.mapNotNull { (code, q) -> QcEntryModel.groupOf(code)?.let { g -> QcEntry(id, code, g, q) } }.filter { it.defectQty > 0 },
                            )
                        },
                    ) else if (review != null) QcScreen(
                        // the memo's SKUs; with no sale line the whole catalog is offered (QC-only sale, D-18)
                        if (draft?.lines.isNullOrEmpty()) skus else skus.filter { s -> draft?.lines?.any { it.skuId == s.skuId } == true }, review,
                        qtyOf = { id, code -> draft?.qc?.firstOrNull { it.skuId == id && it.faultTypeCode == code }?.defectQty ?: 0L },
                        onQty = { id, code, q -> QcEntryModel.groupOf(code)?.let { g -> vm.setQc(QcEntry(id, code, g, q)) } },
                        capMtkOf = { id -> ui.qcCapMtkBySku[id] },
                        onClearSku = { id -> QcEntryModel.APP_TYPES.forEach { t -> vm.setQc(QcEntry(id, t.code, t.group, 0)) } },
                        locked = draft?.qcCompleted == true, onDone = { qcOnlyStarted = false; vm.completeQc(); stage = SaleStage.Review },
                        onCancel = { if (qcOnlyStarted) { qcOnlyStarted = false; vm.cancelQcOnly(); stage = SaleStage.Entry } else { vm.restoreQc(qcBefore); stage = SaleStage.Review } }, // Cancel discards what was typed since the screen opened
                        collapsible = draft?.lines.isNullOrEmpty(), twoStep = quantityPopup, // the SR's two steps (manual 25, 26); the AMO keeps the one-scroll screen
                    )
                    SaleStage.Credit -> if (review != null) CreditPrompt(
                        review, { vm.setPaid(it); stage = SaleStage.Review },
                        // closing the credit prompt without confirming turns credit off (screens-sr s8)
                        onCancel = { vm.setPaid(null); stage = SaleStage.Review }, asDialog = creditAsDialog,
                    )
                    SaleStage.Review -> if (review != null) {
                        ReviewScreen(
                            review, onSave = vm::onPrintTapped, outletLine = outletLines.joinToString("\n").takeIf { it.isNotBlank() },
                            onCreditToggle = { on -> if (on) stage = SaleStage.Credit else vm.setPaid(null) },
                            onQc = if (draft?.edit == null) ({ qcBefore = draft?.qc.orEmpty(); stage = SaleStage.Qc }) else null,
                            // the Slide step exists only while a SKU carries an active offer (docs/27: offers deferred)
                            onSlide = if (!locked && skus.any { it.drp != null }) ({ stage = SaleStage.Slide }) else null,
                            printLaterCard = printLaterCard, totalsQuantity = totalsQuantity,
                        )
                    }
                }
            }
        }
    }
    // the SR's partial payment is a box over the Review (manual 27), not a screen of its own: the same amount rules and the same setPaid
    if (quantityPopup && stage == SaleStage.Credit && review != null) {
        CreditDialog(review, { vm.setPaid(it); stage = SaleStage.Review }, onCancel = { vm.setPaid(null); stage = SaleStage.Review })
    }
    // a save the transaction refused is worded with the SAME problem texts the review uses (SKU, held figure, unit; or "already edited")
    val refusalText = ui.refusal.takeIf { it.isNotEmpty() }?.map { problemText(it) }?.joinToString("\n\n")
    SaveAndPrintDialogs(vm.saveAndPrint, vm.viewModelScopeForDialogs(), onFinished, refusalText, manual = quantityPopup)
    // D-AGATE-25: the Save had already been stored; changes made after it are not in the memo
    var laterShown by remember(ui.committed?.memoUuid) { mutableStateOf(true) }
    if (ui.laterChangesNotSaved && laterShown) {
        com.aktcl.aron.core.ui.aron.AronDialog(
            stringResource(R.string.review_later_title), stringResource(R.string.review_later_body), stringResource(R.string.sale_yes_ok),
            onConfirm = { laterShown = false }, onDismiss = { laterShown = false },
        )
    }
}
