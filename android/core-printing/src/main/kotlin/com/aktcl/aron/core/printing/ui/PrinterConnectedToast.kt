package com.aktcl.aron.core.printing.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * Where the SR frame shows the connected bar: the frame provides a flag and draws [PrinterConnectedBar] over its content when it is true
 * (an overlay in the same window, so it never takes a touch). Null outside the SR frame: the system Toast is used.
 */
val LocalPrinterBarFlag = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/** How long the SR's connected bar stays up. */
const val PrinterBarMillis = 2500L

@Composable
fun PrinterConnectedToast(manager: PrinterManager) = PrinterConnectedToast(manager.state)

/** The same, over any state flow (the behaviour tests drive it). */
@Composable
fun PrinterConnectedToast(states: kotlinx.coroutines.flow.StateFlow<PrinterState>) {
    val state by states.collectAsState()
    val context = LocalContext.current
    // inside the SR app the sentence is the manual's full-width green bar (a system Toast cannot be styled or recorded); AMO keeps the system toast
    // the bar needs both: the SR frame's flag and the SR look (without the look the system toast is used, as the host tests expect)
    val flag = if (com.aktcl.aron.core.ui.LocalSrManualLook.current) LocalPrinterBarFlag.current else null
    var was by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        // `was` moves first and nothing here suspends: a state change inside the bar's time can never leave it stale
        val toast = PrinterToast.shouldToast(was, state)
        was = state
        if (toast) { if (flag != null) flag.value = true else Toast.makeText(context, R.string.printer_connected, Toast.LENGTH_SHORT).show() }
    }
    // the bar hides from its own effect, keyed on the flag: a printer state change cannot cancel the hide
    val on = flag?.value == true
    LaunchedEffect(on) { if (on) { delay(PrinterBarMillis); flag?.value = false } }
}

/** The manual's bar (screenshot 17, right): full width, green, white sentence. A plain layout (no window): it takes no touch, so what is under it still works. */
@Composable
fun PrinterConnectedBar(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(PrinterBarGreen).padding(vertical = 20.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.printer_connected), color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}
