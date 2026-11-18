package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-PAR-AMO-14 (screenshots 50, 54, 55, 57): the route, cluster and retailer drop-downs of the AMO outlet forms carry their prompt as a label above the field, and Cancel and Save carry their icons. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class OutletFormLabelsTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN, dark = false, sunlight = false, amo = true) { content() } } }
    }

    private val pick = Pick(listOf(AmoOption(1, "Savar BazarDaily")), 1L, {})

    @Test fun routeClusterAndRetailerShowTheirPromptAsALabel() {
        show { AmoOutletFormBody(listOf(routeItem(pick, emptySet()), clusterItem(pick, emptySet()), retailerItem(pick, emptySet())), onSave = {}) }
        rule.onNodeWithText("Select a route").assertIsDisplayed()
        rule.onNodeWithText("Select a cluster").assertIsDisplayed()
        rule.onNodeWithText("Select retailer", substring = true).assertIsDisplayed()
    }

    @Test fun cancelAndSaveAreBothThereWithTheirOwnWords() {
        show { AmoOutletFormBody(emptyList(), onSave = {}, onCancel = {}) }
        rule.onNodeWithTag(OutletFormTags.CANCEL).assertIsDisplayed()
        rule.onNodeWithTag(OutletFormTags.SAVE).assertIsDisplayed()
        rule.onAllNodesWithText("Save").assertCountEquals(1)
    }
}
