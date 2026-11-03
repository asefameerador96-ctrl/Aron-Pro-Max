package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Screenshot 7, By Channel STD ▸ (conformance row 7, D-TSO-MAN-41): a tap on the title row switches the card to By Channel Successful
 * Call and a second tap back; the chosen mode is the host's (the app keeps it across a relaunch: TsoApiTest), so a card drawn again
 * with the stored mode shows the same side.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TsoChannelToggleTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val std = ChannelCard("2026-10-29T08:30:00Z", listOf(ChannelSlice("Astha Channel", 100)), 100)
    private val calls = ChannelCard("2026-10-29T08:30:00Z", listOf(ChannelSlice("Astha Channel", 3), ChannelSlice("Other", 1)), 4)
    private val state = TsoDashboardState(channelStd = CardLoad(std), channelCalls = CardLoad(calls))

    private fun show(initial: ChannelMode, onMode: (ChannelMode) -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            var mode by mutableStateOf(initial)
            CompositionLocalProvider(LocalContext provides ctx) {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                    TsoDashboardContent(state, false, mode, {}, { mode = if (mode == ChannelMode.STD) ChannelMode.CALLS else ChannelMode.STD; onMode(mode) }, {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun description() = compose.onNodeWithTag(TsoDashTags.CHANNEL_SWITCH).fetchSemanticsNode().config
        .first { it.key.name == "ContentDescription" }.value.toString().removePrefix("[")

    @Test fun theArrowSwitchesStdToCallsAndBack() {
        val seen = mutableListOf<ChannelMode>()
        show(ChannelMode.STD) { seen += it }
        assert(description().startsWith("By Channel STD")) { description() }
        compose.onNodeWithTag(TsoDashTags.CHANNEL_SWITCH).performClick(); compose.waitForIdle()
        assert(description().startsWith("By Channel Successful Call")) { description() }
        compose.onNodeWithTag(TsoDashTags.CHANNEL_SWITCH).performClick(); compose.waitForIdle()
        assert(description().startsWith("By Channel STD")) { description() }
        assertEquals(listOf(ChannelMode.CALLS, ChannelMode.STD), seen)
    }

    @Test fun aCardDrawnAgainWithTheStoredModeShowsThatSide() {
        show(ChannelMode.CALLS) {}
        assert(description().startsWith("By Channel Successful Call")) { description() }
    }
}
