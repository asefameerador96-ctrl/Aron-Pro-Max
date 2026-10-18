package com.aktcl.aron.feature.tso

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The audit itself must be able to fail: a small unnamed control is reported twice, a proper one is clean. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class A11yAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reportsASmallUnnamedControl() {
        compose.setContent { Box(Modifier.size(24.dp).clickable { }) }
        val p = compose.a11yProblems()
        assertTrue(p.toString(), p.any { "too small" in it } && p.any { "without a name" in it })
    }

    @Test fun acceptsANamedFullSizeControl() {
        compose.setContent {
            Box(Modifier.size(48.dp).clickable { }.semantics { contentDescription = "Close" })
            Box(Modifier.size(56.dp).clickable { }) { Text("Save") }
        }
        assertEquals(emptyList<String>(), compose.a11yProblems())
    }
}
