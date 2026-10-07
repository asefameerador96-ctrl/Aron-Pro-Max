package com.aktcl.aron.core.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CameraCaptureHostTest {
    @Test fun aLateShotFromAnOldRequestNeverLandsInANewOne() = runTest {
        val host = CameraCaptureHost()
        val first = async { host.takePicture() }
        yield()
        val old = host.request.value!!
        host.cancel()
        assertNull(first.await())
        val second = async { host.takePicture() }
        yield()
        host.deliver(old, byteArrayOf(9)) // the cancelled request's camera callback fires late
        assertTrue(second.isActive)
        host.deliver(host.request.value!!, byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), second.await())
        assertNull(host.request.value)
    }

    @Test fun aSecondRequestWhileOneIsOpenIsBusyNotAFailure() = runTest {
        val host = CameraCaptureHost()
        val first = async { host.takePicture() }
        yield()
        try { host.takePicture(); fail() } catch (_: CameraBusyException) { }
        host.deliver(host.request.value!!, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), first.await())
    }

    @Test fun deliverToAnUnknownRequestIsIgnored() {
        val host = CameraCaptureHost()
        host.deliver(CompletableDeferred(), byteArrayOf(1))
        assertEquals(null, host.request.value)
    }
}
