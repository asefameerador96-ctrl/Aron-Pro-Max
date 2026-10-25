package com.aktcl.aron.core.printing.bt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * A simulated MP-58N for tests: an ESC/POS parser that puts raster rows on "paper", a receive buffer of
 * [bufferBytes] drained at [rowsPerSecond] (virtual time from [now]); overflowing it is recorded, since that
 * is what prints garbage on the real printer. It can be switched off at any byte, run out of paper after a row
 * count, ignore status queries, or hang on writes.
 */
class SimPrinter(
    private val now: () -> Long,
    val bufferBytes: Int = 4096,
    val rowsPerSecond: Int = 350,
    val bytesPerRow: Int = 48,
) {
    var on = true
    var answersStatus = true
    /** Cheap firmware: answers DLE EOT only once everything before it has printed (not in real time). */
    var statusAfterDrain = false
    /**
     * A printer that stayed on (out of range) while the link broke: its parser keeps a half-received command across
     * reconnects instead of starting clean (the default models a printer that was switched off and on).
     */
    var stayedOn = false
    /** False for firmware whose ESC @ does not empty the line buffer of a pending ESC * band. */
    var initClearsLineBuffer = true
    /** An ESC * band arrived while another still waited for its LF: two bands on one line, garbage on paper. */
    var mergedLine = false
    /** An ESC * band was printed with a line spacing under 24 dots: bands overlap. */
    var badSpacing = false

    // parser state: per printer, so a printer that stayed on keeps it across links
    private var pending = ByteArrayOutputStream()
    private var parsed = 0
    private var lineBuf: List<ByteArray>? = null
    private var spacing = 30
    var hangWrites = false
    /** From this byte on, a write blocks like a BluetoothSocket stream: cancellation does not end it, only close() does. */
    var blockFromByte: Long = Long.MAX_VALUE
    /** At this byte the stream throws a RuntimeException (an OEM close race), not an IOException. */
    var runtimeErrorAtByte: Long = Long.MAX_VALUE
    /** Turn off once this many bytes have arrived in total (across sessions). */
    var switchOffAtByte: Long = Long.MAX_VALUE
    /** Rows of paper left on the roll. */
    var paperRows: Int = Int.MAX_VALUE
    var overflowed = false
    /** Blank paper fed (dots) after the last printed row: `ESC d n` feeds n x the current line spacing (D-PRINT-09). */
    var trailingFeedDots = 0
    var connects = 0
    /** Connect attempts, successful or not. */
    var attempts = 0
    var totalBytes = 0L

    /** Rows printed, one list per connection session. */
    val sessions = ArrayList<MutableList<ByteArray>>()

    private var link: Link? = null

    fun switchOff() {
        on = false
        link?.drop()
        link = null
    }

    fun factory() = PrinterTransportFactory { Link() }

    inner class Link : PrinterTransport {
        private val replies = Channel<Int>(Channel.UNLIMITED)
        /** When each queued reply may be read (the same order as [replies]). */
        private val readyAt = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        private var open = false
        private var level = 0L
        private var lastMs = 0L
        private lateinit var paper: MutableList<ByteArray>
        private val hang = CompletableDeferred<Unit>()

        fun drop() {
            open = false
            replies.close(IOException("printer off"))
            hang.completeExceptionally(IOException("printer off"))
        }

        override suspend fun connect() {
            attempts++
            if (!on) throw IOException("printer off")
            connects++
            open = true
            lastMs = now()
            level = 0
            paper = ArrayList()
            sessions.add(paper)
            if (!stayedOn) { pending = ByteArrayOutputStream(); parsed = 0; lineBuf = null; spacing = 30 }
            link = this
        }

        override suspend fun write(bytes: ByteArray) {
            if (!open || !on) throw IOException("link down")
            if (hangWrites) hang.await()
            if (totalBytes >= blockFromByte) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { hang.await() }
            if (totalBytes >= runtimeErrorAtByte) throw IllegalStateException("socket closed under the stream")
            // The buffer drains at print speed only while it holds data: idle time gives no credit.
            val t = now()
            level = maxOf(0L, level - (t - lastMs) * rowsPerSecond * bytesPerRow / 1000)
            lastMs = t
            for (b in bytes) {
                if (totalBytes >= switchOffAtByte) { switchOff(); throw IOException("printer switched off") }
                totalBytes++
                level++
                pending.write(b.toInt())
            }
            if (level > bufferBytes) overflowed = true
            parse()
        }

        override suspend fun read(): Int = try {
            val v = replies.receive()
            val at = readyAt.poll() ?: 0L
            if (at > now()) kotlinx.coroutines.delay(at - now())
            if (!open) throw IOException("closed")
            v
        } catch (e: IOException) {
            throw e
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw IOException("closed")
        }

        override fun close() {
            open = false
            hang.completeExceptionally(IOException("closed"))
            replies.close()
            if (link === this) link = null
        }

        private fun parse() {
            val d = pending.toByteArray()
            var i = parsed
            while (i < d.size) {
                val b = d[i].toInt() and 0xFF
                when {
                    b == 0x1B && i + 1 < d.size && d[i + 1].toInt() == 0x40 -> { if (initClearsLineBuffer) lineBuf = null; spacing = 30; i += 2 }
                    b == 0x1B && i + 2 < d.size && d[i + 1].toInt() == 0x33 -> { spacing = d[i + 2].toInt() and 0xFF; i += 3 }
                    b == 0x1B && i + 2 < d.size && d[i + 1].toInt() == 0x64 -> { trailingFeedDots += (d[i + 2].toInt() and 0xFF) * spacing; i += 3 }
                    b == 0x10 && i + 2 < d.size && d[i + 1].toInt() == 0x04 -> {
                        if (answersStatus) {
                            // the backlog at this moment drains at print speed before a sequential firmware answers
                            readyAt.add(if (statusAfterDrain) now() + level * 1000 / (rowsPerSecond.toLong() * bytesPerRow) else 0L)
                            replies.trySend(if (paperRows <= 0) 0x72 else 0x12)
                        }
                        i += 3
                    }
                    b == 0x1D && i + 7 < d.size && d[i + 1].toInt() == 0x76 -> {
                        val w = (d[i + 4].toInt() and 0xFF) or ((d[i + 5].toInt() and 0xFF) shl 8)
                        val h = (d[i + 6].toInt() and 0xFF) or ((d[i + 7].toInt() and 0xFF) shl 8)
                        if (i + 8 + w * h > d.size) break // wait for the rest of the command
                        for (r in 0 until h) {
                            if (paperRows <= 0) break
                            paperRows--
                            paper.add(d.copyOfRange(i + 8 + r * w, i + 8 + (r + 1) * w))
                            trailingFeedDots = 0
                        }
                        i += 8 + w * h
                    }
                    b == 0x1B && i + 4 < d.size && d[i + 1].toInt() == 0x2A -> {
                        // ESC * 33 nL nH: 24-dot bit image, 3 bytes per column (MSB top), into the line buffer
                        if (d[i + 2].toInt() != 33) throw AssertionError("ESC * mode ${d[i + 2]}")
                        val n = (d[i + 3].toInt() and 0xFF) or ((d[i + 4].toInt() and 0xFF) shl 8)
                        if (i + 5 + 3 * n > d.size) break
                        if (lineBuf != null) mergedLine = true
                        val w = (n + 7) / 8
                        lineBuf = (0 until 24).map { r ->
                            val row = ByteArray(w)
                            for (x in 0 until n) if (d[i + 5 + 3 * x + r / 8].toInt() and (0x80 ushr (r % 8)) != 0) row[x / 8] = (row[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
                            row
                        }
                        i += 5 + 3 * n
                    }
                    b == 0x0A -> {
                        // LF prints the line buffer (an ESC * band); a band needs a line spacing of 24 to touch the next
                        lineBuf?.let { rows ->
                            if (spacing < 24) badSpacing = true
                            for (r in rows) { if (paperRows <= 0) break; paperRows--; paper.add(r); trailingFeedDots = 0 }
                        }
                        lineBuf = null
                        i++
                    }
                    b == 0x1B && i + 1 < d.size && d[i + 1].toInt() == 0x32 -> { spacing = 30; i += 2 } // ESC 2
                    b == 0x00 -> i++ // NUL is ignored by ESC/POS printers
                    b == 0x1B || b == 0x10 || b == 0x1D -> break // incomplete command: wait for more bytes
                    else -> throw AssertionError("garbage byte 0x${b.toString(16)} at $i")
                }
            }
            parsed = i
        }
    }
}
