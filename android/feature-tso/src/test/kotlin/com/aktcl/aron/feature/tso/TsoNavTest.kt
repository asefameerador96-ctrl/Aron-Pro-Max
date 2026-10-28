package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** a1-stubs: the screen list of the build contract, the back stack behind [TsoNav] and the [TsoFrame] back and Home buttons. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoNavTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theScreensAreTheContractListInOrder() {
        assertEquals(
            listOf(
                "DASHBOARD", "LOGIN_SUBMIT_STATUS", "NOTIFICATIONS", "SETTINGS", "CHANGE_PASSWORD", "SUPPORT_UPLOAD",
                "TICKET_DASHBOARD", "TICKET_LIST", "TICKET_QUICK", "TICKET_CREATE", "TICKET_DETAIL", "LEAVE", "FINAL_SUBMIT", "MY_TEAM", "RETAILER",
                "MY_VISIT_PLAN", "SET_PLAN", "TARGET_STATUS", "TARGET_DETAILS", "FEEDBACK_NEW", "FEEDBACK_LIST",
            ),
            TsoScreen.entries.map { it.name },
        )
    }

    @Test fun openPushesBackPopsHomeClearsAndTheSameTopIsNotStackedTwice() {
        val nav = TsoNavState()
        assertNull(nav.top)
        nav.open(TsoScreen.SETTINGS)
        nav.open(TsoScreen.SETTINGS) // a double tap
        nav.open(TsoScreen.CHANGE_PASSWORD)
        assertEquals(listOf(TsoRoute(TsoScreen.SETTINGS), TsoRoute(TsoScreen.CHANGE_PASSWORD)), nav.stack)
        nav.back()
        assertEquals(TsoRoute(TsoScreen.SETTINGS), nav.top)
        nav.open(TsoScreen.TICKET_DETAIL, "uuid-1")
        nav.open(TsoScreen.TICKET_DETAIL, "uuid-2") // another argument is another screen
        assertEquals(3, nav.stack.size)
        nav.home()
        assertNull(nav.top)
        nav.back() // back at the root does nothing
        assertNull(nav.top)
    }

    @Test fun theStackSurvivesSaveAndRestoreWithArguments() {
        val nav = TsoNavState()
        nav.open(TsoScreen.TARGET_STATUS)
        nav.open(TsoScreen.TARGET_DETAILS, "zone:11")
        nav.open(TsoScreen.TICKET_DETAIL, "")
        val saved = with(TsoNavState.Saver) { SaverScope { true }.save(nav) }!!
        val back = TsoNavState.Saver.restore(saved)!!
        assertEquals(nav.stack, back.stack)
        // an unknown name (a screen removed in a later build) is dropped, never a crash
        assertEquals(listOf(TsoRoute(TsoScreen.LEAVE)), TsoNavState.Saver.restore(arrayListOf("GONE", "LEAVE"))!!.stack)
    }

    @Test fun frameBackCallsNavBackAndTheHomeButtonCallsNavHome() {
        val calls = mutableListOf<String>()
        val nav = object : TsoNav {
            override fun open(screen: TsoScreen, arg: String?) { calls += "open" }
            override fun back() { calls += "back" }
            override fun home() { calls += "home" }
        }
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN) { TsoFrame("Target Status", nav, homeButton = true) { Text("body") } }
            }
        }
        compose.onNodeWithContentDescription(ctx.getString(com.aktcl.aron.core.ui.R.string.core_ui_back)).performClick()
        compose.onNodeWithTag(TsoKitTags.HOME).performClick()
        assertEquals(listOf("back", "home"), calls)
    }

    @Test fun noHomeButtonWhenTheScreenHasNone() {
        val nav = object : TsoNav {
            override fun open(screen: TsoScreen, arg: String?) = Unit
            override fun back() = Unit
            override fun home() = Unit
        }
        compose.setContent { AronTheme(AppLanguage.EN) { TsoFrame("All Tickets", nav, homeButton = false) { Text("body") } } }
        compose.onNodeWithTag(TsoKitTags.HOME).assertDoesNotExist()
    }
}
