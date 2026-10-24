package com.aktcl.aron.core.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.aron.AronLoadingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The whole-screen loading state (a skeleton card, no spinner) is announced as "Loading" in the user's language. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AronLoadingScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, label: String? = null) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { if (label == null) AronLoadingScreen() else AronLoadingScreen(label = label) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun announcedAsLoadingInEnglish() { show(AppLanguage.EN); compose.onNodeWithContentDescription("Loading").assertExists() }

    @Test fun announcedAsLoadingInBangla() { show(AppLanguage.BN); compose.onNodeWithContentDescription("লোড হচ্ছে").assertExists() }

    @Test fun aCallerLabelReplacesTheDefault() { show(AppLanguage.EN, "Checking"); compose.onNodeWithContentDescription("Checking").assertExists() }
}
