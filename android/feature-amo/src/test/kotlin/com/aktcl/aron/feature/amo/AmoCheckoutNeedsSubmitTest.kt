package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.attendance.AttendanceTags
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.home.RoleHomes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-NIGHT-CHECKOUT-01, AMO: the attendance screen says Sales Submit first and its link opens the AMO Sales Submit screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AmoCheckoutNeedsSubmitTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theSubmitFirstLinkOpensTheAmoSalesSubmitScreen() {
        val user = HomeUser("Testing Banani", "amo5001", "AMO", offline = true, reauthRequired = false, updateRequired = false)
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        val state = AttendanceState(checkedInAt = "2026-10-07T03:00:00.000Z", showCheckInPrompt = false, checkInEnabled = false, needsSalesSubmit = true)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(AppLanguage.BN) {
                    AmoApp(
                        user, "2026-10-07", BundleStatus.Offline, "0.1.0 (1)", onLanguageSelect = {}, onLogout = {},
                        screens = mapOf(
                            // the app module's entry, as in MainActivity (the permission gate is the app's, not under test here)
                            AmoScreen.ATTENDANCE to { _ ->
                                val navigate = LocalAmoNavigate.current
                                AttendanceContent(state, "17:00", {}, {}, onOpenSalesSubmit = { navigate(AmoScreen.SALES_SUBMIT) })
                            },
                            AmoScreen.SALES_SUBMIT to { _ -> Text("AMO_SALES_SUBMIT") },
                        ),
                    )
                }
            }
        }
        compose.onNodeWithTag(RoleHomeTags.tile(RoleHomes.AmoTile.ATTENDANCE)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("আগে Sales Submit করুন। আজকের বিক্রি সাবমিট করার পর চেক আউট চালু হবে।").assertExists()
        compose.onNodeWithTag(AttendanceTags.CHECK_OUT).assertIsNotEnabled()
        compose.onNodeWithText("Sales Submit খুলুন").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("AMO_SALES_SUBMIT").assertExists()
    }
}
