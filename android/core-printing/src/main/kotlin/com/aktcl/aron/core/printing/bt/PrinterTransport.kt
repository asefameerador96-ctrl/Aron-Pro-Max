package com.aktcl.aron.core.printing.bt

import java.io.IOException

/**
 * A byte pipe to one printer (N-019). The Android implementation is Bluetooth Classic SPP
 * ([BluetoothSppTransport]); tests use a simulated printer. Implementations do their blocking I/O off the
 * caller's thread; [close] may be called from anywhere and unblocks a pending [read] or [write].
 */
interface PrinterTransport {
    /** Opens the link; throws [IOException] when the printer is off, out of range or not paired. */
    @Throws(IOException::class)
    suspend fun connect()

    @Throws(IOException::class)
    suspend fun write(bytes: ByteArray)

    /** Next byte from the printer; -1 at end of stream; throws when the link breaks. */
    @Throws(IOException::class)
    suspend fun read(): Int

    fun close()
}

/** Opens transports to a saved printer address. */
fun interface PrinterTransportFactory {
    fun open(address: String): PrinterTransport
}
