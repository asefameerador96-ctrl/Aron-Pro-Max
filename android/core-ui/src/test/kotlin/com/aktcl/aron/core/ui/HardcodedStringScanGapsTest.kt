package com.aktcl.aron.core.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** Hard-coded strings the first scanner missed, found by the independent checker of F-SYS-018; all must be caught. */
class HardcodedStringScanGapsTest {
    private fun assertCaught(src: String) {
        val v = HardcodedStringScanner.scanKotlin("Gap.kt", "package x\n@Composable fun S() {\n$src\n}")
        assertTrue("not caught:\n$src", v.isNotEmpty())
    }

    @Test fun literalAfterConcatenation() = assertCaught("""Text(qty.toString() + " sticks")""")
    @Test fun resourcePlusLiteral() = assertCaught("""Text(stringResource(R.string.home_logout) + " (offline)")""")
    @Test fun ifElseInsideText() = assertCaught("""Button(onClick = {}) { Text(text = if (started) "End day" else "Start day") }""")
    @Test fun positionalIconContentDescription() = assertCaught("""Icon(Icons.Default.Close, "Close dialog")""")
    @Test fun positionalImageContentDescription() = assertCaught("""Image(painterResource(R.drawable.logo), "Company logo")""")
    @Test fun rawStringInText() = assertCaught("Text(\"\"\"Memo printed\"\"\")")
    @Test fun constantThenText() = assertCaught("""const val TITLE = "Log in to continue"
Text(TITLE)""")
    @Test fun valThenText() = assertCaught("""val msg = "Printer not connected"
Text(msg)""")
    @Test fun listOfTabLabels() = assertCaught("""val tabs = listOf("Visits", "Sales", "Dues")""")
    @Test fun noSaleReasonsArray() = assertCaught("""val reasons = arrayOf("Shop closed", "Owner absent")""")
    @Test fun annotatedString() = assertCaught("""Text(AnnotatedString("Memo printed"))""")
    @Test fun buildAnnotatedStringAppend() = assertCaught("""Text(buildAnnotatedString { append("Due amount: ") })""")
    @Test fun basicText() = assertCaught("""BasicText("Basic label")""")
    @Test fun viewModelErrorState() = assertCaught("""_state.update { it.copy(error = "Invalid quantity") }""")
    @Test fun toastWithConcatenation() = assertCaught("""Toast.makeText(ctx, getString(R.string.a) + " saved", Toast.LENGTH_SHORT).show()""")
    @Test fun viewSnackbar() = assertCaught("""Snackbar.make(view, "Sync failed", Snackbar.LENGTH_LONG).show()""")

    /** An unrelated comment that merely mentions the marker must not silence the line. */
    @Test fun optOutNeedsTheRealMarker() = assertCaught("""Text(text = "Retry later") // TODO: this is not i18n-ignore material""")
}
