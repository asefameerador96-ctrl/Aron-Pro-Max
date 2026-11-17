package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.outlet.GeoPhotoState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lead gate lows 2 and 3 (2026-11-13): the waiting state is announced and labelled; the fallback page says why when Camera is off. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class ManualOverrideContentTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, waiting: Boolean = false, cameraDenied: Boolean = false) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language, amo = true) { ManualOverrideContent(GeoPhotoState(), emptySet(), {}, {}, waiting = waiting, cameraDenied = cameraDenied) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun waitingShowsALabelAndSpeaksItInEnglish() {
        show(AppLanguage.EN, waiting = true)
        compose.onNodeWithTag(ManualOverrideTags.WAITING).assertIsDisplayed().assertContentDescriptionEquals("Please wait…")
        compose.onNodeWithText("Please wait…", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun waitingShowsALabelAndSpeaksItInBangla() {
        show(AppLanguage.BN, waiting = true)
        compose.onNodeWithTag(ManualOverrideTags.WAITING).assertContentDescriptionEquals("অনুগ্রহ করে অপেক্ষা করুন…")
        compose.onNodeWithText("অনুগ্রহ করে অপেক্ষা করুন…", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun cameraOffFallbackNamesTheReasonInEnglish() {
        show(AppLanguage.EN, cameraDenied = true)
        compose.onNodeWithTag(ManualOverrideTags.CAMERA_DENIED).assertIsDisplayed()
        compose.onNodeWithText("Camera permission is off", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun cameraOffFallbackNamesTheReasonInBangla() {
        show(AppLanguage.BN, cameraDenied = true)
        compose.onNodeWithText("ক্যামেরার অনুমতি বন্ধ আছে", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun noReasonIsShownWhenCameraIsAllowed() {
        show(AppLanguage.EN)
        compose.onNodeWithTag(ManualOverrideTags.CAMERA_DENIED).assertDoesNotExist()
    }
}
