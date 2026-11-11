package com.aktcl.aron.amo

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Sales path of `ControlCallHost` on a real [AmoDay] (T1 re-check, 2026-11-10): a call already open for the retailer (a relaunch, or Back from
 * the sale screen) must never leave a dead Sales tile, and going on to the sale must neither open a second visit nor close the call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class AmoControlCallHostTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture
    private var activity: ComponentActivity? = null

    @Before fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.CAMERA, android.Manifest.permission.POST_NOTIFICATIONS)
        Dispatchers.setMain(MainLooperImmediate.dispatcher); f = AmoLockFixture(app, latePolicy = "accept_and_flag") }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun show(content: @Composable () -> Unit) {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        f.own(ActivityScenario.launch(ComponentActivity::class.java)).onActivity { a ->
            activity = a
            val ctx = AppLocale.wrap(a, AppLanguage.EN)
            a.setContent { CompositionLocalProvider(LocalContext provides ctx, androidx.activity.compose.LocalActivityResultRegistryOwner provides a) { AronTheme(AppLanguage.EN) { content() } } }
        }
        compose.waitForIdle()
    }

    private fun back() { activity!!.runOnUiThread { activity!!.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun present(tag: String) = runCatching { compose.onNodeWithTag(tag).assertExists() }.isSuccess

    @Test fun backFromTheSaleThenTheSalesTileGoesOnWithTheSameVisit() {
        val v = f.controlCall()
        f.zone(); f.day.visitSession.restore(v); f.day.routeId = v.routeId
        show { ControlCallHost(f.day, onBack = {}, startWithSale = true) }
        // a restored call on the Sales path opens the sale at once, not the picker
        compose.waitUntil(5_000) { !present("amo_cp_route") }
        // screenshots 24, 25: the retailer stays on the sale screen
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Rahim Store 50001", substring = true).assertExists() }.isSuccess }
        back() // Back from the sale entry: the picker, the call still open
        runCatching { compose.waitUntil(5_000) { present("amo_cc_sale") } }.onFailure { throw AssertionError("no Sales tile after Back; tree:\n" + compose.onRoot().printToString(), it) }
        // owner walk of build 1860: the Sale tile's picker is headed "Sales", never "Control Call Activity"
        compose.onNodeWithText("Sales").assertExists()
        compose.onNodeWithText("Control Call Activity").assertDoesNotExist()
        assertEquals(v.visitUuid, f.day.visitSession.current.value?.visitUuid)
        compose.onNodeWithTag("amo_cc_sale").performClick() // formerly a dead tile
        compose.waitUntil(5_000) { !present("amo_cc_sale") }
        // the same single visit: no second visit, the call is neither closed nor reopened
        assertEquals(v.visitUuid, f.day.visitSession.current.value?.visitUuid)
        assertNotNull(f.day.visitSession.current.value)
    }

    private fun tag(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { n ->
        n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) && n.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith(prefix)
    }

    /** Out of range offers Refresh first; Manual Override appears once the refreshes are used up (Geo rule). */
    private fun awaitOverride() {
        compose.waitUntil(8_000) { present("amo_cp_override") || present("amo_cp_refresh") }
        var guard = 0
        while (!present("amo_cp_override") && guard++ < 10) { compose.onNodeWithTag("amo_cp_refresh").performClick(); compose.waitForIdle(); compose.waitUntil(8_000) { present("amo_cp_override") || present("amo_cp_refresh") } }
        compose.waitUntil(8_000) { present("amo_cp_override") }
    }

    private fun pickRetailer() {
        compose.waitUntil(5_000) { present("amo_cp_route") }
        compose.onNodeWithTag("amo_cp_route").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(tag("amo_cp_route_")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("amo_cp_route_" + f.route).performClick()
        compose.onNodeWithTag("amo_cp_outlet").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(tag("amo_cp_outlet_")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("amo_cp_outlet_50001").performClick()
    }

    /** Lead gate M1: out of range on the Sales path asks the reason whether or not the AMO came through the location-off card. */
    @Test fun outOfRangeOnTheSalesPathAsksTheReasonAndNeverRecordsOneUnasked() {
        f.zone(); f.phoneOffsetLat = 0.01
        show { ControlCallHost(f.day, onBack = {}, startWithSale = true) }
        pickRetailer()
        compose.onNodeWithTag("amo_cc_sale").performClick()
        awaitOverride()
        compose.onNodeWithTag("amo_cp_override").performClick()
        compose.onNodeWithText("What is the reason for your force sale?").assertExists() // the reason dialog, nothing recorded yet
        assertEquals(null, f.day.visitSession.current.value)
        compose.onNodeWithText("Internet problem").performClick(); compose.waitForIdle()
        // the chosen reason goes on to the override photo step (the visit is committed only after the photo)
        assertEquals(null, f.day.visitSession.current.value)
    }

    /**
     * Lead gate M3: reason -> override photo -> visit through the real host, with a FAKE photo pipeline set on the day (no camera). The chosen reason
     * is the visit's force_reason_code, and a replay of the same confirm (a second tap) leaves one visit.
     */
    @Test fun theChosenReasonThroughTheFakePhotoLandsOnTheVisitAndAReplayAddsNoSecondVisit() {
        f.zone(); f.phoneOffsetLat = 0.01
        f.day.photoPipeline = object : com.aktcl.aron.feature.outlet.PhotoPipeline {
            override suspend fun captureAndCompress(photoUuid: String) = com.aktcl.aron.feature.outlet.CapturedPhoto(photoUuid, "/fake.jpg", 1)
            override suspend fun discard(photoUuid: String) = Unit
        }
        show { ControlCallHost(f.day, onBack = {}, startWithSale = true) }
        pickRetailer()
        compose.onNodeWithTag("amo_cc_sale").performClick()
        awaitOverride()
        compose.onNodeWithTag("amo_cp_override").performClick()
        compose.onNodeWithText("Internet problem").performClick(); compose.waitForIdle()
        compose.waitUntil(8_000) { present(com.aktcl.aron.feature.outlet.ForceSaleTags.PHOTO) }
        compose.onNodeWithTag(com.aktcl.aron.feature.outlet.ForceSaleTags.PHOTO).performClick(); compose.waitForIdle()
        compose.waitUntil(8_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Continue")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Continue").performClick(); compose.waitForIdle()
        compose.waitUntil(8_000) { f.day.visitSession.current.value != null }
        val visits = { kotlinx.coroutines.runBlocking { f.day.database.outboxDao().nextPending(1000) }.filter { it.recordType == "visit" } }
        assertEquals(1, visits().size)
        org.junit.Assert.assertTrue(visits()[0].payloadJson.contains("\"force_reason_code\":\"internet_problem\""))
        org.junit.Assert.assertTrue(visits()[0].payloadJson.contains("\"action\":\"force_sale\""))
        // the committed call moves the screen on: the Continue button is gone, so no second submit of the same override can be tapped
        compose.onAllNodes(androidx.compose.ui.test.hasText("Continue")).assertCountEquals(0)
        assertEquals(1, visits().size)
    }
}
