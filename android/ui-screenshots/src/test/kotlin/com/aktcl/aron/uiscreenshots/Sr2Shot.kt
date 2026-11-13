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
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.MemoMenu
import com.aktcl.aron.feature.memo.domain.StoredMemo
import com.aktcl.aron.feature.memo.ui.MemoDetailView
import com.aktcl.aron.feature.memo.ui.MemoSwitchOption
import com.aktcl.aron.feature.memo.ui.MemoSwitcher
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.aktcl.aron.feature.home.R as HomeR

/**
 * Lane P-SR2 (android-parity-sr-2) goldens: the SR screens of the second half of the journey in the host's own configuration,
 * next to the owner's manual images (docs/ui-reference/sr/manual). Record on Linux only: `-Paron.screenshots.recordTo=src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class Sr2Shot {
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

    // ---- Memo of a credit sale (manual 38) ----
    private val names = mapOf(1L to "MaxR-10S", 2L to "MaxR-20S", 3L to "AB-25s", 4L to "Aster", 5L to "SL")
    private val cats = mapOf(1L to "cigarette", 2L to "cigarette", 3L to "bidi", 4L to "lighter", 5L to "match")
    private val creditMemo = StoredMemo(
        "m1", "sr334001-261105-001", 1, "2026-11-05", "2026-11-05T04:00:00.000Z", null,
        listOf(
            MemoItem(1, 10, 8_000, 80_000, 10, "stick"), MemoItem(2, 20, 8_000, 160_000, 20, "stick"), MemoItem(3, 25, 800, 20_000, 25, "stick"),
            MemoItem(4, 1, 12_500, 12_500, 1, "piece"), MemoItem(5, 12, 1_583, 19_000, 12, "piece"),
        ), emptyList(), emptyList(), 291_500, 0, 0, 0, 291_500, 0, 291_500,
    )

    @Composable
    private fun CreditMemo(paidDialog: Boolean, canMarkPaid: Boolean = true) = ParityFrame(stringResource(HomeR.string.hm_tile_memo), com.aktcl.aron.core.ui.R.drawable.aron_art_sale, printerConnected = true) {
        MemoDetailView(
            MemoMenu.detail(creditMemo), canMarkPaid, 291_500, { names[it] ?: "" }, {}, {}, {}, skuCategory = { cats[it] }, skuUnit = { "" },
            outletName = "Tasmin (1618415)", dashedDueBox = true, dueSideBySide = true, subtotalQuantities = true, letterFilter = true,
            switcher = MemoSwitcher(listOf(MemoSwitchOption("m1", "Tasmin (1618415)", 291_500, 291_500)), "m1") {},
            detailBanner = stringResource(com.aktcl.aron.feature.memo.R.string.memo_banner_manual), buttonsInRow = true,
            paidDialog = paidDialog, onPaidDialogOk = {}, vendorLayout = true,
        )
    }

    @Test fun edit_reason_bn() = shot("sr2_edit_reason_bn") { ParityFrame(stringResource(HomeR.string.hm_tile_memo), com.aktcl.aron.core.ui.R.drawable.aron_art_sale) { com.aktcl.aron.feature.sale.ui.EditReasonDialog({}, {}) } }
    @Config(qualifiers = "bn-w360dp-h1400dp") @Test fun memo_credit_bn() = shot("sr2_memo_credit_bn") { CreditMemo(false) }
    @Config(qualifiers = "bn-w360dp-h1400dp") @Test fun memo_paid_bn() = shot("sr2_memo_paid_bn") { CreditMemo(true) }
    @Test fun memo_credit_font20_bn() = shot("sr2_memo_credit_bn_font20", fontScale = 2.0f) { CreditMemo(false) }
}
