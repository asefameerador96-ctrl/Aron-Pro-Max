package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronQtyField
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.aron.PackshotImage
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.QcEntryModel
import com.aktcl.aron.feature.sale.domain.SaleReview
import com.aktcl.aron.feature.sale.domain.SaleSku

/**
 * Product QC in the vendor's two pages (manual 25, 26): the TILE page (info card, a lavender panel of pack tiles, "QC সারাংশ" with the saved
 * lines and the settlement, a deep-purple bar with "QC জমা দিন →" that asks before it submits) and the ENTRY page for one SKU (the maximum,
 * done and remaining card, the table of fault type, kind and quantity, and the buttons সংরক্ষণ / বাতিল / ডিলিট).
 *
 * The counts are written to the draft as they are typed, through the same [onQty] and the same cap logic as the earlier screen, so a row can
 * never pass what is left under the cap; সংরক্ষণ keeps them and returns to the tiles, বাতিল puts back what the page opened with, ডিলিট clears the SKU.
 * Nothing here reads the network. [onEntryPage] tells the host when the entry page is open (its title is "QC এন্ট্রি", back leaves it).
 */
@Composable
fun QcManualScreen(
    skus: List<SaleSku>,
    review: SaleReview,
    qtyOf: (skuId: Long, faultCode: String) -> Long,
    onQty: (skuId: Long, faultCode: String, qty: Long) -> Unit,
    locked: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    capMtkOf: (skuId: Long) -> Long? = { null },
    onClearSku: (skuId: Long) -> Unit = {},
    onEntryPage: (Boolean) -> Unit = {},
    /** Opens on the entry page of this SKU (restores a page after a process restart; the goldens). */
    startOnEntrySku: Long? = null,
) {
    var picked by rememberSaveable { mutableStateOf(startOnEntrySku) }
    var opened by remember { mutableStateOf(emptyMap<String, Long>()) }
    val sku = skus.firstOrNull { it.skuId == picked }
    androidx.compose.runtime.LaunchedEffect(sku != null) { onEntryPage(sku != null) }
    // back on the entry page is the vendor's বাতিল (what the page opened with comes back); on the tiles the host's back applies
    androidx.activity.compose.BackHandler(enabled = sku != null) { if (sku != null) { opened.forEach { (code, q) -> onQty(sku.skuId, code, q) }; picked = null } }
    if (sku == null) {
        QcTiles(skus, review, locked, onPick = { s ->
            opened = QcEntryModel.APP_TYPES.associate { it.code to qtyOf(s.skuId, it.code) }
            picked = s.skuId
        }, onDone = onDone, modifier = modifier)
    } else {
        QcEntryPage(
            sku, review, qtyOf, onQty, locked, capMtkOf(sku.skuId), modifier,
            onSave = { picked = null },
            onCancel = { opened.forEach { (code, q) -> onQty(sku.skuId, code, q) }; picked = null },
            onDelete = { onClearSku(sku.skuId); picked = null },
        )
    }
}

