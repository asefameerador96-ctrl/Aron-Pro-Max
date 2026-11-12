package com.aktcl.aron.core.system.update

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.system.R
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook

/** Test tags of the SR update page (manual screenshots 06 and 07). */
object SrUpdateTags {
    const val NETWORK_STATUS = "sr_update_network_status"
    const val PROGRESS = "sr_update_progress"
    const val PROGRESS_TEXT = "sr_update_progress_text"
    const val WARNING = "sr_update_warning"
    const val NOTES = "sr_update_notes"
}

/**
 * The SR app's update page (owner's SR manual screenshots 06 and 07, SRM-A-09 and SRM-A-10): the white strip "Network Status" with the
 * green "অনলাইন" dot, the orange "NEW UPDATE" ribbon, the purple cloud, "Update Available", "Version x available", the release notes
 * (from the server's `notes_en` / `notes_bn`, in the rep's language), then a flat red "Download App & Install" button; while the file
 * downloads the button gives way to a red bar with "Downloading n%" and the red warning "Please do not close the app while
 * downloading"; at the end "Download completed" and the Install button stays as the way back after the system installer's Cancel. The
 * states the vendor does not draw (Wi-Fi only, no space, failed, unknown sources, sync busy) keep our banners and actions. Resumable
 * download, checksum and signature checks are unchanged (the shell). "Later" stays for an optional update in every state, the download included; a required update has none.
 */
@Composable
fun SrUpdateContent(
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
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().background(look.page).testTag(UpdateTags.SCREEN)) {
        SrNetworkStrip(network)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AronTokens.Space.L),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SrRibbon(stringResource(R.string.update_sr_ribbon))
            SrCloud()
            Text(
                stringResource(R.string.update_sr_heading), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = look.ink,
                modifier = Modifier.testTag(UpdateTags.AMO_HEADING),
            )
            Text(stringResource(R.string.update_sr_version, release.versionName), style = MaterialTheme.typography.bodyLarge, color = look.ink)
            // the screenshots show the notes in English: English first, Bangla only when the release has no English notes
            val notes = release.notesEn?.takeIf { it.isNotBlank() } ?: release.notesBn
            notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.testTag(SrUpdateTags.NOTES))
            }
            when (download) {
                DownloadUi.Idle -> SrRedButton(stringResource(R.string.update_sr_download), onDownload, enabled = network != NetworkStatus.OFFLINE, tag = UpdateTags.DOWNLOAD)
                is DownloadUi.Downloading -> {
                    SrProgress(download.percent)
                    Text(
                        stringResource(R.string.update_sr_downloading, download.percent.toString()), style = MaterialTheme.typography.bodyLarge, color = look.ink,
                        modifier = Modifier.testTag(SrUpdateTags.PROGRESS_TEXT),
                    )
                    SrDoNotClose()
                }
                DownloadUi.Ready -> {
                    SrProgress(100)
                    Text(stringResource(R.string.update_sr_completed), style = MaterialTheme.typography.bodyLarge, color = look.ink, modifier = Modifier.testTag(SrUpdateTags.PROGRESS_TEXT))
                    SrRedButton(stringResource(R.string.update_install), onInstall, tag = UpdateTags.INSTALL)
                }
                DownloadUi.NeedsWifi -> AronBanner(stringResource(R.string.update_needs_wifi), kind = BannerKind.Info)
                DownloadUi.NoSpace -> AronBanner(stringResource(R.string.update_no_space), kind = BannerKind.Error)
                DownloadUi.Failed -> {
                    AronBanner(stringResource(R.string.update_failed), kind = BannerKind.Error)
                    SrRedButton(stringResource(R.string.update_retry), onDownload, tag = UpdateTags.DOWNLOAD)
                }
                DownloadUi.SyncBusy -> {
                    AronBanner(stringResource(R.string.update_sync_busy), kind = BannerKind.Info)
                    SrRedButton(stringResource(R.string.update_install), onInstall, tag = UpdateTags.INSTALL)
                }
                DownloadUi.NeedsUnknownSources -> {
                    AronBanner(stringResource(R.string.update_unknown_sources_why), kind = BannerKind.Warning)
                    Text(stringResource(R.string.update_unknown_sources_steps), style = MaterialTheme.typography.bodyLarge, color = look.ink)
                    AronPrimaryButton(stringResource(R.string.update_open_settings), onOpenUnknownSources, Modifier.fillMaxWidth().testTag(UpdateTags.UNKNOWN_SOURCES))
                }
            }
            // an optional update can always be put off, also while it downloads (the download goes on in the background); a required one cannot
            if (!required) AronSecondaryButton(stringResource(R.string.update_later), onLater, Modifier.fillMaxWidth().testTag(UpdateTags.LATER))
        }
    }
}

