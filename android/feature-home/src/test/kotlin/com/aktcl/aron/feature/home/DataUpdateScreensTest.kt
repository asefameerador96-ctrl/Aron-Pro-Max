package com.aktcl.aron.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SR manual 08 (SRM-A-11): the data update prompt and its "প্রসেসিং(n/total)" progress page, Bangla and English. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class DataUpdateScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        language: AppLanguage, phase: DataUpdatePhase, done: Int? = null, total: Int? = null, reason: FirstBundleState? = null,
        onUpdate: () -> Unit = {}, onSkip: (() -> Unit)? = null,
    ) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { DataUpdateContent(phase, "1.0.1", onUpdate, done = done, total = total, reason = reason, onSkip = onSkip) } }
        }
        compose.waitForIdle()
    }

    @Test fun theBanglaPromptSaysTheManualWordsAndTheButtonStartsTheUpdate() {
        var taps = 0
        show(AppLanguage.BN, DataUpdatePhase.PROMPT, onUpdate = { taps++ })
        compose.onNodeWithText("কিছু নতুন আপডেট পাওয়া গেছে. এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে!").assertIsDisplayed()
        compose.onNodeWithText("আপডেট").assertIsDisplayed()
        compose.onNodeWithTag(DataUpdateTags.VERSION).assertTextEquals("Current version SR App 1.0.1")
        compose.onNodeWithTag(DataUpdateTags.UPDATE).performClick()
        assertEquals(1, taps)
    }

    @Test fun theEnglishPromptIsTheSameInEnglish() {
        show(AppLanguage.EN, DataUpdatePhase.PROMPT)
        compose.onNodeWithText("Some new updates have been found. To keep using this app, you must update!").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsDisplayed()
    }

    @Test fun progressShowsTheStepCounterInBanglaDigitsTheHeadingAndTheRedTip() {
        show(AppLanguage.BN, DataUpdatePhase.RUNNING, done = 14, total = 87)
        compose.onNodeWithText("অ্যাপ আপডেট হচ্ছে").assertIsDisplayed()
        compose.onNodeWithTag(DataUpdateTags.COUNTER).assertTextEquals("প্রসেসিং(১৪/৮৭)")
        compose.onNodeWithTag(DataUpdateTags.TIP).assertTextEquals("টিপস: অ্যাপটি বন্ধ করবেন না")
        compose.onNodeWithTag(DataUpdateTags.BAR).assertIsDisplayed()
        compose.onNodeWithTag(DataUpdateTags.UPDATE).assertDoesNotExist() // no button, no way off the page while it runs
    }

    @Test fun progressInEnglishUsesLatinDigits() {
        show(AppLanguage.EN, DataUpdatePhase.RUNNING, done = 3, total = 4)
        compose.onNodeWithTag(DataUpdateTags.COUNTER).assertTextEquals("Processing(3/4)")
    }

    @Test fun whileTheSizeIsNotKnownYetThereIsNoCounterAndNeverAZeroOverZero() {
        show(AppLanguage.EN, DataUpdatePhase.RUNNING, done = null, total = null)
        compose.onNodeWithTag(DataUpdateTags.COUNTER).assertDoesNotExist()
        compose.onNodeWithTag(DataUpdateTags.BAR).assertIsDisplayed()
    }

    @Test fun anOfflineAttemptBringsBackThePromptWithTheReasonAndAQuietSkip() {
        var skips = 0
        show(AppLanguage.EN, DataUpdatePhase.PROMPT, reason = FirstBundleState.OFFLINE, onSkip = { skips++ })
        compose.onNodeWithTag(DataUpdateTags.REASON).assertIsDisplayed()
        compose.onNodeWithTag(DataUpdateTags.SKIP).performClick()
        assertEquals(1, skips)
    }

    @Test fun theSkipLinkIsThereWhileTheDownloadRunsToo() {
        var skips = 0
        show(AppLanguage.EN, DataUpdatePhase.RUNNING, done = 1, total = 4, onSkip = { skips++ })
        compose.onNodeWithTag(DataUpdateTags.SKIP).performScrollTo().performClick()
        assertEquals(1, skips)
    }

    @Test fun theSkipLinkIsThereBeforeTheFirstStepIsKnownRightAfterARelaunch() {
        show(AppLanguage.EN, DataUpdatePhase.RUNNING, done = null, total = null, onSkip = {})
        compose.onNodeWithTag(DataUpdateTags.SKIP).assertExists()
    }

    @Test fun withoutASkipCallbackThereIsNoSkipLink() {
        show(AppLanguage.EN, DataUpdatePhase.PROMPT)
        compose.onNodeWithTag(DataUpdateTags.SKIP).assertDoesNotExist()
    }

    @Test fun theDeferredCameraIsNotInTheFirstRunList() {
        val granted = AppPermission.entries.associateWith { PermissionStatus.NOT_ASKED }
        val plain = PermissionState(granted)
        val sr = PermissionState(granted, deferred = setOf(AppPermission.CAMERA))
        assertTrue(AppPermission.CAMERA in plain.toAsk)
        assertFalse(AppPermission.CAMERA in sr.toAsk)
        assertEquals(plain.toAsk - AppPermission.CAMERA, sr.toAsk)
        assertFalse(sr.canTakePhoto) // deferred is not granted
    }
}
