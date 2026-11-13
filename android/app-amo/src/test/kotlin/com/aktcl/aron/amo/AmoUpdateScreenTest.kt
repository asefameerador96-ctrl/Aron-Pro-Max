package com.aktcl.aron.amo

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.ui.AppLocale
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.system.update.DownloadUi
import com.aktcl.aron.core.system.update.NetworkStatus
import com.aktcl.aron.core.system.update.ReleaseInfo
import com.aktcl.aron.core.system.update.UpdateTags
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-PAR-AMO-03: the back arrow of an optional update does what the removed "Later" button did; a mandatory update has no way round. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class AmoUpdateScreenTest {
    @get:Rule val compose = hostComposeRule()
    private val release = ReleaseInfo("1.0.4", 104, "arm64-v8a", "x", 1_000_000, "https://example.invalid/a.apk", "y")

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun show(required: Boolean, download: DownloadUi = DownloadUi.Idle, network: NetworkStatus = NetworkStatus.WIFI, later: () -> Unit = {}, onDownload: () -> Unit = {}) {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent {
                CompositionLocalProvider(LocalContext provides ctx) {
                    AronTheme(AppLanguage.EN) { AmoUpdateScreen(release, required, network, download, onDownload, {}, {}, later, currentVersion = "1.0.2") }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun backArrowOfAnOptionalUpdateCallsLater() {
        var later = 0
        show(required = false, later = { later++ })
        compose.onNodeWithTag(AmoUpdateTags.NETWORK).assertIsDisplayed()
        compose.onNodeWithTag(AmoUpdateTags.BACK).performClick()
        assertEquals(1, later)
    }

    @Test fun mandatoryUpdateHasNoBackArrowNoLaterAndNoNetworkRowToLeaveBy() {
        show(required = true)
        compose.onAllNodesWithTag(AmoUpdateTags.BACK).assertCountEquals(0)
        compose.onAllNodesWithTag(UpdateTags.LATER).assertCountEquals(0)
        compose.onNodeWithTag(AmoUpdateTags.MANDATORY).assertExists()
    }

    @Test fun downloadButtonStartsTheDownloadOnBothScreens() {
        var started = 0
        show(required = true, onDownload = { started++ })
        compose.onNodeWithTag(UpdateTags.DOWNLOAD).performClick()
        assertEquals(1, started)
    }

    @Test fun downloadingShowsTheProcessingScreenWithoutAnyWayOut() {
        show(required = true, download = DownloadUi.Downloading(46))
        compose.onNodeWithTag(AmoUpdateTags.PROGRESS).assertExists()
        compose.onNodeWithTag(AmoUpdateTags.STEPS).assertExists()
        compose.onAllNodesWithTag(AmoUpdateTags.BACK).assertCountEquals(0)
    }
}
