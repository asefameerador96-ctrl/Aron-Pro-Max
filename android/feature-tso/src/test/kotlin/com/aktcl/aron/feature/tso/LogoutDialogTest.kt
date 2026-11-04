package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-TSO-MAN-37, screenshot 44: Log Out is the last drawer row and asks first: "Log Out!", "All app data on this phone will be
 * removed.", the green Cancel and the orange Log Out. Cancel logs nothing out; Log Out hands over to the host, which still refuses
 * while items are unsent and then wipes this user's data (core-system LogoutFlowTest and LogoutWipeUiPrefsTest prove those).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class LogoutDialogTest {
    @get:Rule val compose = createComposeRule()
    private var logouts = 0

    private fun show(language: AppLanguage) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                TsoLoginTheme(language, loggedOut = false) {
                    TsoApp(
                        HomeUser("Apsis", "tso1001", "TSO", false, false, false), "2026-10-29", BundleStatus.Ready("b", "2026-10-29"), "0.1.0 (1)", {}, { logouts++ },
                        screens = mapOf(TsoScreen.DASHBOARD to { _, _ -> Text("dashboard") }),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TsoShellTags.MENU).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoShellTags.row(TsoDrawerEntry.LOGOUT_ID), useUnmergedTree = true).performScrollTo().performClick(); compose.waitForIdle()
    }

    @Test fun theDialogSaysWhatTheWipeDoesAndCancelKeepsTheSession() {
        show(AppLanguage.EN)
        compose.onNodeWithTag(TsoShellTags.LOGOUT_DIALOG).assertExists()
        compose.onNodeWithText("Log Out!").assertExists()
        compose.onNodeWithText("All app data on this phone will be removed.").assertExists()
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithTag(TsoShellTags.LOGOUT_CANCEL).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(TsoShellTags.LOGOUT_DIALOG).assertDoesNotExist()
        assertEquals(0, logouts)
    }

    @Test fun logOutHandsOverToTheHostOnce() {
        show(AppLanguage.EN)
        compose.onNodeWithTag(TsoShellTags.LOGOUT_CONFIRM).performClick(); compose.waitForIdle()
        assertEquals(1, logouts)
        compose.onNodeWithTag(TsoShellTags.LOGOUT_DIALOG).assertDoesNotExist()
    }

    @Test fun banglaText() {
        show(AppLanguage.BN)
        compose.onNodeWithText("লগ আউট!").assertExists()
        compose.onNodeWithText("এই ফোন থেকে অ্যাপের সব তথ্য মুছে যাবে।").assertExists()
        compose.onNodeWithText("বাতিল").assertExists()
    }
}
