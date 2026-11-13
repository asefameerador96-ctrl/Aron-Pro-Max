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

    // ---- Sale data (manual 23) ----
    private fun histMemo(id: String, date: String, vararg l: MemoItem) =
        StoredMemo(id, "sr334001-261110-$id", 1, date, "${date}T04:00:00.000Z", null, l.toList(), emptyList(), emptyList(), 0, 0, 0, 0, 0, 0, 0)
    private val history = com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder.build(
        listOf(histMemo("001", "2026-11-10", MemoItem(1, 20, 6_500, 130_000), MemoItem(2, 10, 6_500, 65_000), MemoItem(3, 20, 6_500, 130_000))), 1,
    )

    @Composable
    private fun SaleData() = ParityFrame(stringResource(com.aktcl.aron.feature.memo.R.string.sale_data_title), null) {
        com.aktcl.aron.feature.memo.ui.SaleDataScreen(history, today = "2026-11-11", outletLabel = null, offlineBanner = false, skuName = { names[it] ?: "" }, skuCategory = { cats[it] })
    }

    @Test fun sale_data_bn() = shot("sr2_sale_data_bn") { SaleData() }
    @Test fun sale_data_sunlight_bn() = shot("sr2_sale_data_sunlight_bn", sunlight = true) { SaleData() }

    // ---- Sales summary with every category incl. the match row (manual 39) ----
    private val sumNames = mapOf(1L to "MaxR-10S", 2L to "MaxR-20S", 3L to "AB-25s", 4L to "Aster", 5L to "SL")
    private val summary = com.aktcl.aron.feature.memo.domain.DaySummaryCalculator.compute(
        listOf(
            com.aktcl.aron.feature.memo.domain.SummaryMemo("a", null, listOf(
                com.aktcl.aron.feature.memo.domain.SummaryLine(1, 10, 80_000), com.aktcl.aron.feature.memo.domain.SummaryLine(2, 20, 160_000), com.aktcl.aron.feature.memo.domain.SummaryLine(3, 25, 20_000),
                com.aktcl.aron.feature.memo.domain.SummaryLine(4, 1, 12_500), com.aktcl.aron.feature.memo.domain.SummaryLine(5, 12, 19_000),
            ), emptyList(), 0, 291_500),
        ),
        cats.mapValues { (id, c) -> com.aktcl.aron.feature.memo.domain.SummarySku(id, c) },
        mapOf(1L to 100L, 2L to 100L, 3L to 100L, 4L to 10L, 5L to 50L),
    )
    private val catNames = mapOf("cigarette" to "সিগারেট", "bidi" to "বিড়ি", "lighter" to "লাইটার", "match" to "ম্যাচ")
    private val unitNames = mapOf("cigarette" to "স্টিক", "bidi" to "স্টিক", "lighter" to "পিস", "match" to "ডজন")

    @Config(qualifiers = "bn-w360dp-h1000dp")
    @Test fun summary_bn() = shot("sr2_summary_bn") {
        ParityFrame(stringResource(com.aktcl.aron.feature.memo.R.string.summary_title), com.aktcl.aron.core.ui.R.drawable.aron_art_sale, printerConnected = true) {
            com.aktcl.aron.feature.memo.ui.SummaryScreen(summary, { sumNames[it] ?: "" }, {}, categoryLabel = { catNames[it] ?: it }, unitLabel = { unitNames[it] ?: "" })
        }
    }

    // ---- Sync and submit (manual 40, 41): business figures, device against server ----
    private fun qty(c: String, base: Long, entered: Long, unit: String) = com.aktcl.aron.feature.dayclose.CategoryQty(c, base, entered, unit)
    private val dev = com.aktcl.aron.feature.dayclose.BusinessTotals(39, listOf(qty("cigarette", 300, 30, "packet"), qty("match", 12, 1, "dozen")), listOf(qty("cigarette", 1000, 100, "packet")), emptyList(), 0)
    private val srv = com.aktcl.aron.feature.dayclose.BusinessTotals(39, listOf(qty("cigarette", 300, 30, "packet"), qty("match", 12, 1, "dozen")), listOf(qty("cigarette", 1000, 100, "packet")), emptyList(), 0)

    private val recLabels = mapOf("visit" to "আউটলেট", "memo" to "বিক্রয়", "stock_movement" to "স্টক", "qc_line" to "কিউসি", "memo_discount" to "প্রমোশন")

    @Composable
    private fun Submit(dues: Int, serverFigures: com.aktcl.aron.feature.dayclose.BusinessTotals?) {
        val counts = listOf(com.aktcl.aron.feature.dayclose.CountRow("memo", 6, 6))
        val gate = com.aktcl.aron.feature.dayclose.SalesSubmitRules.gate(com.aktcl.aron.feature.dayclose.OutboxStates(0, 0, 0, 0, 0), counts, com.aktcl.aron.feature.dayclose.DuesAtSubmit(dues, if (dues > 0) 84_000 else 0), false)
        ParityFrame(stringResource(com.aktcl.aron.feature.dayclose.R.string.submit_title), com.aktcl.aron.core.ui.R.drawable.aron_art_sale) {
            com.aktcl.aron.feature.dayclose.SalesSubmitScreen(
                true, counts, gate, com.aktcl.aron.feature.dayclose.SubmitProgress.Idle, { recLabels[it] ?: it }, {}, {},
                business = dev, serverBusiness = serverFigures, categoryLabel = { catNames[it] ?: it }, vendorDialogs = true, checkoutHint = true,
            )
        }
    }

    @Test fun submit_bn() = shot("sr2_submit_bn") { Submit(0, srv) }
    @Test fun submit_due_bn() = shot("sr2_submit_due_bn") { Submit(1, srv) }

    // ---- Tasks (manual 47): the card, the type in red, no raw codes ----
    private val taskState = com.aktcl.aron.feature.tasks.TaskListState(
        listOf(
            com.aktcl.aron.feature.tasks.TaskItem("t1", "poor_brand_visibility", "Poor Brand Visibility", "রুকু স্টোরে দ্রুত প্রোডাক্ট দেও", 5, "Ruku Store", "2026-11-11", "ongoing", null),
            com.aktcl.aron.feature.tasks.TaskItem("t2", "visit_outlet", "OOS", "Babu Store দোকানে Avon এ OOS আছে", 6, "Babu Store", "2026-11-30", "ongoing", null),
            com.aktcl.aron.feature.tasks.TaskItem("t3", "collect_due", "Due collection", null, 7, "Tasmin", "2026-11-09", "ongoing", null),
        ),
        3, "2026-11-11T04:42:00.000Z",
    )

    @Test fun tasks_bn() = shot("sr2_tasks_bn") {
        ParityFrame("টাস্ক", null) {
            com.aktcl.aron.feature.tasks.TaskContent(taskState, {}, nowMs = java.time.Instant.parse("2026-11-11T05:00:00Z").toEpochMilli(), manual = true)
        }
    }
    @Test fun tasks_empty_bn() = shot("sr2_tasks_empty_bn") {
        ParityFrame("টাস্ক", null) {
            com.aktcl.aron.feature.tasks.TaskContent(com.aktcl.aron.feature.tasks.TaskListState(emptyList(), 0, null, showEmptyState = true), {}, manual = true)
        }
    }
}
