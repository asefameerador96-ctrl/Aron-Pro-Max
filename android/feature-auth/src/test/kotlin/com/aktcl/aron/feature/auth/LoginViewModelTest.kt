package com.aktcl.aron.feature.auth

import com.aktcl.aron.core.session.LoginOutcome
import com.aktcl.aron.core.session.OfflineRefusal
import com.aktcl.aron.core.session.UnlockMode
import com.aktcl.aron.core.session.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val profile = UserProfile(1001, "sr334001", "Testing Banani", "SR", null, "bn", "v", 0)

    @Test
    fun emptyFieldsNeverCallLogin() {
        var calls = 0
        val vm = LoginViewModel { _, _ -> calls++; LoginOutcome.InvalidCredentials }
        vm.onSubmit()
        assertEquals(LoginMessage.EmptyFields, vm.state.value.message)
        vm.onUsernameChange("  ")
        vm.onPasswordChange("x")
        vm.onSubmit()
        assertEquals(0, calls)
    }

    @Test
    fun bengaliDigitsInTheUsernameAreNormalised() {
        var sent = ""
        val vm = LoginViewModel { u, _ -> sent = u; LoginOutcome.LoggedIn(profile, UnlockMode.OFFLINE) }
        vm.onUsernameChange(" sr৩৩৪০০১ ")
        vm.onPasswordChange("pw")
        vm.onSubmit()
        assertEquals("sr334001", sent)
        assertEquals("", vm.state.value.password)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun outcomesMapToMessages() {
        val m = LoginViewModel.Companion::messageFor
        assertEquals(LoginMessage.InvalidCredentials, m(LoginOutcome.InvalidCredentials))
        assertEquals(LoginMessage.Locked(15), m(LoginOutcome.Refused("ERR_AUTH_ACCOUNT_LOCKED", 900)))
        assertEquals(LoginMessage.Locked(1), m(LoginOutcome.Refused("ERR_AUTH_ACCOUNT_LOCKED", 1)))
        assertEquals(LoginMessage.DeviceNotEnrolled, m(LoginOutcome.Refused("ERR_DEVICE_NOT_ENROLLED", null)))
        assertEquals(LoginMessage.DeviceBlocked, m(LoginOutcome.Refused("ERR_DEVICE_REVOKED", null)))
        assertEquals(LoginMessage.UpdateRequired, m(LoginOutcome.Refused("ERR_APP_VERSION_UNSUPPORTED", null)))
        assertEquals(LoginMessage.Refused("ERR_SOMETHING_NEW"), m(LoginOutcome.Refused("ERR_SOMETHING_NEW", null)))
        assertEquals(LoginMessage.OfflineNeverOnline, m(LoginOutcome.OfflineUnavailable(OfflineRefusal.NEVER_ONLINE_ON_THIS_PHONE)))
        assertEquals(LoginMessage.OfflineCooldown(5), m(LoginOutcome.OfflineUnavailable(OfflineRefusal.COOLDOWN, 5)))
        assertEquals(null, m(LoginOutcome.LoggedIn(profile, UnlockMode.ONLINE)))
    }

    @Test
    fun aCrashingUseCaseNeverLeavesTheScreenBusy() {
        val vm = LoginViewModel { _, _ -> error("boom") }
        vm.onUsernameChange("sr334001"); vm.onPasswordChange("pw"); vm.onSubmit()
        assertFalse(vm.state.value.busy)
        assertEquals(LoginMessage.Refused(null), vm.state.value.message)
    }
}
