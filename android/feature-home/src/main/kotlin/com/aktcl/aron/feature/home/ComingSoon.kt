package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronEmptyState

object ComingSoonTags { const val SCREEN = "hm_coming_soon" }

/**
 * The plain "coming with the next update" screen (SRM-A-13): where a Home tile's real screen is built by another lane, the
 * tile opens this instead of a dead tap or a crash. It needs no data, so it works offline and cannot fail. The caller's
 * frame supplies the title (the tile's own name) and the back button.
 */
@Composable
fun ComingSoonContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(ComingSoonTags.SCREEN), contentAlignment = Alignment.Center) {
        AronEmptyState(
            title = stringResource(R.string.hm_soon_title),
            hint = stringResource(R.string.hm_soon_body),
            actionLabel = stringResource(R.string.hm_soon_back),
            onAction = onBack,
        )
    }
}
