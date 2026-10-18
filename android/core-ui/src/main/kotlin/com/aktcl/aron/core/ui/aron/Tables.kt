package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.background
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.Glyph.caretDown

/** One row of a [TotalsPanel] or a [SummaryTable] subtotal: a [label] and its figures, already localised. */
@Immutable
data class TotalsLine(val label: String, val values: List<String>)

/**
 * The solid purple totals panel docked above the Stock and Sale buttons (PARITY-SPEC s2.7, sr/stock.webp): the [header] row in 12 sp
 * (`ক্যাটাগরি / মোট ইস্যু / স্টক`), then one 22 sp white row per category in [lines]; figures right-aligned. Put it in
 * [AronScaffold]'s `bottomPanel`.
 */
@Composable
fun TotalsPanel(header: TotalsLine, lines: List<TotalsLine>, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth().background(look.panel).padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        TotalsRow(header, 13.sp, look.onPanelMuted)
        lines.forEach { TotalsRow(it, 22.sp, look.onPanel) }
    }
}

@Composable
private fun TotalsRow(line: TotalsLine, size: TextUnit, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(line.label, style = lookStyle(size, color = color), modifier = Modifier.weight(1.3f))
        line.values.forEach { Text(it, style = lookStyle(size, color = color, align = TextAlign.End), modifier = Modifier.weight(1f)) }
    }
}

/**
 * The white pill "select a retailer" field with its caret (PARITY-SPEC s2.9, sr/sale-select.png). Shows [value] or the grey
 * [placeholder]; [onClick] opens the caller's list (use this form for long retailer lists with search).
 */
@Composable
fun RetailerPickerField(value: String?, placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .background(look.card)
            .clickable(role = Role.DropdownList, onClick = onClick)
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value ?: placeholder, style = lookStyle(17.sp, color = if (value == null) look.inkMuted else look.ink), modifier = Modifier.weight(1f))
        GlyphCanvas(Modifier.size(24.dp)) { caretDown(look.inkMuted) }
    }
}

/**
 * [RetailerPickerField] with its own drop-down of [options] (each shown by [optionLabel]); [onSelect] gets the chosen one. For a few
 * dozen retailers; a route of hundreds should use the field with a searchable sheet.
 */
@Composable
fun <T> RetailerPicker(
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    placeholder: String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        RetailerPickerField(selected?.let(optionLabel), placeholder, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option), style = lookStyle(17.sp)) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

/**
 * The A-Z retailer filter (PARITY-SPEC s2.9, sr/sale-select.png): a dark `#505050` 12 dp panel, white 20 sp [letters] (pass the
 * localised "All" first), rows centred and wrapped, the [selected] one on a vertical gradient rounded square. Each letter is a 48 dp
 * target; [letterDescription] gives its TalkBack text (default: the letter itself).
 */
@Composable
fun LetterFilterBar(
    letters: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    letterDescription: ((String) -> String)? = null,
) {
    val look = LocalAronLook.current
    CenteredWrap(modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.panelDark).padding(8.dp)) {
        letters.forEach { letter ->
            val on = letter == selected
            Box(
                Modifier
                    .padding(2.dp)
                    .sizeIn(minWidth = AronLook.Touch, minHeight = AronLook.Touch)
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (on) Modifier.background(look.verticalBrush()) else Modifier)
                    .clickable(role = Role.Tab, onClick = { onSelect(letter) })
                    .semantics {
                        this.selected = on
                        if (letterDescription != null) contentDescription = letterDescription(letter)
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(letter, style = lookStyle(20.sp, color = Color.White, align = TextAlign.Center)) }
        }
    }
}

/** Lays children out left to right, wrapping, each row centred (the letter rows of sr/sale-select.png). */
@Composable
private fun CenteredWrap(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.sumOf { it.width }
        val rows = mutableListOf<List<Placeable>>()
        var current = mutableListOf<Placeable>()
        var width = 0
        placeables.forEach { p ->
            if (current.isNotEmpty() && width + p.width > maxWidth) {
                rows += current
                current = mutableListOf()
                width = 0
            }
            current += p
            width += p.width
        }
        if (current.isNotEmpty()) rows += current
        val height = rows.sumOf { row -> row.maxOf { it.height } }
        layout(maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            rows.forEach { row ->
                var x = (maxWidth - row.sumOf { it.width }) / 2
                row.forEach { p -> p.placeRelative(x, y); x += p.width }
                y += row.maxOf { it.height }
            }
        }
    }
}

/** One SKU of a [SummaryTable]: its [title] (purple 20 sp) over the figures of [values], one per column. */
@Immutable
data class SummaryRow(val title: String, val values: List<String>)

/** A line of the totals block under a [SummaryTable] (`সর্বমোট`, `ডিসকাউন্ট এবং অন্যান্য (-)`): purple bold 20 sp, value at the end. */
@Immutable
data class SummaryTotal(val label: String, val value: String)

private fun cellAlign(index: Int, count: Int): TextAlign = when (index) {
    0 -> TextAlign.Start
    count - 1 -> TextAlign.End
    else -> TextAlign.Center
}

/**
 * A cell text that never breaks inside a word: it shrinks from the style's size (down to [minSize]) until its longest word fits the
 * cell, then wraps only between words (sr/summary.png keeps "৪,৬৮৭.৫০৳" and "ডিসকাউন্ট" whole on 360 dp).
 */
