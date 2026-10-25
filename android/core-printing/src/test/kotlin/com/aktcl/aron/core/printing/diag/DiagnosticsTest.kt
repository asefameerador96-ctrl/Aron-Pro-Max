package com.aktcl.aron.core.printing.diag

import com.aktcl.aron.core.printing.bt.ConnectAttempt
import com.aktcl.aron.core.printing.bt.PrinterTransport
import com.aktcl.aron.core.printing.bt.SimPrinter
import com.aktcl.aron.core.printing.bt.SppMethod
import com.aktcl.aron.core.printing.escpos.EscPos
import com.aktcl.aron.core.printing.raster.MonoBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale

/** D-PRINT-02: the three test pages, the session log and the export. */
class DiagnosticsTest {
    /** Records every byte; never answers. */
    private class Recorder : PrinterTransport {
        val out = ByteArrayOutputStream()
        private val never = CompletableDeferred<Int>()
        override suspend fun connect() {}
        override suspend fun write(bytes: ByteArray) { out.write(bytes) }
        override suspend fun read(): Int = never.await()
        override fun close() { never.completeExceptionally(java.io.IOException("closed")) }
    }

    /** Decodes a stream of `ESC * 33` bands (as a printer would) back into a bitmap. */
    private fun decodeBitImage(bytes: ByteArray, width: Int): MonoBitmap {
        val bands = ArrayList<ByteArray>()
        var i = 0
        while (i < bytes.size) {
            if (bytes[i].toInt() == 0x1B && bytes[i + 1].toInt() == 0x2A) {
                assertEquals(33, bytes[i + 2].toInt())
                val n = (bytes[i + 3].toInt() and 0xFF) or ((bytes[i + 4].toInt() and 0xFF) shl 8)
                assertEquals(width, n)
                bands.add(bytes.copyOfRange(i + 5, i + 5 + 3 * n))
                assertEquals("each band ends with LF", 0x0A, bytes[i + 5 + 3 * n].toInt())
                i += 5 + 3 * n + 1
            } else i++
        }
        val b = MonoBitmap(width, bands.size * 24)
        bands.forEachIndexed { band, d ->
            for (x in 0 until width) for (k in 0 until 3) for (bit in 0 until 8) {
                if (d[3 * x + k].toInt() and (0x80 ushr bit) != 0) b.set(x, band * 24 + k * 8 + bit)
            }
        }
        return b
    }

    @Test fun bitImageBandsRoundTripToThePatternAndSpacingIs24() {
        val p = TestPages.pattern("ARON TEST ESC * 24")
        val bytes = TestPages.commands(TestMode.BIT_IMAGE).reduce { a, b -> a + b }
        assertArrayEquals(EscPos.INIT + EscPos.LINE_SPACING_24, bytes.copyOfRange(0, 5))
        val back = decodeBitImage(bytes, 384)
        assertEquals(5, back.height / 24) // 120 rows = 5 bands
        assertEquals(p, back.rows(0, p.height))
    }

    @Test fun bitImageColumnOrderMsbIsTheTopDot() {
        val b = MonoBitmap(16, 24)
        b.set(0, 0); b.set(1, 7); b.set(2, 8); b.set(3, 23)
        val band = EscPos.bitImage24Band(b, 0)
        assertEquals(5 + 48 + 1, band.size)
        assertEquals(0x80, band[5].toInt() and 0xFF)          // x0: top dot of the first byte
        assertEquals(0x01, band[5 + 3].toInt() and 0xFF)      // x1: 8th dot
        assertEquals(0x80, band[5 + 6 + 1].toInt() and 0xFF)  // x2: 9th dot = MSB of the second byte
        assertEquals(0x01, band[5 + 9 + 2].toInt() and 0xFF)  // x3: 24th dot
        assertEquals(16, band[3].toInt()); assertEquals(0, band[4].toInt())
    }

    @Test fun asciiPageIsPrintableAsciiOnlyAndNoByteStartsACommand() {
        val bytes = EscPos.textJob(listOf("OK ১২৩ \u001B@")).reduce { a, b -> a + b }
        val body = bytes.copyOfRange(2, bytes.size - EscPos.TEAR_OFF.size)
        assertEquals("OK ??? ?@\n", String(body, Charsets.US_ASCII))
        val page = TestPages.commands(TestMode.ASCII).reduce { a, b -> a + b }
        val text = page.copyOfRange(2, page.size - EscPos.TEAR_OFF.size)
        assertTrue(text.all { it.toInt() == 0x0A || it.toInt() in 0x20..0x7E })
        assertTrue(String(text, Charsets.US_ASCII).lines().all { it.length <= 32 })
    }

