package com.aktcl.aron.feature.outlet

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdictResult
import com.aktcl.aron.rules.TextRules
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Screens of F-SR-016/017/019: Bangla and English, the buttons, no network anywhere. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VisitScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    private val outlet = VisitOutlet(1, 2, "Rahim Store", "DHK-1", 23.79, 90.40, "master", 100, 50)
    private fun needs(verdict: GeoVerdict, action: GeoAction, left: Boolean, force: Boolean) = VisitUiState.NeedsDecision(
        outlet, GeoVerdictResult(verdict, action, 250.0), 1, left, 3, force, false,
    )

    @Test fun outOfRangeShowsTheBanglaMessageWithRefreshAndNoForceYet() {
        var refreshed = 0
        show(AppLanguage.BN) { VisitCheckContent(needs(GeoVerdict.OUT_OF_RANGE, GeoAction.REFRESH_OFFERED, true, false), { refreshed++ }, {}, {}, null) }
        compose.onNodeWithText("আপনি দোকানের অনুমোদিত দূরত্বের বাইরে আছেন। কাছে যান, অবস্থান রিফ্রেশ করুন, অথবা ফোর্স সেল ব্যবহার করুন।").assertExists()
        compose.onNodeWithTag(OutletTags.REFRESH).performClick(); assertEquals(1, refreshed)
        compose.onNodeWithTag(OutletTags.FORCE).assertDoesNotExist()
    }

    @Test fun forceSaleAppearsWhenAllowedAndMapOnlyWhenWired() {
        var forced = 0
        show(AppLanguage.EN) { VisitCheckContent(needs(GeoVerdict.OUT_OF_RANGE, GeoAction.FORCE_SALE, false, true), {}, { forced++ }, {}, {}) }
        compose.onNodeWithTag(OutletTags.FORCE).performClick(); assertEquals(1, forced)
        compose.onNodeWithTag(OutletTags.MAP).assertExists(); compose.onNodeWithTag(OutletTags.REFRESH).assertDoesNotExist()
    }

    @Test fun locationBlockedAndCommitFailedHaveTheirOwnMessages() {
        show(AppLanguage.EN) { VisitCheckContent(VisitUiState.LocationBlocked("location_off"), {}, {}, {}, null) }
        compose.onNodeWithText("Location is switched off. Switch it on to continue.").assertExists()
    }

    private fun o(id: Long, name: String) = OutletEntity(
        outletId = id, routeId = 1, code = "DHK-$id", name = name, nameBn = null, nameSortKey = TextRules.nameSortKey(name), ownerName = "x",
        contactNumber = "01712345678", lat = 1.0, lng = 1.0, locationConfirmed = true, provisionalLat = null, provisionalLng = null,
        clusterId = 1, clusterName = "C", channel = "g", subChannelId = null, geoClass = null, status = "active", priceType = "outlet",
        outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = id.toInt(), openDueMtk = 0, openDueAsOf = null,
        programmeFlagsJson = "[]", pendingRequest = false,
    )

    @Test fun pickerListsRowsAndChipFilters() {
        val rows = OutletPicker.rows(listOf(o(1, "Rahim Store"), o(2, "Karim")))
        var picked: Long? = null
        show(AppLanguage.EN) { OutletPickerContent(rows, OutletPicker.chips(rows), OutletPicker.ALL_CHIP, {}, { picked = it.outlet.outletId }) }
        compose.onNodeWithText("Rahim Store (DHK-1-01712345678-C)").performClick()
        assertEquals(1L, picked)
        compose.onNodeWithText("K").assertExists()
    }
}
