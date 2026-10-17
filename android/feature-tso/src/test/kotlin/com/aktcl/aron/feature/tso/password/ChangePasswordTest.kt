package com.aktcl.aron.feature.tso.password

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangePasswordTest {
    private class Api(var next: CpOutcome = CpOutcome.Ok) : ChangePasswordApi { val calls = mutableListOf<Pair<String, String>>(); override suspend fun change(current: String, new: String): CpOutcome { calls += current to new; return next } }

    @Test fun validation() {
        assertTrue(validatePasswordChange("old-pass", "new-pass-1", "new-pass-1").isEmpty())
        assertEquals(listOf(CpError.CURRENT_EMPTY), validatePasswordChange("", "new-pass-1", "new-pass-1"))
        assertEquals(listOf(CpError.NEW_SHORT, CpError.MISMATCH), validatePasswordChange("a", "short", "other"))
        assertEquals(listOf(CpError.NEW_SAME), validatePasswordChange("same-pass-1", "same-pass-1", "same-pass-1"))
        assertEquals(listOf(CpError.NEW_LONG), validatePasswordChange("a", "x".repeat(129), "x".repeat(129)))
    }

    @Test fun invalidInputNeverCallsTheServer() = runBlocking {
        val api = Api(); val s = ChangePasswordState(api); s.setCurrent("a"); s.setNew("short"); s.setConfirm("short"); s.submit()
        assertTrue(s.ui.value.phase is CpPhase.Invalid); assertTrue(api.calls.isEmpty())
    }

    @Test fun successDropsEverySecretFromMemoryAndIsOnce() = runBlocking {
        val api = Api(); val s = ChangePasswordState(api)
        s.setCurrent("old-pass-1"); s.setNew("new-pass-22"); s.setConfirm("new-pass-22"); s.submit(); s.submit()
        assertEquals(1, api.calls.size); assertEquals("old-pass-1" to "new-pass-22", api.calls[0])
        val u = s.ui.value
        assertEquals(CpPhase.Done, u.phase); assertEquals("", u.current); assertEquals("", u.new); assertEquals("", u.confirm)
    }

    @Test fun refusalsKeepTheFormForARetry() = runBlocking {
        for (o in listOf(CpOutcome.WrongCurrent, CpOutcome.Policy, CpOutcome.RateLimited, CpOutcome.Offline, CpOutcome.Failed)) {
            val api = Api(o); val s = ChangePasswordState(api)
            s.setCurrent("old-pass-1"); s.setNew("new-pass-22"); s.setConfirm("new-pass-22"); s.submit()
            assertEquals(CpPhase.Refused(o), s.ui.value.phase); assertEquals("new-pass-22", s.ui.value.new)
        }
    }

    @Test fun theStateNeverPrintsThePasswords() {
        val s = ChangePasswordState(Api()); s.setCurrent("secret-current"); s.setNew("secret-new-1")
        assertFalse(s.ui.value.toString().contains("secret"))
    }
}
