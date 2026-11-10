package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.amo.AmoFormError
import com.aktcl.aron.feature.amo.AmoFormItem
import com.aktcl.aron.feature.amo.AmoOption
import com.aktcl.aron.feature.amo.AmoOutletFormBody
import com.aktcl.aron.feature.amo.AmoSuccessDialog
import com.aktcl.aron.feature.amo.Pick
import com.aktcl.aron.feature.amo.ShopText
import com.aktcl.aron.feature.amo.UpdateBaseMapContent
import com.aktcl.aron.feature.amo.UpdateBasePointContent
import com.aktcl.aron.feature.amo.changeInfoItems
import com.aktcl.aron.feature.amo.closeItems
import com.aktcl.aron.feature.amo.newShopItems
import com.aktcl.aron.feature.amo.retailerItem
import com.aktcl.aron.feature.amo.routeItem
import com.aktcl.aron.feature.outlet.CapturedPhoto
import com.aktcl.aron.feature.outlet.FixReading
import com.aktcl.aron.feature.outlet.GeoPhotoState
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.feature.outlet.R as OutR

/**
 * Goldens of the AMO Outlet forms (lane android-amo-outlet A4, screenshots 46 to 64) in Bangla, English, 200 % font and Sunlight.
 * Record on Linux only: `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*AmoOutletScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AmoOutletScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun host(language: AppLanguage, title: String, sunlight: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, amo = true) {
                    com.aktcl.aron.feature.amo.AmoFrame(title, onBack = {}) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun check(name: String) = if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists()) else Unit

    private fun shot(name: String, language: AppLanguage, title: String, sunlight: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        host(language, title, sunlight, fontScale, content)
        check(name)
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    /** A dialog is its own window: the golden is the dialog node. */
    private fun dialogShot(name: String, language: AppLanguage, title: String, sunlight: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        host(language, title, sunlight, fontScale, content)
        check(name)
        rule.onNode(isDialog()).captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    // ---- fixtures: the data of the screenshots
    private val none = emptySet<AmoFormError>()
    private val routes = listOf(AmoOption(1, "LaxmipurDaily"), AmoOption(2, "Court Bari(Sun, Tue, Thu)"), AmoOption(3, "Savar BazarDaily"))
    private val clusters = listOf(AmoOption(10, "Kacha Bazar"), AmoOption(11, "Madrasa Road"))
    private val retailers = listOf(AmoOption(100, "Billal Store (2625509-01624788895-Kacha Bazar)"), AmoOption(101, "Babu Store (1618411-01723412771-Madrasa Road)"))
    private fun subs(bn: Boolean) = listOf(AmoOption(1, if (bn) "জিটি" else "GT"), AmoOption(2, if (bn) "পান ঘর" else "Pan shop"))
    private val text = ShopText("Ashraf Store", "Ashraf", "01772369852", {}, {}, {})
    private val emptyText = ShopText("", "", "", {}, {}, {})
    private val photo = GeoPhotoState(CapturedPhoto("p", "/nope.jpg", 1000), FixReading("ok", 23.79, 90.40, 9.0, false), 9, 1)

    private fun newShop(bn: Boolean, picked: Boolean, errors: Set<AmoFormError> = none) = @Composable {
        AmoOutletFormBody(
            items = newShopItems(
                Pick(routes, 1, {}), Pick(clusters, if (picked) 11 else null, {}), if (picked) text else emptyText, Pick(subs(bn), if (picked) 1 else null, {}),
                if (picked) "Urban" else null, {}, if (picked) photo else GeoPhotoState(), {}, errors,
            ),
            onSave = {}, onCancel = null, saveEnabled = picked, modifier = androidx.compose.ui.Modifier,
        )
    }

    private fun closeForm(bn: Boolean) = @Composable {
        AmoOutletFormBody(
            items = closeItems(Pick(clusters, 11, {}), Pick(retailers, 101, {}), "Kamal Store", "Kamal", "1942222558", none), onSave = {},
        )
    }

    private fun infoForm(errors: Set<AmoFormError> = none) = @Composable {
        AmoOutletFormBody(
            items = changeInfoItems(
                Pick(routes, 1, {}), Pick(retailers, 100, {}), AmoFormItem.Letters(listOf("A", "B", "D", "H", "J", "L", "M", "N", "R", "S", "T"), "") {},
                Pick(clusters.take(1), 10, {}, false), ShopText("Billal Store", "Abdur Rashid", "01624788895", {}, {}, {}), photo, {}, errors,
            ),
            onSave = {},
        )
    }

    private fun basePick(canUpdate: Boolean) = @Composable {
        AmoOutletFormBody(
            items = listOf(routeItem(Pick(routes, 1, {}), none), AmoFormItem.Letters(listOf("A", "B", "D", "J", "S", "T"), "") {}, retailerItem(Pick(retailers, if (canUpdate) 100 else null, {}), none)),
            onSave = {}, bottom = { com.aktcl.aron.feature.amo.AmoPrimary(stringResource(AmoR.string.amo_out_base_button), {}, androidx.compose.ui.Modifier, enabled = canUpdate) },
        )
    }

    private fun baseMap() = @Composable {
        UpdateBaseMapContent(
            23.7925, 90.4078, 23.7925 to 90.4078, 100, live = false, onPin = {}, onConfirm = {}, confirmEnabled = true,
            fallback = { UpdateBasePointContent(0, 0, 100, true, { _, _ -> }, {}) },
        )
    }

    // ---- 54, 55 New shop
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_new_shop_route_cluster_bn() = shot("amo_new_shop_route_cluster_bn", AppLanguage.BN, "নতুন দোকান", content = newShop(true, false))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_new_shop_form_bn() = shot("amo_new_shop_form_bn", AppLanguage.BN, "নতুন দোকান", content = newShop(true, true))
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_new_shop_form_en() = shot("amo_new_shop_form_en", AppLanguage.EN, "New shop", content = newShop(false, true))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_new_shop_form_bn_fs2() = shot("amo_new_shop_form_bn_fs2", AppLanguage.BN, "নতুন দোকান", fontScale = 2f, content = newShop(true, true, setOf(AmoFormError.NAME, AmoFormError.MOBILE)))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_new_shop_form_sunlight_bn() = shot("amo_new_shop_form_sunlight_bn", AppLanguage.BN, "নতুন দোকান", sunlight = true, content = newShop(true, true))

    // ---- 49, 51, 53, 56, 58, 61 the success dialog
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_outlet_saved_dialog_bn() = dialogShot("amo_outlet_saved_dialog_bn", AppLanguage.BN, "নতুন দোকান") {
        AmoSuccessDialog(stringResource(AmoR.string.amo_out_saved), stringResource(AmoR.string.amo_ok)) {}
    }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_outlet_updated_dialog_en() = dialogShot("amo_outlet_updated_dialog_en", AppLanguage.EN, "Permanent close") {
        AmoSuccessDialog(stringResource(AmoR.string.amo_out_updated), stringResource(AmoR.string.amo_ok)) {}
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_outlet_saved_dialog_bn_fs2() = dialogShot("amo_outlet_saved_dialog_bn_fs2", AppLanguage.BN, "নতুন দোকান", fontScale = 2f) {
        AmoSuccessDialog(stringResource(AmoR.string.amo_out_saved), stringResource(AmoR.string.amo_ok)) {}
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_outlet_saved_dialog_sunlight_bn() = dialogShot("amo_outlet_saved_dialog_sunlight_bn", AppLanguage.BN, "নতুন দোকান", sunlight = true) {
        AmoSuccessDialog(stringResource(AmoR.string.amo_out_saved), stringResource(AmoR.string.amo_ok)) {}
    }

    // ---- 57 Permanent close and its question
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_close_form_bn() = shot("amo_close_form_bn", AppLanguage.BN, "স্থায়ী বন্ধ", content = closeForm(true))
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_close_form_en() = shot("amo_close_form_en", AppLanguage.EN, "Permanent close", content = closeForm(false))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_close_form_bn_fs2() = shot("amo_close_form_bn_fs2", AppLanguage.BN, "স্থায়ী বন্ধ", fontScale = 2f, content = closeForm(true))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_close_form_sunlight_bn() = shot("amo_close_form_sunlight_bn", AppLanguage.BN, "স্থায়ী বন্ধ", sunlight = true, content = closeForm(true))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_close_confirm_bn() = dialogShot("amo_close_confirm_bn", AppLanguage.BN, "স্থায়ী বন্ধ") {
        AronConfirmDialog(stringResource(OutR.string.out_req_confirm_title), stringResource(OutR.string.out_req_confirm_close), stringResource(OutR.string.out_req_yes), stringResource(OutR.string.out_req_no), {}, {})
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_close_confirm_bn_fs2() = dialogShot("amo_close_confirm_bn_fs2", AppLanguage.BN, "স্থায়ী বন্ধ", fontScale = 2f) {
        AronConfirmDialog(stringResource(OutR.string.out_req_confirm_title), stringResource(OutR.string.out_req_confirm_close), stringResource(OutR.string.out_req_yes), stringResource(OutR.string.out_req_no), {}, {})
    }

    // ---- 59, 60, 61 Change information
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_info_form_bn() = shot("amo_info_form_bn", AppLanguage.BN, "তথ্য পরিবর্তন করুন", content = infoForm())
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_info_form_en() = shot("amo_info_form_en", AppLanguage.EN, "Change information", content = infoForm())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_info_form_bn_fs2() = shot("amo_info_form_bn_fs2", AppLanguage.BN, "তথ্য পরিবর্তন করুন", fontScale = 2f, content = infoForm(setOf(AmoFormError.MOBILE)))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_info_form_sunlight_bn() = shot("amo_info_form_sunlight_bn", AppLanguage.BN, "তথ্য পরিবর্তন করুন", sunlight = true, content = infoForm())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_info_photo_done_dialog_bn() = dialogShot("amo_info_photo_done_dialog_bn", AppLanguage.BN, "তথ্য পরিবর্তন করুন") {
        AmoSuccessDialog(stringResource(AmoR.string.amo_out_photo_done), stringResource(AmoR.string.amo_ok)) {}
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_info_confirm_bn() = dialogShot("amo_info_confirm_bn", AppLanguage.BN, "তথ্য পরিবর্তন করুন") {
        AronConfirmDialog(stringResource(OutR.string.out_req_confirm_title), stringResource(OutR.string.out_req_confirm_message), stringResource(OutR.string.out_req_yes), stringResource(OutR.string.out_req_no), {}, {})
    }

    // ---- 62, 63, 64 Update Base
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_base_pick_bn() = shot("amo_base_pick_bn", AppLanguage.BN, "বেস রিক্যালিব্রেশন", content = basePick(false))
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_base_pick_en() = shot("amo_base_pick_en", AppLanguage.EN, "Base Recalibration", content = basePick(true))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_base_pick_bn_fs2() = shot("amo_base_pick_bn_fs2", AppLanguage.BN, "বেস রিক্যালিব্রেশন", fontScale = 2f, content = basePick(true))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_base_pick_sunlight_bn() = shot("amo_base_pick_sunlight_bn", AppLanguage.BN, "বেস রিক্যালিব্রেশন", sunlight = true, content = basePick(true))
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_base_confirm_bn() = dialogShot("amo_base_confirm_bn", AppLanguage.BN, "বেস রিক্যালিব্রেশন") {
        AronConfirmDialog(stringResource(AmoR.string.amo_base_confirm_title), "", stringResource(AmoR.string.amo_base_confirm_yes), stringResource(AmoR.string.amo_base_confirm_no), {}, {})
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_base_map_bn() = shot("amo_base_map_bn", AppLanguage.BN, "অবস্থান নিশ্চিত করুন", content = baseMap())
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_base_map_en() = shot("amo_base_map_en", AppLanguage.EN, "Confirm location", content = baseMap())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_base_map_bn_fs2() = shot("amo_base_map_bn_fs2", AppLanguage.BN, "অবস্থান নিশ্চিত করুন", fontScale = 2f, content = baseMap())
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_base_map_sunlight_bn() = shot("amo_base_map_sunlight_bn", AppLanguage.BN, "অবস্থান নিশ্চিত করুন", sunlight = true, content = baseMap())

    // ---- 46 menu in English (the Bangla, 200 % and Sunlight goldens are in AmoTsoScreenshotTests under their old names)
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_outlet_hub_en() = shot("amo_outlet_hub_en", AppLanguage.EN, "Outlet") {
        com.aktcl.aron.feature.amo.AmoOutletMenuContent(mapOf(com.aktcl.aron.feature.amo.VerifyGroup.NEW to 3), {}, {}, onOwn = {})
    }
}
