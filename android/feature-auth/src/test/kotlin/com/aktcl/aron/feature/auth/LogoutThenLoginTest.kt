package com.aktcl.aron.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.session.LoginOutcome
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.SessionPhaseScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual A1 fix round (SH-17, D-A1-17): the TSO shell's logout followed by a login on the same phone. The shell is built as
 * MainActivity builds it: the login form in the logged-out phase's [SessionPhaseScope], the signed-in day (its retained state) in the
 * signed-in phase's. After a logout the next login's submit must reach the login port (it used to spin for ever: the activity's whole
 * ViewModel store was cleared while the login screen already held its ViewModel), the day's retained state must be gone, and the form
 * must not show the last user's name.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class LogoutThenLoginTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** What `rememberRetained` keeps for the signed-in day (a half-typed form, its client uuid). */
    class DayState : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    /** The session: null while logged out, the user id once signed in. */
    private var signedIn by mutableStateOf<String?>(null)
    private val submitted = mutableListOf<String>()
    private val forms = mutableListOf<LoginViewModel>()
    private val days = mutableListOf<DayState>()

    private fun shell() {
        compose.setContent {
            AronTheme(AppLanguage.EN, dark = true, tso = true) {
                val key = signedIn
                if (key == null) {
                    SessionPhaseScope(signedInKey = null) {
                        val vm = viewModel { LoginViewModel { username, _ -> submitted += username; LoginOutcome.InvalidCredentials } }
                        SideEffect { if (forms.lastOrNull() !== vm) forms += vm }
                        LoginScreen(vm, "ARON TSO", "1.0", {}, variant = LoginVariant.Tso)
                    }
                } else {
                    SessionPhaseScope(signedInKey = key) {
                        val day = viewModel(key = "tso-day-$key") { DayState() }
                        SideEffect { if (days.lastOrNull() !== day) days += day }
                        Text("day of $key")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun submit(username: String) {
        val form = forms.last()
        compose.runOnUiThread { form.onUsernameChange(username); form.onPasswordChange("secret") }
        compose.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun afterALogoutTheNextLoginReachesTheLoginPortWithAFreshFormAndNoRetainedDay() {
        shell()
        submit("tso1001")
        assertEquals(listOf("tso1001"), submitted)

        signedIn = "42" // the login let the user in
        compose.waitForIdle()
        compose.onNodeWithText("day of 42").assertExists()
        assertEquals(1, days.size)
        assertFalse(days[0].cleared)

        signedIn = null // the logout ended the session
        compose.waitForIdle()
        assertTrue("SH-17: the day's retained state is cleared", days[0].cleared)
        assertEquals("a fresh login form after the logout", 2, forms.size)
        assertEquals("the last user's name is not left in the form", "", forms.last().state.value.username)

        submit("tso2002")
        assertEquals("the submit reached the login port", listOf("tso1001", "tso2002"), submitted)
        assertFalse("no spinner left behind", forms.last().state.value.busy)
        assertEquals(LoginMessage.InvalidCredentials, forms.last().state.value.message)

        signedIn = "42" // the same user signs in again
        compose.waitForIdle()
        assertEquals(2, days.size)
        assertNotSame("a fresh day, never the cleared one", days[0], days[1])
        assertFalse(days[1].cleared)
    }

    @Test fun aSecondLogoutAndLoginWorksToo() {
        shell()
        repeat(3) { round ->
            submit("tso$round")
            signedIn = "4$round"
            compose.waitForIdle()
            signedIn = null
            compose.waitForIdle()
        }
        submit("tso-last")
        assertEquals(listOf("tso0", "tso1", "tso2", "tso-last"), submitted)
        assertTrue(days.all { it.cleared })
    }
}
