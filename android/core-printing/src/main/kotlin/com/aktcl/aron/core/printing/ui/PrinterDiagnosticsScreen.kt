package com.aktcl.aron.core.printing.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aktcl.aron.core.printing.R
import com.aktcl.aron.core.printing.bt.BluetoothSppTransport
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SppConnector
import com.aktcl.aron.core.printing.diag.AndroidDiagEnvironment
import com.aktcl.aron.core.printing.diag.DiagConnection
import com.aktcl.aron.core.printing.diag.DiagnosticsReport
import com.aktcl.aron.core.printing.diag.PrinterDiagnosticsController
import com.aktcl.aron.core.printing.diag.StepOutcome
import com.aktcl.aron.core.printing.diag.TestMode
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronOutlinedButton
import com.aktcl.aron.core.ui.aron.AronRadioRow
import com.aktcl.aron.core.ui.aron.AronSectionHeader
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.core.ui.aron.AronYesNo
import com.aktcl.aron.core.ui.aron.SolidTone
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings → Printer diagnostics (D-PRINT-02, docs/34 s4.1): Bluetooth state, the runtime permissions with a request
 * BEFORE the paired list is read, every paired device (no name filter), connect secure → insecure → channel 1 with
 * each error, three test pages, and a text report to copy or share. It never touches a memo or the sale flow; it
 * only closes the app's own printer link while it tests (the memo path reconnects on its next print).
 */
@Composable
fun PrinterDiagnosticsScreen(manager: PrinterManager, appVersion: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember {
        PrinterDiagnosticsController(
            scope = scope,
            connect = { address -> openLink(context, address) },
            releaseAppLink = { manager.shutdown() },
        )
    }
    val state by controller.state.collectAsState()
    fun refresh() = controller.setEnvironment(AndroidDiagEnvironment.collect(context, appVersion, manager.savedPrinter?.let { "${it.name} ${it.address}" }))
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    // read again on every return to the screen (after Bluetooth or app settings)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val env = state.env
    val busy = state.busy

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 1. Bluetooth
        AronSectionHeader(stringResource(R.string.diag_bluetooth))
        AronCard {
            Text(
                stringResource(
                    when {
                        env == null -> R.string.diag_log_empty
                        !env.bluetoothSupported -> R.string.diag_bt_none
                        env.adapterState == "ON" -> R.string.diag_bt_on
                        else -> R.string.diag_bt_off
                    },
                ),
            )
            if (env != null && env.bluetoothSupported && env.adapterState != "ON") {
                Spacer(Modifier.height(8.dp))
                AronOutlinedButton(stringResource(R.string.diag_bt_open_settings), { open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, Modifier.fillMaxWidth())
            }
        }
        // 2. Permissions, asked before the paired list
        AronSectionHeader(stringResource(R.string.diag_permissions))
        AronCard {
            val perms = env?.permissions.orEmpty()
            for ((name, granted) in perms) Text("$name: " + stringResource(if (granted) R.string.diag_perm_granted else R.string.diag_perm_denied))
            if (perms.values.any { !it }) {
                Text(stringResource(R.string.diag_perm_needed), Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                AronSolidButton(stringResource(R.string.diag_perm_ask), { ask.launch(AndroidDiagEnvironment.permissionsToAsk().toTypedArray()) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                // a "don't ask again" denial answers at once without a dialog: the app settings page is the way out
                AronOutlinedButton(stringResource(R.string.diag_perm_app_settings), {
                    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }, Modifier.fillMaxWidth())
            }
        }
        // 3. Paired devices, all of them
        AronSectionHeader(stringResource(R.string.diag_paired))
        AronCard {
            val list = env?.bonded
            when {
                env == null -> Unit
                list == null -> Text(stringResource(R.string.diag_paired_hidden, env.bondedError ?: "?"))
                list.isEmpty() -> Text(stringResource(R.string.diag_paired_none))
                else -> for (d in list) {
                    AronRadioRow(DiagnosticsReport.describe(d, full = true), state.selected?.address == d.address, { controller.select(d) }, enabled = !busy)
                }
            }
            val sel = state.selected
            if (sel != null) {
                Spacer(Modifier.height(8.dp))
                val inUse = manager.savedPrinter?.address == sel.address
                AronOutlinedButton(stringResource(if (inUse) R.string.diag_in_use else R.string.diag_use_printer), {
                    scope.launch { manager.select(SavedPrinter(sel.address, sel.name ?: sel.address)); refresh() }
                }, Modifier.fillMaxWidth(), enabled = !inUse && !busy)
            }
        }
        // 4. Tests
        AronSectionHeader(stringResource(R.string.diag_tests))
        AronCard {
            val can = !busy && state.selected != null
            if (busy) Text(stringResource(R.string.diag_running), Modifier.padding(bottom = 8.dp))
            AronSolidButton(stringResource(R.string.diag_run_all), { scope.launch { controller.runAll() } }, Modifier.fillMaxWidth(), enabled = can)
            Spacer(Modifier.height(8.dp))
            AronOutlinedButton(stringResource(R.string.diag_connect), { scope.launch { controller.connectOnly() } }, Modifier.fillMaxWidth(), enabled = can)
            for ((mode, label) in listOf(TestMode.RASTER to R.string.diag_test_raster, TestMode.BIT_IMAGE to R.string.diag_test_bitimage, TestMode.ASCII to R.string.diag_test_ascii)) {
                Spacer(Modifier.height(8.dp))
                AronOutlinedButton(stringResource(label), { scope.launch { controller.testPage(mode) } }, Modifier.fillMaxWidth(), enabled = can)
            }
            for (mode in state.toJudge) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.diag_did_print, mode.label))
                AronYesNo(stringResource(R.string.dialog_yes), stringResource(R.string.dialog_no), state.paper[mode], { controller.judge(mode, it) }, Modifier.padding(top = 4.dp))
            }
        }
        // 5. Log and export
        AronSectionHeader(stringResource(R.string.diag_log))
        AronCard {
            if (state.steps.isEmpty()) Text(stringResource(R.string.diag_log_empty))
            for (s in state.steps) {
                val mark = when (s.outcome) { StepOutcome.OK -> "✓"; StepOutcome.FAIL -> "✗"; StepOutcome.INFO -> "•" }
                Text("$mark ${s.name}: ${s.detail}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AronSolidButton(stringResource(R.string.diag_copy), {
                val text = controller.report(now())
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Aron printer diagnostics", text))
                Toast.makeText(context, R.string.diag_copied, Toast.LENGTH_SHORT).show()
            }, Modifier.weight(1f), tone = SolidTone.Green)
            AronOutlinedButton(stringResource(R.string.diag_share), {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Aron printer diagnostics").putExtra(Intent.EXTRA_TEXT, controller.report(now()))
                open(context, Intent.createChooser(send, null))
            }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun now(): String =
    ZonedDateTime.now(ZoneId.of("Asia/Dhaka")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT))

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // settings blocked by the device-owner lock task: nothing to open
    } catch (_: SecurityException) {
    }
}

/** One diagnostics link: every SPP method in turn, each error kept; never throws. */
private suspend fun openLink(context: Context, address: String): DiagConnection {
    val t = BluetoothSppTransport(context, address, SppConnector())
    return try {
        t.connect()
        DiagConnection(t, t.lastAttempts)
    } catch (e: IOException) {
        t.close()
        DiagConnection(null, t.lastAttempts, if (t.lastAttempts.isEmpty()) SppConnector.describe(e) else null)
    } catch (e: SecurityException) {
        t.close()
        DiagConnection(null, t.lastAttempts, SppConnector.describe(e))
    }
}
