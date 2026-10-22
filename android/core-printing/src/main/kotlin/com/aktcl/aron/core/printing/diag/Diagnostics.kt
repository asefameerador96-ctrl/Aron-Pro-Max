package com.aktcl.aron.core.printing.diag

import com.aktcl.aron.core.printing.bt.ConnectAttempt
import com.aktcl.aron.core.printing.bt.PrinterTransport
import com.aktcl.aron.core.printing.escpos.EscPos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/** A paired (bonded) Bluetooth device as the phone lists it; nothing is filtered by name (D-PRINT-02). */
data class BondedDevice(val name: String?, val address: String, val majorClass: Int?, val type: Int?)

/** What the phone and its Bluetooth looked like when the diagnostics ran. */
data class DiagEnvironment(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appVersion: String,
    val bluetoothSupported: Boolean,
    /** "ON", "OFF", "TURNING_ON", "TURNING_OFF" or "UNKNOWN". */
    val adapterState: String,
    /** Runtime permissions the printer path can need, name to granted; only those that exist on this Android. */
    val permissions: Map<String, Boolean>,
    /** null when the list could not be read (the reason is [bondedError]). */
    val bonded: List<BondedDevice>?,
    val bondedError: String? = null,
    val savedPrinter: String? = null,
)

enum class StepOutcome { OK, FAIL, INFO }

data class DiagStep(val atMs: Long, val name: String, val outcome: StepOutcome, val detail: String)

/** The ordered steps of one diagnostics session (shown on screen and exported). Thread-safe. */
class DiagnosticsLog(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val start = nowMs()
    private val list = ArrayList<DiagStep>()

    val steps: List<DiagStep> get() = synchronized(list) { list.toList() }

    fun add(name: String, outcome: StepOutcome, detail: String) {
        synchronized(list) { list.add(DiagStep(nowMs() - start, name, outcome, Redact.text(detail))) }
    }

    fun connect(attempts: List<ConnectAttempt>) {
        for (a in attempts) add("CONNECT ${a.method}", if (a.error == null) StepOutcome.OK else StepOutcome.FAIL, (a.error ?: "connected") + " (${a.ms} ms)")
    }
}

/** What one test page did on the link. [error] is null when every byte was written. */
data class TestPageResult(val mode: TestMode, val bytesWritten: Int, val bytesTotal: Int, val ms: Long, val error: String?)

/**
 * Talks to an already connected printer for the diagnostics screen: a status probe and the three test pages, each
 * logged. Writes are paced well under the MP-58N's speed and bounded by [writeTimeoutMs], so a stalled printer
 * gives a logged error, never a hung screen. It is separate from [com.aktcl.aron.core.printing.bt.PrinterManager]:
 * nothing here touches the memo print path.
 */
