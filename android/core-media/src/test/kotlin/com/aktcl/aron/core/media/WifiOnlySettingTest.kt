package com.aktcl.aron.core.media

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-037: the switch, its persistence, and what it changes in the media job. The upload rule itself is MediaUploaderTest. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WifiOnlySettingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clean() { app.getSharedPreferences(WifiOnlySetting.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun unsetFollowsTheConfigDefaultAndTheRepsChoiceWins() {
        var cfg = true
        val s = WifiOnlySetting(app) { cfg }
        assertTrue(s.wifiOnly())
        cfg = false
        assertFalse(s.wifiOnly())
        s.set(true)
        assertTrue(s.wifiOnly())
        // A relaunch (a new instance over the same storage) keeps the choice.
        assertTrue(WifiOnlySetting(app) { false }.wifiOnly())
    }

    @Test fun theHintFollowsTheConfiguredFallback() {
        rule.setContent { com.aktcl.aron.core.ui.AronTheme(com.aktcl.aron.core.common.AppLanguage.EN) { WifiOnlyPhotosRow(true, {}, evidenceFallbackHours = 0) } }
        rule.onNodeWithText("Evidence photos also wait for Wi-Fi.").assertExists()
    }

    @Test fun turningItOffLetsQueuedPhotosGoOnMobileData() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val wm = WorkManager.getInstance(app)
        val s = WifiOnlySetting(app) { true }
        val scheduler = MediaWorkScheduler(wm, wifiOnly = s::wifiOnly, nowMs = { 0L })
        scheduler.requestUpload()
        fun types() = listOf(MediaWorkScheduler.WORK_WIFI, MediaWorkScheduler.WORK_ANY).flatMap { wm.getWorkInfosForUniqueWork(it).get() }
            .filter { it.state == WorkInfo.State.ENQUEUED }.map { it.constraints.requiredNetworkType }.toSet()
        assertEquals(setOf(NetworkType.UNMETERED), types())
        s.set(false); scheduler.requestUpload()
        assertEquals(setOf(NetworkType.UNMETERED, NetworkType.CONNECTED), types())
    }

    @Test @Config(qualifiers = "bn")
    fun theRowToggles() {
        val s = WifiOnlySetting(app) { true }
        rule.setContent {
            var on by remember { mutableStateOf(s.wifiOnly()) }
            com.aktcl.aron.core.ui.AronTheme(com.aktcl.aron.core.common.AppLanguage.BN) { WifiOnlyPhotosRow(on, { on = it; s.set(it) }, evidenceFallbackHours = 6) }
        }
        rule.onNodeWithText("শুধু ওয়াই-ফাইতে ছবি পাঠান").assertExists()
        rule.onNodeWithText("৬ ঘণ্টা পর মোবাইল ডেটায় যাবে", substring = true).assertExists() // from the config value
        rule.onNodeWithTag(WifiOnlyTags.SWITCH).assertIsOn().performClick().assertIsOff()
        assertFalse(s.wifiOnly())
    }
}
