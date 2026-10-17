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
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronNoButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.feature.outlet.KitTextField

@Composable
private fun codeLabel(code: String): String = when (code) {
    "GEO_MOCK" -> stringResource(R.string.amo_exc_code_mock)
    "GEO_TELEPORT" -> stringResource(R.string.amo_exc_code_teleport)
    else -> code // an unknown code is shown by its code
}

@Composable
private fun groupLabel(g: ExceptionGroup) = stringResource(
    when (g) { ExceptionGroup.GEO -> R.string.amo_exc_group_geo; ExceptionGroup.DEVICE -> R.string.amo_exc_group_device; ExceptionGroup.CLOCK -> R.string.amo_exc_group_clock; ExceptionGroup.OTHER -> R.string.amo_exc_group_other },
)

/** F-AMO-038: today's open signals of the zone; [names] maps user ids to SR names. */
@Composable
fun ExceptionsListContent(rows: List<RiskSignalRow>, names: Map<Long, String>, onOpen: (RiskSignalRow) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        AronEmptyState(stringResource(R.string.amo_exc_empty), modifier.fillMaxSize(), hint = stringResource(R.string.amo_exc_empty_hint))
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        rows.forEachIndexed { i, r ->
            AronListRow(
                codeLabel(r.code), subtitle = listOfNotNull(groupLabel(Exceptions.group(r.code)), r.userId?.let { names[it] }).joinToString(stringResource(R.string.amo_verify_sep)),
                trailing = "▲".repeat(r.severity.coerceIn(1, 4)), onClick = { onOpen(r) },
                position = if (rows.size == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == rows.lastIndex) RowPosition.Last else RowPosition.Middle,
                divider = i < rows.lastIndex, flat = true,
            )
        }
    }
}

/** One signal with the numbers that fired it; Review, Dismiss and Confirm each queue one idempotent event (works offline). */
@Composable
fun ExceptionDetailContent(
    row: RiskSignalRow, userName: String?, note: String, busy: Boolean, onNote: (String) -> Unit, onAction: (String) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            AronListRow(codeLabel(row.code), subtitle = listOfNotNull(groupLabel(Exceptions.group(row.code)), userName).joinToString(stringResource(R.string.amo_verify_sep)), flat = true)
            AronSectionLabel(stringResource(R.string.amo_exc_evidence))
            row.evidence.forEach { (k, v) -> AronListRow(v, subtitle = k, flat = true) }
            KitTextField(note, onNote, stringResource(R.string.amo_verify_note), Modifier.fillMaxWidth(), singleLine = false)
        }
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            AronSecondaryButton(stringResource(R.string.amo_exc_review), { onAction("reviewed") }, Modifier.fillMaxWidth(), enabled = !busy)
            AronSecondaryButton(stringResource(R.string.amo_exc_dismiss), { onAction("dismissed") }, Modifier.fillMaxWidth(), enabled = !busy)
            AronNoButton(stringResource(R.string.amo_exc_confirm), { onAction("confirmed") }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}
