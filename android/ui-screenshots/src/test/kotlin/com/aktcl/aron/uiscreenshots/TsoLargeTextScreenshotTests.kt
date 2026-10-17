package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.tso.TsoApp
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitScreen
import com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitState
import com.aktcl.aron.feature.tso.finalsubmit.FsPreview
import com.aktcl.aron.feature.tso.finalsubmit.FsPreviewOutcome
import com.aktcl.aron.feature.tso.finalsubmit.FsResult
import com.aktcl.aron.feature.tso.finalsubmit.FsRoute
import com.aktcl.aron.feature.tso.finalsubmit.FsSubmitOutcome
import com.aktcl.aron.feature.tso.leave.LeaveApplyScreen
import com.aktcl.aron.feature.tso.leave.LeaveApplyState
import com.aktcl.aron.feature.tso.mycall.Criterion
import com.aktcl.aron.feature.tso.mycall.Rubric
import com.aktcl.aron.feature.tso.mycall.VisitQueryScreen
import com.aktcl.aron.feature.tso.mycall.VisitQueryState
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 200 percent font scale goldens (docs/32 s2a: nothing clips, every action stays reachable) of the TSO Home, Leave apply and Final
 * Submit, and the longest Bangla labels wrapping to three lines. Linux only (`-Proborazzi.record=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp", fontScale = 2.0f)
class TsoLargeTextScreenshotTests {
    @get:Rule val rule = createComposeRule()
    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, dark = false, sunlight = false, tier = GlassTier.B, reducedMotion = true) { content() } } }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private val user = HomeUser("টেস্টিং ঢাকা নর্থ টেরিটরি অফিসার", "tso1001", "TSO", offline = false, reauthRequired = false, updateRequired = false)

    @Test fun home_200_bn() = shot("tso_home_200_bn", AppLanguage.BN) {
        TsoApp(user, "2026-10-17", BundleStatus.Ready("b-1", "2026-10-17"), "0.1.0 (1)", {}, {})
    }

    @Test fun leave_apply_200_bn() = shot("tso_leave_apply_200_bn", AppLanguage.BN) {
        val st = LeaveApplyState({ _, _ -> true }, { "2026-10-17" }); st.edit { it.copy(days = 3, reason = "পারিবারিক জরুরি প্রয়োজনে গ্রামের বাড়িতে যেতে হচ্ছে") }
        LeaveApplyScreen(st, onBack = {})
    }

    private val api = object : FinalSubmitApi {
        override suspend fun zones() = listOf(10L, 11L)
        override suspend fun preview(zoneId: Long, date: String) = FsPreviewOutcome.Ok(FsPreview(zoneId, date, false, null, null, listOf(
            FsRoute(1, "DHK-R1", "ঢাকা উত্তর রুট এক", "sr", "রহিম উদ্দিন আহমেদ", "sales_submitted", true, 12, 4_391_000, 0), FsRoute(2, "DHK-R2", "Route Two", "amo", null, "in_field", true, 0, 0, null)), emptyList()))
        override suspend fun submit(clientUuid: String, zoneId: Long, date: String) = FsSubmitOutcome.Ok(FsResult(zoneId, date, "t", 2, 12, 4_391_000))
    }

    @Test fun final_submit_pick_200_bn() = shot("tso_final_submit_pick_200_bn", AppLanguage.BN) { val st = FinalSubmitState(api, 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10) }; FinalSubmitScreen(st, onBack = {}) }
    @Test fun final_submit_review_200_en() = shot("tso_final_submit_review_200_en", AppLanguage.EN) { val st = FinalSubmitState(api, 7) { "2026-10-17" }; runBlocking { st.loadZones(); st.pickZone(10); st.getSalesData() }; FinalSubmitScreen(st, onBack = {}) }

    @Test fun visit_query_three_line_bn() = shot("tso_visit_query_3line_bn", AppLanguage.BN) {
        val long = "এসআর কি নিয়মিত আপনার দোকানে এসে ভিজিট করে এবং প্রতিটি ভিজিটে দোকানের স্টক দেখে প্রয়োজনমতো নতুন অর্ডার নিয়ে যায় কিনা বলুন?"
        val st = VisitQueryState(Rubric(5, 1, listOf(Criterion(1, long, "Does the SR visit regularly?"))), "p", 1, 1) { true }
        VisitQueryScreen(st, "রহমান স্টোর অ্যান্ড জেনারেল ট্রেডার্স কাওরান বাজার ঢাকা", onDone = {}, onBack = {})
    }
}
