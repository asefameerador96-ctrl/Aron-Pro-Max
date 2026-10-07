package com.aktcl.aron.core.system.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.system.R
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

/** What the update screen shows below the release. */
sealed interface DownloadUi {
    data object Idle : DownloadUi
    data class Downloading(val percent: Int) : DownloadUi
    data object Ready : DownloadUi
    data object NeedsWifi : DownloadUi
    data object NoSpace : DownloadUi
    data object Failed : DownloadUi
    data object NeedsUnknownSources : DownloadUi
    data object SyncBusy : DownloadUi
}

enum class NetworkStatus { WIFI, MOBILE, OFFLINE }

object UpdateTags {
    const val SCREEN = "update_screen"
    const val DOWNLOAD = "update_download"
    const val INSTALL = "update_install"
    const val LATER = "update_later"
    const val UNKNOWN_SOURCES = "update_unknown_sources"
    const val DAY_BLOCKED = "update_day_blocked"
}

/**
 * The update page (F-SYS-020; SR p7 to p8 parity): the release and its notes in the rep's language, a network-status line,
 * download with percentage ("do not close the app"), then install. A required update has no "Later".
 */
@Composable
fun UpdateContent(
    release: ReleaseInfo,
    required: Boolean,
    network: NetworkStatus,
    download: DownloadUi,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L).testTag(UpdateTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(if (required) R.string.update_title_required else R.string.update_title), style = MaterialTheme.typography.headlineSmall)
        // Version names are identifiers (never localized digits); the size is a number.
        Text(stringResource(R.string.update_version_size, release.versionName, localizedNumber(maxOf(1, (release.sizeBytes + 524_288) / 1_048_576))), style = MaterialTheme.typography.bodyLarge)
        val notes = if (LocalAppLanguage.current == AppLanguage.BN) release.notesBn ?: release.notesEn else release.notesEn ?: release.notesBn
        notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            stringResource(
                when (network) {
                    NetworkStatus.WIFI -> R.string.update_network_wifi
                    NetworkStatus.MOBILE -> R.string.update_network_mobile
                    NetworkStatus.OFFLINE -> R.string.update_network_offline
                },
            ),
            style = MaterialTheme.typography.labelLarge,
        )
        when (download) {
            DownloadUi.Idle -> AronPrimaryButton(stringResource(R.string.update_download), onDownload, Modifier.fillMaxWidth().testTag(UpdateTags.DOWNLOAD), enabled = network != NetworkStatus.OFFLINE)
            is DownloadUi.Downloading -> {
                LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(localizedDigits(stringResource(R.string.update_progress, download.percent)), style = MaterialTheme.typography.bodyLarge)
                AronBanner(stringResource(R.string.update_do_not_close), kind = BannerKind.Warning)
            }
            DownloadUi.Ready -> AronPrimaryButton(stringResource(R.string.update_install), onInstall, Modifier.fillMaxWidth().testTag(UpdateTags.INSTALL))
            DownloadUi.NeedsWifi -> AronBanner(stringResource(R.string.update_needs_wifi), kind = BannerKind.Info)
            DownloadUi.NoSpace -> AronBanner(stringResource(R.string.update_no_space), kind = BannerKind.Error)
            DownloadUi.Failed -> {
                AronBanner(stringResource(R.string.update_failed), kind = BannerKind.Error)
                AronPrimaryButton(stringResource(R.string.update_retry), onDownload, Modifier.fillMaxWidth().testTag(UpdateTags.DOWNLOAD))
            }
            DownloadUi.SyncBusy -> {
                AronBanner(stringResource(R.string.update_sync_busy), kind = BannerKind.Info)
                AronPrimaryButton(stringResource(R.string.update_install), onInstall, Modifier.fillMaxWidth().testTag(UpdateTags.INSTALL))
            }
            DownloadUi.NeedsUnknownSources -> {
                AronBanner(stringResource(R.string.update_unknown_sources_why), kind = BannerKind.Warning)
                Text(stringResource(R.string.update_unknown_sources_steps), style = MaterialTheme.typography.bodyLarge)
                AronPrimaryButton(stringResource(R.string.update_open_settings), onOpenUnknownSources, Modifier.fillMaxWidth().testTag(UpdateTags.UNKNOWN_SOURCES))
            }
        }
        if (!required) AronSecondaryButton(stringResource(R.string.update_later), onLater, Modifier.fillMaxWidth().testTag(UpdateTags.LATER))
    }
}

/**
 * The new-day gate (min_version): [gate] BLOCKED shows the block with the way to update; FINISH_OPEN_DAY_ONLY shows a
 * banner and lets the open day go on. Upload is never blocked here.
 */
@Composable
fun DayGateBanner(gate: DayGate, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    when (gate) {
        DayGate.OPEN -> Unit
        DayGate.FINISH_OPEN_DAY_ONLY -> AronBanner(stringResource(R.string.update_finish_day_first), modifier, kind = BannerKind.Warning, onClick = onUpdate)
        DayGate.BLOCKED_UPDATE_REQUIRED -> Column(modifier.testTag(UpdateTags.DAY_BLOCKED), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AronBanner(stringResource(R.string.update_day_blocked), kind = BannerKind.Error)
            AronPrimaryButton(stringResource(R.string.update_now), onUpdate, Modifier.fillMaxWidth())
        }
    }
}
