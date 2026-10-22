package com.aktcl.aron.feature.outlet

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdictResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-MAP-02: the Sale confirm step shows distance, radius, verdict and the map without a tap; offline it shows the panel and the arrow. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VisitGeoTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    private val outlet = VisitOutlet(1, 2, "Rahim Store", "DHK-1", 23.7900, 90.4000, "master", 100, 50)

    /** The phone is north-east of the outlet, so the way to walk is south-west. */
    private val phoneLat = 23.7912; private val phoneLng = 90.4009

    private fun outside(left: Boolean = true, force: Boolean = false) = VisitUiState.NeedsDecision(
        outlet, GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, if (force) GeoAction.FORCE_SALE else GeoAction.REFRESH_OFFERED, 163.0), 1, left, 3, force, false,
        phoneLat = phoneLat, phoneLng = phoneLng,
    )

    private val stub: GeoMapSlot = { _, m -> Text("MAP-STUB", m.fillMaxSize()) }

    @Test fun outsideShowsDistanceRadiusAndVerdictInBanglaWithTheMapStubbed() {
        show(AppLanguage.BN) { VisitCheckContent(outside(), {}, {}, {}, {}, geoPanel = true, mapSlot = stub) }
        compose.onNodeWithText("আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।").assertExists()
        compose.onNodeWithText("সীমার বাইরে: আউটলেট থেকে ১৬৩ মি · সীমা ১০০ মি").assertIsDisplayed()
        compose.onNodeWithTag(GeoPanelTags.MAP_SLOT).assertExists()
        compose.onNodeWithText("MAP-STUB").assertExists()
        compose.onNodeWithText("আউটলেট দক্ষিণ-পশ্চিম দিকে").assertExists()
        compose.onNodeWithTag(OutletTags.MAP).assertExists() // Open in Maps stays, as a secondary link
    }

    @Test fun outsideShowsTheEnglishLine() {
        show(AppLanguage.EN) { VisitCheckContent(outside(), {}, {}, {}, {}, geoPanel = true, mapSlot = stub) }
        compose.onNodeWithText("Outside the range: 163 m from the outlet · limit 100 m").assertIsDisplayed()
        compose.onNodeWithText("The outlet is to the south-west").assertExists()
    }

    @Test fun offlineShowsThePanelAndTheBearingArrowWithNoMapSlot() {
        show(AppLanguage.EN) { VisitCheckContent(outside(), {}, {}, {}, {}, geoPanel = true, mapSlot = null) }
        compose.onNodeWithTag(GeoPanelTags.MAP_SLOT).assertDoesNotExist()
        compose.onNodeWithTag(GeoPanelTags.DISTANCE).assertIsDisplayed()
        compose.onNodeWithTag(GeoPanelTags.ARROW).assertIsDisplayed()
    }

    @Test fun insideRangeShowsTheDistanceWithoutATapOnTheStartStep() {
        val info = VisitGeoInfo.of(outlet, GeoVerdict.IN_RANGE, 42.0, 23.7903, 90.4002)!!
        var started = 0
        show(AppLanguage.BN) {
            VisitCheckContent(VisitUiState.Open(OpenVisit("v1", 1, 2, "in_range", "sale_allowed", true, false, null, "x")), {}, {}, {}, {}, onStartCall = { started++ }, geo = info, mapSlot = stub, geoPanel = true)
        }
        compose.onNodeWithText("আপনি আউটলেটের সীমার মধ্যে আছেন।").assertExists()
        compose.onNodeWithText("সীমার মধ্যে: আউটলেট থেকে ৪২ মি · সীমা ১০০ মি").assertIsDisplayed()
        compose.onNodeWithText("MAP-STUB").assertExists()
        compose.onNodeWithText("কল শুরু করুন").assertExists().performClick()
        assertEquals(1, started)
    }

    @Test fun forceSaleBranchIsUnchanged() {
        var forced = 0
        show(AppLanguage.EN) { VisitCheckContent(outside(left = false, force = true), {}, { forced++ }, {}, {}, mapSlot = stub) }
        compose.onNodeWithTag(OutletTags.FORCE).performClick(); assertEquals(1, forced)
        compose.onNodeWithTag(OutletTags.REFRESH).assertDoesNotExist()
    }

    @Test fun withoutAFixTheTextLineStaysAndThereIsNoPanel() {
        val st = VisitUiState.NeedsDecision(outlet, GeoVerdictResult(GeoVerdict.NO_FIX, GeoAction.REFRESH_OFFERED, null), 0, true, 3, false, false)
        show(AppLanguage.EN) { VisitCheckContent(st, {}, {}, {}, {}, geoPanel = true, mapSlot = stub) }
        compose.onNodeWithTag(GeoPanelTags.PANEL).assertDoesNotExist()
        compose.onNodeWithTag(OutletTags.MAP).assertExists()
    }

    @Test fun forceSoldVisitDoesNotClaimInRangeAndOffersAbandon() {
        val info = VisitGeoInfo.of(outlet, GeoVerdict.OUT_OF_RANGE, 140.0, phoneLat, phoneLng)!!
        var abandoned = 0
        show(AppLanguage.EN) {
            VisitCheckContent(VisitUiState.Open(OpenVisit("v1", 1, 2, "out_of_range", "force_sale", false, true, "location_change", "x")), {}, {}, {}, {}, onStartCall = {}, geo = info, onBack = { abandoned++ })
        }
        compose.onNodeWithText("You are within the outlet's range.").assertDoesNotExist()
        compose.onNodeWithText("Outside the range: 140 m from the outlet · limit 100 m").assertExists()
        compose.onNodeWithText("Go back").performScrollTo().performClick(); assertEquals(1, abandoned)
    }

    @Test fun bearingAndCompass() {
        assertEquals(0.0, VisitGeoInfo.bearing(23.0, 90.0, 24.0, 90.0), 0.01)
        assertEquals(90.0, VisitGeoInfo.bearing(0.0, 90.0, 0.0, 91.0), 0.01)
        assertEquals(180.0, VisitGeoInfo.bearing(24.0, 90.0, 23.0, 90.0), 0.01)
        assertEquals(270.0, VisitGeoInfo.bearing(0.0, 91.0, 0.0, 90.0), 0.01)
        assertEquals(listOf(0, 0, 1, 2, 4, 6, 7, 0), listOf(0.0, 22.0, 45.0, 90.0, 180.0, 270.0, 315.0, 359.0).map(::compassSector))
        val b = VisitGeoInfo.of(outlet, GeoVerdict.OUT_OF_RANGE, null, phoneLat, phoneLng)!!.bearingDeg
        assertNotNull(b); assertEquals(5, compassSector(b!!)) // south-west
        assertEquals(null, VisitGeoInfo.of(outlet.copy(lat = null), GeoVerdict.NO_OUTLET_LOCATION, null, phoneLat, phoneLng))
    }
}
