package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.printing.bt.PrintFailure
import com.aktcl.aron.core.printing.flow.PrintAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** A throwing print mapping (stock slip or day summary) cannot escape the SR and AMO day flows. */
class PrintGuardTest {
    @Test fun aThrowingPrintStepBecomesAnInternalFailure() = runBlocking {
        assertEquals(PrintAttempt.Failed(PrintFailure.INTERNAL), guardedPrint { error("mapping blew up") })
        assertEquals(PrintAttempt.Failed(PrintFailure.INTERNAL), guardedPrint { throw IllegalStateException() })
    }

    @Test fun anAttemptPassesThroughUnchanged() = runBlocking {
        val failed = PrintAttempt.Failed(PrintFailure.PAPER_OUT)
        assertEquals(failed, guardedPrint { failed })
    }

    @Test fun cancellationIsNotSwallowed() {
        try {
            runBlocking { guardedPrint { throw CancellationException("screen gone") } }
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("screen gone", e.message)
        }
    }
}
