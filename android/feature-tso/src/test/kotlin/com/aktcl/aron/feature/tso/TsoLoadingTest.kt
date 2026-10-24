package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The TSO loading state of the dashboard, the leave list and the feedback list is one kit skeleton card (`TsoLoading`, no
 * spinner, no animation) announced to TalkBack as "Loading" in the user's language.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoLoadingTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { TsoLoading() } } }
        compose.waitForIdle()
    }

    @Test fun theSkeletonIsAnnouncedAsLoadingInEnglish() { show(AppLanguage.EN); compose.onNodeWithContentDescription("Loading").assertExists() }

    @Test fun theSkeletonIsAnnouncedAsLoadingInBangla() { show(AppLanguage.BN); compose.onNodeWithContentDescription("লোড হচ্ছে").assertExists() }
}
