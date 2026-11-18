package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.rules.TextRules
import com.aktcl.aron.feature.outlet.ClusterOption
import com.aktcl.aron.feature.outlet.ClustersContent
import com.aktcl.aron.feature.outlet.GeoPhotoState
import com.aktcl.aron.feature.outlet.OutletMenuContent
import com.aktcl.aron.feature.outlet.OutletRequestForm
import com.aktcl.aron.feature.outlet.OutletRequestKind
import com.aktcl.aron.feature.outlet.RequestError
import com.aktcl.aron.feature.outlet.SrCluster
import com.aktcl.aron.feature.outlet.SrOutletRequestContent
import com.aktcl.aron.feature.outlet.SrRequestTags

import androidx.compose.ui.test.performTextInput
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.core.printing.ui.SaveAndPrintStepDialog
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.ui.CreditDialog
import com.aktcl.aron.feature.sale.ui.CreditTags
import com.aktcl.aron.rules.QtyUnit
import com.aktcl.aron.feature.sale.domain.DrpRule
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.domain.SkuStats
import com.aktcl.aron.feature.sale.ui.QuantityPopup
import com.aktcl.aron.feature.sale.ui.ReviewScreen
import com.aktcl.aron.feature.sale.ui.SaleEntryScreen
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.system.permission.GateAction as A
import com.aktcl.aron.core.system.permission.GatedFeature as G
import com.aktcl.aron.core.system.permission.RuntimePermission as P
import com.aktcl.aron.feature.outlet.CallQuestion
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitGeoInfo
import com.aktcl.aron.feature.outlet.VisitOutlet
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdictResult
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lane P-SR1b goldens: the SR Sale page as SrApp draws it (manual 19, 30, 31, 32): the inline start-call question
 * (relaunch path and with the fix), outside range (ফোর্স সেল and রিফ্রেশ), the force reason. Bangla light, 360 x 800 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrParity1bOutletTests {
    @get:Rule val rule = createComposeRule()
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, fontScale: Float = 1.0f, sunlight: Boolean = false, titleText: String, icon: Int? = null, clicks: List<String> = emptyList(), content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(AppLanguage.BN, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) {
                    ParityFrame(titleText, icon, printerConnected = false) { content() }
                }
            }
        }
        rule.waitForIdle()
        clicks.forEach { if (it.startsWith("#")) rule.onNodeWithTag(it.drop(1)).performClick() else rule.onNodeWithText(it).performClick(); rule.waitForIdle() }
        if (System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("$goldens/$name.png", roborazziOptions = options)
    }

    private fun outlet(id: Long, name: String, cluster: Long = 1, clusterName: String = "Agora Road", due: Long = 0) = OutletEntity(
        outletId = id, routeId = 1, code = "DHK-344-00$id", name = name, nameBn = null, nameSortKey = TextRules.nameSortKey(name), ownerName = "Sample Owner",
        contactNumber = "01700000000", lat = 23.79, lng = 90.40, locationConfirmed = true, provisionalLat = null, provisionalLng = null,
        clusterId = cluster, clusterName = clusterName, channel = "GT", subChannelId = null, geoClass = null, status = "active", priceType = "outlet",
        outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = id.toInt(), openDueMtk = due, openDueAsOf = null,
        programmeFlagsJson = "[]", pendingRequest = false,
    )
    private val outlets = listOf(outlet(1, "Ruku Store", due = 92_000), outlet(2, "Hafiz Store"), outlet(3, "Kamal Store", 2, "Madrasa Road"))
    private val clusters = listOf(ClusterOption(1, "Agora Road"), ClusterOption(2, "Madrasa Road"), ClusterOption(3, "Pink City"), ClusterOption(4, "111 No Road"))

    @Composable private fun Request(
        kind: OutletRequestKind, form: OutletRequestForm = OutletRequestForm(kind), errors: Set<RequestError> = emptySet(), saved: Boolean = false, blocking: String? = null,
    ) = SrOutletRequestContent(
        form, errors, clusters, outlets, GeoPhotoState(), saving = false, savedDialog = saved, onChange = {}, onPickOutlet = {}, onShutter = {}, onSave = {}, onSavedOk = {},
        blockingReason = blocking, onAddToRoute = {},
    )

    private val menuTitle = "আউটলেট"
    private val newTitle = "নতুন দোকান"

    @Test fun menu() = shot("srp1b_outlet_menu", titleText = menuTitle, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet) { OutletMenuContent(onKind = {}) }
    @Test fun menuSunlight() = shot("srp1b_outlet_menu_sunlight", sunlight = true, titleText = menuTitle, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet) { OutletMenuContent(onKind = {}) }
    @Test fun menuFont20() = shot("srp1b_outlet_menu_font20", fontScale = 2.0f, titleText = menuTitle, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet) { OutletMenuContent(onKind = {}) }

    @Test fun newShop() = shot("srp1b_req_new", titleText = newTitle, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new) { Request(OutletRequestKind.NEW) }
    @Test fun newShopErrors() = shot("srp1b_req_new_errors", titleText = newTitle, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new) {
        Request(OutletRequestKind.NEW, errors = setOf(RequestError.CLUSTER_REQUIRED, RequestError.NAME_INVALID))
    }
    @Test fun newShopFilled() = shot("srp1b_req_new_filled", titleText = newTitle, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new) {
        Request(OutletRequestKind.NEW, OutletRequestForm(OutletRequestKind.NEW, name = "Hasan Store", ownerName = "Hasan Ali", mobile = "01711000000", clusterId = 1))
    }
    @Test fun newShopFont20() = shot("srp1b_req_new_font20", fontScale = 2.0f, titleText = newTitle, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new) { Request(OutletRequestKind.NEW) }
    @Test fun newShopSaved() = shot("srp1b_req_new_saved", titleText = newTitle, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_new) { Request(OutletRequestKind.NEW, saved = true) }
    @Test fun closeShop() = shot("srp1b_req_close", titleText = "স্থায়ী বন্ধ", icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_close) { Request(OutletRequestKind.CLOSE) }
    @Test fun closeShopPicked() = shot("srp1b_req_close_picked", titleText = "স্থায়ী বন্ধ", icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_close) {
        Request(OutletRequestKind.CLOSE, OutletRequestForm(OutletRequestKind.CLOSE, outletId = 1, openDueMtk = 92_000))
    }
    @Test fun changeInfo() = shot("srp1b_req_info", titleText = "তথ্য পরিবর্তন করুন", icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_edit) { Request(OutletRequestKind.INFO) }
    @Test fun changeInfoPicked() = shot("srp1b_req_info_picked", titleText = "তথ্য পরিবর্তন করুন", icon = com.aktcl.aron.core.ui.R.drawable.aron_art_outlet_edit) {
        Request(OutletRequestKind.INFO, OutletRequestForm(OutletRequestKind.INFO, outletId = 1, name = "Ruku Store", ownerName = "Sample Owner", mobile = "01700000000", clusterId = 1, currentClusterId = 1))
    }

    private val clusterList = listOf(SrCluster(1, null, "Agora Road", "Agora GoliDaily", false), SrCluster(2, null, "Madrasa Road", "Savar Bazar(Sat, Mon, Wed)", false), SrCluster(null, "u1", "Pink City", "Agora GoliDaily", true))
    @Test fun clusters() = shot("srp1b_clusters", titleText = "ক্লাস্টারসমূহ") { ClustersContent(clusterList, { _, _ -> null }) }
    @Test fun clustersEmpty() = shot("srp1b_clusters_empty", titleText = "ক্লাস্টারসমূহ") { ClustersContent(emptyList(), { _, _ -> null }) }
}
