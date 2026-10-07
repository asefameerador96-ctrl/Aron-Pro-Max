package com.aktcl.aron.feature.outlet

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-018, 037, 038, 039, 076, N-040 screens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RequestScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    @Test fun forceSaleShowsReasonsPhotoAndGeoLineInBangla() {
        var reason: ForceReason? = null; var confirmed = 0
        show(AppLanguage.BN) {
            ForceSaleContent(null, GeoPhotoState(CapturedPhoto("p", "/t", 1), com.aktcl.aron.feature.outlet.FixReading("ok", 1.0, 1.0, 11.6, false), 12, 1), setOf(ForceSaleMissing.REASON), false, { reason = it }, {}, { confirmed++ })
        }
        compose.onNodeWithText("ইন্টারনেট সমস্যা").performClick(); assertEquals(ForceReason.INTERNET_PROBLEM, reason)
        compose.onNodeWithText("জিইও নেওয়া হয়েছে ± ১২ মিটার").assertExists()
        compose.onNodeWithText("একটি কারণ বেছে নিন।").assertExists()
        compose.onNodeWithTag(ForceSaleTags.CONFIRM).performClick(); assertEquals(1, confirmed)
    }

    @Test fun newShopFormSavesWithoutAConfirmation() {
        var saves = 0
        var form = OutletRequestForm(OutletRequestKind.NEW)
        show(AppLanguage.EN) {
            OutletRequestContent(form, emptySet(), listOf(ClusterOption(7, "Apsis Cluster")), GeoPhotoState(), false, false, { form = it }, {}, { saves++ })
        }
        compose.onNodeWithTag(RequestTags.NAME).performTextInput("Rahim Store")
        compose.onNodeWithTag(RequestTags.SAVE).performScrollTo().performClick()
        assertEquals(1, saves)
    }

    @Test fun closeAsksForConfirmationBeforeSaving() {
        var saves = 0
        show(AppLanguage.EN) {
            OutletRequestContent(OutletRequestForm(OutletRequestKind.CLOSE, outletId = 5, openDueMtk = 100), emptySet(), emptyList(), GeoPhotoState(), false, true, {}, {}, { saves++ })
        }
        compose.onNodeWithText("This shop will be permanently closed.").assertExists(); compose.onNodeWithText("This shop still has unpaid dues.").assertExists()
        compose.onNodeWithTag(RequestTags.SAVE).performScrollTo().performClick(); assertEquals(0, saves)
        compose.onNodeWithText("Yes").performClick(); assertEquals(1, saves)
    }

    @Test fun savedMessageShowsPendingInBangla() {
        show(AppLanguage.BN) { OutletRequestContent(OutletRequestForm(OutletRequestKind.ROUTE_ADD, outletId = 1), emptySet(), emptyList(), GeoPhotoState(), true, false, {}, {}, {}) }
        compose.onNodeWithText("সংরক্ষিত হয়েছে। অনুরোধটি যাচাইয়ের অপেক্ষায় আছে।").assertExists()
    }
}
