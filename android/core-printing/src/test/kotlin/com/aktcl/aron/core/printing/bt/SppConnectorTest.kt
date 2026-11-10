package com.aktcl.aron.core.printing.bt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * D-PRINT-02: the SPP connect tries secure, insecure, channel 1; every error is kept; a stuck connect really ends.
 *
 * Deterministic (D-QA-CI-07): the timeouts and pauses run on the test scheduler's virtual time, never on the wall clock, and
 * the connector's IO context is [TestIo]: a socket that connects or fails runs inline on the test thread (no thread-start
 * stall can make a 300 ms attempt time out on a loaded runner); only a socket that hangs gets a real thread, which is what a
 * blocked BluetoothSocket.connect() needs, and its timeout then fires at once in virtual time. Assertions are unchanged.
 */
class SppConnectorTest {
    /** Inline for ordinary sockets; a real thread while [onThread] is set (the hanging socket's create sets it). */
    private class TestIo : CoroutineDispatcher() {
        @Volatile var onThread = false
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "spp-test-io").apply { isDaemon = true } }
        override fun isDispatchNeeded(context: CoroutineContext) = onThread
        override fun dispatch(context: CoroutineContext, block: Runnable) = pool.execute(block)
        fun close() { pool.shutdownNow() }
    }

    private val io = TestIo()

    /** A socket whose connect fails with [error], succeeds, or (hang) blocks until closed, like BluetoothSocket. */
    private class FakeSocket(val method: SppMethod, val error: String? = null, val hang: Boolean = false) : SppSocket {
        val released = CountDownLatch(1)
        @Volatile var closed = false
        override fun connect() {
            if (hang) {
                released.await(30, TimeUnit.SECONDS)
                throw IOException("socket closed")
            }
            if (error != null) throw IOException(error)
        }
        override val input = ByteArrayInputStream(ByteArray(0))
        override val output = ByteArrayOutputStream()
        override fun close() { closed = true; released.countDown() }
    }

    @Before fun forget() = SppConnector.resetMemory()

    @After fun stopThreads() = io.close()

    private val connector = SppConnector(attemptTimeoutMs = 300, pauseMs = 1, ioContext = io)

    @Test fun secureRefusedThenInsecureConnectsAndTheErrorTextIsKept() = runTest {
        val made = ArrayList<FakeSocket>()
        val r = connector.connect("AA:BB:CC:DD:EE:01") { m ->
            FakeSocket(m, error = if (m == SppMethod.SECURE) "read failed, socket might closed or timeout, read ret: -1" else null).also { made.add(it) }
        }
        assertNotNull(r.socket)
        assertSame(made[1], r.socket)
        assertEquals(listOf(SppMethod.SECURE, SppMethod.INSECURE), r.attempts.map { it.method })
        assertEquals("IOException: read failed, socket might closed or timeout, read ret: -1", r.attempts[0].error)
        assertNull(r.attempts[1].error)
        assertTrue("the failed secure socket is closed", made[0].closed)
        assertTrue("the live socket stays open", !made[1].closed)
    }

    @Test fun allThreeFailEachWithItsOwnError() = runTest {
        val r = connector.connect("AA:BB:CC:DD:EE:02") { m -> FakeSocket(m, error = "fail ${m.name}") }
        assertNull(r.socket)
        assertEquals(SppMethod.entries, r.attempts.map { it.method })
        assertEquals(SppMethod.entries.map { "IOException: fail ${it.name}" }, r.attempts.map { it.error })
    }

    @Test fun channelOneIsTheLastResortForABrokenSdpRecord() = runTest {
        val r = connector.connect("AA:BB:CC:DD:EE:03") { m -> FakeSocket(m, error = if (m != SppMethod.CHANNEL_1) "Service discovery failed" else null) }
        assertEquals(SppMethod.CHANNEL_1, (r.socket as FakeSocket).method)
    }

    @Test fun aSocketThatCannotBeCreatedIsLoggedAndSkipped() = runTest {
        val r = connector.connect("AA:BB:CC:DD:EE:04") { m ->
            if (m == SppMethod.SECURE) throw SecurityException("Need BLUETOOTH_CONNECT permission") else FakeSocket(m)
        }
        assertEquals("SecurityException: Need BLUETOOTH_CONNECT permission", r.attempts[0].error)
        assertEquals(SppMethod.INSECURE, (r.socket as FakeSocket).method)
    }

    /** Before this fix a timeout around BluetoothSocket.connect() waited for the OS (it ignores interrupts). */
    @Test fun aHangingConnectIsClosedAtTheTimeoutAndTheNextMethodIsTried() = runTest {
        val made = ArrayList<FakeSocket>()
        val t0 = System.nanoTime()
        val r = connector.connect("AA:BB:CC:DD:EE:05") { m -> FakeSocket(m, hang = m == SppMethod.SECURE).also { made.add(it); io.onThread = it.hang } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000)
        assertTrue(made[0].closed)
        assertEquals("timeout after 300 ms", r.attempts[0].error)
        assertEquals(SppMethod.INSECURE, (r.socket as FakeSocket).method)
    }

    @Test fun cancellingTheCallerClosesTheSocketAtOnce() = runTest {
        val s = FakeSocket(SppMethod.SECURE, hang = true)
        val slow = SppConnector(attemptTimeoutMs = 60_000, pauseMs = 1, ioContext = io)
        io.onThread = true
        val t0 = System.nanoTime()
        val r = withTimeoutOrNull(200) { slow.connect("AA:BB:CC:DD:EE:06", listOf(SppMethod.SECURE)) { s } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNull(r)
        assertTrue(s.closed)
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test fun anOemRuntimeExceptionFromConnectIsLoggedNotThrown() = runTest {
        val r = connector.connect("AA:BB:CC:DD:EE:09") { m ->
            object : SppSocket by FakeSocket(m) {
                override fun connect() { if (m == SppMethod.SECURE) throw NullPointerException("mSocket is null") }
            }
        }
        assertEquals("NullPointerException: mSocket is null", r.attempts[0].error)
        assertEquals(SppMethod.INSECURE, r.attempts.last().method)
        assertNotNull(r.socket)
    }

    @Test fun aRememberedMethodThatFailsIsForgotten() = runTest {
        val addr = "AA:BB:CC:DD:EE:10"
        connector.connect(addr) { m -> FakeSocket(m, error = if (m != SppMethod.CHANNEL_1) "no" else null) }
        // re-paired: channel 1 now fails, secure works
        val r = connector.connect(addr) { m -> FakeSocket(m, error = if (m == SppMethod.CHANNEL_1) "dead" else null) }
        assertEquals(listOf(SppMethod.CHANNEL_1, SppMethod.SECURE), r.attempts.map { it.method })
        val again = connector.connect(addr) { m -> FakeSocket(m) }
        assertEquals(listOf(SppMethod.SECURE), again.attempts.map { it.method })
    }

    @Test fun theMethodThatWorkedIsTriedFirstNextTime() = runTest {
        val addr = "AA:BB:CC:DD:EE:07"
        connector.connect(addr) { m -> FakeSocket(m, error = if (m != SppMethod.INSECURE) "no" else null) }
        val r = connector.connect(addr) { m -> FakeSocket(m) }
        assertEquals(listOf(SppMethod.INSECURE), r.attempts.map { it.method })
        // another printer still starts with secure
        val other = connector.connect("AA:BB:CC:DD:EE:08") { m -> FakeSocket(m) }
        assertEquals(listOf(SppMethod.SECURE), other.attempts.map { it.method })
    }
}
