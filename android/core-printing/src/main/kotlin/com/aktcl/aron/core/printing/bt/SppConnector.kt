package com.aktcl.aron.core.printing.bt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** The ways to open an RFCOMM link to an SPP printer, in the order they are tried by default (D-PRINT-02). */
enum class SppMethod {
    /** `createRfcommSocketToServiceRecord(SPP)`: authenticated, SDP lookup of the serial-port record. */
    SECURE,
    /** `createInsecureRfcommSocketToServiceRecord(SPP)`: no link key check; some MP-58N firmware wants this. */
    INSECURE,
    /** RFCOMM channel 1 directly, no SDP: for printers whose SDP record is missing or wrong. */
    CHANNEL_1,
}

/** One blocking RFCOMM socket; [close] from another thread unblocks a pending [connect]. */
interface SppSocket {
    @Throws(IOException::class) fun connect()
    val input: InputStream
    val output: OutputStream
    fun close()
}

/** What happened to one connect try: [error] is null on success, else the exception's class and message. */
data class ConnectAttempt(val method: SppMethod, val error: String?, val ms: Long)

/** The link, or null, and every try with its exact error text (shown and exported by the diagnostics screen). */
class ConnectResult<S : SppSocket>(val socket: S?, val attempts: List<ConnectAttempt>)

/**
 * Opens an SPP link trying [SppMethod]s in turn (D-PRINT-02). Each try is bounded by [attemptTimeoutMs] and really
 * stops at the bound or on cancellation: `BluetoothSocket.connect()` ignores thread interrupts, so the socket is
 * closed to unblock it (before this, a `withTimeout` around the connect waited for the OS timeout anyway). Between
 * tries it pauses [pauseMs]: some stacks refuse a new RFCOMM connect right after a failed one. The method that worked
 * last for an address is tried first next time.
 */
class SppConnector(
    private val attemptTimeoutMs: Long = 8_000,
    private val pauseMs: Long = 600,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun <S : SppSocket> connect(
        address: String,
        methods: List<SppMethod> = SppMethod.entries,
        create: (SppMethod) -> S,
    ): ConnectResult<S> {
        val order = lastGood[address]?.takeIf { it in methods }?.let { listOf(it) + (methods - it) } ?: methods
        val attempts = ArrayList<ConnectAttempt>()
        for ((i, m) in order.withIndex()) {
            if (i > 0) delay(pauseMs)
            val start = nowMs()
            val s = try {
                create(m)
            } catch (e: Exception) {
                attempts.add(ConnectAttempt(m, describe(e), nowMs() - start)); continue
            }
            val error = try {
                val ok = withTimeoutOrNull(attemptTimeoutMs) { connectCancellable(s); true }
                if (ok == null) "timeout after ${attemptTimeoutMs} ms" else null // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            } catch (e: CancellationException) {
                try { s.close() } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                // IOException normally; some OEM stacks throw a RuntimeException from connect(): logged, never escapes
                describe(e)
            }
            attempts.add(ConnectAttempt(m, error, nowMs() - start))
            if (error == null) {
                lastGood[address] = m
                return ConnectResult(s, attempts)
            }
            lastGood.remove(address, m) // re-paired or new firmware: do not lead with a dead method next time
            try { s.close() } catch (_: Exception) {}
        }
        return ConnectResult(null, attempts)
    }

    /** [SppSocket.connect] that a cancellation (or timeout) ends by closing the socket. */
    private suspend fun connectCancellable(s: SppSocket) = coroutineScope {
        val done = AtomicBoolean(false)
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!done.get()) try { s.close() } catch (_: Exception) {} }
        }
        try {
            runInterruptible(ioContext) { s.connect() }
            done.set(true)
        } finally {
            closer.cancel()
        }
    }

    companion object {
        private val lastGood = ConcurrentHashMap<String, SppMethod>()

        /** Test hook: forget which method worked. */
        internal fun resetMemory() = lastGood.clear()

        fun describe(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"
    }
}
