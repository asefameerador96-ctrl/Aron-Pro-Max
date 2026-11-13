package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.core.ui.aron.SolidTone
import com.aktcl.aron.feature.memo.R

/**
 * Lane P-SR2: the vendor's memo screen pieces (owner's manual 36 and 38), used by the SR through `vendorLayout`; the AMO and TSO keep
 * their own layout. The bar gradient, the dark letter bar and the light-grey subtotal block come from the look tokens, so Sunlight
 * turns them solid and keeps the key figures at the kit's contrast.
 */
@Composable
internal fun vendorBarBrush(): Brush {
    val look = LocalAronLook.current
    return Brush.horizontalGradient(listOf(look.barStart, look.barMid, look.barEnd))
}

/** The dark rounded letter bar: "All" in a gradient chip and the letters of the memo's SKUs, wrapping onto a second row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VendorLetterBar(letters: List<Char>, selected: Char?, onPick: (Char?) -> Unit) {
    if (letters.size < 2) return
    val look = LocalAronLook.current
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.panelDark).padding(horizontal = AronTokens.Space.S, vertical = AronTokens.Space.Xs),
        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs), verticalArrangement = Arrangement.Center,
    ) {
        @Composable
        fun chip(text: String, on: Boolean, pick: () -> Unit) {
            Box(
                Modifier.heightIn(min = AronLook.Touch).widthIn(min = AronLook.Touch).clip(RoundedCornerShape(8.dp))
                    .then(if (on) Modifier.background(vendorBarBrush()) else Modifier)
                    .clickable(role = Role.Button, onClick = pick).padding(horizontal = AronTokens.Space.M),
                contentAlignment = Alignment.Center,
            ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) look.onBar else Color.White) }
        }
        chip(stringResource(R.string.memo_letter_all), selected == null) { onPick(null) }
        letters.forEach { c -> chip(c.toString(), selected == c) { onPick(c) } }
    }
}

/** The lines table: gradient header (SKU, quantity, price), rows, a light-grey category block and the bold white total row. */
@Composable
internal fun VendorTable(
    lines: List<Triple<String, String, String>>,
    subtotals: List<Triple<String, String, String>>,
    totalName: String, totalValue: String,
) {
    val look = LocalAronLook.current
    val stacked = tablesStacked()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)) {
        Row(
            Modifier.fillMaxWidth().background(vendorBarBrush()).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.M),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            Text(stringResource(R.string.memo_col_sku), style = MaterialTheme.typography.labelLarge, color = look.onBar, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.memo_col_qty), style = MaterialTheme.typography.labelLarge, color = look.onBar, textAlign = TextAlign.End, modifier = if (stacked) Modifier else Modifier.widthIn(min = 72.dp))
            Text(stringResource(R.string.memo_col_price_plain), style = MaterialTheme.typography.labelLarge, color = look.onBar, textAlign = TextAlign.End, modifier = if (stacked) Modifier else Modifier.widthIn(min = 72.dp))
        }
        Column(Modifier.padding(vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            lines.forEach { (n, q, v) -> TableRow(n, q, v) }
        }
        if (subtotals.isNotEmpty()) Column(Modifier.fillMaxWidth().background(look.rowAlt).padding(vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            subtotals.forEach { (n, q, v) -> SubtotalRow(n, q, v) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically) {
            Text(totalName, style = MaterialTheme.typography.titleSmall, color = look.ink, modifier = Modifier.weight(1f))
            MoneyText(totalValue, style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun SubtotalRow(name: String, qty: String, value: String) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = look.ink, modifier = Modifier.weight(1f))
        MoneyText(qty, Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.bodyMedium, color = look.ink, textAlign = TextAlign.End)
        MoneyText(value, Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.bodyMedium, color = look.ink, textAlign = TextAlign.End)
    }
}

/** "মোট QC" and "সর্বমোট": two plain purple lines under the table, no card (manual 36, 38). */
@Composable
internal fun VendorTotals(qcLine: String, grandTotal: String, discountLine: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        if (discountLine != null) VendorTotalLine(stringResource(R.string.memo_total_discount), discountLine)
        VendorTotalLine(stringResource(R.string.memo_total_qc), qcLine)
        VendorTotalLine(stringResource(R.string.memo_grand_total), grandTotal)
    }
}

@Composable
private fun VendorTotalLine(label: String, value: String) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = look.purple, modifier = Modifier.weight(1f))
        MoneyText(value, style = MaterialTheme.typography.titleSmall, color = look.purple, textAlign = TextAlign.End)
    }
}

/** The credit box (manual 38): dashed red edge, the card icon over the red sentence, a thin red divider, the green "পরিশোধিত করুন". */
@Composable
internal fun VendorDueBox(canMarkPaid: Boolean, onMarkPaid: () -> Unit) {
    val look = LocalAronLook.current
    val red = look.red
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).drawBehind {
            drawRoundRect(red, cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
        }.padding(AronTokens.Space.L),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            Icon(AronIcons.Wallet, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(AronTokens.Size.Icon))
            Text(stringResource(R.string.memo_credit_title), style = MaterialTheme.typography.titleSmall, color = red, textAlign = TextAlign.Center)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(red))
        if (canMarkPaid) AronSolidButton(stringResource(R.string.memo_mark_paid), onMarkPaid, Modifier.weight(1f), tone = SolidTone.Green)
        else Box(Modifier.weight(1f))
    }
}

/** The pencil "এডিট" button, amber (manual 36, 38): the kit's solid button has no glyph slot, so the icon sits beside the word. */
@Composable
internal fun VendorEditButton(enabled: Boolean, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val fill = if (enabled) look.amber else look.primaryDisabled
    Box(
        modifier.heightIn(min = AronLook.ButtonHeight).clip(RoundedCornerShape(AronLook.ButtonRadius)).background(fill).clickable(enabled = enabled, role = Role.Button, onClick = onEdit),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Icon(AronIcons.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(AronTokens.Size.IconSmall))
            Text(stringResource(R.string.memo_edit), style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}
