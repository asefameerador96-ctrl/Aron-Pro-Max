package com.aktcl.aron.core.sync.shell

import com.aktcl.aron.core.system.update.DayGate
import com.aktcl.aron.core.system.update.ReleaseInfo
import com.aktcl.aron.core.system.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shell's updater screen rule (F-SYS-020, D24-22): a required update blocks a new day only, never an open one. */
class UpdateScreenTest {
    private val r = ReleaseInfo("1.2.0", 12, "arm64-v8a", "a".repeat(64), 4_000_000, "https://x/aron.apk", "b".repeat(64))

    @Test
    fun aRequiredUpdateBlocksANewDayButOnlyOffersItselfDuringAnOpenDay() {
        val required = UpdateState.Required(r, wifiOnly = true)
        assertEquals(UpdateScreen.Prompt(r, required = true), updateScreen(required, null, DayGate.BLOCKED_UPDATE_REQUIRED))
        assertEquals(UpdateScreen.Prompt(r, required = false), updateScreen(required, null, DayGate.FINISH_OPEN_DAY_ONLY))
        assertEquals(UpdateScreen.None, updateScreen(required, 12, DayGate.FINISH_OPEN_DAY_ONLY)) // "Later" during the open day
    }

    @Test
    fun nothingToInstallBelowTheMinimumIsTheDayBlock() {
        assertEquals(UpdateScreen.Blocked, updateScreen(UpdateState.Required(null, true), null, DayGate.BLOCKED_UPDATE_REQUIRED))
        assertEquals(UpdateScreen.Blocked, updateScreen(UpdateState.Current, null, DayGate.BLOCKED_UPDATE_REQUIRED)) // a 426 from the server
    }

    @Test
    fun anOptionalReleasePromptsUnlessSilentOrPutOff() {
        assertEquals(UpdateScreen.Prompt(r, required = false), updateScreen(UpdateState.Available(r, prompt = true, wifiOnly = true), null, DayGate.OPEN))
        assertEquals(UpdateScreen.None, updateScreen(UpdateState.Available(r, prompt = false, wifiOnly = true), null, DayGate.OPEN))
        assertEquals(UpdateScreen.None, updateScreen(UpdateState.Available(r, prompt = true, wifiOnly = true), 12, DayGate.OPEN))
        assertEquals(UpdateScreen.None, updateScreen(UpdateState.Current, null, DayGate.OPEN))
    }

    /** Settings > App update (checker): a silent or put-off release is shown on the rep's tap; nothing when current. */
    @Test
    fun theSettingsRowShowsAnyAvailableRelease() {
        assertEquals(UpdateScreen.Prompt(r, required = false), updateScreen(UpdateState.Available(r, prompt = false, wifiOnly = true), SHOW_ANY, DayGate.OPEN))
        assertEquals(UpdateScreen.Prompt(r, required = false), updateScreen(UpdateState.Available(r, prompt = true, wifiOnly = true), SHOW_ANY, DayGate.OPEN))
        assertEquals(UpdateScreen.None, updateScreen(UpdateState.Current, SHOW_ANY, DayGate.OPEN))
        assertEquals(UpdateScreen.Prompt(r, required = true), updateScreen(UpdateState.Required(r, true), SHOW_ANY, DayGate.BLOCKED_UPDATE_REQUIRED))
    }
}
