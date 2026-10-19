package com.aktcl.aron.feature.tso

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The date input of the TSO screens (the manual: a native-style date picker, never typed digits). The value is an ISO date
 * (`2026-04-26`); the field shows it in the long form of the manual ("April 26, 2026", Bangla month names under bn) and a tap opens
 * the Material date dialog limited to [min]..[max]. Nothing is parsed from typed text, so a wrong format cannot occur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TsoDateField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, min: String? = null, max: String? = null, enabled: Boolean = true) {
    val c = LocalAronLook.current
    val language = LocalAppLanguage.current
    var open by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth().heightIn(min = AronLook.Touch).clip(RoundedCornerShape(AronLook.ButtonRadius)).background(c.card).border(1.dp, c.underline, RoundedCornerShape(AronLook.ButtonRadius))
            .clickable(enabled = enabled, role = Role.Button) { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(TsoDates.long(value, language), style = MaterialTheme.typography.bodyLarge, color = c.ink) }
    if (open) {
        val minMs = min?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val maxMs = max?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val initial = runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = (minMs == null || utcTimeMillis >= minMs) && (maxMs == null || utcTimeMillis <= maxMs)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton({ state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false }, colors = ButtonDefaults.textButtonColors(contentColor = c.purple)) { Text(stringResource(R.string.tso_date_ok)) } },
            dismissButton = { TextButton({ open = false }, colors = ButtonDefaults.textButtonColors(contentColor = c.purple)) { Text(stringResource(R.string.tso_fs_cancel)) } },
        ) { DatePicker(state) }
    }
}
