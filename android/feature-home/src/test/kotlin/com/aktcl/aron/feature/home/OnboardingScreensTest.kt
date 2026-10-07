package com.aktcl.aron.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-003, F-SR-065, F-SR-048 screens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OnboardingScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    @Test fun permissionRationaleShowsInBanglaAndNeverMentionsTheMicrophone() {
        var asked: AppPermission? = null
        show(AppLanguage.BN) { PermissionOnboardingContent(PermissionGate.initial(), { asked = it }, {}) }
        compose.onNodeWithText("ক্যামেরা: ফোর্স সেল ও আউটলেটের অনুরোধে দোকানের ছবি তুলতে।").assertExists()
        compose.onNodeWithTag(OnboardingTags.allow(AppPermission.PRECISE_LOCATION)).performClick()
        assertEquals(AppPermission.PRECISE_LOCATION, asked)
        assertTrue(AppPermission.entries.none { it.manifestName.contains("AUDIO") })
    }

    @Test fun permanentlyDeniedSendsToSettings() {
        var opened = 0
        val st = PermissionState(mapOf(AppPermission.PRECISE_LOCATION to PermissionStatus.DENIED_PERMANENTLY))
        show(AppLanguage.EN) { PermissionOnboardingContent(st, {}, { opened++ }) }
        compose.onNodeWithText("Visits stay blocked until precise location is allowed.").assertExists()
        compose.onNodeWithTag(OnboardingTags.SETTINGS).performScrollTo().performClick(); assertEquals(1, opened)
    }

    @Test fun routePickerListsPlannedRoutesAndReportsTheChoice() {
        var picked: Long? = null
        val routes = listOf(PlannedRoute(1, "Route A", true, 1), PlannedRoute(2, "Route B", true, 2), PlannedRoute(3, "Route C", false, null))
        show(AppLanguage.EN) { RoutePickerContent(routes, null, { picked = it.routeId }) }
        compose.onNodeWithTag(OnboardingTags.route(2)).performClick(); assertEquals(2L, picked)
        compose.onNodeWithTag(OnboardingTags.route(3)).assertDoesNotExist()
    }

    @Test fun tutorialsShowEmptyStateAndOnlyPlayOnline() {
        show(AppLanguage.EN) { TutorialListContent(emptyList(), false, {}) }
        compose.onNodeWithText("No tutorial videos yet.").assertExists(); compose.onNodeWithText("Videos play online only. Connect to watch.").assertExists()
    }

    @Test fun tutorialPlayCallsBackOnlineOnly() {
        var played: String? = null
        show(AppLanguage.EN) { TutorialListContent(listOf(TutorialItem("t1", "How to sell")), true, { played = it.id }) }
        compose.onNodeWithTag(OnboardingTags.tutorial("t1")).performClick(); assertEquals("t1", played)
    }
}
