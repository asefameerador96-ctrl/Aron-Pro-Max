package com.aktcl.aron.core.printing.diag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Reads the phone's Bluetooth and permission state for the diagnostics screen (D-PRINT-02). */
object AndroidDiagEnvironment {
    /**
     * The runtime permissions the printer path can need on this Android: BLUETOOTH_CONNECT (paired list, connect)
     * and BLUETOOTH_SCAN (cancelling discovery before a connect) from Android 12; location below that (only for
     * scanning, which the app does not do; reported so a phone where it matters shows it).
     */
    fun permissionsToAsk(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission") // the permission is checked first; a SecurityException is reported, not thrown
    fun collect(context: Context, appVersion: String, savedPrinter: String?): DiagEnvironment {
        val a = adapter(context)
        val perms = permissionsToAsk().associate { it.removePrefix("android.permission.") to (context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) }
        val state = when (a?.state) {
            null -> "UNKNOWN"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "STATE_${a.state}"
        }
        var bonded: List<BondedDevice>? = null
        var error: String? = null
        when {
            a == null -> error = "this phone has no Bluetooth adapter" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            perms["BLUETOOTH_CONNECT"] == false -> error = "BLUETOOTH_CONNECT not granted: Android hides the paired list" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            !a.isEnabled -> error = "Bluetooth is off" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            else -> try {
                // every bonded device, no name or class filter: an "MTP-II" or unnamed printer must show
                bonded = a.bondedDevices.orEmpty().map { BondedDevice(it.name, it.address, it.bluetoothClass?.majorDeviceClass, it.type) }
                    .sortedWith(compareBy({ it.majorClass != 0x0600 }, { it.name ?: "" }))
            } catch (e: SecurityException) {
                error = "SecurityException: ${e.message}"
            }
        }
        return DiagEnvironment(
            manufacturer = Build.MANUFACTURER ?: "?", model = Build.MODEL ?: "?", androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT, appVersion = appVersion, bluetoothSupported = a != null, adapterState = state,
            permissions = perms, bonded = bonded, bondedError = error, savedPrinter = savedPrinter,
        )
    }
}
