package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.programmes.AsthaMenuScreen
import com.aktcl.aron.feature.programmes.AsthaModel
import com.aktcl.aron.feature.programmes.AsthaSelection
import com.aktcl.aron.feature.programmes.AsthaTargetScreen
import com.aktcl.aron.feature.programmes.LoyaltyMenuScreen
import com.aktcl.aron.feature.programmes.LoyaltyModel
import com.aktcl.aron.feature.programmes.OutletPointsScreen
import com.aktcl.aron.feature.programmes.GiftChoiceModel
import com.aktcl.aron.feature.programmes.GiftSelectionScreen
import com.aktcl.aron.feature.programmes.LocalGiftChoice
import com.aktcl.aron.feature.programmes.RedemptionScreen
import com.aktcl.aron.feature.programmes.SaveOutcome
import com.aktcl.aron.feature.programmes.ShopInfo
import com.aktcl.aron.feature.programmes.ShopOutlet
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.programmes.R as ProgR
import com.aktcl.aron.rules.UiLocale
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
 * Goldens of the SR Astha screens (lane android-sr-programmes, manual 48 to 50): menu, target table, empty, Sunlight and font scale 2.0.
 * Same frame, tier B, 360 x 800 dp. Record on Linux only: `-Daron.screenshots.recordTo=src/test/screenshots` (docs/status/android-core-ui.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrProgrammesScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage = AppLanguage.BN, sunlight: Boolean = false, fontScale: Float = 1.0f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { content() }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") {
            assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        }
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    @Composable private fun Frame(title: Int = ProgR.string.pg_astha_title, content: @Composable () -> Unit) = AronScreen(topBar = { AronTopBar(title = stringResource(title), onBack = {}) }) { content() }

    private val programmes = """{"astha_as_of":"2026-11-17T10:00:00Z","astha_truncated":false,"astha_targets":[
      {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"21600","std_achieved":"4680","memo_target":0,"memo_achieved":0,"value_target_mtk":"86400000","value_achieved_mtk":"18720000","by_pack":[{"pack_factor":20,"std_achieved":"3600"},{"pack_factor":10,"std_achieved":"1080"}]},
      {"route_id":7,"outlet_id":null,"brand_id":12,"month":"2026-11","std_target":"10800","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"43200000","value_achieved_mtk":"0","by_pack":[]},
      {"route_id":7,"outlet_id":null,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":884,"memo_achieved":787,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}"""
    private val products = """{"nodes":[{"id":11,"level":"brand","name":"Marise","name_bn":"মারিস","sort":1},{"id":12,"level":"brand","name":"Avon","name_bn":"অ্যাভন","sort":2},{"id":21,"level":"variant","name":"M","parent_id":11,"sort":1},{"id":22,"level":"variant","name":"A","parent_id":12,"sort":1}],"skus":[{"sku_id":901,"variant_id":21,"category_code":"cigarette","base_per_pack":20},{"sku_id":902,"variant_id":22,"category_code":"cigarette","base_per_pack":20}]}"""
    private val data get() = AsthaModel.parse(programmes, products)
    private val picked = AsthaSelection(2026, 4, "2026-11")

    @Test fun astha_menu_bn() = shot("astha_menu_bn") { Frame { AsthaMenuScreen({}, null) } }
    @Test fun astha_target_bn_std() = shot("astha_target_bn_std") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = picked) } }
    @Test fun astha_target_bn_memo() = shot("astha_target_bn_memo") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = picked.copy(memoTab = true)) } }
    @Test fun astha_target_en_std() = shot("astha_target_en_std", AppLanguage.EN) { Frame { AsthaTargetScreen(data, UiLocale.EN, 2026, initial = picked) } }
    @Test fun astha_target_bn_sunlight() = shot("astha_target_bn_sunlight", sunlight = true) { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = picked) } }
    @Test fun astha_target_bn_font20() = shot("astha_target_bn_font20", fontScale = 2.0f) { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = picked) } }
    @Test fun astha_target_bn_empty_no_programme() = shot("astha_target_bn_empty_no_programme") { Frame { AsthaTargetScreen(null, UiLocale.BN, 2026) } }
    @Test fun astha_target_bn_empty_month() = shot("astha_target_bn_empty_month") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, initial = AsthaSelection(2026, 1, "2026-01")) } }

    private val shops = listOf(
        ShopInfo(501, "Ajij Tea Store (3700634-01716184335-Pink City Road)", "3700634", "Md.Ajij", "01716184335", "Gold"),
        ShopInfo(502, "Rubel Tea Store (2681254-01334363419-Agora Road, Gulshan-2)", "2681254", "Abdul Mannan", "01334363419", "Diamond"),
    )
    @Test fun astha_shops_bn() = shot("astha_shops_bn") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, shops = shops, startOnShops = true) } }
    @Test fun astha_shops_bn_empty() = shot("astha_shops_bn_empty") { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, shops = emptyList(), startOnShops = true) } }
    @Test fun astha_shops_bn_font20() = shot("astha_shops_bn_font20", fontScale = 2.0f) { Frame { AsthaTargetScreen(data, UiLocale.BN, 2026, shops = shops, startOnShops = true) } }

    // Loyalty Point (manual 53 left, 55): the bundle's real balances, an overdrawn one shown plainly
    private val loyaltyJson = """{"programmes":[{"programme_id":3,"kind":"diamond_league","name_en":"Diamond League","name_bn":"ডায়মন্ড লীগ","period_label":"2026-04"}],
      "loyalty":[{"outlet_id":10,"programme_id":3,"league_label":"Diamond League (April)","balance_points":110,"overdrawn":false,"expiring_points":110,"expiry_date":"2026-05-07","as_of_date":"2026-04-17"},
                 {"outlet_id":11,"programme_id":3,"league_label":"Diamond League (April)","balance_points":-20,"overdrawn":true,"expiring_points":0,"expiry_date":null,"as_of_date":"2026-04-17"}],"gifts":[]}"""
    private val pointOutlets = listOf(ShopOutlet(10, "Bipu Store (6909084-01779223366-Apsis Cluster)", "6909084", "A", null, null), ShopOutlet(11, "Milon Store (2663519)", "2663519", "B", null, null))
    private val loyalty get() = LoyaltyModel.parse(loyaltyJson)

    @Test fun loyalty_menu_bn() = shot("loyalty_menu_bn") { Frame(HomeR.string.hm_tile_loyalty_point) { LoyaltyMenuScreen(onPoints = {}, onRedeem = null) } }
    @Test fun outlet_points_bn() = shot("outlet_points_bn") { Frame(ProgR.string.pg_loyalty_points) { OutletPointsScreen(pointOutlets, loyalty, UiLocale.BN, onRedeem = {}, initialOutlet = 10) } }
    @Test fun outlet_points_bn_overdrawn() = shot("outlet_points_bn_overdrawn") { Frame(ProgR.string.pg_loyalty_points) { OutletPointsScreen(pointOutlets, loyalty, UiLocale.BN, onRedeem = {}, initialOutlet = 11) } }
    @Test fun outlet_points_en_sunlight() = shot("outlet_points_en_sunlight", AppLanguage.EN, sunlight = true) { Frame(ProgR.string.pg_loyalty_points) { OutletPointsScreen(pointOutlets, loyalty, UiLocale.EN, initialOutlet = 10) } }
    @Test fun outlet_points_bn_font20() = shot("outlet_points_bn_font20", fontScale = 2.0f) { Frame(ProgR.string.pg_loyalty_points) { OutletPointsScreen(pointOutlets, loyalty, UiLocale.BN, onRedeem = {}, initialOutlet = 10) } }
    @Test fun outlet_points_bn_empty() = shot("outlet_points_bn_empty") { Frame(ProgR.string.pg_loyalty_points) { OutletPointsScreen(pointOutlets, null, UiLocale.BN) } }

    // Gift redemption (manual 53 right): balance 220, gifts with a cost; an overdrawn basket is flagged, not blocked
    private val redeemJson = """{"programmes":[{"programme_id":3,"kind":"diamond_league","name_en":"Diamond League","period_label":"2026-04"}],
      "loyalty":[{"outlet_id":10,"programme_id":3,"league_label":"Diamond League (April)","balance_points":220,"overdrawn":false,"expiring_points":0,"expiry_date":null,"as_of_date":"2026-04-17"}],
      "gifts":[{"gift_id":1,"programme_id":3,"name_en":"2 Step Kitchen Rack","name_bn":"২ স্টেপ কিচেন র‍্যাক।","points_cost":200,"status":"active"},
               {"gift_id":2,"programme_id":3,"name_en":"Chair","name_bn":"চেয়ার।","points_cost":200,"status":"active"},
               {"gift_id":3,"programme_id":3,"name_en":"Tornado Fan","name_bn":"টর্নেডো ফ্যান।","points_cost":400,"status":"active"}]}"""
    private val redeemOutlets = listOf(ShopOutlet(10, "Bipu Store (6909084-01779223366-Apsis Cluster)", "6909084", "A", null, null, chip = "B", routeId = 7))
    private val redeem get() = LoyaltyModel.parse(redeemJson)

    @Composable private fun Redeem(qty: Map<Long, Int> = emptyMap(), locale: UiLocale = UiLocale.BN, data: com.aktcl.aron.feature.programmes.LoyaltyData? = redeem) =
        Frame(ProgR.string.pg_loyalty_redeem) { RedemptionScreen(redeemOutlets, data, locale, { _, _, b -> b }, { _, _ -> SaveOutcome.SAVED }, initialOutlet = 10, initialQty = qty) }

    @Test fun redemption_bn() = shot("redemption_bn") { Redeem() }
    @Test fun redemption_bn_basket() = shot("redemption_bn_basket") { Redeem(mapOf(1L to 1)) }
    @Test fun redemption_bn_overdrawn() = shot("redemption_bn_overdrawn") { Redeem(mapOf(3L to 1)) }
    @Test fun redemption_en_sunlight() = shot("redemption_en_sunlight", AppLanguage.EN, sunlight = true) { Redeem(mapOf(1L to 1), UiLocale.EN) }
    @Test fun redemption_bn_font20() = shot("redemption_bn_font20", fontScale = 2.0f) { Redeem(mapOf(1L to 1)) }
    @Test fun redemption_bn_empty() = shot("redemption_bn_empty") { Redeem(data = null) }

    // Astha gift selection (manual 52)
    private val giftJson = """{"programmes":[{"programme_id":5,"kind":"astha","name_en":"Astha"}],"loyalty":[],"gifts":[
        {"gift_id":1,"programme_id":5,"name_en":"Hotpot- 3 bati","name_bn":"হটপট- ৩ বাটি","points_cost":null,"tier_codes":["gold"],"status":"active"},
        {"gift_id":2,"programme_id":5,"name_en":"Feature Phone","name_bn":"ফিচার ফোন","points_cost":null,"tier_codes":[],"status":"active"}],
      "gift_assignments":[{"gift_assignment_id":2,"quarter":"2026-Q4","outlet_id":11,"route_id":7,"gift_id":2,"photo_captured":true,"locked":true}]}"""
    private val giftShops = listOf(
        ShopInfo(10, "Milon Store (2663519)", "2663519", "Milon", null, "Gold", chip = "M", tier = "gold", routeId = 7, routeName = "Agora GoliDaily", clusterName = "Agora Goli, Gulshan-2"),
        ShopInfo(11, "Locked Store (1234567)", "1234567", "B", null, "Gold", chip = "L", routeId = 7, tier = "gold"),
    )
    @Composable private fun Gift(outlet: Long, picked: Long? = null, local: List<LocalGiftChoice> = emptyList(), data: com.aktcl.aron.feature.programmes.GiftChoiceData? = GiftChoiceModel.parse(giftJson)) =
        Frame(ProgR.string.pg_astha_tile_gift) { GiftSelectionScreen(giftShops, data, "2026-Q4", local, UiLocale.BN, { _, _ -> SaveOutcome.SAVED }, initialOutlet = outlet, initialGift = picked) }

    @Test fun gift_selection_bn() = shot("gift_selection_bn") { Gift(10) }
    @Test fun gift_selection_bn_picked() = shot("gift_selection_bn_picked") { Gift(10, picked = 1) }
    @Test fun gift_selection_bn_on_phone() = shot("gift_selection_bn_on_phone") { Gift(10, local = listOf(LocalGiftChoice(10, "2026-Q4", 1, "pending"))) }
    @Test fun gift_selection_bn_locked() = shot("gift_selection_bn_locked") { Gift(11) }
    @Test fun gift_selection_bn_font20() = shot("gift_selection_bn_font20", fontScale = 2.0f) { Gift(10, picked = 1) }
    @Test fun gift_selection_bn_empty() = shot("gift_selection_bn_empty") { Gift(10, data = null) }

    // Photo capture menu and Astha gift photo (manual 56, 57)
    private val photoJson = """{"programmes":[{"programme_id":5,"kind":"astha","name_en":"Astha"}],"loyalty":[],"gifts":[
        {"gift_id":1,"programme_id":5,"name_en":"Hotpot- 3 bati","name_bn":"হটপট- ৩ বাটি","points_cost":null,"tier_codes":[],"status":"active"}],
      "gift_assignments":[{"gift_assignment_id":71,"quarter":"2026-Q4","outlet_id":10,"route_id":7,"gift_id":1,"photo_captured":false,"locked":false},
                          {"gift_assignment_id":72,"quarter":"2026-Q4","outlet_id":11,"route_id":7,"gift_id":1,"photo_captured":true,"locked":true}]}"""
    @Composable private fun PhotoPage(outlet: Long, state: com.aktcl.aron.feature.outlet.GeoPhotoState = com.aktcl.aron.feature.outlet.GeoPhotoState(), locale: UiLocale = UiLocale.BN, data: com.aktcl.aron.feature.programmes.GiftChoiceData? = GiftChoiceModel.parse(photoJson)) =
        Frame(ProgR.string.pg_photo_astha) {
            com.aktcl.aron.feature.programmes.GiftPhotoScreen(
                giftShops, data, "2026-Q4", emptyList(), locale, outlet, {}, photoBlock = { com.aktcl.aron.feature.outlet.GeoPhotoBlock(state, {}) },
                canSubmit = state.complete, checking = state.busy, onSubmit = { _, _, _ -> SaveOutcome.SAVED },
            )
        }
    private val shot1 = com.aktcl.aron.feature.outlet.GeoPhotoState(
        photo = com.aktcl.aron.feature.outlet.CapturedPhoto("p1", "/none.jpg", 48_000), fix = com.aktcl.aron.feature.outlet.FixReading("ok", 23.79, 90.40, 11.6, false), accuracyMeters = 12,
    )

    @Test fun photo_menu_bn() = shot("photo_menu_bn") { Frame(ProgR.string.pg_photo_astha) { com.aktcl.aron.feature.programmes.PhotoMenuScreen({}, {}) } }
    @Test fun photo_astha_bn() = shot("photo_astha_bn") { PhotoPage(10) }
    @Test fun photo_astha_bn_taken() = shot("photo_astha_bn_taken") { PhotoPage(10, shot1) }
    @Test fun photo_astha_bn_checking() = shot("photo_astha_bn_checking") { PhotoPage(10, com.aktcl.aron.feature.outlet.GeoPhotoState(busy = true)) }
    @Test fun photo_astha_bn_captured() = shot("photo_astha_bn_captured") { PhotoPage(11) }
    @Test fun photo_astha_en_sunlight() = shot("photo_astha_en_sunlight", AppLanguage.EN, sunlight = true) { PhotoPage(10, shot1, UiLocale.EN) }
    @Test fun photo_astha_bn_font20() = shot("photo_astha_bn_font20", fontScale = 2.0f) { PhotoPage(10, shot1) }
    @Test fun photo_astha_bn_empty() = shot("photo_astha_bn_empty") { PhotoPage(10, data = null) }

    // Campaign gift photo (manual 58)
    private val campaignGifts = listOf(com.aktcl.aron.feature.programmes.LoyaltyGift(4, 3, "Chair", "চেয়ার", 200, emptyList(), null, true))
    @Composable private fun Campaign(groups: List<com.aktcl.aron.feature.programmes.CampaignGroup>, ready: Boolean = false, locale: UiLocale = UiLocale.BN, state: com.aktcl.aron.feature.outlet.GeoPhotoState = com.aktcl.aron.feature.outlet.GeoPhotoState()) =
        Frame(ProgR.string.pg_photo_campaign) {
            com.aktcl.aron.feature.programmes.CampaignPhotoScreen(
                giftShops, groups, campaignGifts, locale, 10, {}, slot = { _, _ -> com.aktcl.aron.feature.outlet.GeoPhotoBlock(state, {}) }, canSubmit = { ready }, onSubmit = { SaveOutcome.SAVED },
            )
        }
    private val campaignGroup = com.aktcl.aron.feature.programmes.CampaignGroup("r1", 10, 4, 3, setOf(1))

    @Test fun campaign_photo_bn() = shot("campaign_photo_bn") { Campaign(listOf(campaignGroup)) }
    @Test fun campaign_photo_bn_taken() = shot("campaign_photo_bn_taken") { Campaign(listOf(campaignGroup), ready = true, state = shot1) }
    @Test fun campaign_photo_en_sunlight() = shot("campaign_photo_en_sunlight", AppLanguage.EN, sunlight = true) { Campaign(listOf(campaignGroup), ready = true, locale = UiLocale.EN, state = shot1) }
    @Test fun campaign_photo_bn_font20() = shot("campaign_photo_bn_font20", fontScale = 2.0f) { Campaign(listOf(campaignGroup), state = shot1) }
    @Test fun campaign_photo_bn_empty() = shot("campaign_photo_bn_empty") { Campaign(emptyList()) }
}
