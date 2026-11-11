package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.repo.LockAcks
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.home.BundleFreshness
import com.aktcl.aron.feature.home.HomeContent
import com.aktcl.aron.feature.home.HomeModel
import com.aktcl.aron.feature.home.HomeTiles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * tso-manual X4a (R-W-11, A3 ask 1, s8 row 26 "SR phone shows lock"): the SR Home draws the day lock in its banner slot
 * ([srHomeBanner] with the lock text, as SrApp passes it), the bundle download keeps its text first, and the lock text
 * shown where a capture is refused ([SrDayLockDialog]) says the same in bn and en.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrDayLockHomeTest {
    @get:Rule val compose = hostComposeRule()

    private fun show(language: AppLanguage, content: @Composable () -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            val ctx = AppLocale.wrap(activity, language)
            activity.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        }
        compose.waitForIdle()
    }

    /** The SR Home as SrApp draws it, with the banner slot filled by [srHomeBanner]. */
    @Composable
    private fun home(downloading: Boolean, lock: DayLock?) {
        val header = HomeModel.header("SR", "Testing Banani", "sr334001", "Apsis RouteDaily", "daily", "2026-10-26")
        HomeContent(
            header, HomeTiles.resolve(emptySet(), 0), BundleFreshness.Fresh, false, null, onTile = {},
            banner = srHomeBanner(downloading) { srDayLockText(lock) },
        )
    }

    @Test fun aFinalDayShowsTheLockOnTheHome() {
        show(AppLanguage.EN) { home(downloading = false, lock = DayLock(locked = true, acks = LockAcks(held = 1, refused = 2))) }
        compose.onNodeWithText("Day closed by Final Submit", substring = true).assertExists()
        compose.onNodeWithText("New calls cannot start", substring = true).assertExists()
        compose.onNodeWithText("Printing still works.", substring = true).assertExists()
        compose.onNodeWithText("2 records made after the Final Submit were not accepted.", substring = true).assertExists()
    }

    @Test fun banglaHomeShowsTheBanglaLock() {
        show(AppLanguage.BN) { home(downloading = false, lock = DayLock(locked = true, acks = LockAcks(0, 0))) }
        compose.onNodeWithText("ফাইনাল সাবমিটে দিন বন্ধ", substring = true).assertExists()
        compose.onNodeWithText("প্রিন্ট করা যাবে।", substring = true).assertExists()
    }

    @Test fun theDownloadKeepsItsTextFirstAndAnOpenDayShowsNoLock() {
        show(AppLanguage.EN) { home(downloading = true, lock = DayLock(locked = true, acks = LockAcks(0, 0))) }
        compose.onNodeWithText("Downloading today's route in the background", substring = true).assertExists()
        compose.onNodeWithText("Day closed by Final Submit", substring = true).assertDoesNotExist()
    }

    @Test fun anOpenDayShowsNoLockBanner() {
        show(AppLanguage.EN) { home(downloading = false, lock = null) }
        compose.onNodeWithText("Day closed by Final Submit", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Downloading today's route", substring = true).assertDoesNotExist()
    }

    @Test fun theRefusalSaysTheLockTextAndOkCloses() {
        var ok = 0
        show(AppLanguage.EN) { SrDayLockDialog(onOk = { ok++ }) }
        compose.onNodeWithText("Day closed by Final Submit").assertExists()
        compose.onNodeWithText("sales, stock entries, memo edits and collections are not accepted. Printing still works.", substring = true).assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(1, ok)
    }

    @Test fun theRefusalInBangla() {
        show(AppLanguage.BN) { SrDayLockDialog(onOk = {}) }
        compose.onNodeWithText("ফাইনাল সাবমিটে দিন বন্ধ").assertExists()
        compose.onNodeWithText("নতুন কল শুরু করা যাবে না", substring = true).assertExists()
    }
}
