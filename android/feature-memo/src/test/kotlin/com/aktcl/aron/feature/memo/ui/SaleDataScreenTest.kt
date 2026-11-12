package com.aktcl.aron.feature.memo.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.memo.domain.HistoryDay
import com.aktcl.aron.feature.memo.domain.HistorySkuRow
import com.aktcl.aron.feature.memo.domain.SaleHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SR manual s8 / 23 (D-SR-MAN-S4-04): "সেল ডাটা" with a date field, the ISO date, the SKU table and a total; future dates are not selectable. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SaleDataScreenTest {
    @get:Rule val compose = createComposeRule()

    private val day = HistoryDay("2026-11-10", emptyList(), 325_000, listOf(HistorySkuRow(1, 20, 130_000, 20, "pack"), HistorySkuRow(2, 10, 65_000, 10, "pack"), HistorySkuRow(3, 20, 130_000, 20, "pack")))

    @Test fun showsTheDateTheIsoDateTheTableAndTheValueTotal() {
        compose.setContent { AronTheme(AppLanguage.EN) { SaleDataScreen(SaleHistory(listOf(day), 325_000, false), "2026-11-11", "Ruku Store (2681268)", false, skuName = { "SKU$it" }) } }
        compose.onNodeWithText("November 10, 2026").assertExists()
        compose.onNodeWithTag(SaleDataTags.ISO).assertExists()
        compose.onNodeWithText("Ruku Store (2681268)").assertExists()
        compose.onNodeWithTag(SaleDataTags.TABLE).assertExists()
        compose.onNodeWithText("325.00").assertExists() // the total is the sum of the rows: 130.00 + 65.00 + 130.00
    }

    @Test fun aDateWithNoSaleSaysSo() {
        compose.setContent { AronTheme(AppLanguage.EN) { SaleDataScreen(SaleHistory(emptyList(), 0, false), "2026-11-11", null, false) } }
        compose.onNodeWithTag(SaleDataTags.EMPTY).assertExists()
    }

    @Test fun futureDatesAreNeverSelectable() {
        assertTrue(saleDataSelectable("2026-11-11", "2026-11-11")); assertTrue(saleDataSelectable("2026-11-10", "2026-11-11"))
        assertFalse(saleDataSelectable("2026-11-12", "2026-11-11")); assertFalse(saleDataSelectable("2027-01-01", "2026-11-11"))
        assertEquals("November 10, 2026", longDate("2026-11-10"))
        // older than the local window: disabled in the picker instead of reading "no sale"
        assertTrue(saleDataSelectable("2026-11-05", "2026-11-11", oldest = "2026-11-05"))
        assertFalse(saleDataSelectable("2026-11-04", "2026-11-11", oldest = "2026-11-05"))
    }
}
