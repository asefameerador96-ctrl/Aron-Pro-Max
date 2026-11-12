package com.aktcl.aron.core.system.update

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The SR update page of the owner's manual (screenshots 06 and 07; SRM-A-09, SRM-A-10). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SrUpdateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val notes = ReleaseInfo("1.0.25", 25, "arm64-v8a", "a".repeat(64), 25_000_000, "https://x/y.apk", "c".repeat(64), "New promotion modality., Login time 2FA verification.", "নতুন সুবিধা, লগইনের সময় যাচাই।")
    private val bare = notes.copy(notesEn = null, notesBn = null)

    private fun show(
        language: AppLanguage, release: ReleaseInfo = notes, required: Boolean = false, network: NetworkStatus = NetworkStatus.WIFI, download: DownloadUi = DownloadUi.Idle,
        onDownload: () -> Unit = {}, onInstall: () -> Unit = {}, onLater: () -> Unit = {},
    ) = rule.setContent { AronTheme(language) { SrUpdateContent(release, required, network, download, onDownload, onInstall, {}, onLater) } }

    @Test fun theEnglishPageHasTheStripRibbonHeadingVersionNotesAndTheRedButton() {
        var tapped = 0
        show(AppLanguage.EN, onDownload = { tapped++ })
        rule.onNodeWithText("Network Status").assertIsDisplayed()
        rule.onNodeWithText("Online").assertIsDisplayed()
        rule.onNodeWithText("NEW UPDATE").assertIsDisplayed()
        rule.onNodeWithText("Update Available").assertIsDisplayed()
        rule.onNodeWithText("Version 1.0.25 available").assertIsDisplayed()
        rule.onNodeWithTag(SrUpdateTags.NOTES).assertIsDisplayed()
        rule.onNodeWithText("New promotion modality., Login time 2FA verification.").assertExists()
        rule.onNodeWithText("Download App & Install").performClick()
        assertEquals(1, tapped)
    }

    @Test @Config(qualifiers = "bn") fun theBanglaPageShowsBanglaWordsAndTheBanglaNotes() {
        show(AppLanguage.BN)
        rule.onNodeWithText("অনলাইন").assertIsDisplayed()
        rule.onNodeWithText("নতুন আপডেট").assertExists()
        rule.onNodeWithText("আপডেট পাওয়া যাচ্ছে").assertExists()
        rule.onNodeWithText("অ্যাপ ডাউনলোড ও ইনস্টল করুন").assertExists()
        rule.onNodeWithText("নতুন সুবিধা, লগইনের সময় যাচাই।").assertExists()
        rule.onNodeWithText("New promotion modality., Login time 2FA verification.").assertDoesNotExist()
    }

    @Test fun noNotesMeansNoNotesLineAndNeverAPlaceholder() {
        show(AppLanguage.EN, release = bare)
        rule.onNodeWithTag(SrUpdateTags.NOTES).assertDoesNotExist()
    }

    @Test fun offlineShowsTheOfflineWordAndTheDownloadCannotStart() {
        var tapped = 0
        show(AppLanguage.EN, network = NetworkStatus.OFFLINE, onDownload = { tapped++ })
        rule.onNodeWithText("Offline").assertIsDisplayed()
        rule.onNodeWithTag(UpdateTags.DOWNLOAD).performClick()
        assertEquals(0, tapped)
    }

    @Test fun downloadingShowsTheBarThePercentAndTheRedWarningAndNoButtons() {
        show(AppLanguage.EN, download = DownloadUi.Downloading(24))
        rule.onNodeWithTag(SrUpdateTags.PROGRESS).assertIsDisplayed()
        rule.onNodeWithText("Downloading 24%").assertIsDisplayed()
        rule.onNodeWithTag(SrUpdateTags.WARNING).assertExists()
        rule.onNodeWithText("Please do not close the app while downloading").assertExists()
        rule.onNodeWithTag(UpdateTags.DOWNLOAD).assertDoesNotExist()
        rule.onNodeWithTag(UpdateTags.LATER).assertDoesNotExist() // no way off the page mid-download
    }

    @Test @Config(qualifiers = "bn") fun downloadingInBanglaUsesBanglaDigits() {
        show(AppLanguage.BN, download = DownloadUi.Downloading(24))
        rule.onNodeWithText("ডাউনলোড হচ্ছে ২৪%").assertIsDisplayed()
        rule.onNodeWithText("ডাউনলোডের সময় অনুগ্রহ করে অ্যাপটি বন্ধ করবেন না").assertExists()
    }

    @Test fun completedSaysSoAndKeepsTheInstallButtonAsTheWayBackAfterCancel() {
        var installs = 0
        show(AppLanguage.EN, download = DownloadUi.Ready, onInstall = { installs++ })
        rule.onNodeWithText("Download completed").assertIsDisplayed()
        rule.onNodeWithTag(UpdateTags.INSTALL).performScrollTo().performClick()
        assertEquals(1, installs)
    }

    @Test fun aRequiredUpdateHasNoLaterAndAnOptionalOneHasIt() {
        show(AppLanguage.EN, required = true)
        rule.onNodeWithTag(UpdateTags.LATER).assertDoesNotExist()
    }

    @Test fun anOptionalUpdateCanBePostponed() {
        var later = 0
        show(AppLanguage.EN, required = false, onLater = { later++ })
        rule.onNodeWithTag(UpdateTags.LATER).performScrollTo().performClick()
        assertEquals(1, later)
    }

    @Test fun theStatesTheVendorDoesNotDrawKeepTheirExplanations() {
        show(AppLanguage.EN, download = DownloadUi.NeedsUnknownSources)
        rule.onNodeWithText("The phone must allow ARON to install its own update.").assertExists()
        rule.onNodeWithTag(UpdateTags.UNKNOWN_SOURCES).assertExists()
    }

    @Test fun noVendorNameAppearsOnThePage() {
        show(AppLanguage.EN, download = DownloadUi.Downloading(5))
        rule.onNodeWithText("Apsis", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
