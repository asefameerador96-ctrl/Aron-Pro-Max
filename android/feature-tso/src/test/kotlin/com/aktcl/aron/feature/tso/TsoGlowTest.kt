package com.aktcl.aron.feature.tso

import com.aktcl.aron.core.ui.aron.NightPageGlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pages that carry the owner's bright-blue bottom dome (screenshots 8, 39, 40; Target Details 41, 42 has none); the hosts and the goldens read the same table. */
class TsoGlowTest {
    @Test fun targetStatusAndLoginStatusDrawTheBottomDome() {
        for (s in listOf(TsoScreen.TARGET_STATUS, TsoScreen.LOGIN_SUBMIT_STATUS)) assertEquals("$s", NightPageGlow.Bottom, TsoGlow.of(s))
    }
    @Test fun targetDetailsHasNoDomeAsInTheOwnersScreenshots() = assertEquals(NightPageGlow.None, TsoGlow.of(TsoScreen.TARGET_DETAILS))
    @Test fun otherScreensDrawNoneHere() {
        for (s in TsoScreen.entries - setOf(TsoScreen.TARGET_STATUS, TsoScreen.LOGIN_SUBMIT_STATUS)) assertEquals("$s", NightPageGlow.None, TsoGlow.of(s))
    }
}
