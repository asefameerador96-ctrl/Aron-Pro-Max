package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.printing.bt.PrinterState
import com.aktcl.aron.core.printing.ui.PrinterBarMillis
import com.aktcl.aron.core.printing.ui.PrinterBarHost
import com.aktcl.aron.core.printing.ui.PrinterConnectedToast
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P-SR1b: the SR's printer-connected bar (manual 17). It shows on Connecting/Off to Connected, goes after [PrinterBarMillis] whatever the printer does meanwhile
 * (Printing, Off), and takes no touch: a control under it still works. The SR frame draws the bar from the flag the toast sets (SrFrame); this test builds the same pair.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PrinterBarBehaviourTest {
    @get:Rule val compose = createComposeRule()
    private val label = "Printer connected"

    /** The real wiring: PrinterBarHost (what SrFrame wraps its screen in) around a screen that holds the toast and a control in the middle. */
    private fun host(states: MutableStateFlow<PrinterState>, taps: IntArray = IntArray(1)) {
        compose.setContent {
            CompositionLocalProvider(com.aktcl.aron.core.ui.LocalSrManualLook provides true) {
                PrinterBarHost(Modifier.fillMaxSize()) {
                    PrinterConnectedToast(states)
                    Box(Modifier.fillMaxSize()) { Text("under", Modifier.align(Alignment.Center).testTag("under").clickable { taps[0]++ }) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun showsOnConnectedAndGoesAfterTheTime() {
        val s = MutableStateFlow<PrinterState>(PrinterState.Off)
        host(s)
        s.value = PrinterState.Connected; compose.waitForIdle()
        compose.onNodeWithText(label).assertExists()
        compose.mainClock.advanceTimeBy(PrinterBarMillis + 200); compose.waitForIdle()
        compose.onNodeWithText(label).assertDoesNotExist()
    }

    @Test fun connectedThenPrintingWithinTheTimeStillGoes() {
        val s = MutableStateFlow<PrinterState>(PrinterState.Connecting)
        host(s)
        s.value = PrinterState.Connected; compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800); s.value = PrinterState.Printing("j", 10); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(PrinterBarMillis); compose.waitForIdle()
        compose.onNodeWithText(label).assertDoesNotExist()
    }

    @Test fun connectedThenOffWithinTheTimeStillGoesAndTheNextConnectShowsAgain() {
        val s = MutableStateFlow<PrinterState>(PrinterState.Connecting)
        host(s)
        s.value = PrinterState.Connected; compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500); s.value = PrinterState.Off; compose.waitForIdle()
        compose.mainClock.advanceTimeBy(PrinterBarMillis); compose.waitForIdle()
        compose.onNodeWithText(label).assertDoesNotExist()
        s.value = PrinterState.Connected; compose.waitForIdle()
        compose.onNodeWithText(label).assertExists()
    }

    @Test fun aTapOnAControlUnderTheBarWorks() {
        val s = MutableStateFlow<PrinterState>(PrinterState.Off)
        val taps = IntArray(1)
        host(s, taps)
        s.value = PrinterState.Connected; compose.waitForIdle()
        compose.onNodeWithText(label).assertExists()
        compose.onNodeWithTag("under").performClick()
        assertEquals(1, taps[0])
    }
}
