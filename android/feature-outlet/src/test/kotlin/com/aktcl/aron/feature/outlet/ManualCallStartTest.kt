package com.aktcl.aron.feature.outlet

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.TextRules
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SR manual s8 / 18-20 (D-SR-MAN-S4-01/07): the outlet list rows, the call question and the "start the call first" box; the AMO start bar is untouched. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ManualCallStartTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    private val open = VisitUiState.Open(OpenVisit("v1", 1, 2, "in_range", "sale_allowed", true, false, null, "x"))

    @Test fun theQuestionOffersYesNoHistoryAndTargetAndEachAnswersItsCallback() {
        var yes = 0; var no = 0; var hist = 0; var target = 0; var blocked = 0
        val q = CallQuestion(onSaleHistory = { hist++ }, onTodayTarget = { target++ }, onProceedBlocked = { blocked++ }, onNo = { no++ })
        show(AppLanguage.BN) { VisitCheckContent(open, {}, {}, {}, null, onStartCall = { yes++ }, callQuestion = q) }
        compose.onNodeWithText("আপনি কি কল শুরু করতে চান?").assertExists()
        compose.onNodeWithText("Sale History").assertExists(); compose.onNodeWithText("আজকের টার্গেট").assertExists()
        compose.onNodeWithTag(CallQuestionTags.HISTORY).performClick(); compose.onNodeWithTag(CallQuestionTags.TARGET).performClick()
        compose.onNodeWithTag(CallQuestionTags.NO).performClick()
        compose.onNodeWithTag(CallQuestionTags.PROCEED).performClick() // the cart bar before the call: the host shows the box
        assertEquals(listOf(1, 1, 1, 1, 0), listOf(hist, target, no, blocked, yes))
        compose.onNodeWithTag(CallQuestionTags.YES).performClick(); assertEquals(1, yes)
    }

    @Test fun theBoxSaysStartTheCallFirstInEnglishAndBangla() {
        show(AppLanguage.EN) { StartCallFirstDialog {} }
        compose.onNodeWithText("Please start the call to continue.").assertExists()
    }

    @Test fun banglaBoxKeepsTheManualsEnglishLineAndAddsBangla() {
        show(AppLanguage.BN) { StartCallFirstDialog {} }
        compose.onNodeWithText("Please start the call to continue.\nকল শুরু না করে বিক্রয় করা যাবে না।").assertExists()
        compose.onNodeWithText("ওকে").assertExists()
    }

    @Test fun withoutTheQuestionTheAmoStartBarIsUnchanged() {
        var started = 0
        show(AppLanguage.EN) { VisitCheckContent(open, {}, {}, {}, null, onStartCall = { started++ }) }
        compose.onNodeWithText("Start call").assertExists().performClick()
        assertEquals(1, started)
        compose.onNodeWithTag(CallQuestionTags.QUESTION).assertDoesNotExist()
    }

    private fun o(id: Long, name: String) = OutletEntity(
        outletId = id, routeId = 1, code = "37009$id", name = name, nameBn = null, nameSortKey = TextRules.nameSortKey(name), ownerName = "x",
        contactNumber = "01712345678", lat = 1.0, lng = 1.0, locationConfirmed = true, provisionalLat = null, provisionalLng = null,
        clusterId = 1, clusterName = "111 No Road", channel = "g", subChannelId = null, geoClass = null, status = "active", priceType = "outlet",
        outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = id.toInt(), openDueMtk = 0, openDueAsOf = null,
        programmeFlagsJson = "[]", pendingRequest = false,
    )

    @Test fun manualRowsAreOneStringWithTheDotAndOnlyPresentLettersAreChips() {
        val rows = OutletPicker.rows(listOf(o(1, "Mojammel Store"), o(2, "Ariyan Store")))
        var picked: Long? = null
        show(AppLanguage.EN) { OutletPickerContent(rows, OutletPicker.chips(rows), OutletPicker.ALL_CHIP, {}, { picked = it.outlet.outletId }, manualRows = true) }
        compose.onNodeWithText("Select retailer").performClick()
        compose.onNodeWithText("Mojammel Store (370091-01712345678-111 No Road)").assertExists().performClick()
        assertEquals(1L, picked)
        compose.onNodeWithText("Z").assertDoesNotExist(); compose.onNodeWithText("A").assertExists(); compose.onNodeWithText("M").assertExists()
    }

    @Test fun callPhotoContinueNeedsBothPhotoAndFixAndSkipNeverBlocks() {
        var skipped = 0
        show(AppLanguage.EN) { CallPhotoStep(GeoPhotoState(), {}, {}, { skipped++ }) }
        compose.onNodeWithTag(CallPhotoTags.CONTINUE).assertIsNotEnabled()
        compose.onNodeWithTag(CallPhotoTags.SKIP).performClick(); assertEquals(1, skipped)
    }
}