@Composable
private fun QcTiles(skus: List<SaleSku>, review: SaleReview, locked: Boolean, onPick: (SaleSku) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val look = LocalAronLook.current
    var ask by remember { mutableStateOf(false) }
    val entries = review.qc.map { it.entry }
    Column(modifier.fillMaxSize().background(look.page)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AronInfoCard(stringResource(if (locked) R.string.qc_locked else R.string.qc_pick))
            // the lavender panel of pack tiles, scrolling sideways
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(QcLavender).horizontalScroll(rememberScrollState()).padding(AronTokens.Space.M),
                horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
            ) {
                skus.forEach { s ->
                    Column(
                        Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).background(look.card).heightIn(min = AronLook.Touch)
                            .clickable(enabled = !locked, role = Role.Button) { onPick(s) }.padding(AronTokens.Space.S).testTag(QcTags.TILE),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs),
                    ) {
                        PackshotImage(s.skuId.toString(), Modifier.size(64.dp, 80.dp), category = packCategoryOf(s.categoryCode))
                        Text(skuDisplayName(s), color = look.purple, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
            Text(stringResource(R.string.qc_summary), color = look.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (review.qc.isEmpty()) {
                Text(stringResource(R.string.qc_empty), color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.L))
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)) {
                    QcHeader(stringResource(R.string.qc_col_sku), stringResource(R.string.qc_col_qctype), stringResource(R.string.qc_col_qty))
                    QcEntryModel.summary(entries).forEachIndexed { i, r ->
                        val s = skus.firstOrNull { it.skuId == r.skuId }
                        val kinds = listOfNotNull(
                            stringResource(R.string.qc_kind_mfc).takeIf { r.mfcQty > 0 }, stringResource(R.string.qc_kind_mkt).takeIf { r.mktQty > 0 },
                        ).joinToString(",")
                        Row(
                            Modifier.fillMaxWidth().background(if (i % 2 == 0) look.card else look.rowAlt).heightIn(min = AronLook.Touch).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S).testTag(QcTags.SUMMARY_ROW),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
                        ) {
                            Text(s?.let { skuDisplayName(it) } ?: r.skuId.toString(), Modifier.weight(1.3f), color = look.purple, maxLines = 2)
                            Text(kinds, Modifier.weight(1.2f), color = look.ink, fontSize = 13.sp, textAlign = TextAlign.Center)
                            Text(localizedNumber(r.totalQty), Modifier.widthIn(min = 40.dp), color = look.ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                        }
                    }
                    Column(Modifier.fillMaxWidth().background(look.tableHeader).padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                        QcTotalLine(stringResource(R.string.qc_total_settled), localizedNumber(review.qc.sumOf { it.entry.defectQty }))
                        QcTotalLine(stringResource(R.string.qc_settlement), money(review.totals.qcDeductionMtk))
                    }
                }
            }
        }
        // the deep-purple bar with the gradient "QC জমা দিন →"
        Row(
            Modifier.fillMaxWidth().background(look.panel).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(AronTokens.Space.M),
            horizontalArrangement = Arrangement.End,
        ) {
            GradientButton(stringResource(R.string.qc_done) + " " + stringResource(R.string.qc_submit_arrow), enabled = !locked, onClick = { ask = true }, modifier = Modifier.widthIn(min = 200.dp))
        }
    }
    if (ask) AronConfirmDialog(
        stringResource(R.string.qc_confirm_title), stringResource(R.string.qc_confirm_message), stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
        onConfirm = { ask = false; onDone() }, onDismiss = { ask = false },
    )
}

