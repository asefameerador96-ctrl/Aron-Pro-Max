package com.aktcl.aron.feature.tso.leave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoButtonStyle
import com.aktcl.aron.feature.tso.TsoDates
import com.aktcl.aron.feature.tso.TsoDropdownField
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoGlowField
import com.aktcl.aron.feature.tso.TsoGradientButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

object LeaveApplyTags {
    const val TYPE = "tso_leave_type"
    const val DATE = "tso_leave_date"
    const val DAYS = "tso_leave_days"
    const val REASON = "tso_leave_reason"
    const val SEND = "tso_leave_send"
    const val QUEUED = "tso_leave_queued"
    const val FAILED = "tso_leave_failed"
    const val TO_DATE = "tso_leave_to_date"
    fun type(code: String) = "tso_leave_type_$code"
    fun error(e: LeaveError) = "tso_leave_error_${e.name.lowercase()}"
}

/** The type's label: the bundle's text (Bangla when given), else the app's own words for the manual's three codes. */
@Composable
private fun typeLabel(t: LeaveType): String {
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    if (t !in LeaveTypes.DEFAULT) return t.label(bangla)
    return when (t.code) {
        "casual" -> stringResource(R.string.tso_leave_type_casual)
        "sick" -> stringResource(R.string.tso_leave_type_sick)
        else -> stringResource(R.string.tso_leave_type_earn)
    }
}

@Composable
private fun errorText(e: LeaveError) = when (e) {
    LeaveError.TYPE -> stringResource(R.string.tso_leave_err_type)
    LeaveError.DATE_MISSING -> stringResource(R.string.tso_leave_err_date_missing)
    LeaveError.DATE_FORMAT -> stringResource(R.string.tso_leave_err_date_format)
    LeaveError.DATE_PAST -> stringResource(R.string.tso_leave_err_date_past)
    LeaveError.DATE_FAR -> stringResource(R.string.tso_leave_err_date_far)
    LeaveError.DAYS -> stringResource(R.string.tso_leave_err_days)
    LeaveError.REASON_EMPTY -> stringResource(R.string.tso_leave_err_reason_empty)
    LeaveError.REASON_LONG -> stringResource(R.string.tso_leave_err_reason_long)
}

/**
 * Leave Apply (screenshots 20 to 22, D-TSO-MAN-33): Leave Type (a dropdown with nothing chosen, the types from the bundle
 * code list), Date (empty until picked from the calendar), Number of days (typed; "0" to start, as the screenshot), Reason,
 * and the lime Apply under the form. Apply queues the application on the phone at once (no network); the flow then
 * returns to the list with a toast ([LeaveFlow]). The last day (`from + days - 1`, calendar days) shows under the days.
 */
