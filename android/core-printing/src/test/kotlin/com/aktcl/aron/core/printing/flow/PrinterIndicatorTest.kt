package com.aktcl.aron.core.printing.flow

import com.aktcl.aron.core.printing.R
import com.aktcl.aron.core.printing.bt.PrinterState
import com.aktcl.aron.core.printing.ui.PrinterIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-013: green with "প্রিন্টার কানেক্ট করা হয়েছে" when linked; red and slashed when not. */
class PrinterIndicatorTest {
    @Test fun mapping() {
        val c = PrinterIndicator.of(PrinterState.Connected)
        assertTrue(c.green); assertFalse(c.slashed); assertEquals(R.string.printer_connected, c.banner)
        for (s in listOf(PrinterState.Off, PrinterState.NoPrinter, PrinterState.PaperOut)) {
            val i = PrinterIndicator.of(s)
            assertFalse(i.green); assertTrue(i.slashed)
        }
        assertTrue(PrinterIndicator.of(PrinterState.Printing("j", 40)).green)
        assertTrue(PrinterIndicator.of(PrinterState.Connecting).busy)
    }
}
