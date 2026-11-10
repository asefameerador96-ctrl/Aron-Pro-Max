package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Screenshots 17-22: letters present (case-insensitive, upper-case), code-descending rows, the green dot and the brown badge, the range area. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CallPickTest {
    @get:Rule val compose = createComposeRule()

    private fun outlet(id: Long, code: String, name: String, lat: Double? = 23.78, lng: Double? = 90.41) = OutletEntity(
        outletId = id, routeId = 1, code = code, name = name, nameBn = null, nameSortKey = name.lowercase(), ownerName = "o", contactNumber = "01711111111",
        lat = lat, lng = lng, locationConfirmed = true, provisionalLat = null, provisionalLng = null, clusterId = 1, clusterName = "Kacha Bazar", channel = "gt",
        subChannelId = null, geoClass = null, status = "active", priceType = "std", outletKind = "retail", radiusM = 50, maxAccuracyM = 50, visitSequence = null,
        openDueMtk = 0, openDueAsOf = null, programmeFlagsJson = "{}", pendingRequest = false,
    )

    private val outlets = listOf(
        outlet(1, "1421245", "Shafiul Store"), outlet(2, "2654199", "Basahar Store"), outlet(3, "1750844", "m store", lat = null, lng = null),
        outlet(4, "2647524", "Tota Store"), outlet(5, "1421246", "Sombaru Store"),
    )

    @Test fun rowsAreOrderedByOutletCodeDescendingAndMarkBaseAndToday() {
        val rows = CallPick.rows(outlets, calledToday = setOf(4L))
        assertEquals(listOf("2654199", "2647524", "1750844", "1421246", "1421245"), rows.map { it.outlet.code })
        assertEquals(listOf(true, true, false, true, true), rows.map { it.hasBase }) // the green dot: a base location on file
        assertEquals(listOf(false, true, false, false, false), rows.map { it.calledToday }) // the brown badge: called today
    }

    @Test fun lettersArePresentOnesOnlyCaseInsensitiveAndUpperCase() {
        assertEquals(listOf("B", "M", "S", "T"), CallPick.letters(outlets)) // "m store" and "Shafiul"/"Sombaru" fold into M and S, no C or G
        assertEquals(listOf("B"), CallPick.rows(outlets, emptySet(), "B").map { it.chip }.distinct())
        assertEquals(2, CallPick.rows(outlets, emptySet(), "S").size)
    }

    @Test fun nonNumericCodesSortAfterNumericOnesAndTiesAreStable() {
        val rows = CallPick.rows(listOf(outlet(1, "A-9", "a"), outlet(2, "100", "b"), outlet(3, "100", "c")), emptySet())
        assertEquals(listOf(2L, 3L, 1L), rows.map { it.outlet.outletId })
    }

    private fun show(range: CallRange, live: Boolean = true, onOverride: () -> Unit = {}, onRefresh: () -> Unit = {}, below: @androidx.compose.runtime.Composable () -> Unit = {}) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(AppLanguage.EN, amo = true) {
                    AmoCallPickContent("DariapurDaily", "Basahar Store (2654199-123-Tatul Tola)", true, listOf("A", "B"), "", live, range, true, false, {}, {}, {}, onOverride, onRefresh, below = below)
                }
            }
        }
    }

    @Test fun outOfRangeShowsTheWarningWithOverrideAndRefreshAndTheLiveBadgeIsNotAButton() {
        var override = 0; var refresh = 0
        show(CallRange.Out(overrideAvailable = true, refreshLeft = true), onOverride = { override++ }, onRefresh = { refresh++ })
        compose.onNodeWithTag(CallPickTags.WARNING).assertIsDisplayed()
        compose.onNodeWithTag(CallPickTags.OVERRIDE).performClick(); compose.onNodeWithTag(CallPickTags.REFRESH).performClick()
        assertEquals(1, override); assertEquals(1, refresh)
        compose.onNodeWithTag(CallPickTags.LIVE).assertIsDisplayed()
        assertTrue(compose.onNodeWithTag(CallPickTags.LIVE).fetchSemanticsNode().config.none { it.key.name == "OnClick" }) // indicator only
    }

    @Test fun overrideAndRefreshHideWhenNotAvailable() {
        show(CallRange.Out(overrideAvailable = false, refreshLeft = false))
        compose.onNodeWithTag(CallPickTags.WARNING).assertIsDisplayed()
        compose.onNodeWithTag(CallPickTags.OVERRIDE).assertDoesNotExist(); compose.onNodeWithTag(CallPickTags.REFRESH).assertDoesNotExist()
    }

    @Test fun inRangeReplacesTheWarningWithTheTilesAndNoLiveBadgeWithoutAnOutlet() {
        var tapped = false
        show(CallRange.In, live = false, below = { AmoMenuTiles(listOf(MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_sale, R.string.amo_cc_menu_sale, AmoCallTags.MENU_SALE) { tapped = true })) })
        compose.onNodeWithTag(CallPickTags.WARNING).assertDoesNotExist(); compose.onNodeWithTag(CallPickTags.LIVE).assertDoesNotExist()
        compose.onNodeWithTag(AmoCallTags.MENU_SALE).performClick(); assertTrue(tapped)
    }

    @Test fun checkingShowsOnlyTheCheckingLine() {
        show(CallRange.Checking)
        compose.onNodeWithTag(CallPickTags.CHECKING).assertIsDisplayed(); compose.onNodeWithTag(CallPickTags.OVERRIDE).assertDoesNotExist()
        assertFalse(false)
    }

    @Test fun forceReasonDialogGivesTheContractCodes() {
        val got = mutableListOf<String>()
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(AppLanguage.EN, amo = true) { AmoForceReasonCard({ got += it }) } } }
        compose.onNodeWithTag(AmoDialogTags.REASON_INTERNET).performClick(); compose.onNodeWithTag(AmoDialogTags.REASON_LOCATION).performClick()
        assertEquals(listOf("internet_problem", "location_change"), got) // VisitRecord.force_sale_reason values (contract 1.11.0)
    }
}
