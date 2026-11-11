package com.aktcl.aron.feature.amo

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The route detail of Sales till now says when it shows a kept copy or could not load, like the zone and route cards beside it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TillRouteBannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aKeptCopyIsSaid() {
        compose.setContent { AronTheme(AppLanguage.EN, amo = true) { TillRouteDetailContent("79.67", "572", emptyList(), stale = true) } }
        compose.onNodeWithText("List from the last time you were online.").assertExists()
    }
}
