package com.aktcl.aron.core.printing.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic SPP link to a paired printer (docs/24 s5.5, D-PRINT-02): RFCOMM on the serial-port UUID, secure
 * first, then insecure (some MP-58N firmware refuses the secure one), then RFCOMM channel 1 without SDP (printers
 * with a broken SDP record), each try bounded and cancellable ([SppConnector]). Discovery is cancelled before
 * connecting because it slows RFCOMM down. All blocking calls run on [Dispatchers.IO].
 */
class BluetoothSppTransport(
    private val context: Context,
    private val address: String,
    private val connector: SppConnector = SppConnector(),
) : PrinterTransport {
    @Volatile private var socket: SppSocket? = null

    /** The tries of the last [connect], with their error text (diagnostics). */
    @Volatile var lastAttempts: List<ConnectAttempt> = emptyList()
        private set

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission") // checked by hasConnectPermission; a SecurityException is handled by the caller
    override suspend fun connect() = connect(SppMethod.entries)

    /** Connects trying [methods] in order (the diagnostics screen tries one at a time). */
    @SuppressLint("MissingPermission")
    suspend fun connect(methods: List<SppMethod>) {
        lastAttempts = emptyList()
        if (!hasConnectPermission(context)) throw IOException("BLUETOOTH_CONNECT not granted")
        val a = adapter() ?: throw IOException("no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth off")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw IOException("bad address")
        val device = a.getRemoteDevice(address)
        withContext(Dispatchers.IO) { try { a.cancelDiscovery() } catch (_: SecurityException) {} }
        val result = connector.connect(address, methods) { m -> AndroidSppSocket(open(device, m)) }
        lastAttempts = result.attempts
        socket = result.socket ?: throw IOException(result.attempts.joinToString("; ") { "${it.method}: ${it.error}" }.ifEmpty { "connect failed" })
    }

    @SuppressLint("MissingPermission")
    private fun open(device: BluetoothDevice, m: SppMethod): BluetoothSocket = when (m) {
        SppMethod.SECURE -> device.createRfcommSocketToServiceRecord(SPP)
        SppMethod.INSECURE -> device.createInsecureRfcommSocketToServiceRecord(SPP)
        // Hidden API on the unsupported (greylist) list: reachable by reflection on every Android version we support.
        SppMethod.CHANNEL_1 -> try {
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.targetException as? Exception) ?: IOException(e.targetException)
        }
    }

    private class AndroidSppSocket(private val s: BluetoothSocket) : SppSocket {
        @SuppressLint("MissingPermission")
        override fun connect() = s.connect()
        override val input get() = s.inputStream
        override val output get() = s.outputStream
        override fun close() = s.close()
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("not connected")
        s.output.write(bytes)
        s.output.flush()
    }

    override suspend fun read(): Int {
        val s = socket ?: throw IOException("not connected")
        return runInterruptible(Dispatchers.IO) { s.input.read() }
    }

    override fun close() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: IOException) {}
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun hasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun factory(context: Context) = PrinterTransportFactory { BluetoothSppTransport(context.applicationContext, it) }

        /**
         * Paired devices that can be a printer, printers first (major class IMAGING). Empty when Bluetooth is off
         * or the permission is missing; pairing itself happens in the system Bluetooth settings.
         */
        @SuppressLint("MissingPermission")
        fun bondedPrinters(context: Context): List<SavedPrinter> {
            if (!hasConnectPermission(context)) return emptyList()
            val a = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
            return try {
                a.bondedDevices.orEmpty()
                    .sortedWith(compareBy({ it.bluetoothClass?.majorDeviceClass != BluetoothClass.Device.Major.IMAGING }, { it.name ?: "" }))
                    .map { SavedPrinter(it.address, it.name ?: it.address) }
            } catch (_: SecurityException) {
                emptyList()
            }
        }
    }
}

/** The paired printer in the app's private preferences (one file per app; each app pairs once). */
class PrefsSavedPrinterStore(context: Context) : SavedPrinterStore {
    private val prefs = context.applicationContext.getSharedPreferences("aron_printer", Context.MODE_PRIVATE)

    override fun load(): SavedPrinter? {
        val a = prefs.getString(KEY_ADDRESS, null) ?: return null
        return SavedPrinter(a, prefs.getString(KEY_NAME, null) ?: a)
    }

    override fun save(printer: SavedPrinter?) {
        prefs.edit().apply {
            if (printer == null) { remove(KEY_ADDRESS); remove(KEY_NAME) } else { putString(KEY_ADDRESS, printer.address); putString(KEY_NAME, printer.name) }
        }.apply()
    }

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
    }
}
