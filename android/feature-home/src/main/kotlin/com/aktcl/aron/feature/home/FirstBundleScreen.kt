package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind

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
 * continues where it stopped; offline and failure show their own words and a retry. The SR can always go on without the
 * route (check-in never depends on the bundle). Solid surfaces and the kit's buttons only.
 */
@Composable
fun FirstBundleContent(state: FirstBundleState, onRetry: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(AronTokens.Space.L).testTag(FirstBundleTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M, androidx.compose.ui.Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.fb_title), style = MaterialTheme.typography.headlineSmall)
        when (state) {
            FirstBundleState.DOWNLOADING -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag(FirstBundleTags.PROGRESS))
                Text(stringResource(R.string.fb_downloading), style = MaterialTheme.typography.bodyLarge)
            }
            FirstBundleState.OFFLINE -> {
                AronBanner(stringResource(R.string.fb_offline), kind = BannerKind.Warning)
                AronPrimaryButton(stringResource(R.string.fb_retry), onRetry, Modifier.testTag(FirstBundleTags.RETRY))
            }
            FirstBundleState.AUTH -> AronBanner(stringResource(R.string.fb_auth), kind = BannerKind.Error)
            FirstBundleState.FAILED -> {
                AronBanner(stringResource(R.string.fb_failed), kind = BannerKind.Error)
                AronPrimaryButton(stringResource(R.string.fb_retry), onRetry, Modifier.testTag(FirstBundleTags.RETRY))
            }
        }
        AronSecondaryButton(stringResource(R.string.fb_skip), onSkip, Modifier.testTag(FirstBundleTags.SKIP))
    }
}
