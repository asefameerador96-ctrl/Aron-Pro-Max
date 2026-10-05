package com.aktcl.aron.amo

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun roleAndApplicationIdAreFixed() {
        assertEquals("AMO", BuildConfig.ARON_ROLE)
        assertEquals("com.aktcl.aron.amo", BuildConfig.APPLICATION_ID)
    }
}
