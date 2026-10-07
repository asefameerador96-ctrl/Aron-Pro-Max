package com.aktcl.aron.core.printing.bt

import com.aktcl.aron.core.printing.escpos.EscPos
import com.aktcl.aron.core.printing.raster.MonoBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** The paired printer the phone remembers (one per phone, shared by the SR, AMO and TSO apps' own copies). */
data class SavedPrinter(val address: String, val name: String)

/** Where the paired printer is kept. */
interface SavedPrinterStore {
    fun load(): SavedPrinter?
    fun save(printer: SavedPrinter?)
}

/** What the printer icon and banner show (F-SR-013). */
sealed interface PrinterState {
    /** No printer saved yet: the icon opens pairing. */
    data object NoPrinter : PrinterState
    /** Saved but not linked (switched off, out of range, or released when idle): red slashed icon. */
    data object Off : PrinterState
    data object Connecting : PrinterState
    /** Linked and ready: green icon; Print is enabled. */
    data object Connected : PrinterState
    data class Printing(val jobId: String, val percent: Int) : PrinterState
    /** The printer reported an empty roll. */
    data object PaperOut : PrinterState
}

/** One document to print. [id] makes printing idempotent: a job already printed is never printed again. */
class PrintJob(val id: String, val bitmap: MonoBitmap)

enum class PrintFailure { NO_PRINTER, UNREACHABLE, DISCONNECTED, PAPER_OUT }

sealed interface PrintOutcome {
    data object Printed : PrintOutcome
    /** The same job id printed earlier; nothing was sent. */
    data object AlreadyPrinted : PrintOutcome
    data class Failed(val reason: PrintFailure) : PrintOutcome
}

/**
 * Printer speed and buffer model used to pace the data (N-019). The MP-58N prints about 50 to 70 mm/s
 * (400 to 560 dot rows); [rowsPerSecond] is set below that so the printer's receive buffer never overflows,
 * which is what produces garbage on cheap printers. Bytes are written in [chunkBytes] pieces only while the
 * estimated unprinted backlog stays under [bufferBytes].
 */
data class PrintPacing(
    val rowsPerSecond: Int = 300,
    val bufferBytes: Int = 3072,
    val chunkBytes: Int = 512,
    val bandRows: Int = 24,
    val statusTimeoutMs: Long = 400,
    val writeTimeoutMs: Long = 8_000,
    val connectTimeoutMs: Long = 12_000,
) {
    /** Values a bad configuration could give are clamped, so pacing never divides by zero or stalls. */
    internal val safeRowsPerSecond get() = rowsPerSecond.coerceIn(20, 2_000)
    internal val safeBufferBytes get() = bufferBytes.coerceIn(256, 1 shl 20)
    internal val safeChunkBytes get() = chunkBytes.coerceIn(16, safeBufferBytes)
    internal val safeBandRows get() = bandRows.coerceIn(1, 255)
}

/**
 * Owns the link to the saved printer (N-019, F-SR-013): connects on demand, keeps the link while a screen holds
 * it, releases it after [idleDisconnectMs] (cfg.print.disconnect_idle_s, 120 s) otherwise, notices a printer
 * switched off at once (the reader sees the link drop; no polling), reconnects with a short backoff while a
 * screen still holds it, and prints jobs one at a time with paced raster data.
 *
 * Printing never throws and never blocks a sale: the caller commits first and treats [PrintOutcome.Failed] as
 * "print later". A failed job retried with the same id prints the whole document again from the top, once.
 */
class PrinterManager(
    private val factory: PrinterTransportFactory,
    private val store: SavedPrinterStore,
    private val scope: CoroutineScope,
    private val pacing: PrintPacing = PrintPacing(),
    private val idleDisconnectMs: Long = 120_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Waits between reconnect tries while a screen holds the link; the last one repeats until it is released. */
    private val reconnectBackoffMs: List<Long> = listOf(2_000, 5_000, 15_000, 30_000),
) {
    private val _state = MutableStateFlow<PrinterState>(if (store.load() == null) PrinterState.NoPrinter else PrinterState.Off)
    val state: StateFlow<PrinterState> = _state.asStateFlow()

    private val lock = Mutex()
    private var transport: PrinterTransport? = null
    private var reader: Job? = null
    private var idleTimer: Job? = null
    private var reconnector: Job? = null
    private var statusBytes = Channel<Int>(Channel.UNLIMITED)
    private var statusSupported: Boolean? = null
    @Volatile private var linkLost = false
    private var holders = 0
    /**
     * Bytes a raster command still expected when the link last broke in the middle of it. The printer may have
     * stayed on (out of range) with its parser waiting for them, so the next job first sends that many zero
     * bytes: white dots that finish the stranded band, or NULs, which ESC/POS ignores, if the printer was reset.
     */
    private var strandedBytes = 0
    private val printed = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }

    val savedPrinter: SavedPrinter? get() = store.load()

    /**
     * Print is enabled only with a saved printer that is linked now (F-SR-013). An empty roll keeps the link,
     * so Print stays enabled to retry after the roll is changed.
     */
    val canPrint: Boolean get() = store.load() != null && printable(state.value)

    /** [canPrint] as a flow, for a Print button that must follow the printer. */
    val canPrintFlow: Flow<Boolean> = state.map { printable(it) && store.load() != null }.distinctUntilChanged()

    private fun printable(s: PrinterState) = s == PrinterState.Connected || s == PrinterState.PaperOut

    /** Saves (or replaces) the paired printer and connects to it. */
    suspend fun select(printer: SavedPrinter): Boolean {
        lock.withLock {
            closeLink()
            strandedBytes = 0
            store.save(printer)
            _state.value = PrinterState.Off
        }
        return connect()
    }

    /** Forgets the printer. */
    suspend fun forget() = lock.withLock {
        closeLink()
        strandedBytes = 0
        store.save(null)
        _state.value = PrinterState.NoPrinter
    }

    /**
     * A screen that prints (sale, memo, stock) holds the link while visible; the returned function releases it.
     * Holding connects now if needed and keeps reconnecting after a drop.
     */
    fun hold(): () -> Unit {
        synchronized(this) { holders++ }
        idleTimer?.cancel()
        scope.launch { if (!connect()) autoReconnect() }
        var released = false
        return {
            synchronized(this) {
                if (!released) { released = true; holders-- }
            }
            scheduleIdle()
        }
    }

    /** Connects to the saved printer unless linked; true when linked afterwards. */
    suspend fun connect(): Boolean = lock.withLock { connectLocked() }

    private suspend fun connectLocked(): Boolean {
        val saved = store.load() ?: run { _state.value = PrinterState.NoPrinter; return false }
        if (transport != null && !linkLost) return true
        closeLink()
        _state.value = PrinterState.Connecting
        val t = factory.open(saved.address)
        val ok = try {
            withTimeoutOrNull(pacing.connectTimeoutMs) { t.connect(); true } ?: false
        } catch (e: CancellationException) {
            t.close(); throw e
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false // Bluetooth permission withdrawn: same as unreachable for the seller
        }
        if (!ok) {
            t.close()
            _state.value = PrinterState.Off
            return false
        }
        transport = t
        linkLost = false
        statusSupported = null
        statusBytes = Channel(Channel.UNLIMITED)
        val bytes = statusBytes
        reader = scope.launch {
            try {
                while (true) {
                    val b = t.read()
                    if (b < 0) break
                    bytes.trySend(b)
                }
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
            onLinkDropped(t)
        }
        _state.value = PrinterState.Connected
        scheduleIdle()
        return true
    }

    private fun onLinkDropped(t: PrinterTransport) {
        if (transport !== t) return
        linkLost = true
        if (_state.value !is PrinterState.Printing) _state.value = PrinterState.Off
        scope.launch {
            lock.withLock { if (transport === t) closeLink() }
            if (_state.value == PrinterState.Connected || _state.value == PrinterState.PaperOut) _state.value = PrinterState.Off
            autoReconnect()
        }
    }

    private fun autoReconnect() {
        if (reconnector?.isActive == true) return
        reconnector = scope.launch {
            var attempt = 0
            // Only while a printing screen is open: the screen is on anyway, and a try is one RFCOMM connect.
            while (synchronized(this@PrinterManager) { holders } > 0 && store.load() != null) {
                delay(reconnectBackoffMs[minOf(attempt, reconnectBackoffMs.lastIndex)])
                attempt++
                if (synchronized(this@PrinterManager) { holders } == 0) return@launch
                if (transport != null && !linkLost) return@launch
                if (connect()) return@launch
            }
        }
    }

    private fun scheduleIdle() {
        idleTimer?.cancel()
        idleTimer = scope.launch {
            delay(idleDisconnectMs)
            lock.withLock {
                if (synchronized(this@PrinterManager) { holders } == 0 && transport != null) {
                    closeLink()
                    _state.value = if (store.load() == null) PrinterState.NoPrinter else PrinterState.Off
                }
            }
        }
    }

    private fun closeLink() {
        val t = transport
        transport = null
        reader?.cancel()
        reader = null
        t?.close()
        statusBytes.close()
    }

    /** Prints [job]; never throws. See the class comment for the guarantees. */
    suspend fun print(job: PrintJob): PrintOutcome = lock.withLock {
        if (printed.containsKey(job.id)) return@withLock PrintOutcome.AlreadyPrinted
        if (store.load() == null) return@withLock PrintOutcome.Failed(PrintFailure.NO_PRINTER)
        idleTimer?.cancel()
        if (!connectLocked()) return@withLock PrintOutcome.Failed(PrintFailure.UNREACHABLE)
        val t = transport!!
        val outcome = try {
            send(t, job)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            PrintOutcome.Failed(PrintFailure.DISCONNECTED)
        } catch (_: SecurityException) {
            PrintOutcome.Failed(PrintFailure.DISCONNECTED)
        }
        when (outcome) {
            PrintOutcome.Printed -> {
                printed[job.id] = true
                _state.value = PrinterState.Connected
            }
            is PrintOutcome.Failed -> when (outcome.reason) {
                PrintFailure.PAPER_OUT -> _state.value = PrinterState.PaperOut
                else -> {
                    closeLink()
                    _state.value = PrinterState.Off
                    autoReconnect()
                }
            }
            else -> Unit
        }
        scheduleIdle()
        outcome
    }

    /**
     * Asks the paper sensor (DLE EOT 4): true = roll out, false = paper present, null = no answer from a printer
     * that never answers. The first query on a link waits longer (the printer may be waking up). A printer that
     * answered before and now stays silent is gone: that throws, so the job fails instead of reporting Printed.
     */
    private suspend fun queryPaper(t: PrinterTransport): Boolean? {
        if (statusSupported == false) return null
        while (statusBytes.tryReceive().isSuccess) Unit
        guardedWrite(t, EscPos.statusQuery(4))
        val wait = if (statusSupported == null) pacing.statusTimeoutMs * 3 else pacing.statusTimeoutMs * 2
        val b = withTimeoutOrNull(wait) {
            var v: Int
            do { v = statusBytes.receive() } while (!EscPos.isStatusByte(v))
            v
        }
        if (b == null) {
            if (statusSupported == true) throw IOException("printer stopped answering")
            // Never answered on this link: a printer without DLE EOT; stop asking on this link.
            statusSupported = false
            return null
        }
        statusSupported = true
        return EscPos.paperOut(b)
    }

    private suspend fun guardedWrite(t: PrinterTransport, bytes: ByteArray) {
        if (linkLost) throw IOException("link lost")
        val done = withTimeoutOrNull(pacing.writeTimeoutMs.coerceAtLeast(500)) { t.write(bytes); true }
        if (done == null) {
            t.close() // unblocks the stuck write; the reader then reports the drop
            throw IOException("write timed out")
        }
        if (linkLost) throw IOException("link lost")
    }

    private suspend fun send(t: PrinterTransport, job: PrintJob): PrintOutcome {
        _state.value = PrinterState.Printing(job.id, 0)
        if (queryPaper(t) == true) return PrintOutcome.Failed(PrintFailure.PAPER_OUT)
        val commands = ArrayList<ByteArray>()
        val padding = strandedBytes > 0
        if (padding) commands.add(ByteArray(strandedBytes))
        commands.addAll(EscPos.job(job.bitmap, pacing.safeBandRows))
        val total = commands.sumOf { it.size }.toLong()
        val bytesPerSecond = pacing.safeRowsPerSecond.toLong() * job.bitmap.bytesPerRow
        val buffer = pacing.safeBufferBytes
        val start = nowMs()
        var sent = 0L
        for ((index, cmd) in commands.withIndex()) {
            var off = 0
            // Raster commands and the padding before them leave the printer waiting when cut short.
            val raster = (padding && index == 0) || (cmd.size > 1 && cmd[0].toInt() == 0x1D && cmd[1].toInt() == 0x76)
            while (off < cmd.size) {
                val n = minOf(pacing.safeChunkBytes, cmd.size - off)
                // Estimated bytes still in the printer's buffer; wait until this chunk fits.
                while (true) {
                    val drained = (nowMs() - start) * bytesPerSecond / 1000
                    val backlog = sent - drained
                    if (backlog + n <= buffer) break
                    val waitMs = ((backlog + n - buffer) * 1000 / bytesPerSecond).coerceAtLeast(5)
                    delay(waitMs)
                }
                // If this write breaks the link, assume none of it arrived: the printer may still want the rest.
                strandedBytes = if (raster) cmd.size - off else 0
                guardedWrite(t, cmd.copyOfRange(off, off + n))
                strandedBytes = if (raster && off + n < cmd.size) cmd.size - off - n else 0
                off += n
                sent += n
                _state.value = PrinterState.Printing(job.id, (sent * 100 / total).toInt())
            }
        }
        // Wait for the paper to come out, then ask whether the roll ran out on the way.
        val remainingMs = (sent * 1000 / bytesPerSecond) - (nowMs() - start)
        if (remainingMs > 0) delay(remainingMs)
        if (linkLost) throw IOException("link lost")
        if (queryPaper(t) == true) return PrintOutcome.Failed(PrintFailure.PAPER_OUT)
        if (linkLost) throw IOException("link lost")
        return PrintOutcome.Printed
    }

    /** Closes the link now (app going to the background for good). */
    suspend fun shutdown() = lock.withLock {
        reconnector?.cancel()
        idleTimer?.cancel()
        closeLink()
        _state.value = if (store.load() == null) PrinterState.NoPrinter else PrinterState.Off
    }
}
