package com.aktcl.aron.feature.tso

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual X4b (integration of A1's frame with A3's screens): the Home button never takes the tap meant for a screen's action. A
 * bottom bar sits above the Home disc (screenshot 33: Set Plan over the house, the disc overlapping only its lower edge), and the
 * content of a frame ends above the disc (screenshots 31 and 43), so a form's last action never sits under it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class TsoFrameFabTest {
    @get:Rule val compose = createComposeRule()

    private var homes = 0
    private val nav = object : TsoNav {
        override fun open(screen: TsoScreen, arg: String?) = Unit
        override fun back() = Unit
        override fun home() { homes++ }
    }

    @Test fun theBottomBarActionSitsAboveTheHomeDisc() {
        var taps = 0
        compose.setContent {
            TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                TsoFrame("Select Outlets", nav, homeButton = true, bottomBar = { TsoGradientButton("Set Plan", { taps++ }, Modifier.testTag("bar")) }) {}
            }
        }
        compose.waitForIdle()
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val disc = compose.onNodeWithTag(TsoKitTags.HOME).getUnclippedBoundsInRoot()
        assertTrue("the bar's centre ${(bar.top + bar.bottom) / 2} is above the disc top ${disc.top}", (bar.top + bar.bottom) / 2 < disc.top)
        assertTrue("the disc overlaps at most 16 dp of the bar (${bar.bottom - disc.top})", bar.bottom - disc.top <= 16.dp + 0.5.dp)
        compose.onNodeWithTag("bar").performClick(); compose.waitForIdle()
        assertEquals(1, taps); assertEquals(0, homes)
        compose.onNodeWithTag(TsoKitTags.HOME).performClick(); compose.waitForIdle()
        assertEquals(1, homes)
    }

    @Test fun withoutTheHomeButtonTheBarKeepsItsUsualMargin() {
        compose.setContent {
            TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                TsoFrame("Leave", nav, homeButton = false, bottomBar = { TsoGradientButton("Apply", {}, Modifier.testTag("bar")) }) {}
            }
        }
        compose.waitForIdle()
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val bottom = compose.onRoot().getUnclippedBoundsInRoot().bottom
        assertTrue("16 dp under the bar, was ${bottom - bar.bottom}", (bottom - bar.bottom - 16.dp).value in -0.5f..0.5f)
        compose.onNodeWithTag(TsoKitTags.HOME).assertDoesNotExist()
    }

    @Test fun aScrollingFormEndsAboveTheHomeDisc() {
        var taps = 0
        compose.setContent {
            TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                TsoFrame("Create New Feedback", nav, homeButton = true) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("form")) {
                        Spacer(Modifier.height(900.dp)) // a long form; Save is its last item (screenshot 43)
                        TsoGradientButton("Save", { taps++ }, Modifier.testTag("save"))
                    }
                }
            }
        }
        compose.waitForIdle()
        val form = compose.onNodeWithTag("form").getUnclippedBoundsInRoot()
        val disc = compose.onNodeWithTag(TsoKitTags.HOME).getUnclippedBoundsInRoot()
        assertTrue("the content ends at ${form.bottom}, above the disc top ${disc.top}", form.bottom <= disc.top + 0.5.dp)
        // scrolled just into view (the least scroll, as a test or TalkBack does), Save is still not under the disc
        compose.onNodeWithTag("save").performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(1, taps); assertEquals(0, homes)
    }
}
