package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PerfAssessmentTest {
    @get:Rule val compose = createComposeRule()

    private val products = """{"nodes":[{"id":5,"level":"brand","name":"Avon","name_bn":"এভন","sort":2},{"id":4,"level":"brand","name":"Maxim","sort":1},{"id":9,"level":"category","name":"Cigarette","sort":1}]}"""

    @Test fun brandsAreTheBrandLevelNodesInBundleOrder() {
        assertEquals(listOf("Maxim", "Avon"), PerfAssessment.brands(products).map { it.name })
        assertTrue(PerfAssessment.brands(null).isEmpty() && PerfAssessment.brands("junk").isEmpty() && PerfAssessment.brands("{}").isEmpty())
    }

    @Test fun oosOnlyForDistributedBrandsAndUntickingDistributionClearsIt() {
        var s = PerfState()
        assertEquals(s, s.toggleOos(4)) // not distributed: nothing changes
        s = s.toggleDistributed(4).toggleOos(4)
        assertEquals(setOf(4L), s.oos)
        s = s.toggleDistributed(4)
        assertTrue(s.present.isEmpty() && s.oos.isEmpty())
    }

    @Test fun saveWritesOneCheckAndOneLinePerBrandWithOosImpliesPresent() {
        val brands = PerfAssessment.brands(products)
        var n = 0
        val recs = PerfAssessment.records("v1", brands, PerfState().toggleDistributed(4).toggleOos(4).withPosm(false)) { "u${n++}" }
        assertEquals(listOf("distribution_check", "distribution_check_line", "distribution_check_line"), recs.map { it.type })
        assertEquals(listOf(1, 2, 2), recs.map { it.rank })
        assertTrue(recs.all { it.familyUuid == "v1" })
        assertEquals("u0", recs[1].payload["check_client_uuid"].toString().trim('"'))
        assertEquals("true", recs[1].payload["oos"].toString()); assertEquals("false", recs[2].payload["present"].toString())
        assertEquals("false", recs[0].payload["posm_present"].toString())
    }

    private fun show(initial: PerfState = PerfState(), saved: Boolean = false, onSave: () -> Unit = {}): () -> PerfState {
        var state = initial
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                AronTheme(AppLanguage.EN) {
                    var s by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
                    state = s
                    PerfAssessmentContent(
                        PerfAssessment.brands(products), s, saved, false, { s = s.toggleDistributed(it) }, { s = s.toggleOos(it) }, { s = s.withPosm(it) }, onSave,
                    )
                }
            }
        }
        return { state }
    }

    @Test fun oosRowIsDisabledUntilDistributedAndPosmIsTriState() {
        val read = show()
        compose.onNodeWithTag(PerfTags.oos(4)).assertIsNotEnabled()
        compose.onNodeWithTag(PerfTags.dist(4)).performClick()
        compose.waitForIdle()
        assertEquals(setOf(4L), read().present)
        compose.onNodeWithTag(PerfTags.oos(4)).performClick()
        compose.waitForIdle()
        assertEquals(setOf(4L), read().oos)
        assertEquals(null, read().posm)
        compose.onNodeWithTag(PerfTags.POSM_YES).performScrollTo().performClick(); compose.waitForIdle(); assertEquals(true, read().posm)
        compose.onNodeWithTag(PerfTags.POSM_YES).performScrollTo().performClick(); compose.waitForIdle(); assertEquals(null, read().posm)
        compose.onNodeWithTag(PerfTags.POSM_NO).performScrollTo().performClick(); compose.waitForIdle(); assertEquals(false, read().posm)
    }

    @Test fun savedShowsTheMessageAndLocksTheForm() {
        show(saved = true)
        compose.onNodeWithTag(PerfTags.SAVED).assertIsDisplayed()
        compose.onNodeWithTag(PerfTags.SAVE).assertIsNotEnabled()
    }
}
