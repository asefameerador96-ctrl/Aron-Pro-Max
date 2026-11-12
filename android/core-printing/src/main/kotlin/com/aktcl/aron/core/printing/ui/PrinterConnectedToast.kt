package com.aktcl.aron.core.printing.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aktcl.aron.core.printing.R
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.PrinterState

/** Screenshot 13 (R12) and SR manual screenshot 17 (D-SR-MAN-S3-02), shared by the AMO and SR apps: the toast "প্রিন্টার কানেক্ট করা হয়েছে" when the printer turns from not connected to connected on a printing screen. */
object PrinterToast {
    /** True on the transition into [PrinterState.Connected] from a not-connected state (not after a print or paper-out); entering a screen with the printer already connected says nothing. */
    fun shouldToast(was: PrinterState, now: PrinterState): Boolean = now == PrinterState.Connected && (was == PrinterState.Off || was == PrinterState.NoPrinter || was == PrinterState.Connecting)
}

@Composable
fun PrinterConnectedToast(manager: PrinterManager) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    var was by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        if (PrinterToast.shouldToast(was, state)) Toast.makeText(context, R.string.printer_connected, Toast.LENGTH_SHORT).show()
        was = state
    }
}
