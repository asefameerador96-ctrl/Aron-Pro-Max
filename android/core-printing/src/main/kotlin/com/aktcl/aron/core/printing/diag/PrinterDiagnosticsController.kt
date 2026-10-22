package com.aktcl.aron.core.printing.diag

import com.aktcl.aron.core.printing.bt.ConnectAttempt
import com.aktcl.aron.core.printing.bt.PrinterTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A connect try: the open link (null when every method failed) and the methods tried with their errors. */
class DiagConnection(val transport: PrinterTransport?, val attempts: List<ConnectAttempt>, val error: String? = null)

/**
 * The Printer diagnostics screen's logic (D-PRINT-02), free of Android so it is unit-tested: choose a paired device,
 * connect (secure, insecure, channel 1, each logged), probe the status, print the three test pages, record what the
 * person saw on paper, and produce the export. One action at a time; each opens its own link and closes it.
 */
class PrinterDiagnosticsController(
    private val scope: CoroutineScope,
    /** Opens a link to [address] trying every method; must not throw. */
    private val connect: suspend (address: String) -> DiagConnection,
    /** Frees the app's own printer link first (a printer accepts one RFCOMM link at a time). */
    private val releaseAppLink: suspend () -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pauseBetweenPagesMs: Long = 1_500,
) {
    data class State(
        val env: DiagEnvironment? = null,
        val selected: BondedDevice? = null,
        val steps: List<DiagStep> = emptyList(),
        val busy: Boolean = false,
        val paper: Map<TestMode, Boolean> = emptyMap(),
        /** Test pages sent in full whose paper the person has not judged yet. */
        val toJudge: List<TestMode> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val log = DiagnosticsLog(nowMs)
    private val running = Mutex()

    fun setEnvironment(env: DiagEnvironment) = _state.update { s ->
        val keep = s.selected?.takeIf { sel -> env.bonded.orEmpty().any { it.address == sel.address } }
        s.copy(env = env, selected = keep ?: env.bonded?.firstOrNull { it.majorClass == 0x0600 } ?: env.bonded?.firstOrNull())
    }

    fun select(device: BondedDevice) = _state.update { it.copy(selected = device) }

    fun judge(mode: TestMode, printed: Boolean) = _state.update { it.copy(paper = it.paper + (mode to printed), toJudge = it.toJudge - mode) }

    /** Connect only: shows which method works and the exact error of each that does not. */
    suspend fun connectOnly() = withLink { link -> link.close() }

    /** One test page. */
    suspend fun testPage(mode: TestMode) = withLink { link ->
        val s = DiagnosticsSession(link, scope, log, nowMs)
        s.start()
        try { sent(s.testPage(mode)) } finally { s.close() }
    }

    /** Everything in one go: connect, status, the three pages with a pause between them. */
    suspend fun runAll() = withLink { link ->
        val s = DiagnosticsSession(link, scope, log, nowMs)
        s.start()
        try {
            s.probeStatus()
            for ((i, m) in TestMode.entries.withIndex()) {
                if (i > 0) delay(pauseBetweenPagesMs)
                sent(s.testPage(m))
            }
        } finally {
            s.close()
        }
    }

    private fun sent(r: TestPageResult) {
        if (r.error == null) _state.update { it.copy(toJudge = it.toJudge - r.mode + r.mode, paper = it.paper - r.mode) }
    }

    private suspend fun withLink(body: suspend (PrinterTransport) -> Unit) {
        val target = state.value.selected
        if (target == null) {
            log.add("CONNECT", StepOutcome.FAIL, "no paired device chosen") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
            publish()
            return
        }
        if (!running.tryLock()) return
        try {
            _state.update { it.copy(busy = true) }
            try { releaseAppLink() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            log.add("TARGET", StepOutcome.INFO, DiagnosticsReport.describe(target, full = true))
            val c = connect(target.address)
            log.connect(c.attempts)
            c.error?.let { log.add("CONNECT", StepOutcome.FAIL, it) }
            publish()
            val link = c.transport ?: return
            try { body(link) } finally { link.close() }
        } finally {
            _state.update { it.copy(busy = false, steps = log.steps) }
            running.unlock()
        }
    }

    private fun publish() = _state.update { it.copy(steps = log.steps) }

    /** The export text (copy to the clipboard or share). */
    fun report(generatedAt: String): String {
        val s = state.value
        val env = s.env ?: DiagEnvironment("?", "?", "?", 0, "?", false, "UNKNOWN", emptyMap(), null, "not read yet") // i18n-ignore: technical diagnostics text, English by design (D-PRINT-02 report for the lead)
        return DiagnosticsReport.render(env, s.selected, log.steps, s.paper, generatedAt)
    }
}
