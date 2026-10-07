package com.aktcl.aron.feature.dayclose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

private const val SEP = "  "

/** What the Sales Submit screen shows after the submit was tapped. */
enum class SubmitProgress { Idle, Queued, Settled }

/**
 * Sales Submit with the sync reconciliation: Online status, device versus server counts, Sync data, and a Submit that
 * is enabled only by [SubmitGate]. Success text shows only once the server has settled the submit (F-SR-034/035).
 */
@Composable
fun SalesSubmitScreen(
    online: Boolean,
    counts: List<CountRow>,
    gate: SubmitGate,
    progress: SubmitProgress,
    typeLabel: (String) -> String,
    onSync: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    syncing: Boolean = false,
) {
    val bn = LocalAppLanguage.current == AppLanguage.BN
    AronCard(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.submit_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (online) R.string.submit_status_online else R.string.submit_status_offline))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(""); Text(listOf(stringResource(R.string.submit_col_device), stringResource(R.string.submit_col_server)).joinToString(SEP))
            }
            counts.forEach { c ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(typeLabel(c.recordType))
                    Text(listOf(localizedNumber(c.device.toLong()), c.server?.let { localizedNumber(it.toLong()) } ?: stringResource(R.string.submit_server_blank)).joinToString(SEP))
                }
            }
            gate.blocks.forEach { b -> AronBanner(blockText(b), kind = BannerKind.Warning) }
            gate.notes.forEach { n -> AronBanner(blockText(n), kind = BannerKind.Info) }
            gate.duesWarning?.let { d ->
                val money = Formats.money(d.outstandingMtk, if (bn) UiLocale.BN else UiLocale.EN)
                AronBanner(stringResource(R.string.submit_dues_warning, localizedNumber(d.retailersWithDues.toLong()), money), kind = BannerKind.Warning)
            }
            AronSecondaryButton(stringResource(R.string.submit_sync), onSync, Modifier.fillMaxWidth(), enabled = SalesSubmitRules.syncButtonEnabled(online, syncing))
            AronPrimaryButton(stringResource(R.string.submit_button), onSubmit, Modifier.fillMaxWidth(), enabled = gate.enabled && progress == SubmitProgress.Idle)
            when (progress) {
                SubmitProgress.Queued -> AronBanner(stringResource(R.string.submit_queued))
                SubmitProgress.Settled -> AronBanner(stringResource(R.string.submit_success))
                SubmitProgress.Idle -> Unit
            }
        }
    }
}

@Composable
private fun blockText(b: SubmitBlock): String = when (b) {
    is SubmitBlock.Unsynced -> stringResource(R.string.submit_blocked_unsynced, localizedNumber((b.pending + b.inFlight).toLong()))
    is SubmitBlock.CountMismatch -> stringResource(R.string.submit_blocked_mismatch, b.recordType, localizedNumber(b.device.toLong()), b.server?.let { localizedNumber(it.toLong()) } ?: stringResource(R.string.submit_server_blank))
    SubmitBlock.AlreadySubmitted -> stringResource(R.string.submit_blocked_done)
    is SubmitBlock.Rejected -> stringResource(R.string.submit_note_rejected, localizedNumber(b.count.toLong()))
    is SubmitBlock.Quarantined -> stringResource(R.string.submit_note_quarantined, localizedNumber(b.count.toLong()))
}
