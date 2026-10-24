package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.Fixtures
import com.aktcl.aron.core.printing.PaperRenderer
import com.aktcl.aron.core.printing.bt.PrintFailure
import com.aktcl.aron.core.printing.bt.PrintJob
import com.aktcl.aron.core.printing.bt.PrintOutcome
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.PrinterState
import com.aktcl.aron.core.printing.bt.PrinterTransport
import com.aktcl.aron.core.printing.bt.PrinterTransportFactory
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.bt.SimPrinter
import com.aktcl.aron.core.printing.diag.CrashLog
import com.aktcl.aron.core.printing.diag.DiagnosticsLog
import com.aktcl.aron.core.printing.diag.DiagnosticsSession
import com.aktcl.aron.core.printing.diag.TestMode
import com.aktcl.aron.core.printing.ui.PrintFailureText
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

/**
 * Owner P0 2026-10-24: the SR app closed when Print was tapped with the MP-58N connected. Whatever the printer, the
 * Bluetooth stack, storage or the renderer throws (an Error too), no exception may leave the print path: the seller
 * gets a message, the memo stays saved and reprintable, the trace is kept for the diagnostics export.
 */
class PrintCrashProofTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun log() = CrashLog.use(tmp.root, "test+1")

    @After fun reset() = CrashLog.use(null, "?")

    private class Store(var p: SavedPrinter? = SavedPrinter("00:11:22:33:44:55", "MP-58N")) : SavedPrinterStore {
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer }
    }

    /** A link that behaves like the simulated MP-58N until told to throw what an OEM Bluetooth stack throws. */
    private class Faulty(private val inner: PrinterTransport) : PrinterTransport {
        var onConnect: Throwable? = null
        var onRead: Throwable? = null
        var onWrite: Throwable? = null
        var onClose: Throwable? = null
        override suspend fun connect() { onConnect?.let { throw it }; inner.connect() }
        override suspend fun write(bytes: ByteArray) { onWrite?.let { throw it }; inner.write(bytes) }
        override suspend fun read(): Int { onRead?.let { throw it }; return inner.read() }
        override fun close() { inner.close(); onClose?.let { throw it } }
    }

    private fun TestScope.manager(sim: SimPrinter, setup: (Faulty) -> Unit = {}, store: Store = Store()): Pair<PrinterManager, () -> Faulty?> {
        var last: Faulty? = null
        val inner = sim.factory()
        val factory = PrinterTransportFactory { a -> Faulty(inner.open(a)).also { setup(it); last = it } }
        return PrinterManager(factory, store, backgroundScope, nowMs = { testScheduler.currentTime }) to { last }
    }

    private fun lastError(): String = File(tmp.root, "last_print_error.txt").takeIf { it.isFile }?.readText().orEmpty()

    private val memoUuid = "11111111-1111-4111-8111-111111111111"

    private fun TestScope.printing(pm: PrinterManager, renderer: () -> PaperRenderer = { Fixtures.renderer() }, ledger: PrintLedger = PrintFlowTest.MemLedger()) =
        MemoPrinting(pm, renderer, ledger, { java.util.UUID.randomUUID().toString() }, { testScheduler.currentTime }, renderContext = kotlin.coroutines.EmptyCoroutineContext)

    @Test fun aRuntimeExceptionFromReadIsALinkDropNotACrash() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        // the OEM case: read on a socket closed under it throws a NullPointerException, not an IOException
        val (m, _) = manager(sim, { it.onRead = NullPointerException("mSocketIS") })
        assertTrue(m.connect())
        runCurrent()
        assertEquals(PrinterState.Off, m.state.value)
        assertTrue(lastError(), "reader" in lastError() && "NullPointerException" in lastError())
        assertFalse("the last-resort handler was not needed", "printer-link" in lastError())
    }

    @Test fun aRuntimeExceptionFromOpenOrConnectIsUnreachableNotACrash() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val (m, _) = manager(sim, { it.onConnect = IllegalStateException("BT stack") })
        val release = m.hold() // the screen's hold connects in the background: before, this ended the app
        runCurrent()
        assertEquals(PrinterState.Off, m.state.value)
        assertEquals(PrintOutcome.Failed(PrintFailure.UNREACHABLE), m.print(PrintJob("j1", Fixtures.renderer().memo(Fixtures.seededSale).bitmap)))
        assertTrue(lastError(), "connect" in lastError() && "IllegalStateException" in lastError())
        release()

        val throwingFactory = PrinterTransportFactory { throw IllegalArgumentException("bad address") }
        val m2 = PrinterManager(throwingFactory, Store(), backgroundScope, nowMs = { testScheduler.currentTime })
        assertFalse(m2.connect())
        assertEquals(PrinterState.Off, m2.state.value)
    }

    @Test fun anErrorWhileWritingNeverLeavesPrint() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val (m, link) = manager(sim)
        assertTrue(m.connect())
        link()!!.onWrite = OutOfMemoryError("write") // before: only Exception was caught, an Error ended the app
        val out = m.print(PrintJob("j1", Fixtures.renderer().memo(Fixtures.seededSale).bitmap))
        assertEquals(PrintOutcome.Failed(PrintFailure.DISCONNECTED), out)
        assertEquals("the indicator shows the printer as not connected", PrinterState.Off, m.state.value)
    }

    @Test fun aCloseThatThrowsIsSwallowed() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val (m, link) = manager(sim, { it.onClose = IllegalStateException("close race") })
        assertTrue(m.connect())
        link()!!.onWrite = java.io.IOException("broken pipe")
        assertEquals(PrintOutcome.Failed(PrintFailure.DISCONNECTED), m.print(PrintJob("j1", Fixtures.renderer().memo(Fixtures.seededSale).bitmap)))
        m.shutdown()
        runCurrent()
        assertEquals(PrinterState.Off, m.state.value)
    }

    @Test fun aRendererErrorIsAFailedPrintAndTheMemoStaysReprintable() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val (m, _) = manager(sim)
        val ledger = PrintFlowTest.MemLedger()
        var broken = true
        val p = printing(m, { if (broken) throw NoClassDefFoundError("shrunk away") else Fixtures.renderer() }, ledger)
        assertEquals(PrintAttempt.Failed(PrintFailure.INTERNAL), p.printMemo(memoUuid, Fixtures.seededSale))
        assertEquals(PrintAttempt.Failed(PrintFailure.INTERNAL), p.printStockSlip(memoUuid, Fixtures.stockSlip))
        assertEquals(PrintAttempt.Failed(PrintFailure.INTERNAL), p.printDaySummary(memoUuid, Fixtures.daySummary))
        assertTrue(lastError(), "where: print" in lastError() && "NoClassDefFoundError" in lastError())
        assertTrue("nothing counted", ledger.events.none { ReprintPolicy.isCopy(it) })
        broken = false
        val again = p.printMemo(memoUuid, Fixtures.seededSale)
        assertTrue("the next print is the original, whole: $again", again is PrintAttempt.AwaitingConfirmation)
        assertEquals("memo", (again as PrintAttempt.AwaitingConfirmation).pending.documentKind)
    }

    @Test fun aStorageErrorIsAFailedPrintNotACrash() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val (m, _) = manager(sim)
        val ledger = object : PrintLedger {
            override suspend fun history(documentClientUuid: String): List<PrintEvent> = throw StackOverflowError("query")
            override suspend fun pending(): List<PendingPrint> = emptyList()
            override suspend fun savePending(job: PendingPrint) = Unit
            override suspend fun record(event: PrintEvent) = Unit
        }
        assertEquals(PrintAttempt.Failed(PrintFailure.INTERNAL), printing(m, ledger = ledger).printMemo(memoUuid, Fixtures.seededSale))
    }

    @Test fun saveAndPrintShowsTheFailureAndKeepsTheSale() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val (m, _) = manager(sim)
        val p = printing(m, { throw OutOfMemoryError("raster") })
        val flow = SaveAndPrint({ CommittedMemo(memoUuid, Fixtures.seededSale) }, p)
        flow.onPrintTapped()
        flow.onSaveAnswer(true)
        flow.onPrintAnswer(true)
        assertEquals(SaveAndPrint.Step.PrintFailed(PrintFailure.INTERNAL), flow.step.value)
        assertNotNull("the sale stays saved", flow.memo)

        // a commit that throws an Error is "save failed", never the end of the app
        val broken = SaveAndPrint({ throw ExceptionInInitializerError("dao") }, p)
        broken.onPrintTapped()
        broken.onSaveAnswer(true)
        assertEquals(SaveAndPrint.Step.SaveFailed, broken.step.value)
    }

    @Test fun renderingNeverRunsOnTheCallersThread() = runTest {
        // the sale screen calls from the main thread: a cold render there is seconds of frozen screen (an ANR) on a 2 GB phone
        val main = Executors.newSingleThreadExecutor { Thread(it, "main-sim") }.asCoroutineDispatcher()
        try {
            var renderedOn: String? = null
            val pm = PrinterManager(SimPrinter({ 0L }).factory(), Store(null), backgroundScope)
            val p = MemoPrinting(pm, { renderedOn = Thread.currentThread().name; Fixtures.renderer() }, PrintFlowTest.MemLedger(), { java.util.UUID.randomUUID().toString() }, { 0L })
            val out = withContext(main) { p.printMemo(memoUuid, Fixtures.seededSale) }
            assertEquals(PrintAttempt.Failed(PrintFailure.NO_PRINTER), out)
            assertNotNull(renderedOn)
            assertNotEquals("main-sim", renderedOn)
        } finally {
            main.close()
        }
    }

    @Test fun aDiagnosticsLinkThatThrowsIsLoggedNotACrash() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val link = Faulty(sim.factory().open("00:11:22:33:44:55"))
        link.connect()
        link.onRead = NullPointerException("mSocketIS")
        link.onWrite = IllegalStateException("closed")
        val log = DiagnosticsLog { testScheduler.currentTime }
        val s = DiagnosticsSession(link, backgroundScope, log, { testScheduler.currentTime })
        s.start()
        runCurrent()
        val r = s.testPage(TestMode.ASCII)
        assertNotNull(r.error)
        link.onClose = IllegalStateException("close race")
        s.close()
        assertTrue(log.steps.any { it.name == "TEST ASCII" })
    }

    @Test fun everyFailureHasAMessageInBothLanguages() {
        for (r in PrintFailure.entries) {
            val id = PrintFailureText.of(r)
            val name = com.aktcl.aron.core.printing.R.string::class.java.fields.first { it.getInt(null) == id }.name
            assertTrue("$name in Bangla", Fixtures.bn[name].orEmpty().isNotBlank())
            assertTrue("$name in English", Fixtures.en[name].orEmpty().isNotBlank())
        }
        assertTrue(Fixtures.bn["ui_print_failed_doc"].orEmpty().isNotBlank())
    }
}