/** "Network Status" at the start, the status dot and word at the end (green and "অনলাইন" online; grey and "অফলাইন" offline): never colour alone. */
@Composable
private fun SrNetworkStrip(network: NetworkStatus) {
    val look = LocalAronLook.current
    val online = network != NetworkStatus.OFFLINE
    Row(
        Modifier.fillMaxWidth().background(look.card).padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M).testTag(SrUpdateTags.NETWORK_STATUS),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
    ) {
        Text(stringResource(R.string.update_sr_network_status), style = MaterialTheme.typography.bodyMedium, color = look.ink)
        Box(Modifier.padding(horizontal = 8.dp).size(10.dp).clip(CircleShape).background(if (online) look.online else look.offlineDot))
        Text(
            stringResource(if (online) R.string.update_sr_online else R.string.update_sr_offline), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = if (online) Color(0xFF1B7A2E) else look.secondaryText,
        )
    }
}

@Composable
private fun SrRibbon(text: String) {
    Box(
        Modifier.padding(vertical = AronTokens.Space.M).background(LocalAronLook.current.amber, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(2.dp)).padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

/** The purple cloud with the download arrow, drawn (no bitmap: the APK stays lean). */
@Composable
private fun SrCloud() {
    val purple = LocalAronLook.current.purple
    Canvas(Modifier.size(width = 150.dp, height = 100.dp).semantics { contentDescription = "" }) {
        val w = size.width
        val h = size.height
        drawCircle(purple, radius = h * 0.27f, center = Offset(w * 0.30f, h * 0.60f))
        drawCircle(purple, radius = h * 0.36f, center = Offset(w * 0.52f, h * 0.45f))
        drawCircle(purple, radius = h * 0.26f, center = Offset(w * 0.74f, h * 0.60f))
        drawRoundRect(purple, Offset(w * 0.30f, h * 0.58f), Size(w * 0.44f, h * 0.29f), CornerRadius(h * 0.14f))
        val stroke = Stroke(width = h * 0.075f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val arrow = Path().apply {
            moveTo(w * 0.52f, h * 0.30f); lineTo(w * 0.52f, h * 0.70f)
            moveTo(w * 0.42f, h * 0.60f); lineTo(w * 0.52f, h * 0.70f); lineTo(w * 0.62f, h * 0.60f)
        }
        drawPath(arrow, Color.White, style = stroke)
    }
}

@Composable
private fun SrProgress(percent: Int) {
    val look = LocalAronLook.current
    val p = percent.coerceIn(0, 100)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(look.track).testTag(SrUpdateTags.PROGRESS).semantics { contentDescription = "$p%" }) {
        Box(Modifier.fillMaxWidth(p / 100f).height(6.dp).background(look.red))
    }
}

/** The red warning line with its info glyph (the vendor draws it in a serif face; ours uses the app's face). */
@Composable
private fun SrDoNotClose() {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().testTag(SrUpdateTags.WARNING), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        // the round "i" glyph, drawn (a stem and a dot): no text literal, no bitmap
        Canvas(Modifier.size(20.dp)) {
            drawCircle(look.red)
            drawCircle(Color.White, radius = size.width * 0.07f, center = Offset(size.width / 2, size.height * 0.3f))
            drawLine(Color.White, Offset(size.width / 2, size.height * 0.45f), Offset(size.width / 2, size.height * 0.78f), size.width * 0.13f, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Text(stringResource(R.string.update_sr_do_not_close), color = look.red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
    }
}

/** The flat red button of the manual (not the gradient): white bold label, small corners, 52 dp tall. */
@Composable
private fun SrRedButton(text: String, onClick: () -> Unit, tag: String, enabled: Boolean = true) {
    val look = LocalAronLook.current
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(if (enabled) look.red else look.primaryDisabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)) }
}
