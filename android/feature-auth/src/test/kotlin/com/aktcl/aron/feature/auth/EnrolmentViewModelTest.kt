package com.aktcl.aron.feature.auth

import com.aktcl.aron.core.session.EnrolmentOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnrolmentViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun anEmptyEntryNeverCallsTheServer() {
        val sent = mutableListOf<String?>()
        val vm = EnrolmentViewModel({ sent += it; EnrolmentOutcome.Enrolled }, pending = false, refusedCode = null)
        vm.onInput("   ")
        vm.onSubmit()
        assertEquals(EnrolMessage.Empty, vm.state.value.message)
        assertTrue(sent.isEmpty())
    }

    @Test fun aTokenThatEnrolsLeadsToLoginAndIsClearedFromTheScreen() {
        val sent = mutableListOf<String?>()
        val vm = EnrolmentViewModel({ sent += it; EnrolmentOutcome.Enrolled }, pending = false, refusedCode = null)
        vm.onInput("Tk_token")
        vm.onSubmit()
        assertEquals(listOf<String?>("Tk_token"), sent)
        assertTrue(vm.state.value.enrolled)
        assertEquals("", vm.state.value.input)
    }

    @Test fun aStoredTokenIsRetriedWhenTheScreenOpensAndOfflineIsShownWithTryAgain() {
        val sent = mutableListOf<String?>()
        var answer: EnrolmentOutcome = EnrolmentOutcome.Waiting(offline = true)
        val vm = EnrolmentViewModel({ sent += it; answer }, pending = true, refusedCode = null)
        assertEquals(listOf<String?>(null), sent) // the QR path: retried at once, no entry needed
        assertEquals(EnrolMessage.Waiting(offline = true), vm.state.value.message)
        assertTrue(vm.state.value.pending)
        answer = EnrolmentOutcome.Enrolled
        vm.onRetry()
        assertEquals(listOf<String?>(null, null), sent)
        assertTrue(vm.state.value.enrolled)
    }

    @Test fun aRefusalIsShownWithItsCodeAndAnUnreadableEntryKeepsTheTextForCorrection() {
        val vm = EnrolmentViewModel({ if (it == "typo") EnrolmentOutcome.Unreadable("token_malformed") else EnrolmentOutcome.Refused("ERR_ENROLMENT_TOKEN_EXPIRED") }, pending = false, refusedCode = null)
        vm.onInput("typo")
        vm.onSubmit()
        assertEquals(EnrolMessage.Unreadable, vm.state.value.message)
        assertEquals("typo", vm.state.value.input)
        vm.onScanned("{qr}")
        assertEquals(EnrolMessage.Refused("ERR_ENROLMENT_TOKEN_EXPIRED"), vm.state.value.message)
        assertFalse(vm.state.value.pending)
        assertFalse(vm.state.value.enrolled)
    }

    @Test fun anEarlierRefusalIsShownOnOpenWithoutACall() {
        var calls = 0
        val vm = EnrolmentViewModel({ calls++; EnrolmentOutcome.Enrolled }, pending = false, refusedCode = "ERR_ENROLMENT_TOKEN_EXHAUSTED")
        assertEquals(EnrolMessage.Refused("ERR_ENROLMENT_TOKEN_EXHAUSTED"), vm.state.value.message)
        assertEquals(0, calls)
        vm.onScanned(null)
        assertEquals(0, calls)
    }

    @Test fun aTokenForAnotherServerIsNotKeptOnScreen() {
        val vm = EnrolmentViewModel({ EnrolmentOutcome.Unreadable("other_server") }, pending = false, refusedCode = null)
        vm.onInput("{\"other\":\"qr\"}")
        vm.onSubmit()
        assertEquals(EnrolMessage.Unreadable, vm.state.value.message)
        assertEquals("", vm.state.value.input)
    }
}
