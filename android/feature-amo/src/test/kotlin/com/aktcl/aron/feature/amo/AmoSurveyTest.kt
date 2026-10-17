package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.SurveyQuestionEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.outlet.AnswerType
import com.aktcl.aron.feature.outlet.SurveyAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AmoSurveyTest {
    @get:Rule val compose = createComposeRule()

    // The POSM shape: Q1 yes/no, Q1.1 the photo only when Q1 is yes, plus a required text question that cannot be answered here.
    private val rows = listOf(
        SurveyQuestionEntity(7, 12, 2, "photo_only", "Photo of the POSM", "পোসম ছবি", requiresPhoto = true, questionKey = "posm_photo", required = true, showIfKey = "posm", showIfBool = true),
        SurveyQuestionEntity(7, 11, 1, "bool", "Is the POSM displayed?", "পোসম আছে?", questionKey = "posm", required = true),
        SurveyQuestionEntity(7, 13, 3, "text", "Remarks", null, questionKey = "remarks", required = true),
    )

    @Test fun questionsFollowTheBundleOrderInTheUsersLanguageAndAnUnanswerableRequiredOneIsOptional() {
        val en = AmoSurvey.questions(rows, bn = false)
        assertEquals(listOf("posm", "posm_photo", "remarks"), en.map { it.question.key })
        assertEquals("Is the POSM displayed?", en[0].label)
        assertEquals("পোসম আছে?", AmoSurvey.questions(rows, bn = true)[0].label)
        assertEquals("Remarks", AmoSurvey.questions(rows, bn = true)[2].label) // no Bangla label: English
        assertFalse(en[2].question.required) // a required text question would block Submit for ever
        assertEquals(AnswerType.PHOTO_ONLY, en[1].question.answerType)
    }

    @Test fun thePhotoQuestionShowsOnlyWhenQ1IsYesAndSubmitNeedsItThen() {
        val all = AmoSurvey.questions(rows, bn = false)
        assertEquals(listOf("posm"), AmoSurvey.shown(all, emptyMap()).map { it.question.key })
        assertFalse(AmoSurvey.canSubmit(all, emptyMap()))
        val no = mapOf("posm" to SurveyAnswer(bool = false))
        assertEquals(listOf("posm"), AmoSurvey.shown(all, no).map { it.question.key })
        assertTrue(AmoSurvey.canSubmit(all, no)) // no photo asked after a No
        val yes = mapOf("posm" to SurveyAnswer(bool = true))
        assertEquals(listOf("posm", "posm_photo"), AmoSurvey.shown(all, yes).map { it.question.key })
        assertFalse(AmoSurvey.canSubmit(all, yes))
        assertTrue(AmoSurvey.canSubmit(all, yes + ("posm_photo" to SurveyAnswer(photoUuid = "p1"))))
    }

    private fun show(all: List<AmoSurveyQuestion>, onSubmit: () -> Unit = {}, confirming: Boolean = false, onConfirm: () -> Unit = {}): () -> Map<String, SurveyAnswer> {
        var state: Map<String, SurveyAnswer> = emptyMap()
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(AppLanguage.EN) {
                    var a by remember { mutableStateOf<Map<String, SurveyAnswer>>(emptyMap()) }
                    state = a
                    AmoSurveyContent(
                        all, a, confirming, false,
                        onYes = { k -> a = a + (k to SurveyAnswer(bool = true)) }, onNo = { k -> a = a + (k to SurveyAnswer(bool = false)) },
                        onPhoto = { k -> a = a + (k to (a[k] ?: SurveyAnswer()).copy(photoUuid = "p1")) },
                        onSubmit = onSubmit, onConfirm = onConfirm, onDismiss = {}, onSkip = {},
                    )
                }
            }
        }
        return { state }
    }

    @Test fun submitStaysDisabledUntilTheRequiredAnswersAreIn() {
        var submitted = 0
        val read = show(AmoSurvey.questions(rows, bn = false), onSubmit = { submitted++ })
        compose.onNodeWithTag(AmoSurveyTags.SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(AmoSurveyTags.yes("posm")).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AmoSurveyTags.photo("posm_photo")).assertIsDisplayed() // Q1.1 appears
        compose.onNodeWithTag(AmoSurveyTags.SUBMIT).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AmoSurveyTags.photo("posm_photo")).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(AmoSurveyTags.SUBMIT).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, submitted)
        assertEquals("p1", read()["posm_photo"]?.photoUuid)
    }

    @Test fun noSurveyShowsTheEmptyState() {
        show(emptyList())
        compose.onNodeWithTag(AmoSurveyTags.EMPTY).assertIsDisplayed()
    }
}
