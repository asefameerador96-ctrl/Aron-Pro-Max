package com.aktcl.aron.core.printing.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
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

/** The green of the SR's connected bar (manual 17); white text on it is 7:1 or better (PrinterConnectedBarContrastTest). */
val PrinterBarGreen = Color(0xFF14612A)

@Composable
fun PrinterConnectedToast(manager: PrinterManager) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    // inside the SR app the sentence is the manual's full-width green bar (a system Toast cannot be styled or recorded); AMO keeps the system toast
    val bar = com.aktcl.aron.core.ui.LocalSrManualLook.current
    var was by remember { mutableStateOf(state) }
    var showBar by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (PrinterToast.shouldToast(was, state)) {
            if (bar) { showBar = true; delay(2500); showBar = false }
            else Toast.makeText(context, R.string.printer_connected, Toast.LENGTH_SHORT).show()
        }
        was = state
    }
    if (showBar) PrinterConnectedBar()
}

/** The manual's bar (screenshot 17, right): full width, green, white sentence, centred on the screen above the content; it does not take touches. */
@Composable
fun PrinterConnectedBar() {
    val width = LocalConfiguration.current.screenWidthDp.dp
    Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Box(Modifier.width(width).background(PrinterBarGreen).padding(vertical = 20.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.printer_connected), color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        }
    }
}
