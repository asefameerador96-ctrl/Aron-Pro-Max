package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CampaignPhotoScreenTest {
    @get:Rule val compose = createComposeRule()
    private val shops = listOf(ShopInfo(10, "Milon (1)", "1", "M", null, null, chip = "M"))
    private val gifts = listOf(LoyaltyGift(4, 3, "Chair", null, 200, emptyList(), null, true))
    private val group = CampaignGroup("r1", 10, 4, 3, setOf(1))
    private val saved = mutableListOf<CampaignGroup>()

    private fun show(ready: Boolean, groups: List<CampaignGroup> = listOf(group)) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN) {
                    CampaignPhotoScreen(shops, groups, gifts, UiLocale.EN, 10, {}, slot = { _, _ -> }, canSubmit = { ready }, onSubmit = { g -> saved += g; SaveOutcome.SAVED })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun submitIsEnabledOnlyWhenEveryOpenSlotHasACompleteCapture() {
        show(ready = false); compose.onNodeWithTag(CampaignTags.SUBMIT).performScrollTo().assertIsNotEnabled()
    }

    @Test fun aReadyGiftSubmitsOnce() {
        show(ready = true)
        compose.onNodeWithTag(CampaignTags.SUBMIT).performScrollTo().assertIsEnabled().performClick(); compose.waitForIdle()
        assertEquals(listOf(group), saved)
    }

    @Test fun withNothingPendingTheEmptyStateShows() { show(ready = true, groups = emptyList()); compose.onNodeWithTag(CampaignTags.EMPTY).assertExists() }
}
