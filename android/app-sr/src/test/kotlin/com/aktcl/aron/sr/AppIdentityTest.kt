package com.aktcl.aron.sr

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun roleAndApplicationIdAreFixed() {
        assertEquals("SR", BuildConfig.ARON_ROLE)
        assertEquals("com.aktcl.aron.sr", BuildConfig.APPLICATION_ID)
    }
}