@Composable
fun LeaveApplyScreen(state: LeaveApplyState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val form by state.form.collectAsStateWithLifecycle()
    val result by state.result.collectAsStateWithLifecycle()
    val options by state.options.collectAsStateWithLifecycle()
    val limits by state.limits.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load() }
    val queued = result is LeaveApplyResult.Queued
    val errors = (result as? LeaveApplyResult.Invalid)?.errors.orEmpty()
    TsoFrame(stringResource(R.string.tso_leave_apply_title), backOnly(onBack), homeButton = false, modifier = modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            if (result is LeaveApplyResult.Failed) TsoBanner(stringResource(R.string.tso_leave_failed), Modifier.testTag(LeaveApplyTags.FAILED), kind = TsoBannerKind.Error)
            if (queued) TsoBanner(stringResource(R.string.tso_leave_queued), Modifier.testTag(LeaveApplyTags.QUEUED), kind = TsoBannerKind.Success)
            val labels = options.associate { it.code to typeLabel(it) }
            TsoDropdownField(
                options, options.firstOrNull { it.code == form.type }, { t -> state.edit { f -> f.copy(type = t.code) } }, { labels[it.code] ?: it.labelEn },
                Modifier.testTag(LeaveApplyTags.TYPE), label = stringResource(R.string.tso_leave_type_label),
                placeholder = stringResource(R.string.tso_leave_type), enabled = !queued,
            )
            FieldError(errors, LeaveError.TYPE)
            val today = state.todayIso()
            LeaveDateField(
                form.fromDate, { v -> state.edit { f -> f.copy(fromDate = v) } }, stringResource(R.string.tso_leave_date_label), stringResource(R.string.tso_leave_from),
                Modifier.testTag(LeaveApplyTags.DATE), enabled = !queued, today = today,
                min = runCatching { LocalDate.parse(today).minusDays(limits.pastDays).toString() }.getOrNull(),
                max = runCatching { LocalDate.parse(today).plusDays(limits.aheadDays).toString() }.getOrNull(),
            )
            FieldError(errors, LeaveError.DATE_MISSING); FieldError(errors, LeaveError.DATE_FORMAT); FieldError(errors, LeaveError.DATE_PAST); FieldError(errors, LeaveError.DATE_FAR)
            // the typed text is kept as typed (an empty field stays empty); the form holds its number
            val lastDay = if (form.days in 1..MAX_DAYS && form.fromDate.isNotBlank()) runCatching { leaveToDate(form.fromDate, form.days) }.getOrNull() else null
            var daysText by rememberSaveable { mutableStateOf(form.days.toString()) }
            TsoGlowField(
                daysText,
                { v -> val n = daysFromText(v); daysText = if (v.isEmpty()) "" else n.toString(); state.edit { f -> f.copy(days = n) } },
                Modifier.testTag(LeaveApplyTags.DAYS), label = stringResource(R.string.tso_leave_days_label), placeholder = stringResource(R.string.tso_leave_days_hint),
                enabled = !queued, isError = LeaveError.DAYS in errors, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = if (LeaveError.DAYS in errors) errorText(LeaveError.DAYS) else lastDay?.let { stringResource(R.string.tso_leave_until, TsoDates.long(it, LocalAppLanguage.current)) },
            )
            TsoGlowField(
                form.reason, { v -> state.edit { f -> f.copy(reason = v.take(MAX_REASON * 2)) } }, Modifier.testTag(LeaveApplyTags.REASON),
                label = stringResource(R.string.tso_leave_reason), placeholder = stringResource(R.string.tso_leave_reason),
                enabled = !queued, singleLine = false, minLines = 4,
                isError = LeaveError.REASON_EMPTY in errors || LeaveError.REASON_LONG in errors,
                supportingText = errors.firstOrNull { it == LeaveError.REASON_EMPTY || it == LeaveError.REASON_LONG }?.let { errorText(it) },
            )
            TsoGradientButton(
                stringResource(R.string.tso_leave_send), { scope.launch { state.submit() } }, Modifier.padding(top = AronTokens.Space.M).testTag(LeaveApplyTags.SEND),
                style = TsoButtonStyle.Lime, enabled = !queued,
            )
        }
    }
}

@Composable
private fun FieldError(errors: List<LeaveError>, e: LeaveError) {
    if (e in errors) Text(errorText(e), Modifier.testTag(LeaveApplyTags.error(e)), style = MaterialTheme.typography.bodySmall, color = LocalAronLook.current.red)
}

/**
 * The Date field of screenshot 20: the label above, "Select Date" until a day is picked, the calendar icon at the end; a tap
 * opens the date dialog limited to [min]..[max] (the config window), on the month of the picked day or of [today]. The picked
 * day shows in the long form ("October 26, 2026").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaveDateField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, modifier: Modifier = Modifier, enabled: Boolean = true, min: String? = null, max: String? = null, today: String? = null) {
    val look = LocalAronLook.current
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = if (value.isBlank()) null else TsoDates.long(value, LocalAppLanguage.current)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = look.ink)
        Row(
            modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(look.card).border(1.dp, look.underline, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { open = true }
                .semantics { contentDescription = listOfNotNull(label, shown ?: placeholder).joinToString(", ") }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown ?: placeholder, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (shown != null) look.ink else look.inkMuted)
            Icon(AronIcons.Calendar, contentDescription = null, tint = look.inkMuted)
        }
    }
    if (open) {
        fun ms(d: String?) = d?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val minMs = ms(min); val maxMs = ms(max)
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = ms(value),
            // nothing picked yet: open on the business date's month (trusted time), not the device clock's
            initialDisplayedMonthMillis = ms(value) ?: ms(today),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = (minMs == null || utcTimeMillis >= minMs) && (maxMs == null || utcTimeMillis <= maxMs)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({ picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false }, colors = ButtonDefaults.textButtonColors(contentColor = look.purple)) {
                    Text(stringResource(R.string.tso_date_ok))
                }
            },
            dismissButton = { TextButton({ open = false }, colors = ButtonDefaults.textButtonColors(contentColor = look.purple)) { Text(stringResource(R.string.tso_fs_cancel)) } },
        ) { Box { DatePicker(picker) } }
    }
}
