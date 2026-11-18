package com.aktcl.aron.core.system.integrity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aktcl.aron.core.common.ActionGate
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IntegrityGateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var flags = DevFlags(false, false)
    private val dir = Files.createTempDirectory("gateui").toFile()
    private val clock = LabExemptionStoreTest.Clock()
    private val log = IntegrityEventLog(dir)
    private fun controller() = IntegrityGateController({ flags }, LabExemptionStore(dir, clock), log, dir, null, "1", clock::wallMs)

    @Test fun gatedShowsOnlyTheBlockingScreenInBanglaAndEnglishAndCheckAgainReopens() {
        flags = DevFlags(devOptions = true, adb = false)
        val c = controller()
        var composed = 0
        rule.setContent { IntegrityGate(c) { composed++; Text("APP-CONTENT", modifier = androidx.compose.ui.Modifier.testTag("app")) } }
        rule.onNodeWithTag(IntegrityGateTags.SCREEN).assertIsDisplayed()
        rule.onNodeWithText("ডেভেলপার অপশন চালু আছে", substring = true).assertIsDisplayed()
        rule.onAllNodesWithTag("app").assertCountEquals(0)
        assertEquals("the app UI is not composed behind the gate", 0, composed)
        // Still on: check again keeps the gate.
        rule.onNodeWithTag(IntegrityGateTags.CHECK_AGAIN).performClick()
        rule.onNodeWithTag(IntegrityGateTags.SCREEN).assertIsDisplayed()
        flags = DevFlags(false, false)
        rule.onNodeWithTag(IntegrityGateTags.CHECK_AGAIN).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(IntegrityGateTags.SCREEN).assertCountEquals(0)
        rule.onNodeWithTag("app").assertIsDisplayed()
        assertTrue(log.pending().isNotEmpty())
    }

    @Test fun theScreenTheRepWasOnIsKeptWhenTheGateLifts() {
        flags = DevFlags(false, false)
        val c = controller()
        rule.setContent { IntegrityGate(c) { var t by rememberSaveable { mutableStateOf("") }; BasicTextField(t, { t = it }, modifier = androidx.compose.ui.Modifier.testTag("field")) } }
        rule.onNodeWithTag("field").performTextInput("half-typed")
        flags = DevFlags(true, false)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) // to the background ...
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) // ... and back: the settings are re-read
        rule.onNodeWithTag(IntegrityGateTags.SCREEN).assertIsDisplayed()
        flags = DevFlags(false, false)
        rule.onNodeWithTag(IntegrityGateTags.CHECK_AGAIN).performClick()
        rule.onNodeWithText("half-typed").fetchSemanticsNode() // the screen state came back
    }

    @Test fun anOpenGateComposesTheContentAtOnce() {
        val c = controller()
        rule.setContent { IntegrityGate(c) { Text("APP-CONTENT") } }
        rule.onNodeWithText("APP-CONTENT").assertIsDisplayed()
    }

    @Test fun aSettingSwitchedOnWhileTheAppStaysResumedClosesTheGateAtOnce() {
        // Split screen: the app never leaves RESUMED while the rep opens Developer options in the other pane.
        val c = controller()
        rule.setContent { IntegrityGate(c) { Text("APP-CONTENT") } }
        rule.onNodeWithText("APP-CONTENT").assertIsDisplayed()
        flags = DevFlags(devOptions = true, adb = false)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        app.contentResolver.notifyChange(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), null)
        rule.waitForIdle()
        rule.onNodeWithTag(IntegrityGateTags.SCREEN).assertIsDisplayed()
        rule.onAllNodesWithTag("never").assertCountEquals(0)
    }

    @Test fun usbDebuggingSwitchedOnWhileResumedClosesTheGateToo() {
        val c = controller()
        rule.setContent { IntegrityGate(c) { Text("APP-CONTENT") } }
        flags = DevFlags(devOptions = false, adb = true)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        app.contentResolver.notifyChange(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ADB_ENABLED), null)
        rule.waitForIdle()
        rule.onNodeWithTag(IntegrityGateTags.SCREEN).assertIsDisplayed()
    }

    @Test fun theGateTitleIsInBothLanguages() {
        flags = DevFlags(true, false)
        rule.setContent { IntegrityGate(controller()) { Text("x") } }
        rule.onNodeWithText("ডেভেলপার অপশন চালু আছে / Developer options are on").assertIsDisplayed()
    }

    @Test fun wirelessDebuggingSwitchedOnWhileResumedClosesTheGateAndShowsItsOwnLine() {
        val c = controller()
        rule.setContent { IntegrityGate(c) { Text("APP-CONTENT") } }
        flags = DevFlags(devOptions = false, adb = false, adbWifi = true)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        app.contentResolver.notifyChange(android.provider.Settings.Global.getUriFor(AndroidDevFlagsReader.ADB_WIFI_ENABLED), null)
        rule.waitForIdle()
        rule.onNodeWithTag(IntegrityGateTags.SCREEN).assertIsDisplayed()
        rule.onNodeWithText("ওয়্যারলেস ডিবাগিং: চালু").assertIsDisplayed()
        rule.onNodeWithText("Wireless debugging: ON").performScrollTo().assertIsDisplayed()
    }

    @Test fun aWriterThatFindsTheGateClosedMakesTheScreenShowItAtOnceWithoutAnyObserverEvent() {
        val c = controller()
        IntegrityGates.refreshNow = {}
        ActionGate.check = { !c.check().blocked }
        ActionGate.onClosed = { IntegrityGates.refreshNow() }
        try {
            rule.setContent { IntegrityGate(c) { Text("APP-CONTENT") } }
            rule.onNodeWithText("APP-CONTENT").assertIsDisplayed()
            flags = DevFlags(true, false) // changed, and no settings event reaches the observer (the race window)
            val thrown = runCatching { ActionGate.requireOpen() }.exceptionOrNull()
            assertTrue(thrown is com.aktcl.aron.core.common.GateClosedException)
            rule.waitForIdle()
            rule.onNodeWithTag(IntegrityGateTags.SCREEN).assertIsDisplayed()
        } finally { ActionGate.check = { true }; ActionGate.onClosed = {} }
    }

    @Test fun syncNowIsOnTheGateScreenWhenSetAndRunsTheSync() {
        flags = DevFlags(true, false)
        var synced = 0
        IntegrityGates.syncNow = { synced++ }
        try {
            rule.setContent { IntegrityGate(controller()) { Text("x") } }
            rule.onNodeWithTag(IntegrityGateTags.SYNC_NOW).performScrollTo().performClick()
            assertEquals(1, synced)
        } finally { IntegrityGates.syncNow = null }
    }

    @Test fun noSyncButtonWhenNoSyncIsWired() {
        flags = DevFlags(true, false)
        IntegrityGates.syncNow = null
        rule.setContent { IntegrityGate(controller()) { Text("x") } }
        rule.onAllNodesWithTag(IntegrityGateTags.SYNC_NOW).assertCountEquals(0)
    }

    @Test @Config(sdk = [30])
    fun theRecentsThumbnailIsHiddenOnlyWhileTheGateIsUp() {
        flags = DevFlags(true, false)
        rule.setContent { IntegrityGate(controller()) { Text("x") } }
        rule.waitForIdle()
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        assertTrue("gate up: FLAG_SECURE below API 33", rule.activity.window.attributes.flags and secure != 0)
        flags = DevFlags(false, false)
        rule.onNodeWithTag(IntegrityGateTags.CHECK_AGAIN).performClick()
        rule.waitForIdle()
        assertEquals("gate lifted: cleared again", 0, rule.activity.window.attributes.flags and secure)
    }

    @Test @Config(sdk = [30])
    fun liftingTheGateNeverClearsTheSecureFlagOfAScreenThatShowsDeviceCodes() {
        flags = DevFlags(true, false)
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        rule.setContent { IntegrityGate(controller()) { Text("x") }; com.aktcl.aron.core.ui.SecureScreen() }
        rule.waitForIdle()
        assertTrue("gate up: secure", rule.activity.window.attributes.flags and secure != 0)
        flags = DevFlags(false, false)
        rule.onNodeWithTag(IntegrityGateTags.CHECK_AGAIN).performClick()
        rule.waitForIdle()
        assertTrue("gate lifted: the SecureScreen still holds the flag", rule.activity.window.attributes.flags and secure != 0)
    }
}