@Composable
private fun QcEntryPage(
    sku: SaleSku, review: SaleReview, qtyOf: (Long, String) -> Long, onQty: (Long, String, Long) -> Unit, locked: Boolean, cap: Long?, modifier: Modifier,
    onSave: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val look = LocalAronLook.current
    val entries = review.qc.map { it.entry }
    val done = QcEntryModel.doneMtk(sku, entries)
    val groups = listOf(QcEntryModel.PRODUCTION to R.string.qc_production, QcEntryModel.TRANSPORT to R.string.qc_transport)
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        // the lavender card: the pack and the three amounts (manual 26)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(QcLavender).padding(AronTokens.Space.M).testTag(QcTags.CARD),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            PackshotImage(sku.skuId.toString(), Modifier.size(64.dp, 80.dp), category = packCategoryOf(sku.categoryCode))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                if (cap != null) QcLine(R.string.qc_max, money(cap))
                QcLine(R.string.qc_done_amount, money(done))
                QcEntryModel.remainingMtk(cap, done)?.let { QcLine(R.string.qc_remaining, money(it)) }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)) {
            QcHeader(stringResource(R.string.qc_col_fault), stringResource(R.string.qc_col_kind), stringResource(R.string.qc_col_qty))
            groups.forEachIndexed { gi, (group, label) ->
                QcEntryModel.typesOf(group).forEachIndexed { ti, type ->
                    val otherQc = review.qc.filterNot { it.entry.skuId == sku.skuId && it.entry.faultTypeCode == type.code }.sumOf { it.settlementMtk }
                    val memoRoom = QcEntryModel.memoRoomMtk(review.totals.grossMtk, review.totals.offerDiscountMtk + review.totals.drpDiscountMtk, otherQc)
                    val max = if (locked) 0 else QcEntryModel.maxQty(sku, entries, type.code, cap, memoRoom).coerceAtMost(QcEntryModel.MAX_QTY).toInt()
                    Row(
                        Modifier.fillMaxWidth().background(if (gi == 0) look.card else look.rowAlt).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
                    ) {
                        Text(if (ti == 0) stringResource(label) else "", Modifier.weight(0.9f), color = look.purple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(qcTypeLabel(type.code), Modifier.weight(1.5f), color = look.ink, fontSize = 14.sp)
                        AronQtyField(
                            qtyOf(sku.skuId, type.code).toInt(), { if (!locked) onQty(sku.skuId, type.code, it.toLong()) },
                            name = qcTypeLabel(type.code) + " " + skuDisplayName(sku), max = max, enabled = !locked,
                            clampedText = { stringResource(R.string.qc_typed_max, localizedNumber(it.toLong())) },
                            modifier = Modifier.widthIn(min = 72.dp, max = 96.dp).testTag(QcTags.QTY_FIELD),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height8().background(look.tableHeader))
        }
        GradientButton(stringResource(R.string.qc_save), enabled = !locked, onClick = onSave, modifier = Modifier.fillMaxWidth().testTag(QcTags.SAVE))
        Box(
            Modifier.fillMaxWidth().heightIn(min = AronLook.ButtonHeight).clip(RoundedCornerShape(AronLook.ButtonRadius)).background(Color(0xFFBDBDBD)).clickable(role = Role.Button, onClick = onCancel).testTag(QcTags.CANCEL),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.qc_cancel), color = look.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(AronTokens.Space.M)) }
        Box(
            Modifier.fillMaxWidth().heightIn(min = AronLook.ButtonHeight).clip(RoundedCornerShape(AronLook.ButtonRadius))
                .background(Brush.horizontalGradient(listOf(Color(0xFFE65100), Color(0xFFC62828)))).clickable(enabled = !locked, role = Role.Button, onClick = onDelete).testTag(QcTags.DELETE),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.qc_clear), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(AronTokens.Space.M)) }
    }
}

private fun Modifier.height8(): Modifier = this.heightIn(min = 8.dp, max = 8.dp)

@Composable
private fun QcLine(label: Int, value: String) {
    val look = LocalAronLook.current
    Text(stringResource(label) + ": " + value, color = look.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun QcTotalLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

/** The charcoal header strip of the vendor's QC tables. */
@Composable
private fun QcHeader(a: String, b: String, c: String) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().background(look.tableHeader).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.M), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S), verticalAlignment = Alignment.CenterVertically) {
        Text(a, Modifier.weight(1.3f), color = Color.White, fontWeight = FontWeight.Bold)
        Text(b, Modifier.weight(1.2f), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(c, Modifier.widthIn(min = 72.dp), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

/** A solid-gradient action in the bar's red-to-purple, white text; disabled it greys out. */
@Composable
private fun GradientButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val brush = if (enabled) Brush.horizontalGradient(listOf(look.barStart, look.barMid, look.barEnd)) else Brush.horizontalGradient(listOf(look.primaryDisabled, look.primaryDisabled))
    Box(
        modifier.heightIn(min = AronLook.ButtonHeight).clip(RoundedCornerShape(AronLook.ButtonRadius)).background(brush).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) look.onBar else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S)) }
}

/** The pale lavender of the vendor's QC panels (`#EFE7F6`): the ink on it is the kit's dark text. */
private val QcLavender = Color(0xFFEFE7F6)
