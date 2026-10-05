package com.aktcl.aron.dpc

import org.junit.Assert.assertEquals
import org.junit.Test

class DpcPlaceholderTest {
    @Test
    fun componentClassNameIsStable() {
        assertEquals(AronDeviceAdminReceiver::class.java.name, AronDeviceAdminReceiver.CLASS_NAME)
    }
}
