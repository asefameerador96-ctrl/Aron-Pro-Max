package com.aktcl.aron.core.printing.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.printing.R
import com.aktcl.aron.core.printing.bt.BluetoothSppTransport
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.PrinterState
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import kotlinx.coroutines.launch

/**
 * What the printer icon and banner show for a state (F-SR-013): green when linked, red and slashed otherwise.
 * Pure, so the mapping is unit-tested; the composables below only draw it.
 */
data class PrinterIndicator(val green: Boolean, val slashed: Boolean, val busy: Boolean, @StringRes val banner: Int) {
    companion object {
        fun of(state: PrinterState): PrinterIndicator = when (state) {
            PrinterState.Connected -> PrinterIndicator(green = true, slashed = false, busy = false, banner = R.string.printer_connected)
            is PrinterState.Printing -> PrinterIndicator(green = true, slashed = false, busy = true, banner = R.string.printer_printing)
            PrinterState.Connecting -> PrinterIndicator(green = false, slashed = false, busy = true, banner = R.string.printer_connecting)
            PrinterState.PaperOut -> PrinterIndicator(green = false, slashed = true, busy = false, banner = R.string.printer_paper_out)
            PrinterState.Off -> PrinterIndicator(green = false, slashed = true, busy = false, banner = R.string.printer_not_connected)
            PrinterState.NoPrinter -> PrinterIndicator(green = false, slashed = true, busy = false, banner = R.string.printer_none)
        }

        val GREEN = Color(0xFF2E7D32)
        val RED = Color(0xFFC62828)
    }
}

/** Keeps the printer link while the calling screen is visible (connects now; releases on leave). */
@Composable
fun HoldPrinter(manager: PrinterManager) {
    DisposableEffect(manager) {
        val release = manager.hold()
        onDispose { release() }
    }
}

/** The printer icon: green printer when connected, red slashed when not; tap opens the picker. */
@Composable
fun PrinterIcon(manager: PrinterManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val ind = PrinterIndicator.of(state)
    val description = stringResource(ind.banner)
    Row(modifier.clickable { picking = true }.semantics { contentDescription = description }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ind.busy && !ind.green) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            PrinterGlyph(if (ind.green) PrinterIndicator.GREEN else PrinterIndicator.RED, ind.slashed)
        }
    }
    if (picking) PrinterPickerDialog(manager) { picking = false }
}

@Composable
private fun PrinterGlyph(color: Color, slashed: Boolean) {
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.08f)
        drawRect(color, Offset(w * 0.28f, h * 0.12f), Size(w * 0.44f, h * 0.22f), style = stroke)
        drawRoundRect(color, Offset(w * 0.1f, h * 0.34f), Size(w * 0.8f, h * 0.34f), CornerRadius(w * 0.06f))
        drawRect(color, Offset(w * 0.28f, h * 0.6f), Size(w * 0.44f, h * 0.28f), style = stroke)
        if (slashed) drawLine(color, Offset(w * 0.08f, h * 0.92f), Offset(w * 0.92f, h * 0.08f), strokeWidth = w * 0.1f)
    }
}

/** The banner under the screen title: "প্রিন্টার কানেক্ট করা হয়েছে" or why printing is not possible now. */
@Composable
fun PrinterBanner(manager: PrinterManager, modifier: Modifier = Modifier) {
    val state by manager.state.collectAsState()
    val ind = PrinterIndicator.of(state)
    Surface(modifier.fillMaxWidth(), color = if (ind.green) PrinterIndicator.GREEN else PrinterIndicator.RED) {
        Text(stringResource(ind.banner), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Chooses the printer from the phone's paired devices (pairing itself is in Android's Bluetooth settings, PIN
 * 0000). Asks for BLUETOOTH_CONNECT on Android 12+ when the device-owner policy has not granted it already.
 */
@Composable
fun PrinterPickerDialog(manager: PrinterManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var printers by remember { mutableStateOf(BluetoothSppTransport.bondedPrinters(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        printers = BluetoothSppTransport.bondedPrinters(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !BluetoothSppTransport.hasConnectPermission(context)) {
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.printer_pick_title)) },
        text = {
            Column {
                if (printers.isEmpty()) Text(stringResource(R.string.printer_pick_empty))
                for (p in printers) {
                    val saved = manager.savedPrinter?.address == p.address
                    Text(
                        p.name,
                        Modifier.fillMaxWidth().clickable {
                            scope.launch { manager.select(SavedPrinter(p.address, p.name)) }
                            onDismiss()
                        }.padding(vertical = 12.dp),
                        color = if (saved) PrinterIndicator.GREEN else Color.Unspecified,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Text(stringResource(R.string.printer_open_bluetooth))
            }
        },
        dismissButton = {
            Row {
                if (manager.savedPrinter != null) TextButton(onClick = { scope.launch { manager.forget() }; onDismiss() }) { Text(stringResource(R.string.printer_forget)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
            }
        },
    )
}

/**
 * The sale screen's dialogs over a [SaveAndPrint] flow (F-SR-028, F-SR-073). [onFinished] runs after the
 * saved message, with whether paper was confirmed.
 */
@Composable
fun SaveAndPrintDialogs(flow: SaveAndPrint, onFinished: (printed: Boolean) -> Unit) {
    val step by flow.step.collectAsState()
    val scope = rememberCoroutineScope()
    when (val s = step) {
        SaveAndPrint.Step.Idle -> Unit
        SaveAndPrint.Step.AskSave -> YesNo(R.string.sale_confirm_title, R.string.sale_confirm_text, { scope.launch { flow.onSaveAnswer(it) } })
        SaveAndPrint.Step.Saving -> Busy(R.string.ui_print_saving)
        SaveAndPrint.Step.AskPrint -> YesNo(null, R.string.sale_print_question, { scope.launch { flow.onPrintAnswer(it) } })
        SaveAndPrint.Step.Printing -> Busy(R.string.printer_printing)
        is SaveAndPrint.Step.PrintFailed -> AlertDialog(
            onDismissRequest = {},
            text = { Text(stringResource(R.string.ui_print_failed)) },
            confirmButton = { TextButton(onClick = { scope.launch { flow.onRetry() } }) { Text(stringResource(R.string.ui_print_retry)) } },
            dismissButton = { TextButton(onClick = { flow.onLater() }) { Text(stringResource(R.string.ui_print_later)) } },
        )
        SaveAndPrint.Step.LimitReached -> Info(R.string.ui_print_limit_reached) { flow.onLater() }
        SaveAndPrint.Step.TooLong -> Info(R.string.ui_print_too_long) { flow.onLater() }
        SaveAndPrint.Step.AskReadable -> YesNo(null, R.string.ui_print_readable_question, { scope.launch { flow.onReadableAnswer(it) } })
        is SaveAndPrint.Step.Done -> Info(R.string.sale_saved) { onFinished(s.printed) }
    }
}

@Composable
private fun YesNo(@StringRes title: Int?, @StringRes text: Int, answer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = title?.let { { Text(stringResource(it)) } },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text(stringResource(R.string.dialog_yes)) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.dialog_no)) } },
    )
}

@Composable
private fun Info(@StringRes text: Int, ok: () -> Unit) {
    AlertDialog(
        onDismissRequest = ok,
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = ok) { Text(stringResource(R.string.dialog_ok)) } },
    )
}

@Composable
private fun Busy(@StringRes text: Int) {
    AlertDialog(
        onDismissRequest = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(text), Modifier.padding(start = 16.dp))
            }
        },
        confirmButton = {},
    )
}
