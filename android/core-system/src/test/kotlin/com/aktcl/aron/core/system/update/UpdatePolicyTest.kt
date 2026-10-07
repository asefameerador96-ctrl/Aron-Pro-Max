package com.aktcl.aron.core.system.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val abis = listOf("arm64-v8a", "armeabi-v7a")
    private fun rel(code: Int, abi: String = "arm64-v8a") = ReleaseInfo("1.2.$code", code, abi, "a".repeat(64), 20_000_000, "https://cdn.example/aron-$code.apk", "c".repeat(64))
    private fun info(min: Int = 1, latest: ReleaseInfo? = rel(12), available: Boolean = true, policy: String = "prompt") =
        UpdateInfo(available, false, min, latest, policy, true)

    @Test fun aNewerReleaseIsOfferedAndSilentOnlyInSettings() {
        assertEquals(UpdateState.Available(rel(12), prompt = true, wifiOnly = true), UpdatePolicy.evaluate(11, info(), abis))
        assertEquals(false, (UpdatePolicy.evaluate(11, info(policy = "silent"), abis) as UpdateState.Available).prompt)
    }

    @Test fun anOlderOrEqualOrForeignAbiReleaseIsNotOffered() {
        assertEquals(UpdateState.Current, UpdatePolicy.evaluate(12, info(), abis))
        assertEquals(UpdateState.Current, UpdatePolicy.evaluate(13, info(), abis))
        assertEquals(UpdateState.Current, UpdatePolicy.evaluate(11, info(latest = rel(12, "x86_64")), abis))
        assertTrue(UpdatePolicy.evaluate(11, info(latest = rel(12, "universal")), abis) is UpdateState.Available)
    }

    @Test fun belowTheMinimumIsRequired() {
        assertEquals(UpdateState.Required(rel(12), true), UpdatePolicy.evaluate(10, info(min = 11), abis))
    }

    @Test fun aForcedMinimumBlocksANewDayButNotAnOpenOfflineDay() {
        assertEquals(DayGate.BLOCKED_UPDATE_REQUIRED, UpdatePolicy.dayGate(10, 11, dayOpen = false))
        assertEquals(DayGate.FINISH_OPEN_DAY_ONLY, UpdatePolicy.dayGate(10, 11, dayOpen = true))
        assertEquals(DayGate.BLOCKED_UPDATE_REQUIRED, UpdatePolicy.dayGate(10, 11, dayOpen = true, finishOfflineDayBeforeForce = false))
        assertEquals(DayGate.OPEN, UpdatePolicy.dayGate(11, 11, dayOpen = false))
    }

    @Test fun checkAtLoginAndAtMostEvery12Hours() {
        val h = 3_600_000L
        assertTrue(UpdatePolicy.shouldCheck(null, 0, atLogin = false))
        assertFalse(UpdatePolicy.shouldCheck(0, 11 * h, atLogin = false))
        assertTrue(UpdatePolicy.shouldCheck(0, 12 * h, atLogin = false))
        assertTrue(UpdatePolicy.shouldCheck(0, 1, atLogin = true))
        assertTrue(UpdatePolicy.shouldCheck(10 * h, 0, atLogin = false)) // the clock went back: check
    }

    @Test fun abiPreference() {
        assertEquals("arm64-v8a", UpdatePolicy.preferredAbi(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("armeabi-v7a", UpdatePolicy.preferredAbi(listOf("armeabi-v7a", "armeabi")))
        assertEquals("universal", UpdatePolicy.preferredAbi(listOf("x86_64")))
    }

    // ---- UpdateManager

    private class Memory : UpdateMemory {
        var at: Long? = null; var info: UpdateInfo? = null
        override fun lastCheckMs() = at
        override fun lastInfo() = info
        override fun save(info: UpdateInfo, atMs: Long) { this.info = info; at = atMs }
    }

    @Test fun offlineKeepsTheLastAnswerAndTheMinimumNeverDrops() = runTest {
        val mem = Memory()
        var answer: UpdateCheckResult = UpdateCheckResult.Ok(info(min = 11))
        var calls = 0
        var now = 0L
        val m = UpdateManager({ _, _, _ -> calls++; answer }, mem, "sr", 10, abis, { now })
        assertTrue(m.check(atLogin = true) is UpdateState.Required)
        answer = UpdateCheckResult.Unavailable("offline")
        assertTrue(m.check(atLogin = true) is UpdateState.Required)
        assertEquals(DayGate.BLOCKED_UPDATE_REQUIRED, m.dayGate(dayOpen = false))
        // A later answer with a lower minimum (stale edge cache) does not lift the gate.
        answer = UpdateCheckResult.Ok(info(min = 5))
        now += 13 * 3_600_000L
        m.check(atLogin = false)
        assertEquals(11, mem.info!!.minVersionCode)
        // Not due: no call.
        val before = calls
        m.check(atLogin = false)
        assertEquals(before, calls)
    }

    @Test fun a426FromTheServerCountsAsTooOldEvenWithoutAnAnswer() {
        val m = UpdateManager({ _, _, _ -> UpdateCheckResult.Unavailable("x") }, Memory(), "sr", 10, abis, { 0 })
        assertEquals(DayGate.OPEN, m.dayGate(dayOpen = false))
        assertEquals(DayGate.BLOCKED_UPDATE_REQUIRED, m.dayGate(dayOpen = false, serverSaidTooOld = true))
        assertEquals(DayGate.FINISH_OPEN_DAY_ONLY, m.dayGate(dayOpen = true, serverSaidTooOld = true))
    }
}
