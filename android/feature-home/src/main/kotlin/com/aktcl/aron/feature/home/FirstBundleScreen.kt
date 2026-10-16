package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronIllustration
import com.aktcl.aron.core.ui.AronMeter
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.core.ui.skeleton

/** Where the first bundle download stands (F-SR-001). */
enum class FirstBundleState { DOWNLOADING, OFFLINE, FAILED, AUTH }

object FirstBundleTags {
    const val SCREEN = "fb_screen"
    const val PROGRESS = "fb_progress"
    const val RETRY = "fb_retry"
    const val SKIP = "fb_skip"
}

/**
 * The first bundle on a new phone (F-SR-001): the download is resumable (staged pages are kept), so the screen says it
 * continues where it stopped; offline and failure show their own words and a retry in the bottom bar. The SR can always
 * go on without the route (check-in never depends on the bundle). [progressPercent] fills the meter when the downloader
 * reports it; while unknown the meter is a skeleton band (never a spinner). Solid surfaces and the kit's buttons only.
 */
@Composable
fun FirstBundleContent(state: FirstBundleState, onRetry: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier, progressPercent: Int? = null) {
    val c = LocalAronColors.current
    val retry = state == FirstBundleState.OFFLINE || state == FirstBundleState.FAILED
    AronScreen(
        modifier = modifier.testTag(FirstBundleTags.SCREEN),
        topBar = { AronTopBar(stringResource(R.string.fb_title)) },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.L),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val (icon, disc, glyph) = when (state) {
                FirstBundleState.DOWNLOADING -> Triple(AronIcons.CloudUpload, c.accentContainer, c.accentOnContainer)
                FirstBundleState.OFFLINE -> Triple(AronIcons.CloudOff, c.offlineContainer, c.offlineOnContainer)
                FirstBundleState.FAILED -> Triple(AronIcons.Warning, c.warningContainer, c.warningOnContainer)
                FirstBundleState.AUTH -> Triple(AronIcons.Lock, c.dangerContainer, c.dangerOnContainer)
            }
            AronIllustration(icon, disc, glyph)
            when (state) {
                FirstBundleState.DOWNLOADING -> AronCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        Box(Modifier.fillMaxWidth().testTag(FirstBundleTags.PROGRESS)) {
                            AronMeter(progressPercent ?: 0, 100, Modifier.skeleton(progressPercent == null))
                        }
                        Text(
                            if (progressPercent != null) localizedNumber(progressPercent.toLong()) + "%" else stringResource(R.string.fb_progress_unknown),
                            style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
                        )
                        Text(stringResource(R.string.fb_downloading), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    }
                }
                FirstBundleState.OFFLINE -> AronBanner(stringResource(R.string.fb_offline), kind = BannerKind.Offline)
                FirstBundleState.AUTH -> AronBanner(stringResource(R.string.fb_auth), kind = BannerKind.Error)
                FirstBundleState.FAILED -> AronBanner(stringResource(R.string.fb_failed), kind = BannerKind.Error)
            }
            if (retry) AronPrimaryButton(stringResource(R.string.fb_retry), onRetry, Modifier.testTag(FirstBundleTags.RETRY), icon = AronIcons.Refresh)
            AronSecondaryButton(stringResource(R.string.fb_skip), onSkip, Modifier.testTag(FirstBundleTags.SKIP))
        }
    }
}
