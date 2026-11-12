package com.aktcl.aron.core.printing.ui

import com.aktcl.aron.core.printing.bt.PrinterState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screenshot 13: the "printer connected" toast fires once, on the way into Connected. */
class PrinterToastTest {
    @Test fun itFiresOnTheTransitionIntoConnected() {
        assertTrue(PrinterToast.shouldToast(PrinterState.Off, PrinterState.Connected))
        assertTrue(PrinterToast.shouldToast(PrinterState.Connecting, PrinterState.Connected))
        assertTrue(PrinterToast.shouldToast(PrinterState.NoPrinter, PrinterState.Connected))
    }

    @Test fun itStaysQuietWhenAlreadyConnectedOrNotConnected() {
        assertFalse(PrinterToast.shouldToast(PrinterState.Connected, PrinterState.Connected))
        assertFalse(PrinterToast.shouldToast(PrinterState.Connected, PrinterState.Off))
        assertFalse(PrinterToast.shouldToast(PrinterState.Off, PrinterState.Connecting))
        // after a print or a paper-out the printer comes back to Connected: no toast
        assertFalse(PrinterToast.shouldToast(PrinterState.Printing("j", 100), PrinterState.Connected))
        assertFalse(PrinterToast.shouldToast(PrinterState.PaperOut, PrinterState.Connected))
    }
}
