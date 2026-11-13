package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.aron.PackCategory
import com.aktcl.aron.core.ui.aron.PackshotImage
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.SaleHistory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The total band of "সেল ডাটা": a lilac-purple with white text at 7:1 or better (`SaleDataContrastTest`). */
internal val LilacBand = Color(0xFF5B3F9E)

internal fun packCategory(code: String?): PackCategory = when (code) {
    "bidi" -> PackCategory.Bidi
    "lighter" -> PackCategory.Lighter
    "match" -> PackCategory.Match
    else -> PackCategory.Cigarette
}

object SaleDataTags {
    const val DATE_FIELD = "sale_data_date"
    const val ISO = "sale_data_iso"
    const val TABLE = "sale_data_table"
    const val TOTAL = "sale_data_total"
    const val EMPTY = "sale_data_empty"
}

/** True when [iso] (`yyyy-MM-dd`) may be chosen on a day whose business date is [today]: today and the past, never the future. */
fun saleDataSelectable(iso: String, today: String, oldest: String? = null): Boolean = iso <= today && (oldest == null || iso >= oldest)

/**
 * "সেল ডাটা" (SR manual s8 / 23, D-SR-MAN-S4-04): the outlet's sales on one chosen date. A white date field with a purple outline and a
 * calendar icon opens the Material date picker with every date after [today] greyed and unselectable; the ISO date sits under the field;
 * then the table SKU / quantity / value and the total row. The quantity of each line is in its entry unit (packets, boxes, dozens: rule 5),
 * so the total row carries the value only (adding packets to dozens would mean nothing). A date inside the local window with no sale says so; dates older than the window ([oldest]) are disabled.
 * Reads the same local window and server fallback as [SaleHistoryScreen]; the AMO keeps that screen unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleDataScreen(
    h: SaleHistory, today: String, outletLabel: String?, offlineBanner: Boolean, modifier: Modifier = Modifier,
    skuName: (Long) -> String = { it.toString() }, skuUnit: (Long) -> String = { "" }, skuCategory: (Long) -> String? = { null },
    /** The oldest date the phone holds sales for (the local window); older dates are disabled in the picker instead of reading "no sale". Null = no lower limit. */
    oldest: String? = null,
) {
    val look = LocalAronLook.current
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var open by rememberSaveable { mutableStateOf(false) }
    val iso = picked ?: h.days.firstOrNull()?.businessDate ?: today
    val day = h.days.firstOrNull { it.businessDate == iso }
    ScrollColumn(modifier.background(look.page), contentPadding = androidx.compose.foundation.layout.PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        if (offlineBanner || h.fromServer) com.aktcl.aron.core.ui.aron.AronInfoCard(stringResource(R.string.history_offline))
        outletLabel?.let { Text(localizedDigits(it), color = look.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        val shape = RoundedCornerShape(AronLook.CardRadius)
        Row(
            Modifier.fillMaxWidth().heightIn(min = AronLook.Touch).background(look.card, shape).border(1.5.dp, look.purple, shape)
                .clickable(role = Role.Button) { open = true }.padding(horizontal = AronTokens.Space.L).testTag(SaleDataTags.DATE_FIELD),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Icon(AronIcons.Calendar, contentDescription = null, tint = look.purple)
            Text(longDate(iso), color = look.ink, fontSize = 16.sp)
        }
        Text(localizedDigits(iso), color = look.purple, fontSize = 15.sp, modifier = Modifier.testTag(SaleDataTags.ISO))
        if (day == null || day.skuRows.isEmpty()) {
            Text(
                stringResource(R.string.sale_data_none), color = look.inkMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.Xl).testTag(SaleDataTags.EMPTY),
            )
        } else {
            // manual 23: a solid deep-purple header, rows with the pack picture alternating white and light grey, the total on a lilac-purple band
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card).testTag(SaleDataTags.TABLE)) {
                Row(
                    Modifier.fillMaxWidth().background(look.purple).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.M),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
                ) {
                    Text(stringResource(R.string.memo_col_sku), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.memo_col_qty), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 72.dp))
                    Text(stringResource(R.string.summary_col_value), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 72.dp))
                }
                day.skuRows.forEachIndexed { i, r ->
                    Row(
                        Modifier.fillMaxWidth().background(if (i % 2 == 0) look.card else look.rowAlt).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
                    ) {
                        PackshotImage(r.skuId.toString(), Modifier.size(30.dp, 36.dp), null, packCategory(skuCategory(r.skuId)))
                        Text(skuName(r.skuId), color = look.purple, fontSize = 16.sp, maxLines = 2, modifier = Modifier.weight(1f))
                        MoneyText(lineQtyText(r.qtyEntered, r.unitEntered, skuCategory(r.skuId), r.qtyBase, skuUnit(r.skuId)), Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End)
                        MoneyText(amount(r.valueMtk), Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End)
                    }
                }
                // the lilac band: the kit's purple at a lighter step with white text (>= 7:1, tested), never the vendor's pale #9B84C4 (about 3.4:1)
                Row(Modifier.fillMaxWidth().background(LilacBand).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.M).testTag(SaleDataTags.TOTAL), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.history_footer), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(amount(day.skuTotalMtk), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                }
            }
        }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(iso).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    saleDataSelectable(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().toString(), today, oldest)
                override fun isSelectableYear(year: Int) = year <= LocalDate.parse(today).year
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        if (saleDataSelectable(d, today, oldest)) picked = d
                    }
                    open = false
                }) { Text(stringResource(R.string.sale_data_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.sale_data_cancel)) } },
        ) { DatePicker(state) }
    }
}

/** `November 10, 2026` as the manual's date field shows it (English words, whatever the language: the picker is the system's). */
internal fun longDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)) }.getOrDefault(iso)
