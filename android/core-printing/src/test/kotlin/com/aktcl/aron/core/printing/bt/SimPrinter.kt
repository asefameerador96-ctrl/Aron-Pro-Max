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
    var hangWrites = false
    /** Turn off once this many bytes have arrived in total (across sessions). */
    var switchOffAtByte: Long = Long.MAX_VALUE
    /** Rows of paper left on the roll. */
    var paperRows: Int = Int.MAX_VALUE
    var overflowed = false
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
        private var open = false
        private val pending = ByteArrayOutputStream()
        private var parsed = 0
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
            link = this
        }

        override suspend fun write(bytes: ByteArray) {
            if (!open || !on) throw IOException("link down")
            if (hangWrites) hang.await()
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
            replies.receive()
        } catch (e: IOException) {
            throw e
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            throw IOException("closed")
        }

        override fun close() {
            open = false
            replies.close()
            if (link === this) link = null
        }

        private fun parse() {
            val d = pending.toByteArray()
            var i = parsed
            while (i < d.size) {
                val b = d[i].toInt() and 0xFF
                when {
                    b == 0x1B && i + 1 < d.size && d[i + 1].toInt() == 0x40 -> i += 2
                    b == 0x1B && i + 2 < d.size && d[i + 1].toInt() == 0x33 -> i += 3
                    b == 0x1B && i + 2 < d.size && d[i + 1].toInt() == 0x64 -> i += 3
                    b == 0x10 && i + 2 < d.size && d[i + 1].toInt() == 0x04 -> {
                        if (answersStatus) replies.trySend(if (paperRows <= 0) 0x72 else 0x12)
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
                        }
                        i += 8 + w * h
                    }
                    b == 0x00 -> i++ // NUL is ignored by ESC/POS printers
                    b == 0x1B || b == 0x10 || b == 0x1D -> break // incomplete command: wait for more bytes
                    else -> throw AssertionError("garbage byte 0x${b.toString(16)} at $i")
                }
            }
            parsed = i
        }
    }
}
