package com.aktcl.aron.feature.auth

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * The enrolment QR on a normally installed phone: Google's code scanner (the camera UI runs in Play services, so the app
 * asks for no camera permission and ships no scanning model). Opened only by a tap on the enrolment screen; nothing runs
 * in the background. The scanned text is the portal's QR (it carries the one-time token) and is never logged.
 */
object EnrolmentQrScanner {
    sealed interface Result {
        data class Text(val value: String) : Result
        data object Cancelled : Result
        /** No Play services scanner on this phone (or it failed): the token can still be pasted. */
        data object Unavailable : Result
    }

    fun scan(context: Context, onResult: (Result) -> Unit) {
        val client = try {
            GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        } catch (e: Exception) {
            onResult(Result.Unavailable); return
        }
        client.startScan()
            .addOnSuccessListener { b -> onResult(b.rawValue?.let { Result.Text(it) } ?: Result.Unavailable) }
            .addOnCanceledListener { onResult(Result.Cancelled) }
            .addOnFailureListener { onResult(Result.Unavailable) }
    }
}
