package com.aktcl.aron.feature.outlet

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.LocalSrManualLook
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdictResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P-SR1b fix round: the SR's out-of-range page (triangle, sentence, ফোর্স সেল and রিফ্রেশ on the page) is drawn only inside the SR app
 * (LocalSrManualLook). The AMO control call passes geoPanel = true too and keeps what it drew before: the verdict card and the bottom bar
 * (Refresh, and the host's own label for Force Sale, "Manual Override").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VisitSrLayoutGateTest {
    @get:Rule val compose = createComposeRule()
    private val outlet = VisitOutlet(1, 2, "Rahim Store", "DHK-1", 23.7900, 90.4000, "master", 100, 50)
    private fun outside(force: Boolean = true, left: Boolean = true) = VisitUiState.NeedsDecision(
        outlet, GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, GeoAction.REFRESH_OFFERED, 163.0), 1, left, 3, force, false, phoneLat = 23.7912, phoneLng = 90.4009,
    )

    private fun show(sr: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx, LocalSrManualLook provides sr) { AronTheme(AppLanguage.EN) { content() } } }
        compose.waitForIdle()
    }

    @Test fun theAmoControlCallKeepsItsVerdictCardAndBottomBarWithTheHostLabel() {
        var forced = 0; var refreshed = 0
        show(sr = false) {
            VisitCheckContent(outside(), { refreshed++ }, { forced++ }, {}, {}, geoPanel = true, texts = VisitCheckTexts(outOfRange = "Not within retailer range", forceLabel = "Manual Override"))
        }
        // the verdict card carries the host's wording, as before
        compose.onNodeWithText("Not within retailer range").assertExists()
        // the bar: Refresh as the primary and the host's Force label as the secondary
        compose.onNodeWithTag(OutletTags.REFRESH).assertIsDisplayed()
        compose.onNodeWithTag(OutletTags.FORCE).assertIsDisplayed()
        compose.onNodeWithText("Manual Override").assertExists()
        // the buttons sit in the BOTTOM bar (in the lower part of the screen), not in the page
        assertTrue("AMO: Force Sale is in the bottom bar", centreFraction(OutletTags.FORCE) > 0.8f)
        assertTrue("AMO: Refresh is in the bottom bar", centreFraction(OutletTags.REFRESH) > 0.8f)
        val force = compose.onNodeWithTag(OutletTags.FORCE).getUnclippedBoundsInRoot()
        val refresh = compose.onNodeWithTag(OutletTags.REFRESH).getUnclippedBoundsInRoot()
        assertTrue("AMO: the two buttons share one row", refresh.top < force.bottom && force.top < refresh.bottom)
        compose.onNodeWithTag(OutletTags.FORCE).performClick()
        assertEquals(1, forced)
    }

    @Test fun theSrAppDrawsItsPageButtonsWithTheSameTagsAndNoBottomBar() {
        show(sr = true) { VisitCheckContent(outside(), {}, {}, {}, {}, geoPanel = true) }
        compose.onNodeWithTag(OutletTags.REFRESH).assertExists()
        compose.onNodeWithTag(OutletTags.FORCE).assertExists()
        // the SR page has its buttons in the page, high on the screen, and no bottom bar
        assertTrue("SR: Force Sale is in the page, high on the screen", centreFraction(OutletTags.FORCE) < 0.6f)
        // stacked one under the other (the AMO bar puts them side by side in one row)
        val force = compose.onNodeWithTag(OutletTags.FORCE).getUnclippedBoundsInRoot()
        val refresh = compose.onNodeWithTag(OutletTags.REFRESH).getUnclippedBoundsInRoot()
        assertTrue("SR: Refresh is under Force Sale", refresh.top >= force.bottom)
    }

    /** The vertical centre of the tagged node as a fraction of the screen's height (0 top, 1 bottom). */
    private fun centreFraction(tag: String): Float {
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return ((b.top + b.bottom).value / 2f) / (root.bottom - root.top).value
    }

    @Test fun anSrOutletWithNoLocationAndNoForceSaleStillOffersBack() {
        show(sr = true) {
            VisitCheckContent(
                VisitUiState.NeedsDecision(outlet.copy(lat = null, lng = null), GeoVerdictResult(GeoVerdict.NO_OUTLET_LOCATION, GeoAction.FORCE_SALE, null), 0, false, 3, false, false),
                {}, {}, {}, null, geoPanel = true, onBack = {},
            )
        }
        compose.onNodeWithText("Go back").assertExists()
    }

}
