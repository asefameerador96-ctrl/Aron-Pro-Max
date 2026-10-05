package com.aktcl.aron.tso

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun roleAndApplicationIdAreFixed() {
        assertEquals("TSO", BuildConfig.ARON_ROLE)
        assertEquals("com.aktcl.aron.tso", BuildConfig.APPLICATION_ID)
    }
}