class DiagnosticsSession(
    private val transport: PrinterTransport,
    private val scope: CoroutineScope,
    private val log: DiagnosticsLog,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val bytesPerSecond: Int = 9_600,
    private val bufferBytes: Int = 2_048,
    private val chunkBytes: Int = 256,
    private val writeTimeoutMs: Long = 5_000,
    private val replyWaitMs: Long = 1_000,
) {
    private val replies = Channel<Int>(Channel.UNLIMITED)
    private var reader: Job? = null
    @Volatile private var dropped: String? = null

    /** Starts reading what the printer sends back (status replies); call once after the connect. */
    fun start() {
        reader = scope.launch {
            try {
                while (true) {
                    val b = transport.read()
                    if (b < 0) { dropped = "end of stream"; break } // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
                    replies.trySend(b)
                }
            } catch (e: IOException) {
                dropped = "${e.javaClass.simpleName}: ${e.message}"
            } catch (e: SecurityException) {
                dropped = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    /** DLE EOT 1, 2 and 4; logs the reply bytes in hex or "no reply" (many cheap printers never answer). */
    suspend fun probeStatus() {
        for (n in listOf(1, 2, 4)) {
            while (replies.tryReceive().isSuccess) Unit
            val err = write(EscPos.statusQuery(n))
            if (err != null) { log.add("STATUS DLE EOT $n", StepOutcome.FAIL, err); return } // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            val got = ArrayList<Int>()
            withTimeoutOrNull(replyWaitMs) { while (true) got.add(replies.receive()) }
            if (got.isEmpty()) {
                log.add("STATUS DLE EOT $n", StepOutcome.INFO, "no reply in $replyWaitMs ms") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            } else {
                val b = got.first()
                val meaning = when {
                    !EscPos.isStatusByte(b) -> "not a status byte" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
                    n == 4 && EscPos.paperOut(b) -> "paper OUT" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
                    n == 4 && EscPos.paperNearEnd(b) -> "paper near end" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
                    n == 4 -> "paper present" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
                    else -> "status ok" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
                }
                log.add("STATUS DLE EOT $n", StepOutcome.OK, got.joinToString(" ") { "0x%02X".format(Locale.ROOT, it) } + " ($meaning)") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            }
        }
    }

    /** Sends one test page; logs bytes written, time and outcome. */
    suspend fun testPage(mode: TestMode): TestPageResult {
        val commands = TestPages.commands(mode)
        val total = commands.sumOf { it.size }
        val start = nowMs()
        var sent = 0
        var error: String? = null
        outer@ for (cmd in commands) {
            var off = 0
            while (off < cmd.size) {
                val n = minOf(chunkBytes, cmd.size - off)
                while (true) {
                    val drained = (nowMs() - start) * bytesPerSecond / 1000
                    val backlog = sent - drained
                    if (backlog + n <= bufferBytes) break
                    delay(((backlog + n - bufferBytes) * 1000 / bytesPerSecond).coerceAtLeast(5))
                }
                error = write(cmd.copyOfRange(off, off + n))
                if (error != null) break@outer
                off += n
                sent += n
            }
        }
        val ms = nowMs() - start
        val result = TestPageResult(mode, sent, total, ms, error)
        log.add(
            "TEST ${mode.name}", if (error == null) StepOutcome.OK else StepOutcome.FAIL,
            "${mode.label}: wrote $sent of $total bytes in $ms ms" + (error?.let { ", $it" } ?: ", all bytes accepted by the link"), // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        )
        return result
    }

    private suspend fun write(bytes: ByteArray): String? {
        dropped?.let { return "link dropped: $it" } // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        return try {
            val ok = withTimeoutOrNull(writeTimeoutMs) { transport.write(bytes); true }
            if (ok == null) { transport.close(); "write timed out after $writeTimeoutMs ms" } else dropped?.let { "link dropped: $it" } // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun close() {
        reader?.cancel()
        transport.close()
        replies.close()
    }
}

/** Takes phone numbers and long digit runs out of free text (error messages, device names) before export. */
object Redact {
    // optional +880 / 880 with spaces or dashes, then 01[3-9] and eight more digits with any spaces or dashes between
    private val phone = Regex("""(?<![\d+])(?:(?:\+\s*)?880[\s-]*)?0?1[3-9](?:[\s-]*\d){8}(?!\d)""")
    private val longDigits = Regex("""\d{10,}""")

    /** Bengali digits become Latin first, so a number typed in Bangla is caught too. */
    fun text(s: String): String {
        val latin = buildString(s.length) { for (c in s) append(if (c in '০'..'৯') '0' + (c - '০') else c) }
        return longDigits.replace(phone.replace(latin, "<phone>"), "<number>")
    }
}

/** The plain-text report the owner copies or shares back (no secrets, no phone numbers). */
object DiagnosticsReport {
    fun render(
        env: DiagEnvironment,
        selected: BondedDevice?,
        steps: List<DiagStep>,
        /** The person's answer per test page: true printed readable, false nothing or garbage, absent not asked. */
        paper: Map<TestMode, Boolean>,
        generatedAt: String,
    ): String = buildString {
        appendLine("ARON PRINTER DIAGNOSTICS (D-PRINT-02)") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        appendLine("Generated: $generatedAt")
        appendLine("App: ${env.appVersion}")
        appendLine("Phone: ${env.manufacturer} ${env.model}, Android ${env.androidRelease} (SDK ${env.sdkInt})")
        appendLine("Bluetooth: " + if (!env.bluetoothSupported) "NOT SUPPORTED on this phone" else "adapter ${env.adapterState}") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        appendLine("Permissions: " + if (env.permissions.isEmpty()) "none needed at runtime on this Android" else env.permissions.entries.joinToString(", ") { "${it.key}=${if (it.value) "granted" else "DENIED"}" }) // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        appendLine("Saved printer in app: ${env.savedPrinter?.let(Redact::text) ?: "none"}") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        when (val b = env.bonded) {
            null -> appendLine("Paired devices: not readable (${env.bondedError?.let(Redact::text) ?: "unknown reason"})") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            else -> {
                appendLine("Paired devices (${b.size}):") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
                b.forEachIndexed { i, d -> appendLine("  ${i + 1}. ${describe(d)}") }
            }
        }
        appendLine("Printer under test: ${selected?.let { describe(it, full = true) } ?: "none chosen"}") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        appendLine("Steps:")
        if (steps.isEmpty()) appendLine("  (none run)") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        for (s in steps) appendLine("  [+%.1fs] %s: %s %s".format(Locale.ROOT, s.atMs / 1000.0, s.name, s.outcome, s.detail))
        appendLine("Paper (as seen by the person holding the printer):") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        for (m in TestMode.entries) appendLine("  ${m.label}: " + when (paper[m]) { true -> "PRINTED OK"; false -> "NOTHING OR GARBAGE"; null -> "not answered" }) // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
    }

    /** [full] (the screen, the device under test) keeps every name: nothing is filtered or hidden there. */
    fun describe(d: BondedDevice, full: Boolean = false): String {
        val cls = when (d.majorClass) {
            null -> "class ?"
            0x0600 -> "class IMAGING (printer)" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            0x0200 -> "class PHONE" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            0x0400 -> "class AUDIO_VIDEO" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            0x1F00 -> "class UNCATEGORIZED" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            else -> "class 0x%04X".format(Locale.ROOT, d.majorClass)
        }
        val type = when (d.type) { 1 -> "CLASSIC"; 2 -> "LE"; 3 -> "DUAL"; 0 -> "UNKNOWN"; else -> "?" }
        // a headset or someone's phone is not the printer: its name and full address stay off the report
        if (!full && d.majorClass != null && d.majorClass != 0x0600 && d.majorClass != 0x1F00 && d.majorClass != 0x0000) {
            return "(other device, name hidden)  ${d.address.take(9)}XX:XX:XX  $cls  type $type" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        }
        return "${Redact.text(d.name ?: "(no name)")}  ${d.address}  $cls  type $type" // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
    }
}
