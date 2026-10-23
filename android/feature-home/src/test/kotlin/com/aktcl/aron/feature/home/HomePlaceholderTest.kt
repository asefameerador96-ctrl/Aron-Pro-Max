package com.aktcl.aron.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.TransportFailure
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HomePlaceholderTest {
    @get:Rule val compose = createComposeRule()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val user = HomeUser("Testing Banani", "sr334001", "SR", offline = true, reauthRequired = false, updateRequired = false)
    // 2026-10-04T18:30:00Z is 00:30 on 2026-10-05 in Dhaka (UTC+6): the business date has already turned.
    private val justAfterDhakaMidnight = 1_791_138_600_000L

    @Test
    fun businessDateIsDhakaAndOfflineNeverBlocks() {
        val vm = HomePlaceholderViewModel({ justAfterDhakaMidnight }) { ApiResult.Transport(TransportFailure.OFFLINE) }
        assertEquals("2026-10-05", vm.state.value.businessDate)
        assertEquals(BundleStatus.Offline, vm.state.value.bundle)
    }

    @Test
    fun banglaHomeShowsBengaliDigitsAndTheOfflineBanner() {
        val vm = HomePlaceholderViewModel({ justAfterDhakaMidnight }) { ApiResult.Transport(TransportFailure.OFFLINE) }
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(AppLanguage.BN) { HomePlaceholderScreen(vm, user, {}, {}) }
            }
        }
        compose.onNodeWithText("ব্যবসায়িক তারিখ: ২০২৬-১০-০৫").assertIsDisplayed()
        compose.onNodeWithText("SR - Testing Banani (sr334001)").assertIsDisplayed()
        compose.onNodeWithText("অফলাইন: আপনার কাজ এই ফোনে সংরক্ষিত আছে, পরে সিঙ্ক হবে").assertIsDisplayed()
        compose.onNodeWithText("লগআউট").assertIsDisplayed()
    }

    @Test
    fun englishHomeShowsAsciiDigits() {
        val vm = HomePlaceholderViewModel({ justAfterDhakaMidnight }) { ApiResult.Transport(TransportFailure.TIMEOUT) }
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(AppLanguage.EN) { HomePlaceholderScreen(vm, user.copy(offline = false), {}, {}) }
            }
        }
        compose.onNodeWithText("Business date: 2026-10-05").assertIsDisplayed()
        compose.onNodeWithText("Log out").assertIsDisplayed()
    }
}
