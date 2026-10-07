package com.aktcl.aron.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-001: the first-bundle screen in its three states, Bangla and English. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FirstBundleScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, state: FirstBundleState, onRetry: () -> Unit = {}, onSkip: () -> Unit = {}) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { FirstBundleContent(state, onRetry, onSkip) } } }
        compose.waitForIdle()
    }

    @Test fun downloadingShowsProgressAndResumeNoteInBangla() {
        show(AppLanguage.BN, FirstBundleState.DOWNLOADING)
        compose.onNodeWithTag(FirstBundleTags.PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("আপনার দিন প্রস্তুত হচ্ছে").assertIsDisplayed()
        compose.onNodeWithTag(FirstBundleTags.RETRY).assertDoesNotExist()
    }

    @Test fun failedOffersRetryAndSkip() {
        var retry = 0; var skip = 0
        show(AppLanguage.EN, FirstBundleState.FAILED, { retry++ }, { skip++ })
        compose.onNodeWithTag(FirstBundleTags.RETRY).performClick()
        compose.onNodeWithTag(FirstBundleTags.SKIP).performClick()
        assertEquals(1, retry); assertEquals(1, skip)
    }

    @Test fun offlineSaysSoAndKeepsRetry() {
        show(AppLanguage.EN, FirstBundleState.OFFLINE)
        compose.onNodeWithText("No connection. Connect once to download your route. What was downloaded is kept.").assertIsDisplayed()
        compose.onNodeWithTag(FirstBundleTags.RETRY).assertIsDisplayed()
    }
}
