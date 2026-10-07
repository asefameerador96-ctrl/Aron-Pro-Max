package com.aktcl.aron.core.printing.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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
 * Bluetooth Classic SPP link to a paired printer (docs/24 s5.5): RFCOMM on the serial-port UUID, secure first
 * and the insecure socket as fallback (some MP-58N firmware refuses the secure one). Discovery is cancelled
 * before connecting because it slows RFCOMM down. All blocking calls run on [Dispatchers.IO].
 */
class BluetoothSppTransport(private val context: Context, private val address: String) : PrinterTransport {
    @Volatile private var socket: BluetoothSocket? = null

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission") // checked by hasConnectPermission; a SecurityException is handled by the caller
    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (!hasConnectPermission(context)) throw IOException("BLUETOOTH_CONNECT not granted")
        val a = adapter() ?: throw IOException("no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth off")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw IOException("bad address")
        val device = a.getRemoteDevice(address)
        try { a.cancelDiscovery() } catch (_: SecurityException) {}
        var last: IOException? = null
        for (secure in listOf(true, false)) {
            val s = try {
                if (secure) device.createRfcommSocketToServiceRecord(SPP) else device.createInsecureRfcommSocketToServiceRecord(SPP)
            } catch (e: IOException) { last = e; continue }
            try {
                s.connect()
                socket = s
                return@withContext
            } catch (e: IOException) {
                last = e
                try { s.close() } catch (_: IOException) {}
            }
        }
        throw last ?: IOException("connect failed")
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("not connected")
        s.outputStream.write(bytes)
        s.outputStream.flush()
    }

    override suspend fun read(): Int {
        val s = socket ?: throw IOException("not connected")
        return runInterruptible(Dispatchers.IO) { s.inputStream.read() }
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