    @Test fun rasterPageIsTheMemoCommandAndPrintsWholeOnTheSimulatedPrinter() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val link = sim.factory().open("x").also { it.connect() }
        val log = DiagnosticsLog { testScheduler.currentTime }
        val s = DiagnosticsSession(link, backgroundScope, log, nowMs = { testScheduler.currentTime })
        s.start()
        val r = s.testPage(TestMode.RASTER)
        assertNull(r.error)
        assertEquals(r.bytesTotal, r.bytesWritten)
        assertFalse(sim.overflowed)
        val p = TestPages.pattern("ARON TEST GS V 0")
        assertEquals(p.height, sim.sessions.single().size)
        assertArrayEquals(p.data.copyOfRange(0, 48), sim.sessions.single()[0])
        assertTrue(log.steps.single().detail.contains("wrote ${r.bytesTotal} of ${r.bytesTotal} bytes"))
    }

    @Test fun statusProbeLogsTheReplyOrNoReply() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val link = sim.factory().open("x").also { it.connect() }
        val log = DiagnosticsLog { testScheduler.currentTime }
        val s = DiagnosticsSession(link, backgroundScope, log, nowMs = { testScheduler.currentTime })
        s.start()
        s.probeStatus()
        assertEquals(listOf("STATUS DLE EOT 1", "STATUS DLE EOT 2", "STATUS DLE EOT 4"), log.steps.map { it.name })
        assertEquals("0x12 (paper present)", log.steps[2].detail)
        sim.answersStatus = false
        val log2 = DiagnosticsLog { testScheduler.currentTime }
        DiagnosticsSession(link, backgroundScope, log2, nowMs = { testScheduler.currentTime }).also { it.start() }.probeStatus()
        assertTrue(log2.steps.all { it.outcome == StepOutcome.INFO && it.detail == "no reply in 1000 ms" })
    }

    @Test fun aStalledPrinterGivesALoggedTimeoutNotAHang() = runTest {
        val sim = SimPrinter({ testScheduler.currentTime })
        val link = sim.factory().open("x").also { it.connect() }
        sim.hangWrites = true
        val log = DiagnosticsLog { testScheduler.currentTime }
        val s = DiagnosticsSession(link, backgroundScope, log, nowMs = { testScheduler.currentTime })
        s.start()
        val r = s.testPage(TestMode.RASTER)
        assertEquals("write timed out after 5000 ms", r.error)
        assertEquals(0, r.bytesWritten)
        assertEquals(StepOutcome.FAIL, log.steps.single().outcome)
    }

    @Test fun eachModeSendsItsOwnBytes() = runTest {
        for (m in TestMode.entries) {
            val rec = Recorder()
            val s = DiagnosticsSession(rec, backgroundScope, DiagnosticsLog { testScheduler.currentTime }, nowMs = { testScheduler.currentTime })
            val r = s.testPage(m)
            assertArrayEquals(TestPages.commands(m).reduce { a, b -> a + b }, rec.out.toByteArray())
            assertEquals(rec.out.size(), r.bytesWritten)
        }
    }

    @Test fun reportHasEveryFactNoFilterAndNoPhoneNumbers() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("bn-BD-u-nu-beng"))
        try {
            var t = 0L
            val log = DiagnosticsLog { t }
            t = 1500
            log.connect(listOf(ConnectAttempt(SppMethod.SECURE, "IOException: read failed, socket might closed or timeout, read ret: -1", 1203), ConnectAttempt(SppMethod.INSECURE, null, 640)))
            log.add("TEST RASTER", StepOutcome.OK, "call 01712345678 or +8801812-345678 ok")
            val env = DiagEnvironment(
                "samsung", "SM-A065F", "14", 34, "sr 1.0 (1258)", true, "ON",
                mapOf("BLUETOOTH_CONNECT" to false, "BLUETOOTH_SCAN" to true),
                listOf(
                    BondedDevice("MTP-II", "66:22:11:00:AA:01", 0x0600, 1),
                    BondedDevice(null, "66:22:11:00:AA:02", 0x1F00, 1),
                    BondedDevice("Rahim 01911223344", "66:22:11:00:AA:03", 0x0200, 3),
                ),
                savedPrinter = "MP-58N 66:22:11:00:AA:04",
            )
            val text = DiagnosticsReport.render(env, env.bonded!![0], log.steps, mapOf(TestMode.ASCII to true, TestMode.RASTER to false), "2026-10-22 15:04 +06:00")
            for (want in listOf(
                "Phone: samsung SM-A065F, Android 14 (SDK 34)", "Bluetooth: adapter ON", "BLUETOOTH_CONNECT=DENIED", "BLUETOOTH_SCAN=granted",
                "Paired devices (3):", "MTP-II  66:22:11:00:AA:01  class IMAGING (printer)  type CLASSIC", "(no name)  66:22:11:00:AA:02",
                "[+1.5s] CONNECT SECURE: FAIL IOException: read failed, socket might closed or timeout, read ret: -1 (1203 ms)",
                "CONNECT INSECURE: OK connected (640 ms)", "Printer under test: MTP-II", "ASCII self-test: PRINTED OK",
                "GS v 0 raster: NOTHING OR GARBAGE", "ESC * 24-dot: not answered", "App: sr 1.0 (1258)",
            )) assertTrue("missing <$want> in\n$text", text.contains(want))
            assertFalse(text, Regex("""01[3-9]\d{8}""").containsMatchIn(text))
            assertTrue(text.contains("call <phone> or <phone> ok"))
            assertFalse("another person's phone is not named", text.contains("Rahim"))
            assertTrue(text.contains("(other device, name hidden)  66:22:11:XX:XX:XX  class PHONE"))
            assertFalse("Latin digits only", text.any { it in '০'..'৯' })
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun redactionCatchesSeparatorsCountryCodeAndBengaliDigits() {
        for (n in listOf("01712345678", "+8801712345678", "+880 1712-345678", "880-1712345678", "017-1234-5678", "01712 345 678", "০১৭১২৩৪৫৬৭৮", "8801712345678")) {
            assertEquals(n, "x <phone> y", Redact.text("x $n y"))
        }
        // a MAC address, an error code and a short count stay
        assertEquals("66:22:11:00:AA:01 read ret: -1 (1203 ms)", Redact.text("66:22:11:00:AA:01 read ret: -1 (1203 ms)"))
    }

    @Test fun theScreenAndTheDeviceUnderTestKeepEveryName() {
        val phone = BondedDevice("Rahim phone", "66:22:11:00:AA:03", 0x0200, 3)
        assertTrue(DiagnosticsReport.describe(phone, full = true).startsWith("Rahim phone  66:22:11:00:AA:03"))
        val env = DiagEnvironment("x", "y", "14", 34, "a", true, "ON", emptyMap(), listOf(phone))
        assertTrue(DiagnosticsReport.render(env, phone, emptyList(), emptyMap(), "now").contains("Printer under test: Rahim phone  66:22:11:00:AA:03"))
    }

    @Test fun reportSaysWhyThePairedListIsMissing() {
        val env = DiagEnvironment("x", "y", "11", 30, "a", true, "OFF", emptyMap(), null, "Bluetooth is off")
        val text = DiagnosticsReport.render(env, null, emptyList(), emptyMap(), "now")
        assertTrue(text.contains("Paired devices: not readable (Bluetooth is off)"))
        assertTrue(text.contains("Permissions: none needed at runtime on this Android"))
        assertTrue(text.contains("(none run)"))
    }

    private val printer = BondedDevice("MP-58N", "66:22:11:00:AA:04", 0x0600, 1)
    private val env = DiagEnvironment("x", "y", "14", 34, "sr 1258", true, "ON", mapOf("BLUETOOTH_CONNECT" to true), listOf(BondedDevice("Buds", "11:22:33:44:55:66", 0x0400, 1), printer))

    @Test fun controllerPicksThePrinterRunsEverythingAndRecordsThePaper() = runTest {
        val rec = Recorder()
        var released = 0
        val c = PrinterDiagnosticsController(
            backgroundScope,
            connect = { DiagConnection(rec, listOf(ConnectAttempt(SppMethod.SECURE, "IOException: refused", 900), ConnectAttempt(SppMethod.INSECURE, null, 400))) },
            releaseAppLink = { released++ },
            nowMs = { testScheduler.currentTime },
        )
        c.setEnvironment(env)
        assertEquals("the printer class is chosen first", printer, c.state.value.selected)
        c.runAll()
        assertEquals(1, released)
        val names = c.state.value.steps.map { it.name }
        assertEquals(listOf("TARGET", "CONNECT SECURE", "CONNECT INSECURE", "STATUS DLE EOT 1", "STATUS DLE EOT 2", "STATUS DLE EOT 4", "TEST RASTER", "TEST BIT_IMAGE", "TEST ASCII"), names)
        assertEquals(TestMode.entries, c.state.value.toJudge)
        assertFalse(c.state.value.busy)
        val all = TestMode.entries.flatMap { TestPages.commands(it) }.reduce { a, b -> a + b }
        val sent = rec.out.toByteArray()
        assertTrue(sent.size > all.size) // the pages plus three status queries
        c.judge(TestMode.ASCII, true); c.judge(TestMode.RASTER, false)
        assertEquals(listOf(TestMode.BIT_IMAGE), c.state.value.toJudge)
        val text = c.report("now")
        assertTrue(text, text.contains("GS v 0 raster: NOTHING OR GARBAGE"))
        assertTrue(text, text.contains("ASCII self-test: PRINTED OK"))
        assertTrue(text, text.contains("CONNECT SECURE: FAIL IOException: refused (900 ms)"))
    }

    @Test fun controllerLogsAFailedConnectAndStaysUsable() = runTest {
        val c = PrinterDiagnosticsController(
            backgroundScope,
            connect = { DiagConnection(null, SppMethod.entries.map { ConnectAttempt(it, "IOException: Host is down", 5000) }) },
            releaseAppLink = {},
            nowMs = { testScheduler.currentTime },
        )
        c.connectOnly()
        assertEquals("CONNECT", c.state.value.steps.single().name) // nothing chosen yet
        c.setEnvironment(env)
        c.testPage(TestMode.RASTER)
        assertEquals(3, c.state.value.steps.count { it.name.startsWith("CONNECT ") && it.outcome == StepOutcome.FAIL })
        assertTrue(c.state.value.toJudge.isEmpty())
        assertFalse(c.state.value.busy)
    }
}
