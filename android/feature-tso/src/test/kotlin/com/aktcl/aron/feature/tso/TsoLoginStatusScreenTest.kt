package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Login & Bikroy Joma Status (screenshot 8, D-TSO-MAN-12), tso-manual A1 fix round: a row's name is the full name, else the
 * username; "SR Not Set" only for a vacant route (no user at all); the list is lazy (a tab may carry 2000 rows: only the rows on
 * screen are composed, the rest come with the scroll).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TsoLoginStatusScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val stamp = "\"as_of\":\"2026-10-29T08:30:00Z\",\"date\":\"2026-10-29\",\"till_time\":true,\"last_updated_at\":null"

    private fun show(users: LoginSubmitUsers?) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) { TsoLoginSubmitStatusContent(LoginSubmitTab.NOT_LOGGED_IN, CardLoad(users), false, {}, {}) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aUserWithoutAFullNameShowsTheUsernameAndOnlyAVacantRouteIsSrNotSet() {
        show(
            TsoDashboards.users(
                """{$stamp,"tab":"not_logged_in","route_count":2,"truncated":false,"items":[
                {"route_id":2,"route_code":"R2","route_name":"Apsis Route 2","zone_id":9,"dep_name":"Banani - Test","user_id":9,"username":"sr9","display_name":null,"role":"SR"},
                {"route_id":3,"route_code":"R3","route_name":"Apsis Route 3","zone_id":9,"dep_name":"Banani - Test","user_id":null,"username":null,"display_name":null,"role":"SR"},
                {"route_id":4,"route_code":"R4","route_name":"Apsis Route 4","zone_id":9,"dep_name":"Banani - Test","user_id":7,"username":"amo6334","display_name":"AMO-6334","role":"AMO"}]}""",
            ),
        )
        compose.onNodeWithText("sr9").assertExists() // the name line
        compose.onNodeWithText("AMO-6334").assertExists()
        // one "SR Not Set": the vacant route only
        compose.onNodeWithText("SR Not Set").assertExists()
    }

    @Test fun aLongTabIsLazyAndScrollsToItsLastRow() {
        val rows = (1..2000).joinToString(",") { i ->
            """{"route_id":$i,"route_code":"R$i","route_name":"Route $i","zone_id":9,"dep_name":"Banani - Test","user_id":$i,"username":"sr$i","display_name":"SR Person $i","role":"SR"}"""
        }
        show(TsoDashboards.users("""{$stamp,"tab":"not_logged_in","route_count":2000,"truncated":false,"items":[$rows]}"""))
        compose.onNodeWithText("SR Person 1").assertExists()
        compose.onNodeWithText("SR Person 2000").assertDoesNotExist() // not composed until it is on screen
        compose.onNodeWithTag(TsoDashTags.USERS).performScrollToNode(hasText("SR Person 2000"))
        compose.onNodeWithText("SR Person 2000").assertExists()
    }
}
