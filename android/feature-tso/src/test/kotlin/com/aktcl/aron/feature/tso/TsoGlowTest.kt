package com.aktcl.aron.feature.tso

import com.aktcl.aron.core.ui.aron.NightPageGlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pages that carry the owner's bright-blue bottom dome (screenshots 8, 39 to 42); the hosts and the goldens read the same table. */
class TsoGlowTest {
    @Test fun targetStatusDetailsAndLoginStatusDrawTheBottomDome() {
        for (s in listOf(TsoScreen.TARGET_STATUS, TsoScreen.TARGET_DETAILS, TsoScreen.LOGIN_SUBMIT_STATUS)) assertEquals("$s", NightPageGlow.Bottom, TsoGlow.of(s))
    }
    @Test fun otherScreensDrawNoneHere() {
        assertEquals(NightPageGlow.None, TsoGlow.of(TsoScreen.DASHBOARD))
        assertEquals(NightPageGlow.None, TsoGlow.of(TsoScreen.MY_TEAM))
    }
}