@Composable
internal fun FitText(text: String, style: TextStyle, minSize: TextUnit, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxPx = constraints.maxWidth
        val size = remember(text, style, maxPx, minSize) {
            val words = text.split(' ').filter { it.isNotEmpty() }
            var sp = style.fontSize.value
            while (sp > minSize.value && words.any { measurer.measure(it, style.copy(fontSize = sp.sp), softWrap = false, maxLines = 1).size.width > maxPx }) sp -= 0.5f
            sp
        }
        Text(text, style = style.copy(fontSize = size.sp), maxLines = maxLines, modifier = Modifier.fillMaxWidth())
    }
}

/** Column weights of the six-column day summary at 360 dp (memo, quantity, value, discount, net value, return). */
private val SummaryWeights6 = listOf(0.7f, 0.9f, 1.4f, 1.2f, 1.4f, 0.8f)

/**
 * The day summary table (PARITY-SPEC s2.10, sr/summary.png): a dark `#4D4D4D` header row of [headers] (white bold 11 sp, at most two
 * lines, rounded top), one block per [rows] item (the SKU name in purple 20 sp over its figures, 14 sp) on alternating white and
 * `#F0F0F0`, then the category [subtotals] in purple (the label takes the first column, its values the rest), then the [totals] block.
 * [weights] set the column widths (default: the six-column summary weights, else equal); no cell breaks inside a word or a number, it
 * shrinks instead. Not lazy (one day's SKUs); put it in a scrolling column with `AronPrimaryButton(reverse = true, glyph = Print)` under it.
 */
@Composable
fun SummaryTable(
    headers: List<String>,
    rows: List<SummaryRow>,
    modifier: Modifier = Modifier,
    subtotals: List<TotalsLine> = emptyList(),
    totals: List<SummaryTotal> = emptyList(),
    weights: List<Float>? = null,
) {
    val look = LocalAronLook.current
    val n = headers.size
    val w = weights ?: if (n == SummaryWeights6.size) SummaryWeights6 else List(n) { 1f }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)) {
        Row(Modifier.fillMaxWidth().background(look.tableHeader).padding(horizontal = 10.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            headers.forEachIndexed { i, h ->
                FitText(h, lookStyle(11.sp, bold = true, color = Color.White, align = cellAlign(i, n)), 7.sp, Modifier.weight(w[i]).padding(horizontal = 1.dp), maxLines = 2)
            }
        }
        rows.forEachIndexed { r, row ->
            Column(Modifier.fillMaxWidth().background(if (r % 2 == 0) look.card else look.rowAlt).padding(horizontal = 10.dp, vertical = 10.dp)) {
                Text(row.title, style = lookStyle(20.sp, color = look.purple))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    row.values.forEachIndexed { i, v -> FitText(v, lookStyle(14.sp, color = look.ink, align = cellAlign(i, n)), 7.sp, Modifier.weight(w[i]).padding(horizontal = 1.dp)) }
                }
            }
        }
        if (subtotals.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(look.rowAlt).padding(horizontal = 10.dp, vertical = 8.dp)) {
                subtotals.forEach { line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FitText(line.label, lookStyle(16.sp, color = look.purple), 9.sp, Modifier.weight(w[0]).padding(horizontal = 1.dp))
                        line.values.forEachIndexed { i, v ->
                            FitText(v, lookStyle(14.sp, color = look.purple, align = cellAlign(i + 1, n)), 7.sp, Modifier.weight(w.getOrElse(i + 1) { 1f }).padding(horizontal = 1.dp))
                        }
                    }
                }
            }
        }
        if (totals.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(look.rowAlt).padding(horizontal = 12.dp, vertical = 8.dp)) {
                totals.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.label, style = lookStyle(20.sp, bold = true, color = look.purple), modifier = Modifier.weight(1f))
                        Text(t.value, style = lookStyle(20.sp, bold = true, color = look.purple, align = TextAlign.End), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

/**
 * A plain data table (sr/sales-submit.png): a solid purple header with rounded corners ([headers], white bold 22 sp), then white rows
 * with a thin rule, 18 sp. The first column is start-aligned, the others centred; [weights] (one per column, default first 2, rest 1)
 * set the column widths. Not lazy: for short tables such as the five submit rows.
 */
@Composable
fun AronDataTable(headers: List<String>, rows: List<List<String>>, modifier: Modifier = Modifier, weights: List<Float>? = null) {
    val look = LocalAronLook.current
    val w = weights ?: List(headers.size) { if (it == 0) 2f else 1f }
    val align = { i: Int -> if (i == 0) TextAlign.Start else TextAlign.Center }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.panel).padding(horizontal = 12.dp, vertical = 16.dp)) {
            headers.forEachIndexed { i, h -> Text(h, style = lookStyle(22.sp, bold = true, color = look.onPanel, align = align(i)), modifier = Modifier.weight(w[i])) }
        }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().background(look.card).drawBehind { drawRect(look.rowRule, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                row.forEachIndexed { i, cell -> Text(cell, style = lookStyle(18.sp, color = look.ink, align = align(i)), modifier = Modifier.weight(w[i])) }
            }
        }
    }
}
