package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.RowPosition

@Composable
private fun codeLabel(code: String): String {
    val res = when (code) {
        "GEO_MOCK" -> R.string.amo_exc_code_geo_mock
        "GEO_TELEPORT" -> R.string.amo_exc_code_geo_teleport
        "GEO_ZERO_JITTER" -> R.string.amo_exc_code_geo_zero_jitter
        "GEO_PERFECT_ACCURACY" -> R.string.amo_exc_code_geo_perfect_accuracy
        "GEO_SAME_POINT" -> R.string.amo_exc_code_geo_same_point
        "GEO_ROUTE_SINGLE_POINT" -> R.string.amo_exc_code_geo_route_single_point
        "GEO_STALE_FIX" -> R.string.amo_exc_code_geo_stale_fix
        "GEO_GNSS_INCONSISTENT" -> R.string.amo_exc_code_geo_gnss_inconsistent
        "GEO_GNSS_TIME_SKEW" -> R.string.amo_exc_code_geo_gnss_time_skew
        "GEO_DEVICE_SERVER_MISMATCH" -> R.string.amo_exc_code_geo_device_server_mismatch
        "GEO_SHORT_VISIT_GAPS" -> R.string.amo_exc_code_geo_short_visit_gaps
        "GEO_OUT_OF_BOUNDS" -> R.string.amo_exc_code_geo_out_of_bounds
        "DEVICE_NOT_OWNER" -> R.string.amo_exc_code_device_not_owner
        "DEVICE_INTEGRITY_FAIL" -> R.string.amo_exc_code_device_integrity_fail
        "DEVICE_DEBUG_ENABLED" -> R.string.amo_exc_code_device_debug_enabled
        "DEVICE_MOCK_APP_PRESENT" -> R.string.amo_exc_code_device_mock_app_present
        "DEVICE_POLICY_DRIFT" -> R.string.amo_exc_code_device_policy_drift
        "CLOCK_SKEW" -> R.string.amo_exc_code_clock_skew
        "CONFIG_STAMP_REGRESS" -> R.string.amo_exc_code_config_stamp_regress
        "MEMO_DUPLICATE_SUSPECT" -> R.string.amo_exc_code_memo_duplicate_suspect
        else -> return code // a code the app does not know is shown by its code
    }
    return stringResource(res)
}

@Composable
private fun groupLabel(g: ExceptionGroup) = stringResource(
    when (g) { ExceptionGroup.GEO -> R.string.amo_exc_group_geo; ExceptionGroup.DEVICE -> R.string.amo_exc_group_device; ExceptionGroup.CLOCK -> R.string.amo_exc_group_clock; ExceptionGroup.OTHER -> R.string.amo_exc_group_other },
)

/** F-AMO-038: today's open signals of the zone; [names] maps user ids to SR names. */
@Composable
fun ExceptionsListContent(rows: List<RiskSignalRow>, names: Map<Long, String>, onOpen: (RiskSignalRow) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        AmoEmptyState(stringResource(R.string.amo_exc_empty), modifier.fillMaxSize(), hint = stringResource(R.string.amo_exc_empty_hint))
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        rows.forEachIndexed { i, r ->
            AmoListRow(
                codeLabel(r.code), subtitle = listOfNotNull(groupLabel(Exceptions.group(r.code)), r.userId?.let { names[it] }).joinToString(stringResource(R.string.amo_verify_sep)),
                trailing = stringResource(R.string.amo_exc_severity, r.severity.coerceIn(1, 4)), onClick = { onOpen(r) },
                position = if (rows.size == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == rows.lastIndex) RowPosition.Last else RowPosition.Middle,
                divider = i < rows.lastIndex, flat = true,
            )
        }
    }
}

/** One signal with the numbers that fired it; Review, Dismiss and Confirm each queue one idempotent event (works offline). */
@Composable
fun ExceptionDetailContent(
    row: RiskSignalRow, userName: String?, note: String, busy: Boolean, failed: Boolean, onNote: (String) -> Unit, onAction: (String) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            AmoListRow(codeLabel(row.code), subtitle = listOfNotNull(groupLabel(Exceptions.group(row.code)), userName).joinToString(stringResource(R.string.amo_verify_sep)), flat = true)
            if (failed) AmoBanner(stringResource(R.string.amo_exc_failed), kind = BannerKind.Error)
            AmoSection(stringResource(R.string.amo_exc_evidence))
            row.evidence.forEach { (k, v) -> AmoListRow(localizedDigits(v), subtitle = k, flat = true) }
            AmoField(note, onNote, stringResource(R.string.amo_verify_note), singleLine = false)
        }
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            AmoSecondary(stringResource(R.string.amo_exc_review), { onAction("reviewed") }, Modifier.fillMaxWidth(), enabled = !busy)
            AmoSecondary(stringResource(R.string.amo_exc_dismiss), { onAction("dismissed") }, Modifier.fillMaxWidth(), enabled = !busy)
            AmoNo(stringResource(R.string.amo_exc_confirm), { onAction("confirmed") }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}
