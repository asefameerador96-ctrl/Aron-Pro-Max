package com.aktcl.aron.feature.tso.leave

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronBarAction
import com.aktcl.aron.core.ui.AronBottomBar
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronSegmented
import com.aktcl.aron.core.ui.AronStepper
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.R
import java.time.LocalDate
import kotlinx.coroutines.launch

object LeaveApplyTags {
    const val DATE = "tso_leave_date"
    const val REASON = "tso_leave_reason"
    const val SEND = "tso_leave_send"
    const val ERRORS = "tso_leave_errors"
    const val QUEUED = "tso_leave_queued"
    const val FAILED = "tso_leave_failed"
    const val TO_DATE = "tso_leave_to_date"
    fun type(code: String) = "tso_leave_type_$code"
}

@Composable
private fun typeLabel(code: String) = when (code) {
    "casual" -> stringResource(R.string.tso_leave_type_casual)
    "sick" -> stringResource(R.string.tso_leave_type_sick)
    else -> stringResource(R.string.tso_leave_type_earn)
}

@Composable
private fun errorText(e: LeaveError) = when (e) {
    LeaveError.TYPE -> stringResource(R.string.tso_leave_err_type)
    LeaveError.DATE_FORMAT -> stringResource(R.string.tso_leave_err_date_format)
    LeaveError.DATE_PAST -> stringResource(R.string.tso_leave_err_date_past)
    LeaveError.DATE_FAR -> stringResource(R.string.tso_leave_err_date_far)
    LeaveError.DAYS -> stringResource(R.string.tso_leave_err_days)
    LeaveError.REASON_EMPTY -> stringResource(R.string.tso_leave_err_reason_empty)
    LeaveError.REASON_LONG -> stringResource(R.string.tso_leave_err_reason_long)
}

/** F-TSO-009: type, start date, days and reason; Send queues locally at once and says "not sent yet" until the server has it. */
@Composable
fun LeaveApplyScreen(state: LeaveApplyState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val form by state.form.collectAsStateWithLifecycle()
    val result by state.result.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val queued = result is LeaveApplyResult.Queued
    AronScreen(
        modifier,
        topBar = { AronTopBar(stringResource(R.string.tso_leave_apply_title), onBack = onBack) },
        bottomBar = {
            AronBottomBar(
                primary = if (queued) AronBarAction(stringResource(R.string.tso_leave_done), onBack)
                else AronBarAction(stringResource(R.string.tso_leave_send), { scope.launch { state.submit() } }),
                modifier = Modifier.testTag(LeaveApplyTags.SEND),
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            if (queued) {
                AronBanner(stringResource(R.string.tso_leave_queued), Modifier.testTag(LeaveApplyTags.QUEUED), kind = BannerKind.Success)
                AronTextButton(stringResource(R.string.tso_leave_another), { state.startNew() })
            }
            if (result is LeaveApplyResult.Failed) AronBanner(stringResource(R.string.tso_leave_failed), Modifier.testTag(LeaveApplyTags.FAILED), kind = BannerKind.Error)
            AronSectionLabel(stringResource(R.string.tso_leave_type))
            AronSegmented(LEAVE_TYPES.map { typeLabel(it) }, LEAVE_TYPES.indexOf(form.type).coerceAtLeast(0), { state.edit { f -> f.copy(type = LEAVE_TYPES[it]) } })
            AronSectionLabel(stringResource(R.string.tso_leave_from))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                OutlinedTextField(
                    form.fromDate, { v -> state.edit { f -> f.copy(fromDate = v.take(10)) } }, Modifier.weight(1f).testTag(LeaveApplyTags.DATE),
                    singleLine = true, enabled = !queued, label = { Text(stringResource(R.string.tso_leave_date_hint)) },
                )
            }
            AronSectionLabel(stringResource(R.string.tso_leave_days_label))
            AronStepper(form.days, { v -> state.edit { f -> f.copy(days = v) } }, stringResource(R.string.tso_leave_days_minus), stringResource(R.string.tso_leave_days_plus), min = 1, max = MAX_DAYS)
            runCatching { leaveToDate(form.fromDate, form.days) }.getOrNull()?.let {
                Text(localizedDigits(stringResource(R.string.tso_leave_until, it)), Modifier.testTag(LeaveApplyTags.TO_DATE), style = MaterialTheme.typography.bodyMedium)
            }
            AronSectionLabel(stringResource(R.string.tso_leave_reason))
            OutlinedTextField(
                form.reason, { v -> state.edit { f -> f.copy(reason = v.take(MAX_REASON + 1)) } }, Modifier.fillMaxWidth().testTag(LeaveApplyTags.REASON),
                minLines = 3, enabled = !queued,
            )
            (result as? LeaveApplyResult.Invalid)?.errors?.forEach { e ->
                AronBanner(errorText(e), Modifier.testTag(LeaveApplyTags.ERRORS), kind = BannerKind.Error)
            }
        }
    }
}
